package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.WindowManager;

/**
 * StandBy as the TV's screensaver (this fork):
 * settings put secure screensaver_components com.gathin.tvhome/.StandByDream
 * It opens on the face last chosen in StandByActivity, or on Now Playing with lyrics when music is playing, and
 * switches to it if music starts later. While music plays it keeps the TV awake, so a playlist never ends in
 * standby; once the music stops, the TV's own sleep timer applies again.
 */
public class StandByDream extends DreamService implements MediaWatch.Listener {
    private StandByView view;
    private MediaWatch media;
    private boolean wasPlaying;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive, so the Now Playing buttons work while it runs as the screensaver; other keys end it.
        setInteractive(true);
        setFullscreen(true);
        media = new MediaWatch(this);
        view = new StandByView(this, getSharedPreferences("standby", MODE_PRIVATE).getInt("face", 0), media);
        // The weather outside drawn over it (WeatherFx), and the whole face drifting a little (burn-in).
        android.widget.FrameLayout stack = new android.widget.FrameLayout(this);
        stack.setBackgroundColor(android.graphics.Color.BLACK);
        stack.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        stack.addView(new WeatherFx(this), new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(stack);
    }

    /** Burn-in: every 90 seconds the face glides to a new spot within 18 dp of the centre, over 20 seconds. */
    private final android.os.Handler drift = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.Random random = new java.util.Random();
    private final Runnable driftStep = new Runnable() {
        @Override
        public void run() {
            if (view == null) return;
            float r = 18 * getResources().getDisplayMetrics().density;
            view.animate().translationX((random.nextFloat() * 2 - 1) * r).translationY((random.nextFloat() * 2 - 1) * r)
                    .setDuration(20_000).setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
            drift.postDelayed(this, 90_000);
        }
    };

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        media.addListener(this);
        media.start();
        drift.postDelayed(driftStep, 30_000);
    }

    @Override
    public void onDreamingStopped() {
        drift.removeCallbacks(driftStep);
        media.removeListener(this);
        media.stop();
        super.onDreamingStopped();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        if (k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || k == android.view.KeyEvent.KEYCODE_VOLUME_MUTE || android.view.KeyEvent.isMediaSessionKey(k)) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
        if (view.onFaceKey(k)) return true;
        // CH+ / CH- change the face from anywhere, the lyrics face included, as they change the wallpaper on Home.
        if (k == android.view.KeyEvent.KEYCODE_CHANNEL_UP || k == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN
                || k == android.view.KeyEvent.KEYCODE_PAGE_UP || k == android.view.KeyEvent.KEYCODE_PAGE_DOWN) {
            view.step(k == android.view.KeyEvent.KEYCODE_CHANNEL_UP || k == android.view.KeyEvent.KEYCODE_PAGE_UP ? 1 : -1);
            getSharedPreferences("standby", MODE_PRIVATE).edit().putInt("face", view.face()).apply();
            return true;
        }
        if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            view.step(k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        finish();
        return true;
    }

    @Override
    public void onMedia(MediaWatch.Track t) {
        boolean playing = t != null && t.playing;
        if (playing && !wasPlaying) view.showFace(StandByView.FACE_NOW_PLAYING);
        wasPlaying = playing;
        if (getWindow() == null) return;
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
