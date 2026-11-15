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
    /** The last ten songs that played, newest first, for the phone's Recently played (tap one to play it again). */
    private static final List<JSONObject> played = new ArrayList<>();
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
            JSONArray h = new JSONArray();
            for (JSONObject t : played) h.put(new JSONObject().put("id", t.optString("id")).put("title", t.optString("title"))
                    .put("artist", t.optString("artist")).put("art", t.optString("art")).put("by", t.optString("by")));
            o.put("history", h);
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
                    .put("duration", t.optLong("trackTimeMillis")).put("preview", t.optString("previewUrl"));
            FOUND.put(track.optString("id"), track);
            out.put(track);
        }
        return new JSONObject().put("results", out);
    }

    /**
     * Songs for the phone's swipe deck. With a seed (an artist or a genre) they come from a search for it; without
     * one, from this country's top songs on iTunes (the US chart if it has none). Each card has a 30-second preview.
     */
    static JSONObject discover(String seed) throws Exception {
        String country = Locale.getDefault().getCountry();
        if (!country.matches("[A-Z]{2}")) country = "US";
        JSONArray out = new JSONArray();
        seed = seed == null ? "" : seed.trim();
        if (seed.length() > 100) seed = seed.substring(0, 100);
        if (!seed.isEmpty()) {
            JSONArray items = new JSONObject(http("https://itunes.apple.com/search?media=music&entity=song&limit=50&country=" + country
                    + "&term=" + URLEncoder.encode(seed, "UTF-8"))).optJSONArray("results");
            Set<String> seenSongs = new HashSet<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject t = items.getJSONObject(i);
                String title = t.optString("trackName"), artist = t.optString("artistName");
                if (title.isEmpty() || !seenSongs.add(plain(title) + "|" + plain(artist))) continue;
                out.put(card(String.valueOf(t.optLong("trackId")), title, artist, t.optString("artworkUrl100", "").replace("100x100bb", "600x600bb"),
                        t.optLong("trackTimeMillis"), t.optString("previewUrl"), t.optString("primaryGenreName")));
            }
        } else {
            JSONArray entries = null;
            for (String cc : new String[]{country.toLowerCase(Locale.ROOT), "us"}) {
                try {
                    entries = new JSONObject(http("https://itunes.apple.com/" + cc + "/rss/topsongs/limit=50/json"))
                            .getJSONObject("feed").optJSONArray("entry");
                } catch (Exception ignored) {
                }
                if (entries != null && entries.length() > 5) break;
            }
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                JSONArray imgs = e.optJSONArray("im:image"), links = e.optJSONArray("link");
                String art = imgs == null ? "" : imgs.getJSONObject(imgs.length() - 1).optString("label").replaceAll("/\\d+x\\d+bb", "/600x600bb");
                String preview = "";
                long ms = 0;
                for (int k = 0; links != null && k < links.length(); k++) {
                    JSONObject a = links.getJSONObject(k).optJSONObject("attributes");
                    if (a != null && a.optString("type").startsWith("audio")) {
                        preview = a.optString("href");
                        ms = a.optLong("im:duration");
                    }
                }
                String id = e.getJSONObject("id").getJSONObject("attributes").optString("im:id");
                JSONObject cat = e.optJSONObject("category");
                out.put(card(id, e.getJSONObject("im:name").optString("label"), e.getJSONObject("im:artist").optString("label"), art, ms, preview,
                        cat == null ? "" : cat.getJSONObject("attributes").optString("label")));
            }
        }
        return new JSONObject().put("cards", out);
    }

    private static JSONObject card(String id, String title, String artist, String art, long ms, String preview, String genre) throws Exception {
        JSONObject track = new JSONObject().put("id", id).put("title", title).put("artist", artist).put("art", art)
                .put("duration", ms).put("preview", preview).put("genre", genre);
        FOUND.put(id, new JSONObject(track.toString()).put("art", art.replace("600x600bb", "300x300bb")));
        return track;
    }

    /** Adds a song a search found. Each phone's songs take turns with everyone else's. */
    static JSONObject add(Context ctx, String id, String name, String phone) throws Exception {
        return add(ctx, id, name, phone, null);
    }

    /**
     * {@code card} is the song as the phone has it, used when the TV no longer remembers that id (it keeps the last
     * 500 songs it found). Adding the same song twice from one phone answers ok without adding it again, so a phone
     * can safely retry an add whose answer was lost on the Wi-Fi.
     */
    static JSONObject add(Context ctx, String id, String name, String phone, JSONObject card) throws Exception {
        JSONObject found = FOUND.get(id == null ? "" : id);
        if (found == null && card != null && id != null && id.matches("\\d{1,15}")) {
            String title = card.optString("title").trim(), artist = card.optString("artist").trim(), art = card.optString("art");
            if (!title.isEmpty() && title.length() <= 200 && artist.length() <= 200) {
                found = new JSONObject().put("id", id).put("title", title).put("artist", artist)
                        .put("art", art.startsWith("https://") && art.length() < 400 ? art : "").put("duration", card.optLong("duration"));
                FOUND.put(id, found);
            }
        }
        if (found == null) return new JSONObject().put("error", "Search again, then add it.");
        if (!installed(ctx)) return new JSONObject().put("error", "This TV has no music player (Bitchord or YouTube).");
        String by = name == null || name.trim().isEmpty() ? "Guest" : name.trim();
        if (by.length() > 24) by = by.substring(0, 24);
        boolean startNow;
        synchronized (LOCK) {
            if (now != null && now.optString("id").equals(id) && now.optString("phone").equals(phone) && System.currentTimeMillis() - startedAt < 60_000
                    || queue.stream().anyMatch(t -> t.optString("id").equals(id) && t.optString("phone").equals(phone)))
                return new JSONObject().put("ok", true).put("already", true);
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
    // The queue follows what the player actually does, not titles: the first new item the player plays after we ask
    // is "ours" (whichever version it picked), and the moment it moves past that item, the next song in the queue
    // starts. A second before a song ends we start the next one ourselves, so the player's own autoplay never cuts in.

    /** Counts every start, so a slow start thread for a skipped song can't play over the next one. */
    private static int generation;
    /** The player's title when we asked, and the title it then played: our song, in the version it chose. */
    private static String titleBefore, startedTitle;

    private static void next(Context ctx) {
        JSONObject t;
        int gen;
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
            // Recently played: this song once, at the top, and it can be added again from there.
            for (int i = played.size() - 1; i >= 0; i--) if (played.get(i).optString("id").equals(t.optString("id"))) played.remove(i);
            played.add(0, t);
            while (played.size() > 10) played.remove(played.size() - 1);
            try {
                JSONObject again = new JSONObject(t.toString());
                again.remove("by");
                again.remove("phone");
                again.remove("round");
                FOUND.put(t.optString("id"), again);
            } catch (Exception ignored) {
            }
            startedAt = System.currentTimeMillis();
            seen = false;
            startedTitle = null;
            MediaController c = current(ctx);
            titleBefore = c == null ? null : titleOf(c);
            gen = ++generation;
        }
        new Thread(() -> play(ctx, t, gen), "party-play").start();
    }

    /**
     * Starts a song on the room's player: Bitchord through its media session with the song's exact YouTube Music
     * link (it ignores links sent as intents), falling back to a search if the link doesn't start; else TizenTube or
     * YouTube opened on the song's video.
     */
    private static void play(Context ctx, JSONObject t, int gen) {
        String player = player(ctx);
        if (player == null) return;
        String title = t.optString("title"), artist = t.optString("artist");
        String id = videoId(title + " " + artist);
        if (gen != generation) return;
        if (BITCHORD.equals(player)) {
            playOnSession(ctx, player, id, title, artist, gen);
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

    /** Bitchord: open it if it has no session yet, then ask its session for the exact link; search if that fails. */
    private static void playOnSession(Context ctx, String pkg, String id, String title, String artist, int gen) {
        Bundle extras = new Bundle();
        extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio");
        extras.putString(MediaStore.EXTRA_MEDIA_TITLE, title);
        extras.putString(MediaStore.EXTRA_MEDIA_ARTIST, artist);
        MediaController c = session(ctx, pkg);
        // Not running yet: wake its player service in the background (its media browser), so the song starts
        // without Bitchord coming up on screen. Opening the app is only the fallback.
        if (c == null) c = background(ctx, pkg);
        for (int i = 0; i < 6 && c == null; i++) {
            c = session(ctx, pkg);
            if (c != null) break;
            if (i == 0) {
                Intent app = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (app == null) app = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (app != null) {
                    Intent go = app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    new Handler(Looper.getMainLooper()).post(() -> ctx.startActivity(go));
                }
            }
            if (!sleep(1500)) return;
        }
        if (c == null || gen != generation) return;
        // A search plays with the song's real title, artist and art (StandBy shows them, with the lyrics), but now and
        // then picks another song ("Roommate" for "Roommates"). So: search, check what started, and if it's not ours,
        // play the exact video by its id (Bitchord's media ids are YouTube video ids), which shows no details.
        android.util.Log.i("Party", "searching " + pkg + " for " + title + " / " + artist);
        c.getTransportControls().playFromSearch(title + " " + artist, extras);
        if (started(gen, 10_000)) {
            MediaController got = session(ctx, pkg);
            if (got == null || right(got, title, artist)) return;
            android.util.Log.i("Party", "search played " + titleOf(got) + "; switching to the exact video");
        }
        if (id == null || gen != generation) return;
        synchronized (LOCK) {
            seen = false;
            startedTitle = null;
            MediaController before = session(ctx, pkg);
            titleBefore = before == null ? null : titleOf(before);
            startedAt = System.currentTimeMillis();
        }
        android.util.Log.i("Party", "asking " + pkg + " for media id " + id + " (" + title + ")");
        c.getTransportControls().playFromMediaId(id, extras);
    }

    private static android.media.browse.MediaBrowser browser;

    /**
     * Connects to the player's media browser service (Bitchord's PlaybackService), which starts its player without its
     * screen, and returns a controller for its session; null if it won't connect within five seconds. The connection
     * is kept, so the service stays up between songs.
     */
    private static MediaController background(Context ctx, String pkg) {
        try {
            if (browser != null && browser.isConnected()) return new MediaController(ctx, browser.getSessionToken());
            Intent probe = new Intent("android.media.browse.MediaBrowserService").setPackage(pkg);
            java.util.List<android.content.pm.ResolveInfo> found = ctx.getPackageManager().queryIntentServices(probe, 0);
            if (found.isEmpty()) return null;
            android.content.ComponentName svc = new android.content.ComponentName(pkg, found.get(0).serviceInfo.name);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Handler mainThread = new Handler(Looper.getMainLooper());
            mainThread.post(() -> {
                if (browser != null) browser.disconnect();
                browser = new android.media.browse.MediaBrowser(ctx, svc, new android.media.browse.MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        done.countDown();
                    }

                    @Override
                    public void onConnectionFailed() {
                        browser = null;
                        done.countDown();
                    }

                    @Override
                    public void onConnectionSuspended() {
                        browser = null;
                    }
                }, null);
                browser.connect();
            });
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) return null;
            android.media.browse.MediaBrowser b = browser;
            if (b == null || !b.isConnected()) return null;
            android.util.Log.i("Party", "connected to " + pkg + " in the background");
            return new MediaController(ctx, b.getSessionToken());
        } catch (Exception e) {
            android.util.Log.w("Party", "background connect", e);
            return null;
        }
    }

    /** Whether the player's song is this one: its title and its lead artist both appear in what the player shows. */
    private static boolean right(MediaController c, String title, String artist) {
        if (c.getMetadata() == null) return false;
        String t = titleOf(c), a = c.getMetadata().getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (t == null) return false;
        String lead = plain(artist.split(",|&| feat\\.| x ")[0]);
        String shownTitle = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        String shownArtist = (a == null ? "" : a + " ") + t;
        shownArtist = shownArtist.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        String mine = plain(title);
        // "Roommate" must not pass for "Roommates": the title must match whole, not as a piece of a longer one.
        boolean titleOk = shownTitle.equals(mine) || shownTitle.startsWith(mine) && !Character.isLetter(firstAfter(t, title));
        return titleOk && (lead.isEmpty() || shownArtist.contains(lead));
    }

    /** The character in the shown title right after the song's own title, or a space when there is none. */
    private static char firstAfter(String shown, String title) {
        String core = title.replaceAll("\\s*[(\\[].*?[)\\]]", "").trim().toLowerCase(Locale.ROOT);
        int at = shown.toLowerCase(Locale.ROOT).indexOf(core);
        int after = at < 0 ? -1 : at + core.length();
        return after < 0 || after >= shown.length() ? ' ' : shown.charAt(after);
    }

    /** Waits up to ms for tick() to see our song start; false if it didn't, or a newer start took over. */
    private static boolean started(int gen, long ms) {
        for (long waited = 0; waited < ms; waited += 500) {
            if (!sleep(500) || gen != generation) return false;
            synchronized (LOCK) {
                if (seen) return true;
            }
        }
        return false;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * The first YouTube video for a song, read from YouTube's own search page (no key); null when none is found.
     * A desktop browser's identity: the mobile page writes its data escaped (\x22videoId\x22), which hid every id.
     */
    static String videoId(String query) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            }
            String page = b.toString("UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:\"|\\\\x22)videoId(?:\"|\\\\x22):(?:\"|\\\\x22)([A-Za-z0-9_-]{11})").matcher(page);
            if (m.find()) return m.group(1);
            android.util.Log.w("Party", "no video id in " + page.length() + " bytes for " + query);
            return null;
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
            watch.scheduleWithFixedDelay(() -> tick(ctx.getApplicationContext()), 1, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Every second while a song is on. Our song has started once the player plays something new after we asked (or
     * matches the title, or anything at all after 20 seconds, so a odd version never stalls the queue). From then,
     * the queue moves on when the player leaves that song, stops, or is 1.5 seconds from its end.
     */
    private static void tick(Context ctx) {
        try {
            boolean advance = false, firstSeen = false;
            synchronized (LOCK) {
                if (now == null) return;
                MediaController c = current(ctx);
                if (c == null || c.getMetadata() == null) return;
                PlaybackState st = c.getPlaybackState();
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                String title = titleOf(c);
                if (!seen && playing && title != null) {
                    // A new, non-empty title: an item with no details yet (loading) doesn't count as our song.
                    boolean moved = !title.trim().isEmpty() && !title.equals(titleBefore);
                    boolean waitedLong = System.currentTimeMillis() - startedAt > 20_000;
                    if (moved || matches(c) || waitedLong) {
                        seen = true;
                        startedTitle = title;
                        firstSeen = !standbyShown;
                        standbyShown = true;
                        android.util.Log.i("Party", "started: " + title + " for " + now.optString("title")
                                + ", player length " + c.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION) + " ms");
                    }
                }
                // Left our song: a different title (an empty one is the player loading, not a new song).
                if (seen && title != null && !title.trim().isEmpty() && !title.equals(startedTitle)) {
                    advance = true;
                    // The player has moved on to a song of its own (Bitchord trims the silence at the end, so it can
                    // beat the early start below): hush it at once while the next song is fetched.
                    if (playing && !queue.isEmpty()) c.getTransportControls().pause();
                }
                if (seen && playing) {
                    long dur = c.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
                    long pos = st.getPosition() + (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                    if (dur > 0 && pos >= dur - 1500) advance = true;
                }
                if (seen && st != null && (st.getState() == PlaybackState.STATE_STOPPED || st.getState() == PlaybackState.STATE_NONE)) advance = true;
                if (advance) {
                    android.util.Log.i("Party", "moving on from " + startedTitle + " (player now: " + title + ")");
                    // The queue is empty: let the player carry on; party mode has nothing more to play.
                    if (queue.isEmpty()) {
                        now = null;
                        advance = false;
                    }
                }
            }
            if (firstSeen) lyrics(ctx);
            if (advance) next(ctx);
        } catch (Exception e) {
            android.util.Log.w("Party", "tick", e);
        }
    }

    private static String titleOf(MediaController c) {
        if (c.getMetadata() == null) return null;
        String title = c.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE);
        return title != null ? title : c.getMetadata().getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    /**
     * Whether the player's current item is the song the queue started. A video's title carries more ("Dua Lipa -
     * Levitating (Official Music Video)"), so the song's own title only has to appear in it.
     */
    private static boolean matches(MediaController c) {
        JSONObject n = now;
        String title = titleOf(c);
        if (n == null || title == null) return false;
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
