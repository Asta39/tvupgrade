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
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        media.addListener(this);
        media.start();
    }

    @Override
    public void onDreamingStopped() {
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
