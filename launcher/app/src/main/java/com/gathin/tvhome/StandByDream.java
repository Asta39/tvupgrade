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

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
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
    public void onMedia(MediaWatch.Track t) {
        boolean playing = t != null && t.playing;
        if (playing) view.showFace(StandByView.FACE_NOW_PLAYING);
        if (getWindow() == null) return;
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
