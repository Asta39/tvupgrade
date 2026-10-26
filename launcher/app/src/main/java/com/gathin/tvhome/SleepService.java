package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

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
    }

    @Override
    public boolean onUnbind(Intent intent) {
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
