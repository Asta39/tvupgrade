package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * StandBy as the TV's screensaver (this fork): pick "Home" StandBy in Settings > Screen saver, or
 * settings put secure screensaver_components com.gathin.tvhome/.StandByDream
 * It shows the face last chosen in StandByActivity; any button wakes the TV as with every screensaver.
 */
public class StandByDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(new StandByView(this, getSharedPreferences("standby", MODE_PRIVATE).getInt("face", 0)));
    }
}
