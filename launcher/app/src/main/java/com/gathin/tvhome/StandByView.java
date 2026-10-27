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
import java.util.Locale;

/**
 * This fork's StandBy, after iPhone StandBy: three full-screen faces on black, switched with Left/Right —
 * huge digits, an analog clock, and a clock beside this month's calendar. From 22:00 to 06:00 everything turns a dim
 * red, as StandBy's night mode does, so a dark room stays dark. Shared by StandByActivity and StandByDream.
 */
final class StandByView extends FrameLayout {
    static final int FACES = 3;
    private static final int NIGHT_RED = 0xFFB3261E;

    private final float dp;
    private final FrameLayout stage;
    private final LinearLayout dots;
    private int face;

    StandByView(Context ctx, int startFace) {
        super(ctx);
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
        View next = f == 0 ? digits() : f == 1 ? analogFace(px(220)) : widgets();
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
