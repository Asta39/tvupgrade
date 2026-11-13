package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * First-run setup without a computer (this fork): what SetupActivity's steps collect, and how it becomes a theme.
 * The APK carries the default theme (wallpapers/, depth/ and a base config.json in its assets); the choices made
 * on the TV (people, their apps) are written over that base into the same folder scripts/push-theme.sh fills.
 * With debugging on, LocalAdb then does what scripts/grant.sh and install-launcher.sh do from a computer.
 */
final class Setup {
    /** Most apps one home row holds (the dock's columns). */
    static final int MAX_APPS = 8;
    static final int MAX_PEOPLE = 4;

    static final class App {
        final String pkg, label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    static final class Person {
        String name = "";
        final List<String> apps = new ArrayList<>();
    }

    private Setup() {
    }

    /** No theme on this TV yet and setup never finished: Home opens the welcome steps first. */
    static boolean needed(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null || new File(dir, Config.FILE_NAME).exists()) return false;
        return !ctx.getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("done", false);
    }

    /** Every app the TV can open from a home screen, by name; this launcher itself left out. */
    static List<App> apps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || found.containsKey(pkg)) continue;
                Drawable icon = r.activityInfo.loadIcon(pm);
                found.put(pkg, new App(pkg, String.valueOf(r.loadLabel(pm)), icon));
            }
        }
        List<App> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    /**
     * Writes the theme: the bundled wallpapers and cut-outs, and config.json with these people and their apps.
     * One person is simply the home row; two or more become profiles (Profiles).
     */
    static void write(Context ctx, List<Person> people) throws Exception {
        File dir = Config.dir(ctx);
        if (dir == null) throw new java.io.IOException("no storage for the theme");
        for (String folder : new String[]{"wallpapers", "depth"}) {
            String[] names = ctx.getAssets().list(folder);
            if (names == null) continue;
            File to = new File(dir, folder);
            to.mkdirs();
            for (String name : names) copy(ctx, folder + "/" + name, new File(to, name));
        }
        JSONObject json = new JSONObject(new String(read(ctx.getAssets().open(Config.FILE_NAME)), StandardCharsets.UTF_8));
        // The base is one household's theme: its people, its city and its name are not this TV's.
        for (String own : new String[]{"profiles", "greeting_name", "weather_city", "weather_lat", "weather_lon"}) json.remove(own);
        List<Person> named = new ArrayList<>();
        for (Person p : people) if (!p.apps.isEmpty()) named.add(p);
        if (!named.isEmpty()) json.put("dock_apps", new JSONArray(named.get(0).apps));
        // The home row holds what was picked and nothing else: no topping up from the other apps.
        json.put("fill_dock", false);
        if (named.size() > 1) {
            JSONArray profiles = new JSONArray();
            for (int i = 0; i < named.size(); i++) {
                Person p = named.get(i);
                String name = p.name.trim().isEmpty() ? "Person " + (i + 1) : p.name.trim();
                profiles.put(new JSONObject().put("name", name).put("dock_apps", new JSONArray(p.apps))
                        .put("wallpapers", new JSONArray()));
            }
            json.put("profiles", profiles);
        }
        try (OutputStream o = new FileOutputStream(new File(dir, Config.FILE_NAME))) {
            o.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        try (OutputStream o = new FileOutputStream(new File(dir, "theme_revision"))) {
            o.write(UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8));
        }
        ctx.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("done", true).apply();
    }

    /**
     * What scripts/grant.sh and install-launcher.sh do, run on the TV itself: permissions for Now Playing, Control
     * Center and StandBy, then Home. Returns one line per thing that did not work (empty: all done).
     */
    static List<String> grant(Context ctx, LocalAdb adb) throws Exception {
        String pkg = ctx.getPackageName();
        List<String> failed = new ArrayList<>();
        run(adb, failed, "Now Playing", "cmd notification allow_listener " + pkg + "/" + GuardRevival.class.getName());
        run(adb, failed, "Do Not Disturb", "cmd notification allow_dnd " + pkg);
        run(adb, failed, "Brightness", "appops set " + pkg + " WRITE_SETTINGS allow");
        run(adb, failed, "TV listings", "pm grant " + pkg + " android.permission.READ_TV_LISTINGS");
        run(adb, failed, "Settings access", "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
        // Sleep, Night Shift and Screen off: add the service to whatever accessibility services are already on.
        // The setting may hold the short form (pkg/.SleepService) or the long one; either counts as already on.
        String service = new android.content.ComponentName(ctx, SleepService.class).flattenToShortString();
        String on = adb.shell("settings get secure enabled_accessibility_services");
        if (on.isEmpty() || "null".equals(on)) on = service;
        else if (!on.contains(service) && !on.contains(pkg + "/" + SleepService.class.getName())) on = on + ":" + service;
        run(adb, failed, "Sleep and Night Shift", "settings put secure enabled_accessibility_services " + on);
        adb.shell("settings put secure accessibility_enabled 1");
        run(adb, failed, "StandBy screensaver", "settings put secure screensaver_components " + pkg + "/" + StandByDream.class.getName());
        String home = adb.shell("cmd package set-home-activity --user 0 " + pkg + "/" + MainActivity.class.getName());
        if (!home.contains("Success")) failed.add("Home button");
        return failed;
    }

    private static void run(LocalAdb adb, List<String> failed, String what, String command) throws Exception {
        String out = adb.shell(command).toLowerCase();
        if (out.contains("exception") || out.contains("error") || out.contains("denied")) failed.add(what);
    }

    /**
     * The tested clean-up list for this exact TV model (assets debloat/, named in its "# model:" line), or empty:
     * turning packages off on a model nobody tested can stop a TV from starting, so there is no generic list.
     */
    static List<String> cleanup(Context ctx) {
        List<String> packages = new ArrayList<>();
        try {
            String[] lists = ctx.getAssets().list("debloat");
            if (lists == null) return packages;
            for (String name : lists) {
                String text = new String(read(ctx.getAssets().open("debloat/" + name)), StandardCharsets.UTF_8);
                boolean mine = false;
                List<String> found = new ArrayList<>();
                for (String line : text.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("# model:")) mine = isThisTv(t.substring(8).trim());
                    int hash = t.indexOf('#');
                    if (hash >= 0) t = t.substring(0, hash).trim();
                    if (t.matches("[A-Za-z0-9._]+")) found.add(t);
                }
                if (mine) return found;
            }
        } catch (Exception ignored) {
        }
        return packages;
    }

    /** Firmware names a TV differently in its model, device and product fields; a list may use any of them. */
    private static boolean isThisTv(String name) {
        return name.equalsIgnoreCase(Build.MODEL.trim()) || name.equalsIgnoreCase(Build.DEVICE.trim())
                || name.equalsIgnoreCase(Build.PRODUCT.trim());
    }

    /** Turns the packages off for this user (never uninstalls); returns how many were turned off. */
    static int disable(LocalAdb adb, List<String> packages) throws Exception {
        int done = 0;
        for (String p : packages) {
            if (adb.shell("pm disable-user --user 0 " + p).contains("disabled-user")) done++;
        }
        return done;
    }

    private static void copy(Context ctx, String asset, File to) throws Exception {
        try (InputStream in = ctx.getAssets().open(asset); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }
}
