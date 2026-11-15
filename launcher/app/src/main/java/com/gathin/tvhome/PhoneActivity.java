package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
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

/**
 * Phone remote (this fork, Home mode): a QR code that opens the TV's phone page. Before anyone has claimed the TV
 * it carries the claim code, so the first phone to scan it sets the PIN and becomes the host; after that it is the
 * plain page, where everyone on the Wi-Fi gets the remote, Watch, Watch together and Music. Opened from Control
 * Center; the end of Home setup shows the same card.
 */
public class PhoneActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private boolean claimed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        HostServer.start(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0F);
        setContentView(root);
        show();
    }

    private void show() {
        root.removeAllViews();
        claimed = HostServer.claimCode == null;
        root.addView(card(this, 260), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    /** Rebuilds once the TV is claimed, so the code makes way for the page everyone else scans. */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!claimed && HostServer.claimCode == null) show();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        main.post(watch);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(watch);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** The QR code with what to do, for this screen and the last step of setup. */
    static View card(Activity a, int qrDp) {
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String url = HostServer.url(a), code = HostServer.claimCode;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        if (url != null) {
            Bitmap bmp = HostQr.bitmap(code != null ? url + "#claim=" + code : url, Math.round(qrDp * dp));
            if (bmp != null) {
                ImageView q = new ImageView(a);
                q.setImageBitmap(bmp);
                row.addView(q, new LinearLayout.LayoutParams(Math.round(qrDp * dp), Math.round(qrDp * dp)));
                col.setPadding(Math.round(48 * dp), 0, 0, 0);
            }
        }
        col.addView(text(a, "YOUR PHONE IS THE REMOTE", 15, 0xFF7FD8FF, R.string.font_medium, 0.12f));
        col.addView(text(a, url == null ? "Connect the TV to Wi-Fi first" : code != null ? "Scan to set it up" : "Scan to join",
                40, Color.WHITE, R.string.font_medium, 0f), top(a, 8));
        String body = url == null ? "The phone page needs the TV on your home Wi-Fi."
                : code != null
                ? "On the same Wi-Fi as the TV, point your phone's camera at the code. The first phone picks a PIN and "
                + "becomes the host. Everyone else just scans to get the remote, Watch, Watch together and Music."
                : "Anyone on the same Wi-Fi as the TV can scan this for the remote, the phone keyboard, Watch, Watch "
                + "together and Music. Or open " + url.replace("http://", "").replaceAll("/$", "") + " in the browser.";
        TextView b = text(a, body, 18, 0xCCFFFFFF, R.string.font_regular, 0f);
        b.setLineSpacing(0, 1.2f);
        col.addView(b, top(a, 14));
        if (code != null) col.addView(text(a, "Setup code  " + code, 20, 0xFFFFD27A, R.string.font_medium, 0.06f), top(a, 18));
        row.addView(col, new LinearLayout.LayoutParams(Math.round(520 * dp), -2));
        return row;
    }

    private static LinearLayout.LayoutParams top(Activity a, int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.topMargin = Math.round(topDp * a.getResources().getDisplayMetrics().density);
        return l;
    }

    private static TextView text(Activity a, String s, int sp, int color, int font, float spacing) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        Typeface face = UiFont.get(a, font);
        if (face != null) t.setTypeface(face);
        return t;
    }
}
