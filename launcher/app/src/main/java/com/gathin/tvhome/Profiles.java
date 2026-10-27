package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This fork's people on one TV (theme key "profiles"): each has a name, a dock and a set of wallpapers.
 * <pre>
 * "profiles": [
 *   {"name": "Ian",  "dock_apps": ["com.netflix.ninja", ...], "wallpapers": ["01-forest-rays.jpg", ...]},
 *   {"name": "Bond", "dock_apps": [...], "wallpapers": [...]}
 * ]
 * </pre>
 * Control Center switches between them; the choice is kept across restarts, and Home rebuilds with it.
 */
final class Profiles {
    static final class Profile {
        final String name;
        final List<String> dockApps, wallpapers;
        /** A picture in the theme's avatars/ folder, e.g. "ian.png"; empty for the initial on a colour. */
        final String avatar;

        Profile(String name, List<String> dockApps, List<String> wallpapers, String avatar) {
            this.name = name;
            this.avatar = avatar;
            this.dockApps = dockApps;
            this.wallpapers = wallpapers;
        }
    }

    private static final String PREFS = "profiles", ACTIVE = "active";
    private static List<Profile> list = Collections.emptyList();
    private static int active;

    private Profiles() {
    }

    /** Read from the theme overlay; called by Config.load before it settles the dock and wallpapers. */
    static void load(Context ctx, JSONObject json) {
        List<Profile> out = new ArrayList<>();
        JSONArray arr = json != null ? json.optJSONArray("profiles") : null;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optString("name").trim().isEmpty()) continue;
                out.add(new Profile(o.optString("name").trim(), strings(o.optJSONArray("dock_apps")),
                        strings(o.optJSONArray("wallpapers")), o.optString("avatar").trim()));
            }
        }
        list = Collections.unmodifiableList(out);
        int saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(ACTIVE, 0);
        active = out.isEmpty() ? 0 : Math.max(0, Math.min(saved, out.size() - 1));
    }

    static List<Profile> all() {
        return list;
    }

    static Profile current() {
        return list.isEmpty() ? null : list.get(active);
    }

    static int activeIndex() {
        return active;
    }

    /** Remember the choice; the caller recreates Home so the dock and wallpapers follow. */
    static void select(Context ctx, int index) {
        if (index < 0 || index >= list.size()) return;
        active = index;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(ACTIVE, index).commit();
        ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).edit().remove("image").apply();
    }

    /** The current profile's dock, or the theme's own when the profile names none. */
    static List<String> dock(List<String> fallback) {
        Profile p = current();
        return p != null && !p.dockApps.isEmpty() ? p.dockApps : fallback;
    }

    /** Only the current profile's pictures; all of them when it names none, or none of its names exist. */
    static List<File> wallpapers(List<File> all) {
        Profile p = current();
        if (p == null || p.wallpapers.isEmpty()) return all;
        List<File> mine = new ArrayList<>();
        for (File f : all) if (p.wallpapers.contains(f.getName())) mine.add(f);
        return mine.isEmpty() ? all : mine;
    }

    /** The profile's picture, decoded small; null without one. */
    static android.graphics.Bitmap avatar(Context ctx, Profile p) {
        File dir = p == null || p.avatar.isEmpty() ? null : Config.dir(ctx);
        if (dir == null) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = 1;
        return android.graphics.BitmapFactory.decodeFile(new File(new File(dir, "avatars"), p.avatar).getPath(), o);
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
