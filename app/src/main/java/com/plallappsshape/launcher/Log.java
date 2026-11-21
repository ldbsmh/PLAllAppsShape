package com.plallappsshape.launcher;

import de.robv.android.xposed.XposedBridge;

/**
 * Logger: Xposed log always, logcat when verbose.
 *
 * <p>{@link #i} de-duplicates identical messages, because a hook that fires per icon can otherwise
 * flood the Xposed log (a few thousand identical lines from the launcher's main thread is a stall).
 */
final class Log {
    private static final String TAG = "PLAllAppsShape";
    static volatile boolean verbose;

    private static String sLastInfo;
    private static long sLastInfoAt;
    private static int sSuppressed;

    private Log() {
    }

    static void d(String msg) {
        if (verbose) {
            android.util.Log.d(TAG, msg);
        }
    }

    static synchronized void i(String msg) {
        long now = System.currentTimeMillis();
        if (msg.equals(sLastInfo) && now - sLastInfoAt < 3_000L) {
            sSuppressed++;
            return;
        }
        if (sSuppressed > 0) {
            android.util.Log.i(TAG, "(+" + sSuppressed + " identical)");
            sSuppressed = 0;
        }
        sLastInfo = msg;
        sLastInfoAt = now;
        android.util.Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        XposedBridge.log("[" + TAG + "] ERROR " + msg
                + (t == null ? "" : ": " + android.util.Log.getStackTraceString(t)));
    }
}
