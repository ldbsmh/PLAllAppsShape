package com.plallappsshape.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Path;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Launcher-side glue: installs the hooks and implements {@link ShapeSync.Platform}. */
final class LauncherHooks implements ShapeSync.Platform {

    private static final String L3 = "com.android.launcher3.";
    private static final int ICON_SIZE_PX = 192;

    private static volatile LauncherHooks sInstance;

    private final Context mContext;
    private final ClassLoader mCl;
    private final ShapeSync mSync;

    private Object mThemeManager;
    private Method mParseIconState;
    private Field mIconShapeDataField;
    private Method mDispatchValue;

    private Class<?> mGenericShapeClass;
    private Constructor<?> mGenericShapeCtor;
    private Method mAddToPath;
    private Method mGenerateIconShape;

    private Field mDefaultIconShapeField;
    private Object mIconProvider;

    private volatile String mOverridePath;
    private volatile boolean mOverrideActive;
    private volatile boolean mForceOverride;

    private LauncherHooks(Context context, ClassLoader cl, ShapeSync sync) {
        mContext = context;
        mCl = cl;
        mSync = sync;
    }

    static void install(Context context, ClassLoader cl) {
        if (sInstance != null) {
            return;
        }
        LauncherHooks hooks = new LauncherHooks(context, cl, new ShapeSync());
        sInstance = hooks;
        try {
            hooks.hookThemeManager();
            hooks.hookBitmapInfo();
            Probe.state(context, "hook 已安装（" + (hooks.mParseIconState != null ? "形状" : "缺形状 hook")
                    + "/" + (hooks.mDefaultIconShapeField != null ? "图标" : "缺图标 hook") + "）");
            Log.i("hooks installed");
        } catch (Throwable t) {
            Log.e("install", t);
            sInstance = null;
        }
    }

    /** Called when prefs change (module settings toggled). */
    static void requestRefresh() {
        LauncherHooks hooks = sInstance;
        if (hooks == null) {
            return;
        }
        boolean enabled = HookEntry.enabled();
        hooks.mForceOverride = !enabled && hooks.mOverrideActive;
        Log.i("refresh, enabled=" + enabled);
        hooks.dispatchIconShape();
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
            mParseIconState = m;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    mThemeManager = param.thisObject;
                    onIconState(param.getResult());
                }
            });
            Log.i("ThemeManager.parseIconState hooked");
            break;
        }
        if (mParseIconState == null) {
            Log.e("parseIconState not found", null);
        }
    }

    /** Keeps the system mask in sync and lets icons redraw when the desktop shape changed. */
    private void onIconState(Object state) {
        try {
            if (state == null || !HookEntry.enabled()) {
                return;
            }
            Field maskField = ShapeSync.field(state.getClass(), "iconMask");
            Object mask = maskField == null ? null : maskField.get(state);
            boolean changed = mask instanceof String && !mask.equals(mSync.lastMask());
            mSync.resolve(this);
            Probe.last(mContext, "桌面形状：" + ShapeSync.abbreviate(mSync.lastMask()));
            if (changed) {
                dispatchIconShape();
            }
        } catch (Throwable t) {
            Log.e("onIconState", t);
        }
    }

    private void hookBitmapInfo() {
        Class<?> bitmapInfo = XposedHelpers.findClass(L3 + "icons.BitmapInfo", mCl);
        mDefaultIconShapeField = ShapeSync.field(bitmapInfo, "defaultIconShape");
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

    private void dispatchIconShape() {
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
    public String readShapeKey() {
        try {
            Class<?> themeManager = XposedHelpers.findClass(L3 + "graphics.ThemeManager", mCl);
            Object item = XposedHelpers.getStaticObjectField(themeManager, "PREF_ICON_SHAPE");
            Object prefs = launcherPrefs();
            if (prefs == null) {
                return null;
            }
            Object value = XposedHelpers.callMethod(prefs, "get", item);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            Log.d("readShapeKey: " + t);
            return null;
        }
    }

    private Object launcherPrefs() {
        try {
            Class<?> prefsClass = XposedHelpers.findClass(L3 + "LauncherPrefs", mCl);
            Field inst = ShapeSync.field(prefsClass, "INSTANCE");
            if (inst != null) {
                Object singleton = inst.get(null);
                if (singleton != null) {
                    return XposedHelpers.callMethod(singleton, "get");
                }
            }
            Constructor<?> ctor = ShapeSync.ctor(prefsClass, Context.class);
            return ctor == null ? null : ctor.newInstance(mContext);
        } catch (Throwable t) {
            Log.d("launcherPrefs: " + t);
            return null;
        }
    }

    @Override
    public ShapeSync.Pending parseIconState() {
        if (mThemeManager == null || mParseIconState == null) {
            return null;
        }
        try {
            Object state = mParseIconState.invoke(mThemeManager, (Object) null);
            if (state == null) {
                return null;
            }
            Field maskF = ShapeSync.field(state.getClass(), "iconMask");
            Field shapeF = ShapeSync.field(state.getClass(), "iconShape");
            String mask = (String) maskF.get(state);
            Object shape = shapeF.get(state);
            return new ShapeSync.Pending(mask, pathOf(shape), shape);
        } catch (Throwable t) {
            Log.d("parseIconState: " + t);
            return null;
        }
    }

    /** SVG string behind a ShapeDelegate, or "" for built-in shapes. */
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

    @Override
    public Object buildIconShape(String svg) {
        try {
            if (mGenericShapeCtor == null) {
                mGenericShapeClass = XposedHelpers.findClass(
                        L3 + "graphics.ShapeDelegate$GenericPathShape", mCl);
                mGenericShapeCtor = ShapeSync.ctor(mGenericShapeClass, String.class);
                mAddToPath = ShapeSync.method(mGenericShapeClass, "addToPath", 4);
            }
            if (mGenericShapeCtor == null) {
                return null;
            }
            Object delegate = mGenericShapeCtor.newInstance(svg);
            if (mGenerateIconShape == null) {
                Class<?> utils = XposedHelpers.findClass(L3 + "icons.GraphicsUtils", mCl);
                for (Method m : utils.getDeclaredMethods()) {
                    if ("generateIconShape".equals(m.getName()) && m.getParameterCount() == 2) {
                        m.setAccessible(true);
                        mGenerateIconShape = m;
                        break;
                    }
                }
            }
            if (mGenerateIconShape == null || mAddToPath == null) {
                Log.e("generateIconShape missing", null);
                return delegate;
            }
            Path path = new Path();
            mAddToPath.invoke(delegate, 0f, 0f, (float) ICON_SIZE_PX / 2f, path);
            return mGenerateIconShape.invoke(null, ICON_SIZE_PX, path);
        } catch (Throwable t) {
            Log.e("buildIconShape", t);
            return null;
        }
    }

    @Override
    public String systemString(String resourceName) {
        try {
            int id = Resources.getSystem().getIdentifier(resourceName, "string", "android");
            return id == 0 ? null : Resources.getSystem().getString(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Patches {@code Resources.getSystem().getString(config_icon_mask)} so the static
     * {@code defaultIconShapeCache} regenerates with the desktop path.
     */
    @Override
    public boolean setOverride(String svg) {
        if (svg == null) {
            if (!mOverrideActive) {
                return false;
            }
            mOverrideActive = false;
            mOverridePath = null;
            Log.i("mask override released");
            return false;
        }
        if (svg.equals(mOverridePath)) {
            mOverrideActive = true;
            return true;
        }
        mOverridePath = svg;
        if (!mOverrideActive) {
            try {
                int maskId = Resources.getSystem().getIdentifier("config_icon_mask", "string", "android");
                Method getString = null;
                for (Method m : Resources.class.getDeclaredMethods()) {
                    if ("getString".equals(m.getName()) && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == int.class) {
                        getString = m;
                        break;
                    }
                }
                if (maskId == 0 || getString == null) {
                    Log.e("config_icon_mask hook unavailable", null);
                    return false;
                }
                getString.setAccessible(true);
                final int id = maskId;
                XposedBridge.hookMethod(getString, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] instanceof Integer && ((Integer) param.args[0]) == id) {
                            String p = mOverridePath;
                            if (p != null) {
                                param.setResult(p);
                            }
                        }
                    }
                });
                mOverrideActive = true;
                Log.i("config_icon_mask overridden");
            } catch (Throwable t) {
                Log.e("patch config_icon_mask", t);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean forceOverride() {
        boolean force = mForceOverride;
        mForceOverride = false;
        return force;
    }

    @Override
    public Class<?> ownerClassOf(Object bitmapInfo) {
        try {
            Field owner = ShapeSync.field(bitmapInfo.getClass(), "owner");
            Object o = owner == null ? null : owner.get(bitmapInfo);
            return o == null ? null : o.getClass();
        } catch (Throwable t) {
            return null;
        }
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
