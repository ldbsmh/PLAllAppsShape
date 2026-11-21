package com.plallappsshape.launcher;

import com.plallappsshape.launcher.ShapeSync.Pending;
import com.plallappsshape.launcher.ShapeSync.Platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fake launcher backing {@link ShapeSyncTest}: mutable IconState + recorded shape builds. */
final class FakeLauncher implements Platform {

    /** Stand-in for icons.IconShape: only identity matters. */
    static final class IconShape {
        final String path;
        final int size;

        IconShape(String path, int size) {
            this.path = path;
            this.size = size;
        }

        @Override
        public String toString() {
            return "IconShape(" + size + "px)";
        }
    }

    /** Only the class name matters: it must contain {@code ShapeDelegate$Circle}. */
    static final class ShapeDelegate$Circle {
    }

    String mask = "M50 0A50 50,0,1,1,50 100A50 50,0,1,1,50 0Z";
    String path = "M0 0H100V100H0Z";
    Object shape = new Object();
    boolean forceOverride;
    boolean stateAvailable = true;
    boolean buildFails;

    int parseCalls;
    final List<String> overrides = new ArrayList<>();
    final Map<String, Integer> builds = new HashMap<>();

    @Override
    public Pending parseIconState() {
        parseCalls++;
        return stateAvailable ? new Pending(mask, path, shape) : null;
    }

    @Override
    public Object buildIconShape(String path, int size) {
        builds.merge(path + "@" + size, 1, Integer::sum);
        return buildFails ? null : new IconShape(path, size);
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

    int totalBuilds() {
        int n = 0;
        for (int v : builds.values()) {
            n += v;
        }
        return n;
    }
}
