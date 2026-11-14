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
                json(out, 200, j);
                return;
            case "POST /api/watch/search":
                if (!host && !guestRemote()) break;
                json(out, 200, Watch.search(ctx, body.optString("q", "")));
                return;
            case "POST /api/watch/play":
                if (!host && !guestRemote()) break;
                JSONObject played = Watch.play(ctx, body.optString("id"));
                json(out, 200, played);
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
                .put("checkin", Stay.friendly(stay.checkin)).put("checkout", Stay.friendly(stay.checkout))
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
        return o;
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
        // The AI key: set when a new one is sent, removed on request, otherwise kept as it is.
        if (!b.optString("ai_key", "").trim().isEmpty()) s.put("ai_key", b.optString("ai_key").trim());
        if (b.optBoolean("ai_key_clear")) s.remove("ai_key");
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
    private static final String[] MODELS = {"openai/gpt-oss-20b", "openai/gpt-oss-120b", "llama-3.3-70b-versatile"};
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
    private static String get(String url, String body, String bearer) throws java.io.IOException {
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
