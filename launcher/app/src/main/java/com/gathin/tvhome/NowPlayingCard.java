package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * This fork's Apple Music–style mini player (theme key "now_playing"): a small frosted card centred between the clock
 * and the dock while something plays. Up from the dock focuses it; OK plays or pauses, Left/Right skip. It is gone
 * the moment nothing is playing, so the home screen stays bare.
 */
final class NowPlayingCard implements MediaWatch.Listener {
    private static final int TINT = 0x66000000, TINT_FOCUSED = 0xEBFFFFFF;

    private final Activity activity;
    private final FrameLayout root;
    private final View dockAnchor;
    private final HomeExtras extras;
    private final float dp;
    private final FrostedLayout card;
    private final ImageView art, glyph;
    private final TextView title, artist;
    private final View progressFill;
    private final GradientDrawable trackBg, fillBg;
    private final Handler tick = new Handler(Looper.getMainLooper());
    private MediaWatch.Track track;
    private boolean shown, scrolledAway;

    NowPlayingCard(Activity activity, FrameLayout root, View dockAnchor, HomeExtras extras) {
        this.activity = activity;
        this.root = root;
        this.dockAnchor = dockAnchor;
        this.extras = extras;
        dp = activity.getResources().getDisplayMetrics().density;

        card = new FrostedLayout(activity, 18, TINT);
        card.setFocusable(true);
        card.setClickable(true);
        card.setAlpha(0f);
        card.setVisibility(View.GONE);
        int pad = px(9);
        card.setPadding(pad, pad, px(16), pad);

        LinearLayout rowView = new LinearLayout(activity);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        rowView.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(rowView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(0x33FFFFFF);
        art.setBackground(artBg);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 8 * dp);
            }
        });
        art.setClipToOutline(true);
        rowView.addView(art, new LinearLayout.LayoutParams(px(42), px(42)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = px(12);
        tlp.rightMargin = px(14);
        title = line(14, Color.WHITE, R.string.font_medium);
        artist = line(12, 0x99FFFFFF, R.string.font_body);
        text.addView(title);
        text.addView(artist);

        // Hairline progress: a 20% track with an 85% fill, as in the Music app's mini player.
        FrameLayout bar = new FrameLayout(activity);
        trackBg = new GradientDrawable();
        trackBg.setColor(0x33FFFFFF);
        trackBg.setCornerRadius(px(2));
        bar.setBackground(trackBg);
        progressFill = new View(activity);
        fillBg = new GradientDrawable();
        fillBg.setColor(0xD9FFFFFF);
        fillBg.setCornerRadius(px(2));
        progressFill.setBackground(fillBg);
        progressFill.setPivotX(0);
        bar.addView(progressFill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3));
        blp.topMargin = px(6);
        text.addView(bar, blp);
        rowView.addView(text, tlp);

        glyph = new ImageView(activity);
        glyph.setAlpha(0.9f);
        rowView.addView(glyph, new LinearLayout.LayoutParams(px(22), px(22)));

        card.setOnClickListener(v -> MediaWatch.playPause(track));
        card.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { MediaWatch.previous(track); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { MediaWatch.next(track); return true; }
            return false;
        });
        card.setOnFocusChangeListener((v, focused) -> {
            card.setTint(focused ? TINT_FOCUSED : TINT);
            trackBg.setColor(focused ? 0x331C1C1E : 0x33FFFFFF);
            fillBg.setColor(focused ? 0xD91C1C1E : 0xD9FFFFFF);
            title.setTextColor(focused ? 0xFF1C1C1E : Color.WHITE);
            artist.setTextColor(focused ? 0xB31C1C1E : 0x99FFFFFF);
            glyph.setColorFilter(focused ? 0xFF1C1C1E : Color.WHITE);
            // tvOS focus: the item lifts and grows a little; critically damped, no overshoot.
            card.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                    .translationZ(focused ? px(12) : 0).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(320), px(64), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(card, lp);
        dockAnchor.getViewTreeObserver().addOnGlobalLayoutListener(this::place);
        // Browsing the rows below the dock: the card steps aside with the clock, and comes back with the dock.
        if (dockAnchor instanceof androidx.recyclerview.widget.RecyclerView) {
            ((androidx.recyclerview.widget.RecyclerView) dockAnchor).addOnScrollListener(
                    new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                    boolean away = rv.computeVerticalScrollOffset() >= 4 * dp;
                    if (away != scrolledAway) {
                        scrolledAway = away;
                        if (shown) card.animate().alpha(away ? 0f : 1f).setDuration(220).start();
                        card.setFocusable(!away);
                    }
                }
            });
        }
    }

    /** Centred between the clock's baseline and the dock's top. */
    private void place() {
        int clockBottom = extras != null ? extras.clockBottom() : px(220);
        View first = dockAnchor;
        if (dockAnchor instanceof ViewGroup && ((ViewGroup) dockAnchor).getChildCount() > 0) {
            first = ((ViewGroup) dockAnchor).getChildAt(0);
        }
        int[] d = new int[2], r = new int[2];
        first.getLocationOnScreen(d);
        root.getLocationOnScreen(r);
        int dockTop = d[1] - r[1];
        if (dockTop <= clockBottom) return;
        int top = (clockBottom + dockTop) / 2 - card.getLayoutParams().height / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (Math.abs(lp.topMargin - top) > 1) {
            lp.topMargin = top;
            card.setLayoutParams(lp);
        }
    }

    @Override
    public void onMedia(MediaWatch.Track t) {
        track = t;
        if (t == null) {
            hide();
            return;
        }
        title.setText(t.title);
        artist.setText(t.artist.isEmpty() ? appName(t.pkg) : t.artist);
        if (t.art != null) {
            art.setImageBitmap(t.art);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setPadding(0, 0, 0, 0);
        } else {
            art.setImageResource(R.drawable.ic_cc_music);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setPadding(px(13), px(13), px(13), px(13));
        }
        glyph.setImageResource(t.playing ? R.drawable.ic_cc_pause : R.drawable.ic_cc_play);
        updateProgress();
        show();
    }

    private void show() {
        tick.removeCallbacksAndMessages(null);
        tick.post(this::loop);
        if (shown) return;
        shown = true;
        card.setVisibility(View.VISIBLE);
        card.setTranslationY(px(8));
        card.animate().alpha(scrolledAway ? 0f : 1f).translationY(0).setDuration(280).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void hide() {
        tick.removeCallbacksAndMessages(null);
        if (!shown) return;
        shown = false;
        if (card.hasFocus()) dockAnchor.requestFocus();
        card.animate().alpha(0f).translationY(px(8)).setDuration(220)
                .withEndAction(() -> { if (!shown) card.setVisibility(View.GONE); }).start();
    }

    /** Twice a second while the card is up and playing; nothing runs while it is hidden. */
    private void loop() {
        updateProgress();
        if (shown && track != null && track.playing) tick.postDelayed(this::loop, 500);
    }

    private void updateProgress() {
        MediaWatch.Track t = track;
        float f = t == null || t.duration <= 0 ? 0f : Math.min(1f, t.positionNow() / (float) t.duration);
        progressFill.setScaleX(f);
    }

    void stop() {
        tick.removeCallbacksAndMessages(null);
    }

    boolean isShown() {
        return shown;
    }

    private String appName(String pkg) {
        try {
            return String.valueOf(activity.getPackageManager().getApplicationLabel(
                    activity.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    private TextView line(int sp, int color, int font) {
        TextView t = new TextView(activity);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(activity, font));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(0, px(1), 0, px(1));
        return t;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
