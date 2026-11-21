package com.plallappsshape.launcher;

import android.content.Context;
import android.content.res.Resources;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Launcher-side glue: installs the hooks and implements {@link ShapeSync.Platform}.
 *
 * <p>See {@link ShapeSync} for the decompiled pipeline. Two hooks do the work:
 * <ul>
 *   <li>{@code BitmapInfo}'s constructors: replace {@code defaultIconShape} with the workspace
 *       shape. Workspace icons pass their own shape to {@code newIcon()}, so they are unaffected;</li>
 *   <li>{@code Resources.getString(int)} for {@code config_icon_mask}: so the launcher's static
 *       {@code defaultIconShapeCache} (weak references) rebuilds with the same path.</li>
 * </ul>
 * {@code ThemeManager.parseIconState} is only <i>observed</i>, to snapshot the current shape.
 *
 * <p><b>Re-entrancy:</b> the {@code parseIconState} hook only records the result into
 * {@link #mState}; it never calls back into the launcher. The first attempt called
 * {@code parseIconState()} from inside its own hook, which recursed until the launcher died.
 */
final class LauncherHooks implements ShapeSync.Platform {

    private static final String L3 = "com.android.launcher3.";

    private static volatile LauncherHooks sInstance;

    private final Context mContext;
    private final ClassLoader mCl;
    private final ShapeSync mSync = new ShapeSync();

    /** Latest snapshot from the launcher: written by the hook, read by the module. */
    private volatile ShapeSync.Pending mState;

    private Object mThemeManager;
    private Field mIconShapeDataField;
    private Method mDispatchValue;

    private Field mDefaultIconShapeField;

    private volatile String mOverridePath;
    private volatile boolean mOverrideActive;
    private volatile boolean mForceOverride;

    /** Throttle for the redraw request, so a burst of parseIconState calls cannot flood it. */
    private volatile long mLastDispatch;

    private LauncherHooks(Context context, ClassLoader cl) {
        mContext = context;
        mCl = cl;
    }

    static void install(Context context, ClassLoader cl) {
        if (sInstance != null) {
            return;
        }
        LauncherHooks hooks = new LauncherHooks(context, cl);
        sInstance = hooks;
        try {
            hooks.hookThemeManager();
            hooks.hookBitmapInfo();
            Probe.state(context, "hook 已安装（" + (hooks.mIconShapeDataField != null ? "形状" : "缺形状")
                    + "/" + (hooks.mDefaultIconShapeField != null ? "图标" : "缺图标") + "）");
            Log.i("hooks installed");
        } catch (Throwable t) {
            Log.e("install", t);
            sInstance = null;
        }
    }

    /** Called when the module settings change (enable / disable / log). */
    static void requestRefresh() {
        LauncherHooks hooks = sInstance;
        if (hooks == null) {
            return;
        }
        boolean enabled = HookEntry.enabled();
        hooks.mForceOverride = !enabled && hooks.mOverrideActive;
        Log.i("settings changed, enabled=" + enabled);
        if (!enabled) {
            // Release the mask override so the launcher returns to its own shape.
            hooks.mSync.applySnapshot(hooks, null);
            hooks.dispatchIconShape(true);
        }
    }

    // ---------------------------------------------------------------- hooks

    private void hookThemeManager() {
        Class<?> themeManager = XposedHelpers.findClass(L3 + "graphics.ThemeManager", mCl);
        Class<?> iconState = XposedHelpers.findClass(L3 + "graphics.ThemeManager$IconState", mCl);
        mIconShapeDataField = ShapeSync.field(themeManager, "iconShapeData");
        try {
            Class<?> refClass = XposedHelpers.findClass(L3 + "util.MutableListenableRef", mCl);
            mDispatchValue = ShapeSync.method(refClass, "dispatchValue", 1);
        } catch (Throwable t) {
            Log.e("MutableListenableRef", t);
        }
        for (Method m : themeManager.getDeclaredMethods()) {
            if (!"parseIconState".equals(m.getName()) || m.getParameterCount() != 1) {
                continue;
            }
            if (!m.getParameterTypes()[0].isAssignableFrom(iconState)) {
                continue;
            }
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Record only. Never call back into the launcher from here.
                    onIconState(param.thisObject, param.getResult());
                }
            });
            Log.i("ThemeManager.parseIconState hooked");
            return;
        }
        Log.e("parseIconState not found", null);
    }

    /** Stores the launcher's freshly parsed state and reacts only when the shape really changed. */
    private void onIconState(Object themeManager, Object state) {
        try {
            if (themeManager != null) {
                mThemeManager = themeManager;
            }
            if (state == null) {
                return;
            }
            Field maskField = ShapeSync.field(state.getClass(), "iconMask");
            Field shapeField = ShapeSync.field(state.getClass(), "iconShape");
            Object mask = maskField == null ? null : maskField.get(state);
            Object shape = shapeField == null ? null : shapeField.get(state);
            ShapeSync.Pending pending =
                    new ShapeSync.Pending((String) mask, pathOf(shape), shape);
            mState = pending;

            if (!HookEntry.enabled()) {
                return;
            }
            if (mSync.applySnapshot(this, pending)) {
                Probe.last(mContext, "桌面形状：" + ShapeSync.abbreviate(mSync.lastPath()));
                dispatchIconShape(false);
            }
        } catch (Throwable t) {
            Log.e("onIconState", t);
        }
    }

    private void hookBitmapInfo() {
        Class<?> bitmapInfo = XposedHelpers.findClass(L3 + "icons.BitmapInfo", mCl);
        mDefaultIconShapeField = ShapeSync.field(bitmapInfo, "defaultIconShape");
        if (mDefaultIconShapeField == null) {
            Log.e("BitmapInfo.defaultIconShape not found", null);
        }
        int hooked = 0;
        for (Constructor<?> ctor : bitmapInfo.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!HookEntry.enabled()) {
                            return;
                        }
                        mSync.applyToBitmap(LauncherHooks.this, param.thisObject);
                    } catch (Throwable t) {
                        Log.e("patch BitmapInfo", t);
                    }
                }
            });
            hooked++;
        }
        Log.i("BitmapInfo constructors hooked: " + hooked);
    }

    /**
     * Asks the launcher to re-apply the icon shape to its icons.
     *
     * @param force bypass the throttle (used when disabling the module)
     */
    private void dispatchIconShape(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - mLastDispatch < 1000L) {
            return;
        }
        mLastDispatch = now;
        try {
            if (mThemeManager == null || mIconShapeDataField == null || mDispatchValue == null) {
                return;
            }
            Object ref = mIconShapeDataField.get(mThemeManager);
            if (ref == null) {
                return;
            }
            Object current = XposedHelpers.callMethod(ref, "getValue");
            if (current != null) {
                mDispatchValue.invoke(ref, current);
                Log.i("icon shape redraw requested");
            }
        } catch (Throwable t) {
            Log.e("dispatch", t);
        }
    }

    // ---------------------------------------------------------------- Platform

    @Override
    public ShapeSync.Pending parseIconState() {
        return mState;
    }

    /** The SVG path behind a ShapeDelegate, or "" when it is a built-in shape. */
    private String pathOf(Object shape) {
        if (shape == null) {
            return "";
        }
        try {
            Class<?> generic = XposedHelpers.findClass(
                    L3 + "graphics.ShapeDelegate$GenericPathShape", mCl);
            if (!generic.isInstance(shape)) {
                return "";
            }
            Field f = ShapeSync.field(generic, "pathString");
            Object v = f == null ? null : f.get(shape);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Patches {@code Resources.getSystem().getString(config_icon_mask)} to the desktop path so the
     * launcher's static shape cache regenerates with it (its entries are weak references).
     */
    @Override
    public boolean setOverride(String svg) {
        if (svg == null) {
            mOverridePath = null;
            mOverrideActive = false;
            return false;
        }
        mOverridePath = svg;
        if (mOverrideActive) {
            return true;
        }
        try {
            int maskId = Resources.getSystem().getIdentifier("config_icon_mask", "string", "android");
            Method getString = ShapeSync.method(Resources.class, "getString", 1);
            if (maskId == 0 || getString == null) {
                Log.e("config_icon_mask unavailable", null);
                return false;
            }
            final int id = maskId;
            XposedBridge.hookMethod(getString, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] instanceof Integer && ((Integer) param.args[0]) == id) {
                        String path = mOverridePath;
                        if (path != null) {
                            param.setResult(path);
                        }
                    }
                }
            });
            mOverrideActive = true;
            Log.i("config_icon_mask overridden");
            return true;
        } catch (Throwable t) {
            Log.e("patch config_icon_mask", t);
            return false;
        }
    }

    @Override
    public boolean forceOverride() {
        boolean force = mForceOverride;
        mForceOverride = false;
        return force;
    }

    @Override
    public Object getField(Object target, String name) {
        try {
            Field f = mDefaultIconShapeField != null && "defaultIconShape".equals(name)
                    ? mDefaultIconShapeField : ShapeSync.field(target.getClass(), name);
            return f == null ? null : f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void setField(Object target, String name, Object value) {
        try {
            Field f = mDefaultIconShapeField != null && "defaultIconShape".equals(name)
                    ? mDefaultIconShapeField : ShapeSync.field(target.getClass(), name);
            if (f != null) {
                f.set(target, value);
            }
        } catch (Throwable t) {
            Log.e("setField " + name, t);
        }
    }
}
