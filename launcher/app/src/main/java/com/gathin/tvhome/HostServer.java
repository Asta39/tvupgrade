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
    /** When a phone last opened the page, and who to tell (the welcome screen hides its QR code). */
    static volatile long lastVisit;
    static volatile Runnable onVisit;

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
        // Every ten minutes: sign guests out of the apps they signed in to, an hour after their check-out.
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread x = new Thread(r, "auto-sign-out");
            x.setDaemon(true);
            return x;
        }).scheduleWithFixedDelay(s::autoSignOut, 1, 10, java.util.concurrent.TimeUnit.MINUTES);
    }

    private void autoSignOut() {
        try {
            JSONObject j = Stay.json(ctx);
            long at = j.optLong("signout_at", 0);
            if (at == 0 || System.currentTimeMillis() < at) return;
            if (j.optBoolean("auto_signout", true) && !signins().isEmpty()) {
                JSONObject r = signOut("");
                if (r.has("error")) return; // try again in ten minutes
            }
            JSONObject after = Stay.json(ctx);
            after.remove("signout_at");
            Stay.write(ctx, after);
            changed();
        } catch (Exception e) {
            android.util.Log.w("HostServer", "auto sign-out failed", e);
        }
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
            String method = first[0], path = first[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
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
            if ("GET".equals(method) && "/api/backdrop".equals(path)) {
                backdrop(socket.getOutputStream());
                return;
            }
            if ("GET".equals(method) && "/api/now/art".equals(path)) {
                nowArt(socket.getOutputStream());
                return;
            }
            if ("GET".equals(method) && "/api/icon".equals(path)) {
                icon(socket.getOutputStream(), param(query, "pkg"));
                return;
            }
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
            lastVisit = System.currentTimeMillis();
            main.post(() -> {
                Runnable r = onVisit;
                if (r != null) r.run();
            });
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
                // A guest's phone on check-in day: the TV plays its welcome, once per stay.
                if (!host) maybeArrival();
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
                // The AI key is write-only: the page only learns whether one is set.
                j.put("ai_configured", !j.optString("ai_key", "").isEmpty());
                j.remove("ai_key");
                // Keys from an earlier version of party mode, no longer used.
                j.remove("spotify_id");
                j.remove("spotify_secret");
                json(out, 200, j);
                return;
            case "POST /api/watch/search":
                if (!host && !guestRemote()) break;
                json(out, 200, Watch.search(ctx, body.optString("q", "")));
                return;
            case "POST /api/watch/discover":
                if (!host && !guestRemote()) break;
                json(out, 200, Watch.discover(ctx, body.optString("genre"), body.optInt("offset")));
                return;
            case "GET /api/now":
                if (!host && !guestRemote()) break;
                json(out, 200, now());
                return;
            case "POST /api/watch/play":
                if (!host && !guestRemote()) break;
                JSONObject played = Watch.play(ctx, body.optString("id"));
                json(out, 200, played);
                return;
            case "GET /api/accounts":
                if (!host && !guestRemote()) break;
                json(out, 200, accounts(host));
                return;
            case "POST /api/accounts/signin":
                if (!host && !guestRemote()) break;
                json(out, 200, signIn(body.optString("pkg"), host));
                return;
            case "GET /api/dashboard":
                if (!host && !"guest".equals(Stay.json(ctx).optString("mode"))) break;
                json(out, 200, dashboard(host));
                return;
            // ---- watch together: one shared swipe deck, a match when everyone likes the same title (Together)
            case "POST /api/group":
                if (!host && !guestRemote()) break;
                json(out, 200, Together.state(body.optString("me")));
                return;
            case "POST /api/group/start":
                if (!host && !guestRemote()) break;
                json(out, 200, Together.start(ctx, body.optString("name")));
                return;
            case "POST /api/group/join":
                if (!host && !guestRemote()) break;
                json(out, 200, Together.join(body.optString("name")));
                return;
            case "POST /api/group/leave":
                if (!host && !guestRemote()) break;
                json(out, 200, Together.leave(body.optString("me")));
                return;
            case "POST /api/group/vote":
                if (!host && !guestRemote()) break;
                json(out, 200, Together.vote(ctx, body.optString("me"), body.optString("key"), body.optBoolean("like")));
                return;
            case "POST /api/group/again":
                if (!host && !guestRemote()) break;
                json(out, 200, Together.again(body.optString("me")));
                return;
            case "POST /api/group/play":
                if (!host && !guestRemote()) break;
                JSONObject gp = Together.play(ctx, body.optString("key"));
                if (!gp.has("error")) Together.end();
                json(out, 200, gp);
                return;
            // ---- party mode: a shared music queue on the TV's Spotify (Party)
            case "GET /api/continue":
                if (!host && !guestRemote()) break;
                json(out, 200, new JSONObject().put("items", continueWatching(host)));
                return;
            case "POST /api/party/discover":
                if (!host && !guestRemote()) break;
                try {
                    json(out, 200, Party.discover(body.optString("seed")));
                } catch (java.io.IOException e) {
                    json(out, 200, new JSONObject().put("error", "Song search isn’t answering. Try again."));
                }
                return;
            case "POST /api/party/replay":
                if (!host && !guestRemote()) break;
                json(out, 200, Party.add(ctx, body.optString("id"), body.optString("name"), body.optString("phone")));
                return;
            case "POST /api/ime":
                if (!host && !guestRemote()) break;
                json(out, 200, ime(body));
                return;
            case "POST /api/party":
                if (!host && !guestRemote()) break;
                json(out, 200, Party.state(ctx, body.optString("phone")));
                return;
            case "POST /api/party/search":
                if (!host && !guestRemote()) break;
                try {
                    json(out, 200, Party.search(body.optString("q")));
                } catch (java.io.IOException e) {
                    json(out, 200, new JSONObject().put("error", "Song search isn’t answering. Try again."));
                }
                return;
            case "POST /api/party/add":
                if (!host && !guestRemote()) break;
                json(out, 200, Party.add(ctx, body.optString("id"), body.optString("name"), body.optString("phone")));
                return;
            case "POST /api/party/remove":
                if (!host && !guestRemote()) break;
                json(out, 200, Party.remove(body.optString("id"), body.optString("phone"), host));
                return;
            case "POST /api/party/skip":
                if (!host && !guestRemote()) break;
                json(out, 200, Party.skip(ctx));
                return;
            case "POST /api/party/lyrics":
                if (!host && !guestRemote()) break;
                Party.lyrics(ctx);
                json(out, 200, new JSONObject().put("ok", true));
                return;
            case "POST /api/party/stop":
                if (!host) break;
                json(out, 200, Party.stop());
                return;
            case "POST /api/arrival/play":
                if (!host) break;
                arrivalShow();
                json(out, 200, new JSONObject().put("ok", true));
                return;
            case "POST /api/accounts/signout":
                if (!host && !guestRemote()) break;
                json(out, 200, signOut(body.optString("pkg", "")));
                return;
            case "POST /api/kids/add":
                if (!host) break;
                json(out, 200, kidsAdd(body));
                return;
            case "POST /api/kids/remove":
                if (!host) break;
                JSONObject k = Stay.json(ctx);
                JSONArray shows = k.optJSONArray("kids_shows");
                int at = body.optInt("index", -1);
                if (shows != null && at >= 0 && at < shows.length()) shows.remove(at);
                Stay.write(ctx, k);
                changed();
                json(out, 200, new JSONObject().put("ok", true));
                return;
            case "POST /api/ask":
                if (!host && !"guest".equals(Stay.json(ctx).optString("mode"))) break;
                json(out, 200, ask(body.optString("q", "")));
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
                .put("mode", stay.kids ? "kids" : stay.guestMode ? "guest" : "home")
                .put("place", stay.place).put("about", stay.about)
                .put("wifi_name", stay.wifiName).put("wifi_password", stay.wifiPassword)
                .put("rules", new JSONArray(stay.rules))
                .put("checkin", Stay.friendly(stay.checkin)).put("checkout", Stay.friendly(stay.checkout)).put("checkout_time", stay.checkout)
                .put("staying", stay.staying()).put("names", stay.names)
                .put("door_code", stay.doorCode)
                .put("night", stay.night).put("nights", stay.nights)
                .put("arriving", stay.arrivingToday()).put("leaving", stay.leavingToday())
                .put("remote", guestRemote() || host)
                .put("keys", adbReady())
                .put("ai", !s.optString("ai_key", "").isEmpty())
                .put("weather", weather())
                .put("checkout_day", stay.staying() ? stay.depart.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) : "")
                .put("arrive", stay.staying() ? stay.arrive.toString() : "")
                .put("depart", stay.staying() ? stay.depart.toString() : "");
        o.put("apps", allowedApps());
        o.put("signed_in", signedInLabels());
        // Changes with every install, so an open phone tab can tell it is running an older page and reload.
        try {
            o.put("build", ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        o.put("auto_signout", s.optBoolean("auto_signout", true) && s.optLong("signout_at", 0) > 0);
        // Party mode shows whenever the TV has Spotify: nothing to set up.
        o.put("music", Party.installed(ctx));
        return o;
    }

    // ---- the phone's Home dashboard -------------------------------------------------------------------

    /**
     * Everything the phone's Home shows beyond the stay (Dashboard caches each part): the next hours' weather with
     * sunrise and sunset, tonight's plan, what's popular on the TV's apps, what was last played during this stay,
     * and the song party mode is playing.
     */
    private JSONObject dashboard(boolean host) throws Exception {
        JSONObject o = new JSONObject();
        JSONObject w = Dashboard.weather();
        if (w != null) {
            JSONObject cur = w.optJSONObject("current"), daily = w.optJSONObject("daily"), hourly = w.optJSONObject("hourly");
            JSONObject ow = new JSONObject().put("city", Config.weatherCity);
            if (cur != null) ow.put("temp", Math.round(cur.optDouble("temperature_2m"))).put("text", Weather.describe(cur.optInt("weather_code")))
                    .put("code", cur.optInt("weather_code")).put("day", cur.optInt("is_day", 1) == 1);
            if (daily != null) ow.put("hi", Math.round(daily.optJSONArray("temperature_2m_max").optDouble(0)))
                    .put("lo", Math.round(daily.optJSONArray("temperature_2m_min").optDouble(0)))
                    .put("sunrise", daily.optJSONArray("sunrise")).put("sunset", daily.optJSONArray("sunset"));
            if (hourly != null) {
                // The next six hours, from the hour after now.
                String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH"));
                JSONArray t = hourly.optJSONArray("time"), hours = new JSONArray();
                int start = -1;
                for (int i = 0; t != null && i < t.length(); i++) if (t.optString(i).startsWith(now)) start = i + 1;
                for (int i = start; start > 0 && i < t.length() && hours.length() < 6; i++) {
                    hours.put(new JSONObject().put("time", t.optString(i)).put("temp", Math.round(hourly.optJSONArray("temperature_2m").optDouble(i)))
                            .put("code", hourly.optJSONArray("weather_code").optInt(i)).put("rain", hourly.optJSONArray("precipitation_probability").optInt(i))
                            .put("day", hourly.optJSONArray("is_day").optInt(i, 1) == 1));
                }
                ow.put("hours", hours);
            }
            o.put("weather", ow);
        }
        JSONArray plan = Dashboard.tonight(ctx);
        if (plan != null) o.put("tonight", plan);
        JSONArray trending = Dashboard.trending(ctx);
        if (trending != null) o.put("trending", trending);
        JSONArray cont = continueWatching(host);
        if (cont.length() > 0) {
            o.put("continue", cont);
            JSONObject r = cont.getJSONObject(0);
            o.put("recent", new JSONObject(r.toString()));
        }
        JSONObject music = Party.current();
        if (music != null) o.put("music", new JSONObject().put("title", music.optString("title")).put("artist", music.optString("artist"))
                .put("art", music.optString("art")).put("by", music.optString("by")).put("next", Party.queued()));
        return o;
    }

    /**
     * Continue watching, newest first. Guests see only what was played during their stay (from the check-in day), or
     * in the last twelve hours when no stay is set, so the next guests never see it; the host sees everything.
     */
    private JSONArray continueWatching(boolean host) throws Exception {
        Stay stay = Stay.load(ctx);
        long since = host ? 0 : stay.staying()
                ? stay.arrive.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                : System.currentTimeMillis() - 12 * 3600_000L;
        JSONArray list = Watch.continueList(ctx, since);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            o.put("app", label(o.optString("pkg")));
            o.remove("pkg");
        }
        return list;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    // ---- the arrival show ------------------------------------------------------------------------------

    /**
     * The first guest phone on check-in day starts the welcome on the TV (ArrivalActivity). "arrival_shown" keeps the
     * stay it played for, so it plays once per stay however many phones open the page.
     */
    private void maybeArrival() {
        try {
            JSONObject s = Stay.json(ctx);
            Stay stay = Stay.load(ctx);
            if (!s.optBoolean("arrival_show", true) || !stay.arrivingToday() || !"guest".equals(s.optString("mode"))) return;
            JSONObject g = s.optJSONObject("guest");
            String key = stay.arrive + "|" + (g == null ? "" : g.optString("names"));
            synchronized (this) {
                if (key.equals(s.optString("arrival_shown"))) return;
                s.put("arrival_shown", key);
                Stay.write(ctx, s);
            }
            arrivalShow();
        } catch (Exception e) {
            android.util.Log.w("HostServer", "arrival show", e);
        }
    }

    private void arrivalShow() {
        main.post(() -> ctx.startActivity(new Intent(ctx, ArrivalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    /** Fields the host page may change; anything else in the body is ignored. */
    private void save(JSONObject b) throws Exception {
        JSONObject s = Stay.json(ctx);
        for (String k : new String[]{"place", "about", "wifi_name", "wifi_password", "checkin", "checkout", "door_code"}) {
            if (b.has(k)) s.put(k, b.optString(k, "").trim());
        }
        for (String k : new String[]{"rules", "apps", "kids_apps"}) if (b.has(k) && b.optJSONArray(k) != null) s.put(k, b.optJSONArray(k));
        if (b.has("kids_name")) s.put("kids_name", b.optString("kids_name", "").trim());
        if (b.has("guest")) {
            JSONObject g = b.optJSONObject("guest");
            if (g == null || g.optString("names").trim().isEmpty()) s.remove("guest");
            else s.put("guest", new JSONObject().put("names", g.optString("names").trim())
                    .put("arrive", g.optString("arrive")).put("depart", g.optString("depart")));
        }
        if (b.has("locked")) s.put("locked", b.optBoolean("locked"));
        if (b.has("auto_signout")) s.put("auto_signout", b.optBoolean("auto_signout"));
        // The AI key: set when a new one is sent, removed on request, otherwise kept as it is.
        if (!b.optString("ai_key", "").trim().isEmpty()) s.put("ai_key", b.optString("ai_key").trim());
        if (b.optBoolean("ai_key_clear")) s.remove("ai_key");
        if (b.has("arrival_show")) s.put("arrival_show", b.optBoolean("arrival_show"));
        if (b.has("guest_remote")) s.put("guest_remote", b.optBoolean("guest_remote"));
        if (b.has("mode")) {
            String m = b.optString("mode");
            s.put("mode", "home".equals(m) || "kids".equals(m) ? m : "guest");
        }
        Stay.write(ctx, s);
        changed();
    }

    /** A show from a search goes on the Kids screen: its picture and the link the search found (Watch). */
    private JSONObject kidsAdd(JSONObject b) throws Exception {
        String[] link = Watch.link(b.optString("id"));
        if (link == null) return new JSONObject().put("error", "Search again, then add it.");
        JSONObject s = Stay.json(ctx);
        JSONArray shows = s.optJSONArray("kids_shows");
        if (shows == null) shows = new JSONArray();
        if (shows.length() >= 24) return new JSONObject().put("error", "Kids mode holds 24 shows. Remove one first.");
        shows.put(new JSONObject().put("title", b.optString("title")).put("poster", b.optString("poster"))
                .put("pkg", link[0]).put("link", link[1]));
        s.put("kids_shows", shows);
        Stay.write(ctx, s);
        changed();
        return new JSONObject().put("ok", true);
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

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    /** An installed app's icon as a PNG, for the phone page's app buttons. Icons are not private. */
    private void icon(OutputStream out, String pkg) throws Exception {
        try {
            android.graphics.drawable.Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(c);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            reply(out, 200, "image/png", png.toByteArray());
        } catch (Exception e) {
            reply(out, 404, "text/plain", "no icon");
        }
    }

    // ---- now playing and the backdrop, for the phone's remote -------------------------------------------

    /** The media session that is playing, else the first one; null without the notification-listener grant. */
    private android.media.session.MediaController playing() {
        try {
            android.media.session.MediaSessionManager m = ctx.getSystemService(android.media.session.MediaSessionManager.class);
            java.util.List<android.media.session.MediaController> list =
                    m.getActiveSessions(new android.content.ComponentName(ctx, GuardRevival.class));
            android.media.session.MediaController first = null;
            for (android.media.session.MediaController c : list) {
                if (c.getMetadata() == null) continue;
                if (first == null) first = c;
                if (c.getPlaybackState() != null && c.getPlaybackState().getState() == android.media.session.PlaybackState.STATE_PLAYING) return c;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject now() throws Exception {
        android.media.session.MediaController c = playing();
        if (c == null) return new JSONObject().put("playing", false).put("title", "");
        android.media.MediaMetadata m = c.getMetadata();
        android.media.session.PlaybackState st = c.getPlaybackState();
        String title = m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = m.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String sub = m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        if (sub == null) sub = m.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String app;
        try {
            app = String.valueOf(ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(c.getPackageName(), 0)));
        } catch (Exception e) {
            app = "";
        }
        long pos = st == null ? 0 : st.getPosition();
        if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return new JSONObject().put("title", title == null ? "" : title).put("sub", sub == null ? "" : sub).put("app", app)
                .put("playing", st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING)
                .put("position", Math.max(0, pos)).put("duration", Math.max(0, m.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)))
                .put("art", artOf(m) != null);
    }

    private static android.graphics.Bitmap artOf(android.media.MediaMetadata m) {
        for (String k : new String[]{android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, android.media.MediaMetadata.METADATA_KEY_ART,
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            android.graphics.Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private void nowArt(OutputStream out) throws Exception {
        android.media.session.MediaController c = playing();
        android.graphics.Bitmap b = c == null || c.getMetadata() == null ? null : artOf(c.getMetadata());
        if (b == null) {
            reply(out, 404, "text/plain", "no art");
            return;
        }
        jpeg(out, b, 360);
    }

    /** The picture behind the TV's welcome screen, small: the phone blurs it into its background. */
    private void backdrop(OutputStream out) throws Exception {
        android.graphics.Bitmap b = WallpaperPlayer.shownBitmap;
        if (b == null || b.isRecycled()) {
            reply(out, 404, "text/plain", "no picture");
            return;
        }
        jpeg(out, b, 320);
    }

    private static void jpeg(OutputStream out, android.graphics.Bitmap src, int width) throws Exception {
        android.graphics.Bitmap sw = src.getConfig() == android.graphics.Bitmap.Config.HARDWARE ? src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) : src;
        int h = Math.max(1, Math.round(sw.getHeight() * (width / (float) sw.getWidth())));
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(sw, width, h, true);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, b);
        if (sw != src) sw.recycle();
        if (small != sw && small != src) small.recycle();
        reply(out, 200, "image/jpeg", b.toByteArray());
    }

    // ---- weather, for the phone's welcome --------------------------------------------------------------

    private JSONObject weather;
    private long weatherAt;

    private synchronized JSONObject weather() {
        if (weather != null && System.currentTimeMillis() - weatherAt < 30 * 60_000) return weather;
        if (Config.weatherLat == 0 && Config.weatherLon == 0) return new JSONObject();
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + Config.weatherLat + "&longitude=" + Config.weatherLon
                    + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto";
            JSONObject j = new JSONObject(get(url, null, null));
            JSONObject now = j.getJSONObject("current");
            JSONObject day = j.getJSONObject("daily");
            int code = now.optInt("weather_code");
            weather = new JSONObject().put("temp", Math.round(now.optDouble("temperature_2m")))
                    .put("text", Weather.describe(code))
                    .put("hi", Math.round(day.getJSONArray("temperature_2m_max").optDouble(0)))
                    .put("lo", Math.round(day.getJSONArray("temperature_2m_min").optDouble(0)))
                    .put("city", Config.weatherCity);
            weatherAt = System.currentTimeMillis();
        } catch (Exception e) {
            if (weather == null) weather = new JSONObject();
        }
        return weather;
    }

    // ---- ask about the area (Groq) ---------------------------------------------------------------------

    /** Groq retires models often: try these in order, skipping any Groq says it no longer serves. */
    static final String[] MODELS = {"openai/gpt-oss-20b", "openai/gpt-oss-120b", "llama-3.3-70b-versatile"};
    private final java.util.ArrayDeque<Long> asks = new java.util.ArrayDeque<>();

    private JSONObject ask(String question) throws Exception {
        question = question.trim();
        if (question.isEmpty() || question.length() > 300) return new JSONObject().put("error", "Ask a short question.");
        String key = Stay.json(ctx).optString("ai_key", "");
        if (key.isEmpty()) return new JSONObject().put("error", "The host hasn't turned this on.");
        synchronized (asks) {
            long now = System.currentTimeMillis();
            while (!asks.isEmpty() && now - asks.peekFirst() > 10 * 60_000) asks.pollFirst();
            if (asks.size() >= 30) return new JSONObject().put("error", "Lots of questions just now. Try again in a few minutes.");
            asks.addLast(now);
        }
        Stay stay = Stay.load(ctx);
        String where = Config.weatherCity.isEmpty() ? "" : " in or near " + Config.weatherCity;
        String system = "You are a friendly local concierge for guests staying at " + (stay.place.isEmpty() ? "a holiday home" : stay.place)
                + where + ". Answer in at most 80 words, plainly, without markdown tables or headings. Suggest specific, real places "
                + "when you know them, and tell guests to check opening hours. For questions about the house, use only these facts: "
                + "check-in from " + Stay.friendly(stay.checkin) + ", check-out by " + Stay.friendly(stay.checkout)
                + (stay.rules.isEmpty() ? "" : ", house rules: " + String.join("; ", stay.rules)) + ". "
                + "Never invent door codes, Wi-Fi passwords or prices; say the host can help instead.";
        String last = "The assistant is unavailable right now.";
        for (String model : MODELS) {
            JSONObject req = new JSONObject().put("model", model).put("temperature", 0.5).put("max_tokens", 400)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", question)));
            if (model.startsWith("openai/gpt-oss")) req.put("reasoning_effort", "low");
            try {
                JSONObject res = new JSONObject(get("https://api.groq.com/openai/v1/chat/completions", req.toString(), key));
                String text = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
                if (!text.isEmpty()) return new JSONObject().put("answer", text);
            } catch (java.io.IOException e) {
                last = e.getMessage() == null ? last : e.getMessage();
                if (last.startsWith("401")) return new JSONObject().put("error", "The host's AI key isn't working.");
                // 400/404 for a retired model: try the next one; anything else is not about the model.
                if (!last.startsWith("400") && !last.startsWith("404")) break;
            }
        }
        return new JSONObject().put("error", "The assistant is unavailable right now.");
    }

    /** GET, or POST a JSON body with a bearer key; throws "<status> ..." for HTTP errors. */
    static String get(String url, String body, String bearer) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) try (InputStream i = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
        }
        String text = b.toString("UTF-8");
        if (code >= 400) throw new java.io.IOException(code + " " + text.substring(0, Math.min(200, text.length())));
        return text;
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
        KEYS.put("next", 87);
        KEYS.put("previous", 88);
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
        String k = b.optString("key");
        // Ten seconds back or on, through the playing app's own controls (keys can't say "ten seconds").
        if ("back10".equals(k) || "fwd10".equals(k)) {
            android.media.session.MediaController c = playing();
            if (c == null || c.getPlaybackState() == null) return new JSONObject().put("error", "Nothing is playing.");
            long pos = c.getPlaybackState().getPosition() + ("fwd10".equals(k) ? 10_000 : -10_000);
            c.getTransportControls().seekTo(Math.max(0, pos));
            return new JSONObject().put("ok", true);
        }
        Integer code = KEYS.get(k);
        if (code == null) return new JSONObject().put("error", "Unknown key.");
        return new JSONObject().put("ok", key(code));
    }

    /**
     * Typing from the phone's own keyboard. {"check":true} says whether the TV is showing its on-screen keyboard (a text
     * box has focus); {"text":"…"} types into it, {"del":n} deletes n characters, {"enter":true} submits it.
     */
    private JSONObject ime(JSONObject b) throws Exception {
        if (b.optBoolean("check")) {
            String d = tvShell("dumpsys input_method | grep -E 'mInputShown=|inputType=0x' | head -2");
            if (d == null) return new JSONObject().put("error", "The TV isn't answering.");
            boolean shown = d.contains("mInputShown=true");
            // Password boxes (text variation 0x80, 0xe0, number 0x10): the phone hides what's typed.
            boolean secret = false;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("inputType=0x([0-9a-f]+)").matcher(d);
            if (m.find()) {
                int t = (int) Long.parseLong(m.group(1), 16), cls = t & 0xf, var = t & 0xff0;
                secret = cls == 1 && (var == 0x80 || var == 0xe0 || var == 0x90) || cls == 2 && var == 0x10;
            }
            return new JSONObject().put("shown", shown).put("secret", secret);
        }
        String text = b.optString("text", "");
        if (!text.isEmpty()) {
            if (text.length() > 200) return new JSONObject().put("error", "That's too long.");
            // input text: %s is a space; everything goes in single quotes for the shell.
            String arg = text.replace("%", "\\%").replace(" ", "%s").replace("'", "'\\''");
            return new JSONObject().put("ok", tvShell("input text '" + arg + "'") != null);
        }
        int del = Math.min(100, b.optInt("del", 0));
        if (del > 0) {
            StringBuilder c = new StringBuilder("input keyevent");
            for (int i = 0; i < del; i++) c.append(" 67");
            // The keyboard app takes deletes a moment after they're sent: wait, so the next letters don't arrive first.
            c.append("; sleep ").append(del > 5 ? "0.4" : "0.15");
            return new JSONObject().put("ok", tvShell(c.toString()) != null);
        }
        if (b.optBoolean("enter")) return new JSONObject().put("ok", key(66));
        return new JSONObject().put("error", "Nothing to type.");
    }

    private synchronized String tvShell(String command) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (adb == null) adb = LocalAdb.connect(ctx, 3000);
                return adb.shell(command);
            } catch (Exception e) {
                if (adb != null) adb.close();
                adb = null;
            }
        }
        return null;
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

    // ---- signing in to streaming apps from the phone ----------------------------------------------------------
    // The TV can't ask an app whether someone is signed in, so we remember the apps someone started signing in to
    // from the phone ("signins" in stay.json). Signing out clears that app's data, the only way that works for every
    // app: it signs out whoever is signed in to it, the host included.

    private JSONObject accounts(boolean host) throws Exception {
        java.util.Set<String> mine = signins();
        java.util.Set<String> allowed = new java.util.HashSet<>();
        JSONArray a = Stay.json(ctx).optJSONArray("apps");
        if (a != null) for (int i = 0; i < a.length(); i++) allowed.add(a.optString(i));
        PackageManager pm = ctx.getPackageManager();
        JSONArray list = new JSONArray();
        for (Map.Entry<String, String> e : Watch.SIGN_IN.entrySet()) {
            String pkg = e.getKey();
            if (!host && !allowed.contains(pkg)) continue;
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                list.put(new JSONObject().put("pkg", pkg).put("label", label.toString()).put("url", e.getValue())
                        .put("signed_in", mine.contains(pkg)));
            } catch (PackageManager.NameNotFoundException notHere) {
                // not on this TV
            }
        }
        return new JSONObject().put("apps", list).put("can_sign_out", adbReady());
    }

    private JSONObject signIn(String pkg, boolean host) throws Exception {
        if (!Watch.SIGN_IN.containsKey(pkg)) return new JSONObject().put("error", "That app can't sign in from the phone.");
        JSONObject opened = remote(new JSONObject().put("app", pkg), host);
        if (opened.has("error")) return opened;
        JSONObject s = Stay.json(ctx);
        java.util.Set<String> mine = signins();
        mine.add(pkg);
        s.put("signins", new JSONArray(mine));
        // Signed in during a stay: due to be signed out an hour after that stay's check-out.
        Stay stay = Stay.load(ctx);
        if (stay.staying()) {
            long at = stay.depart.atTime(Stay.time(stay.checkout)).plusHours(1)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            s.put("signout_at", Math.max(at, s.optLong("signout_at", 0)));
        }
        Stay.write(ctx, s);
        return new JSONObject().put("ok", true).put("url", Watch.SIGN_IN.get(pkg));
    }

    /** Signs out of one app, or of every app signed in from the phone when pkg is empty. */
    private JSONObject signOut(String pkg) throws Exception {
        java.util.Set<String> mine = signins();
        java.util.List<String> targets = new java.util.ArrayList<>();
        if (pkg.isEmpty()) targets.addAll(mine);
        else if (Watch.SIGN_IN.containsKey(pkg)) targets.add(pkg);
        else return new JSONObject().put("error", "Unknown app.");
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (String p : targets) {
            if (shell("am force-stop " + p + "; pm clear " + p).contains("Success")) mine.remove(p);
            else failed.add(p);
        }
        JSONObject s = Stay.json(ctx);
        s.put("signins", new JSONArray(mine));
        Stay.write(ctx, s);
        if (!failed.isEmpty()) return new JSONObject().put("error", "Couldn't sign out. Debugging must be on in the TV's setup.");
        return new JSONObject().put("ok", true).put("count", targets.size());
    }

    private java.util.Set<String> signins() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        JSONArray a = Stay.json(ctx).optJSONArray("signins");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private JSONArray signedInLabels() {
        JSONArray out = new JSONArray();
        PackageManager pm = ctx.getPackageManager();
        for (String p : signins()) {
            try {
                out.put(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString());
            } catch (PackageManager.NameNotFoundException gone) {
                // uninstalled since
            }
        }
        return out;
    }

    private synchronized String shell(String command) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (adb == null) adb = LocalAdb.connect(ctx, 3000);
                return adb.shell(command);
            } catch (Exception e) {
                android.util.Log.w("HostServer", "shell failed", e);
                if (adb != null) adb.close();
                adb = null;
            }
        }
        return "";
    }

    private boolean adbReady() {
        return LocalAdb.portOpen();
    }
}
