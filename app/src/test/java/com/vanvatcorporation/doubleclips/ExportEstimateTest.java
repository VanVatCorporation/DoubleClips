package com.vanvatcorporation.doubleclips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM checks of {@link ExportEstimate} (the same numbers as Desktop's ExportEstimateSelfTest, plus the OpenGL engine). */
public class ExportEstimateTest {

    @Test public void hardwareBitrate() {
        // 15 Mbit/s video + 128 kbit/s audio for 60 s = 15.128e6 / 8 * 60 bytes
        ExportEstimate hw = ExportEstimate.of(1920, 1080, 30, 23, 15, false, true, 60);
        assertEquals(113_460_000L, hw.bytes);
        assertFalse(hw.isRough());
        assertEquals(15_000_000L, hw.videoBitsPerSecond);
    }

    @Test public void openGlUsesTheResolutionRule() {
        ExportEstimate gl = ExportEstimate.of(1920, 1080, 30, 23, 15, true, true, 60);
        assertEquals(ExportEstimate.Basis.OPENGL_BITRATE, gl.basis);
        assertEquals(ExportEstimate.openGlBitrate(1920, 1080, 30), (int) gl.videoBitsPerSecond);
        assertEquals(6_220_800, ExportEstimate.openGlBitrate(1920, 1080, 30));
        assertFalse(gl.isRough());
    }

    @Test public void softwareIsRoughAndFollowsCrf() {
        ExportEstimate sw = ExportEstimate.of(1920, 1080, 30, 23, 15, false, false, 60);
        assertTrue(sw.isRough());
        assertTrue(sw.videoBitsPerSecond > 4_300_000L && sw.videoBitsPerSecond < 4_600_000L);
        long at29 = ExportEstimate.of(1920, 1080, 30, 29, 15, false, false, 60).videoBitsPerSecond;
        long at17 = ExportEstimate.of(1920, 1080, 30, 17, 15, false, false, 60).videoBitsPerSecond;
        assertTrue(Math.abs(at29 * 2 - sw.videoBitsPerSecond) < 1000);
        assertTrue(Math.abs(at17 - sw.videoBitsPerSecond * 2) < 1000);
    }

    @Test public void badInputNeverGoesNegative() {
        assertEquals(0, ExportEstimate.of(1920, 1080, 30, 23, 15, false, true, 0).bytes);
        assertTrue(ExportEstimate.of(0, 0, 0, 99, 0, false, false, 10).bytes >= 0);
        assertTrue(ExportEstimate.of(0, 0, 0, 99, 0, true, false, 10).bytes >= 0);
        assertTrue(ExportEstimate.openGlBitrate(100000, 100000, 1000) > 0);   // clamped, no overflow
    }

    @Test public void formatting() {
        assertEquals("0 bytes", ExportEstimate.formatBytes(0));
        assertEquals("845 KB", ExportEstimate.formatBytes(845 * 1024L));
        assertEquals("108 MB", ExportEstimate.formatBytes(113_460_000L));
        assertEquals("1.5 GB", ExportEstimate.formatBytes(1_610_612_736L));
        assertEquals("9.5 MB", ExportEstimate.formatBytes(10_000_000L));
        assertEquals("1:05", ExportEstimate.formatDuration(65));
        assertEquals("1:02:03", ExportEstimate.formatDuration(3723));
        assertEquals("0:00", ExportEstimate.formatDuration(-3));
        assertEquals("15 Mbps", ExportEstimate.formatMbps(15_000_000L));
        assertEquals("6.2 Mbps", ExportEstimate.formatMbps(6_220_800L));
    }
}
