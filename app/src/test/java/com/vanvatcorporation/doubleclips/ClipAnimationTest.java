package com.vanvatcorporation.doubleclips;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

/** JUnit entry point; the checks themselves live in ClipAnimationChecks. */
public class ClipAnimationTest {
    @Test
    public void clipAnimationLoaderAndUnfoldRegression() throws Exception {
        List<String> fails = ClipAnimationChecks.runAll();
        assertTrue(fails.toString(), fails.isEmpty());
    }

    @Test
    public void animationPacksInstallValidateAndRejectHostileZips() throws Exception {
        List<String> fails = ClipAnimationPackChecks.runAll();
        assertTrue(fails.toString(), fails.isEmpty());
    }

    @Test
    public void ffmpegGeneratorMatchesCurvesAndLegacyUnfold() throws Exception {
        List<String> fails = ClipAnimationFFmpegChecks.runAll();
        assertTrue(fails.toString(), fails.isEmpty());
    }
}
