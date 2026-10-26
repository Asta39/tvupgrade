package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * This fork's tvOS / iOS lock-screen layer (theme keys "hero_clock" and "keep_watching"): a large centred clock
 * with the date above it, and a Continue watching row of glass cards just above the dock. Both sit on the root
 * FrameLayout over the wallpaper; the stock top-left clock is hidden while hero_clock is on.
 */
final class HomeExtras {
    private final Activity activity;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final float dp;
    private LinearLayout row;
    private TextView rowTitle;
    private List<KeepWatching.Item> shown;
    private View clockBox;

    /** Bottom edge of the hero clock in root coordinates; a sensible default without it. */
    int clockBottom() {
        return clockBox != null && clockBox.getHeight() > 0 ? clockBox.getBottom() : Math.round(220 * dp);
    }

    private HomeExtras(Activity activity, FrameLayout root, RecyclerView grid) {
        this.activity = activity;
        this.root = root;
        this.grid = grid;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    /** Adds what the theme asks for; null when neither key is on. */
    static HomeExtras attach(Activity activity, FrameLayout root, View clockBlock, RecyclerView grid) {
        if (!Config.heroClock && !Config.keepWatching) return null;
        HomeExtras x = new HomeExtras(activity, root, grid);
        if (Config.heroClock) {
            // INVISIBLE, not GONE: the top bar's pills are laid out against this block.
            clockBlock.setVisibility(View.INVISIBLE);
            x.addClock();
        }
        if (Config.keepWatching) x.addRow();
        return x;
    }

    /** Home came back: read the list again (an episode watched since then moves to the front). */
    void refresh() {
        if (row != null) KeepWatching.load(activity, this::show);
    }

    // ---- clock -------------------------------------------------------------------------------------

    private void addClock() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(false);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        TextClock date = new TextClock(activity);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTextColor(0xE6FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        date.setTypeface(UiFont.get(activity, R.string.font_medium));
        date.setLetterSpacing(0.01f);
        date.setShadowLayer(12 * dp, 0, 0, 0x40000000);
        date.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock clock = new TextClock(activity);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 150);
        clock.setTypeface(UiFont.get(activity, R.string.font_light));
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.03f);   // display size wants negative tracking
        // Lock-screen glass: bright at the top, fading into the picture towards the baseline.
        clock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t == ob - ot) return;
            clock.getPaint().setShader(new LinearGradient(0, 0, 0, b - t,
                    0xF2FFFFFF, 0x80FFFFFF, Shader.TileMode.CLAMP));
            clock.invalidate();
        });
        clock.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Math.round(-6 * dp);
        box.addView(clock, clp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.round(44 * dp);
        // Behind the grid and top bar (index 0 would sit under the wallpaper): just above the ambient glow.
        clockBox = box;
        int at = Math.min(3, root.getChildCount());
        root.addView(box, at, lp);
        // The picture's foreground, in front of the clock and behind everything else (WallpaperPlayer.onDepth).
        ImageView depth = new ImageView(activity);
        depth.setScaleType(ImageView.ScaleType.CENTER_CROP);
        depth.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(depth, at + 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Browsing the rows below the dock: the clock and its scenery step back, and return with the dock.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                float a = rv.computeVerticalScrollOffset() < 4 * dp ? 1f : 0f;
                if (box.getTag() == null || (float) box.getTag() != a) {
                    box.setTag(a);
                    box.animate().alpha(a).setDuration(220).start();
                }
            }
        });
        WallpaperPlayer.onDepth = next -> {
            android.graphics.drawable.Drawable was = depth.getDrawable();
            android.graphics.drawable.Drawable now = next != null
                    ? new android.graphics.drawable.BitmapDrawable(activity.getResources(), next)
                    : new android.graphics.drawable.ColorDrawable(0);
            if (was instanceof android.graphics.drawable.TransitionDrawable) {
                was = ((android.graphics.drawable.TransitionDrawable) was).getDrawable(1);
            }
            if (was == null) {
                depth.setImageDrawable(next != null ? now : null);
                return;
            }
            android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                    new android.graphics.drawable.Drawable[]{was, now});
            fade.setCrossFadeEnabled(true);
            depth.setImageDrawable(fade);
            fade.startTransition(450);
            depth.postDelayed(() -> {
                if (depth.getDrawable() == fade) depth.setImageDrawable(next != null ? now : null);
            }, 600);
        };
        depth.setImageBitmap(WallpaperPlayer.depthShown);
    }

    // ---- keep watching -----------------------------------------------------------------------------

    private void addRow() {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);
        wrap.setVisibility(View.GONE);

        rowTitle = new TextView(activity);
        rowTitle.setText("Keep watching");
        rowTitle.setTextColor(0xCCFFFFFF);
        rowTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        rowTitle.setTypeface(UiFont.get(activity, R.string.font_medium));
        rowTitle.setLetterSpacing(0.04f);
        rowTitle.setShadowLayer(10 * dp, 0, 0, 0x59000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = Math.round(8 * dp);
        tlp.leftMargin = Math.round(4 * dp);
        wrap.addView(rowTitle, tlp);

        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        wrap.addView(row);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round(64 * dp);
        root.addView(wrap, lp);
        // Sit just above the dock band once the grid has laid out its first tile.
        grid.getViewTreeObserver().addOnGlobalLayoutListener(() -> place(wrap));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                place(wrap);
            }

            @Override
            public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) place(wrap);
            }
        });
        KeepWatching.load(activity, this::show);
    }

    private void place(View wrap) {
        View first = grid.getChildCount() > 0 ? grid.getChildAt(0) : null;
        if (first == null || wrap.getHeight() == 0) return;
        // Scrolled down into the other apps: the row would ride up over the clock, so it steps aside.
        boolean atTop = grid.getChildAdapterPosition(first) == 0 && grid.computeVerticalScrollOffset() < 4 * dp;
        float alpha = atTop ? 1f : 0f;
        if (wrap.getAlpha() != alpha) wrap.animate().alpha(alpha).setDuration(200).start();
        if (!atTop) return;
        int[] g = new int[2], r = new int[2];
        first.getLocationOnScreen(g);
        root.getLocationOnScreen(r);
        int top = g[1] - r[1] - Math.round(30 * dp) - wrap.getHeight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrap.getLayoutParams();
        if (Math.abs(lp.topMargin - top) > 1) {
            lp.topMargin = Math.max(0, top);
            wrap.setLayoutParams(lp);
        }
    }

    private void show(List<KeepWatching.Item> items) {
        View wrap = (View) row.getParent();
        if (items.isEmpty()) {
            wrap.setVisibility(View.GONE);
            row.removeAllViews();
            shown = null;
            return;
        }
        boolean same = shown == items && row.getChildCount() == items.size();
        shown = items;
        if (!same) {
            View focused = row.findFocus();
            int keep = focused != null ? row.indexOfChild(focused) : -1;
            row.removeAllViews();
            for (KeepWatching.Item item : items) row.addView(card(item));
            if (keep >= 0 && keep < row.getChildCount()) row.getChildAt(keep).requestFocus();
        } else {
            for (int i = 0; i < items.size(); i++) bindPoster(row.getChildAt(i), items.get(i));
        }
        wrap.setVisibility(View.VISIBLE);
    }

    private View card(KeepWatching.Item item) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(8 * dp);
        card.setPadding(pad, pad, Math.round(16 * dp), pad);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(glass(false));
        card.setContentDescription(item.title);

        ImageView poster = new ImageView(activity);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0x33FFFFFF);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 9 * dp);
            }
        });
        poster.setClipToOutline(true);
        poster.setTag("poster");
        card.addView(poster, new LinearLayout.LayoutParams(Math.round(96 * dp), Math.round(54 * dp)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Math.round(140 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Math.round(12 * dp);
        text.addView(line(item.app.toUpperCase(), 10, 0x99FFFFFF, R.string.font_medium, 0.12f));
        text.addView(line(item.title, 15, Color.WHITE, R.string.font_medium, 0f));
        if (item.subtitle != null) text.addView(line(item.subtitle, 11, 0xA6FFFFFF, R.string.font_body, 0f));
        card.addView(text, tlp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = Math.round(14 * dp);
        card.setLayoutParams(clp);

        card.setOnFocusChangeListener((v, focus) -> {
            v.setBackground(glass(focus));
            // Critically damped feel: a quick decelerating scale, no overshoot.
            v.animate().scaleX(focus ? 1.06f : 1f).scaleY(focus ? 1.06f : 1f).translationZ(focus ? 10 * dp : 0)
                    .setDuration(180).setInterpolator(new DecelerateInterpolator(2f)).start();
        });
        card.setOnClickListener(v -> {
            try {
                activity.startActivity(item.intent);
            } catch (ActivityNotFoundException | SecurityException e) {
                android.content.Intent open = activity.getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
                if (open != null) activity.startActivity(open);
            }
        });
        bindPoster(card, item);
        return card;
    }

    private static void bindPoster(View card, KeepWatching.Item item) {
        ImageView poster = card.findViewWithTag("poster");
        if (poster != null && item.poster != null) poster.setImageBitmap(item.poster);
    }

    private TextView line(String s, int sp, int color, int font, float tracking) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(activity, font));
        t.setLetterSpacing(tracking);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(0, Math.round(2 * dp), 0, Math.round(2 * dp));
        return t;
    }

    /** The pills' glass, a step brighter with a white rim when focused. */
    private GradientDrawable glass(boolean focused) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{0x66FFFFFF, 0x40FFFFFF} : new int[]{0x38FFFFFF, 0x1AFFFFFF});
        g.setCornerRadius(16 * dp);
        g.setStroke(Math.max(1, Math.round(dp)), focused ? 0xCCFFFFFF : 0x33FFFFFF);
        return g;
    }
}
