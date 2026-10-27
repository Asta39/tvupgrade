package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics for StandBy's Now Playing face (this fork), from LRCLIB (lrclib.net, free, no key). Music apps
 * don't hand lyrics to other apps, so the song's title, artist and length are looked up there instead. Results,
 * misses included, are cached for the session.
 */
final class Lyrics {
    static final class Line {
        final long at;
        final String text;

        Line(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    interface Callback {
        /** On the UI thread; an empty list when nothing synced was found. */
        void onLyrics(String key, List<Line> lines);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Map<String, List<Line>> CACHE = Collections.synchronizedMap(new HashMap<>());
    /** Length of the recording the timings were made for, in ms (0 when unknown). */
    private static final Map<String, Long> SOURCE_MS = Collections.synchronizedMap(new HashMap<>());
    private static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)");

    private Lyrics() {
    }

    /** Whether a lookup for this song has finished (found or truly missing), as opposed to failed. */
    static boolean known(String key) {
        return CACHE.containsKey(key);
    }

    static String key(String artist, String title) {
        return artist + "\n" + title;
    }

    static void load(String artist, String title, long durationMs, Callback cb) {
        String key = key(artist, title);
        List<Line> hit = CACHE.get(key);
        Handler main = new Handler(Looper.getMainLooper());
        if (hit != null) {
            cb.onLyrics(key, hit);
            return;
        }
        IO.execute(() -> {
            List<Line> lines = null;
            try {
                lines = lookup(key, firstArtist(artist), clean(title), durationMs);
            } catch (java.io.IOException offline) {
                // Network trouble or LRCLIB overloaded (503): don't remember it as "no lyrics"; the next track
                // change or StandBy visit asks again.
            } catch (Exception bad) {
                lines = new ArrayList<>();
            }
            if (lines != null) CACHE.put(key, lines);
            android.util.Log.i("Lyrics", firstArtist(artist) + " / " + clean(title) + " -> "
                    + (lines == null ? "lookup failed" : lines.size() + " lines"));
            List<Line> result = lines != null ? lines : new ArrayList<>();
            main.post(() -> cb.onLyrics(key, result));
        });
    }

    /**
     * Every LRCLIB entry for this title and artist, best first: synced lyrics whose length is within five seconds of
     * the song, then any synced, then plain lyrics spread over the song. A free-text search when the fielded one
     * finds nothing.
     */
    private static List<Line> lookup(String key, String artist, String title, long durationMs) throws Exception {
        JSONArray hits = searchArray("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
        if (hits.length() == 0) hits = searchArray("https://lrclib.net/api/search?q=" + enc(artist + " " + title));
        long sec = Math.round(durationMs / 1000.0);
        JSONObject near = null, anySynced = null, plain = null;
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.optJSONObject(i);
            if (h == null) continue;
            boolean synced = !h.optString("syncedLyrics", "").isEmpty() && !"null".equals(h.optString("syncedLyrics"));
            boolean close = sec <= 0 || Math.abs(h.optDouble("duration", 0) - sec) <= 5;
            if (synced && close && near == null) near = h;
            if (synced && anySynced == null) anySynced = h;
            if (!synced && plain == null && !h.optString("plainLyrics", "").isEmpty() && !"null".equals(h.optString("plainLyrics"))) plain = h;
        }
        JSONObject pick = near != null ? near : anySynced;
        if (pick != null) SOURCE_MS.put(key, Math.round(pick.optDouble("duration", 0) * 1000));
        List<Line> lines = new ArrayList<>();
        if (pick != null) {
            for (String row : pick.optString("syncedLyrics").split("\n")) {
                Matcher m = LRC.matcher(row.trim());
                if (!m.matches()) continue;
                String frac = m.group(3) == null ? "0" : m.group(3);
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000
                        + Math.round(Long.parseLong(frac) * Math.pow(10, 3 - frac.length()));
                lines.add(new Line(ms, m.group(4).trim()));
            }
        } else if (plain != null && durationMs > 0) {
            // Untimed lyrics: spread evenly between 8% and 95% of the song. Approximate, but better than nothing.
            List<String> rows = new ArrayList<>();
            for (String row : plain.optString("plainLyrics").split("\n")) if (!row.trim().isEmpty()) rows.add(row.trim());
            long start = durationMs * 8 / 100, span = durationMs * 87 / 100;
            for (int i = 0; i < rows.size(); i++) lines.add(new Line(start + span * i / Math.max(1, rows.size()), rows.get(i)));
        }
        return lines;
    }

    private static JSONArray searchArray(String url) throws Exception {
        String body = get(url);
        return body == null ? new JSONArray() : new JSONArray(body);
    }

    /** "Lies (feat. Lana Del Rey)" and "Song - Remastered 2011" are stored as "Lies" and "Song". */
    static String clean(String title) {
        String t = title.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|featuring)[^)\\]]*[)\\]]", "");
        t = t.replaceAll("(?i)\\s+-\\s+(remaster(ed)?|live|radio edit|single version|mono|stereo).*$", "");
        return t.trim();
    }

    /**
     * The position in the lyric file's own timeline for a playback position. The same song can be a few seconds
     * longer or shorter between releases (a radio edit, a longer intro), and a fixed offset would only be right at
     * one point; scaling by the two lengths keeps the whole song in step.
     */
    static long sourcePosition(String key, long positionMs, long trackMs) {
        Long src = SOURCE_MS.get(key);
        if (src == null || src <= 0 || trackMs <= 0) return positionMs;
        double ratio = src / (double) trackMs;
        return ratio > 0.9 && ratio < 1.1 ? Math.round(positionMs * ratio) : positionMs;
    }

    /** The line playing at this position, or -1 before the first. */
    static int index(List<Line> lines, long positionMs) {
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).at <= positionMs) at = i;
            else break;
        }
        return at;
    }

    /** "A, B & C" credits: LRCLIB matches best on the lead artist alone. */
    private static String firstArtist(String artist) {
        String a = artist.split(",| & | feat\\.| ft\\.| x ")[0];
        return a.trim();
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** GET with up to three tries: LRCLIB answers 503 now and then when it is busy. */
    private static String get(String url) throws java.io.IOException {
        java.io.IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String body = getOnce(url);
                if (body != null) return body;
                return null;   // 404 and the like: a real "not found"
            } catch (java.io.IOException busy) {
                last = busy;
                try {
                    Thread.sleep(800L * (attempt + 1));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        throw last != null ? last : new java.io.IOException("unreachable");
    }

    private static String getOnce(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "tvupgrade-launcher (github.com/Asta39/tvupgrade)");
        try {
            int code = c.getResponseCode();
            if (code >= 500) throw new java.io.IOException("LRCLIB " + code);
            if (code != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
