package com.plallappsshape.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Path;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Launcher-side glue: installs the hooks and implements {@link ShapeSync.Platform}.
 *
 * <p>See {@link ShapeSync} for the decompiled pipeline and for the two traps this design avoids.
 * Two hooks do the work:
 * <ul>
 *   <li>{@code BitmapInfo.newIcon(Context, int, IconShape)}: when the caller passes a null shape -
 *       which is what the drawer does - the argument is replaced with the desktop shape. This runs
 *       per draw, so a new shape applies immediately instead of after a restart;</li>
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

    private final ClassLoader mCl;
    private final ShapeSync mSync = new ShapeSync();

    /** Latest snapshot from the launcher: written by the hook, read by the module. */
    private volatile ShapeSync.Pending mState;

    private Object mThemeManager;
    /** LauncherModel#rebindCallbacks(String): the launcher's own "icons must be rebuilt" call. */
    private Method mRebindCallbacks;
    /** ActivityAllAppsContainerView#rebindAdapters(boolean): rebuilds the drawer list itself. */
    private Method mRebindAdapters;
    /** Live drawer container, captured from rebindAdapters(). */
    private volatile java.lang.ref.WeakReference<Object> mAllAppsRef;

    /** {@code GraphicsUtils.generateIconShape(int, Path)} */
    private Method mGenerateIconShape;
    private Constructor<?> mGenericShapeCtor;
    private Method mAddToPath;

    /** Icon bitmap size, learnt from the shapes the launcher already built (fallback only). */
    private volatile int mFallbackIconSize = 192;

    private volatile String mOverridePath;
    private volatile boolean mOverrideActive;

    /** Throttle for the reload request, so a burst of parseIconState calls cannot flood it. */
    private volatile long mLastDispatch;

    /** LauncherModel instance, captured from ModelInitializer.initializeDisplayEvents(). */
    private volatile Object mLauncherModel;

    private LauncherHooks(ClassLoader cl) {
        mCl = cl;
    }

    static void install(Context context, ClassLoader cl) {
        if (sInstance != null) {
            return;
        }
        // context is unused for now: every hook works through the launcher's own objects.
        LauncherHooks hooks = new LauncherHooks(cl);
        sInstance = hooks;
        try {
            hooks.hookThemeManager();
            hooks.hookNewIcon();
            Log.i("hooks installed");
            // Report what is missing once, so a broken install is visible without a debug build.
            if (hooks.mGenerateIconShape == null) {
                Log.e("GraphicsUtils.generateIconShape not found: the drawer shape cannot be built", null);
            }
            if (!hooks.mNewIconHooked) {
                Log.e("BitmapInfo.newIcon(Context,int,IconShape) not found: nothing to patch", null);
            }
            if (hooks.mRebindCallbacks == null) {
                Log.e("LauncherModel.rebindCallbacks not found: a shape change needs a restart", null);
            }
        } catch (Throwable t) {
            Log.e("install", t);
            sInstance = null;
        }
    }

    private boolean mNewIconHooked;

    // ---------------------------------------------------------------- hooks

    private void hookThemeManager() {
        Class<?> themeManager = XposedHelpers.findClass(L3 + "graphics.ThemeManager", mCl);
        Class<?> iconState = XposedHelpers.findClass(L3 + "graphics.ThemeManager$IconState", mCl);
        /*
         * The launcher reacts to a shape change with LauncherModel.rebindCallbacks("icon-shape-changed")
         * (see ModelInitializer.initializeDisplayEvents: iconShapeData.forEach(MAIN_EXECUTOR, ...)).
         * That is the call that actually rebuilds the icon views, so we make it ourselves.
         * The LauncherModel instance is captured from initializeDisplayEvents(LauncherModel).
         */
        try {
            Class<?> modelClass = XposedHelpers.findClass(L3 + "LauncherModel", mCl);
            mRebindCallbacks = ShapeSync.method(modelClass, "rebindCallbacks", 1);
            if (mRebindCallbacks == null) {
                Log.e("rebindCallbacks not found", null);
            }
            // The drawer keeps its own adapter, so it has to be rebound as well.
            try {
                Class<?> allApps = XposedHelpers.findClass(
                        L3 + "allapps.ActivityAllAppsContainerView", mCl);
                mRebindAdapters = ShapeSync.method(allApps, "rebindAdapters", 1);
                if (mRebindAdapters != null) {
                    XposedBridge.hookMethod(mRebindAdapters, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // Remember the live view so we can ask it to rebuild later.
                            mAllAppsRef = new java.lang.ref.WeakReference<>(param.thisObject);
                        }
                    });
                }
            } catch (Throwable t) {
                Log.d("rebindAdapters: " + t);
            }
            Class<?> modelInit = XposedHelpers.findClass(L3 + "model.ModelInitializer", mCl);
            Method initEvents = ShapeSync.method(modelInit, "initializeDisplayEvents", 1);
            if (initEvents != null) {
                XposedBridge.hookMethod(initEvents, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mLauncherModel = param.args[0];
                        Log.d("launcher model captured");
                    }
                });
            }
        } catch (Throwable t) {
            Log.e("LauncherModel", t);
        }
        // ShapeDelegate(path) -> IconShape, exactly how the launcher does it.
        try {
            Class<?> utils = XposedHelpers.findClass(L3 + "icons.GraphicsUtils", mCl);
            mGenerateIconShape = ShapeSync.method(utils, "generateIconShape", 2);
            Class<?> generic = XposedHelpers.findClass(
                    L3 + "graphics.ShapeDelegate$GenericPathShape", mCl);
            mGenericShapeCtor = ShapeSync.ctor(generic, String.class);
            mAddToPath = ShapeSync.method(generic, "addToPath", 4);
        } catch (Throwable t) {
            Log.e("shape builders", t);
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
            break;
        }
    }

    /** Replaces the null shape the drawer passes, so the drawer follows the desktop shape. */
    private void hookNewIcon() {
        Class<?> bitmapInfo = XposedHelpers.findClass(L3 + "icons.BitmapInfo", mCl);
        Class<?> iconShape = XposedHelpers.findClass(L3 + "icons.IconShape", mCl);
        for (Method m : bitmapInfo.getDeclaredMethods()) {
            if (!"newIcon".equals(m.getName())) {
                continue;
            }
            Class<?>[] types = m.getParameterTypes();
            if (types.length != 3 || !iconShape.isAssignableFrom(types[2])) {
                continue;
            }
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args[2] != null) {
                            return; // workspace icon: it brings its own shape
                        }
                        int px = iconSizeOf(param.thisObject);
                        if (px <= 0) {
                            return;
                        }
                        Object shape = mSync.drawerShape(LauncherHooks.this, px);
                        if (shape != null) {
                            param.args[2] = shape;
                        }
                    } catch (Throwable t) {
                        Log.e("newIcon", t);
                    }
                }
            });
            mNewIconHooked = true;
            Log.i("BitmapInfo.newIcon hooked");
            return;
        }
        Log.e("BitmapInfo.newIcon(Context,int,IconShape) not found", null);
    }

    /**
     * The bitmap size for this BitmapInfo. NOTE: {@code newIcon}'s second argument is a flag set,
     * not a size - passing it to {@code generateIconShape} made ShadowGenerator build a
     * BlurMaskFilter with an illegal radius and every call threw.
     */
    private int iconSizeOf(Object bitmapInfo) {
        try {
            Field f = ShapeSync.field(bitmapInfo.getClass(), "icon");
            Object icon = f == null ? null : f.get(bitmapInfo);
            if (icon instanceof Bitmap) {
                int w = ((Bitmap) icon).getWidth();
                if (w > 0) {
                    return w;
                }
            }
        } catch (Throwable t) {
            Log.d("iconSizeOf: " + t);
        }
        return mFallbackIconSize;
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

            if (mSync.applySnapshot(this, pending)) {
                dispatchIconShape(false);
            }
        } catch (Throwable t) {
            Log.e("onIconState", t);
        }
    }

    /**
     * Makes the launcher rebuild its icons, the same way it reacts to a theme change:
     * clear the icon pool, then reload the model. This is what makes a new shape apply without
     * restarting the launcher.
     *
     * @param force bypass the throttle (used when disabling the module)
     */
    private void dispatchIconShape(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - mLastDispatch < 1500L) {
            return;
        }
        mLastDispatch = now;
        try {
            if (mRebindCallbacks == null) {
                return;
            }
            Object model = mLauncherModel;
            if (model == null) {
                Log.d("launcher model unknown yet");
                return;
            }
            mRebindCallbacks.invoke(model, "icon-shape-changed");
            Log.i("icons rebound for the new shape");
            rebindDrawer();
        } catch (Throwable t) {
            Log.e("reload icons", t);
        }
    }

    /** Rebuilds the drawer's adapter so already-inflated rows pick up the new shape. */
    private void rebindDrawer() {
        try {
            java.lang.ref.WeakReference<Object> ref = mAllAppsRef;
            Object container = ref == null ? null : ref.get();
            if (container == null || mRebindAdapters == null) {
                return;
            }
            // Post to the main thread: this may be called from a hook on any thread.
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            final Object target = container;
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mRebindAdapters.invoke(target, Boolean.TRUE);
                        Log.i("drawer adapters rebound");
                    } catch (Throwable t) {
                        Log.e("rebind drawer", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e("rebindDrawer", t);
        }
    }

    // ---------------------------------------------------------------- Platform

    @Override
    public ShapeSync.Pending parseIconState() {
        return mState;
    }

    @Override
    public Object buildIconShape(String path, int size) {
        try {
            if (mGenerateIconShape == null || mGenericShapeCtor == null || mAddToPath == null) {
                return null;
            }
            Object delegate = mGenericShapeCtor.newInstance(path);
            Path out = new Path();
            mAddToPath.invoke(delegate, 0f, 0f, (float) size / 2f, out);
            return mGenerateIconShape.invoke(null, size, out);
        } catch (Throwable t) {
            Log.e("buildIconShape", t);
            return null;
        }
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

}
