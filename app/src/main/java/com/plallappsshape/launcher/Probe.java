package com.plallappsshape.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Records what the launcher side managed to do, so the settings screen can show it without logcat.
 *
 * <p>Writes are throttled and done with {@code apply()} (async). The first version used
 * {@code commit()} on every icon state parse, which hammered the disk from the launcher's main
 * thread - a burst of thousands of synchronous writes is an easy ANR.
 */
public final class Probe {

    /** Shared with the settings UI; separate from the module prefs so a clear is harmless. */
    public static final String QA_PREFS = "plallappsshape_qa";
    public static final String KEY_STATE = "state";
    public static final String KEY_LAST = "last";

    /** Values are only interesting when they change, and only once per this interval. */
    private static final long MIN_INTERVAL_MS = 5_000L;

    private static String sState;
    private static String sLast;
    private static long sLastWrite;

    private Probe() {
    }

    public static void state(Context ctx, String message) {
        Log.i(message);
        write(ctx, KEY_STATE, message);
    }

    public static void last(Context ctx, String message) {
        Log.d(message);
        write(ctx, KEY_LAST, message);
    }

    private static synchronized void write(Context ctx, String key, String value) {
        if (KEY_STATE.equals(key)) {
            if (value.equals(sState)) {
                return;
            }
            sState = value;
        } else {
            if (value.equals(sLast)) {
                return;
            }
            sLast = value;
        }
        long now = System.currentTimeMillis();
        if (now - sLastWrite < MIN_INTERVAL_MS) {
            return;
        }
        sLastWrite = now;
        try {
            SharedPreferences p = ctx.getSharedPreferences(QA_PREFS, Context.MODE_PRIVATE);
            p.edit().putString(key, value).apply();
        } catch (Throwable t) {
            Log.e("probe write", t);
        }
    }
}
