package com.plallappsshape.launcher;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point (META-INF/xposed/java_init.list). Only the launcher process is hooked.
 *
 * <p>There is no settings screen: the module does one thing, so it is either enabled in the
 * Xposed manager or it is not. Nothing to configure, nothing to store.
 */
public class HookEntry implements de.robv.android.xposed.IXposedHookLoadPackage,
        de.robv.android.xposed.IXposedHookZygoteInit {

    static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";

    private static volatile boolean sReady;

    @Override
    public void initZygote(StartupParam startupParam) {
        Log.i("module loaded");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.classLoader == null || !LAUNCHER_PKG.equals(lpparam.packageName)) {
            return;
        }
        Log.i("loaded into " + lpparam.processName);
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sReady) {
                        return;
                    }
                    sReady = true;
                    LauncherHooks.install((Context) param.thisObject, lpparam.classLoader);
                }
            });
        } catch (Throwable t) {
            Log.e("entry hook", t);
        }
    }
}
