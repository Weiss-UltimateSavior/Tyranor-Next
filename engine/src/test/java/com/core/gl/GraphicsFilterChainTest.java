package com.core.gl;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class GraphicsFilterChainTest {
    @Test public void builderIsImmutableAndPreservesCanonicalWireVersions() {
        List<GraphicsFilterChain.Step> steps = new ArrayList<>();
        steps.add(new GraphicsFilterChain.Step("s", true));
        steps.add(new GraphicsFilterChain.Step("lanczos", false));
        steps.add(new GraphicsFilterChain.Step("s", true));
        GraphicsFilterChain chain = GraphicsFilterChain.of(steps);
        steps.clear();
        assertEquals("v1:s,!lanczos,s", chain.encode());
        assertEquals(3, chain.steps().size());
        assertThrows(UnsupportedOperationException.class, () -> chain.steps().clear());

        GraphicsFilterChain parameters = GraphicsFilterChain.of(List.of(
                new GraphicsFilterChain.Step("lanczos", true,
                        GraphicsFilterChain.defaultCasSharpness("lanczos"),
                        GraphicsFilterChain.defaultBicubicB("lanczos"),
                        GraphicsFilterChain.defaultBicubicC("lanczos"),
                        0.4, "factor", 1.5, 1.5)));
        assertEquals("v2:lanczos~0.4~factor~1.5~1.5", parameters.encode());
        assertEquals(parameters.encode(), GraphicsFilterChain.parse(parameters.encode()).encode());
        assertEquals("v1:", GraphicsFilterChain.of(List.of()).encode());
    }
    @Test public void builderRejectsInvalidListsRatherThanDroppingNodes() {
        assertThrows(IllegalArgumentException.class, () -> GraphicsFilterChain.of(null));
        assertThrows(IllegalArgumentException.class,
                () -> GraphicsFilterChain.of(Arrays.asList((GraphicsFilterChain.Step) null)));
        GraphicsFilterChain.Step step = new GraphicsFilterChain.Step("s", true);
        assertThrows(IllegalArgumentException.class,
                () -> GraphicsFilterChain.of(Collections.nCopies(GraphicsFilterChain.MAX_STEPS + 1, step)));
        assertEquals(GraphicsFilterChain.MAX_STEPS, GraphicsFilterChain.of(
                Collections.nCopies(GraphicsFilterChain.MAX_STEPS, step)).steps().size());
    }
    @Test public void legacyStepConstructorValidatesSharpnessForNonAdjustableFilters() {
        new GraphicsFilterChain.Step("s", true, GraphicsFilterChain.defaultCasSharpness("s"), "none", 1.0, 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> new GraphicsFilterChain.Step("s", true, 0.9, "none", 1.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new GraphicsFilterChain.Step("s", true, Double.NaN, "none", 1.0, 1.0));
    }
    @Test public void assetPathsAreFullAndCannotEscapeBundledResources() {
        assertTrue(GraphicsFilterDefinitions.isValidAssetPath("anime4k/Anime4K_Restore_CNN_S.glsl"));
        assertTrue(GraphicsFilterDefinitions.isValidAssetPath("filter/Example.glsl"));
        for (String bad : new String[]{null, "", "Anime4K_Restore_CNN_S", "../shader.glsl",
                "anime4k/../shader.glsl", "/shader.glsl", "anime4k\\shader.glsl",
                "C:/shader.glsl", "anime4k/shader.glsl.bak", "anime4k//shader.glsl"}) {
            assertFalse(String.valueOf(bad), GraphicsFilterDefinitions.isValidAssetPath(bad));
        }
        assertThrows(UnsupportedOperationException.class, () -> GraphicsFilterDefinitions.all().clear());
    }
    @Test public void preservesOrderDuplicatesAndPausedStepsThroughRoundTrip() {
        GraphicsFilterChain chain = GraphicsFilterChain.parse("v1:s,!deblur,lanczos,s");
        assertNotNull(chain);
        assertEquals("v1:s,!deblur,lanczos,s", chain.encode());
        assertEquals(4, chain.steps().size());
        assertFalse(chain.steps().get(1).enabled);
        assertEquals("lanczos", chain.steps().get(2).id);
        assertTrue(chain.hasActive());
    }
    @Test public void rejectsTheWholeUnknownOrMalformedProtocol() {
        for (String bad : new String[]{null, "s", "v3:s", "v1:unknown,s", "v1:!!s", "v1:s,", "v1: s", "v1:s,,lanczos"}) {
            assertNull(String.valueOf(bad), GraphicsFilterChain.parse(bad));
        }
        assertNull(GraphicsFilterChain.parse("v1:s,s,s,s,s,s,s,s,s"));
        assertNotNull(GraphicsFilterChain.parse("v1:s,s,s,s,s,s,s,s"));
    }
    @Test public void emptyAndPausedChainsKeepTheirConfigurationWithoutActivation() {
        assertFalse(GraphicsFilterChain.parse("v1:").hasActive());
        GraphicsFilterChain paused = GraphicsFilterChain.parse("v1:!s,!lanczos");
        assertFalse(paused.hasActive());
        assertEquals("v1:!s,!lanczos", paused.encode());
        assertEquals("v1:soft_m", GraphicsFilterChain.fromLegacy("soft_m").encode());
        assertEquals("v1:", GraphicsFilterChain.fromLegacy("off").encode());
        assertThrows(UnsupportedOperationException.class, () -> paused.steps().clear());
    }
    @Test public void vnsrNativeReconstructionPrecedesDisplayResampling() {
        GraphicsFilterPlan plan = GraphicsFilterPlan.create(
                GraphicsFilterChain.parse("v1:vnsr_c16_x2,lanczos"), 1920, 1080, 1920, 1080);
        assertEquals(3840, plan.outputWidths[0]);
        assertEquals(2160, plan.outputHeights[0]);
        assertEquals(1920, plan.outputWidths[1]);
        assertEquals(1080, plan.outputHeights[1]);
        assertNull(GraphicsFilterChain.parse("v1:vnsr_c24_x2"));
        assertNull(GraphicsFilterChain.parse("v1:vnsr_c32_x2"));
    }
    @Test public void orderedGeometryRestoresAfterScaleAndSkipsRepeatedIdentityScale() {
        GraphicsFilterPlan plan = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:lanczos,s,lanczos,!deblur"),
                1280, 720, 2560, 1600);
        assertTrue(plan.execute[0]); assertEquals(1280, plan.inputWidths[0]); assertEquals(2560, plan.outputWidths[0]);
        assertTrue(plan.execute[1]); assertEquals(2560, plan.inputWidths[1]); assertEquals(1440, plan.inputHeights[1]);
        assertFalse(plan.execute[2]); assertFalse(plan.execute[3]);
        GraphicsFilterPlan reverse = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:s,lanczos"),
                1280, 720, 2560, 1600);
        assertEquals(1280, reverse.inputWidths[0]); assertEquals(1280, reverse.inputWidths[1]);
    }
    @Test public void scaleAloneDoesNotInsertRestoreAndSmallerDisplaysUseExplicitFit() {
        GraphicsFilterPlan alone = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:lanczos"),
                1600, 900, 2400, 1200);
        assertEquals(1, alone.execute.length); assertTrue(alone.execute[0]);
        assertEquals(2133, alone.outputWidths[0]); assertEquals(1200, alone.outputHeights[0]);
        assertEquals(240000L, LanczosScale.estimatedOwnedBytes(100, 100, 200, 200, 0, 0, false, false));
        GraphicsFilterPlan smaller = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:lanczos,s"),
                1280, 720, 800, 600);
        assertEquals(800, smaller.fitted.width); assertEquals(450, smaller.fitted.height);
        assertTrue(smaller.execute[0]); assertTrue(smaller.execute[1]);
        assertEquals(800, smaller.outputWidths[1]); assertEquals(450, smaller.outputHeights[1]);
    }
    @Test public void customScalePlansUseCurrentInputAndScreenFitBeforeFactors() {
        GraphicsFilterPlan fit = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v2:lanczos~0.4~fit~1.25~0.5"),
                1600, 900, 2608, 1200);
        assertEquals(2666, fit.outputWidths[0]); assertEquals(600, fit.outputHeights[0]);
        GraphicsFilterPlan factor = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v2:lanczos~0.4~factor~0.5~1.25"),
                1600, 900, 2608, 1200);
        assertEquals(800, factor.outputWidths[0]); assertEquals(1125, factor.outputHeights[0]);
        GraphicsFilterPlan tiny = GraphicsFilterPlan.create(GraphicsFilterChain.parse("v2:lanczos~0.4~factor~0.25~0.25"), 1, 1, 100, 100);
        assertEquals(1, tiny.outputWidths[0]); assertEquals(1, tiny.outputHeights[0]);
    }
    @Test public void completeChainBudgetSumsAllNodesAndSaturatesOverflow() {
        long oneL = LanczosScale.estimatedOwnedBytes(1920, 1080, 1920, 1080, 8, 0, false);
        long twoL = GraphicsFilterPipeline.addBytes(oneL, oneL);
        assertTrue(oneL < Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES);
        assertTrue(twoL > Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES);
        assertEquals(Long.MAX_VALUE, GraphicsFilterPipeline.addBytes(Long.MAX_VALUE, 1));
        assertEquals(8294400L, GraphicsFilterPipeline.ownedSurfaceBytes(1920, 1080));
    }
    @Test public void scalePresetDefinitionsCarryKernelsAndStayWireCompatible() {
        GraphicsFilterDefinition lanczos = GraphicsFilterDefinitions.find("lanczos");
        assertTrue(lanczos.sharpnessAdjustable);
        assertEquals(0.5, lanczos.defaultSharpness, 0);
        assertEquals("lanczos2", lanczos.resampleKernel);
        GraphicsFilterDefinition bilinear = GraphicsFilterDefinitions.find("bilinear");
        assertEquals("bilinear", bilinear.resampleKernel);
        assertFalse(bilinear.sharpnessAdjustable);
        assertTrue(bilinear.visible);
        GraphicsFilterDefinition bicubic = GraphicsFilterDefinitions.find("bicubic");
        assertEquals("bicubic", bicubic.resampleKernel);
        assertTrue(bicubic.sharpnessAdjustable);
        assertEquals(0.33, bicubic.defaultSharpness, 0);
        // Menu order follows performance cost: bilinear -> bicubic -> lanczos.
        assertTrue(bilinear.categoryOrder < bicubic.categoryOrder);
        assertTrue(bicubic.categoryOrder < lanczos.categoryOrder);
        // Pure resample presets reuse the whole fit/factor planning path of lanczos.
        GraphicsFilterPlan lanczosPlan = GraphicsFilterPlan.create(
                GraphicsFilterChain.parse("v1:lanczos"), 1600, 900, 2400, 1200);
        GraphicsFilterPlan bilinearPlan = GraphicsFilterPlan.create(
                GraphicsFilterChain.parse("v1:bilinear"), 1600, 900, 2400, 1200);
        GraphicsFilterPlan bicubicPlan = GraphicsFilterPlan.create(
                GraphicsFilterChain.parse("v1:bicubic"), 1600, 900, 2400, 1200);
        assertEquals(lanczosPlan.outputWidths[0], bilinearPlan.outputWidths[0]);
        assertEquals(lanczosPlan.outputHeights[0], bicubicPlan.outputHeights[0]);
        assertTrue(bilinearPlan.execute[0]);
        assertTrue(bicubicPlan.execute[0]);
        // Resampling at 1:1 geometry has nothing to compute: every scale-only preset skips it
        // (its kernel would only smooth, not act); shader filters keep running their effect.
        assertFalse(GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:bicubic"), 1920, 1080, 1920, 1080).execute[0]);
        assertFalse(GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:lanczos"), 1920, 1080, 1920, 1080).execute[0]);
        // Shader filters with their own in-place effect (CAS, incl. the hidden cas_scale_* family)
        // must keep running at identity — their scaling parameter can still change the image.
        assertTrue(GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:cas"), 1920, 1080, 1920, 1080).execute[0]);
        assertTrue(GraphicsFilterPlan.create(GraphicsFilterChain.parse("v1:cas_scale_standard"), 1920, 1080, 1920, 1080).execute[0]);
        // Wire round trips carry the adjustable parameters (bicubic B/C / lanczos anti-ringing).
        assertEquals("v2:bicubic~0.5~0.25~fit~1.0~1.0",
                GraphicsFilterChain.parse("v2:bicubic~0.5~0.25~fit~1.0~1.0").encode());
        // Legacy 5-token bicubic wire does not migrate sharpness to B/C; defaults apply.
        assertEquals("v1:bicubic",
                GraphicsFilterChain.parse("v2:bicubic~0.8~fit~1.0~1.0").encode());
        assertEquals("v2:lanczos~0.7~fit~1.0~1.0",
                GraphicsFilterChain.parse("v2:lanczos~0.7~fit~1.0~1.0").encode());
        assertEquals("v1:", GraphicsFilterChain.fromLegacy("lanczos").encode());
    }
}
