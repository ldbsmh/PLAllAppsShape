package com.plallappsshape.launcher;

import de.robv.android.xposed.XposedBridge;

/**
 * Logging policy: errors only.
 *
 * <p>This module has no settings screen, so there is nothing to toggle at runtime. Set
 * {@link #VERBOSE} to true and rebuild when debugging; the verbose lines (shape paths, build
 * counts, rebind steps) then go to logcat and the Xposed log as well.
 */
final class Log {
    private static final String TAG = "PLAllAppsShape";

    /** Flip to true for a diagnostic build. Errors are logged either way. */
    static final boolean VERBOSE = false;

    private Log() {
    }

    static void d(String msg) {
        if (VERBOSE) {
            android.util.Log.d(TAG, msg);
        }
    }

    static void i(String msg) {
        if (VERBOSE) {
            android.util.Log.i(TAG, msg);
            XposedBridge.log("[" + TAG + "] " + msg);
        }
    }

    static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        XposedBridge.log("[" + TAG + "] ERROR " + msg
                + (t == null ? "" : ": " + android.util.Log.getStackTraceString(t)));
    }
}
