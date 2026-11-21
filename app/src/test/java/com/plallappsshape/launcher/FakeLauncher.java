package com.plallappsshape.launcher;

import com.plallappsshape.launcher.ShapeSync.Pending;
import com.plallappsshape.launcher.ShapeSync.Platform;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Fake launcher backing {@link ShapeSyncTest}: a mutable IconState plus recorded overrides. */
final class FakeLauncher implements Platform {

    /** Stand-in for the launcher's IconShape: identity is what matters. */
    static final class Shape {
        final String label;

        Shape(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return "Shape(" + label + ")";
        }
    }

    /** Only the class name matters: it must contain {@code ShapeDelegate$Circle}. */
    static final class ShapeDelegate$Circle {
    }

    String mask = "M50 0A50 50,0,1,1,50 100A50 50,0,1,1,50 0Z";
    String path = "M0 0H100V100H0Z";
    Object shape = new Shape("desktop");
    boolean forceOverride;
    boolean stateAvailable = true;

    /** Counts calls to parseIconState(): the core must not call it in a loop. */
    int parseCalls;

    final List<String> overrides = new ArrayList<>();

    @Override
    public Pending parseIconState() {
        parseCalls++;
        return stateAvailable ? new Pending(mask, path, shape) : null;
    }

    @Override
    public boolean setOverride(String svgPath) {
        overrides.add(String.valueOf(svgPath));
        return svgPath != null;
    }

    @Override
    public boolean forceOverride() {
        boolean f = forceOverride;
        forceOverride = false;
        return f;
    }

    @Override
    public Object getField(Object target, String name) {
        try {
            Field f = ShapeSync.field(target.getClass(), name);
            return f == null ? null : f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void setField(Object target, String name, Object value) {
        try {
            Field f = ShapeSync.field(target.getClass(), name);
            if (f != null) {
                f.set(target, value);
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
