package com.plallappsshape.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ShapeSync} behaviour on a plain JVM. The owner classes come from the doubles in
 * {@code app/src/test/java/com/android/launcher3} - same package and class names as the launcher's,
 * which is exactly what the production matching code looks at.
 */
public class ShapeSyncTest {

    private final ShapeSync sync = new ShapeSync();
    private FakeLauncher launcher;

    @org.junit.Before
    public void setUp() {
        launcher = new FakeLauncher();
    }

    private static final class BitmapInfo {
        Object defaultIconShape = new FakeLauncher.Shape("SYSTEM_MASK");
        final Object owner;

        BitmapInfo(Object owner) {
            this.owner = owner;
        }
    }

    private static <T> T make(Class<T> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BitmapInfo drawerIcon() {
        return new BitmapInfo(make(com.android.launcher3.allapps.AllAppsGridAdapter.class));
    }

    private static String svgOf(BitmapInfo info) {
        return ((FakeLauncher.Shape) info.defaultIconShape).svg;
    }

    @Test
    public void folderIconsAreNeverPatched() {
        BitmapInfo info = new BitmapInfo(make(com.android.launcher3.folder.FolderIcon.class));
        assertFalse(sync.applyToBitmap(launcher, info));
        assertEquals("SYSTEM_MASK", svgOf(info));
    }

    @Test
    public void drawerIconGetsDesktopShape() {
        BitmapInfo info = drawerIcon();
        assertTrue(sync.applyToBitmap(launcher, info));
        assertEquals("SQUIRCLE_SVG", svgOf(info));
        assertEquals("SQUIRCLE_SVG", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void circleIsIgnored() {
        launcher.stateShape = new ShapeDelegate$Circle();
        BitmapInfo info = new BitmapInfo(make(com.android.launcher3.BubbleTextView.class));
        assertFalse(sync.applyToBitmap(launcher, info));
        assertTrue(launcher.built.isEmpty());
        assertTrue(ShapeSync.isCircle(launcher.stateShape));
        assertFalse(ShapeSync.isCircle(new Object()));
    }

    @Test
    public void staleIconStateIsIgnoredUntilItMatchesPrefs() {
        launcher.prefKey = "icon_shape_teardrop";
        BitmapInfo info = new BitmapInfo(make(com.android.launcher3.allapps.AllAppsRecyclerView.class));
        assertFalse(sync.applyToBitmap(launcher, info));

        launcher.stateMask = launcher.statePath = "icon_shape_teardrop";
        launcher.svg = "TEARDROP_SVG";
        assertTrue(sync.applyToBitmap(launcher, info));
        assertEquals("TEARDROP_SVG", svgOf(info));
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
        assertEquals("TEARDROP_SVG", svgOf(info));
        assertEquals(2, launcher.built.size());
    }

    @Test
    public void customSvgPathIsUsedVerbatim() {
        launcher.prefKey = launcher.stateMask = launcher.statePath = "M0 0H100V100H0Z";
        launcher.stateShape = new FakeLauncher.Shape("CUSTOM");
        sync.applyToBitmap(launcher, new BitmapInfo(make(com.android.launcher3.BubbleTextView.class)));
        assertEquals("M0 0H100V100H0Z", launcher.overrides.get(launcher.overrides.size() - 1));
        assertTrue(launcher.built.isEmpty());
    }

    @Test
    public void missingSvgIsNotFatal() {
        launcher.nullSystemString = true;
        assertFalse(sync.applyToBitmap(launcher, drawerIcon()));
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
        assertTrue(ShapeSync.isAllAppsOwner(com.android.launcher3.allapps.AllAppsGridAdapter.class));
        assertTrue(ShapeSync.isAllAppsOwner(com.android.launcher3.allapps.AllAppsRecyclerView.class));
        assertTrue(ShapeSync.isAllAppsOwner(com.android.launcher3.BubbleTextView.class));
        assertFalse(ShapeSync.isAllAppsOwner(com.android.launcher3.folder.Folder.class));
        assertFalse(ShapeSync.isAllAppsOwner(com.android.launcher3.folder.PreviewItemManager.class));
        assertFalse(ShapeSync.isAllAppsOwner(com.android.launcher3.dragndrop.DragView.class));
        assertFalse(ShapeSync.isAllAppsOwner(null));
    }

    @Test
    public void pathDetection() {
        assertTrue(ShapeSync.looksLikePath("m 0,0 h 100"));
        assertFalse(ShapeSync.looksLikePath("icon_shape_squircle"));
        assertFalse(ShapeSync.looksLikePath(null));
    }
}
