package com.plallappsshape.launcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Framework-agnostic core: no android.* / Xposed imports, only reflection and the small
 * {@link Platform} interface, so it is unit-tested on a plain JVM.
 *
 * <h3>Why this works (decompiled Pixel Launcher 1.7 / launcher3)</h3>
 * <pre>
 * ThemeManager.parseIconState(null) -&gt; IconState{iconMask, iconShape}
 *     iconMask  = the SVG path (ShapesProvider pathString, or config_icon_mask)
 *     iconShape = ShapeDelegate.Companion.pickBestShape(iconMask)   // a ShapeDelegate
 *
 * BitmapInfo.newIcon(ctx, flags, IconShape shape):
 *     shape != null ? shape : this.defaultIconShape
 *
 * ItemInfoWithIcon.newIcon(ctx, flags):
 *     workspace icon  -&gt; passes iconShapeData.value   (non-null: leave it alone)
 *     everything else -&gt; passes null                  (drawer, taskbar: that is our hook)
 * </pre>
 *
 * <p>So the drawer is exactly the case where {@code newIcon} receives a null shape, and it falls
 * back to {@code defaultIconShape} - which the launcher filled from the system mask (a circle).
 *
 * <p>Two things must <b>not</b> be done, both learned the hard way:
 * <ul>
 *   <li>{@code BitmapInfo.defaultIconShape} cannot be assigned a {@code ShapeDelegate}: the field
 *       type is {@code icons.IconShape} (path + renderer), a different class. It has to be built
 *       through {@code GraphicsUtils.generateIconShape(size, path)};</li>
 *   <li>patching {@code BitmapInfo} instances does not take effect until a restart, because the
 *       icon cache reuses them and they keep the shape they were built with. Overriding the
 *       argument of {@code newIcon} is evaluated per draw instead, so a new shape applies at once.</li>
 * </ul>
 */
public final class ShapeSync {

    /** Built IconShape instances, keyed by "path@size". */
    private final Map<String, Object> mBuilt = new HashMap<>();

    private String mPath = "";
    private int mPatches;

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
     * @return true when the effective path changed (the caller may want to redraw)
     */
    public synchronized boolean applySnapshot(Platform platform, Pending state) {
        try {
            if (platform.forceOverride()) {
                reset(platform, "override released");
                return true;
            }
            if (state == null || state.shape == null) {
                return false;
            }
            if (isCircle(state.shape)) {
                // The system mask is a circle already: nothing to do.
                if (mPath.isEmpty()) {
                    return false;
                }
                reset(platform, "desktop shape is a circle: override released");
                return true;
            }
            String path = state.path != null && !state.path.trim().isEmpty() ? state.path : state.mask;
            if (path == null || path.trim().isEmpty()) {
                return false;
            }
            if (path.equals(mPath)) {
                return false;
            }
            platform.setOverride(path);
            mPath = path;
            mBuilt.clear();
            mPatches = 0;
            Log.i("desktop shape: " + abbreviate(path));
            return true;
        } catch (Throwable t) {
            Log.e("apply snapshot", t);
            return false;
        }
    }

    private void reset(Platform platform, String why) {
        platform.setOverride(null);
        mPath = "";
        mBuilt.clear();
        Log.i(why);
    }

    /** The current desktop path, or "" when the drawer should keep the launcher's own shape. */
    public String path() {
        return mPath;
    }

    /**
     * The {@code icons.IconShape} the drawer should use at this size, or null to leave the launcher
     * alone (no desktop shape chosen, or it is a circle).
     */
    public synchronized Object drawerShape(Platform platform, int size) {
        if (mPath.isEmpty()) {
            return null;
        }
        String key = mPath + "@" + size;
        Object cached = mBuilt.get(key);
        if (cached != null) {
            return cached;
        }
        Object built = platform.buildIconShape(mPath, size);
        if (built == null) {
            return null;
        }
        mBuilt.put(key, built);
        if (mPatches++ < 3) {
            Log.i("drawer shape built for " + size + "px (" + mPatches + ")");
        }
        return built;
    }

    public int builtCount() {
        return mBuilt.size();
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
         * Builds an {@code icons.IconShape} for this path at this pixel size, the same way the
         * launcher does: {@code new ShapeDelegate$GenericPathShape(path).addToPath(0,0,size/2,p)}
         * then {@code GraphicsUtils.generateIconShape(size, p)}.
         *
         * @return the IconShape, or null when it cannot be built
         */
        Object buildIconShape(String path, int size);

        /**
         * Patches {@code Resources.getSystem().getString(config_icon_mask)} to this path so the
         * launcher's static shape cache rebuilds with it. Pass null to restore the original.
         *
         * @return whether an override is active
         */
        boolean setOverride(String path);

        /** One-shot: the user disabled the module and the mask override must be released. */
        boolean forceOverride();
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
