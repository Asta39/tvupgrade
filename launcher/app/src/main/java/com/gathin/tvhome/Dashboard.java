package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The welcome screen's dashboard data (this fork), fetched off the main thread and cached so the screen can ask as
 * often as it likes:
 * <ul>
 * <li>weather: now, the next six hours and today's sunrise and sunset (Open-Meteo, no key, ≤ every 30 min);</li>
 * <li>tonight's plan: three things to do nearby this evening, from the host's Groq key, once a day after noon;</li>
 * <li>trending: a page of the swipe deck's popular titles on this TV's apps, ≤ once an hour.</li>
 * </ul>
 */
final class Dashboard {
    private static JSONObject weather;
    private static long weatherAt;
    private static JSONArray trending;
    private static long trendingAt;

    private Dashboard() {
    }

    static synchronized JSONObject weather() {
        if (weather != null && System.currentTimeMillis() - weatherAt < 30 * 60_000) return weather;
        if (Config.weatherLat == 0 && Config.weatherLon == 0) return null;
        try {
            weather = new JSONObject(HostServer.get("https://api.open-meteo.com/v1/forecast?latitude=" + Config.weatherLat
                    + "&longitude=" + Config.weatherLon + "&current=temperature_2m,weather_code,is_day"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&forecast_days=2&timezone=auto", null, null));
            weatherAt = System.currentTimeMillis();
        } catch (Exception e) {
            android.util.Log.w("Dashboard", "weather", e);
        }
        return weather;
    }

    /** Up to three short lines, or null without a key (or before the first answer). Cached per day in stay.json. */
    static JSONArray tonight(Context ctx) {
        JSONObject s = Stay.json(ctx);
        String today = LocalDate.now().toString();
        JSONObject plan = s.optJSONObject("plan");
        if (plan != null && today.equals(plan.optString("date"))) return plan.optJSONArray("lines");
        String key = s.optString("ai_key", "");
        if (key.isEmpty() || LocalTime.now().getHour() < 12) return null;
        String where = Config.weatherCity.isEmpty() ? "the area" : Config.weatherCity;
        String system = "You suggest an evening for holiday guests staying in or near " + where + ". Reply with exactly three lines and "
                + "nothing else, each under 48 characters: an emoji, then a specific real place and what to do there, e.g. "
                + "\"🍝 Dinner at Mama Rosa's, by the harbour\". One for dinner, one for a drink or a view at sunset, one for later "
                + "or something relaxed. No numbering, no markdown.";
        for (String model : HostServer.MODELS) {
            try {
                JSONObject req = new JSONObject().put("model", model).put("temperature", 0.7).put("max_tokens", 300)
                        .put("messages", new JSONArray()
                                .put(new JSONObject().put("role", "system").put("content", system))
                                .put(new JSONObject().put("role", "user").put("content", "Plan tonight, " + LocalDate.now().getDayOfWeek() + ".")));
                if (model.startsWith("openai/gpt-oss")) req.put("reasoning_effort", "low");
                JSONObject res = new JSONObject(HostServer.get("https://api.groq.com/openai/v1/chat/completions", req.toString(), key));
                String text = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
                JSONArray lines = new JSONArray();
                for (String l : text.split("\n")) {
                    l = l.replaceAll("^[-*•\\d.)\\s]+", "").replace("**", "").trim();
                    if (!l.isEmpty() && lines.length() < 3) lines.put(l.length() > 60 ? l.substring(0, 59) + "…" : l);
                }
                if (lines.length() == 0) continue;
                JSONObject fresh = Stay.json(ctx);
                fresh.put("plan", new JSONObject().put("date", today).put("lines", lines));
                Stay.write(ctx, fresh);
                return lines;
            } catch (java.io.IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (!m.startsWith("400") && !m.startsWith("404")) break;
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }

    /** Popular titles on this TV's apps, each with a play id (Watch), for the welcome screen's poster shelf. */
    static synchronized JSONArray trending(Context ctx) {
        if (trending != null && System.currentTimeMillis() - trendingAt < 3600_000) return trending;
        try {
            JSONArray all = new JSONArray();
            java.util.Set<String> keys = new java.util.HashSet<>();
            // Two pages from different apps (discover picks one app at random each time), so one service doesn't fill it.
            for (int i = 0; i < 2 && all.length() < 12; i++) {
                JSONArray cards = Watch.discover(ctx, "", 0).optJSONArray("cards");
                if (cards != null) for (int k = 0; k < cards.length() && all.length() < 12; k++) {
                    JSONObject c = cards.getJSONObject(k);
                    if (keys.add(c.optString("key"))) all.put(c);
                }
            }
            if (all.length() > 0) {
                trending = all;
                trendingAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            android.util.Log.w("Dashboard", "trending", e);
        }
        return trending;
    }
}
