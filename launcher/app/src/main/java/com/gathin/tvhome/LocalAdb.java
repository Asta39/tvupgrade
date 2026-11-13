package com.gathin.tvhome;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;

/**
 * A small ADB client that talks to this TV's own debugging port (127.0.0.1:5555), for first-run setup (this fork).
 * With USB / network debugging switched on, the app can run the same shell commands scripts/grant.sh and
 * install-launcher.sh run from a computer: grant itself its permissions, make itself Home, turn packages off.
 * The first connection makes the TV ask "Allow USB debugging?"; the key is kept in the app's private files.
 * Plain ADB only: Android 11's TLS "Wireless debugging" is not spoken here.
 */
final class LocalAdb implements AutoCloseable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    /** The ASN.1 prefix of a SHA-1 DigestInfo: ADB signs its 20-byte token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private LocalAdb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Whether anything listens on the debugging port at all (debugging off: connection refused). */
    static boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Connects and authenticates. Blocks up to waitForApprovalMs while the TV shows its "Allow USB debugging?"
     * prompt the first time. Throws when debugging is off, or the prompt was not accepted in time.
     */
    static LocalAdb connect(Context ctx, int waitForApprovalMs) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setSoTimeout(8000);
        LocalAdb adb = new LocalAdb(s);
        try {
            adb.handshake(ctx, waitForApprovalMs);
            return adb;
        } catch (Exception e) {
            adb.close();
            throw e;
        }
    }

    private void handshake(Context ctx, int waitForApprovalMs) throws Exception {
        KeyPair keys = keys(ctx);
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Message m = read();
            if (m.command == CNXN) return;
            if (m.command != AUTH) throw new IOException("unexpected reply to connect");
            if (!signed) {
                signed = true;
                send(AUTH, 2, 0, sign(keys.getPrivate(), m.data));
            } else {
                // The signature was not known: offer the public key, and wait for the prompt on screen.
                socket.setSoTimeout(waitForApprovalMs);
                send(AUTH, 3, 0, publicKey((RSAPublicKey) keys.getPublic()));
                Message ok = read();
                socket.setSoTimeout(8000);
                if (ok.command != CNXN) throw new IOException("debugging was not allowed");
                return;
            }
        }
    }

    /** Runs one shell command and returns what it printed. */
    String shell(String command) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int remote = 0;
        socket.setSoTimeout(30000);
        while (true) {
            Message m = read();
            if (m.arg1 != local && m.command != CLSE) continue;
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                text.write(m.data);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.command == CLSE) {
                if (remote != 0 || m.arg1 == local) send(CLSE, local, m.arg0, new byte[0]);
                break;
            }
        }
        return new String(text.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- wire format -----------------------------------------------------------------------------------

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        out.write(head.array());
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        if (length < 0 || length > (1 << 20)) throw new IOException("bad ADB packet");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    // ---- keys ------------------------------------------------------------------------------------------

    private static KeyPair keys(Context ctx) throws Exception {
        File file = new File(ctx.getFilesDir(), "adbkey");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (file.exists()) {
            PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(file.toPath())));
            java.security.interfaces.RSAPrivateCrtKey crt = (java.security.interfaces.RSAPrivateCrtKey) priv;
            return new KeyPair(factory.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())), priv);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Files.write(file.toPath(), pair.getPrivate().getEncoded());
        return pair;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, key);
        rsa.update(SHA1_PREFIX);
        return rsa.doFinal(token);
    }

    /** The key as adbd wants it: Android's own RSAPublicKey struct (little-endian words), base64, then a name. */
    private static byte[] publicKey(RSAPublicKey key) {
        final int words = 2048 / 32;
        BigInteger n = key.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhome@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger value, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) {
            b.putInt(value.and(mask).intValue());
            value = value.shiftRight(32);
        }
    }

}
