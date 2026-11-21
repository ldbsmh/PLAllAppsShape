package com.plallappsshape.launcher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point (META-INF/xposed/java_init.list). Only the launcher process is hooked.
 *
 * <p>Preferences live in the launcher's own storage and are written by
 * {@code com.plallappsshape.settings.SettingsActivity}, which LSPosed also loads into the launcher
 * process, so both sides see the same file.
 */
public class HookEntry implements de.robv.android.xposed.IXposedHookLoadPackage,
        de.robv.android.xposed.IXposedHookZygoteInit {

    static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    static final String PREFS = "plallappsshape";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_LOG = "verbose_log";
    static final String ACTION_RELOAD = "com.plallappsshape.RELOAD";

    private static volatile boolean sReady;
    private static volatile SharedPreferences sPrefs;

    @Override
    public void initZygote(StartupParam startupParam) {
        Log.i("module loaded: " + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.classLoader == null || !LAUNCHER_PKG.equals(lpparam.packageName)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sReady) {
                        return;
                    }
                    sReady = true;
                    Context ctx = (Context) param.thisObject;
                    initPrefs(ctx);
                    LauncherHooks.install(ctx, lpparam.classLoader);
                }
            });
        } catch (Throwable t) {
            Log.e("entry hook", t);
        }
    }

    static boolean enabled() {
        SharedPreferences p = sPrefs;
        return p == null || p.getBoolean(KEY_ENABLED, true);
    }

    private static void initPrefs(Context ctx) {
        try {
            sPrefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Log.verbose = sPrefs.getBoolean(KEY_LOG, false);
            sPrefs.registerOnSharedPreferenceChangeListener(
                    new SharedPreferences.OnSharedPreferenceChangeListener() {
                        @Override
                        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                            Log.verbose = prefs.getBoolean(KEY_LOG, false);
                            LauncherHooks.requestRefresh();
                        }
                    });
            // SettingsActivity can also poke the launcher, e.g. to force an icon redraw.
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    LauncherHooks.requestRefresh();
                }
            }, new IntentFilter(ACTION_RELOAD), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.e("init prefs", t);
        }
    }
}
