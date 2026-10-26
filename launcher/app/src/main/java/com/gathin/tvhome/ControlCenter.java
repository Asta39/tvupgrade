package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.app.NotificationManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.widget.ScrollView;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * This fork's Control Center, after tvOS: a frosted panel that slides in from the right edge over a dimmed home
 * screen (theme key "control_center"; opened by the Settings button or the remote's Menu key, closed by Back).
 * Top to bottom: the time, what's playing with its transport, a volume slider, and six round controls
 * (Sleep, Sleep timer, Screensaver, Wallpaper, Inputs, Settings). Focus lifts and whitens a control, as tvOS does.
 * It lives in its own dialog window, so focus can never wander back to the home screen behind it.
 */
final class ControlCenter implements MediaWatch.Listener {
    interface Host {
        void nextWallpaper();
        void showInputs();
        void openSettings();
        void openSoundSettings();
        void openNetworkSettings();
        void profileChanged();
    }

    private static final int[] TIMER_STEPS = {0, 15, 30, 60, 90};
    private static final int[] PROFILE_COLORS = {0xFF0A84FF, 0xFFFF375F, 0xFF30D158, 0xFFFF9F0A};
    private static final int CHIP = 0x2EFFFFFF, CHIP_FOCUSED = 0xFFFFFFFF, INK = 0xFF1C1C1E;

    private final Activity activity;
    private final Host host;
    private final MediaWatch media;
    private final float dp;
    private Dialog dialog;
    private FrostedLayout panel;
    private View mediaModule, playButton;
    private ImageView mediaArt, playGlyph;
    private TextView mediaTitle, mediaArtist, timerLabel;
    private View volumeRow, brightnessRow;
    private MediaWatch.Track track;

    ControlCenter(Activity activity, Host host, MediaWatch media) {
        this.activity = activity;
        this.host = host;
        this.media = media;
        dp = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return dialog != null && dialog.isShowing();
    }

    void open() {
        if (isOpen()) return;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout stage = new FrameLayout(activity);
        stage.setClipChildren(false);
        panel = buildPanel();
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(px(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        plp.setMargins(px(24), px(24), px(24), px(24));
        stage.addView(panel, plp);
        dialog.setContentView(stage);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // A modal task: dim the home screen behind, as tvOS does under Control Center.
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            w.setWindowAnimations(0);
        }
        dialog.setOnKeyListener((d, key, e) -> {
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) close();
                return true;
            }
            return false;
        });
        media.addListener(this);
        dialog.setOnDismissListener(d -> media.removeListener(this));
        dialog.show();
        // In from the right edge it is anchored to, and out the same way (spatial consistency).
        panel.setTranslationX(px(56));
        panel.setAlpha(0f);
        panel.animate().translationX(0).alpha(1f).setDuration(300).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        refreshSliders();
        refreshTimer();
        // With music on, Play/Pause is what people reach for first.
        if (track != null && playButton != null) playButton.post(playButton::requestFocus);
    }

    void close() {
        if (!isOpen()) return;
        Dialog d = dialog;
        panel.animate().translationX(px(56)).alpha(0f).setDuration(220).setInterpolator(new DecelerateInterpolator())
                .withEndAction(d::dismiss).start();
    }

    // ---- layout --------------------------------------------------------------------------------------------

    private FrostedLayout buildPanel() {
        FrostedLayout p = new FrostedLayout(activity, 28, 0x80000000);
        // Taller than the screen once music, both sliders and nine controls are in: it scrolls with focus.
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(24));
        scroll.setClipToPadding(false);
        scroll.setPadding(px(20), px(20), px(20), px(36));
        p.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        scroll.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Header: the time, large and light, with the date under it; the profile on the right.
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout when = new LinearLayout(activity);
        when.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(activity);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 28, Color.WHITE, R.string.font_light, -0.02f);
        when.addView(time);
        TextClock date = new TextClock(activity);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        style(date, 13, 0x99FFFFFF, R.string.font_medium, 0f);
        when.addView(date);
        header.addView(when, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View chip = profileChip();
        if (chip != null) header.addView(chip);
        col.addView(header);

        col.addView(mediaModule = buildMedia(), lp(ViewGroup.LayoutParams.MATCH_PARENT, 16));
        col.addView(volumeRow = slider(R.drawable.ic_cc_speaker, this::volumeFraction, this::stepVolume),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        col.addView(brightnessRow = slider(R.drawable.ic_cc_sun, this::brightnessFraction, this::stepBrightness),
                lp(ViewGroup.LayoutParams.MATCH_PARENT, 8));
        col.addView(infoRow(R.drawable.ic_cc_airplay, audioOutput(), "Sound output",
                v -> { close(); host.openSoundSettings(); }), lp(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        col.addView(infoRow(networkIcon(), networkText(), "Network",
                v -> { close(); host.openNetworkSettings(); }), lp(ViewGroup.LayoutParams.MATCH_PARENT, 8));

        LinearLayout row1 = tileRow(), row2 = tileRow(), row3 = tileRow();
        row1.addView(tile(R.drawable.ic_cc_power, "Sleep", v -> {
            close();
            if (!SleepService.sleep()) toast("Turn on Sleep in Accessibility to use this");
        }));
        View timer = tile(R.drawable.ic_cc_timer, "Sleep timer", v -> cycleTimer());
        timerLabel = timer.findViewWithTag("label");
        row1.addView(timer);
        row1.addView(tile(R.drawable.ic_cc_sparkle, "Screensaver", v -> {
            close();
            RemoteKeys.runAction(activity, "screensaver");
        }));
        row2.addView(toggle(R.drawable.ic_cc_nightshift, "Night Shift", 0xFFFF9F0A,
                () -> SleepService.nightShift(activity), on -> {
                    if (!SleepService.setNightShift(activity, on)) toast("Turn on Sleep in Accessibility for Night Shift");
                }));
        row2.addView(tile(R.drawable.ic_cc_screenoff, "Screen off", v -> {
            close();
            if (!SleepService.screenOff(activity)) toast("Turn on Sleep in Accessibility for Screen off");
        }));
        row2.addView(toggle(R.drawable.ic_cc_dnd, "Do Not Disturb", 0xFF5E5CE6, this::dndOn, this::setDnd));
        row3.addView(tile(R.drawable.ic_picture, "Wallpaper", v -> host.nextWallpaper()));
        row3.addView(tile(R.drawable.ic_input, "Inputs", v -> { close(); host.showInputs(); }));
        row3.addView(tile(R.drawable.ic_settings, "Settings", v -> { close(); host.openSettings(); }));
        col.addView(row1, lp(ViewGroup.LayoutParams.MATCH_PARENT, 18));
        col.addView(row2, lp(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        col.addView(row3, lp(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        return p;
    }

    /** The current profile as a small round initial plus name; OK moves to the next person and rebuilds Home. */
    private View profileChip() {
        java.util.List<Profiles.Profile> all = Profiles.all();
        Profiles.Profile me = Profiles.current();
        if (me == null) return null;
        LinearLayout chip = new LinearLayout(activity);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setFocusable(all.size() > 1);
        chip.setPadding(px(6), px(6), px(14), px(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CHIP);
        bg.setCornerRadius(px(22));
        chip.setBackground(bg);
        TextView initial = new TextView(activity);
        initial.setText(me.name.substring(0, 1).toUpperCase());
        style(initial, 14, Color.WHITE, R.string.font_medium, 0f);
        initial.setGravity(Gravity.CENTER);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(PROFILE_COLORS[Profiles.activeIndex() % PROFILE_COLORS.length]);
        initial.setBackground(dot);
        chip.addView(initial, new LinearLayout.LayoutParams(px(30), px(30)));
        TextView name = new TextView(activity);
        name.setText(me.name);
        style(name, 14, Color.WHITE, R.string.font_medium, 0f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.leftMargin = px(8);
        chip.addView(name, nl);
        chip.setOnClickListener(v -> {
            Profiles.select(activity, (Profiles.activeIndex() + 1) % all.size());
            close();
            host.profileChanged();
        });
        chip.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? CHIP_FOCUSED : CHIP);
            name.setTextColor(f ? INK : Color.WHITE);
            lift(v, f, 1.06f);
        });
        return chip;
    }

    /** A status line (audio output, network) that opens the matching settings page. */
    private View infoRow(int icon, String value, String title, View.OnClickListener open) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(px(14), px(10), px(14), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CHIP);
        bg.setCornerRadius(px(18));
        row.setBackground(bg);
        ImageView i = new ImageView(activity);
        i.setImageResource(icon);
        row.addView(i, new LinearLayout.LayoutParams(px(22), px(22)));
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(activity);
        style(t, 11, 0x99FFFFFF, R.string.font_medium, 0.04f);
        t.setText(title);
        TextView v = new TextView(activity);
        style(v, 14, Color.WHITE, R.string.font_medium, 0f);
        v.setText(value);
        text.addView(t);
        text.addView(v);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = px(12);
        row.addView(text, tl);
        row.setOnClickListener(open);
        row.setOnFocusChangeListener((x, f) -> {
            bg.setColor(f ? CHIP_FOCUSED : CHIP);
            i.setColorFilter(f ? INK : Color.WHITE);
            t.setTextColor(f ? 0x991C1C1E : 0x99FFFFFF);
            v.setTextColor(f ? INK : Color.WHITE);
            lift(x, f, 1.03f);
        });
        return row;
    }

    /** A round on/off control that fills with its colour while on (Night Shift orange, Do Not Disturb indigo). */
    private View toggle(int icon, String name, int onColor, java.util.function.BooleanSupplier state,
                        java.util.function.Consumer<Boolean> set) {
        View t = tile(icon, name, null);
        View circle = ((ViewGroup) t).getChildAt(0);
        ImageView glyph = circle.findViewWithTag("icon");
        GradientDrawable bg = (GradientDrawable) circle.getBackground();
        Runnable paint = () -> {
            boolean on = state.getAsBoolean(), f = circle.isFocused();
            bg.setColor(f ? CHIP_FOCUSED : on ? onColor : CHIP);
            glyph.setColorFilter(f ? (on ? onColor : INK) : Color.WHITE);
        };
        circle.setOnClickListener(v -> {
            set.accept(!state.getAsBoolean());
            paint.run();
        });
        View.OnFocusChangeListener own = circle.getOnFocusChangeListener();
        circle.setOnFocusChangeListener((v, f) -> {
            own.onFocusChange(v, f);
            paint.run();
        });
        paint.run();
        return t;
    }

    /** What's playing: artwork, title and artist, and the three transport buttons. Hidden when nothing plays. */
    private View buildMedia() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1FFFFFFF);
        bg.setCornerRadius(px(20));
        box.setBackground(bg);
        box.setPadding(px(12), px(12), px(12), px(10));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        mediaArt = new ImageView(activity);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 10 * dp);
            }
        });
        mediaArt.setClipToOutline(true);
        top.addView(mediaArt, new LinearLayout.LayoutParams(px(48), px(48)));
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        mediaTitle = new TextView(activity);
        style(mediaTitle, 15, Color.WHITE, R.string.font_medium, 0f);
        mediaArtist = new TextView(activity);
        style(mediaArtist, 13, 0x99FFFFFF, R.string.font_body, 0f);
        text.addView(mediaTitle);
        text.addView(mediaArtist);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = px(12);
        top.addView(text, tlp);
        box.addView(top);

        LinearLayout transport = new LinearLayout(activity);
        transport.setClipChildren(false);
        transport.setClipToPadding(false);
        transport.setPadding(0, px(6), 0, px(6));
        transport.setGravity(Gravity.CENTER);
        transport.addView(round(R.drawable.ic_cc_prev, 38, v -> MediaWatch.previous(track)));
        View play = round(R.drawable.ic_cc_pause, 46, v -> MediaWatch.playPause(track));
        playButton = play;
        playGlyph = play.findViewWithTag("icon");
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(px(46), px(46));
        mid.setMargins(px(24), 0, px(24), 0);
        transport.addView(play, mid);
        transport.addView(round(R.drawable.ic_cc_next, 38, v -> MediaWatch.next(track)));
        box.addView(transport, lp(ViewGroup.LayoutParams.MATCH_PARENT, 10));
        box.setVisibility(View.GONE);
        return box;
    }

    /** A focusable slider: Left/Right move it one step, its fill and percentage follow. */
    private View slider(int iconRes, java.util.function.DoubleSupplier value, java.util.function.IntConsumer step) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setPadding(px(14), px(10), px(14), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CHIP);
        bg.setCornerRadius(px(18));
        row.setBackground(bg);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        row.addView(icon, new LinearLayout.LayoutParams(px(22), px(22)));

        FrameLayout bar = new FrameLayout(activity);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x40FFFFFF);
        track.setCornerRadius(px(3));
        bar.setBackground(track);
        View fill = new View(activity);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(Color.WHITE);
        fillBg.setCornerRadius(px(3));
        fill.setBackground(fillBg);
        fill.setPivotX(0);
        bar.addView(fill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, px(6), 1f);
        blp.setMargins(px(12), 0, px(12), 0);
        row.addView(bar, blp);

        TextView pct = new TextView(activity);
        style(pct, 13, Color.WHITE, R.string.font_medium, 0f);
        pct.setGravity(Gravity.END);
        row.addView(pct, new LinearLayout.LayoutParams(px(32), ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable refresh = () -> {
            double f = Math.max(0, Math.min(1, value.getAsDouble()));
            fill.setScaleX((float) f);
            pct.setText(String.valueOf(Math.round(f * 100)));
        };
        row.setTag(refresh);
        refresh.run();
        row.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            step.accept(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            refresh.run();
            return true;
        });
        row.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0x52FFFFFF : CHIP);
            lift(v, f, 1.03f);
        });
        return row;
    }

    private double volumeFraction() {
        AudioManager am = activity.getSystemService(AudioManager.class);
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) / (double) Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void stepVolume(int dir) {
        activity.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
    }

    /** The panel backlight, through Android's screen brightness (0-255), which this TCL follows. */
    private double brightnessFraction() {
        try {
            return Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255.0;
        } catch (Settings.SettingNotFoundException e) {
            return 0.5;
        }
    }

    private void stepBrightness(int dir) {
        if (!Settings.System.canWrite(activity)) {
            toast("Brightness needs: appops set com.gathin.tvhome WRITE_SETTINGS allow");
            return;
        }
        int now = (int) Math.round(brightnessFraction() * 255);
        int next = Math.max(5, Math.min(255, now + dir * 13));   // about 5% a press
        Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, next);
    }

    private boolean dndOn() {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        return nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    private void setDnd(boolean on) {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            toast("Do Not Disturb needs: cmd notification allow_dnd com.gathin.tvhome");
            return;
        }
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
    }

    /** Where media sound goes now. Android routes media to a connected Bluetooth device first, then to ARC/eARC. */
    private String audioOutput() {
        AudioManager am = activity.getSystemService(AudioManager.class);
        String bt = null, arc = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) bt = String.valueOf(d.getProductName());
            else if (t == AudioDeviceInfo.TYPE_HDMI_ARC || t == 29 /* TYPE_HDMI_EARC */) arc = "Soundbar (HDMI ARC)";
        }
        if (bt != null) return bt.isEmpty() ? "Bluetooth" : bt;
        return arc != null ? arc : "TV speakers";
    }

    private String networkText() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc == null) return "Not connected";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet · Connected";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            android.net.wifi.WifiManager wm = activity.getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            String ssid = wm != null && wm.getConnectionInfo() != null ? wm.getConnectionInfo().getSSID() : null;
            if (ssid != null && !ssid.contains("unknown")) return ssid.replace("\"", "") + " · Connected";
            return "Wi-Fi · Connected";
        }
        return "Connected";
    }

    private int networkIcon() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? R.drawable.ic_ethernet : R.drawable.ic_wifi;
    }

    private LinearLayout tileRow() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        return r;
    }

    /** A round control with its name under it, as in tvOS Control Center. */
    private View tile(int icon, String name, View.OnClickListener action) {
        LinearLayout t = new LinearLayout(activity);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setClipChildren(false);
        View circle = round(icon, 54, action);
        t.addView(circle, new LinearLayout.LayoutParams(px(54), px(54)));
        TextView label = new TextView(activity);
        label.setTag("label");
        label.setText(name);
        style(label, 12, 0xCCFFFFFF, R.string.font_medium, 0f);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(100), ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = px(6);
        t.addView(label, l);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View.OnFocusChangeListener own = circle.getOnFocusChangeListener();
        circle.setOnFocusChangeListener((v, f) -> {
            own.onFocusChange(v, f);
            if (!"Sleep timer".equals(name) || SleepService.minutesLeft(activity) == 0) {
                label.setTextColor(f ? Color.WHITE : 0xCCFFFFFF);
            }
        });
        return t;
    }

    private View round(int icon, int sizeDp, View.OnClickListener action) {
        FrameLayout b = new FrameLayout(activity);
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(CHIP);
        b.setBackground(bg);
        ImageView glyph = new ImageView(activity);
        glyph.setTag("icon");
        glyph.setImageResource(icon);
        glyph.setColorFilter(Color.WHITE);
        int g = Math.round(sizeDp * 0.42f);
        b.addView(glyph, new FrameLayout.LayoutParams(px(g), px(g), Gravity.CENTER));
        b.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
        b.setOnClickListener(action);
        b.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? CHIP_FOCUSED : CHIP);
            glyph.setColorFilter(f ? INK : Color.WHITE);
            lift(v, f, 1.12f);
        });
        return b;
    }

    /** tvOS focus: the control grows and lifts, critically damped (no overshoot). */
    private void lift(View v, boolean focused, float scale) {
        v.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f)
                .translationZ(focused ? px(10) : 0).setDuration(180)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    // ---- state ---------------------------------------------------------------------------------------------

    @Override
    public void onMedia(MediaWatch.Track t) {
        track = t;
        if (mediaModule == null) return;
        if (t == null) {
            mediaModule.setVisibility(View.GONE);
            return;
        }
        mediaModule.setVisibility(View.VISIBLE);
        mediaTitle.setText(t.title);
        mediaArtist.setText(t.artist);
        if (t.art != null) mediaArt.setImageBitmap(t.art);
        else mediaArt.setImageResource(R.drawable.ic_cc_music);
        playGlyph.setImageResource(t.playing ? R.drawable.ic_cc_pause : R.drawable.ic_cc_play);
    }

    private void refreshSliders() {
        for (View row : new View[]{volumeRow, brightnessRow}) if (row != null && row.getTag() instanceof Runnable) ((Runnable) row.getTag()).run();
    }

    private void cycleTimer() {
        int left = SleepService.minutesLeft(activity);
        int next = 0;
        for (int step : TIMER_STEPS) if (step > left) { next = step; break; }
        if (left >= TIMER_STEPS[TIMER_STEPS.length - 1]) next = 0;
        SleepService.setTimer(activity, next);
        if (next > 0 && !SleepService.available()) toast("Turn on Sleep in Accessibility for the timer to work");
        refreshTimer();
    }

    private void refreshTimer() {
        if (timerLabel == null) return;
        int left = SleepService.minutesLeft(activity);
        timerLabel.setText(left > 0 ? left + " min" : "Sleep timer");
        timerLabel.setTextColor(left > 0 ? Color.WHITE : 0xCCFFFFFF);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void style(TextView t, int sp, int color, int font, float tracking) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(activity, font));
        t.setLetterSpacing(tracking);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
    }

    private LinearLayout.LayoutParams lp(int w, int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = px(topDp);
        return l;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
