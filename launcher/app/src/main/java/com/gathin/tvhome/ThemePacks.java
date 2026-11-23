package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;

/**
 * Theme packs (this fork), switched from Control Center: each is a few theme settings laid over the TV's own
 * config.json, so the theme itself never changes and Dusk gives it back exactly.
 * <ul>
 * <li>Dusk: the theme as it is.</li>
 * <li>Mono: black-and-white wallpapers, a little darker, white glass.</li>
 * <li>Cinema: darker, warmer wallpapers and a gold focus glow.</li>
 * <li>Seasonal: the glow follows the season (winter blue, spring green, summer amber, autumn orange, December red).</li>
 * </ul>
 */
final class ThemePacks {
    static final String[] NAMES = {"Dusk", "Mono", "Cinema", "Seasonal"};
    private static final String PREFS = "theme_pack", KEY = "pack";

    /** Read in Config.load: the glow colour (0 for the theme's own) and the wallpaper filter ("", mono or warm). */
    static int accent;
    static String filter = "";

    private ThemePacks() {
    }

    static String current(Context ctx) {
        String p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, NAMES[0]);
        for (String n : NAMES) if (n.equals(p)) return n;
        return NAMES[0];
    }

    /** The next pack in the cycle; returns its name. */
    static String next(Context ctx) {
        String now = current(ctx);
        int i = 0;
        while (i < NAMES.length && !NAMES[i].equals(now)) i++;
        String n = NAMES[(i + 1) % NAMES.length];
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, n).apply();
        return n;
    }

    /** Lays the current pack over the theme's settings, and sets accent and filter. */
    static void apply(Context ctx, JSONObject json) {
        accent = 0;
        filter = "";
        if (json == null) return;
        try {
            switch (current(ctx)) {
                case "Mono":
                    filter = "mono";
                    json.put("wallpaper_dim", Math.max(20, json.optInt("wallpaper_dim", 0)));
                    break;
                case "Cinema":
                    filter = "warm";
                    accent = 0xFFD4AF37;
                    json.put("wallpaper_dim", Math.max(38, json.optInt("wallpaper_dim", 0)));
                    break;
                case "Seasonal":
                    accent = seasonal(LocalDate.now());
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    static int seasonal(LocalDate d) {
        int m = d.getMonthValue();
        if (m == 12) return 0xFFE5484D;                 // December: festive red
        if (m <= 2) return 0xFF7FB2FF;                  // winter blue
        if (m <= 5) return 0xFF7ED957;                  // spring green
        if (m <= 8) return 0xFFFFB23C;                  // summer amber
        return 0xFFE07A2E;                              // autumn orange
    }

    /** The colour filter for the wallpaper, or null for none. */
    static android.graphics.ColorFilter wallpaperFilter() {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        if ("mono".equals(filter)) {
            m.setSaturation(0f);
        } else if ("warm".equals(filter)) {
            m.setSaturation(0.75f);
            android.graphics.ColorMatrix warm = new android.graphics.ColorMatrix(new float[]{
                    1.06f, 0, 0, 0, 6,
                    0, 1.0f, 0, 0, 0,
                    0, 0, 0.88f, 0, -6,
                    0, 0, 0, 1, 0});
            m.postConcat(warm);
        } else {
            return null;
        }
        return new android.graphics.ColorMatrixColorFilter(m);
    }
}
