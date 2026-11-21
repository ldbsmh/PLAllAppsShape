package com.plallappsshape.launcher;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ShapeSync} behaviour on a plain JVM.
 *
 * <p>The contract, straight from the decompiled launcher:
 * <ul>
 *   <li>a non-circle desktop shape is installed on every {@code BitmapInfo} and on the system mask;</li>
 *   <li>a circle desktop shape means "do nothing" - the system mask already is a circle;</li>
 *   <li>workspace icons pass their own shape to {@code newIcon()}, so replacing the fallback does
 *       not touch them;</li>
 *   <li>disabling the module releases the mask override.</li>
 * </ul>
 */
public class ShapeSyncTest {

    private ShapeSync sync;
    private FakeLauncher launcher;

    @Before
    public void setUp() {
        sync = new ShapeSync();
        launcher = new FakeLauncher();
    }

    /** Stand-in for BitmapInfo: the single field the module replaces. */
    private static final class BitmapInfo {
        Object defaultIconShape = new FakeLauncher.Shape("system-circle");
    }

    @Test
    public void desktopShapeIsInstalledOnEveryBitmap() {
        BitmapInfo info = new BitmapInfo();
        assertTrue(sync.applyToBitmap(launcher, info));
        assertSame(launcher.shape, info.defaultIconShape);
        assertEquals("M0 0H100V100H0Z", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void anUnchangedShapeIsResolvedOnceOnly() {
        sync.applyToBitmap(launcher, new BitmapInfo());
        int overrides = launcher.overrides.size();

        // every new bitmap still gets the shape, but the launcher state is not re-applied
        BitmapInfo second = new BitmapInfo();
        assertTrue(sync.applyToBitmap(launcher, second));
        assertSame(launcher.shape, second.defaultIconShape);
        assertEquals(overrides, launcher.overrides.size());
    }

    @Test
    public void circularDesktopShapeLeavesEverythingAlone() {
        launcher.shape = new FakeLauncher.ShapeDelegate$Circle();
        BitmapInfo info = new BitmapInfo();
        assertFalse(sync.applyToBitmap(launcher, info));
        assertEquals("system-circle", ((FakeLauncher.Shape) info.defaultIconShape).label);
        assertTrue(launcher.overrides.isEmpty());
    }

    @Test
    public void switchingBackToCircleReleasesTheOverride() {
        sync.applyToBitmap(launcher, new BitmapInfo());
        launcher.shape = new FakeLauncher.ShapeDelegate$Circle();
        assertNull(sync.resolve(launcher));
        assertEquals("null", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void changingDesktopShapeRebuilds() {
        sync.applyToBitmap(launcher, new BitmapInfo());

        FakeLauncher.Shape teardrop = new FakeLauncher.Shape("teardrop");
        launcher.shape = teardrop;
        launcher.path = "TEARDROP_PATH";
        BitmapInfo info = new BitmapInfo();
        assertTrue(sync.applyToBitmap(launcher, info));
        assertSame(teardrop, info.defaultIconShape);
        assertEquals("TEARDROP_PATH", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void rawPathIsUsedWhenTheShapeHasNoPathString() {
        // custom (scribbled) shapes: iconShape is not a GenericPathShape, so iconMask is the path
        launcher.path = "";
        launcher.mask = "M1 1L99 99Z";
        sync.applyToBitmap(launcher, new BitmapInfo());
        assertEquals("M1 1L99 99Z", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void missingPathIsNotFatal() {
        launcher.path = "";
        launcher.mask = "";
        assertNull(sync.resolve(launcher));
    }

    @Test
    public void noIconStateYetIsNotFatal() {
        // parseIconState() returns null until the launcher has parsed its own state
        launcher.stateAvailable = false;
        assertNull(sync.resolve(launcher));
        assertTrue(launcher.overrides.isEmpty());
    }

    @Test
    public void disablingReleasesTheMaskOverride() {
        sync.applyToBitmap(launcher, new BitmapInfo());
        launcher.forceOverride = true;
        assertNull(sync.resolve(launcher));
        assertEquals("null", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void resolvingIsBoundedAndDoesNotLoop() {
        // Regression guard: applySnapshot() used to re-enter parseIconState() through its own hook,
        // which recursed until the launcher died. It must be pure bookkeeping.
        for (int i = 0; i < 50; i++) {
            sync.applySnapshot(launcher, launcher.parseIconState());
        }
        assertTrue("parseIconState called " + launcher.parseCalls + " times", launcher.parseCalls <= 50);
    }

    @Test
    public void unchangedStateDoesNotReportAChange() {
        assertTrue(sync.applySnapshot(launcher, launcher.parseIconState()));
        assertFalse(sync.applySnapshot(launcher, launcher.parseIconState()));
    }

    @Test
    public void circleDetection() {
        assertTrue(ShapeSync.isCircle(new FakeLauncher.ShapeDelegate$Circle()));
        assertFalse(ShapeSync.isCircle(new FakeLauncher.Shape("squircle")));
    }

    @Test
    public void abbreviateKeepsLogsShort() {
        StringBuilder longPath = new StringBuilder("M0 0");
        for (int i = 0; i < 40; i++) {
            longPath.append("L").append(i).append(" ").append(i);
        }
        assertTrue(ShapeSync.abbreviate(longPath.toString()).length() <= 57);
        assertEquals("null", ShapeSync.abbreviate(null));
    }
}
