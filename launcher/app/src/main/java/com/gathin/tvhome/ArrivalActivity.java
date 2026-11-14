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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The arrival show (this fork): the first time a guest's phone opens the page on check-in day, the TV plays a short
 * welcome over the place's own pictures (the theme's wallpapers, slowly zooming): their names, the place, the Wi-Fi
 * with a join code, a tour of the house guide, their stay, and how the phone works as the remote. It ends on the
 * welcome screen. Any key but volume skips to the end.
 * <p>
 * HostServer starts it once per stay ("arrival_shown" in stay.json holds the stay it played for); the host can
 * also preview it from the phone.
 */
public class ArrivalActivity extends Activity {
    private static final long SLIDE_MS = 7000, FADE_MS = 900;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Bitmap> pictures = new ArrayList<>();
    private final List<View> slides = new ArrayList<>();
    private float dp;
    private FrameLayout stage;
    private ImageView[] walls;
    private int at = -1, wallTop;
    private boolean done;

    private final Runnable step = this::next;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Config cfg = Config.load(this);
        Stay stay = Stay.load(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        walls = new ImageView[]{wall(), wall()};
        for (ImageView w : walls) root.addView(w, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x55000000, 0x22000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        TextView skip = text("Press OK to skip", 13, 0x99FFFFFF, R.string.font_regular);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        sl.setMargins(0, 0, px(48), px(32));
        root.addView(skip, sl);
        setContentView(root);

        // Up to five of the place's pictures, decoded small: a slideshow, not a wallpaper.
        for (File f : cfg.wallpapers) {
            if (pictures.size() >= 5) break;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) pictures.add(b);
        }

        String names = stay.names.isEmpty() ? "" : stay.names;
        String place = stay.place.isEmpty() ? "your home for now" : stay.place;
        slides.add(big(names.isEmpty() ? "Welcome" : "Welcome, " + names, "to " + place));
        if (!stay.about.isEmpty()) slides.add(big(stay.place.isEmpty() ? "About the place" : stay.place, stay.about));
        if (!stay.wifiName.isEmpty()) slides.add(wifi(stay));
        if (!stay.rules.isEmpty()) slides.add(list("A quick tour", stay.rules));
        List<String> times = new ArrayList<>();
        times.add("Check-in from " + Stay.friendly(stay.checkin));
        if (stay.staying()) {
            times.add(stay.nights + (stay.nights == 1 ? " night" : " nights") + " · check-out "
                    + stay.depart.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", java.util.Locale.getDefault()))
                    + " by " + Stay.friendly(stay.checkout));
        } else {
            times.add("Check-out by " + Stay.friendly(stay.checkout));
        }
        if (!stay.doorCode.isEmpty()) times.add("Door code " + stay.doorCode);
        slides.add(list("Your stay", times));
        slides.add(phone());
        slides.add(big("Enjoy your stay", names.isEmpty() ? "Make yourselves at home." : "Make yourselves at home, " + names + "."));
        next();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            // Right moves on a slide; anything else ends the show.
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                main.removeCallbacks(step);
                next();
            } else finishShow();
        }
        return true;
    }

    private void next() {
        if (done) return;
        at++;
        if (at >= slides.size()) {
            finishShow();
            return;
        }
        // The picture behind: cross-fade to the next one, then a slow push in (Ken Burns), so the house feels alive.
        if (!pictures.isEmpty()) {
            ImageView in = walls[wallTop = 1 - wallTop], out = walls[1 - wallTop];
            in.setImageBitmap(pictures.get(at % pictures.size()));
            in.setAlpha(0f);
            in.setScaleX(1f);
            in.setScaleY(1f);
            in.setTranslationX(at % 2 == 0 ? -px(20) : px(20));
            in.animate().alpha(1f).setStartDelay(0).setDuration(FADE_MS).start();
            in.animate().scaleX(1.12f).scaleY(1.12f).translationX(at % 2 == 0 ? px(20) : -px(20)).setStartDelay(0)
                    .setDuration(SLIDE_MS + FADE_MS).setInterpolator(new android.view.animation.LinearInterpolator()).start();
            // Both pictures sit under the shade and the words; a short overlap keeps the cross-fade from dipping.
            out.animate().alpha(0f).setStartDelay(FADE_MS / 2).setDuration(FADE_MS).start();
        }
        View old = stage.getChildCount() > 0 ? stage.getChildAt(0) : null;
        if (old != null) old.animate().alpha(0f).translationY(-px(16)).setDuration(450).withEndAction(() -> stage.removeView(old)).start();
        View slide = slides.get(at);
        slide.setAlpha(0f);
        slide.setTranslationY(px(24));
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        l.setMargins(px(96), 0, px(96), px(110));
        stage.addView(slide, l);
        slide.animate().alpha(1f).translationY(0).setStartDelay(350).setDuration(700)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        main.postDelayed(step, SLIDE_MS);
    }

    private void finishShow() {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        Class<?> home = Stay.guestMode(this) ? GuestActivity.class : MainActivity.class;
        startActivity(new Intent(this, home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ---- slides --------------------------------------------------------------------------------------------

    private View big(String head, String line) {
        LinearLayout col = column();
        TextView h = text(head, 64, Color.WHITE, R.string.font_medium);
        h.setLetterSpacing(-0.02f);
        h.setShadowLayer(24 * dp, 0, 0, 0x66000000);
        h.setMaxLines(2);
        h.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h);
        if (!line.isEmpty()) {
            TextView s = text(line, 26, 0xE6FFFFFF, R.string.font_regular);
            s.setMaxLines(3);
            s.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(s, lp(12));
        }
        return col;
    }

    private View list(String head, List<String> lines) {
        LinearLayout col = column();
        col.addView(eyebrow(head));
        for (int i = 0; i < lines.size() && i < 5; i++) {
            TextView t = text(lines.get(i), 30, Color.WHITE, R.string.font_medium);
            t.setShadowLayer(16 * dp, 0, 0, 0x66000000);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t, lp(i == 0 ? 16 : 10));
        }
        return col;
    }

    /** The Wi-Fi, with a code phones join from the camera (the standard WIFI: format). */
    private View wifi(Stay stay) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String join = "WIFI:T:" + (stay.wifiPassword.isEmpty() ? "nopass" : "WPA") + ";S:" + qrEscape(stay.wifiName) + ";"
                + (stay.wifiPassword.isEmpty() ? "" : "P:" + qrEscape(stay.wifiPassword) + ";") + ";";
        Bitmap qr = HostQr.bitmap(join, px(150));
        if (qr != null) row.addView(qrTile(qr), new LinearLayout.LayoutParams(px(178), px(178)));
        LinearLayout col = column();
        col.setPadding(qr == null ? 0 : px(36), 0, 0, 0);
        col.addView(eyebrow("Wi-Fi"));
        col.addView(text(stay.wifiName, 48, Color.WHITE, R.string.font_medium), lp(8));
        if (!stay.wifiPassword.isEmpty()) col.addView(text("Password  " + stay.wifiPassword, 28, 0xE6FFFFFF, R.string.font_regular), lp(6));
        if (qr != null) col.addView(text("Point your phone’s camera at the code to join.", 18, 0xB3FFFFFF, R.string.font_regular), lp(14));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private View phone() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String url = HostServer.url(this);
        Bitmap qr = url == null ? null : HostQr.bitmap(url, px(150));
        if (qr != null) row.addView(qrTile(qr), new LinearLayout.LayoutParams(px(178), px(178)));
        LinearLayout col = column();
        col.setPadding(qr == null ? 0 : px(36), 0, 0, 0);
        col.addView(eyebrow("Your phone is the remote"));
        col.addView(text("Find something to watch together", 40, Color.WHITE, R.string.font_medium), lp(8));
        col.addView(text("Swipe on everyone’s phone, play music for the room, and see the house guide.", 22, 0xE6FFFFFF, R.string.font_regular), lp(8));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private View qrTile(Bitmap qr) {
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(px(22));
        tile.setBackground(bg);
        ImageView q = new ImageView(this);
        q.setImageBitmap(qr);
        tile.addView(q, new FrameLayout.LayoutParams(px(150), px(150), Gravity.CENTER));
        return tile;
    }

    private static String qrEscape(String s) {
        return s.replaceAll("([\\\\;,:\"])", "\\\\$1");
    }

    // ---- pieces --------------------------------------------------------------------------------------------

    private ImageView wall() {
        ImageView w = new ImageView(this);
        w.setScaleType(ImageView.ScaleType.CENTER_CROP);
        w.setAlpha(0f);
        return w;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private TextView eyebrow(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.getDefault()), 16, 0xCCFFFFFF, R.string.font_medium);
        t.setLetterSpacing(0.14f);
        return t;
    }

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
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
