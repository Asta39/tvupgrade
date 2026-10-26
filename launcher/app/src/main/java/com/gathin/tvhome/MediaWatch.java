package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * The app playing music or video right now, for this fork's Now Playing card and Control Center. Reads the system's
 * media sessions, which needs the launcher's notification listener (GuardRevival) to be allowed:
 * cmd notification allow_listener com.gathin.tvhome/.GuardRevival
 * Only a session with a title counts, so a paused-and-forgotten app with an empty session never shows a card.
 */
final class MediaWatch {
    interface Listener {
        /** Called on the UI thread with the current track, or null when nothing is playing or paused. */
        void onMedia(Track track);
    }

    static final class Track {
        final String title, artist, pkg;
        final Bitmap art;
        final boolean playing;
        final long duration, position, at;
        final float speed;
        final MediaController controller;

        Track(MediaController c, MediaMetadata m, PlaybackState s) {
            controller = c;
            pkg = c.getPackageName();
            title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            art = b;
            duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            position = s != null ? Math.max(0, s.getPosition()) : 0;
            at = s != null ? s.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
            speed = s != null ? s.getPlaybackSpeed() : 0f;
        }

        /** The position now, moved on from the last report while playing. */
        long positionNow() {
            if (!playing || at <= 0) return position;
            long p = position + (long) ((SystemClock.elapsedRealtime() - at) * speed);
            return duration > 0 ? Math.min(p, duration) : p;
        }

        private static String text(MediaMetadata m, String a, String b) {
            CharSequence s = m.getText(a);
            if (s == null || s.length() == 0) s = m.getText(b);
            return s == null ? "" : s.toString();
        }
    }

    private final Context ctx;
    private final MediaSessionManager msm;
    private final ComponentName listenerComponent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private MediaController current;
    private Track track;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> pick(list);
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };

    MediaWatch(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        msm = this.ctx.getSystemService(MediaSessionManager.class);
        listenerComponent = new ComponentName(this.ctx, GuardRevival.class);
    }

    void addListener(Listener l) {
        listeners.add(l);
        l.onMedia(track);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    Track track() {
        return track;
    }

    /** Home is in front: follow the sessions. Fails quietly (no card) when the listener is not allowed. */
    void start() {
        if (started) return;
        try {
            msm.addOnActiveSessionsChangedListener(sessions, listenerComponent, main);
            started = true;
            refresh();
        } catch (SecurityException notAllowed) {
            set(null);
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        msm.removeOnActiveSessionsChangedListener(sessions);
        if (current != null) current.unregisterCallback(callback);
        current = null;
    }

    private void refresh() {
        try {
            pick(msm.getActiveSessions(listenerComponent));
        } catch (SecurityException notAllowed) {
            set(null);
        }
    }

    /** Prefer a session that is playing; else the first one with a title (paused music still shows). */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                if (c.getMetadata() == null || c.getPackageName().equals(ctx.getPackageName())) continue;
                CharSequence t = c.getMetadata().getText(MediaMetadata.METADATA_KEY_TITLE);
                if (t == null || t.length() == 0) continue;
                PlaybackState s = c.getPlaybackState();
                boolean live = s != null && (s.getState() == PlaybackState.STATE_PLAYING
                        || s.getState() == PlaybackState.STATE_PAUSED || s.getState() == PlaybackState.STATE_BUFFERING);
                if (!live) continue;
                if (best == null || s.getState() == PlaybackState.STATE_PLAYING) best = c;
                if (s.getState() == PlaybackState.STATE_PLAYING) break;
            }
        }
        if (current != null && (best == null || !current.getSessionToken().equals(best.getSessionToken()))) {
            current.unregisterCallback(callback);
            current = null;
        }
        if (best != null && current == null) {
            current = best;
            current.registerCallback(callback, main);
        }
        publish();
    }

    private void publish() {
        MediaController c = current;
        if (c == null || c.getMetadata() == null) {
            set(null);
            return;
        }
        PlaybackState s = c.getPlaybackState();
        int st = s != null ? s.getState() : PlaybackState.STATE_NONE;
        if (st == PlaybackState.STATE_STOPPED || st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_ERROR) {
            set(null);
            return;
        }
        set(new Track(c, c.getMetadata(), s));
    }

    private void set(Track t) {
        track = t;
        for (Listener l : new ArrayList<>(listeners)) l.onMedia(t);
    }

    // ---- transport --------------------------------------------------------------------------------------

    static void playPause(Track t) {
        if (t == null) return;
        if (t.playing) t.controller.getTransportControls().pause();
        else t.controller.getTransportControls().play();
    }

    static void next(Track t) {
        if (t != null) t.controller.getTransportControls().skipToNext();
    }

    static void previous(Track t) {
        if (t != null) t.controller.getTransportControls().skipToPrevious();
    }
}
