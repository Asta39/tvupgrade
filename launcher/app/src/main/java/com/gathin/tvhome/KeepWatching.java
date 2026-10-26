package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The system's Continue watching list (TvContract.WatchNextPrograms), for the keep_watching row. Reads what apps
 * publish as searchable rows (READ_TV_LISTINGS), newest first, and fetches each poster once into a small bitmap.
 * Posters are usually https URLs, which is why this fork of the launcher holds INTERNET; nothing else uses it.
 */
final class KeepWatching {
    private static final String TAG = "KeepWatching";
    /** How many cards the row shows. */
    static final int MAX = 3;
    /** Posters are drawn about 120dp wide; decode no larger than this. */
    private static final int POSTER_W = 360;

    static final class Item {
        final String title, subtitle, pkg, app;
        final Intent intent;
        final String posterUri;
        Bitmap poster;
        final long engaged;

        Item(String title, String subtitle, String pkg, String app, Intent intent, String posterUri, long engaged) {
            this.title = title;
            this.subtitle = subtitle;
            this.pkg = pkg;
            this.app = app;
            this.intent = intent;
            this.posterUri = posterUri;
            this.engaged = engaged;
        }
    }

    interface Listener {
        void onItems(List<Item> items);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** Posters by URI, kept across reloads so returning to Home does not fetch them again. */
    private static final Map<String, Bitmap> POSTERS = Collections.synchronizedMap(new HashMap<>());

    private KeepWatching() {
    }

    /** Loads off the UI thread and calls back on it; an empty list when the TV has nothing or refuses. */
    static void load(Context ctx, Listener listener) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        IO.execute(() -> {
            List<Item> items = query(app);
            main.post(() -> listener.onItems(items));
            boolean fetched = false;
            for (Item item : items) {
                if (item.poster != null || item.posterUri == null) continue;
                item.poster = poster(app.getContentResolver(), item.posterUri);
                fetched |= item.poster != null;
            }
            if (fetched) main.post(() -> listener.onItems(items));
        });
    }

    private static List<Item> query(Context ctx) {
        List<Item> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        // No selection: without ACCESS_ALL_EPG_DATA a selection throws (docs/watch-next.md). Filter in code.
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return out;
            int title = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE);
            int episode = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
            int season = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
            int ep = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
            int poster = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
            int thumb = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
            int intent = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
            int pkg = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
            int engaged = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            int browsable = c.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_BROWSABLE);
            while (c.moveToNext()) {
                if (browsable >= 0 && c.getInt(browsable) == 0) continue;
                String p = str(c, pkg), t = str(c, title), u = str(c, intent);
                if (p == null || t == null || u == null) continue;
                Intent launch;
                try {
                    launch = Intent.parseUri(u, Intent.URI_INTENT_SCHEME);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                } catch (Exception bad) {
                    continue;
                }
                String label;
                try {
                    label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
                } catch (PackageManager.NameNotFoundException gone) {
                    continue;   // the app was uninstalled or disabled since it published the row
                }
                String sub = str(c, episode);
                String s = str(c, season), e = str(c, ep);
                if (s != null && e != null) sub = "S" + s + "E" + e + (sub != null ? " · " + sub : "");
                String art = str(c, thumb) != null ? str(c, thumb) : str(c, poster);
                Item item = new Item(t, sub, p, label, launch, art, engaged >= 0 ? c.getLong(engaged) : 0L);
                item.poster = art != null ? POSTERS.get(art) : null;
                out.add(item);
            }
        } catch (RuntimeException refused) {
            Log.w(TAG, "watch next unavailable: " + refused);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.engaged, a.engaged));
        Log.i(TAG, "watch next rows: " + out.size());
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private static String str(Cursor c, int i) {
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static Bitmap poster(ContentResolver cr, String uri) {
        Bitmap cached = POSTERS.get(uri);
        if (cached != null) return cached;
        try {
            byte[] bytes;
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    bytes = read(in);
                } finally {
                    conn.disconnect();
                }
            } else {
                try (InputStream in = cr.openInputStream(Uri.parse(uri))) {
                    if (in == null) return null;
                    bytes = read(in);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= POSTER_W) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (b != null) POSTERS.put(uri, b);
            return b;
        } catch (Exception | OutOfMemoryError failed) {
            Log.w(TAG, "poster " + failed);
            return null;
        }
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 4 * 1024 * 1024) throw new java.io.IOException("poster too large");
        }
        return out.toByteArray();
    }
}
