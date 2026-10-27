package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * StandBy on demand, from Control Center (this fork). Left/Right change the face, which is remembered; volume and the
 * remote's media keys (play, pause, next) work as usual; any other button returns to Home.
 */
public class StandByActivity extends Activity {
    private StandByView view;
    private MediaWatch media;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        media = new MediaWatch(this);
        view = new StandByView(this, getSharedPreferences("standby", MODE_PRIVATE).getInt("face", 0), media);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        media.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        media.stop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE
                || KeyEvent.isMediaSessionKey(k)) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            view.step(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            getSharedPreferences("standby", MODE_PRIVATE).edit().putInt("face", view.face()).apply();
            return true;
        }
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        return true;
    }
}
