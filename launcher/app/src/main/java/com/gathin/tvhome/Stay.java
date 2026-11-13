package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Guest mode's data (this fork): the place, its Wi-Fi and house rules, and the current stay, kept in stay.json
 * beside the theme (Config.dir) so the phone page (phase 2) can write it the same way setup does.
 * <pre>
 * {"mode": "guest" | "home",
 *  "place": "The Ridge", "about": "Light the fire...", "wifi_name": "...", "wifi_password": "...",
 *  "rules": ["No smoking inside", ...], "checkin": "15:00", "checkout": "10:00", "apps": ["com.netflix.ninja", ...],
 *  "door_code": "4821", "locked": true, "pin_sha256": "...",
 *  "guest": {"names": "Sarah and Tom", "arrive": "2026-11-14", "depart": "2026-11-17"}}
 * </pre>
 * Between stays (no guest, or today outside arrive..depart) the TV shows only the place: no names, no door code.
 */
final class Stay {
    static final String FILE_NAME = "stay.json";

    final boolean guestMode;
    final String place, about, wifiName, wifiPassword, doorCode, checkin, checkout;
    final List<String> rules, apps;
    /** The guest's names while a stay is on today, else empty. */
    final String names;
    /** Today within the stay: 1-based night and the number of nights; 0 between stays. */
    final int night, nights;
    final LocalDate arrive, depart;

    private Stay(JSONObject j) {
        guestMode = "guest".equals(j.optString("mode"));
        place = j.optString("place", "").trim();
        about = j.optString("about", "").trim();
        wifiName = j.optString("wifi_name", "").trim();
        wifiPassword = j.optString("wifi_password", "").trim();
        checkin = j.optString("checkin", "15:00").trim();
        checkout = j.optString("checkout", "10:00").trim();
        rules = strings(j.optJSONArray("rules"));
        apps = strings(j.optJSONArray("apps"));
        JSONObject g = j.optJSONObject("guest");
        LocalDate a = null, d = null;
        try {
            if (g != null) {
                a = LocalDate.parse(g.optString("arrive"));
                d = LocalDate.parse(g.optString("depart"));
            }
        } catch (Exception ignored) {
            a = d = null;
        }
        LocalDate today = LocalDate.now();
        boolean on = a != null && d != null && !today.isBefore(a) && !today.isAfter(d)
                // Check-out day ends at check-out time: after it the next guests' TV must not greet the last ones.
                && !(today.equals(d) && LocalTime.now().isAfter(time(checkout).plusHours(1)));
        arrive = on ? a : null;
        depart = on ? d : null;
        names = on ? g.optString("names", "").trim() : "";
        nights = on ? (int) Math.max(1, ChronoUnit.DAYS.between(a, d)) : 0;
        night = on ? (int) Math.min(nights, ChronoUnit.DAYS.between(a, today) + 1) : 0;
        doorCode = on ? j.optString("door_code", "").trim() : "";
    }

    boolean staying() {
        return arrive != null;
    }

    boolean arrivingToday() {
        return staying() && arrive.equals(LocalDate.now());
    }

    boolean leavingToday() {
        return staying() && depart.equals(LocalDate.now());
    }

    static Stay load(Context ctx) {
        return new Stay(json(ctx));
    }

    static boolean guestMode(Context ctx) {
        return "guest".equals(json(ctx).optString("mode"));
    }

    /** Switches between Home and Guest mode, keeping everything else in stay.json. */
    static void setMode(Context ctx, boolean guest) {
        try {
            JSONObject j = json(ctx);
            j.put("mode", guest ? "guest" : "home");
            write(ctx, j);
        } catch (Exception ignored) {
        }
    }

    static JSONObject json(Context ctx) {
        File f = file(ctx);
        try {
            if (f != null && f.exists()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    static void write(Context ctx, JSONObject j) throws Exception {
        File f = file(ctx);
        if (f == null) throw new java.io.IOException("no storage");
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static File file(Context ctx) {
        File dir = Config.dir(ctx);
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    /** The PIN as stored ("pin_sha256"): never the PIN itself. */
    static String sha256(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static LocalTime time(String hhmm) {
        try {
            return LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
        } catch (Exception e) {
            return LocalTime.of(10, 0);
        }
    }

    /** "15:00" as "3pm", "10:30" as "10:30am": how a stay card says it. */
    static String friendly(String hhmm) {
        LocalTime t = time(hhmm);
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        String ap = t.getHour() < 12 ? "am" : "pm";
        return t.getMinute() == 0 ? h + ap : h + ":" + String.format("%02d", t.getMinute()) + ap;
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
