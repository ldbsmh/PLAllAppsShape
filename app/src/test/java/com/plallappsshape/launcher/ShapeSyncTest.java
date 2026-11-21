package com.plallappsshape.launcher;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ShapeSync} behaviour on a plain JVM.
 *
 * <p>The contract, straight from the decompiled launcher:
 * <ul>
 *   <li>the drawer is {@code newIcon(ctx, flags, null)} - the null shape is what we replace;</li>
 *   <li>the built shape is an {@code icons.IconShape}, cached per pixel size;</li>
 *   <li>a circle desktop shape means "leave the launcher alone";</li>
 *   <li>a new desktop shape takes effect without a restart, because the shape is built per draw.</li>
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

    @Test
    public void drawerGetsTheDesktopShape() {
        sync.applySnapshot(launcher, launcher.parseIconState());
        Object shape = sync.drawerShape(launcher, 192);
        assertNotNull(shape);
        assertEquals(192, ((FakeLauncher.IconShape) shape).size);
        assertEquals("M0 0H100V100H0Z", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void shapeIsBuiltOncePerSize() {
        sync.applySnapshot(launcher, launcher.parseIconState());
        Object first = sync.drawerShape(launcher, 192);
        Object again = sync.drawerShape(launcher, 192);
        assertSame(first, again);
        assertEquals(1, launcher.totalBuilds());

        sync.drawerShape(launcher, 128);
        assertEquals(2, launcher.totalBuilds());
    }

    @Test
    public void newDesktopShapeTakesEffectImmediately() {
        sync.applySnapshot(launcher, launcher.parseIconState());
        Object before = sync.drawerShape(launcher, 192);

        launcher.path = "TEARDROP_PATH";
        assertTrue(sync.applySnapshot(launcher, launcher.parseIconState()));
        Object after = sync.drawerShape(launcher, 192);

        assertFalse(before == after);
        assertEquals("TEARDROP_PATH", ((FakeLauncher.IconShape) after).path);
        assertEquals("TEARDROP_PATH", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void circularDesktopShapeLeavesTheLauncherAlone() {
        launcher.shape = new FakeLauncher.ShapeDelegate$Circle();
        assertFalse(sync.applySnapshot(launcher, launcher.parseIconState()));
        assertNull(sync.drawerShape(launcher, 192));
        assertTrue(launcher.overrides.isEmpty());
        assertTrue(launcher.builds.isEmpty());
    }

    @Test
    public void switchingBackToCircleStopsInterfering() {
        sync.applySnapshot(launcher, launcher.parseIconState());
        assertNotNull(sync.drawerShape(launcher, 192));

        launcher.shape = new FakeLauncher.ShapeDelegate$Circle();
        assertTrue(sync.applySnapshot(launcher, launcher.parseIconState()));
        assertNull(sync.drawerShape(launcher, 192));
        assertEquals("null", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void rawPathIsUsedWhenTheShapeHasNoPathString() {
        // custom (scribbled) shapes: iconShape is not a GenericPathShape, so iconMask is the path
        launcher.path = "";
        launcher.mask = "M1 1L99 99Z";
        sync.applySnapshot(launcher, launcher.parseIconState());
        assertEquals("M1 1L99 99Z", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void missingPathIsNotFatal() {
        launcher.path = "";
        launcher.mask = "";
        assertFalse(sync.applySnapshot(launcher, launcher.parseIconState()));
        assertNull(sync.drawerShape(launcher, 192));
    }

    @Test
    public void noIconStateYetIsNotFatal() {
        launcher.stateAvailable = false;
        assertFalse(sync.applySnapshot(launcher, launcher.parseIconState()));
        assertNull(sync.drawerShape(launcher, 192));
    }

    @Test
    public void buildFailureIsNotCached() {
        sync.applySnapshot(launcher, launcher.parseIconState());
        launcher.buildFails = true;
        assertNull(sync.drawerShape(launcher, 192));
        assertEquals(0, sync.builtCount());

        launcher.buildFails = false;
        assertNotNull(sync.drawerShape(launcher, 192));
    }

    @Test
    public void disablingReleasesTheOverride() {
        sync.applySnapshot(launcher, launcher.parseIconState());
        launcher.forceOverride = true;
        assertTrue(sync.applySnapshot(launcher, launcher.parseIconState()));
        assertNull(sync.drawerShape(launcher, 192));
        assertEquals("null", launcher.overrides.get(launcher.overrides.size() - 1));
    }

    @Test
    public void resolvingIsBoundedAndDoesNotLoop() {
        // Regression guard: applySnapshot() must be pure bookkeeping. It once re-entered
        // parseIconState() through its own hook, which recursed until the launcher died.
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
        assertFalse(ShapeSync.isCircle(new Object()));
    }
}
