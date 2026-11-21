package com.plallappsshape.launcher;

import com.plallappsshape.launcher.ShapeSync.Pending;
import com.plallappsshape.launcher.ShapeSync.Platform;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Fake launcher backing {@link ShapeSyncTest}: mutable IconState + recorded builds/overrides. */
final class FakeLauncher implements Platform {

    static final class Shape {
        final String svg;

        Shape(String svg) {
            this.svg = svg;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Shape && ((Shape) o).svg.equals(svg);
        }

        @Override
        public int hashCode() {
            return svg.hashCode();
        }

        @Override
        public String toString() {
            return "Shape(" + svg + ")";
        }
    }

    String prefKey = "icon_shape_squircle";
    String stateMask = "icon_shape_squircle";
    String statePath = "icon_shape_squircle";   // built-in shapes report their key
    Object stateShape = new Object();
    String svg = "SQUIRCLE_SVG";
    boolean forceOverride;
    boolean buildFails;
    boolean nullSystemString;

    final List<String> built = new ArrayList<>();
    final List<String> overrides = new ArrayList<>();

    @Override
    public String readShapeKey() {
        return prefKey;
    }

    @Override
    public Pending parseIconState() {
        return new Pending(stateMask, statePath, stateShape);
    }

    @Override
    public Object buildIconShape(String path) {
        built.add(path);
        return buildFails ? null : new Shape(path);
    }

    @Override
    public String systemString(String name) {
        return nullSystemString ? null : svg;
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
    public Class<?> ownerClassOf(Object bitmapInfo) {
        Object owner = getField(bitmapInfo, "owner");
        return owner == null ? null : owner.getClass();
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
