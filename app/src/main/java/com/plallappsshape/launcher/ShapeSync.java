package com.plallappsshape.launcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Framework-agnostic core: no android.* / Xposed imports, only reflection and the small
 * {@link Platform} interface, so it is unit-tested on a plain JVM (see {@code ShapeSyncTest}).
 *
 * <p>What the launcher does (decompiled Pixel Launcher 1.7, launcher3):
 * <pre>
 * ThemeManager.parseIconState(null)              -> IconState{iconMask, iconShape}
 * ShapeDelegate$GenericPathShape(svg)            -> basePath in a 100x100 coordinate space
 * BaseIconFactory.defaultIconShapeCache (static) -> WeakReference&lt;IconShape&gt; per icon size,
 *                                                   built from AdaptiveIconDrawable.getIconMask()
 * BitmapInfo.defaultIconShape                    -> FastBitmapDrawableDelegate draws through
 *                                                   iconShape.shapeRenderer.render()
 * </pre>
 * The drawer uses the very same static shape cache as the workspace, so it is stuck on the system
 * mask (a circle). Two things make it follow the desktop shape:
 * <ol>
 *   <li>all-apps bitmaps get the workspace's {@code IconShape};</li>
 *   <li>{@code Resources.getSystem().getString(config_icon_mask)} is patched, so the cache
 *       regenerates with that path once the old weak references are collected.</li>
 * </ol>
 * No launcher restart: the next drawer open rebuilds the icons.
 */
public final class ShapeSync {

    private static final String L3 = "com.android.launcher3.";
    static final String MASK_CIRCLE = "icon_shape_circle";

    /** Owner classes of workspace / folder / drag icons: never drawer icons. */
    static final String[] WORKSPACE_OWNERS = {
            L3 + "folder.FolderIcon",
            L3 + "folder.Folder",
            L3 + "folder.PreviewItemManager",
            L3 + "folder.FolderOpenCloseAnimationListener",
            L3 + "dragndrop.DragView",
            L3 + "dragndrop.DragLayer",
    };
    /** Owner classes that only exist in the all-apps (drawer) window. */
    static final String[] ALL_APPS_OWNERS = {
            L3 + "allapps.AllAppsGridAdapter",
            L3 + "allapps.AllAppsRecyclerView",
            L3 + "allapps.SearchRecyclerView",
    };
    static final String BUBBLE_TEXT_VIEW = L3 + "BubbleTextView";

    private String lastMask = "";
    private Object lastShape;
    private int patches;

    // ------------------------------------------------------------------ owner rules

    /**
     * True when a bitmap created for {@code ownerClass} is a drawer icon.
     *
     * <p>{@code IconOptions} is not part of BitmapInfo, so the owner is the only signal: folder
     * things are never drawer icons, all-apps adapters always are, and a BubbleTextView is taken as
     * drawer too (workspace icons are built by the caching logic without an owner).
     */
    public static boolean isAllAppsOwner(Class<?> ownerClass) {
        if (ownerClass == null) {
            return false;
        }
        if (matchesAny(ownerClass, WORKSPACE_OWNERS)) {
            return false;
        }
        if (matchesAny(ownerClass, ALL_APPS_OWNERS)) {
            return true;
        }
        return matchesAny(ownerClass, new String[]{BUBBLE_TEXT_VIEW});
    }

    static boolean matchesAny(Class<?> c, String[] names) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            String name = k.getName();
            for (String n : names) {
                if (n.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

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

    /** Built-in shapes report their key ("icon_shape_x"); custom shapes carry a path. */
    static boolean looksLikePath(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.startsWith("M") || v.startsWith("m");
    }

    public static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() > 56 ? s.substring(0, 56) + "\u2026" : s;
    }

    // ------------------------------------------------------------------ resolution

    /**
     * The shape the drawer should use now.
     *
     * @return shape to install, or null to leave icons untouched
     */
    public synchronized Object resolve(Platform platform) {
        try {
            if (platform.forceOverride()) {
                platform.setOverride(null);
                Log.i("system mask override restored");
                return lastShape;
            }
            String key = platform.readShapeKey();
            if (key == null || key.isEmpty()) {
                return lastShape;
            }
            if (key.equals(lastMask) && lastShape != null) {
                return lastShape;
            }
            Pending state = platform.parseIconState();
            if (state == null || state.shape == null || state.mask == null) {
                return lastShape;
            }
            // IconState is rebuilt lazily: only trust it once it matches the pref.
            if (!key.equals(state.mask)) {
                return lastShape;
            }
            if (isCircle(state.shape)) {
                // Nothing to gain: the system mask is already a circle.
                lastMask = key;
                lastShape = null;
                return null;
            }
            String svg = looksLikePath(state.path) ? state.path : platform.systemString(key);
            if (svg == null || svg.trim().isEmpty()) {
                Log.d("no svg for " + key);
                return lastShape;
            }
            Object shape = looksLikePath(state.path) ? state.shape : platform.buildIconShape(svg);
            if (shape == null) {
                return lastShape;
            }
            platform.setOverride(svg);
            lastMask = key;
            lastShape = shape;
            patches = 0;
            Log.i("desktop shape: key=" + abbreviate(key) + " path=" + abbreviate(svg));
            return lastShape;
        } catch (Throwable t) {
            Log.e("resolve desktop shape", t);
            return lastShape;
        }
    }

    /** Applies the drawer shape to a BitmapInfo; returns true when the field was replaced. */
    public synchronized boolean applyToBitmap(Platform platform, Object bitmapInfo) {
        if (bitmapInfo == null || !isAllAppsOwner(platform.ownerClassOf(bitmapInfo))) {
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
        if (patches++ < 5) {
            Log.i("drawer icon shape applied (" + patches + ")");
        }
        return true;
    }

    public String lastMask() {
        return lastMask;
    }

    // ------------------------------------------------------------------ bridge

    /** Everything launcher/framework specific the core needs. */
    public interface Platform {
        /** {@code PREF_ICON_SHAPE} value, e.g. {@code icon_shape_squircle}. */
        String readShapeKey();

        /** {@code ThemeManager#parseIconState(null)} reduced to what we need. */
        Pending parseIconState();

        /** {@code new ShapeDelegate$GenericPathShape(svg)} rendered into an {@code IconShape}. */
        Object buildIconShape(String svg);

        /** Android system string resource, or null. */
        String systemString(String resourceName);

        /**
         * Patches {@code Resources.getSystem().getString(config_icon_mask)} to this path so the
         * static shape cache rebuilds with it. Pass null to restore the original.
         *
         * @return whether an override is active
         */
        boolean setOverride(String svgPath);

        /** One-shot: the user disabled the module and the mask override must be released. */
        boolean forceOverride();

        Class<?> ownerClassOf(Object bitmapInfo);

        Object getField(Object target, String name);

        void setField(Object target, String name, Object value);
    }

    /** Snapshot of {@code ThemeManager$IconState}. */
    public static final class Pending {
        public final String mask;   // iconMask: shape key or raw path
        public final String path;   // pathString of the shape delegate, or ""
        public final Object shape;  // ShapeDelegate instance

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
