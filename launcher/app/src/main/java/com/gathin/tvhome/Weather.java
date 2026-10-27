package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This fork's weather (theme keys "weather_city", "weather_lat", "weather_lon"): a small glass pill in the top-left
 * corner with the condition and temperature, and, on OK, an Apple Weather–style card with the next six hours and
 * five days with temperature range bars. Data from Open-Meteo (free, no key), fetched at most every 30 minutes and
 * only while Home is in front; the last answer is cached so the pill is never empty after a restart.
 */
final class Weather {
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final String city;
    private final double lat, lon;
    private final float dp;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View pill;
    private final ImageView pillIcon;
    private final TextView pillTemp;
    private JSONObject data;

    Weather(Activity activity, FrameLayout root, String city, double lat, double lon) {
        this.activity = activity;
        this.city = city;
        this.lat = lat;
        this.lon = lon;
        dp = activity.getResources().getDisplayMetrics().density;
        prefs = activity.getSharedPreferences("weather", Context.MODE_PRIVATE);

        LinearLayout p = new LinearLayout(activity);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setFocusable(true);
        p.setClickable(true);
        p.setPadding(px(12), 0, px(16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x2EFFFFFF);
        bg.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
        bg.setCornerRadius(px(21));
        p.setBackground(bg);
        pillIcon = new ImageView(activity);
        p.addView(pillIcon, new LinearLayout.LayoutParams(px(22), px(22)));
        pillTemp = text(17, Color.WHITE, R.string.font_medium);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = px(8);
        p.addView(pillTemp, tl);
        p.setVisibility(View.INVISIBLE);
        p.setOnClickListener(v -> openCard());
        p.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? Color.WHITE : 0x2EFFFFFF);
            pillTemp.setTextColor(f ? 0xFF1C1C1E : Color.WHITE);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        });
        // The same height and line as the top bar's round buttons, in the opposite corner.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(42), Gravity.TOP | Gravity.START);
        lp.leftMargin = px(56);
        lp.topMargin = px(44);
        root.addView(p, lp);
        pill = p;
        // Left from the Control Center button lands here, across the empty top bar; Right goes back.
        p.setId(View.generateViewId());
        View gear = activity.findViewById(R.id.btnSettings);
        if (gear != null) {
            gear.setNextFocusLeftId(p.getId());
            p.setNextFocusRightId(R.id.btnSettings);
        }
        p.setNextFocusDownId(View.NO_ID);

        try {
            String cached = prefs.getString("json", null);
            if (cached != null) show(new JSONObject(cached));
        } catch (Exception ignored) {
        }
    }

    /** Home came to the front: fetch if the last answer is older than 30 minutes. */
    void refresh() {
        if (System.currentTimeMillis() - prefs.getLong("at", 0) < REFRESH_MS && data != null) return;
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=5&forecast_hours=7";
        IO.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                } finally {
                    c.disconnect();
                }
                JSONObject j = new JSONObject(out.toString("UTF-8"));
                prefs.edit().putString("json", j.toString()).putLong("at", System.currentTimeMillis()).apply();
                main.post(() -> show(j));
            } catch (Exception offline) {
                // Keep the cached reading; the next resume tries again.
            }
        });
    }

    private void show(JSONObject j) {
        data = j;
        JSONObject cur = j.optJSONObject("current");
        if (cur == null) return;
        pillIcon.setImageResource(icon(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1));
        pillTemp.setText(Math.round(cur.optDouble("temperature_2m")) + "°");
        if (pill.getVisibility() != View.VISIBLE) {
            pill.setAlpha(0f);
            pill.setVisibility(View.VISIBLE);
            pill.animate().alpha(1f).setDuration(300).start();
        }
    }

    // ---- forecast card ------------------------------------------------------------------------------------

    private void openCard() {
        if (data == null) return;
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout stage = new FrameLayout(activity);
        FrostedLayout card = new FrostedLayout(activity, 28, 0x80000000);
        card.setPadding(px(24), px(22), px(24), px(22));
        card.setFocusable(true);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col);

        JSONObject cur = data.optJSONObject("current");
        JSONObject daily = data.optJSONObject("daily");
        JSONObject hourly = data.optJSONObject("hourly");

        // Header, as in the Weather app: place, big temperature, condition, today's high and low.
        TextView place = text(20, Color.WHITE, R.string.font_medium);
        place.setText(city);
        place.setGravity(Gravity.CENTER);
        col.addView(place, full());
        TextView big = text(64, Color.WHITE, R.string.font_light);
        big.setText(Math.round(cur.optDouble("temperature_2m")) + "°");
        big.setGravity(Gravity.CENTER);
        col.addView(big, full());
        TextView cond = text(15, 0xCCFFFFFF, R.string.font_medium);
        String hl = daily != null ? "  H:" + Math.round(arr(daily, "temperature_2m_max").optDouble(0))
                + "°  L:" + Math.round(arr(daily, "temperature_2m_min").optDouble(0)) + "°" : "";
        cond.setText(describe(cur.optInt("weather_code")) + hl);
        cond.setGravity(Gravity.CENTER);
        col.addView(cond, full());
        TextView feels = text(12, 0x99FFFFFF, R.string.font_medium);
        feels.setText("Feels like " + Math.round(cur.optDouble("apparent_temperature")) + "°");
        feels.setGravity(Gravity.CENTER);
        col.addView(feels, full());

        // Next hours.
        col.addView(section("HOURLY FORECAST"), top(18));
        LinearLayout hours = new LinearLayout(activity);
        JSONArray ht = arr(hourly, "time"), htemp = arr(hourly, "temperature_2m"), hcode = arr(hourly, "weather_code"),
                hrain = arr(hourly, "precipitation_probability"), hday = arr(hourly, "is_day");
        for (int i = 0; i < Math.min(6, ht.length()); i++) {
            LinearLayout h = new LinearLayout(activity);
            h.setOrientation(LinearLayout.VERTICAL);
            h.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView when = text(12, 0xCCFFFFFF, R.string.font_medium);
            when.setText(i == 0 ? "Now" : hour(ht.optString(i)));
            h.addView(when);
            ImageView ic = new ImageView(activity);
            ic.setImageResource(icon(hcode.optInt(i), hday.optInt(i, 1) == 1));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(24), px(24));
            il.topMargin = px(6);
            h.addView(ic, il);
            int rain = hrain.optInt(i);
            TextView pr = text(10, 0xFF64D2FF, R.string.font_medium);
            pr.setText(rain >= 30 ? rain + "%" : " ");
            h.addView(pr);
            TextView t = text(15, Color.WHITE, R.string.font_medium);
            t.setText(Math.round(htemp.optDouble(i)) + "°");
            h.addView(t);
            hours.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        col.addView(hours, top(8));

        // Five days with Apple Weather's range bars, all on one shared scale.
        col.addView(section("5-DAY FORECAST"), top(18));
        JSONArray dt = arr(daily, "time"), dmax = arr(daily, "temperature_2m_max"), dmin = arr(daily, "temperature_2m_min"),
                dcode = arr(daily, "weather_code");
        double lo = 99, hi = -99;
        for (int i = 0; i < dt.length(); i++) {
            lo = Math.min(lo, dmin.optDouble(i));
            hi = Math.max(hi, dmax.optDouble(i));
        }
        for (int i = 0; i < dt.length(); i++) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView day = text(15, Color.WHITE, R.string.font_medium);
            day.setText(i == 0 ? "Today" : LocalDate.parse(dt.optString(i)).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            row.addView(day, new LinearLayout.LayoutParams(px(56), ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView ic = new ImageView(activity);
            ic.setImageResource(icon(dcode.optInt(i), true));
            row.addView(ic, new LinearLayout.LayoutParams(px(22), px(22)));
            TextView min = text(15, 0x99FFFFFF, R.string.font_medium);
            min.setText(Math.round(dmin.optDouble(i)) + "°");
            min.setGravity(Gravity.END);
            row.addView(min, new LinearLayout.LayoutParams(px(44), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(rangeBar(lo, hi, dmin.optDouble(i), dmax.optDouble(i)), barParams());
            TextView max = text(15, Color.WHITE, R.string.font_medium);
            max.setText(Math.round(dmax.optDouble(i)) + "°");
            row.addView(max, new LinearLayout.LayoutParams(px(36), ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(row, top(i == 0 ? 8 : 10));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP);
        lp.setMargins(px(24), px(24), 0, px(24));
        stage.addView(card, lp);
        d.setContentView(stage);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            w.setWindowAnimations(0);
        }
        d.setOnKeyListener((dd, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_UP) return key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN;
            if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
            // Out the way it came in: towards the pill in the top-left corner.
            card.animate().translationX(-px(48)).alpha(0f).setDuration(220).withEndAction(d::dismiss).start();
            return true;
        });
        d.show();
        card.setTranslationX(-px(48));
        card.setAlpha(0f);
        card.animate().translationX(0).alpha(1f).setDuration(300).setInterpolator(new DecelerateInterpolator(2.2f)).start();
    }

    /** One day's span on the week's scale, coloured cool to warm like the Weather app. */
    private View rangeBar(double lo, double hi, double dayMin, double dayMax) {
        FrameLayout bar = new FrameLayout(activity);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x26FFFFFF);
        track.setCornerRadius(px(3));
        bar.setBackground(track);
        View fill = new View(activity);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF64D2FF, 0xFF30D158, 0xFFFFD60A, 0xFFFF9F0A});
        g.setCornerRadius(px(3));
        fill.setBackground(g);
        bar.addView(fill, new FrameLayout.LayoutParams(0, px(5)));
        double span = Math.max(1, hi - lo);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l;
            FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) fill.getLayoutParams();
            int start = (int) (width * (dayMin - lo) / span), end = (int) (width * (dayMax - lo) / span);
            if (fl.leftMargin != start || fl.width != end - start) {
                fl.leftMargin = start;
                fl.width = Math.max(px(5), end - start);
                fill.post(() -> fill.setLayoutParams(fl));
            }
        });
        return bar;
    }

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, px(5), 1f);
        l.setMargins(px(10), 0, px(10), 0);
        return l;
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** WMO weather codes, as Open-Meteo reports them. */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 2) return day ? R.drawable.ic_wx_partly : R.drawable.ic_wx_cloud;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if (code >= 95) return R.drawable.ic_wx_storm;
        return R.drawable.ic_wx_rain;   // drizzle, rain, showers, snow (rare in Nairobi) all read as rain
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly Clear";
        if (code == 2) return "Partly Cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Foggy";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorms";
        return "—";
    }

    private static String hour(String iso) {
        int h = LocalDateTime.parse(iso).getHour();
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? "AM" : "PM");
    }

    private static JSONArray arr(JSONObject o, String key) {
        JSONArray a = o != null ? o.optJSONArray(key) : null;
        return a != null ? a : new JSONArray();
    }

    private TextView section(String s) {
        TextView t = text(11, 0x80FFFFFF, R.string.font_medium);
        t.setLetterSpacing(0.08f);
        t.setText(s);
        return t;
    }

    private TextView text(int sp, int color, int font) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(activity, font));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams top(int dpTop) {
        LinearLayout.LayoutParams l = full();
        l.topMargin = px(dpTop);
        return l;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
