package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * This fork's StandBy, after iPhone StandBy: three full-screen faces on black, switched with Left/Right —
 * huge digits, an analog clock, and a clock beside this month's calendar. From 22:00 to 06:00 everything turns a dim
 * red, as StandBy's night mode does, so a dark room stays dark. Shared by StandByActivity and StandByDream.
 */
final class StandByView extends FrameLayout {
    static final int FACES = 6;
    private static final int NIGHT_RED = 0xFFB3261E;

    private final float dp;
    private final FrameLayout stage;
    private final LinearLayout dots;
    private int face;

    private final MediaWatch media;

    StandByView(Context ctx, int startFace) {
        this(ctx, startFace, null);
    }

    /** With a MediaWatch the sixth face shows what's playing, with lyrics. */
    StandByView(Context ctx, int startFace, MediaWatch media) {
        super(ctx);
        this.media = media;
        dp = ctx.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(ctx);
        addView(stage, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dots = new LinearLayout(ctx);
        dots.setGravity(Gravity.CENTER);
        LayoutParams dl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = px(28);
        addView(dots, dl);
        for (int i = 0; i < FACES; i++) {
            View d = new View(ctx);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.WHITE);
            d.setBackground(g);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(7), px(7));
            l.setMargins(px(5), 0, px(5), 0);
            dots.addView(d, l);
        }
        show(Math.floorMod(startFace, FACES), false);
    }

    int face() {
        return face;
    }

    /** Jump to a face (cross-fade), e.g. the Now Playing face when music starts. */
    void showFace(int f) {
        if (f != face) show(Math.floorMod(f, FACES), true);
    }

    static final int FACE_NOW_PLAYING = 5;

    /** Keys the current face uses itself (the Now Playing buttons); true when handled. */
    boolean onFaceKey(int key) {
        return face == FACE_NOW_PLAYING && playing != null && playing.onKey(key);
    }

    /** Next (+1) or previous (-1) face with a short cross-fade. */
    void step(int dir) {
        show(Math.floorMod(face + dir, FACES), true);
    }

    private void show(int f, boolean animate) {
        face = f;
        View next = f == 0 ? digits() : f == 1 ? analogFace(px(220)) : f == 2 ? widgets()
                : f == 3 ? solar() : f == 4 ? floatFace() : nowPlaying();
        if (animate) {
            // Only the face on screen fades out; any still fading from a quick earlier press goes at once, so two
            // faces never stack.
            while (stage.getChildCount() > 1) {
                View gone = stage.getChildAt(0);
                gone.animate().cancel();
                stage.removeView(gone);
            }
            View old = stage.getChildCount() > 0 ? stage.getChildAt(0) : null;
            next.setAlpha(0f);
            stage.addView(next);
            next.animate().alpha(1f).setDuration(350).start();
            if (old != null) old.animate().alpha(0f).setDuration(250).withEndAction(() -> stage.removeView(old)).start();
        } else {
            stage.removeAllViews();
            stage.addView(next);
        }
        for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setAlpha(i == f ? 0.9f : 0.25f);
    }

    static boolean night() {
        int h = LocalTime.now().getHour();
        return h >= 22 || h < 6;
    }

    private int ink() {
        return night() ? NIGHT_RED : Color.WHITE;
    }

    // ---- Solar: the sky's colour for this hour, the sun on its arc, a large clock -------------------------

    private View solar() {
        FrameLayout f = new FrameLayout(getContext());
        SolarSky sky = new SolarSky(getContext(), night());
        f.addView(sky, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextClock time = clock(null, 150, R.string.font_light);
        time.setShadowLayer(24 * dp, 0, 0, 0x40000000);
        col.addView(time);
        TextClock date = clock("EEEE d MMMM", 22, R.string.font_medium);
        date.setAlpha(0.85f);
        col.addView(date);
        LayoutParams cl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = px(70);
        f.addView(col, cl);
        return f;
    }

    /** iOS Solar's sky: a vertical gradient keyed to the time of day, and the sun (or moon) on a low arc. */
    static final class SolarSky extends View {
        private final boolean night;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable minute = this::invalidate;

        SolarSky(Context ctx, boolean night) {
            super(ctx);
            this.night = night;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            LocalTime t = LocalTime.now();
            float hour = t.getHour() + t.getMinute() / 60f;
            int[] sky;
            if (night) sky = new int[]{0xFF1A0303, 0xFF000000};
            else if (hour < 6 || hour >= 20) sky = new int[]{0xFF0B1026, 0xFF000000};
            else if (hour < 8) sky = new int[]{0xFFF6A55E, 0xFF5B3A78};
            else if (hour < 16) sky = new int[]{0xFF2F7DE1, 0xFF0B3C8C};
            else if (hour < 18) sky = new int[]{0xFFF29B53, 0xFF6A3E8F};
            else sky = new int[]{0xFF7A3B6E, 0xFF120D2B};
            Paint bg = new Paint();
            bg.setShader(new android.graphics.LinearGradient(0, h, 0, 0, sky, null, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, bg);
            // Day runs 06:00-18:00 left to right along a shallow arc; at night the moon takes the same path.
            float frac = ((hour + 18) % 12) / 12f;
            boolean sun = hour >= 6 && hour < 18 && !night;
            float x = w * (0.1f + 0.8f * frac);
            float y = h * 0.62f - (float) Math.sin(Math.PI * frac) * h * 0.38f;
            float r = Math.min(w, h) * 0.11f;
            // The same symbol as the home screen's weather pill, for the current reading: sun, cloud, rain, storm,
            // fog, or the moon at night.
            int code = 0;
            boolean isDay = sun;
            try {
                String json = getContext().getSharedPreferences("weather", Context.MODE_PRIVATE).getString("json", null);
                if (json != null) {
                    org.json.JSONObject cur = new org.json.JSONObject(json).optJSONObject("current");
                    if (cur != null) {
                        code = cur.optInt("weather_code");
                        isDay = cur.optInt("is_day", sun ? 1 : 0) == 1;
                    }
                }
            } catch (Exception ignored) {
            }
            int iconRes = Weather.icon(code, isDay);
            int core = night ? 0xFFB3261E : iconRes == R.drawable.ic_wx_sun ? 0xFFFFD60A : 0xFFE5E5EA;
            glow.setShader(new android.graphics.RadialGradient(x, y, r * 3.2f, (core & 0x00FFFFFF) | 0x59000000, 0,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r * 3.2f, glow);
            android.graphics.drawable.Drawable icon = getContext().getDrawable(iconRes);
            if (icon != null) {
                icon = icon.mutate();
                if (night) icon.setTint(0xFFB3261E);
                icon.setBounds((int) (x - r), (int) (y - r), (int) (x + r), (int) (y + r));
                icon.draw(c);
            }
            postDelayed(minute, 60_000);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(minute);
        }
    }

    // ---- Float: huge overlapping numerals, each its own colour ---------------------------------------------

    private static final int[][] FLOAT_PALETTES = {
            {0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF64D2FF},
            {0xFF30D158, 0xFF64D2FF, 0xFFFFD60A, 0xFFFF9F0A},
            {0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158},
    };

    private View floatFace() {
        TextView t = new TextView(getContext());
        t.setTypeface(UiFont.get(getContext(), R.string.font_medium));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 280);
        // Tight but not negative enough to push the outer digits past the view's edge, with room on every side.
        t.setLetterSpacing(-0.05f);
        t.setIncludeFontPadding(true);
        t.setPadding(px(48), px(24), px(48), px(24));
        t.setGravity(Gravity.CENTER);
        Runnable paint = new Runnable() {
            @Override
            public void run() {
                LocalTime now = LocalTime.now();
                String s = String.format(Locale.getDefault(), "%d%02d",
                        android.text.format.DateFormat.is24HourFormat(getContext()) ? now.getHour()
                                : (now.getHour() % 12 == 0 ? 12 : now.getHour() % 12), now.getMinute());
                int[] pal = FLOAT_PALETTES[now.getHour() % FLOAT_PALETTES.length];
                android.text.SpannableString sp = new android.text.SpannableString(s);
                for (int i = 0; i < s.length(); i++) {
                    int col = night() ? NIGHT_RED : pal[i % pal.length];
                    sp.setSpan(new android.text.style.ForegroundColorSpan(col), i, i + 1, 0);
                }
                t.setText(sp);
                t.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
            }
        };
        t.post(paint);
        return centered(t);
    }

    // ---- Now Playing with lyrics ---------------------------------------------------------------------------

    private NowPlayingFace playing;

    private View nowPlaying() {
        playing = new NowPlayingFace();
        if (media != null) media.addListener(playing);
        return playing.root;
    }

    /**
     * A small clock on the left; on the right the artwork, title, artist, progress and the lyrics, three lines at a
     * time with the current one bright, over a glow in the artwork's colour (Music's own look, kept dark).
     */
    final class NowPlayingFace implements MediaWatch.Listener {
        final FrameLayout root = new FrameLayout(getContext());
        private final android.widget.ImageView backdrop = new android.widget.ImageView(getContext());
        private final android.widget.ImageView art = new android.widget.ImageView(getContext());
        private final TextView title, artist, elapsed, remaining, idle, nothing, breath;
        private final TextClock time;
        private final View fill;
        private final GradientDrawable fillBg = new GradientDrawable();
        private final LinearLayout body, week, transport;
        private final FrameLayout viewport;
        private final android.widget.ScrollView scroller;
        private final LinearLayout column;
        private android.widget.ImageView playGlyph;
        private MediaWatch.Track track;
        private List<Lyrics.Line> lines = java.util.Collections.emptyList();
        private final List<LyricLine> lineViews = new java.util.ArrayList<>();
        private long latencyMs;
        private String key = "";
        private int current = -2, accent = Color.WHITE;
        private android.animation.ValueAnimator breathing;
        private android.animation.ObjectAnimator scrollAnim;
        /** Progress bar and times, four times a second. */
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                update();
                root.postDelayed(this, 250);
            }
        };
        /** The sweep and line changes on the display's own clock, every frame, while a song plays. */
        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                lyricsFrame();
                if (root.isAttachedToWindow()) root.postOnAnimation(this);
            }
        };

        NowPlayingFace() {
            backdrop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            root.addView(backdrop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(getContext());
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x99000000, 0xB3000000}));
            root.addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            body = new LinearLayout(getContext());
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(px(64), px(40), px(64), px(30));
            root.addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Top: the song on the left and the time on the right, on one line.
            LinearLayout head = new LinearLayout(getContext());
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout names = new LinearLayout(getContext());
            names.setOrientation(LinearLayout.VERTICAL);
            title = text("", 26, R.string.font_medium);
            title.setGravity(Gravity.START);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            artist = text("", 18, R.string.font_body);
            artist.setGravity(Gravity.START);
            artist.setAlpha(0.7f);
            artist.setSingleLine(true);
            names.addView(title);
            names.addView(artist);
            head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            time = clock(null, 56, R.string.font_light);
            head.addView(time);
            body.addView(head);

            // Middle: artwork with the transport under it, and the lyrics beside them.
            LinearLayout mid = new LinearLayout(getContext());
            mid.setGravity(Gravity.CENTER_VERTICAL);
            // The focused button's platter grows past its row: nothing up the tree may cut it.
            mid.setClipChildren(false);
            body.setClipChildren(false);
            LinearLayout leftCol = new LinearLayout(getContext());
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setGravity(Gravity.CENTER_HORIZONTAL);
            leftCol.setClipChildren(false);
            art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            art.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 14 * dp);
                }
            });
            art.setClipToOutline(true);
            art.setElevation(px(16));
            leftCol.addView(art, new LinearLayout.LayoutParams(px(220), px(220)));
            transport = new LinearLayout(getContext());
            transport.setGravity(Gravity.CENTER);
            transport.setClipChildren(false);
            transport.setPadding(0, px(8), 0, px(4));
            transport.setClipToPadding(false);
            // Locked until Down, so nothing is highlighted when the face appears.
            transport.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            transport.addView(button(R.drawable.ic_cc_prev, 52, v -> MediaWatch.previous(track)));
            View play = button(R.drawable.ic_cc_pause, 64, v -> MediaWatch.playPause(track));
            playGlyph = play.findViewWithTag("icon");
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(px(64), px(64));
            pl.setMargins(px(18), 0, px(18), 0);
            transport.addView(play, pl);
            transport.addView(button(R.drawable.ic_cc_next, 52, v -> MediaWatch.next(track)));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = px(14);
            leftCol.addView(transport, tl);
            mid.addView(leftCol);

            // The lyrics: every line in one column that glides, never re-laid out while the song plays.
            viewport = new FrameLayout(getContext());
            viewport.setClipChildren(true);
            // A ScrollView lays the whole song out (lines below the fold included); only its scroll position moves.
            scroller = new android.widget.ScrollView(getContext());
            scroller.setVerticalScrollBarEnabled(false);
            scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
            // Lines fade at the top and bottom edges instead of being cut, as in Music.
            scroller.setVerticalFadingEdgeEnabled(true);
            scroller.setFadingEdgeLength(px(90));
            scroller.setFocusable(false);
            scroller.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            column = new LinearLayout(getContext());
            column.setOrientation(LinearLayout.VERTICAL);
            // Room above the first line and below the last, so either can sit a third of the way down.
            column.setPadding(0, px(200), 0, px(400));
            scroller.addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            viewport.addView(scroller, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            breath = text("•  •  •", 34, R.string.font_medium);
            breath.setGravity(Gravity.START);
            breath.setPivotX(0);
            breath.setVisibility(View.GONE);
            viewport.addView(breath, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));
            idle = text("No lyrics for this song", 24, R.string.font_medium);
            idle.setGravity(Gravity.START);
            idle.setAlpha(0.5f);
            viewport.addView(idle, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            vl.leftMargin = px(56);
            mid.addView(viewport, vl);
            body.addView(mid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Bottom: progress with elapsed and remaining time, then this week.
            LinearLayout prog = new LinearLayout(getContext());
            prog.setGravity(Gravity.CENTER_VERTICAL);
            elapsed = text("", 14, R.string.font_medium);
            elapsed.setAlpha(0.7f);
            prog.addView(elapsed, new LinearLayout.LayoutParams(px(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout bar = new FrameLayout(getContext());
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x33FFFFFF);
            trackBg.setCornerRadius(px(3));
            bar.setBackground(trackBg);
            fill = new View(getContext());
            fillBg.setCornerRadius(px(3));
            fill.setBackground(fillBg);
            fill.setPivotX(0);
            bar.addView(fill, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6)));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, px(6), 1f);
            bl.setMargins(px(10), 0, px(10), 0);
            prog.addView(bar, bl);
            remaining = text("", 14, R.string.font_medium);
            remaining.setAlpha(0.7f);
            prog.addView(remaining, new LinearLayout.LayoutParams(px(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gl.topMargin = px(14);
            body.addView(prog, gl);
            week = new LinearLayout(getContext());
            week.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wl.topMargin = px(14);
            body.addView(week, wl);

            nothing = text("Nothing playing", 26, R.string.font_medium);
            nothing.setAlpha(0.6f);
            root.addView(nothing, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            root.setFocusable(true);
            root.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    root.post(tick);
                    root.postOnAnimation(frame);
                    root.requestFocus();
                }
                @Override public void onViewDetachedFromWindow(View v) {
                    root.removeCallbacks(tick);
                    root.removeCallbacks(frame);
                    if (breathing != null) breathing.cancel();
                    if (media != null) media.removeListener(NowPlayingFace.this);
                }
            });
            paintAccent(Color.WHITE);
            onMedia(null);
        }

        /**
         * Music's transport: bare glyphs, no circles, the play/pause glyph the largest. On TV the focused one sits on
         * a white platter with a dark glyph and lifts slightly, as tvOS focus does.
         */
        private View button(int icon, int sizeDp, View.OnClickListener action) {
            FrameLayout b = new FrameLayout(getContext());
            b.setFocusable(true);
            b.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.TRANSPARENT);
            b.setBackground(bg);
            android.widget.ImageView g = new android.widget.ImageView(getContext());
            g.setTag("icon");
            g.setImageResource(icon);
            g.setColorFilter(0xE6FFFFFF);
            int gs = Math.round(sizeDp * 0.6f);
            b.addView(g, new LayoutParams(px(gs), px(gs), Gravity.CENTER));
            b.setLayoutParams(new LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)));
            b.setOnClickListener(action);
            b.setOnFocusChangeListener((v, f) -> {
                bg.setColor(f ? Color.WHITE : Color.TRANSPARENT);
                g.setColorFilter(f ? 0xFF1C1C1E : 0xE6FFFFFF);
                v.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            });
            return b;
        }

        /** Remote keys for this face; true when used. Down reaches the buttons, Up leaves them. */
        boolean onKey(int key) {
            View focused = transport.findFocus();
            if (focused == null) {
                if (key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    transport.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                    transport.getChildAt(1).requestFocus();
                    return true;
                }
                if (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER) {
                    MediaWatch.playPause(track);
                    return true;
                }
                return false;
            }
            int at = transport.indexOfChild(focused);
            switch (key) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    if (at > 0) transport.getChildAt(at - 1).requestFocus();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (at < 2) transport.getChildAt(at + 1).requestFocus();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    focused.clearFocus();
                    transport.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                    root.requestFocus();
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    focused.performClick();
                    return true;
                default:
                    return key == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            }
        }

        /** This week, Monday to Sunday, today in a pill of the artwork's colour. */
        private void buildWeek() {
            week.removeAllViews();
            LocalDate today = LocalDate.now();
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
            for (int i = 0; i < 7; i++) {
                LocalDate d = monday.plusDays(i);
                boolean now = d.equals(today);
                LinearLayout cell = new LinearLayout(getContext());
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(px(14), px(6), px(14), px(8));
                if (now) {
                    GradientDrawable pill = new GradientDrawable();
                    pill.setColor(accent);
                    pill.setCornerRadius(px(14));
                    cell.setBackground(pill);
                }
                TextView name = text(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault()).toUpperCase(), 12, R.string.font_medium);
                name.setTextColor(now ? 0xFF1C1C1E : 0x99FFFFFF);
                TextView num = text(String.valueOf(d.getDayOfMonth()), 20, R.string.font_medium);
                num.setTextColor(now ? 0xFF1C1C1E : Color.WHITE);
                cell.addView(name);
                cell.addView(num);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.setMargins(px(6), 0, px(6), 0);
                week.addView(cell, cl);
            }
        }

        private void paintAccent(int c) {
            accent = night() ? NIGHT_RED : c;
            time.setTextColor(accent);
            fillBg.setColor(accent);
            breath.setTextColor(accent);
            buildWeek();
            current = -2;
        }

        /** One view per line, built once per song. */
        private void buildLines() {
            column.removeAllViews();
            lineViews.clear();
            for (Lyrics.Line l : lines) {
                LyricLine t = new LyricLine(getContext());
                t.setText(l.text.isEmpty() ? " " : l.text);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                t.setTypeface(UiFont.get(getContext(), R.string.font_medium));
                t.setIncludeFontPadding(false);
                t.setGravity(Gravity.START);
                t.setPivotX(0);
                t.setTextColor(Color.WHITE);
                t.setAlpha(0.32f);
                t.setScaleX(0.92f);
                t.setScaleY(0.92f);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ll.bottomMargin = px(18);
                column.addView(t, ll);
                lineViews.add(t);
            }
            scroller.scrollTo(0, 0);
            current = -2;
        }

        @Override
        public void onMedia(MediaWatch.Track t) {
            track = t;
            nothing.setVisibility(t == null ? View.VISIBLE : View.GONE);
            body.setVisibility(t == null ? View.INVISIBLE : View.VISIBLE);
            if (t == null) {
                backdrop.setImageDrawable(null);
                return;
            }
            playGlyph.setImageResource(t.playing ? R.drawable.ic_cc_pause : R.drawable.ic_cc_play);
            latencyMs = outputLatency() + Config.lyricsOffsetMs;
            String k = Lyrics.key(t.artist, t.title);
            if (k.equals(key)) return;   // same song, a play/pause or position update only
            key = k;
            title.setText(t.title);
            artist.setText(t.artist);
            if (t.art != null) {
                art.setImageBitmap(t.art);
                backdrop.setImageBitmap(soften(t.art));
                paintAccent(vivid(t.art));
            } else {
                art.setImageResource(R.drawable.ic_cc_music);
                paintAccent(Color.WHITE);
            }
            lines = java.util.Collections.emptyList();
            buildLines();
            fetch(t);
        }

        /** Look the song up; when the lookup itself failed (offline, LRCLIB busy), try again in 20 seconds. */
        private void fetch(MediaWatch.Track t) {
            String k = Lyrics.key(t.artist, t.title);
            Lyrics.load(t.artist, t.title, t.duration, (kk, l) -> {
                if (!kk.equals(key)) return;
                lines = l;
                buildLines();
                if (l.isEmpty() && !Lyrics.known(kk)) {
                    root.postDelayed(() -> { if (k.equals(key) && track != null) fetch(track); }, 20_000);
                }
            });
        }

        private void update() {
            MediaWatch.Track t = track;
            if (t == null) return;
            long pos = t.positionNow();
            fill.setScaleX(t.duration > 0 ? Math.min(1f, pos / (float) t.duration) : 0f);
            elapsed.setText(mmss(pos));
            remaining.setText(t.duration > 0 ? "-" + mmss(Math.max(0, t.duration - pos)) : "");
            boolean has = !lines.isEmpty();
            idle.setVisibility(has ? View.GONE : View.VISIBLE);
            scroller.setVisibility(has ? View.VISIBLE : View.GONE);
            if (!has) breathe(false);
        }

        /**
         * Where the singer is, as heard: the player reports what it has sent to the speakers, so the speakers' own
         * delay is taken off (larger for a Bluetooth soundbar), then mapped onto the lyric file's timeline.
         */
        private long heardPosition(MediaWatch.Track t) {
            return Lyrics.sourcePosition(key, Math.max(0, t.positionNow() - latencyMs), t.duration);
        }

        private void lyricsFrame() {
            MediaWatch.Track t = track;
            if (t == null || lines.isEmpty()) return;
            long pos = heardPosition(t);
            int i = Lyrics.index(lines, pos);
            if (i != current) moveTo(i);
            boolean gap = i < 0 || lines.get(i).text.isEmpty();
            breathe(gap && t.playing);
            if (gap) {
                // The dots take the place of the line that isn't there: the break's own row, or a third of the way
                // down before the first line.
                float y = i >= 0 && i < lineViews.size()
                        ? lineViews.get(i).getTop() - scroller.getScrollY() + (lineViews.get(i).getHeight() - breath.getHeight()) / 2f
                        : viewport.getHeight() * 0.32f;
                breath.setY(y);
            }
            if (!gap && i < lineViews.size()) {
                long start = lines.get(i).at;
                long end = i + 1 < lines.size() ? lines.get(i + 1).at : start + 4000;
                // Singers finish a line before the next begins: sweep over 85% of the gap, at least 0.6 s.
                float f = (pos - start) / (float) Math.max(600, (end - start) * 0.85f);
                lineViews.get(i).setProgress(Math.max(0f, Math.min(1f, f)));
            }
        }

        /** Glide the column so the current line sits a third of the way down; it brightens, the others recede. */
        private void moveTo(int i) {
            int old = current;
            current = i;
            if (old >= 0 && old < lineViews.size()) {
                LyricLine o = lineViews.get(old);
                o.setProgress(-1f);
                o.animate().alpha(0.32f).scaleX(0.92f).scaleY(0.92f).setDuration(500).start();
            }
            if (i < 0 || i >= lineViews.size()) return;
            LyricLine n = lineViews.get(i);
            n.setColors(accent, 0x80FFFFFF);
            n.setProgress(0f);
            n.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            int target = Math.max(0, n.getTop() - Math.round(viewport.getHeight() * 0.32f));
            if (scrollAnim != null) scrollAnim.cancel();
            scrollAnim = android.animation.ObjectAnimator.ofInt(scroller, "scrollY", scroller.getScrollY(), target);
            scrollAnim.setDuration(700);
            scrollAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            scrollAnim.start();
        }

        /** Instrumental breaks: three dots that swell and settle, like breathing, as in Music. */
        private void breathe(boolean on) {
            if (on == (breath.getVisibility() == View.VISIBLE)) return;
            if (on) {
                breath.setVisibility(View.VISIBLE);
                breath.setAlpha(0f);
                breath.animate().alpha(1f).setDuration(400).start();
                breathing = android.animation.ValueAnimator.ofFloat(0.88f, 1.12f);
                breathing.setDuration(1600);
                breathing.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                breathing.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                breathing.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                breathing.addUpdateListener(a -> {
                    float s = (float) a.getAnimatedValue();
                    breath.setScaleX(s);
                    breath.setScaleY(s);
                });
                breathing.start();
            } else {
                if (breathing != null) breathing.cancel();
                breath.animate().alpha(0f).setDuration(300).withEndAction(() -> breath.setVisibility(View.GONE)).start();
            }
        }

        /** Typical delay of the output in use: Bluetooth A2DP buffers about a fifth of a second, HDMI ARC less. */
        private long outputLatency() {
            android.media.AudioManager am = getContext().getSystemService(android.media.AudioManager.class);
            long best = 40;
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                int ty = d.getType();
                if (ty == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return 220;
                if (ty == android.media.AudioDeviceInfo.TYPE_HDMI_ARC || ty == 29) best = 90;
            }
            return best;
        }

        private String mmss(long ms) {
            long s = ms / 1000;
            return (s / 60) + ":" + String.format(Locale.getDefault(), "%02d", s % 60);
        }
    }

    private static android.graphics.Bitmap software(android.graphics.Bitmap b) {
        return b.getConfig() == android.graphics.Bitmap.Config.HARDWARE ? b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) : b;
    }

    /** A few pixels across: stretched to the screen it reads as a heavy blur. */
    private static android.graphics.Bitmap soften(android.graphics.Bitmap b) {
        try {
            android.graphics.Bitmap s = software(b);
            android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(s, 12, 12, true);
            return android.graphics.Bitmap.createScaledBitmap(tiny, 48, 48, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The artwork's most vivid colour, lifted until it reads on the dark backdrop: Music tints its text the same
     * way. Greys are skipped; an all-grey cover gives white.
     */
    private static int vivid(android.graphics.Bitmap b) {
        try {
            android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(software(b), 24, 24, true);
            float best = 0;
            int pick = Color.WHITE;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                Color.colorToHSV(s.getPixel(x, y), hsv);
                float score = hsv[1] * (0.3f + hsv[2]);
                if (hsv[2] > 0.25f && score > best) {
                    best = score;
                    pick = s.getPixel(x, y);
                }
            }
            if (best < 0.2f) return Color.WHITE;
            Color.colorToHSV(pick, hsv);
            hsv[1] = Math.min(hsv[1], 0.75f);
            hsv[2] = Math.max(hsv[2], 0.92f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    // ---- faces -------------------------------------------------------------------------------------------

    private View digits() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextClock date = clock("EEEE d MMMM", 22, R.string.font_medium);
        date.setAlpha(0.7f);
        col.addView(date);
        TextClock time = clock(null, 210, R.string.font_light);
        time.setLetterSpacing(-0.04f);
        col.addView(time);
        return centered(col);
    }

    private View widgets() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER);
        row.addView(analogFace(px(170)));
        View cal = calendar();
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = px(64);
        row.addView(cal, cl);
        return centered(row);
    }

    /** This month as StandBy's calendar widget: the month in red, today in a red circle. */
    private View calendar() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        LocalDate today = LocalDate.now();
        TextView month = text(today.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()).toUpperCase(), 20, R.string.font_medium);
        month.setTextColor(night() ? NIGHT_RED : 0xFFFF453A);
        month.setLetterSpacing(0.06f);
        col.addView(month);
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(7);
        int cell = px(40);
        for (DayOfWeek d : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY}) {
            TextView t = text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), 14, R.string.font_medium);
            t.setAlpha(0.5f);
            grid.addView(t, cellParams(cell));
        }
        LocalDate first = today.withDayOfMonth(1);
        for (int i = 1; i < first.getDayOfWeek().getValue(); i++) grid.addView(new View(getContext()), cellParams(cell));
        for (int day = 1; day <= today.lengthOfMonth(); day++) {
            TextView t = text(String.valueOf(day), 18, R.string.font_medium);
            if (day == today.getDayOfMonth()) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(night() ? NIGHT_RED : 0xFFFF453A);
                t.setBackground(g);
                t.setTextColor(night() ? Color.BLACK : Color.WHITE);
            }
            grid.addView(t, cellParams(cell));
        }
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = px(10);
        col.addView(grid, gl);
        return col;
    }

    private GridLayout.LayoutParams cellParams(int size) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = size;
        p.height = size;
        return p;
    }

    private View analogFace(int size) {
        AnalogClock c = new AnalogClock(getContext(), ink(), night());
        c.setLayoutParams(new LinearLayout.LayoutParams(size * 2, size * 2));
        FrameLayout wrap = new FrameLayout(getContext());
        wrap.addView(c, new LayoutParams(size * 2, size * 2, Gravity.CENTER));
        return size == px(220) ? centered(wrap) : wrap;
    }

    private View centered(View v) {
        FrameLayout f = new FrameLayout(getContext());
        f.addView(v, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        f.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    private TextClock clock(String pattern, int sp, int font) {
        TextClock t = new TextClock(getContext());
        if (pattern != null) {
            t.setFormat12Hour(pattern);
            t.setFormat24Hour(pattern);
        } else {
            t.setFormat12Hour("h:mm");
            t.setFormat24Hour("H:mm");
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(getContext(), font));
        t.setTextColor(ink());
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView text(String s, int sp, int font) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(getContext(), font));
        t.setTextColor(ink());
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    /** A clock face: twelve ticks and numerals, white hands, an orange seconds hand; redraws once a second. */
    static final class AnalogClock extends View {
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG), hand = new Paint(Paint.ANTI_ALIAS_FLAG),
                sec = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable tickTock = this::invalidate;

        AnalogClock(Context ctx, int ink, boolean night) {
            super(ctx);
            float dp = ctx.getResources().getDisplayMetrics().density;
            tick.setColor(ink);
            tick.setStrokeCap(Paint.Cap.ROUND);
            hand.setColor(ink);
            hand.setStrokeCap(Paint.Cap.ROUND);
            sec.setColor(night ? ink : 0xFFFF9F0A);
            sec.setStrokeWidth(2 * dp);
            sec.setStrokeCap(Paint.Cap.ROUND);
            num.setColor(ink);
            num.setTextAlign(Paint.Align.CENTER);
            Typeface tf = UiFont.get(ctx, R.string.font_medium);
            num.setTypeface(tf);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.95f;
            for (int i = 0; i < 60; i++) {
                boolean hour = i % 5 == 0;
                tick.setStrokeWidth(r * (hour ? 0.018f : 0.008f));
                tick.setAlpha(hour ? 255 : 110);
                double a = Math.toRadians(i * 6);
                float o = r, in = r * (hour ? 0.90f : 0.94f);
                c.drawLine(cx + (float) Math.sin(a) * in, cy - (float) Math.cos(a) * in,
                        cx + (float) Math.sin(a) * o, cy - (float) Math.cos(a) * o, tick);
            }
            num.setTextSize(r * 0.16f);
            for (int h = 1; h <= 12; h++) {
                double a = Math.toRadians(h * 30);
                float d = r * 0.75f;
                c.drawText(String.valueOf(h), cx + (float) Math.sin(a) * d, cy - (float) Math.cos(a) * d + r * 0.055f, num);
            }
            LocalTime t = LocalTime.now();
            float hours = (t.getHour() % 12) + t.getMinute() / 60f, minutes = t.getMinute() + t.getSecond() / 60f;
            line(c, cx, cy, hours * 30, r * 0.5f, r * 0.045f, hand);
            line(c, cx, cy, minutes * 6, r * 0.8f, r * 0.03f, hand);
            line(c, cx, cy, t.getSecond() * 6, r * 0.88f, -1, sec);
            c.drawCircle(cx, cy, r * 0.035f, sec);
            postDelayed(tickTock, 1000 - SystemClock.uptimeMillis() % 1000);
        }

        private static void line(Canvas c, float cx, float cy, float deg, float len, float w, Paint p) {
            if (w > 0) p.setStrokeWidth(w);
            double a = Math.toRadians(deg);
            c.drawLine(cx, cy, cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, p);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(tickTock);
        }
    }
}
