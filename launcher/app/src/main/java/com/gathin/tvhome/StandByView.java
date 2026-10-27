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

    /** Next (+1) or previous (-1) face with a short cross-fade. */
    void step(int dir) {
        show(Math.floorMod(face + dir, FACES), true);
    }

    private void show(int f, boolean animate) {
        face = f;
        View next = f == 0 ? digits() : f == 1 ? analogFace(px(220)) : f == 2 ? widgets()
                : f == 3 ? solar() : f == 4 ? floatFace() : nowPlaying();
        if (animate) {
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
            float r = Math.min(w, h) * 0.06f;
            int core = night ? 0xFFB3261E : sun ? 0xFFFFE08A : 0xFFE5E5EA;
            glow.setShader(new android.graphics.RadialGradient(x, y, r * 5, (core & 0x00FFFFFF) | 0x66000000, 0,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r * 5, glow);
            body.setColor(core);
            c.drawCircle(x, y, r, body);
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 300);
        t.setLetterSpacing(-0.1f);
        t.setIncludeFontPadding(false);
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
        private final View tint = new View(getContext());
        private final android.widget.ImageView art = new android.widget.ImageView(getContext());
        private final TextView title, artist, prev, cur, next, idle;
        private final View fill;
        private final LinearLayout right;
        private MediaWatch.Track track;
        private List<Lyrics.Line> lines = java.util.Collections.emptyList();
        private String key = "";
        private int shownLine = -2;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                update();
                root.postDelayed(this, 250);
            }
        };

        NowPlayingFace() {
            root.addView(tint, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(72), 0, px(72), 0);

            LinearLayout left = new LinearLayout(getContext());
            left.setOrientation(LinearLayout.VERTICAL);
            TextClock time = clock(null, 120, R.string.font_light);
            time.setGravity(Gravity.START);
            left.addView(time);
            TextClock date = clock("EEEE d MMMM", 20, R.string.font_medium);
            date.setGravity(Gravity.START);
            date.setAlpha(0.7f);
            left.addView(date);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            right = new LinearLayout(getContext());
            right.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(getContext());
            head.setGravity(Gravity.CENTER_VERTICAL);
            art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            art.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * dp);
                }
            });
            art.setClipToOutline(true);
            head.addView(art, new LinearLayout.LayoutParams(px(96), px(96)));
            LinearLayout names = new LinearLayout(getContext());
            names.setOrientation(LinearLayout.VERTICAL);
            title = text("", 22, R.string.font_medium);
            title.setGravity(Gravity.START);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            artist = text("", 16, R.string.font_body);
            artist.setGravity(Gravity.START);
            artist.setAlpha(0.7f);
            artist.setSingleLine(true);
            names.addView(title);
            names.addView(artist);
            FrameLayout bar = new FrameLayout(getContext());
            GradientDrawable track = new GradientDrawable();
            track.setColor(0x33FFFFFF);
            track.setCornerRadius(px(2));
            bar.setBackground(track);
            fill = new View(getContext());
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(night() ? NIGHT_RED : Color.WHITE);
            fillBg.setCornerRadius(px(2));
            fill.setBackground(fillBg);
            fill.setPivotX(0);
            bar.addView(fill, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4)));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4));
            bl.topMargin = px(12);
            names.addView(bar, bl);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nl.leftMargin = px(18);
            head.addView(names, nl);
            right.addView(head);

            prev = lyric(20, 0.35f);
            cur = lyric(30, 1f);
            next = lyric(20, 0.35f);
            idle = lyric(18, 0.5f);
            idle.setText("No lyrics for this song");
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ll.topMargin = px(14);
            right.addView(prev, lp(28));
            right.addView(cur, ll);
            right.addView(next, ll);
            right.addView(idle, lp(28));
            row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
            root.addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView nothing = text("Nothing playing", 22, R.string.font_medium);
            nothing.setAlpha(0.6f);
            nothing.setTag("nothing");
            LayoutParams np = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END);
            np.rightMargin = px(160);
            root.addView(nothing, np);
            root.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { root.post(tick); }
                @Override public void onViewDetachedFromWindow(View v) {
                    root.removeCallbacks(tick);
                    if (media != null) media.removeListener(NowPlayingFace.this);
                }
            });
            onMedia(null);
        }

        private LinearLayout.LayoutParams lp(int topDp) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            l.topMargin = px(topDp);
            return l;
        }

        private TextView lyric(int sp, float alpha) {
            TextView t = text("", sp, R.string.font_medium);
            t.setGravity(Gravity.START);
            t.setAlpha(alpha);
            t.setMaxLines(2);
            return t;
        }

        @Override
        public void onMedia(MediaWatch.Track t) {
            track = t;
            root.findViewWithTag("nothing").setVisibility(t == null ? View.VISIBLE : View.GONE);
            right.setVisibility(t == null ? View.INVISIBLE : View.VISIBLE);
            if (t == null) return;
            title.setText(t.title);
            artist.setText(t.artist);
            if (t.art != null) art.setImageBitmap(t.art);
            else art.setImageResource(R.drawable.ic_cc_music);
            int c = night() ? 0xFF3A0A08 : averageColor(t.art);
            tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.TR_BL, new int[]{(c & 0x00FFFFFF) | 0x99000000, 0xFF000000}));
            String k = Lyrics.key(t.artist, t.title);
            if (!k.equals(key)) {
                key = k;
                lines = java.util.Collections.emptyList();
                shownLine = -2;
                Lyrics.load(t.artist, t.title, t.duration, (kk, l) -> {
                    if (kk.equals(key)) {
                        lines = l;
                        shownLine = -2;
                        update();
                    }
                });
            }
            update();
        }

        private void update() {
            MediaWatch.Track t = track;
            if (t == null) return;
            long pos = t.positionNow();
            fill.setScaleX(t.duration > 0 ? Math.min(1f, pos / (float) t.duration) : 0f);
            boolean has = !lines.isEmpty();
            idle.setVisibility(has ? View.GONE : View.VISIBLE);
            prev.setVisibility(has ? View.VISIBLE : View.GONE);
            cur.setVisibility(has ? View.VISIBLE : View.GONE);
            next.setVisibility(has ? View.VISIBLE : View.GONE);
            if (!has) return;
            int i = Lyrics.index(lines, pos);
            if (i == shownLine) return;
            shownLine = i;
            prev.setText(i > 0 ? lines.get(i - 1).text : "");
            cur.setText(i >= 0 ? lines.get(i).text : "♪");
            next.setText(i + 1 < lines.size() ? lines.get(i + 1).text : "");
            // The new line rises into place, as lyrics scroll in Music.
            cur.setTranslationY(px(14));
            cur.setAlpha(0.4f);
            cur.animate().translationY(0).alpha(1f).setDuration(320).start();
        }
    }

    /** The artwork's overall colour, from a 1-pixel scale; a neutral grey without art. */
    private static int averageColor(android.graphics.Bitmap b) {
        if (b == null || b.isRecycled()) return 0xFF3A3A3C;
        try {
            android.graphics.Bitmap one = android.graphics.Bitmap.createScaledBitmap(
                    b.getConfig() == android.graphics.Bitmap.Config.HARDWARE ? b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) : b, 1, 1, true);
            int c = one.getPixel(0, 0);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.4f);
            hsv[2] = Math.max(0.45f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFF3A3A3C;
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
