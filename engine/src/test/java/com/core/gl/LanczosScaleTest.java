package com.core.gl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class LanczosScaleTest {
    @Test public void plans900pTo1200pAtRequestedDimensionsInsteadOfDoubling() {
        LanczosScale.Plan p = new LanczosScale.Plan(1600, 900, 2133, 1200);
        assertEquals(2133, p.outputWidth);
        assertEquals(1200, p.outputHeight);
        assertEquals(4.0 / 3.0, (double) p.outputHeight / p.sourceHeight, 0.000001);
        assertEquals(2133, p.horizontalWidth);
        assertEquals(900, p.horizontalHeight);
        assertTrue(p.horizontal);
        assertTrue(p.vertical);
    }

    @Test public void plans720pTo1440pWithActualTwoTimesFramebuffer() {
        LanczosScale.Plan p = new LanczosScale.Plan(1280, 720, 2560, 1440);
        assertEquals(2560, p.outputWidth);
        assertEquals(1440, p.outputHeight);
        assertEquals(2.0, (double) p.outputWidth / p.sourceWidth, 0);
        assertEquals(2.0, (double) p.outputHeight / p.sourceHeight, 0);
        assertEquals(720, p.horizontalHeight);
    }

    @Test public void identityAndOneAxisRequestsSkipUnnecessaryPasses() {
        LanczosScale.Plan identity = new LanczosScale.Plan(800, 600, 800, 600);
        assertFalse(identity.horizontal);
        assertFalse(identity.vertical);
        LanczosScale.Plan horizontal = new LanczosScale.Plan(800, 600, 1200, 600);
        assertTrue(horizontal.horizontal);
        assertFalse(horizontal.vertical);
        LanczosScale.Plan vertical = new LanczosScale.Plan(800, 600, 800, 900);
        assertFalse(vertical.horizontal);
        assertTrue(vertical.vertical);
    }

    @Test public void rejectsZeroAndNegativeDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new LanczosScale.Plan(0, 720, 2560, 1440));
        assertThrows(IllegalArgumentException.class, () -> new LanczosScale.Plan(1280, -1, 2560, 1440));
        assertThrows(IllegalArgumentException.class, () -> new LanczosScale.Plan(1280, 720, 0, 1440));

    }

    @Test public void explicitReductionsAndMixedAxesPlanTheActualResamplingTargets() {
        LanczosScale.Plan reduction = LanczosScale.resample(1600, 900, 800, 225);
        assertTrue(reduction.horizontal); assertTrue(reduction.vertical);
        assertEquals(800, reduction.horizontalWidth); assertEquals(900, reduction.horizontalHeight);
        assertTrue(LanczosScale.fitsLimits(1600, 900, 800, 225, 4096, 4096));
        assertEquals(800L * 225 * 4 + 800L * 900 * 4,
                LanczosScale.estimatedOwnedBytes(1600, 900, 800, 225, 0, 0, false, false));
        LanczosScale.Plan mixed = LanczosScale.resample(1600, 900, 3200, 450);
        assertEquals(3200, mixed.outputWidth); assertEquals(450, mixed.outputHeight);
        assertEquals(Long.MAX_VALUE, LanczosScale.estimatedOwnedBytes(1600, 900, 0, 225, 0, 0, false, false));
    }
    @Test public void checksBothAxesAndDeviceBoundsWithoutIntegerOverflow() {
        assertTrue(LanczosScale.fitsLimits(1280, 720, 2560, 1440, 4096, 4096));
        assertTrue(LanczosScale.fitsLimits(1280, 720, 4096, 4096, 4096, 4096));
        assertFalse(LanczosScale.fitsLimits(1280, 720, 4097, 1440, 4096, 4096));
        assertFalse(LanczosScale.fitsLimits(1280, 720, 2560, 2049, 4096, 2048));
        assertFalse(LanczosScale.fitsLimits(5000, 720, 5000, 1440, 4096, 4096));
        assertFalse(LanczosScale.fitsLimits(1280, 720, Integer.MAX_VALUE, Integer.MAX_VALUE, 4096, 4096));
        assertFalse(LanczosScale.fitsLimits(1280, 720, 2560, 1440, 0, 4096));
    }
    @Test public void budgetCountsOnlyOwnedIdentityTargetsAndKr2Copy() {
        assertEquals(4L, LanczosScale.estimatedOwnedBytes(1, 1, 1, 1, 0, 0, false));
        assertEquals(8L, LanczosScale.estimatedOwnedBytes(1, 1, 1, 1, 0, 0, true));
        // Deblur owns LINELUMA, MMKERNEL and one self-SAVE back buffer.
        assertEquals(1792L, LanczosScale.estimatedOwnedBytes(8, 8, 8, 8, 2, 1, false));
    }

    @Test public void budgetIncludesTwoAxisNegativeLobeStorageAndIndependentOutput() {
        // Restore S: 100x100 RGBA8 + three 100x100 RGBA16F; then X + final Y.
        assertEquals(600000L, LanczosScale.estimatedOwnedBytes(100, 100, 200, 200, 3, 0, false));
        assertEquals(640000L, LanczosScale.estimatedOwnedBytes(100, 100, 200, 200, 3, 0, true));
        // One changed axis writes directly to the final output, without an X intermediate.
        assertEquals(340000L, LanczosScale.estimatedOwnedBytes(100, 100, 150, 100, 3, 0, false));
        assertEquals(340000L, LanczosScale.estimatedOwnedBytes(100, 100, 100, 150, 3, 0, false));
    }

    @Test public void scaleOnlyBudgetUsesRgba8ForBothAxisPassesWithoutRestoreStorage() {
        // 200x200 output plus a 200x100 horizontal pass, both four-byte RGBA8.
        assertEquals(240000L, LanczosScale.estimatedOwnedBytes(
                100, 100, 200, 200, 0, 0, false, false));
        assertEquals(60000L, LanczosScale.estimatedOwnedBytes(
                100, 100, 150, 100, 0, 0, false, false));
        assertEquals(60000L, LanczosScale.estimatedOwnedBytes(
                100, 100, 100, 150, 0, 0, false, false));
        // Reconstruction's restore target and horizontal pass keep their original formats.
        assertEquals(360000L, LanczosScale.estimatedOwnedBytes(
                100, 100, 200, 200, 0, 0, false, true));
    }
    @Test public void budgetSeparatesSafeAndTooLargeRestoreLRequests() {
        long sameSize = LanczosScale.estimatedOwnedBytes(2560, 1440, 2560, 1440, 8, 0, false);
        long enlarged = LanczosScale.estimatedOwnedBytes(2560, 1440, 3840, 2160, 8, 0, false);
        assertEquals(250675200L, sameSize);
        assertEquals(328089600L, enlarged);
        assertTrue(sameSize < Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES);
        assertTrue(enlarged > Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES);
    }

    @Test public void budgetUsesLongAndSaturatesOverflowInsteadOfAllowingNegativeBytes() {
        assertEquals(34359738356L, LanczosScale.estimatedOwnedBytes(
                1, 1, 1, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, false));
        assertEquals(Long.MAX_VALUE, LanczosScale.estimatedOwnedBytes(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 8, 0, false));
        assertEquals(Long.MAX_VALUE, LanczosScale.estimatedOwnedBytes(100, 100, 200, 200, -1, 0, false));
    }
}
