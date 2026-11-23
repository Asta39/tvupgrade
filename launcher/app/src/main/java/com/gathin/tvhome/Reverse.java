package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Go back to the TV's own home screen": undoes what setup did, on the TV itself, without uninstalling this app.
 *
 * - Home goes back to the TV's own launcher, and this launcher's Home button and Sleep helpers are switched off.
 * - The screensaver goes back to what it was (or the TV's default).
 * - The apps the clean-up turned off are turned back on, unless the user chose to keep them off.
 * - The theme is put aside, so opening this app again starts the welcome steps, and it becomes Home only once
 *   they are finished (Setup.grant).
 *
 * Needs the TV's own ADB (USB / Network debugging), like setup. scripts/restore.sh does the same from a computer.
 */
final class Reverse {
    private static final String PREFS = "reverse";
    /** Setup.disable records here every app it turned off, so they can be turned back on exactly. */
    static final String DISABLED = "disabled_by_setup";
    /** Setup.grant records the screensaver it replaced. */
    static final String OLD_SCREENSAVER = "old_screensaver";

    private Reverse() {
    }

    static void recordDisabled(Context ctx, String pkg) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> s = new HashSet<>(p.getStringSet(DISABLED, new HashSet<>()));
        s.add(pkg);
        p.edit().putStringSet(DISABLED, s).apply();
    }

    static void recordScreensaver(Context ctx, String value) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains(OLD_SCREENSAVER)) p.edit().putString(OLD_SCREENSAVER, value == null ? "" : value).apply();
    }

    /** Whether ADB on the TV is reachable, so the reverse can run. */
    static boolean possible() {
        return LocalAdb.portOpen();
    }

    /**
     * Runs the whole reverse. keepAppsOff: leave the cleaned-up apps turned off. Returns what didn't work (empty: all
     * done). Call off the main thread: it talks to the TV's ADB, which may first ask the user to allow it.
     */
    static List<String> run(Context ctx, boolean keepAppsOff) {
        List<String> failed = new ArrayList<>();
        StringBuilder log = new StringBuilder("reverse keepAppsOff=" + keepAppsOff + "\n");
        String pkg = ctx.getPackageName();
        try (LocalAdb adb = LocalAdb.connect(ctx, 30_000)) {
            // Read everything first, in one go.
            String helpers = adb.shell("settings get secure enabled_accessibility_services").trim();
            String saver = adb.shell("settings get secure screensaver_components").trim();
            String disabled = keepAppsOff ? "" : adb.shell("pm list packages -d");
            log.append("helpers=").append(helpers).append("\nsaver=").append(saver).append('\n');

            // Then the changes, as one shell script.
            List<String> cmds = new ArrayList<>();
            // 1. The TV's own home screens and setup app on (they should never have been off).
            for (String p : Setup.NEVER_OFF) cmds.add("pm enable " + p + " >/dev/null 2>&1");
            // 2. This launcher's helpers off first, so the Home helper can't send the TV straight back here.
            List<String> keep = new ArrayList<>();
            if (!"null".equals(helpers)) for (String h : helpers.split(":")) {
                if (!h.trim().isEmpty() && !h.trim().startsWith(pkg + "/")) keep.add(h.trim());
            }
            cmds.add(keep.isEmpty() ? "settings delete secure enabled_accessibility_services"
                    : "settings put secure enabled_accessibility_services " + String.join(":", keep));
            // 3. Home goes to the TV's own launcher.
            ComponentName stock = stockHomeActivity(ctx);
            if (stock != null) cmds.add("cmd package set-home-activity --user 0 " + stock.flattenToShortString());
            // 4. The screensaver it had before StandBy, or the TV's default.
            if (saver.startsWith(pkg + "/")) {
                String old = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(OLD_SCREENSAVER, "");
                cmds.add(old != null && !old.isEmpty() && !"null".equals(old) && !old.startsWith(pkg + "/")
                        ? "settings put secure screensaver_components " + old
                        : "settings delete secure screensaver_components");
            }
            // 5. The cleaned-up apps back on, unless the user wants them kept off.
            if (!keepAppsOff) {
                Set<String> off = new HashSet<>();
                for (String line : disabled.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("package:")) off.add(t.substring(8).trim());
                }
                Set<String> ours = new HashSet<>(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getStringSet(DISABLED, new HashSet<>()));
                // A TV cleaned up from a computer (scripts/debloat.sh) has no record: use the bundled lists.
                ours.addAll(listed(ctx));
                for (String p : ours) if (off.contains(p)) cmds.add("pm enable " + p);
            }
            String script = String.join("; ", cmds);
            log.append("run: ").append(script).append('\n');
            log.append("out: ").append(adb.shell(script)).append('\n');

            // Check it took.
            String helpersNow = adb.shell("settings get secure enabled_accessibility_services").trim();
            String homeNow = adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim();
            log.append("helpers now=").append(helpersNow).append("\nhome now=").append(homeNow).append('\n');
            if (helpersNow.contains(pkg + "/")) failed.add("Switching off the helpers");
            if (stock != null && homeNow.contains(pkg + "/")) failed.add("Home button");
            if (!keepAppsOff) {
                String stillOff = adb.shell("pm list packages -d");
                for (String p : listed(ctx)) if (stillOff.contains("package:" + p + "\n") && !keepAppsOff) {
                    // Only report ones we tried.
                    if (script.contains("pm enable " + p)) failed.add("Turning " + p + " back on");
                }
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DISABLED).apply();
            }
            // So the Home helper isn't switched back on the next time this app opens (ServiceRepair).
            ctx.getSharedPreferences("accessibility_helpers", Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception e) {
            android.util.Log.w("Reverse", "reverse failed", e);
            log.append("error: ").append(e).append('\n');
            writeLog(ctx, log);
            failed.add("Connecting to the TV (is USB / Network debugging on?)");
            return failed;
        }
        log.append("failed=").append(failed).append('\n');
        writeLog(ctx, log);
        // Home or the helpers didn't change: leave this launcher set up as it was, rather than half undone.
        if (failed.contains("Home button") || failed.contains("Switching off the helpers")) return failed;
        // 6. Setup again next time: put the theme aside (kept, not deleted) and forget that setup finished.
        File dir = Config.dir(ctx);
        if (dir != null) {
            File theme = new File(dir, Config.FILE_NAME);
            File aside = new File(dir, Config.FILE_NAME + ".before-reverse");
            if (theme.exists()) {
                aside.delete();
                if (!theme.renameTo(aside)) failed.add("Resetting the setup");
            }
        }
        ctx.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("done", false).apply();
        return failed;
    }

    /** reverse.log next to the theme, for checking what happened. */
    private static void writeLog(Context ctx, StringBuilder log) {
        File dir = Config.dir(ctx);
        if (dir == null) return;
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "reverse.log"))) {
            o.write(log.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Opens the TV's own home screen, once the reverse is done. */
    static void openStockHome(Context ctx) {
        ComponentName stock = stockHomeActivity(ctx);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (stock != null) home.setComponent(stock);
        try {
            ctx.startActivity(home);
        } catch (Exception ignored) {
        }
    }

    /** The TV's own launcher's Home activity: any Home that isn't this app, Android's chooser or a setup wizard. */
    static ComponentName stockHomeActivity(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ComponentName best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (ResolveInfo r : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            String p = r.activityInfo.packageName;
            if (p.equals(ctx.getPackageName()) || p.equals("android") || p.contains("setup") || p.contains("provision")) continue;
            if (r.priority > bestPriority) {
                bestPriority = r.priority;
                best = new ComponentName(p, r.activityInfo.name);
            }
        }
        return best;
    }

    /** Every package in the debloat lists bundled with the app. */
    static Set<String> listed(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            String[] lists = ctx.getAssets().list("debloat");
            if (lists == null) return out;
            for (String name : lists) {
                String text;
                try (InputStream in = ctx.getAssets().open("debloat/" + name)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    text = b.toString(StandardCharsets.UTF_8.name());
                }
                for (String line : text.split("\n")) {
                    String t = line.trim();
                    int hash = t.indexOf('#');
                    if (hash >= 0) t = t.substring(0, hash).trim();
                    int bar = t.indexOf('|');
                    if (bar >= 0) t = t.substring(0, bar).trim();
                    if (t.matches("[A-Za-z0-9._]+")) out.add(t);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** One reverse at a time, whether it was asked for on the TV or from a phone. */
    private static volatile boolean running;

    /** Runs the reverse in the background, then opens the TV's own home. done gets what didn't work. */
    static boolean start(Context ctx, boolean keepAppsOff, java.util.function.Consumer<List<String>> done) {
        if (running) return false;
        running = true;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            List<String> failed;
            try {
                failed = run(app, keepAppsOff);
            } finally {
                running = false;
            }
            if (!failed.contains("Home button") && !failed.contains("Switching off the helpers")
                    && (failed.isEmpty() || !failed.get(0).startsWith("Connecting"))) openStockHome(app);
            if (done != null) done.accept(failed);
        }, "reverse").start();
        return true;
    }

    /** The two questions on the TV: are you sure, then keep the cleaned-up apps off or turn them back on. */
    static void ask(android.app.Activity a) {
        // The port check opens a socket: never on the main thread.
        new Thread(() -> {
            boolean ok = possible();
            a.runOnUiThread(() -> {
                if (!a.isFinishing()) askNow(a, ok);
            });
        }, "reverse-check").start();
    }

    private static void askNow(android.app.Activity a, boolean possible) {
        if (!possible) {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Turn on debugging first")
                    .setMessage("Going back to the TV's own home screen needs USB or Network debugging, the same as "
                            + "setup. Turn it on in Developer options, then try again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new android.app.AlertDialog.Builder(a)
                .setTitle("Go back to the TV's own home screen?")
                .setMessage("Home goes back to the TV's original launcher and this launcher's helpers are switched off. "
                        + "The app stays installed: open it again whenever you like, and it starts with the setup.")
                .setPositiveButton("Continue", (d, w) -> new android.app.AlertDialog.Builder(a)
                        .setTitle("Keep the clutter turned off?")
                        .setMessage("Setup turned off apps that show ads or track what you watch. Keep them off, "
                                + "or turn everything back on as it was?")
                        .setPositiveButton("Keep them off", (d2, w2) -> go(a, true))
                        .setNegativeButton("Turn everything on", (d2, w2) -> go(a, false))
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void go(android.app.Activity a, boolean keepAppsOff) {
        android.widget.Toast.makeText(a, "Going back to the TV's own home screen…", android.widget.Toast.LENGTH_LONG).show();
        start(a, keepAppsOff, failed -> a.runOnUiThread(() -> {
            if (!failed.isEmpty()) android.widget.Toast.makeText(a, "Didn't finish: " + String.join(", ", failed),
                    android.widget.Toast.LENGTH_LONG).show();
        }));
    }
}
