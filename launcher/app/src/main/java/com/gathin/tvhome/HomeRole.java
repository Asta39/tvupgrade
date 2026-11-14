package com.gathin.tvhome;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/**
 * Making this launcher the Home app with no computer and no debugging. Ported from couch-tv's HomeRole.kt
 * (github.com/Seagey/couch-tv, MIT License, Copyright (c) 2026 Seagey).
 * <p>
 * On a plain Android TV 10 or newer, Android has its own "default home app" request: it shows the system's chooser
 * and the person picks this app with the remote. Older Androids get the Home settings screen if there is one, else
 * Android's "Select a home app" chooser. On Google TV the Google TV home can still win the Home key, so there setup
 * uses debugging instead (Setup.grant, and HomeKeyService's redirect). It never switches off the TV's own home.
 */
final class HomeRole {
    static final int REQUEST = 42;
    private static final String GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx";

    private HomeRole() {
    }

    /** Google TV (the Google TV home is installed). Plain Android TV and the makers' own homes are not. */
    static boolean googleTv(Context c) {
        try {
            c.getPackageManager().getPackageInfo(GOOGLE_TV_LAUNCHER, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the Home key opens this launcher right now. */
    static boolean held(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = c.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        ResolveInfo ri = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Opens Android's own chooser. Returns false when this TV offers none. */
    static boolean request(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = a.getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback(a);
    }

    private static boolean fallback(Activity a) {
        try {
            Intent settings = new Intent(Settings.ACTION_HOME_SETTINGS);
            if (settings.resolveActivity(a.getPackageManager()) != null) {
                a.startActivity(settings);
                return true;
            }
            // No Home settings screen: a Home intent with no default set makes Android ask "Select a home app".
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            a.startActivity(Intent.createChooser(home, null));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
