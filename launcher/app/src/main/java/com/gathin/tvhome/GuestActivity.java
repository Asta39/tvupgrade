package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Guest mode's home screen (this fork), for a TV in an Airbnb or rental: the glass clock over the place's picture,
 * a welcome with the guests' names while they stay, their stay, the Wi-Fi, the weather, the door code (only
 * during the stay), the house rules, and only the apps the host allowed. MainActivity hands Home over to this
 * screen while stay.json says "mode": "guest" (Stay). Menu opens the switch back to Home mode.
 */
public class GuestActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private float dp;
    private Stay stay;
    private TextView label, title, subtitle, weatherTemp, weatherText;
    private ImageView weatherIcon;
    private LinearLayout cards;
    private View rulesSheet;

    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            stay = Stay.load(GuestActivity.this);
            fill();
            main.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        Config cfg = Config.load(this);
        stay = Stay.load(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0F);
        File picture = cfg.wallpapers.isEmpty() ? null : cfg.wallpapers.get(0);
        ImageView wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap wallBitmap = picture == null ? null : decode(picture, 2);
        wall.setImageBitmap(wallBitmap);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        // The glass digits refract whatever WallpaperPlayer says is on screen: here, this picture.
        WallpaperPlayer.shownView = wall;
        WallpaperPlayer.shownBitmap = wallBitmap;

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER_HORIZONTAL);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        style(date, 18, 0xE6FFFFFF, R.string.font_medium);
        date.setShadowLayer(12 * dp, 0, 0, 0x40000000);
        clockBox.addView(date);
        GlassClock clock = new GlassClock(this, getResources().getFont(R.font.clock_barlow),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 190, getResources().getDisplayMetrics()), 0.74f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(-12);
        clockBox.addView(clock, clp);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = px(28);
        root.addView(clockBox, blp);

        // The picture's foreground in front of the clock, as on Home (depth/<picture name>.png).
        File cut = picture == null ? null : new File(new File(picture.getParentFile().getParentFile(), "depth"),
                picture.getName().replaceAll("\\.[^.]+$", "") + ".png");
        if (cut != null && cut.exists()) {
            ImageView depth = new ImageView(this);
            depth.setScaleType(ImageView.ScaleType.CENTER_CROP);
            depth.setImageBitmap(decode(cut, 2));
            root.addView(depth, new FrameLayout.LayoutParams(-1, -1));
        }

        // A soft dark floor so the cards read on any picture.
        View floor = new View(this);
        floor.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x8C000000, 0xD9000000}));
        root.addView(floor, new FrameLayout.LayoutParams(-1, px(330), Gravity.BOTTOM));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(px(56), 0, px(56), px(30));
        label = text("", 12, 0xB3FFFFFF, R.string.font_medium);
        label.setLetterSpacing(0.12f);
        content.addView(label);
        title = text("", 34, Color.WHITE, R.string.font_medium);
        title.setLetterSpacing(-0.01f);
        title.setShadowLayer(14 * dp, 0, 0, 0x59000000);
        content.addView(title);
        subtitle = text("", 15, 0xD9FFFFFF, R.string.font_regular);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(subtitle, new LinearLayout.LayoutParams(px(760), -2));

        cards = new LinearLayout(this);
        cards.setClipChildren(false);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, px(112));
        cl.topMargin = px(16);
        content.addView(cards, cl);

        android.widget.HorizontalScrollView barScroll = new android.widget.HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.setClipChildren(false);
        barScroll.setClipToPadding(false);
        barScroll.addView(appBar());
        content.addView(barScroll, lp(-2, -2, 14));
        root.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        fill();
        fetchWeather();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Home mode again (Control Center, or the phone page later): hand Home back.
        if (!Stay.guestMode(this)) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        main.removeCallbacks(minute);
        main.post(minute);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(minute);
    }

    @Override
    public void onBackPressed() {
        if (rulesSheet != null) {
            ((ViewGroup) rulesSheet.getParent()).removeView(rulesSheet);
            rulesSheet = null;
        }
        // Otherwise nothing: this is the Home screen.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            hostMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- content ---------------------------------------------------------------------------------------

    private void fill() {
        label.setText(stay.place.toUpperCase(Locale.getDefault()));
        label.setVisibility(stay.place.isEmpty() ? View.GONE : View.VISIBLE);
        if (stay.staying() && !stay.names.isEmpty()) {
            title.setText((stay.arrivingToday() ? "Welcome, " : greeting() + ", ") + stay.names);
        } else {
            title.setText(stay.place.isEmpty() ? "Welcome" : "Welcome to " + stay.place);
        }
        if (stay.staying()) {
            DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE", Locale.getDefault());
            subtitle.setText(stay.arrivingToday() ? "Check-in from " + Stay.friendly(stay.checkin) + ". Make yourselves at home."
                    : stay.leavingToday() ? "Check-out today by " + Stay.friendly(stay.checkout) + ". Safe travels."
                    : "Night " + stay.night + " of " + stay.nights + " · check-out " + stay.depart.format(day) + " " + Stay.friendly(stay.checkout));
        } else if (stay.place.isEmpty() && stay.wifiName.isEmpty()) {
            // Nothing set up yet: say so, instead of an empty welcome.
            subtitle.setText("Host: choose Host at the end of the row below to finish setting up, or to go back to Home mode.");
        } else {
            subtitle.setText(stay.about);
        }

        cards.removeAllViews();
        if (stay.staying()) {
            cards.addView(card("Your stay", stay.arrivingToday() ? "Arriving today"
                    : stay.leavingToday() ? "Last day" : "Night " + stay.night + " of " + stay.nights,
                    stay.leavingToday() ? "Check-out " + Stay.friendly(stay.checkout) : "Check-out " + Stay.friendly(stay.checkout), null));
        }
        if (!stay.wifiName.isEmpty()) {
            cards.addView(card("Wi-Fi", stay.wifiName, stay.wifiPassword.isEmpty() ? "" : stay.wifiPassword, null));
        }
        View weather = card("Weather", "", "", null);
        weatherTemp = weather.findViewWithTag("big");
        weatherText = weather.findViewWithTag("small");
        weatherIcon = new ImageView(this);
        ((ViewGroup) weather).addView(weatherIcon, new FrameLayout.LayoutParams(px(26), px(26), Gravity.TOP | Gravity.END));
        cards.addView(weather);
        showWeather();
        if (!stay.doorCode.isEmpty()) cards.addView(card("Door code", stay.doorCode, "During your stay", null));
        if (!stay.rules.isEmpty()) {
            cards.addView(card("House rules", stay.rules.size() == 1 ? "1 rule" : stay.rules.size() + " rules", "Press OK to read", v -> showRules()));
        }
    }

    private String greeting() {
        int h = LocalTime.now().getHour();
        return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    /** A glass card: small heading, one big line, one small line. Focusable only when it opens something. */
    private View card(String heading, String big, String small, View.OnClickListener open) {
        FrameLayout box = new FrameLayout(this);
        box.setPadding(px(16), px(12), px(16), px(12));
        GradientDrawable bg = glass(0x38FFFFFF, 18);
        box.setBackground(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(heading, 11, 0xB3FFFFFF, R.string.font_medium);
        h.setLetterSpacing(0.08f);
        col.addView(h);
        TextView b = text(big, 19, Color.WHITE, R.string.font_medium);
        b.setTag("big");
        b.setMaxLines(1);
        b.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(b, lp(-1, -2, 8));
        TextView s = text(small, 13, 0xCCFFFFFF, R.string.font_regular);
        s.setTag("small");
        s.setMaxLines(1);
        s.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(s, lp(-1, -2, 2));
        box.addView(col);
        if (open != null) {
            box.setFocusable(true);
            box.setClickable(true);
            box.setOnClickListener(open);
            box.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? glass(0x66FFFFFF, 18) : glass(0x38FFFFFF, 18));
                v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(150).start();
            });
        }
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(214), -1);
        l.rightMargin = px(12);
        box.setLayoutParams(l);
        return box;
    }

    /** House guide first, then the apps the host allowed (all launchable apps when none are listed). */
    private View appBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(px(6), px(6), px(6), px(6));
        bar.setBackground(glass(0x2EFFFFFF, 24));
        bar.addView(chip(null, "House guide", v -> showRules()));
        // Last on the row: the host's way out (Menu does the same), behind the PIN once the TV is locked.
        View hostChip = chip(null, "Host", v -> hostMenu());
        PackageManager pm = getPackageManager();
        List<String> allowed = stay.apps;
        if (allowed.isEmpty()) for (Setup.App a : Setup.apps(this)) if (allowed.size() < 8) allowed.add(a.pkg);
        int shown = 0;
        for (String pkg : allowed) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            Intent go = launch;
            Drawable icon;
            String name;
            try {
                icon = pm.getApplicationIcon(pkg);
                name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            View c = chip(icon, name, v -> startActivity(go));
            bar.addView(c);
            if (shown++ == 0) c.post(c::requestFocus);
        }
        bar.addView(hostChip);
        return bar;
    }

    private View chip(Drawable icon, String name, View.OnClickListener open) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(14), px(8), px(16), px(8));
        c.setFocusable(true);
        c.setClickable(true);
        if (icon != null) {
            ImageView i = new ImageView(this);
            i.setImageDrawable(icon);
            c.addView(i, new LinearLayout.LayoutParams(px(22), px(22)));
        }
        TextView t = text(name, 14, Color.WHITE, R.string.font_medium);
        t.setSingleLine(true);
        t.setPadding(icon != null ? px(8) : 0, 0, 0, 0);
        c.addView(t);
        c.setOnClickListener(open);
        c.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? glass(Color.WHITE, 18) : null);
            t.setTextColor(f ? 0xFF1C1C1E : Color.WHITE);
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.rightMargin = px(4);
        c.setLayoutParams(l);
        return c;
    }

    private void showRules() {
        if (rulesSheet != null) return;
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(36), px(30), px(36), px(30));
        panel.setBackground(glass(0xE61C1C1E, 24));
        panel.addView(text("House guide", 28, Color.WHITE, R.string.font_medium));
        StringBuilder b = new StringBuilder();
        if (!stay.rules.isEmpty()) for (String r : stay.rules) b.append("•  ").append(r).append("\n");
        b.append("\nCheck-in from ").append(Stay.friendly(stay.checkin)).append(" · check-out by ").append(Stay.friendly(stay.checkout));
        if (!stay.wifiName.isEmpty()) b.append("\nWi-Fi: ").append(stay.wifiName).append(stay.wifiPassword.isEmpty() ? "" : "  ·  " + stay.wifiPassword);
        TextView body = text(b.toString().trim(), 17, 0xE6FFFFFF, R.string.font_regular);
        body.setLineSpacing(px(6), 1f);
        panel.addView(body, lp(-1, -2, 16));
        TextView hint = text("Press Back to close", 13, 0x99FFFFFF, R.string.font_regular);
        panel.addView(hint, lp(-2, -2, 18));
        scrim.addView(panel, new FrameLayout.LayoutParams(px(640), -2, Gravity.CENTER));
        scrim.setFocusable(true);
        ((FrameLayout) findViewById(android.R.id.content)).addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.requestFocus();
        rulesSheet = scrim;
    }

    /**
     * Menu or the Host chip: the host's way back to Home mode. While the host has locked the TV to Guest mode
     * (stay.json "locked", set from the phone page), it asks for the host's PIN first ("pin_sha256").
     */
    private void hostMenu() {
        JSONObject j = Stay.json(this);
        String pinHash = j.optString("pin_sha256", "");
        if (j.optBoolean("locked") && !pinHash.isEmpty()) {
            android.widget.EditText pin = new android.widget.EditText(this);
            pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pin.setHint("Host PIN");
            new android.app.AlertDialog.Builder(this)
                    .setTitle("This TV is locked to Guest mode")
                    .setMessage("Enter the host PIN to change it.")
                    .setView(pin)
                    .setPositiveButton("Unlock", (d, w) -> {
                        if (Stay.sha256(pin.getText().toString().trim()).equals(pinHash)) switchHome();
                        else android.widget.Toast.makeText(this, "Wrong PIN", android.widget.Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Switch to Home mode?")
                .setMessage("The TV goes back to the personal home screen. You can return to Guest mode from Control Center.")
                .setPositiveButton("Switch", (d, w) -> switchHome())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void switchHome() {
        Stay.setMode(this, false);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    // ---- weather ---------------------------------------------------------------------------------------

    private JSONObject weather;

    private void fetchWeather() {
        if (Config.weatherCity.isEmpty() && Config.weatherLat == 0 && Config.weatherLon == 0) return;
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + Config.weatherLat + "&longitude=" + Config.weatherLon
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto";
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    JSONObject j = new JSONObject(o.toString("UTF-8"));
                    main.post(() -> {
                        weather = j;
                        showWeather();
                    });
                }
            } catch (Exception ignored) {
            }
        }, "guest-weather").start();
    }

    private void showWeather() {
        if (weatherTemp == null) return;
        if (weather == null) {
            weatherTemp.setText(Config.weatherCity.isEmpty() ? "—" : Config.weatherCity);
            weatherText.setText("");
            return;
        }
        JSONObject now = weather.optJSONObject("current");
        JSONObject daily = weather.optJSONObject("daily");
        if (now == null) return;
        int code = now.optInt("weather_code");
        weatherTemp.setText(Math.round(now.optDouble("temperature_2m")) + "°");
        String range = daily != null && daily.optJSONArray("temperature_2m_max") != null
                ? "  ·  H " + Math.round(daily.optJSONArray("temperature_2m_max").optDouble(0)) + "° L "
                + Math.round(daily.optJSONArray("temperature_2m_min").optDouble(0)) + "°" : "";
        weatherText.setText(Weather.describe(code) + range);
        weatherIcon.setImageResource(Weather.icon(code, now.optInt("is_day", 1) == 1));
    }

    // ---- pieces ----------------------------------------------------------------------------------------

    private Bitmap decode(File f, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private GradientDrawable glass(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radiusDp));
        d.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
        return d;
    }

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        style(t, sp, color, font);
        return t;
    }

    private void style(TextView t, int sp, int color, int font) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        Typeface face = UiFont.get(this, font);
        if (face != null) t.setTypeface(face);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h);
        l.topMargin = px(topDp);
        return l;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
