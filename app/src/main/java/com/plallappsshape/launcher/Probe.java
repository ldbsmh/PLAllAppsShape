package com.plallappsshape.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Records what the launcher side managed to do. The settings screen shows it, which answers
 * "did the module get loaded into the launcher at all?" without needing logcat.
 */
public final class Probe {

    /** Shared with the settings UI; separate from the module prefs so a clear is harmless. */
    public static final String QA_PREFS = "plallappsshape_qa";
    public static final String KEY_STATE = "state";
    public static final String KEY_LAST = "last";

    private Probe() {
    }

    public static void state(Context ctx, String message) {
        write(ctx, KEY_STATE, message);
        Log.i(message);
    }

    public static void last(Context ctx, String message) {
        write(ctx, KEY_LAST, message);
    }

    private static void write(Context ctx, String key, String value) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(QA_PREFS, Context.MODE_PRIVATE);
            p.edit().putString(key, value).commit();
        } catch (Throwable t) {
            Log.e("probe write", t);
        }
    }
}
