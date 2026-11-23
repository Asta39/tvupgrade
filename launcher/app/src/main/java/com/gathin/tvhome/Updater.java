package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Updates from this fork's GitHub releases, right on the TV: Control Center shows a card when a newer release is out,
 * Download fetches its APK with a progress bar, and Install hands it to Android's installer, which updates the
 * launcher in place (settings, profiles and the guest setup stay). The first install asks the user, once, to let
 * this launcher install apps.
 *
 * Only an APK signed with the same key as the installed launcher is offered for install: Android would refuse any
 * other as an update anyway, and checking first gives a clear message instead of a failed install.
 */
final class Updater {
    static final String REPO = "Asta39/tvupgrade";
    private static final String PREFS = "updater";
    /** How often Control Center asks GitHub (its API allows 60 requests an hour without a token). */
    private static final long CHECK_EVERY_MS = 3 * 60 * 60 * 1000L;
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    static final String ACTION_STATUS = "com.gathin.tvhome.UPDATE_STATUS";

    static final class Release {
        final String version, url, notes;
        final long size;

        Release(String version, String url, long size, String notes) {
            this.version = version;
            this.url = url;
            this.size = size;
            this.notes = notes;
        }
    }

    interface Progress {
        void on(long done, long total);
    }

    private Updater() {
    }


    /** A newer release than this build, not put off with Later, or null. Network: call off the main thread. */
    static Release check(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject latest;
            long now = System.currentTimeMillis();
            String cached = p.getString("latest", null);
            if (cached != null && now - p.getLong("checked", 0) < CHECK_EVERY_MS) {
                latest = new JSONObject(cached);
            } else {
                latest = fetchLatest();
                if (latest == null) return null;
                p.edit().putString("latest", latest.toString()).putLong("checked", now).apply();
            }
            String version = latest.optString("version");
            if (!newer(version, current(ctx))) return null;
            if (version.equals(p.getString("later", ""))) return null;
            return new Release(version, latest.optString("url"), latest.optLong("size"), latest.optString("notes"));
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() != 200) return null;
        JSONObject r;
        try (InputStream in = c.getInputStream()) {
            r = new JSONObject(readAll(in));
        }
        if (r.optBoolean("draft") || r.optBoolean("prerelease")) return null;
        JSONArray assets = r.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String url = a.optString("browser_download_url");
            // Only this repository's own release downloads.
            if (a.optString("name").endsWith(".apk") && url.startsWith("https://github.com/" + REPO + "/releases/download/")) {
                String notes = r.optString("body", "");
                if (notes.length() > 300) notes = notes.substring(0, 300) + "…";
                return new JSONObject().put("version", r.optString("tag_name").replaceFirst("^[vV]", ""))
                        .put("url", url).put("size", a.optLong("size")).put("notes", notes);
            }
        }
        return null;
    }

    static String current(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** True when version a (like 1.3.0) is above b. Missing parts count as 0; anything after a "-" is ignored. */
    static boolean newer(String a, String b) {
        if (a == null || a.isEmpty()) return false;
        int[] x = parts(a), y = parts(b == null ? "0" : b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] parts(String v) {
        String[] s = v.replaceFirst("^[vV]", "").split("-")[0].split("\\.");
        int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            try {
                out[i] = Integer.parseInt(s[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Later: don't show this version again; a newer one still shows. */
    static void later(Context ctx, String version) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("later", version).apply();
    }

    static File apk(Context ctx) {
        return new File(ctx.getCacheDir(), "update.apk");
    }

    /** Downloads the release's APK. Network: off the main thread. Returns null on success, else what went wrong. */
    static String download(Context ctx, Release r, Progress progress) {
        File out = apk(ctx), part = new File(ctx.getCacheDir(), "update.apk.part");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(r.url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return "The download failed (" + c.getResponseCode() + ").";
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.size;
            if (total > MAX_APK_BYTES) return "That download is too big.";
            long done = 0, shown = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    done += n;
                    if (done > MAX_APK_BYTES) return "That download is too big.";
                    if (done - shown > total / 100 || done == total) {
                        shown = done;
                        long d = done, t = total;
                        new Handler(Looper.getMainLooper()).post(() -> progress.on(d, t));
                    }
                }
            }
            if (total > 0 && done != total) return "The download stopped early. Try again.";
            String bad = verify(ctx, part);
            if (bad != null) {
                part.delete();
                return bad;
            }
            out.delete();
            if (!part.renameTo(out)) return "Couldn't save the download.";
            return null;
        } catch (Exception e) {
            part.delete();
            return "The download failed. Check the TV's internet and try again.";
        }
    }

    /** Same package, a newer version, and signed by the same key as the launcher already on the TV. */
    @SuppressWarnings("deprecation")
    private static String verify(Context ctx, File f) {
        PackageManager pm = ctx.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo got = pm.getPackageArchiveInfo(f.getPath(), flags);
        if (got == null) return "The download isn't a valid app.";
        if (!ctx.getPackageName().equals(got.packageName)) return "The download is a different app.";
        try {
            PackageInfo mine = pm.getPackageInfo(ctx.getPackageName(), flags);
            long gv = Build.VERSION.SDK_INT >= 28 ? got.getLongVersionCode() : got.versionCode;
            long mv = Build.VERSION.SDK_INT >= 28 ? mine.getLongVersionCode() : mine.versionCode;
            if (gv <= mv) return "This TV already has that version or a newer one.";
            if (!Arrays.equals(certs(got), certs(mine))) {
                return "This release is signed with a different key than the launcher on this TV, so Android won't "
                        + "update it in place. Install it once from a computer (it starts fresh), and updates work from here after.";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "Couldn't read this launcher's own details.";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] certs(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
            Signature[] s = p.signingInfo.hasMultipleSigners() ? p.signingInfo.getApkContentsSigners()
                    : p.signingInfo.getSigningCertificateHistory();
            return s == null ? new Signature[0] : new Signature[]{s[s.length - 1]};
        }
        return p.signatures == null ? new Signature[0] : p.signatures;
    }

    /** Whether Android lets this launcher install apps; the first time, the user turns it on in the page this opens. */
    static boolean canInstall(Activity a) {
        if (Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls()) return true;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            Toast.makeText(a, "Allow this launcher to install apps, then press Back and choose Install again.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Turn on Install unknown apps for this launcher in Settings, then try again.", Toast.LENGTH_LONG).show();
        }
        return false;
    }

    /** Hands the downloaded APK to Android's installer; it asks the user to confirm, then updates in place. */
    static String install(Context ctx) {
        File f = apk(ctx);
        if (!f.exists()) return "Download the update first.";
        String bad = verify(ctx, f);
        if (bad != null) return bad;
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        try {
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(f); OutputStream o = s.openWrite("base.apk", 0, f.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    s.fsync(o);
                }
                Intent status = new Intent(ctx, UpdateReceiver.class).setAction(ACTION_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(ctx, id, status, flags).getIntentSender());
            }
            return null;
        } catch (Exception e) {
            return "Android couldn't start the install.";
        }
    }

    /** Android's installer reports here: it needs the user to confirm, it finished, or it failed. */
    public static final class UpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                apk(ctx).delete();
            } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(ctx, "The update didn't install" + (msg == null ? "." : ": " + msg), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
