package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * "Thanks for staying" (this fork): what the TV shows at check-out (Checkout), over one of the place's own pictures.
 * The guests' names, the place, and a QR code for the host's review link when there is one. Any key, or five
 * minutes, goes on to the welcome screen, ready for the next guests.
 */
public class ThanksActivity extends Activity {
    private static final long SHOW_MS = 5 * 60 * 1000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        String names = getIntent().getStringExtra("names");
        String place = getIntent().getStringExtra("place");
        String review = getIntent().getStringExtra("review");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0F);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap pic = picture();
        if (pic != null) back.setImageBitmap(pic);
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        android.view.View shade = new android.view.View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6000000, 0x66000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("SAFE TRAVELS", 18, 0xFFFFB86B, R.string.font_medium);
        eyebrow.setLetterSpacing(0.14f);
        col.addView(eyebrow);
        String who = names == null || names.isEmpty() ? "" : ", " + names;
        TextView title = text("Thanks for staying" + who + ".", 44, Color.WHITE, R.string.font_medium);
        title.setMaxLines(3);
        col.addView(title, lp(12));
        String line = place == null || place.isEmpty() ? "We hope you enjoyed your stay."
                : "We hope you enjoyed " + place + ".";
        col.addView(text(line, 22, 0xD9FFFFFF, R.string.font_regular), lp(14));
        col.addView(text("You've been signed out of the streaming apps on this TV.", 17, 0x99FFFFFF, R.string.font_regular), lp(26));
        row.addView(col, new LinearLayout.LayoutParams(px(520), -2));

        if (review != null && review.startsWith("http")) {
            LinearLayout qr = new LinearLayout(this);
            qr.setOrientation(LinearLayout.VERTICAL);
            qr.setGravity(Gravity.CENTER_HORIZONTAL);
            qr.setPadding(px(18), px(18), px(18), px(14));
            GradientDrawable card = new GradientDrawable();
            card.setColor(Color.WHITE);
            card.setCornerRadius(px(28));
            qr.setBackground(card);
            ImageView code = new ImageView(this);
            Bitmap b = HostQr.bitmap(review, px(170));
            if (b != null) code.setImageBitmap(b);
            qr.addView(code, new LinearLayout.LayoutParams(px(170), px(170)));
            TextView cap = text("Leave a review", 17, 0xFF1C1C1E, R.string.font_medium);
            cap.setGravity(Gravity.CENTER);
            qr.addView(cap, lp(12));
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-2, -2);
            ql.leftMargin = px(56);
            row.addView(qr, ql);
        }
        root.addView(row, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(600).start();
        main.postDelayed(this::done, SHOW_MS);
    }

    /** One of the theme's wallpapers, small enough for a backdrop. */
    private Bitmap picture() {
        try {
            File dir = new File(Config.dir(this), "wallpapers");
            File[] all = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
            if (all == null || all.length == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeFile(all[(int) (System.currentTimeMillis() / 86_400_000L % all.length)].getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    private void done() {
        if (isFinishing()) return;
        if (Stay.guestMode(this)) {
            startActivity(new Intent(this, GuestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event);
        }
        done();
        return true;
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
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
