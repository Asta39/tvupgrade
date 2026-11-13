package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Guest mode's phone page (this fork): a small web server on the TV itself, on the home network only, port 8686.
 * Phones open it from the QR code on the welcome screen. No computer, no cloud.
 * <ul>
 * <li>Claim: the first phone that brings the 6-digit code shown on the TV becomes the host and sets a PIN.
 * Seeing the TV's screen is the proof of being the owner (Android does not let apps read the saved Wi-Fi password).</li>
 * <li>Host (PIN): the place, Wi-Fi, house rules, door code, the guests' names and dates, allowed apps, the guest
 * remote, Home or Guest mode, and the lock that keeps the TV on Guest mode.</li>
 * <li>Guest (open, on the Wi-Fi): the welcome, Wi-Fi, house rules, the door code during the stay, and the remote when
 * the host allows it.</li>
 * </ul>
 * Everything is written to stay.json (Stay), which the welcome screen re-reads at once.
 */
final class HostServer {
    static final int PORT = 8686;
    private static HostServer running;
    /** The code the TV shows while nobody has claimed it; null once a host has. */
    static volatile String claimCode;
    /** Called on the main thread after the phone changed stay.json. */
    static volatile Runnable onChange;

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = Collections.synchronizedMap(new HashMap<>());
    private int failures;
    private long lockedUntil;
    private LocalAdb adb;

    private HostServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Starts the server once per process (Guest mode's screen and setup call this). */
    static synchronized void start(Context ctx) {
        if (running != null) return;
        HostServer s = new HostServer(ctx);
        running = s;
        if (Stay.json(ctx).optString("pin_sha256", "").isEmpty()) newClaimCode();
        Thread t = new Thread(s::serve, "host-server");
        t.setDaemon(true);
        t.start();
    }

    static void newClaimCode() {
        claimCode = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    /** http://<this TV on the home network>:8686/, or null without a network. */
    static String url(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) {
                InetAddress ip = a.getAddress();
                if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) return "http://" + ip.getHostAddress() + ":" + PORT + "/";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void serve() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            while (true) {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            synchronized (HostServer.class) {
                running = null;
            }
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------------------

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(10000);
            InputStream in = socket.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0], path = first[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (length > 65536) {
                reply(socket.getOutputStream(), 413, "text/plain", "too large");
                return;
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject json = new JSONObject();
            if (length > 0) {
                try {
                    json = new JSONObject(new String(body, 0, read, StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
            boolean host = authorized(headers.get("x-session"));
            route(socket.getOutputStream(), method, path, json, host);
        } catch (Exception ignored) {
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev3 = 0, c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            prev3 = (prev3 << 8 | c) & 0xFFFFFFFF;
            if (prev3 == 0x0D0A0D0A) break;
            if (b.size() > 16384) return null;
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }

    private void route(OutputStream out, String method, String path, JSONObject body, boolean host) throws Exception {
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            try (InputStream page = ctx.getAssets().open("web/index.html")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = page.read(buf)) > 0) b.write(buf, 0, n);
                reply(out, 200, "text/html; charset=utf-8", b.toByteArray());
            }
            return;
        }
        switch (method + " " + path) {
            case "GET /api/state":
                json(out, 200, state(host));
                return;
            case "POST /api/claim":
                claim(out, body);
                return;
            case "POST /api/login":
                login(out, body);
                return;
            case "GET /api/host":
                if (!host) break;
                JSONObject j = Stay.json(ctx);
                j.remove("pin_sha256");
                json(out, 200, j);
                return;
            case "POST /api/host":
                if (!host) break;
                save(body);
                json(out, 200, new JSONObject().put("ok", true));
                return;
            case "POST /api/pin":
                if (!host) break;
                String pin = body.optString("pin", "").trim();
                if (!pin.matches("\\d{4,8}")) {
                    json(out, 400, new JSONObject().put("error", "The PIN must be 4 to 8 digits."));
                    return;
                }
                JSONObject s = Stay.json(ctx);
                s.put("pin_sha256", Stay.sha256(pin));
                Stay.write(ctx, s);
                json(out, 200, new JSONObject().put("ok", true));
                return;
            case "GET /api/apps":
                if (!host) break;
                json(out, 200, new JSONObject().put("apps", apps()));
                return;
            case "POST /api/remote":
                if (!host && !guestRemote()) break;
                json(out, 200, remote(body, host));
                return;
            default:
                reply(out, 404, "text/plain", "not found");
                return;
        }
        json(out, 401, new JSONObject().put("error", "Sign in as the host first."));
    }

    private static void json(OutputStream out, int code, JSONObject j) throws Exception {
        reply(out, code, "application/json; charset=utf-8", j.toString());
    }

    private static void reply(OutputStream out, int code, String type, String body) throws Exception {
        reply(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(OutputStream out, int code, String type, byte[] body) throws Exception {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : code == 429 ? "Too Many Requests" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // ---- host sign-in ----------------------------------------------------------------------------------

    private boolean authorized(String token) {
        if (token == null || token.isEmpty()) return false;
        Long until = sessions.get(token);
        return until != null && until > System.currentTimeMillis();
    }

    private String newSession() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        StringBuilder t = new StringBuilder();
        for (byte x : b) t.append(String.format("%02x", x));
        sessions.put(t.toString(), System.currentTimeMillis() + 30L * 24 * 3600_000);
        return t.toString();
    }

    /** One wrong guess after another is slowed down: five misses lock sign-in for five minutes. */
    private synchronized boolean throttled() {
        return System.currentTimeMillis() < lockedUntil;
    }

    private synchronized void missed() {
        if (++failures >= 5) {
            failures = 0;
            lockedUntil = System.currentTimeMillis() + 5 * 60_000;
        }
    }

    private void claim(OutputStream out, JSONObject body) throws Exception {
        if (throttled()) {
            json(out, 429, new JSONObject().put("error", "Too many tries. Wait five minutes."));
            return;
        }
        JSONObject s = Stay.json(ctx);
        if (!s.optString("pin_sha256", "").isEmpty() || claimCode == null) {
            json(out, 400, new JSONObject().put("error", "This TV already has a host. Sign in with the PIN."));
            return;
        }
        String pin = body.optString("pin", "").trim();
        if (!claimCode.equals(body.optString("code", "").trim())) {
            missed();
            json(out, 400, new JSONObject().put("error", "That code doesn't match the one on the TV."));
            return;
        }
        if (!pin.matches("\\d{4,8}")) {
            json(out, 400, new JSONObject().put("error", "Choose a PIN of 4 to 8 digits."));
            return;
        }
        s.put("pin_sha256", Stay.sha256(pin));
        if (!s.has("mode")) s.put("mode", "guest");
        Stay.write(ctx, s);
        claimCode = null;
        changed();
        json(out, 200, new JSONObject().put("session", newSession()));
    }

    private void login(OutputStream out, JSONObject body) throws Exception {
        if (throttled()) {
            json(out, 429, new JSONObject().put("error", "Too many tries. Wait five minutes."));
            return;
        }
        String hash = Stay.json(ctx).optString("pin_sha256", "");
        if (hash.isEmpty() || !hash.equals(Stay.sha256(body.optString("pin", "").trim()))) {
            missed();
            json(out, 400, new JSONObject().put("error", "Wrong PIN."));
            return;
        }
        json(out, 200, new JSONObject().put("session", newSession()));
    }

    // ---- data ------------------------------------------------------------------------------------------

    /** What any phone on the Wi-Fi may see: the guest's view. The host also gets claimed/mode flags. */
    private JSONObject state(boolean host) throws Exception {
        JSONObject s = Stay.json(ctx);
        Stay stay = Stay.load(ctx);
        JSONObject o = new JSONObject()
                .put("claimed", !s.optString("pin_sha256", "").isEmpty())
                .put("host", host)
                .put("mode", stay.guestMode ? "guest" : "home")
                .put("place", stay.place).put("about", stay.about)
                .put("wifi_name", stay.wifiName).put("wifi_password", stay.wifiPassword)
                .put("rules", new JSONArray(stay.rules))
                .put("checkin", Stay.friendly(stay.checkin)).put("checkout", Stay.friendly(stay.checkout))
                .put("staying", stay.staying()).put("names", stay.names)
                .put("door_code", stay.doorCode)
                .put("night", stay.night).put("nights", stay.nights)
                .put("arriving", stay.arrivingToday()).put("leaving", stay.leavingToday())
                .put("remote", guestRemote() || host)
                .put("keys", adbReady());
        o.put("apps", allowedApps());
        return o;
    }

    /** Fields the host page may change; anything else in the body is ignored. */
    private void save(JSONObject b) throws Exception {
        JSONObject s = Stay.json(ctx);
        for (String k : new String[]{"place", "about", "wifi_name", "wifi_password", "checkin", "checkout", "door_code"}) {
            if (b.has(k)) s.put(k, b.optString(k, "").trim());
        }
        for (String k : new String[]{"rules", "apps"}) if (b.has(k) && b.optJSONArray(k) != null) s.put(k, b.optJSONArray(k));
        if (b.has("guest")) {
            JSONObject g = b.optJSONObject("guest");
            if (g == null || g.optString("names").trim().isEmpty()) s.remove("guest");
            else s.put("guest", new JSONObject().put("names", g.optString("names").trim())
                    .put("arrive", g.optString("arrive")).put("depart", g.optString("depart")));
        }
        if (b.has("locked")) s.put("locked", b.optBoolean("locked"));
        if (b.has("guest_remote")) s.put("guest_remote", b.optBoolean("guest_remote"));
        if (b.has("mode")) s.put("mode", "home".equals(b.optString("mode")) ? "home" : "guest");
        Stay.write(ctx, s);
        changed();
    }

    private void changed() {
        main.post(() -> {
            Runnable r = onChange;
            if (r != null) r.run();
            else if (Stay.guestMode(ctx)) {
                ctx.startActivity(new Intent(ctx, GuestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
    }

    private boolean guestRemote() {
        JSONObject s = Stay.json(ctx);
        return s.optBoolean("guest_remote", true) && "guest".equals(s.optString("mode"));
    }

    private JSONArray apps() throws Exception {
        JSONArray a = new JSONArray();
        for (Setup.App app : Setup.apps(ctx)) a.put(new JSONObject().put("pkg", app.pkg).put("label", app.label));
        return a;
    }

    private JSONArray allowedApps() throws Exception {
        PackageManager pm = ctx.getPackageManager();
        JSONArray out = new JSONArray();
        JSONArray allowed = Stay.json(ctx).optJSONArray("apps");
        if (allowed == null) return out;
        for (int i = 0; i < allowed.length(); i++) {
            String pkg = allowed.optString(i);
            try {
                out.put(new JSONObject().put("pkg", pkg)
                        .put("label", String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)))));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return out;
    }

    // ---- remote ----------------------------------------------------------------------------------------

    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        KEYS.put("up", 19);
        KEYS.put("down", 20);
        KEYS.put("left", 21);
        KEYS.put("right", 22);
        KEYS.put("ok", 23);
        KEYS.put("back", 4);
        KEYS.put("home", 3);
        KEYS.put("playpause", 85);
        KEYS.put("volup", 24);
        KEYS.put("voldown", 25);
        KEYS.put("mute", 164);
    }

    /** Keys go through the TV's own ADB (granted in setup); opening an allowed app needs nothing. */
    private JSONObject remote(JSONObject b, boolean host) throws Exception {
        String app = b.optString("app", "");
        if (!app.isEmpty()) {
            boolean ok = host;
            JSONArray allowed = Stay.json(ctx).optJSONArray("apps");
            if (allowed != null) for (int i = 0; i < allowed.length(); i++) if (app.equals(allowed.optString(i))) ok = true;
            if (!ok) return new JSONObject().put("error", "That app isn't allowed.");
            PackageManager pm = ctx.getPackageManager();
            Intent go = pm.getLeanbackLaunchIntentForPackage(app);
            if (go == null) go = pm.getLaunchIntentForPackage(app);
            if (go == null) return new JSONObject().put("error", "Not installed.");
            Intent launch = go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            main.post(() -> ctx.startActivity(launch));
            return new JSONObject().put("ok", true);
        }
        Integer code = KEYS.get(b.optString("key"));
        if (code == null) return new JSONObject().put("error", "Unknown key.");
        return new JSONObject().put("ok", key(code));
    }

    private synchronized boolean key(int code) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (adb == null) adb = LocalAdb.connect(ctx, 3000);
                adb.shell("input keyevent " + code);
                return true;
            } catch (Exception e) {
                android.util.Log.w("HostServer", "remote key failed", e);
                if (adb != null) adb.close();
                adb = null;
            }
        }
        return false;
    }

    private boolean adbReady() {
        return LocalAdb.portOpen();
    }
}
