package com.plallappsshape.launcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Framework-agnostic core: no android.* / Xposed imports, only reflection and the small
 * {@link Platform} interface, so it is unit-tested on a plain JVM.
 *
 * <h3>Why this works (decompiled Pixel Launcher 1.7 / launcher3)</h3>
 * <pre>
 * ThemeManager.parseIconState(null) -&gt; IconState{iconMask, iconShape}
 *     iconMask  = the SVG path itself (ShapesProvider entry's pathString, or config_icon_mask)
 *     iconShape = ShapeDelegate.Companion.pickBestShape(iconMask)   // launcher-built, right scale
 *
 * BitmapInfo.newIcon(ctx, flags, shape):
 *     shape != null ? shape : this.defaultIconShape      &lt;-- the fallback is the whole trick
 *
 * ItemInfoWithIcon.newIcon(ctx, flags):
 *     workspace icon  -&gt; passes iconShapeData.value   (explicit shape, unaffected by us)
 *     everything else -&gt; passes null                  (drawer, taskbar, ...)
 * </pre>
 * So the drawer gets {@code defaultIconShape}, which the launcher fills from the system mask - a
 * circle, regardless of the shape chosen for the workspace. Two changes make it follow the desktop:
 * <ol>
 *   <li>every {@code BitmapInfo.defaultIconShape} is replaced with {@code IconState.iconShape}
 *       (workspace icons are unaffected: they pass their own shape);</li>
 *   <li>{@code config_icon_mask} is overridden with the same path, so the static shape cache and
 *       the icon shadows use it too.</li>
 * </ol>
 *
 * <p><b>Re-entrancy rule:</b> {@link Platform#parseIconState()} must return a <i>cached</i> snapshot,
 * never invoke the launcher method. Calling it from inside that method's own hook is an infinite
 * recursion (it crashed the launcher once).
 */
public final class ShapeSync {

    private String lastPath = "";
    private Object lastShape;
    private int patches;

    // ------------------------------------------------------------------ helpers

    /**
     * True for the launcher's built-in circle. Uses "contains" so anonymous subclasses such as
     * {@code ...ShapeDelegate$Circle$1} count too; reflection failures mean "not a circle".
     */
    public static boolean isCircle(Object shape) {
        try {
            for (Class<?> k = shape.getClass(); k != null; k = k.getSuperclass()) {
                if (k.getName().contains("ShapeDelegate$Circle")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // generated/proxied classes: let the launcher's own shape be used
        }
        return false;
    }

    public static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 56 ? s.substring(0, 56) + "\u2026" : s;
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Applies a state snapshot. Pure bookkeeping: never calls back into the launcher, so it is safe
     * to run from inside {@code parseIconState}'s own hook.
     *
     * @return true when the effective shape changed (the caller may want to redraw)
     */
    public synchronized boolean applySnapshot(Platform platform, Pending state) {
        try {
            if (platform.forceOverride()) {
                platform.setOverride(null);
                lastPath = "";
                lastShape = null;
                Log.i("override released");
                return true;
            }
            if (state == null || state.shape == null) {
                return false;
            }
            if (isCircle(state.shape)) {
                // The system mask is a circle already: nothing to change.
                if (lastPath.isEmpty()) {
                    return false;
                }
                platform.setOverride(null);
                lastPath = "";
                lastShape = null;
                Log.i("desktop shape is a circle: override released");
                return true;
            }
            String path = state.path != null && !state.path.trim().isEmpty() ? state.path : state.mask;
            if (path == null || path.trim().isEmpty()) {
                return false;
            }
            if (path.equals(lastPath) && lastShape != null) {
                return false;
            }
            platform.setOverride(path);
            lastPath = path;
            lastShape = state.shape;
            patches = 0;
            Log.i("desktop shape: " + abbreviate(path));
            return true;
        } catch (Throwable t) {
            Log.e("apply snapshot", t);
            return false;
        }
    }

    /** The shape to use now, from the platform's cached state. */
    public synchronized Object resolve(Platform platform) {
        applySnapshot(platform, platform.parseIconState());
        return lastShape;
    }

    /** Applies the desktop shape to a BitmapInfo; returns true when the field was replaced. */
    public synchronized boolean applyToBitmap(Platform platform, Object bitmapInfo) {
        if (bitmapInfo == null) {
            return false;
        }
        Object wanted = resolve(platform);
        if (wanted == null) {
            return false;
        }
        Object current = platform.getField(bitmapInfo, "defaultIconShape");
        if (current == wanted) {
            return false;
        }
        platform.setField(bitmapInfo, "defaultIconShape", wanted);
        if (patches++ < 3) {
            Log.i("icon shape applied (" + patches + ")");
        }
        return true;
    }

    public String lastPath() {
        return lastPath;
    }

    // ------------------------------------------------------------------ bridge

    /** Everything launcher/framework specific the core needs. */
    public interface Platform {
        /**
         * The <b>cached</b> {@code ThemeManager#parseIconState(null)} result, or null before the
         * launcher has parsed it once. Implementations must not invoke the real method here.
         */
        Pending parseIconState();

        /**
         * Patches {@code Resources.getSystem().getString(config_icon_mask)} to this path so the
         * launcher's static shape cache rebuilds with it. Pass null to restore the original.
         *
         * @return whether an override is active
         */
        boolean setOverride(String svgPath);

        /** One-shot: the user disabled the module and the mask override must be released. */
        boolean forceOverride();

        Object getField(Object target, String name);

        void setField(Object target, String name, Object value);
    }

    /** Snapshot of {@code ThemeManager$IconState}. */
    public static final class Pending {
        public final String mask;   // iconMask: the SVG path
        public final String path;   // pathString behind iconShape, when it is a GenericPathShape
        public final Object shape;  // ShapeDelegate instance (launcher-built)

        public Pending(String mask, String path, Object shape) {
            this.mask = mask;
            this.path = path;
            this.shape = shape;
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    public static Field field(Class<?> cls, String name) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking
            }
        }
        return null;
    }

    public static Method method(Class<?> cls, String name, int paramCount) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    public static java.lang.reflect.Constructor<?> ctor(Class<?> cls, Class<?>... params) {
        try {
            java.lang.reflect.Constructor<?> c = cls.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }
}
