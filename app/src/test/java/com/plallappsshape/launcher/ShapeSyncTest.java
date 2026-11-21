package com.plallappsshape.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Core behaviour of {@link ShapeSync} on a plain JVM. Owner classes come from
 * {@code app/test-placeholders}: empty classes with the launcher's exact names, so the production
 * name-matching code paths are what gets exercised.
 */
public class ShapeSyncTest {

    private final ShapeSync sync = new ShapeSync();
    @org.junit.Before
    public void setUp() {
        launcher = new FakeLauncher();
    }

    private FakeLauncher launcher;

    private static final class BitmapInfo {
        Object defaultIconShape = new FakeLauncher.Shape("SYSTEM_MASK");
        final Object owner;

        BitmapInfo(Object owner) {
            this.owner = owner;
        }
    }

    private static Object owner(Class<?> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BitmapInfo drawerIcon() {
        return new BitmapInfo(owner(com.android.launcher3.allapps.AllAppsGridAdapter.class));
    }

    /** Runs a case that the launcher can only satisfy through its placeholder class name. */
    private static boolean isDrawer(Class<?> cls) {
        return ShapeSync.isAllAppsOwner(cls);
    }

    @Test
    public void folderIconsAreNeverPatched() {
        BitmapInfo info = new BitmapInfo(owner(com.android.launcher3.folder.FolderIcon.class));
        assertFalse(sync.applyToBitmap(launcher, info));
        assertEquals("SYSTEM_MASK", ((FakeLauncher.Shape) info.defaultIconShape).svg);
    }

    @Test
    public void drawerIconGetsDesktopShape() {
        BitmapInfo info = drawerIcon();
        assertTrue(sync.applyToBitmap(launcher, info));
        assertEquals("SQUIRCLE_SVG", ((FakeLauncher.Shape) info.defaultIconShape).svg);
        assertEquals("SQUIRCLE_SVG", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void circleIsIgnored() {
        // name-matched circle, exactly like com.android.launcher3.graphics.ShapeDelegate$Circle
        launcher.stateShape = new ShapeDelegate$Circle();
        BitmapInfo info = new BitmapInfo(owner(com.android.launcher3.BubbleTextView.class));
        assertFalse(sync.applyToBitmap(launcher, info));
        assertTrue(launcher.built.isEmpty());
        assertTrue(ShapeSync.isCircle(launcher.stateShape));
        assertFalse(ShapeSync.isCircle(new Object()));
    }

    @Test
    public void staleIconStateIsIgnoredUntilItMatchesPrefs() {
        launcher.prefKey = "icon_shape_teardrop";
        BitmapInfo info = new BitmapInfo(owner(com.android.launcher3.allapps.AllAppsRecyclerView.class));
        assertFalse(sync.applyToBitmap(launcher, info));

        launcher.prefKey = launcher.stateMask = launcher.statePath = "icon_shape_teardrop";
        launcher.svg = "TEARDROP_SVG";
        assertTrue(sync.applyToBitmap(launcher, info));
        assertEquals("TEARDROP_SVG", ((FakeLauncher.Shape) info.defaultIconShape).svg);
    }

    @Test
    public void shapeIsBuiltOnceAndReused() {
        for (int i = 0; i < 25; i++) {
            sync.applyToBitmap(launcher, drawerIcon());
        }
        assertEquals(1, launcher.built.size());
    }

    @Test
    public void changingDesktopShapeRebuilds() {
        sync.applyToBitmap(launcher, drawerIcon());

        launcher.prefKey = launcher.stateMask = launcher.statePath = "icon_shape_teardrop";
        launcher.svg = "TEARDROP_SVG";
        BitmapInfo info = drawerIcon();
        assertTrue(sync.applyToBitmap(launcher, info));
        assertEquals("TEARDROP_SVG", ((FakeLauncher.Shape) info.defaultIconShape).svg);
        assertEquals(2, launcher.built.size());
    }

    @Test
    public void customSvgPathIsUsedVerbatim() {
        launcher.prefKey = launcher.stateMask = launcher.statePath = "M0 0H100V100H0Z";
        launcher.stateShape = new FakeLauncher.Shape("CUSTOM");
        sync.applyToBitmap(launcher, new BitmapInfo(owner(com.android.launcher3.BubbleTextView.class)));
        assertEquals("M0 0H100V100H0Z", launcher.overrides.get(launcher.overrides.size() - 1));
        assertTrue(launcher.built.isEmpty());
    }

    @Test
    public void missingSvgIsNotFatal() {
        launcher.nullSystemString = true;
        BitmapInfo info = drawerIcon();
        assertFalse(sync.applyToBitmap(launcher, info));
        assertNull(sync.resolve(launcher));
    }

    @Test
    public void buildFailureKeepsThePreviousShape() {
        sync.applyToBitmap(launcher, drawerIcon());
        Object first = sync.resolve(launcher);

        launcher.buildFails = true;
        launcher.prefKey = launcher.stateMask = launcher.statePath = "icon_shape_teardrop";
        assertSame(first, sync.resolve(launcher));
    }

    @Test
    public void disablingReleasesTheMaskOverride() {
        sync.applyToBitmap(launcher, drawerIcon());
        launcher.forceOverride = true;
        sync.resolve(launcher);
        assertEquals("null", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void ownerRules() {
        assertTrue(isDrawer(com.android.launcher3.allapps.AllAppsGridAdapter.class));
        assertTrue(isDrawer(com.android.launcher3.allapps.AllAppsRecyclerView.class));
        assertTrue(isDrawer(com.android.launcher3.BubbleTextView.class));
        assertFalse(isDrawer(com.android.launcher3.folder.Folder.class));
        assertFalse(isDrawer(com.android.launcher3.folder.PreviewItemManager.class));
        assertFalse(isDrawer(com.android.launcher3.dragndrop.DragView.class));
        assertFalse(isDrawer(null));
    }

    @Test
    public void pathDetection() {
        assertTrue(ShapeSync.looksLikePath("m 0,0 h 100"));
        assertFalse(ShapeSync.looksLikePath("icon_shape_squircle"));
        assertFalse(ShapeSync.looksLikePath(null));
    }
}
