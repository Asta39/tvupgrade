package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;

/**
 * "It's a match!" (this fork): the title everyone in the room liked on their phones (Together), big on the TV over
 * its own poster, blurred. OK on Play opens it in its app; Back closes it and the group can keep swiping.
 */
public class MatchActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        String key = getIntent().getStringExtra("key");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0F);
        root.setClipChildren(false);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setAlpha(0.45f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            back.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(60 * dp, 60 * dp, android.graphics.Shader.TileMode.CLAMP));
        }
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x99000000, 0x33000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // The focused button grows a little: nothing up the chain may clip it.
        row.setClipChildren(false);
        row.setClipToPadding(false);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setClipToOutline(true);
        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(px(22));
        round.setColor(0x22FFFFFF);
        poster.setBackground(round);
        poster.setElevation(px(18));
        row.addView(poster, new LinearLayout.LayoutParams(px(280), px(420)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(56), 0, 0, 0);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        TextView eyebrow = text("IT’S A MATCH!", 18, 0xFFFF6B8A, R.string.font_medium);
        eyebrow.setLetterSpacing(0.14f);
        col.addView(eyebrow);
        TextView title = text(getIntent().getStringExtra("title"), 52, Color.WHITE, R.string.font_medium);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title, lp(10));
        TextView sub = text(getIntent().getStringExtra("sub"), 18, 0xD9FFFFFF, R.string.font_regular);
        col.addView(sub, lp(8));
        TextView everyone = text("Everyone liked this one.", 16, 0x99FFFFFF, R.string.font_regular);
        col.addView(everyone, lp(18));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        TextView play = button("▶  Play", true);
        TextView later = button("Keep swiping", false);
        buttons.addView(play);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = px(14);
        buttons.addView(later, gap);
        col.addView(buttons, lp(34));
        row.addView(col, new LinearLayout.LayoutParams(px(500), -2));
        root.addView(row, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        play.setOnClickListener(v -> new Thread(() -> {
            try {
                JSONObject r = Together.play(this, key);
                if (!r.has("error")) {
                    Together.end();
                    main.post(this::finish);
                }
            } catch (Exception ignored) {
            }
        }, "match-play").start());
        later.setOnClickListener(v -> finish());
        play.requestFocus();

        // The room hears it too: a pop as the screen appears.
        root.setAlpha(0f);
        row.setScaleX(0.9f);
        row.setScaleY(0.9f);
        root.animate().alpha(1f).setDuration(350).start();
        row.animate().scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();

        String url = getIntent().getStringExtra("poster");
        if (url != null && url.startsWith("https://")) new Thread(() -> {
            try (InputStream in = new URL(url).openStream()) {
                Bitmap b = BitmapFactory.decodeStream(in);
                main.post(() -> {
                    poster.setImageBitmap(b);
                    back.setImageBitmap(b);
                });
            } catch (Exception ignored) {
            }
        }, "match-poster").start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private TextView button(String s, boolean primary) {
        TextView b = text(s, 18, primary ? 0xFF0B0B0D : Color.WHITE, R.string.font_medium);
        b.setPadding(px(30), px(14), px(30), px(14));
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable off = pill(primary ? 0xE6FFFFFF : 0x33FFFFFF), on = pill(Color.WHITE);
        b.setBackground(off);
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? on : off);
            b.setTextColor(f || primary ? 0xFF0B0B0D : Color.WHITE);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(150).start();
        });
        return b;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(40));
        return d;
    }

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s == null ? "" : s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        Typeface face = UiFont.get(this, font);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.topMargin = px(topDp);
        return l;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
