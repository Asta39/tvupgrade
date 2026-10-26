package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
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
    }

    private static final int[] TIMER_STEPS = {0, 15, 30, 60, 90};
    private static final int CHIP = 0x2EFFFFFF, CHIP_FOCUSED = 0xFFFFFFFF, INK = 0xFF1C1C1E;

    private final Activity activity;
    private final Host host;
    private final MediaWatch media;
    private final float dp;
    private Dialog dialog;
    private FrostedLayout panel;
    private View mediaModule, playButton;
    private ImageView mediaArt, playGlyph;
    private TextView mediaTitle, mediaArtist, volumeValue, timerLabel;
    private View volumeFill;
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
        refreshVolume();
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
        p.setPadding(px(20), px(20), px(20), px(22));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        p.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Header: the time, large and light, with the date under it.
        TextClock time = new TextClock(activity);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 28, Color.WHITE, R.string.font_light, -0.02f);
        col.addView(time);
        TextClock date = new TextClock(activity);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        style(date, 13, 0x99FFFFFF, R.string.font_medium, 0f);
        col.addView(date);

        col.addView(mediaModule = buildMedia(), lp(ViewGroup.LayoutParams.MATCH_PARENT, 16));
        col.addView(buildVolume(), lp(ViewGroup.LayoutParams.MATCH_PARENT, 12));


        LinearLayout row1 = tileRow(), row2 = tileRow();
        row1.addView(tile(R.drawable.ic_cc_moon, "Sleep", v -> {
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
        row2.addView(tile(R.drawable.ic_picture, "Wallpaper", v -> host.nextWallpaper()));
        row2.addView(tile(R.drawable.ic_input, "Inputs", v -> { close(); host.showInputs(); }));
        row2.addView(tile(R.drawable.ic_settings, "Settings", v -> { close(); host.openSettings(); }));
        col.addView(row1, lp(ViewGroup.LayoutParams.MATCH_PARENT, 18));
        col.addView(row2, lp(ViewGroup.LayoutParams.MATCH_PARENT, 12));
        return p;
    }

    /** What's playing: artwork, title and artist, and the three transport buttons. Hidden when nothing plays. */
    private View buildMedia() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
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

    /** A focusable slider: Left/Right change the TV's media volume by one step, as the remote's keys would. */
    private View buildVolume() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setPadding(px(14), px(10), px(14), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CHIP);
        bg.setCornerRadius(px(18));
        row.setBackground(bg);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_cc_speaker);
        row.addView(icon, new LinearLayout.LayoutParams(px(22), px(22)));

        FrameLayout bar = new FrameLayout(activity);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x40FFFFFF);
        track.setCornerRadius(px(3));
        bar.setBackground(track);
        volumeFill = new View(activity);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.WHITE);
        fill.setCornerRadius(px(3));
        volumeFill.setBackground(fill);
        volumeFill.setPivotX(0);
        bar.addView(volumeFill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, px(6), 1f);
        blp.setMargins(px(12), 0, px(12), 0);
        row.addView(bar, blp);

        volumeValue = new TextView(activity);
        style(volumeValue, 13, Color.WHITE, R.string.font_medium, 0f);
        volumeValue.setGravity(Gravity.END);
        row.addView(volumeValue, new LinearLayout.LayoutParams(px(28), ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            AudioManager am = activity.getSystemService(AudioManager.class);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, key == KeyEvent.KEYCODE_DPAD_RIGHT
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            refreshVolume();
            return true;
        });
        row.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0x52FFFFFF : CHIP);
            lift(v, f, 1.03f);
        });
        return row;
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

    private void refreshVolume() {
        AudioManager am = activity.getSystemService(AudioManager.class);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeFill.setScaleX(now / (float) max);
        volumeValue.setText(String.valueOf(Math.round(100f * now / max)));
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
