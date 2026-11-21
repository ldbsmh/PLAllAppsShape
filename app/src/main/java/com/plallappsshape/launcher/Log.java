package com.plallappsshape.launcher;

import de.robv.android.xposed.XposedBridge;

/** Logger: Xposed log always, logcat when verbose. */
final class Log {
    private static final String TAG = "PLAllAppsShape";
    static volatile boolean verbose;

    private Log() {
    }

    static void d(String msg) {
        if (verbose) {
            android.util.Log.d(TAG, msg);
        }
    }

    static void i(String msg) {
        android.util.Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        XposedBridge.log("[" + TAG + "] ERROR " + msg
                + (t == null ? "" : ": " + android.util.Log.getStackTraceString(t)));
    }
}
