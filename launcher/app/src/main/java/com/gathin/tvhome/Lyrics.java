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
    private static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)");

    private Lyrics() {
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
            List<Line> lines = new ArrayList<>();
            try {
                String q = "artist_name=" + enc(firstArtist(artist)) + "&track_name=" + enc(title)
                        + (durationMs > 0 ? "&duration=" + Math.round(durationMs / 1000.0) : "");
                String body = get("https://lrclib.net/api/get?" + q);
                String synced = body != null ? new JSONObject(body).optString("syncedLyrics", "") : "";
                if (synced.isEmpty()) {
                    // No exact match: the best search hit that has synced lyrics.
                    String found = get("https://lrclib.net/api/search?q=" + enc(firstArtist(artist) + " " + title));
                    JSONArray arr = found != null ? new JSONArray(found) : new JSONArray();
                    for (int i = 0; i < arr.length() && synced.isEmpty(); i++) {
                        synced = arr.optJSONObject(i).optString("syncedLyrics", "");
                    }
                }
                for (String row : synced.split("\n")) {
                    Matcher m = LRC.matcher(row.trim());
                    if (!m.matches()) continue;
                    String frac = m.group(3) == null ? "0" : m.group(3);
                    long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000
                            + Math.round(Long.parseLong(frac) * Math.pow(10, 3 - frac.length()));
                    lines.add(new Line(ms, m.group(4).trim()));
                }
            } catch (Exception offline) {
                // Leave it empty; the face then shows the song without lyrics.
            }
            CACHE.put(key, lines);
            main.post(() -> cb.onLyrics(key, lines));
        });
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

    private static String get(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "tvupgrade-launcher (github.com/Asta39/tvupgrade)");
        try {
            if (c.getResponseCode() != 200) return null;
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
