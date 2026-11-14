package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Party mode (this fork): guests search songs on their phones and add them to one shared queue that plays on the
 * TV. The queue takes turns between phones, so nobody fills it alone. When a song ends the next one starts; the
 * first song also opens StandBy, whose Now Playing face shows the lyrics.
 * <p>
 * No keys and no accounts. Search is Apple's public iTunes song search (title, artist, album art). Each song's
 * YouTube video is found on YouTube's own search page, then played by the room's player: Bitchord (YouTube Music)
 * when the TV has it, asked through its media session; else YouTube as the TV has it (TizenTube first, Watch.youtube)
 * opened on the video. Spotify's TV app was tried first and takes no play requests from other apps.
 */
final class Party {
    /** The room's player, in order: Bitchord (YouTube Music), then YouTube as the TV has it (Watch.youtube). */
    static final String BITCHORD = "com.music.bitchord.tv";
    private static final int MAX_QUEUE = 60, MAX_EACH = 6;

    private static final Object LOCK = new Object();
    /** Songs handed out by search, by id, so a phone can only add what a search found. */
    private static final Map<String, JSONObject> FOUND = Collections.synchronizedMap(new LinkedHashMap<String, JSONObject>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> e) {
            return size() > 500;
        }
    });
    private static final List<JSONObject> queue = new ArrayList<>();
    private static JSONObject now;
    /** When the current song was started, and whether Spotify has been seen playing it yet. */
    private static long startedAt;
    private static boolean seen, standbyShown;
    private static ScheduledExecutorService watch;

    private Party() {
    }

    /** The song playing now (title, artist, art, by), for the phone's Home; null when none. */
    static JSONObject current() {
        synchronized (LOCK) {
            return now;
        }
    }

    static int queued() {
        synchronized (LOCK) {
            return queue.size();
        }
    }

    static boolean installed(Context ctx) {
        return player(ctx) != null;
    }

    /** Bitchord when it's installed, else TizenTube, else YouTube; null when the TV has none of them. */
    static String player(Context ctx) {
        try {
            if (ctx.getPackageManager().getApplicationInfo(BITCHORD, 0).enabled) return BITCHORD;
        } catch (Exception ignored) {
        }
        return Watch.youtube(ctx.getPackageManager());
    }

    // ---- what the phones see -------------------------------------------------------------------------------

    static JSONObject state(Context ctx, String phone) throws Exception {
        synchronized (LOCK) {
            JSONArray q = new JSONArray();
            for (JSONObject t : queue) q.put(view(t, phone));
            JSONObject o = new JSONObject().put("player", player(ctx) == null ? "" : label(ctx, player(ctx))).put("queue", q);
            if (now != null) o.put("now", view(now, phone).put("waiting", !seen && System.currentTimeMillis() - startedAt > 15_000));
            return o;
        }
    }

    private static JSONObject view(JSONObject t, String phone) throws Exception {
        return new JSONObject().put("id", t.optString("id")).put("title", t.optString("title")).put("artist", t.optString("artist"))
                .put("art", t.optString("art")).put("by", t.optString("by")).put("mine", t.optString("phone").equals(phone))
                .put("duration", t.optLong("duration"));
    }

    static JSONObject search(String q) throws Exception {
        q = q.trim();
        if (q.isEmpty() || q.length() > 100) return new JSONObject().put("error", "Type a song or an artist.");
        String country = Locale.getDefault().getCountry();
        if (!country.matches("[A-Z]{2}")) country = "US";
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=15&country=" + country
                + "&term=" + URLEncoder.encode(q, "UTF-8");
        JSONArray items = new JSONObject(http(url)).optJSONArray("results");
        JSONArray out = new JSONArray();
        Set<String> seenSongs = new HashSet<>();
        for (int i = 0; items != null && i < items.length() && out.length() < 10; i++) {
            JSONObject t = items.getJSONObject(i);
            String title = t.optString("trackName"), artist = t.optString("artistName");
            // The same song on an album and a compilation shows once.
            if (title.isEmpty() || !seenSongs.add(plain(title) + "|" + plain(artist))) continue;
            JSONObject track = new JSONObject().put("id", String.valueOf(t.optLong("trackId"))).put("title", title)
                    .put("artist", artist).put("art", t.optString("artworkUrl100", "").replace("100x100bb", "300x300bb"))
                    .put("duration", t.optLong("trackTimeMillis"));
            FOUND.put(track.optString("id"), track);
            out.put(track);
        }
        return new JSONObject().put("results", out);
    }

    /** Adds a song a search found. Each phone's songs take turns with everyone else's. */
    static JSONObject add(Context ctx, String id, String name, String phone) throws Exception {
        JSONObject found = FOUND.get(id == null ? "" : id);
        if (found == null) return new JSONObject().put("error", "Search again, then add it.");
        if (!installed(ctx)) return new JSONObject().put("error", "This TV has no music player (Bitchord or YouTube).");
        String by = name == null || name.trim().isEmpty() ? "Guest" : name.trim();
        if (by.length() > 24) by = by.substring(0, 24);
        boolean startNow;
        synchronized (LOCK) {
            if (queue.size() >= MAX_QUEUE) return new JSONObject().put("error", "The queue is full. Try again after a few songs.");
            int mine = 0;
            for (JSONObject t : queue) if (t.optString("phone").equals(phone)) mine++;
            if (mine >= MAX_EACH) return new JSONObject().put("error", "You have " + MAX_EACH + " songs waiting. Let the others have a turn.");
            // Turn order: this song goes in "round" mine, after every song of an earlier or the same round.
            JSONObject t = new JSONObject(found.toString()).put("by", by).put("phone", phone == null ? "" : phone).put("round", mine);
            int at = queue.size();
            for (int i = 0; i < queue.size(); i++) if (queue.get(i).optInt("round") > mine) {
                at = i;
                break;
            }
            queue.add(at, t);
            startNow = now == null;
        }
        ensureWatching(ctx);
        if (startNow) next(ctx);
        return new JSONObject().put("ok", true).put("playing", startNow);
    }

    static JSONObject remove(String id, String phone, boolean host) throws Exception {
        synchronized (LOCK) {
            for (int i = 0; i < queue.size(); i++) {
                JSONObject t = queue.get(i);
                if (t.optString("id").equals(id) && (host || t.optString("phone").equals(phone))) {
                    queue.remove(i);
                    // Later songs of the same phone move up a round.
                    for (int k = i; k < queue.size(); k++) if (queue.get(k).optString("phone").equals(t.optString("phone"))) {
                        queue.get(k).put("round", Math.max(0, queue.get(k).optInt("round") - 1));
                    }
                    return new JSONObject().put("ok", true);
                }
            }
        }
        return new JSONObject().put("error", "That song isn’t yours to remove.");
    }

    static JSONObject skip(Context ctx) throws Exception {
        next(ctx);
        return new JSONObject().put("ok", true);
    }

    static JSONObject stop() throws Exception {
        synchronized (LOCK) {
            queue.clear();
            now = null;
            standbyShown = false;
        }
        return new JSONObject().put("ok", true);
    }

    /** StandBy's Now Playing face, with the lyrics, on the TV. */
    static void lyrics(Context ctx) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ctx.startActivity(new Intent(ctx, StandByActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.util.Log.w("Party", "standby", e);
            }
        });
    }

    // ---- playing -------------------------------------------------------------------------------------------

    private static void next(Context ctx) {
        JSONObject t;
        synchronized (LOCK) {
            if (queue.isEmpty()) {
                now = null;
                return;
            }
            t = queue.remove(0);
            for (JSONObject o : queue) if (o.optString("phone").equals(t.optString("phone"))) {
                try {
                    o.put("round", Math.max(0, o.optInt("round") - 1));
                } catch (Exception ignored) {
                }
            }
            now = t;
            startedAt = System.currentTimeMillis();
            seen = false;
        }
        new Thread(() -> play(ctx, t.optString("title"), t.optString("artist")), "party-play").start();
    }

    /**
     * Starts a song on the room's player (player()): Bitchord through its media session with the song's YouTube Music
     * link (it ignores links sent as intents), else TizenTube or YouTube opened on the song's video.
     */
    private static void play(Context ctx, String title, String artist) {
        String player = player(ctx);
        if (player == null) return;
        String id = videoId(title + " " + artist);
        if (BITCHORD.equals(player)) {
            playOnSession(ctx, player, id, title, artist);
            return;
        }
        if (id == null) {
            android.util.Log.w("Party", "no video found for " + title);
            return;
        }
        Intent go = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + id)).setPackage(player)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ctx.startActivity(go);
            } catch (Exception e) {
                android.util.Log.w("Party", "open video", e);
            }
        });
    }

    /** Bitchord: open it if it has no session yet, then ask its session for the link (or, without one, by search). */
    private static void playOnSession(Context ctx, String pkg, String id, String title, String artist) {
        Bundle extras = new Bundle();
        extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio");
        extras.putString(MediaStore.EXTRA_MEDIA_TITLE, title);
        extras.putString(MediaStore.EXTRA_MEDIA_ARTIST, artist);
        for (int i = 0; i < 6; i++) {
            MediaController c = session(ctx, pkg);
            if (c != null) {
                android.util.Log.i("Party", "asking " + pkg + " for " + (id == null ? "search" : id));
                if (id != null) c.getTransportControls().playFromUri(android.net.Uri.parse("https://music.youtube.com/watch?v=" + id), extras);
                else c.getTransportControls().playFromSearch(title + " " + artist, extras);
                return;
            }
            if (i == 0) {
                Intent app = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (app == null) app = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (app != null) {
                    Intent go = app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    new Handler(Looper.getMainLooper()).post(() -> ctx.startActivity(go));
                }
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The first YouTube video for a song, read from YouTube's own search page (no key); null when none is found. */
    static String videoId(String query) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(b.toString("UTF-8"));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            android.util.Log.w("Party", "video search", e);
            return null;
        }
    }

    private static void ensureWatching(Context ctx) {
        synchronized (LOCK) {
            if (watch != null) return;
            watch = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "party-watch");
                t.setDaemon(true);
                return t;
            });
            watch.scheduleWithFixedDelay(() -> tick(ctx.getApplicationContext()), 2, 2, TimeUnit.SECONDS);
        }
    }

    /**
     * Every two seconds while a song is on: once Spotify plays our song we know it started; when it moves to another
     * song (its own autoplay) or is about to end, the next one in the queue starts.
     */
    private static void tick(Context ctx) {
        try {
            boolean advance = false, firstSeen = false;
            synchronized (LOCK) {
                if (now == null) return;
                MediaController c = current(ctx);
                if (c == null || c.getMetadata() == null) return;
                PlaybackState st = c.getPlaybackState();
                boolean ours = matches(c);
                if (ours && !seen && st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    seen = true;
                    firstSeen = !standbyShown;
                    standbyShown = true;
                }
                if (seen && !ours) advance = true;
                if (seen && ours && st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    long dur = c.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
                    long pos = st.getPosition() + (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                    if (dur > 0 && pos >= dur - 1200 && !queue.isEmpty()) advance = true;
                }
                if (seen && st != null && st.getState() == PlaybackState.STATE_STOPPED) advance = true;
            }
            if (firstSeen) lyrics(ctx);
            if (advance) next(ctx);
        } catch (Exception e) {
            android.util.Log.w("Party", "tick", e);
        }
    }

    /**
     * Whether the player's current item is the song the queue started. A video's title carries more ("Dua Lipa -
     * Levitating (Official Music Video)"), so the song's own title only has to appear in it.
     */
    private static boolean matches(MediaController c) {
        JSONObject n = now;
        if (n == null || c.getMetadata() == null) return false;
        String title = c.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = c.getMetadata().getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) return false;
        String mine = plain(n.optString("title")), theirs = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        return !mine.isEmpty() && (theirs.contains(mine) || mine.contains(plain(title)));
    }

    private static String plain(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s*[(\\[].*?[)\\]]", "").replaceAll("\\s+-\\s+.*$", "").replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** The media session of the room's player, or null. */
    private static MediaController current(Context ctx) {
        String p = player(ctx);
        return p == null ? null : session(ctx, p);
    }

    private static MediaController session(Context ctx, String pkg) {
        try {
            MediaSessionManager m = ctx.getSystemService(MediaSessionManager.class);
            for (MediaController c : m.getActiveSessions(new ComponentName(ctx, GuardRevival.class))) {
                if (pkg.equals(c.getPackageName())) return c;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String label(Context ctx, String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String http(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) try (InputStream i = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
        }
        if (code >= 400) throw new java.io.IOException(code + " search");
        return b.toString("UTF-8");
    }
}
