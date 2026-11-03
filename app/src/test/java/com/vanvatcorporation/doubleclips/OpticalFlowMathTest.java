package com.vanvatcorporation.doubleclips;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

/** JVM checks of the plain arithmetic behind optical flow (the same numbers as Desktop's OpticalFlowMathSelfTest). */
public class OpticalFlowMathTest {

    @Test public void snapToGridIsTheStartOfTheGridFrame() {
        assertEquals(0.0, OpticalFlowMath.snapToGrid(0.01, 30), 1e-9);
        assertEquals(1.0 / 30, OpticalFlowMath.snapToGrid(0.04, 30), 1e-9);
        assertEquals(1.0 / 30, OpticalFlowMath.snapToGrid(1.0 / 30, 30), 1e-9);   // exactly on a frame stays there
        assertEquals(-0.5, OpticalFlowMath.snapToGrid(-0.5, 30), 1e-9);           // a transition's pre-roll is not snapped
        assertEquals(0.7, OpticalFlowMath.snapToGrid(0.7, 0), 1e-9);              // no usable rate: unchanged
    }

    @Test public void modeSnapsNearFrames() {
        assertEquals(OpticalFlowMath.Mode.FIRST, OpticalFlowMath.modeFor(0.01));
        assertEquals(OpticalFlowMath.Mode.SECOND, OpticalFlowMath.modeFor(0.99));
        assertEquals(OpticalFlowMath.Mode.MIX, OpticalFlowMath.modeFor(0.5));
    }

    @Test public void pyramidPlan() {
        assertArrayEquals(new int[]{2, 4}, OpticalFlowMath.plan(1920, 1080, OpticalFlowMath.EXPORT_FLOW_SIDE));
        int[] tiny = OpticalFlowMath.plan(64, 48, OpticalFlowMath.EXPORT_FLOW_SIDE);
        assertEquals(0, tiny[0]);
        assertTrue(tiny[1] >= 1);
        assertEquals(480, OpticalFlowMath.mipSize(1920, 2));
        assertEquals(1, OpticalFlowMath.mipSize(3, 5));
    }

    @Test public void sceneCut() {
        int w = 128, h = 72;
        ByteBuffer dark = ByteBuffer.allocate(w * h * 4), bright = ByteBuffer.allocate(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            dark.put((byte) 20).put((byte) 20).put((byte) 20).put((byte) 255);
            bright.put((byte) 230).put((byte) 230).put((byte) 230).put((byte) 255);
        }
        dark.flip();
        bright.flip();
        assertTrue(OpticalFlowMath.isSceneCut(dark, bright, w, h));
        assertFalse(OpticalFlowMath.isSceneCut(dark, dark, w, h));
    }
}
