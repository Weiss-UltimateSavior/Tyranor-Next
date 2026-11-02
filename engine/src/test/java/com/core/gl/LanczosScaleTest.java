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

    @Test public void scaleShadersBakeKernelWeightsWithoutTemplateResidue() {
        String lanczos = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.LANCZOS2, 0.5);
        assertTrue(lanczos.contains("if (abs(x) >= 2.0) return 0.0;"));
        assertTrue(lanczos.contains("return sinc(x) * sinc(x / 2.0);"));
        assertTrue(lanczos.contains("int support = int(ceil(2.0 * scale));"));
        assertTrue(lanczos.contains("for (int tap = 1 - support; tap <= support; ++tap)"));
        assertTrue(lanczos.contains("weight((center - float(coordinate)) / scale)"));
        assertTrue(lanczos.contains("if (w == 0.0) continue;"));
        assertFalse(lanczos.contains("@@"));

        String bilinear = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.BILINEAR, 0.4);
        assertTrue(bilinear.contains("return max(0.0, 1.0 - abs(x));"));
        assertTrue(bilinear.contains("int support = int(ceil(1.0 * scale));"));
        assertFalse(bilinear.contains("sinc("));
        assertFalse(bilinear.contains("PI"));
        assertFalse(bilinear.contains("@@"));

        String bicubic = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.BICUBIC, 0.33, 0.33, 0.0);
        assertTrue(bicubic.contains("int support = int(ceil(2.0 * scale));"));
        // Default Mitchell baseline B=C=0.33.
        assertTrue(bicubic.contains("(12.0 - 9.0 * 0.33 - 6.0 * 0.33)"));
        assertTrue(bicubic.contains("(-0.33 - 6.0 * 0.33)"));
        assertFalse(bicubic.contains("sinc("));
        assertFalse(bicubic.contains("ringLo"));
        assertFalse(bicubic.contains("@@"));
    }

    @Test public void upscaleVariantKeepsConstantTapsAndSharperBicubicBake() {
        String upscale = LanczosScale.fragmentShader(LanczosScale.ResampleKernel.LANCZOS2, 0.33, 0.33, 0.5);
        assertTrue(upscale.contains("for (int tap = -1; tap <= 2; ++tap)"));
        assertTrue(upscale.contains("weight(center - float(coordinate))"));
        assertFalse(upscale.contains("uOutputSize"));
        assertFalse(upscale.contains("@@"));
        // Independent bicubic B and C baking:
        String bOnly = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.BICUBIC, 1.0, 0.0, 0.0);
        assertTrue(bOnly.contains("(12.0 - 9.0 * 1.0 - 6.0 * 0.0)"));
        String cOnly = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.BICUBIC, 0.0, 0.5, 0.0);
        assertTrue(cOnly.contains("(-0.0 - 6.0 * 0.5)"));
    }

    @Test public void lanczosAntiRingingBakesStrengthAndIsOmittedAtZero() {
        // Anti-ringing 0 (explicit off): no clamp code at all.
        String off = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.LANCZOS2, 0.33, 0.33, 0.0);
        assertFalse(off.contains("ringLo"));
        assertFalse(off.contains("mix(fragColor"));
        // Wire values that round to zero at the 4-decimal bake omit the identity mix block too.
        String tiny = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.LANCZOS2, 0.33, 0.33, 0.00001);
        assertFalse(tiny.contains("ringLo"));
        assertFalse(tiny.contains("mix(fragColor"));
        String antiring = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.LANCZOS2, 0.33, 0.33, 0.5);
        assertTrue(antiring.contains("vec3 ringLo = vec3(1e30); vec3 ringHi = vec3(-1e30);"));
        assertTrue(antiring.contains("if (coordinate == base || coordinate == base + 1) "
                + "{ ringLo = min(ringLo, tapColor.rgb); ringHi = max(ringHi, tapColor.rgb); }"));
        assertTrue(antiring.contains("mix(fragColor.rgb, clamp(fragColor.rgb, ringLo, ringHi), 0.5)"));
        String full = LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.LANCZOS2, 0.33, 0.33, 1.0);
        assertTrue(full.contains("clamp(fragColor.rgb, ringLo, ringHi), 1.0)"));
        assertFalse(LanczosScale.resampleFragmentShader(LanczosScale.ResampleKernel.BILINEAR, 0.33, 0.33, 1.0).contains("ringLo"));
    }

    @Test public void kernelMappingFailsClosedOnUnknownDescriptorStrings() {
        assertEquals(LanczosScale.ResampleKernel.LANCZOS2, LanczosScale.kernelOf(null));
        assertEquals(LanczosScale.ResampleKernel.LANCZOS2, LanczosScale.kernelOf("lanczos2"));
        assertEquals(LanczosScale.ResampleKernel.BILINEAR, LanczosScale.kernelOf("bilinear"));
        assertEquals(LanczosScale.ResampleKernel.BICUBIC, LanczosScale.kernelOf("bicubic"));
        assertThrows(IllegalArgumentException.class, () -> LanczosScale.kernelOf("lanczos3"));
        assertThrows(IllegalArgumentException.class, () -> LanczosScale.kernelOf(""));
        assertThrows(IllegalArgumentException.class, () -> LanczosScale.kernelOf("lanczos"));
    }

    /** GLSL compile errors cannot surface on the JVM; delimiter balance is the next best gate. */
    @Test public void everyGeneratedShaderHasBalancedDelimiters() {
        for (LanczosScale.ResampleKernel kernel : LanczosScale.ResampleKernel.values()) {
            for (double strength : new double[]{0.0, 0.33, 0.5, 1.0}) {
                assertBalanced(LanczosScale.fragmentShader(kernel, strength, strength, strength));
                assertBalanced(LanczosScale.resampleFragmentShader(kernel, strength, strength, strength));
            }
        }
    }

    private static void assertBalanced(String shader) {
        int parens = 0, braces = 0;
        for (int i = 0; i < shader.length(); i++) {
            char c = shader.charAt(i);
            if (c == '(') parens++;
            if (c == ')') parens--;
            if (c == '{') braces++;
            if (c == '}') braces--;
            assertTrue("unbalanced delimiter at " + i + " in " + shader, parens >= 0 && braces >= 0);
        }
        assertEquals("unbalanced () in " + shader, 0, parens);
        assertEquals("unbalanced {} in " + shader, 0, braces);
    }
}
