package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

/**
 * Puts the TV in standby for Control Center (this fork). An app cannot press the power key, but an accessibility
 * service can lock the screen, which a TV treats as standby. Unlike InputKeyService it filters no keys and listens
 * to no events, so held volume keeps repeating with it on.
 * Enable: settings put secure enabled_accessibility_services com.gathin.tvhome/.SleepService
 */
public class SleepService extends AccessibilityService {
    private static SleepService running;

    @Override
    protected void onServiceConnected() {
        running = this;
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("night", false)) showNight(true);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        showNight(false);
        wake();
        running = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    static boolean available() {
        return running != null;
    }

    /** Standby now; false when the service is not enabled. */
    static boolean sleep() {
        SleepService s = running;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    // ---- Night Shift and Screen off (overlays over every app, Live TV and HDMI included) --------------------

    /** Night Shift's amber, about what tvOS / iOS shift towards at their default warmth. */
    private static final int NIGHT = 0x33FF8A1E;
    private View night, blackout;
    private int savedBrightness = -1;

    private WindowManager.LayoutParams overlay(boolean focusable) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
                PixelFormat.TRANSLUCENT);
        return lp;
    }

    private void showNight(boolean on) {
        WindowManager wm = getSystemService(WindowManager.class);
        if (on && night == null) {
            night = new View(this);
            night.setBackgroundColor(NIGHT);
            night.setAlpha(0f);
            wm.addView(night, overlay(false));
            night.animate().alpha(1f).setDuration(600).start();
        } else if (!on && night != null) {
            View v = night;
            night = null;
            v.animate().alpha(0f).setDuration(400).withEndAction(() -> wm.removeView(v)).start();
        }
    }

    /** Night Shift on or off everywhere; false when the service is not enabled. */
    static boolean setNightShift(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("night", on).apply();
        SleepService s = running;
        if (s == null) return false;
        s.showNight(on);
        return true;
    }

    static boolean nightShift(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("night", false);
    }

    /**
     * Screen off, sound on: the backlight to its lowest and the picture black, while music keeps playing. Any
     * button brings it back, except volume, which still changes the volume.
     */
    static boolean screenOff(Context ctx) {
        SleepService s = running;
        if (s == null) return false;
        s.blackout();
        return true;
    }

    private void blackout() {
        if (blackout != null) return;
        ContentResolver cr = getContentResolver();
        try {
            savedBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0);
        } catch (Exception noWrite) {
            savedBrightness = -1;
        }
        FrameLayout v = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                        || k == KeyEvent.KEYCODE_VOLUME_MUTE || KeyEvent.isMediaSessionKey(k)) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) wake();
                return true;
            }
        };
        v.setBackgroundColor(0xFF000000);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        blackout = v;
        getSystemService(WindowManager.class).addView(v, overlay(true));
        v.requestFocus();
    }

    private void wake() {
        if (blackout == null) return;
        getSystemService(WindowManager.class).removeView(blackout);
        blackout = null;
        if (savedBrightness >= 0) {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, savedBrightness);
            } catch (Exception ignored) {
            }
        }
    }

    // ---- sleep timer ---------------------------------------------------------------------------------

    private static final String PREFS = "sleep_timer";

    /** Minutes 0 cancels. The alarm survives Home being closed or the launcher process being killed. */
    static void setTimer(Context ctx, int minutes) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, Fire.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        long at = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        if (minutes > 0) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("at", at).apply();
    }

    /** Minutes left, rounded up; 0 when no timer is set. */
    static int minutesLeft(Context ctx) {
        long at = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getLong("at", 0);
        long left = at - System.currentTimeMillis();
        return left > 0 ? (int) ((left + 59_999) / 60_000) : 0;
    }

    public static class Fire extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("at", 0).apply();
            sleep();
        }
    }
}
