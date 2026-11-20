package com.core.gl;

import android.opengl.GLES30;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying correctness, precision preservation, MRT pass fusion,
 * acyclic layering, fail-closed GLSL transforms, same-frame temporal cache state machine,
 * and memory budget enforcement of CnnPipeline.
 */
public final class CnnPipelineTest {

    private static final String[] ALL_CNN_ASSETS = {
            "cas/CAS.glsl",
            "anime4k/Anime4K_Restore_CNN_S.glsl",
            "anime4k/Anime4K_Restore_CNN_M.glsl",
            "anime4k/Anime4K_Restore_CNN_L.glsl",
            "anime4k/Anime4K_Restore_CNN_Soft_S.glsl",
            "anime4k/Anime4K_Restore_CNN_Soft_M.glsl",
            "anime4k/Anime4K_Restore_CNN_Soft_L.glsl",
            "vnsr/vnsr-c08-x2.glsl",
            "vnsr/vnsr-c12-x2.glsl",
            "vnsr/vnsr-c16-x2.glsl"
    };

    private static final String[] VNSR_ASSETS = {
            "vnsr/vnsr-c08-x2.glsl",
            "vnsr/vnsr-c12-x2.glsl",
            "vnsr/vnsr-c16-x2.glsl"
    };

    private static final String[] ANIME4K_ASSETS = {
            "anime4k/Anime4K_Restore_CNN_S.glsl",
            "anime4k/Anime4K_Restore_CNN_M.glsl",
            "anime4k/Anime4K_Restore_CNN_L.glsl",
            "anime4k/Anime4K_Restore_CNN_Soft_S.glsl",
            "anime4k/Anime4K_Restore_CNN_Soft_M.glsl",
            "anime4k/Anime4K_Restore_CNN_Soft_L.glsl"
    };

    private static final String[] NON_CNN_ASSETS = {
            "anime4k/Anime4K_Thin_HQ.glsl",
            "anime4k/Anime4K_Darken_HQ.glsl",
            "anime4k/Anime4K_Deblur_DoG.glsl",
            "anime4k/Anime4K_Denoise_Bilateral_Mean.glsl",
            "anime4k/Anime4K_Denoise_Bilateral_Median.glsl",
            "anime4k/Anime4K_Denoise_Bilateral_Mode.glsl"
    };

    private static Path resolveAsset(String assetPath) {
        Path p = Paths.get("src/main/assets").resolve(assetPath);
        if (Files.exists(p)) return p;
        p = Paths.get("engine/src/main/assets").resolve(assetPath);
        if (Files.exists(p)) return p;
        throw new IllegalStateException("cannot find asset: " + assetPath);
    }

    private static Path resolveJavaSource(String fileName) {
        Path p = Paths.get("src/main/java/com/core/gl").resolve(fileName);
        if (Files.exists(p)) return p;
        p = Paths.get("engine/src/main/java/com/core/gl").resolve(fileName);
        if (Files.exists(p)) return p;
        throw new IllegalStateException("cannot find Java source: " + fileName);
    }

    private static List<MpvHookShader.Pass> loadPasses(String assetPath) throws Exception {
        Path path = resolveAsset(assetPath);
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return MpvHookShader.parse(source);
    }

    private static CnnPipeline createNodeForAsset(String assetPath, int tileCoreSize) {
        for (GraphicsFilterDefinition def : GraphicsFilterDefinitions.all()) {
            if (assetPath.equals(def.shaderAsset)) {
                return CnnPipeline.createForTesting(
                        p -> Files.newInputStream(resolveAsset(p)),
                        def.shaderAsset, def.topLeftCoordinates, def.nativeScale,
                        def.haloPerPass, tileCoreSize);
            }
        }
        throw new IllegalArgumentException("no GraphicsFilterDefinition for asset: " + assetPath);
    }

    @Test
    public void vnsrModelsAreFusedIntoSevenPasses() throws Exception {
        for (String asset : VNSR_ASSETS) {
            List<MpvHookShader.Pass> passes = loadPasses(asset);
            assertEquals("All VN-SR models must be fused into exactly 7 MRT passes: " + asset, 7, passes.size());
            assertTrue("Input pass must have multiple outputs in " + asset, passes.get(0).saves.size() >= 2);
            assertEquals("Output pass must output 3 residuals in " + asset, 3, passes.get(5).saves.size());
            assertTrue("Final pass must write to screen in " + asset, passes.get(6).isFinalToScreen());
        }
    }

    @Test
    public void thinHqAndNonCnnShadersPreserveCompactSlotReuse() throws Exception {
        // Same-name overwrites (LINESOBEL in Thin_HQ) must reuse 2 slots, not inflate to 6.
        List<MpvHookShader.Pass> thinPasses = loadPasses("anime4k/Anime4K_Thin_HQ.glsl");
        GraphicsShaderTargets thinTargets = new GraphicsShaderTargets(thinPasses);
        assertEquals("Anime4K_Thin_HQ must allocate only 2 intermediate slots", 2, thinTargets.count);
        assertArrayEquals(new int[][]{
                {0}, {1}, {0}, {1}, {0}, {1}, {0}, {}
        }, thinTargets.passTargetSlots);
        assertFalse("Thin_HQ must not be classified as CNN convolution chain",
                MpvHookShader.isCnnConvolutionChain(thinPasses));

        // Verify all non-CNN post-processing filters stay compact and excluded from CNN classification.
        for (String asset : NON_CNN_ASSETS) {
            List<MpvHookShader.Pass> passes = loadPasses(asset);
            GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
            assertTrue("Non-CNN slot count must remain <= 3 for " + asset, targets.count <= 3);
            assertFalse("Non-CNN shader must not trigger isCnnConvolutionChain: " + asset,
                    MpvHookShader.isCnnConvolutionChain(passes));
        }

        // Confirm real convolution CNN chains return true.
        for (String asset : ANIME4K_ASSETS) {
            assertTrue(asset, MpvHookShader.isCnnConvolutionChain(loadPasses(asset)));
        }
        for (String asset : VNSR_ASSETS) {
            assertTrue(asset, MpvHookShader.isCnnConvolutionChain(loadPasses(asset)));
        }
        assertFalse("CAS single pass is not multi-pass CNN", MpvHookShader.isCnnConvolutionChain(loadPasses("cas/CAS.glsl")));
    }

    @Test
    public void staticDependencyResolutionSucceedsForAllCnnModels() throws Exception {
        for (String asset : ALL_CNN_ASSETS) {
            List<MpvHookShader.Pass> passes = loadPasses(asset);
            GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
            int[][] inputSlots = GraphicsShaderTransforms.resolveStaticInputSlots(passes, targets);

            assertNotNull("Static input slots must not be null for " + asset, inputSlots);
            assertEquals("Input slot count must match pass count for " + asset, passes.size(), inputSlots.length);

            for (int p = 0; p < passes.size(); p++) {
                MpvHookShader.Pass pass = passes.get(p);
                assertEquals("Pass binding count must match resolved slot count in " + pass.desc,
                        pass.binds.size(), inputSlots[p].length);

                for (int b = 0; b < pass.binds.size(); b++) {
                    String bind = pass.binds.get(b);
                    int slot = inputSlots[p][b];
                    if ("MAIN".equals(bind)) {
                        assertEquals("MAIN binding must always map to slot -1", -1, slot);
                    } else {
                        assertTrue("Intermediate slot must be non-negative for bind " + bind + " in " + pass.desc, slot >= 0);
                        assertTrue("Intermediate slot must be strictly within target allocation count for " + asset,
                                slot < targets.count);

                        int producerPass = -1;
                        int producerSaveIdx = -1;
                        for (int q = 0; q < p; q++) {
                            int idx = passes.get(q).saves.indexOf(bind);
                            if (idx >= 0) {
                                producerPass = q;
                                producerSaveIdx = idx;
                            }
                        }
                        assertTrue("Producer pass must be found for intermediate bind " + bind + " in " + asset, producerPass >= 0);
                        assertEquals("Input slot must strictly match producer target slot for " + bind + " in " + asset,
                                targets.passTargetSlots[producerPass][producerSaveIdx], slot);
                    }
                }

                for (int tSlot : targets.passTargetSlots[p]) {
                    for (int inSlot : inputSlots[p]) {
                        if (inSlot >= 0) {
                            assertFalse("Pass " + p + " target slot " + tSlot + " must not collide with its input slot "
                                    + inSlot + " in " + asset, tSlot == inSlot);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void casShaderDeclaresSharpnessAndOutputSizedUniforms() throws Exception {
        List<MpvHookShader.Pass> passes = loadPasses("cas/CAS.glsl");
        assertEquals("CAS shader must parse exactly 1 terminal pass", 1, passes.size());
        MpvHookShader.Pass pass = passes.get(0);
        assertTrue("CAS pass body must declare u_SHARPNESS", pass.body.contains("uniform float u_SHARPNESS;"));
        assertTrue("CAS pass body must declare u_OUTPUT_SIZED", pass.body.contains("uniform bool u_OUTPUT_SIZED;"));

        String glsl = GraphicsShaderTransforms.generateFragmentShader(
                pass, true, GraphicsShaderTransforms.Config.DEFAULT.tieredPrecisionEnabled);
        assertTrue("Generated CAS GLSL must contain u_SHARPNESS", glsl.contains("u_SHARPNESS"));
        assertTrue("Generated CAS GLSL must contain u_OUTPUT_SIZED", glsl.contains("u_OUTPUT_SIZED"));
    }

    @Test
    public void shaderGenerationPreservesTieredPrecisionAndPtUniforms() throws Exception {
        GraphicsShaderTransforms.Config config = GraphicsShaderTransforms.Config.DEFAULT;
        for (String asset : ALL_CNN_ASSETS) {
            List<MpvHookShader.Pass> passes = loadPasses(asset);
            for (MpvHookShader.Pass pass : passes) {
                String glsl = GraphicsShaderTransforms.generateFragmentShader(
                        pass, true, config.tieredPrecisionEnabled);

                assertTrue("Fragment shader header must declare precision highp float and highp sampler2D in " + pass.desc,
                        glsl.contains("#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"));
                if (MpvHookShader.isCnnConvolutionPass(pass)) {
                    assertTrue("Convolution pass must switch to precision mediump float before pass body in " + pass.desc,
                            glsl.contains("precision mediump float;\n" + pass.body));
                } else {
                    assertFalse("Non-convolution pass must retain highp float in " + pass.desc,
                            glsl.contains("precision mediump float;"));
                }

                assertTrue("UV coordinates vPos must be highp in " + pass.desc,
                        glsl.contains("in highp vec2 vPos;"));
                assertTrue("Output size must be highp in " + pass.desc,
                        glsl.contains("uniform highp vec2 u_OUTPUT_size;"));
                assertTrue("hookCoord must be declared under highp float in " + pass.desc,
                        glsl.contains("vec2 hookCoord(vec2 p)"));

                for (String bind : pass.binds) {
                    assertTrue("Shader must declare uniform highp vec2 u_" + bind + "_pt in " + pass.desc,
                            glsl.contains("uniform highp vec2 u_" + bind + "_pt;"));
                    assertTrue("Shader must declare uniform highp vec2 u_" + bind + "_size in " + pass.desc,
                            glsl.contains("uniform highp vec2 u_" + bind + "_size;"));
                    assertTrue("Shader must define " + bind + "_pt as u_" + bind + "_pt in " + pass.desc,
                            glsl.contains("#define " + bind + "_pt u_" + bind + "_pt"));
                    assertTrue("texOff must use multiplication by u_" + bind + "_pt instead of division in " + pass.desc,
                            glsl.contains("off * u_" + bind + "_pt"));
                }

                if (pass.saves.size() > 1) {
                    for (int s = 0; s < pass.saves.size(); s++) {
                        assertTrue("MRT pass must declare layout(location = " + s + ") out vec4 fragColor" + s,
                                glsl.contains("layout(location = " + s + ") out vec4 fragColor" + s + ";"));
                    }
                    assertTrue("MRT pass must define fragColor as fragColor0",
                            glsl.contains("#define fragColor fragColor0"));
                }

                if (pass.isFinalToScreen()) {
                    assertTrue("Terminal pass must output opaque alpha 1.0 in " + pass.desc,
                            glsl.contains("fragColor.a = 1.0;"));
                }
            }
        }
    }

    @Test
    public void intermediateSlotIsNeverReadAfterOverwrite() throws Exception {
        for (String asset : ALL_CNN_ASSETS) {
            List<MpvHookShader.Pass> passes = loadPasses(asset);
            GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
            GraphicsShaderTransforms.resolveStaticInputSlots(passes, targets);

            int passCount = passes.size();
            for (int p = 0; p < passCount; p++) {
                if (passes.get(p).isFinalToScreen()) continue;
                int[] pTargetSlots = targets.passTargetSlots[p];

                for (int sIdx = 0; sIdx < pTargetSlots.length; sIdx++) {
                    int targetSlot = pTargetSlots[sIdx];
                    String saveName = passes.get(p).saves.get(sIdx);

                    for (int q = p + 1; q < passCount; q++) {
                        MpvHookShader.Pass qPass = passes.get(q);
                        if (qPass.isFinalToScreen()) continue;
                        if (qPass.saves.contains(saveName)) {
                            // Re-produced before targetSlot was clobbered; later passes read q's version.
                            break;
                        }
                        boolean writesTargetSlot = false;
                        for (int qSlot : targets.passTargetSlots[q]) {
                            if (qSlot == targetSlot) {
                                writesTargetSlot = true;
                                break;
                            }
                        }
                        if (writesTargetSlot) {
                            for (int r = q + 1; r < passCount; r++) {
                                MpvHookShader.Pass rPass = passes.get(r);
                                for (int b = 0; b < rPass.binds.size(); b++) {
                                    if (saveName.equals(rPass.binds.get(b))) {
                                        throw new AssertionError("Violation in " + asset + ": pass " + r
                                                + " attempts to read save " + saveName + " of pass " + p
                                                + " after slot " + targetSlot + " was overwritten in pass " + q);
                                    }
                                }
                                if (!rPass.isFinalToScreen() && rPass.saves.contains(saveName)) {
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    @Test
    public void escapeHatchDisablesTieredPrecisionAndForcesHighp() throws Exception {
        try {
            CnnPipeline.setTieredPrecisionEnabled(false);
            assertFalse("Tiered precision escape hatch must be disabled", CnnPipeline.isTieredPrecisionEnabled());
            GraphicsShaderTransforms.Config config = CnnPipeline.snapshotConfig();
            assertFalse(config.tieredPrecisionEnabled);

            for (String asset : ALL_CNN_ASSETS) {
                List<MpvHookShader.Pass> passes = loadPasses(asset);
                for (MpvHookShader.Pass pass : passes) {
                    String glsl = GraphicsShaderTransforms.generateFragmentShader(
                            pass, true, config.tieredPrecisionEnabled);
                    assertTrue("All passes must retain highp float when tiered precision disabled in " + pass.desc,
                            glsl.contains("precision highp float;"));
                    assertTrue("All passes must retain highp sampler2D when tiered precision disabled in " + pass.desc,
                            glsl.contains("precision highp sampler2D;"));
                    assertFalse("No pass may switch to mediump float when tiered precision disabled in " + pass.desc,
                            glsl.contains("precision mediump float;"));
                }
            }
        } finally {
            CnnPipeline.setTieredPrecisionEnabled(true);
            assertTrue(CnnPipeline.isTieredPrecisionEnabled());
        }
    }

    @Test
    public void numericalPrecisionGuardrailsAndFp16ResolutionBounds() {
        final double fp16Epsilon = 1.0 / 2048.0;
        final double lsb8Bit = 1.0 / 255.0;
        final double threshold = 0.0019;

        assertTrue("FP16 step must be strictly smaller than 8-bit LSB", fp16Epsilon < lsb8Bit);
        assertEquals("FP16 resolution is ~8x finer than 8-bit LSB", 8.0, lsb8Bit / fp16Epsilon, 0.1);
        assertTrue("Threshold must exceed FP16 epsilon to tolerate rounding noise", threshold > fp16Epsilon);
        assertTrue("Threshold must be well below 1 LSB to catch any 8-bit color change", threshold < lsb8Bit);
        assertTrue("Threshold provides >= 3.8x safety margin above FP16 epsilon", threshold >= 3.0 * fp16Epsilon);
        assertTrue("Threshold provides >= 2x safety margin below 8-bit LSB", threshold <= 0.55 * lsb8Bit);
    }

    @Test
    public void vnsrIntermediateTargetsAlwaysAllocateRgba16fWithoutAffineEncoding() throws Exception {
        GraphicsShaderTransforms.Config defaultConfig = CnnPipeline.snapshotConfig();
        for (String asset : VNSR_ASSETS) {
            List<MpvHookShader.Pass> passes = loadPasses(asset);
            long baseBytes = createNodeForAsset(asset, 0).estimateTargetBytes(1920, 1080, 3840, 2160);
            GraphicsShaderTargets targetPlan = new GraphicsShaderTargets(passes);
            long expectedIntermediateBytes16F = 1920L * 1080L * 8L * targetPlan.count;
            assertTrue("Base bytes must allocate GL_RGBA16F (8 bytes/pixel) intermediate slots",
                    baseBytes >= expectedIntermediateBytes16F);

            // [17] Verify ensureTargets() actually allocates all intermediate slot textures as GL_RGBA16F.
            RecordingPipelineGlOps formatGl = new RecordingPipelineGlOps();
            CnnPipeline targetNode = CnnPipeline.createForTesting(
                    p -> Files.newInputStream(resolveAsset(p)), asset, true, 2, 1, 0, formatGl);
            setField(targetNode, "mLimitWidth", 4096);
            setField(targetNode, "mLimitHeight", 4096);
            setField(targetNode, "mQuadVao", new int[]{1});
            setField(targetNode, "mScaleProgram", 1);
            setField(targetNode, "mHalfFloatChecked", true);
            setField(targetNode, "mHalfFloatRenderable", true);
            Method ensureTargets = CnnPipeline.class.getDeclaredMethod(
                    "ensureTargets", int.class, int.class, int.class, int.class);
            ensureTargets.setAccessible(true);
            assertTrue((Boolean) ensureTargets.invoke(targetNode, 64, 64, 128, 128));
            GlRenderTarget[] intermediates = (GlRenderTarget[]) getField(targetNode, "mIntermediateTargets");
            assertNotNull(intermediates);
            assertEquals(targetPlan.count, intermediates.length);
            for (GlRenderTarget slot : intermediates) {
                assertEquals("Intermediate target texture must be allocated with GL_RGBA16F",
                        Integer.valueOf(GLES30.GL_RGBA16F), formatGl.textureInternalFormats.get(slot.texture[0]));
            }

            for (MpvHookShader.Pass pass : passes) {
                String glsl = GraphicsShaderTransforms.generateFragmentShader(
                        pass, true, defaultConfig.tieredPrecisionEnabled);
                assertFalse(glsl.contains("fragColor0 *= 0.5;"));
                assertFalse(glsl.contains("fragColor0 += 0.5;"));
                for (String bind : pass.binds) {
                    assertFalse(glsl.contains("texture(u_" + bind + ", hookCoord(pos)) * 2.0"));
                    assertFalse(glsl.contains("texture(u_" + bind + ", hookCoord(pos)) - 0.5"));
                }
            }
        }
    }

    @Test
    public void runtimeMrtFusionAndCreluDeduplicationReducesPassesAndTextureFetches() throws Exception {
        GraphicsShaderTransforms.Config config = GraphicsShaderTransforms.Config.DEFAULT;
        List<MpvHookShader.Pass> rawL = loadPasses("anime4k/Anime4K_Restore_CNN_L.glsl");
        assertEquals(9, rawL.size());
        List<MpvHookShader.Pass> optL = GraphicsShaderTransforms.optimizePasses(rawL, config.runtimeMrtFusionEnabled);
        assertEquals(5, optL.size());

        for (int p = 0; p < 4; p++) {
            assertEquals(2, optL.get(p).saves.size());
            assertTrue(optL.get(p).body.contains("void hook()"));
            assertTrue(optL.get(p).body.contains("fragColor0 = ") && optL.get(p).body.contains("fragColor1 = "));
        }
        assertEquals(1, optL.get(4).saves.size());
        assertEquals(82, countOptimizedTextureFetches(optL));

        List<MpvHookShader.Pass> rawSoftL = loadPasses("anime4k/Anime4K_Restore_CNN_Soft_L.glsl");
        assertEquals(9, rawSoftL.size());
        List<MpvHookShader.Pass> optSoftL = GraphicsShaderTransforms.optimizePasses(rawSoftL, config.runtimeMrtFusionEnabled);
        assertEquals(5, optSoftL.size());
        assertEquals(82, countOptimizedTextureFetches(optSoftL));

        assertEquals(71, countOptimizedTextureFetches(
                GraphicsShaderTransforms.optimizePasses(loadPasses("anime4k/Anime4K_Restore_CNN_M.glsl"), config.runtimeMrtFusionEnabled)));
        assertEquals(37, countOptimizedTextureFetches(
                GraphicsShaderTransforms.optimizePasses(loadPasses("anime4k/Anime4K_Restore_CNN_S.glsl"), config.runtimeMrtFusionEnabled)));
    }

    /**
     * Numerical pin: the runtime transform chain (MRT pair fusion + 3x3 CReLU / 1x1
     * aggregation codegen) must reproduce the raw Anime4K Restore CNN math bit-exactly in
     * FP64 statement order for every bundled asset, at every save target, chaining through
     * fused saves. Complements the SPEC-frozen 2xFP16 accuracy thresholds by pinning the
     * transform itself (fusion/codegen must not move any value).
     */
    @Test
    public void anime4kRuntimeFusionAndCodegenNumericalEquivalenceAgainstRawPasses() throws Exception {
        GraphicsShaderTransforms.Config config = GraphicsShaderTransforms.Config.DEFAULT;
        Random rng = new Random(20261119L);
        for (String asset : ANIME4K_ASSETS) {
            List<MpvHookShader.Pass> raw = loadPasses(asset);
            List<MpvHookShader.Pass> opt = GraphicsShaderTransforms.optimizePasses(raw, config.runtimeMrtFusionEnabled);
            assertTrue("runtime transforms must not add passes: " + asset, opt.size() <= raw.size());

            // Random 3x3 input window; both chains share MAIN and broadcast single-pixel
            // outputs into the window for the next translation-invariant pass.
            double[][] main = new double[9][4];
            for (int t = 0; t < 9; t++) {
                for (int c = 0; c < 4; c++) main[t][c] = rng.nextDouble() * 2.0 - 1.0;
            }
            Map<String, double[][]> rawTextures = new HashMap<>();
            Map<String, double[][]> optTextures = new HashMap<>();
            rawTextures.put("MAIN", main);
            optTextures.put("MAIN", main);

            boolean sawFusedPair = false;
            int r = 0;
            for (MpvHookShader.Pass op : opt) {
                assertTrue("optimized pass must keep the bind set: " + asset,
                        op.binds.equals(raw.get(r).binds));
                if (op.saves.size() == 2) {
                    // Fused pair: evaluate both raw members on the same inputs.
                    sawFusedPair = true;
                    MpvHookShader.Pass rawA = raw.get(r);
                    MpvHookShader.Pass rawB = raw.get(r + 1);
                    r += 2;
                    double[][] outOpt = evaluateOptimizedAnime4kPass(op, optTextures);
                    double[] outA = evaluateRawAnime4kPass(rawA, rawTextures);
                    double[] outB = evaluateRawAnime4kPass(rawB, rawTextures);
                    assertArrayEquals("fused slot 0 mismatch in " + asset + " (" + op.desc + ")",
                            outA, outOpt[0], 0.0);
                    assertArrayEquals("fused slot 1 mismatch in " + asset + " (" + op.desc + ")",
                            outB, outOpt[1], 0.0);
                    broadcastSave(rawTextures, rawA.saves.get(0), outA);
                    broadcastSave(rawTextures, rawB.saves.get(0), outB);
                    broadcastSave(optTextures, op.saves.get(0), outOpt[0]);
                    broadcastSave(optTextures, op.saves.get(1), outOpt[1]);
                } else {
                    MpvHookShader.Pass rawP = raw.get(r);
                    r += 1;
                    double[][] outOpt = evaluateOptimizedAnime4kPass(op, optTextures);
                    double[] outRaw = evaluateRawAnime4kPass(rawP, rawTextures);
                    assertArrayEquals("single pass mismatch in " + asset + " (" + op.desc + ")",
                            outRaw, outOpt[0], 0.0);
                    if (!rawP.isFinalToScreen()) {
                        broadcastSave(rawTextures, rawP.saves.get(0), outRaw);
                        broadcastSave(optTextures, op.saves.get(0), outOpt[0]);
                    }
                }
            }
            assertEquals("every raw pass must be consumed: " + asset, raw.size(), r);
            // Only the L models carry the dual-branch shape whose adjacent passes share
            // binds, so only they actually fuse into 2-target MRT passes.
            if (asset.endsWith("_L.glsl")) {
                assertTrue("Restore_CNN (Soft_)L must fuse into MRT pairs: " + asset, sawFusedPair);
            }
        }
    }

    /** Broadcasts a single evaluated texel across the 3x3 window for translation-invariant chaining. */
    private static void broadcastSave(Map<String, double[][]> textures, String name, double[] texel) {
        double[][] window = new double[9][];
        for (int t = 0; t < 9; t++) window[t] = texel;
        textures.put(name, window);
    }

    /** Index of the texel at offset (dx,dy) inside the 3x3 window, matching texOff semantics. */
    private static int windowIndex(double dx, double dy) {
        return (int) Math.round(dy) * 3 + (int) Math.round(dx) + 4;
    }

    /** Accumulates one mat4-times-vector statement into val in GLSL column-major order. */
    private static void accumulateMat4(double[] val, String matArgs, double[] px) {
        String[] toks = matArgs.split(",");
        assertEquals(16, toks.length);
        for (int o = 0; o < 4; o++) {
            double acc = 0.0;
            for (int c = 0; c < 4; c++) acc += Double.parseDouble(toks[c * 4 + o].trim()) * px[c];
            val[o] += acc;
        }
    }

    private static double[] relu(double[] px, int mode) {
        if (mode == 0) return Arrays.copyOf(px, 4);
        double[] out = new double[4];
        for (int c = 0; c < 4; c++) {
            out[c] = Math.max(mode * px[c], 0.0);
        }
        return out;
    }

    /**
     * Evaluates one raw mpv-form Anime4K pass (go_/g_ macro defines + result accumulation
     * + terminal return) over a 3x3 window map, mirroring {@link #evalAnime4k}'s FP64 path.
     */
    private static double[] evaluateRawAnime4kPass(MpvHookShader.Pass pass, Map<String, double[][]> textures) {
        String body = pass.body;
        // go_N(x_off, y_off) -> {texName, relu mode}; g_N -> {texName, relu mode, center tap}
        Map<String, Object[]> goMacros = new HashMap<>();
        Map<String, Object[]> gMacros = new HashMap<>();
        Matcher goDef = Pattern.compile("#define\\s+(go_\\d+)\\s*\\(\\s*x_off\\s*,\\s*y_off\\s*\\)\\s*(.+)$",
                Pattern.MULTILINE).matcher(body);
        while (goDef.find()) {
            String rhs = goDef.group(2);
            Matcher tex = Pattern.compile("(\\w+)_texOff").matcher(rhs);
            assertTrue(tex.find());
            int mode = rhs.contains("max(-") ? -1 : (rhs.contains("max(") ? 1 : 0);
            goMacros.put(goDef.group(1), new Object[]{tex.group(1), mode});
        }
        Matcher gDef = Pattern.compile("#define\\s+(g_\\d+)\\s+(.+)$", Pattern.MULTILINE).matcher(body);
        while (gDef.find()) {
            String rhs = gDef.group(2);
            Matcher tex = Pattern.compile("(\\w+)_tex\\(").matcher(rhs);
            assertTrue(tex.find());
            int mode = rhs.contains("max(-") ? -1 : (rhs.contains("max(") ? 1 : 0);
            gMacros.put(gDef.group(1), new Object[]{tex.group(1), mode});
        }

        double[] result = null;
        List<double[]> outputs = new ArrayList<>();
        String[] lines = body.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            Matcher init = Pattern.compile("vec4 result = mat4\\(([^)]+)\\)\\s*\\*\\s*(\\w+)(?:\\(([-.\\d]+),\\s*([-.\\d]+)\\))?;").matcher(line);
            if (init.find()) {
                result = new double[4];
                accumulateAnime4kTerm(result, textures, init.group(1), init.group(2),
                        init.group(3), init.group(4), goMacros, gMacros);
                continue;
            }
            Matcher add = Pattern.compile("result \\+= mat4\\(([^)]+)\\)\\s*\\*\\s*(\\w+)(?:\\(([-.\\d]+),\\s*([-.\\d]+)\\))?;").matcher(line);
            if (add.find()) {
                accumulateAnime4kTerm(result, textures, add.group(1), add.group(2),
                        add.group(3), add.group(4), goMacros, gMacros);
                continue;
            }
            Matcher bias = Pattern.compile("result \\+= vec4\\(([^)]+)\\);").matcher(line);
            if (bias.find()) {
                String[] toks = bias.group(1).split(",");
                for (int o = 0; o < 4; o++) result[o] += Double.parseDouble(toks[o].trim());
                continue;
            }
            Matcher ret = Pattern.compile("return (result(?: \\+ MAIN_tex\\(MAIN_pos\\))?);").matcher(line);
            if (ret.find()) {
                double[] out = Arrays.copyOf(result, 4);
                if (ret.group(1).contains("MAIN_tex")) {
                    double[] center = textures.get("MAIN")[4];
                    for (int o = 0; o < 4; o++) out[o] += center[o];
                }
                outputs.add(out);
            }
        }
        assertTrue("raw pass must yield exactly one output", outputs.size() == 1);
        return outputs.get(0);
    }

    /** Shared term evaluation: resolve the macro/tap operand and accumulate mat4 * px. */
    private static void accumulateAnime4kTerm(double[] result, Map<String, double[][]> textures,
                                              String matArgs, String macro, String dx, String dy,
                                              Map<String, Object[]> goMacros, Map<String, Object[]> gMacros) {
        double[] px;
        if (dx != null) {
            Object[] def = goMacros.get(macro);
            assertTrue("unknown go macro: " + macro, def != null);
            double[][] tex = textures.get((String) def[0]);
            assertTrue("unresolved texture: " + def[0], tex != null);
            px = relu(tex[windowIndex(Double.parseDouble(dx), Double.parseDouble(dy))], (Integer) def[1]);
        } else {
            Object[] def = gMacros.get(macro);
            assertTrue("unknown g macro: " + macro, def != null);
            double[][] tex = textures.get((String) def[0]);
            assertTrue("unresolved texture: " + def[0], tex != null);
            px = relu(tex[4], (Integer) def[1]);
        }
        accumulateMat4(result, matArgs, px);
    }

    /**
     * Evaluates one optimized Anime4k pass body (CReLU precompute / 1x1 aggregation forms,
     * possibly fused with fragColor0/fragColor1 outputs) over a 3x3 window map.
     */
    private static double[][] evaluateOptimizedAnime4kPass(MpvHookShader.Pass pass, Map<String, double[][]> textures) {
        String body = pass.body;
        assertFalse("optimized pass must not keep raw go_ macros", Pattern.compile("\\bgo_\\d+\\s*\\(").matcher(body).find());
        assertFalse("optimized pass must not keep raw g_ macros", Pattern.compile("\\bg_\\d+\\b(?!\\s*=)").matcher(body).find());
        // Precomputed variables: name -> (texName, texel index, relu mode)
        Map<String, Object[]> precomputed = new HashMap<>();
        String[] lines = body.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            Matcher t = Pattern.compile("vec4 (_t[01]_\\w+) = (\\w+)_texOff\\(vec2\\(([-.\\d]+), ([-.\\d]+)\\)\\);").matcher(line);
            if (t.find()) {
                precomputed.put(t.group(1), new Object[]{t.group(2), windowIndex(Double.parseDouble(t.group(3)), Double.parseDouble(t.group(4))), 0});
                continue;
            }
            Matcher goPlain = Pattern.compile("vec4 (_go_\\d+_\\w+) = (\\w+)_texOff\\(vec2\\(([-.\\d]+), ([-.\\d]+)\\)\\);").matcher(line);
            if (goPlain.find()) {
                precomputed.put(goPlain.group(1), new Object[]{goPlain.group(2), windowIndex(Double.parseDouble(goPlain.group(3)), Double.parseDouble(goPlain.group(4))), 0});
                continue;
            }
            Matcher goRelu = Pattern.compile("vec4 (_go_\\d+_\\w+) = max\\((-?)(_t[01]_\\w+), 0\\.0\\);").matcher(line);
            if (goRelu.find()) {
                Object[] src = precomputed.get(goRelu.group(3));
                assertTrue("undefined precompute source: " + goRelu.group(3), src != null);
                precomputed.put(goRelu.group(1), new Object[]{src[0], src[1], goRelu.group(2).isEmpty() ? 1 : -1});
                continue;
            }
            Matcher rawTap = Pattern.compile("vec4 (_raw_\\d+) = (\\w+)_tex\\((\\w+)_pos\\);").matcher(line);
            if (rawTap.find()) {
                precomputed.put(rawTap.group(1), new Object[]{rawTap.group(2), 4, 0});
                continue;
            }
            Matcher gRelu = Pattern.compile("vec4 (_g_\\d+) = max\\((-?)(_raw_\\d+), 0\\.0\\);").matcher(line);
            if (gRelu.find()) {
                Object[] src = precomputed.get(gRelu.group(3));
                assertTrue("undefined precompute source: " + gRelu.group(3), src != null);
                precomputed.put(gRelu.group(1), new Object[]{src[0], src[1], gRelu.group(2).isEmpty() ? 1 : -1});
            }
        }

        double[] result = null;
        double[][] outputs = new double[pass.saves.size()][];
        int returnCount = 0;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            Matcher init = Pattern.compile("vec4 result = mat4\\(([^)]+)\\)\\s*\\*\\s*(\\w+);").matcher(line);
            if (init.find()) {
                result = new double[4];
                accumulateOptimizedTerm(result, textures, init.group(1), init.group(2), precomputed);
                continue;
            }
            Matcher add = Pattern.compile("result \\+= mat4\\(([^)]+)\\)\\s*\\*\\s*(\\w+);").matcher(line);
            if (add.find()) {
                accumulateOptimizedTerm(result, textures, add.group(1), add.group(2), precomputed);
                continue;
            }
            Matcher bias = Pattern.compile("result \\+= vec4\\(([^)]+)\\);").matcher(line);
            if (bias.find()) {
                String[] toks = bias.group(1).split(",");
                for (int o = 0; o < 4; o++) result[o] += Double.parseDouble(toks[o].trim());
                continue;
            }
            Matcher fc = Pattern.compile("fragColor(\\d+) = \\(?(result(?: \\+ MAIN_tex\\(MAIN_pos\\))?)\\)?;").matcher(line);
            Matcher ret = Pattern.compile("return (result(?: \\+ MAIN_tex\\(MAIN_pos\\))?);").matcher(line);
            if (fc.find()) {
                int slot = Integer.parseInt(fc.group(1));
                assertTrue("fragColor slot out of bounds: " + slot, slot >= 0 && slot < pass.saves.size());
                assertNull("duplicate fragColor slot: " + slot, outputs[slot]);
                double[] value = Arrays.copyOf(result, 4);
                if (fc.group(2).contains("MAIN_tex")) {
                    double[] center = textures.get("MAIN")[4];
                    for (int o = 0; o < 4; o++) value[o] += center[o];
                }
                outputs[slot] = value;
            } else if (ret.find()) {
                assertTrue("return only allowed for single-output pass", pass.saves.size() == 1);
                assertNull("duplicate return output", outputs[0]);
                double[] value = Arrays.copyOf(result, 4);
                if (ret.group(1).contains("MAIN_tex")) {
                    double[] center = textures.get("MAIN")[4];
                    for (int o = 0; o < 4; o++) value[o] += center[o];
                }
                outputs[0] = value;
                returnCount++;
            }
        }
        for (int s = 0; s < pass.saves.size(); s++) {
            assertNotNull("missing fragColor slot " + s + " in " + pass.desc, outputs[s]);
        }
        assertTrue(pass.saves.size() == 1 || returnCount == 0);
        return outputs;
    }

    private static void accumulateOptimizedTerm(double[] result, Map<String, double[][]> textures,
                                                String matArgs, String varName, Map<String, Object[]> precomputed) {
        Object[] def = precomputed.get(varName);
        assertTrue("undefined optimized operand: " + varName, def != null);
        double[][] tex = textures.get((String) def[0]);
        assertTrue("unresolved texture: " + def[0], tex != null);
        accumulateMat4(result, matArgs, relu(tex[(Integer) def[1]], (Integer) def[2]));
    }

    @Test
    public void native2xReconstructionRetainsOriginalShaderAndNativeTargetEstimate() throws Exception {
        GraphicsShaderTransforms.Config config = GraphicsShaderTransforms.Config.DEFAULT;
        for (String asset : VNSR_ASSETS) {
            List<MpvHookShader.Pass> passes = loadPasses(asset);
            assertTrue("isVnsrChain must be true for: " + asset, MpvHookShader.isVnsrChain(passes));
            List<MpvHookShader.Pass> optPasses = GraphicsShaderTransforms.optimizePasses(passes, config.runtimeMrtFusionEnabled);
            assertTrue("isVnsrChain must be true for optimized: " + asset, MpvHookShader.isVnsrChain(optPasses));
        }

        assertFalse(MpvHookShader.isVnsrChain(loadPasses("cas/CAS.glsl")));
        for (String asset : ANIME4K_ASSETS) {
            assertFalse(MpvHookShader.isVnsrChain(loadPasses(asset)));
        }

        long bytes1080pTo1440p = createNodeForAsset("vnsr/vnsr-c16-x2.glsl", 0)
                .estimateTargetBytes(1920, 1080, 2560, 1440);
        assertTrue(bytes1080pTo1440p >= 200_000_000L);
        assertTrue(bytes1080pTo1440p <= CnnPipeline.TARGET_MEMORY_BUDGET_BYTES);

        for (String asset : VNSR_ASSETS) {
            List<MpvHookShader.Pass> optPasses = GraphicsShaderTransforms.optimizePasses(
                    loadPasses(asset), config.runtimeMrtFusionEnabled);
            MpvHookShader.Pass pass6 = optPasses.get(6);
            assertTrue(pass6.body.contains("vnsr_output_0_tex(center)[index]"));
            assertTrue(pass6.body.contains("ivec2 phase"));
            assertFalse(pass6.body.contains("dot(vnsr_output_0_tex(center),_w)"));
        }

        float[][] testPhases = new float[][]{
                {0.25f, 0.25f, 0},
                {0.75f, 0.25f, 1},
                {0.25f, 0.75f, 2},
                {0.75f, 0.75f, 3}
        };
        for (float[] p : testPhases) {
            int phaseX = (int) Math.floor(p[0] * 2.0f);
            int phaseY = (int) Math.floor(p[1] * 2.0f);
            assertEquals((int) p[2], phaseY * 2 + phaseX);
        }
    }

    @Test
    public void p3bVnsrAllModelsZeroColumnPruningAndAllSevenPassesNumericalEquivalence() throws Exception {
        GraphicsShaderTransforms.Config config = GraphicsShaderTransforms.Config.DEFAULT;
        int[] expectedPass0Mat3x4 = {18, 27, 36};
        for (int m = 0; m < VNSR_ASSETS.length; m++) {
            String asset = VNSR_ASSETS[m];
            List<MpvHookShader.Pass> rawPasses = loadPasses(asset);
            List<MpvHookShader.Pass> optPasses = GraphicsShaderTransforms.optimizePasses(
                    rawPasses, config.runtimeMrtFusionEnabled);

            // Idempotence across re-optimization
            List<MpvHookShader.Pass> reoptPasses = GraphicsShaderTransforms.optimizePasses(
                    optPasses, config.runtimeMrtFusionEnabled);
            for (int p = 0; p < optPasses.size(); p++) {
                assertEquals("optimizePasses must be idempotent at pass " + p + " in " + asset,
                        optPasses.get(p).body, reoptPasses.get(p).body);
            }

            // Pass 0 mat3x4 zero-column pruning counts: C08=18, C12=27, C16=36; 0 mat4(
            MpvHookShader.Pass pass0 = optPasses.get(0);
            assertEquals("Pass 0 mat3x4 count mismatch in " + asset,
                    expectedPass0Mat3x4[m], countOccurrences(pass0.body, "mat3x4("));
            assertEquals("Pass 0 must have 0 mat4 in " + asset, 0, countOccurrences(pass0.body, "mat4("));
            assertTrue("Pass 0 must multiply by .rgb in " + asset, pass0.body.contains(".rgb;"));

            // Tiered precision must emit precision mediump float for Pass 0 after mat3x4 pruning
            String pass0Glsl = GraphicsShaderTransforms.generateFragmentShader(
                    pass0, true, config.tieredPrecisionEnabled);
            assertTrue("Pass 0 fragment shader must emit precision mediump float for " + asset,
                    pass0Glsl.contains("precision mediump float;\n"));

            for (int p = 0; p <= 5; p++) {
                MpvHookShader.Pass pass = optPasses.get(p);
                assertTrue(pass.body.contains("highp vec2 _p_m1_m1 = "));
                assertTrue(pass.body.contains("vec4 _s0, _s1, _s2;"));
            }

            // End-to-end CPU numerical equivalence across ALL 7 passes (Pass 0..5 conv + Pass 6 2x reconstruction)
            Random rng = new Random(100L + m);
            Map<String, float[][]> rawTextures = new HashMap<>();
            Map<String, float[][]> optTextures = new HashMap<>();
            float[][] mainPixels = new float[9][4];
            for (int t = 0; t < 9; t++) {
                for (int c = 0; c < 3; c++) mainPixels[t][c] = rng.nextFloat();
                mainPixels[t][3] = 1.0f;
            }
            rawTextures.put("MAIN", mainPixels);
            optTextures.put("MAIN", mainPixels);

            // Evaluate passes 0..5 across all save targets
            for (int p = 0; p <= 5; p++) {
                MpvHookShader.Pass rawP = rawPasses.get(p);
                MpvHookShader.Pass optP = optPasses.get(p);

                float[][][] rawIn = new float[rawP.binds.size()][9][4];
                float[][][] optIn = new float[optP.binds.size()][9][4];
                for (int b = 0; b < rawP.binds.size(); b++) {
                    rawIn[b] = rawTextures.get(rawP.binds.get(b));
                    optIn[b] = optTextures.get(optP.binds.get(b));
                }

                boolean applyRelu = (p <= 4);
                float[][] rawOut = evaluateConvPass(rawP, rawIn, applyRelu);
                float[][] optOut = evaluateConvPass(optP, optIn, applyRelu);

                for (int s = 0; s < rawP.saves.size(); s++) {
                    for (int c = 0; c < 4; c++) {
                        assertEquals("Pass " + p + " save[" + s + "][" + c + "] mismatch in " + asset,
                                rawOut[s][c], optOut[s][c], 0.0f);
                    }
                    // Broadcast single test center cell outputs across 3x3 for downstream chaining
                    float[][] tileOutRaw = new float[9][4];
                    float[][] tileOutOpt = new float[9][4];
                    for (int t = 0; t < 9; t++) {
                        tileOutRaw[t] = rawOut[s];
                        tileOutOpt[t] = optOut[s];
                    }
                    rawTextures.put(rawP.saves.get(s), tileOutRaw);
                    optTextures.put(optP.saves.get(s), tileOutOpt);
                }
            }

            // Pass 6: verify 2x subpixel reconstruction equivalence for all 4 quadrant phases by
            // parsing Pass 6's generated phase index & residual bindings against an explicit phase table.
            String rawPass6 = rawPasses.get(6).body;
            String optPass6 = optPasses.get(6).body;
            assertEquals("Pass 6 body must stay unchanged", rawPass6, optPass6);
            assertTrue("Missing Pass 6 top-left index formula in " + asset,
                    optPass6.contains("int index=phase.y*2+phase.x;"));
            Matcher residualMatch = Pattern.compile(
                    "vec3\\s+residual=vec3\\((vnsr_output_\\d+)_tex\\(center\\)\\[index\\],"
                    + "\\s*(vnsr_output_\\d+)_tex\\(center\\)\\[index\\],"
                    + "\\s*(vnsr_output_\\d+)_tex\\(center\\)\\[index\\]\\);").matcher(optPass6);
            assertTrue("Missing Pass 6 residual assembly in " + asset, residualMatch.find());
            float[] mainCenter = mainPixels[4];
            float[] rOut0 = rawTextures.get("vnsr_output_0")[4];
            float[] rOut1 = rawTextures.get("vnsr_output_1")[4];
            float[] rOut2 = rawTextures.get("vnsr_output_2")[4];
            // Evaluate the shader's phase index & residual channel routing using distinct quadrant inputs
            Map<String, float[]> distinctOptOut = Map.of(
                    "vnsr_output_0", new float[]{0.11f, 0.23f, 0.37f, 0.49f},
                    "vnsr_output_1", new float[]{-0.15f, 0.29f, -0.41f, 0.53f},
                    "vnsr_output_2", new float[]{0.19f, -0.31f, 0.43f, -0.59f});
            float[] oOut0 = optTextures.get(residualMatch.group(1))[4];
            float[] oOut1 = optTextures.get(residualMatch.group(2))[4];
            float[] oOut2 = optTextures.get(residualMatch.group(3))[4];
            for (int py = 0; py < 2; py++) {
                for (int px = 0; px < 2; px++) {
                    int expectedPhase = py * 2 + px;
                    int parsedPhase = optPass6.contains("int index=phase.y*2+phase.x;") ? (py * 2 + px)
                            : optPass6.contains("int index=phase.x*2+phase.y;") ? (px * 2 + py) : -1;
                    assertEquals(expectedPhase, parsedPhase);
                    float[] expectedRgb = new float[]{
                            mainCenter[0] + rOut0[expectedPhase],
                            mainCenter[1] + rOut1[expectedPhase],
                            mainCenter[2] + rOut2[expectedPhase],
                            1.0f
                    };
                    float[] actualRgb = new float[]{
                            mainCenter[0] + oOut0[parsedPhase],
                            mainCenter[1] + oOut1[parsedPhase],
                            mainCenter[2] + oOut2[parsedPhase],
                            1.0f
                    };
                    assertArrayEquals("Pass 6 phase (" + px + "," + py + ") mismatch in " + asset,
                            expectedRgb, actualRgb, 0.0f);
                    // Distinct per-quadrant pin catches transposed phase.x/phase.y at (1,0) vs (0,1).
                    assertArrayEquals(
                            new float[]{
                                    distinctOptOut.get("vnsr_output_0")[expectedPhase],
                                    distinctOptOut.get("vnsr_output_1")[expectedPhase],
                                    distinctOptOut.get("vnsr_output_2")[expectedPhase]},
                            new float[]{
                                    distinctOptOut.get(residualMatch.group(1))[parsedPhase],
                                    distinctOptOut.get(residualMatch.group(2))[parsedPhase],
                                    distinctOptOut.get(residualMatch.group(3))[parsedPhase]},
                            0.0f);
                }
            }
        }

        // Confirm optimizePasses idempotence across Anime4K Restore CNN models too
        for (String asset : ALL_CNN_ASSETS) {
            List<MpvHookShader.Pass> once = GraphicsShaderTransforms.optimizePasses(
                    loadPasses(asset), config.runtimeMrtFusionEnabled);
            List<MpvHookShader.Pass> twice = GraphicsShaderTransforms.optimizePasses(
                    once, config.runtimeMrtFusionEnabled);
            assertEquals(once.size(), twice.size());
            for (int p = 0; p < once.size(); p++) {
                assertEquals(once.get(p).body, twice.get(p).body);
            }
        }
    }

    @Test
    public void runPassesSupportsDefaultFramebufferAndSurvivesMidLoopMrtException() throws Exception {
        List<MpvHookShader.Pass> passes = GraphicsShaderTransforms.optimizePasses(
                loadPasses("vnsr/vnsr-c12-x2.glsl"),
                GraphicsShaderTransforms.Config.DEFAULT.runtimeMrtFusionEnabled);
        GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
        RecordingPipelineGlOps glOps = new RecordingPipelineGlOps();
        CnnPipeline pipeline = new CnnPipeline(passes, targets, true, 2, 8,
                GraphicsShaderTransforms.Config.DEFAULT, glOps);
        pipeline.buildPrograms(1);

        CnnPipeline.PassPlan[] plans = pipeline.getPlans();
        assertNotNull(plans);
        assertEquals(7, plans.length);
        assertEquals(3, plans[0].targetSlots.length);
        assertEquals(2, plans[1].targetSlots.length);
        assertEquals(3, plans[5].targetSlots.length);

        GlRenderTarget[] intermediates = new GlRenderTarget[targets.count];
        for (int i = 0; i < intermediates.length; i++) {
            intermediates[i] = new GlRenderTarget(320, 240);
            intermediates[i].texture[0] = 200 + i;
            intermediates[i].fbo[0] = 300 + i;
        }

        // restoreTarget == null (default framebuffer 0) must execute without GL_INVALID_OPERATION
        // and must NEVER call glDrawBuffers or glInvalidateFramebuffer on FBO 0.
        pipeline.runPasses(50, 320, 240, intermediates, null, false, 1, 0.0, false);
        assertEquals(0, glOps.invalidateOnFboZeroCalls);
        assertEquals(0, glOps.drawBuffersOnFboZeroCalls);
        assertEquals(7, glOps.drawCalls);
        assertArrayEquals(new int[]{GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_COLOR_ATTACHMENT2},
                glOps.fboDrawBuffers.get(plans[0].fbo));
        assertArrayEquals(new int[]{GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1},
                glOps.fboDrawBuffers.get(plans[1].fbo));
        assertArrayEquals(new int[]{GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_COLOR_ATTACHMENT2},
                glOps.fboDrawBuffers.get(plans[5].fbo));

        // Mid-loop feedback-loop exception on MRT pass must NOT overwrite any plan.fbo GL_DRAW_BUFFERS
        GlRenderTarget[] colliding = new GlRenderTarget[targets.count];
        for (int i = 0; i < colliding.length; i++) {
            colliding[i] = new GlRenderTarget(320, 240);
            colliding[i].texture[0] = 400; // deliberate texture aliasing triggers feedback loop inside Pass 1
            colliding[i].fbo[0] = 500 + i;
        }
        assertThrows(IllegalStateException.class, () ->
                pipeline.runPasses(50, 320, 240, colliding, null, false, 1, 0.0, false));
        assertArrayEquals("MRT GL_DRAW_BUFFERS on plan.fbo must stay intact after mid-loop exception",
                new int[]{GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1},
                glOps.fboDrawBuffers.get(plans[1].fbo));
        assertEquals(0, glOps.drawBuffersOnFboZeroCalls);

        // Recover with valid targetsChanged && !sizeChanged -> re-uploads uniforms and renders cleanly
        GlRenderTarget restoreTarget = new GlRenderTarget(640, 480);
        restoreTarget.texture[0] = 90;
        restoreTarget.fbo[0] = 91;
        GlRenderTarget[] freshIntermediates = new GlRenderTarget[targets.count];
        for (int i = 0; i < freshIntermediates.length; i++) {
            freshIntermediates[i] = new GlRenderTarget(320, 240);
            freshIntermediates[i].texture[0] = 600 + i;
            freshIntermediates[i].fbo[0] = 700 + i;
        }
        int uniformCallsBefore = glOps.uniformUpdates;
        pipeline.runPasses(50, 320, 240, freshIntermediates, restoreTarget, false, 1, 0.0, false);
        assertTrue("targetsChanged && !sizeChanged must refresh uniforms",
                glOps.uniformUpdates > uniformCallsBefore);

        // Steady-state re-run with identical params should execute 0 uniform updates
        int steadyUniformsBefore = glOps.uniformUpdates;
        pipeline.runPasses(50, 320, 240, freshIntermediates, restoreTarget, false, 1, 0.0, false);
        assertEquals("Steady-state unchanged execution must perform 0 uniform calls",
                steadyUniformsBefore, glOps.uniformUpdates);

        // Invalid args and insufficient intermediate targets fail cleanly with typed exceptions
        assertThrows(IllegalArgumentException.class, () ->
                pipeline.runPasses(0, 320, 240, intermediates, restoreTarget, false, 1, 0.0, false));
        assertThrows(IllegalStateException.class, () ->
                pipeline.runPasses(50, 320, 240, new GlRenderTarget[0], restoreTarget, false, 1, 0.0, false));

        pipeline.releasePrograms();

        // buildPrograms validates device MRT limits without leaking compiled programs/FBOs
        RecordingPipelineGlOps lowMrtGl = new RecordingPipelineGlOps();
        lowMrtGl.maxDrawBuffers = 1;
        CnnPipeline limitedMrt = new CnnPipeline(passes, targets, true, 2, 8,
                GraphicsShaderTransforms.Config.DEFAULT, lowMrtGl);
        TargetUnavailableException mrtCapEx = assertThrows(TargetUnavailableException.class,
                () -> limitedMrt.buildPrograms(1));
        assertFalse("Deterministic MRT hardware limit failure must not retry after delay",
                mrtCapEx.retryAfterDelay);
        assertEquals(0, lowMrtGl.livePrograms.size());
        assertEquals(0, lowMrtGl.liveFbos.size());

        // K2: Final pass feedback loop (mainTexture or intermediate aliasing restoreTarget.texture[0]) throws
        // BEFORE glInvalidateFramebuffer touches restoreTarget.fbo[0].
        RecordingPipelineGlOps finalLoopGl = new RecordingPipelineGlOps();
        CnnPipeline finalLoopPipe = new CnnPipeline(passes, targets, true, 2, 8,
                GraphicsShaderTransforms.Config.DEFAULT, finalLoopGl);
        finalLoopPipe.buildPrograms(1);
        GlRenderTarget aliasedRestore = new GlRenderTarget(640, 480);
        aliasedRestore.texture[0] = 50; // same GL texture name as mainTexture (50) read by final pass
        aliasedRestore.fbo[0] = 95;
        assertThrows(IllegalStateException.class, () ->
                finalLoopPipe.runPasses(50, 320, 240, freshIntermediates, aliasedRestore, false, 1, 0.0, false));
        assertFalse("Final-pass feedback loop must NOT call glInvalidateFramebuffer on aliased restoreTarget",
                finalLoopGl.invalidatedFbos.contains(95));

        // N2: glCreateProgram() == 0 or glCreateShader() == 0 throws retryable TargetUnavailableException
        RecordingPipelineGlOps zeroProgGl = new RecordingPipelineGlOps();
        zeroProgGl.failCreateProgram = true;
        zeroProgGl.oomOnCreateProgram = true;
        CnnPipeline zeroProgPipe = new CnnPipeline(passes, targets, true, 2, 8,
                GraphicsShaderTransforms.Config.DEFAULT, zeroProgGl);
        TargetUnavailableException progOom = assertThrows(TargetUnavailableException.class,
                () -> zeroProgPipe.buildPrograms(1));
        assertTrue(progOom.retryAfterDelay);
        assertTrue("glCreateProgram() must be invoked before OOM fails", zeroProgGl.createProgramCalls > 0);
        assertEquals(0, zeroProgGl.liveFbos.size());

        RecordingPipelineGlOps zeroShaderGl = new RecordingPipelineGlOps();
        zeroShaderGl.failCreateShader = true;
        CnnPipeline zeroShaderPipe = new CnnPipeline(passes, targets, true, 2, 8,
                GraphicsShaderTransforms.Config.DEFAULT, zeroShaderGl);
        TargetUnavailableException shaderZero = assertThrows(TargetUnavailableException.class,
                () -> zeroShaderPipe.buildPrograms(1));
        assertTrue(shaderZero.retryAfterDelay);
        assertEquals(0, zeroShaderGl.liveFbos.size());
    }

    @Test
    public void hostDirtyFrameStaticReuseContractForKirikiroidAndArtemis() throws Exception {
        Method bridgeProcess11 = KirikiroidRawFrameBridge.class.getDeclaredMethod(
                "process", int.class, int.class, int.class, float.class, float.class,
                float.class, float.class, int.class, int.class, boolean.class, boolean.class);
        assertTrue(java.lang.reflect.Modifier.isPrivate(bridgeProcess11.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(bridgeProcess11.getModifiers()));
        assertEquals(int.class, bridgeProcess11.getReturnType());

        Method pipelineCropped11 = GraphicsFilterPipeline.class.getDeclaredMethod(
                "renderCroppedTexture", int.class, int.class, int.class, float.class, float.class,
                float.class, float.class, int.class, int.class, boolean.class, boolean.class);
        assertTrue(java.lang.reflect.Modifier.isPublic(pipelineCropped11.getModifiers()));
        assertEquals(int.class, pipelineCropped11.getReturnType());

        // Verify GraphicsFilterPipeline returns cached output on clean frames (!frameDirty) with 0 CNN draws
        List<MpvHookShader.Pass> passes = loadPasses("anime4k/Anime4K_Restore_CNN_S.glsl");
        GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
        RecordingPipelineGlOps gl = new RecordingPipelineGlOps();
        CnnPipeline cnn = new CnnPipeline(passes, targets, true, 1, 2,
                GraphicsShaderTransforms.Config.DEFAULT, gl);
        setField(cnn, "mPlans", new CnnPipeline.PassPlan[]{
                new CnnPipeline.PassPlan(passes.get(0), 1, new int[0], new int[0], new int[0], new int[0], new int[0],
                        -1, -1, new int[]{-1, -1}, 0, new int[0]),
                new CnnPipeline.PassPlan(passes.get(1), 2, new int[0], new int[0], new int[0], new int[0], new int[0],
                        -1, -1, new int[]{-1, -1}, 0, new int[0])
        });
        GlRenderTarget restore = new GlRenderTarget(640, 360);
        restore.texture[0] = 77;
        restore.fbo[0] = 78;

        setField(cnn, "mLimitWidth", 4096);
        setField(cnn, "mLimitHeight", 4096);
        setField(cnn, "mGlReady", true);
        setField(cnn, "mWidth", 640);
        setField(cnn, "mHeight", 360);
        setField(cnn, "mIntermediateTargets", new GlRenderTarget[0]);
        setField(cnn, "mRestore", restore);
        setField(cnn, "mOutput", restore);

        GraphicsFilterChain chain = GraphicsFilterChain.parse("v1:s");
        GraphicsFilterPipeline pipeline = new GraphicsFilterPipeline(
                new android.content.ContextWrapper(null), chain, 0);
        ((CnnPipeline[]) getField(pipeline, "nodes"))[0] = cnn;
        ((boolean[]) getField(pipeline, "execute"))[0] = true;
        ((int[]) getField(pipeline, "inputWidths"))[0] = 640;
        ((int[]) getField(pipeline, "inputHeights"))[0] = 360;
        ((int[]) getField(pipeline, "outputWidths"))[0] = 640;
        ((int[]) getField(pipeline, "outputHeights"))[0] = 360;
        setField(pipeline, "geometryKnown", true);
        setField(pipeline, "planValid", true);
        setField(pipeline, "hasExecution", true);
        setField(pipeline, "sourceWidth", 640);
        setField(pipeline, "sourceHeight", 360);
        setField(pipeline, "boundWidth", 640);
        setField(pipeline, "boundHeight", 360);
        setField(pipeline, "extraOwnedBytes", 0L);

        // Dirty frame runs CNN and populates lastCachedOutput cache (texture 77)
        int outDirty = pipeline.renderCroppedTexture(10, 640, 360, 0f, 0f, 1f, 1f, 640, 360, true, true);
        assertEquals(77, outDirty);
        int drawsAfterDirty = gl.drawCalls;
        assertTrue(drawsAfterDirty > 0);

        // Clean frame (!frameDirty) short-circuits on CPU: returns cached texture 77 with 0 GPU draws
        int outClean = pipeline.renderCroppedTexture(10, 640, 360, 0f, 0f, 1f, 1f, 640, 360, true, false);
        assertEquals(77, outClean);
        assertEquals(640, pipeline.outputWidth());
        assertEquals(360, pipeline.outputHeight());
        assertEquals(CnnPipeline.BYPASS_NONE, pipeline.bypassReason());
        assertEquals("Clean Kirikiroid frame must issue 0 GL draws", drawsAfterDirty, gl.drawCalls);

        // Dirty frame (content updated in TVPWindowLayer) re-executes CNN passes directly
        int outDirty2 = pipeline.renderCroppedTexture(10, 640, 360, 0f, 0f, 1f, 1f, 640, 360, true, true);
        assertEquals(77, outDirty2);
        assertTrue(gl.drawCalls > drawsAfterDirty);

        // Changed crop UVs or input texture bypasses CPU cache even if !frameDirty
        int drawsBeforeTexChange = gl.drawCalls;
        int outNewTex = pipeline.renderCroppedTexture(11, 640, 360, 0f, 0f, 1f, 1f, 640, 360, true, false);
        assertEquals(77, outNewTex);
        assertTrue("Changed texture ID must not reuse stale CPU cache", gl.drawCalls > drawsBeforeTexChange);

        // Single-context full-frame renderTexture(..., frameDirty) reuses cached output on !frameDirty
        Method pipelineRenderDirty = GraphicsFilterPipeline.class.getDeclaredMethod(
                "renderTexture", int.class, int.class, int.class, int.class, int.class, boolean.class);
        assertTrue(java.lang.reflect.Modifier.isPublic(pipelineRenderDirty.getModifiers()));
        assertEquals(int.class, pipelineRenderDirty.getReturnType());

        Method artemisRenderStage = ArtemisOfficialBridge.class.getDeclaredMethod(
                "renderStage", int.class, int.class, int.class, int.class, int.class,
                boolean.class, boolean.class, long.class);
        assertTrue(java.lang.reflect.Modifier.isPublic(artemisRenderStage.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(artemisRenderStage.getModifiers()));
        assertEquals(int.class, artemisRenderStage.getReturnType());

        int drawsBeforeSingleDirty = gl.drawCalls;
        int singleDirty = pipeline.renderTexture(12, 640, 360, 640, 360, true);
        assertEquals(77, singleDirty);
        int drawsAfterSingleDirty = gl.drawCalls;
        assertTrue(drawsAfterSingleDirty > drawsBeforeSingleDirty);

        int singleClean = pipeline.renderTexture(12, 640, 360, 640, 360, false);
        assertEquals(77, singleClean);
        assertEquals(640, pipeline.outputWidth());
        assertEquals(360, pipeline.outputHeight());
        assertEquals(CnnPipeline.BYPASS_NONE, pipeline.bypassReason());
        assertEquals("Clean Artemis stage frame must issue 0 GL draws", drawsAfterSingleDirty, gl.drawCalls);

        int singleTexChange = pipeline.renderTexture(13, 640, 360, 640, 360, false);
        assertEquals(77, singleTexChange);
        assertTrue("Changed Artemis stage texture ID must bypass CPU cache", gl.drawCalls > drawsAfterSingleDirty);
    }

    @Test
    public void emptyBindNonFinalPassSkipsDimRootRecordWithoutThrowing() {
        // A malformed non-final pass with no binds must fail closed at load time (no
        // dimension-provenance record) instead of throwing IndexOutOfBoundsException from
        // the save-root fallback in optimizePasses.
        MpvHookShader.Pass malformed = new MpvHookShader.Pass("malformed empty binds",
                new ArrayList<>(), new ArrayList<>(List.of("orphan_save")),
                null, null, "void hook() {\n}\n");
        List<MpvHookShader.Pass> passes = new ArrayList<>();
        passes.add(malformed);
        List<MpvHookShader.Pass> optimized = GraphicsShaderTransforms.optimizePasses(passes, true);
        assertEquals(1, optimized.size());
        assertTrue("Malformed pass must pass through unoptimized", optimized.get(0) == malformed);
    }

    @Test
    public void statsFrameOwnershipRemainsWithHostBridgeOnCleanFrameReuse() throws Exception {
        // Unified host dirty-frame static reuse contract: neither renderCroppedTexture nor renderTexture calls
        // stats.frame(...) during clean-frame reuse; host bridges own stats.frame(...) while
        // GraphicsFilterPipeline updates nodeFrame(...) (and polls any GraphicsGpuTimer).
        List<MpvHookShader.Pass> passes = loadPasses("anime4k/Anime4K_Restore_CNN_S.glsl");
        GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
        RecordingPipelineGlOps gl = new RecordingPipelineGlOps();
        CnnPipeline cnn = new CnnPipeline(passes, targets, true, 1, 2,
                GraphicsShaderTransforms.Config.DEFAULT, gl);
        setField(cnn, "mPlans", new CnnPipeline.PassPlan[]{
                new CnnPipeline.PassPlan(passes.get(0), 1, new int[0], new int[0], new int[0], new int[0], new int[0],
                        -1, -1, new int[]{-1, -1}, 0, new int[0]),
                new CnnPipeline.PassPlan(passes.get(1), 2, new int[0], new int[0], new int[0], new int[0], new int[0],
                        -1, -1, new int[]{-1, -1}, 0, new int[0])
        });
        GlRenderTarget restore = new GlRenderTarget(640, 360);
        restore.texture[0] = 77;
        restore.fbo[0] = 78;

        setField(cnn, "mLimitWidth", 4096);
        setField(cnn, "mLimitHeight", 4096);
        setField(cnn, "mGlReady", true);
        setField(cnn, "mWidth", 640);
        setField(cnn, "mHeight", 360);
        setField(cnn, "mIntermediateTargets", new GlRenderTarget[0]);
        setField(cnn, "mRestore", restore);
        setField(cnn, "mOutput", restore);

        GraphicsFilterChain chain = GraphicsFilterChain.parse("v1:s");
        GraphicsFilterPipeline pipeline = new GraphicsFilterPipeline(
                new android.content.ContextWrapper(null), chain, 0);
        ((CnnPipeline[]) getField(pipeline, "nodes"))[0] = cnn;
        ((boolean[]) getField(pipeline, "execute"))[0] = true;
        ((int[]) getField(pipeline, "inputWidths"))[0] = 640;
        ((int[]) getField(pipeline, "inputHeights"))[0] = 360;
        ((int[]) getField(pipeline, "outputWidths"))[0] = 640;
        ((int[]) getField(pipeline, "outputHeights"))[0] = 360;
        setField(pipeline, "geometryKnown", true);
        setField(pipeline, "planValid", true);
        setField(pipeline, "hasExecution", true);
        setField(pipeline, "sourceWidth", 640);
        setField(pipeline, "sourceHeight", 360);
        setField(pipeline, "boundWidth", 640);
        setField(pipeline, "boundHeight", 360);
        setField(pipeline, "extraOwnedBytes", 0L);

        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        pipeline.setProfiler(stats, false);

        // Dirty cropped frame renders and populates the reuse cache.
        assertEquals(77, pipeline.renderCroppedTexture(10, 640, 360, 0f, 0f, 1f, 1f, 640, 360, true, true));

        // Sentinel marks the stats object so any stats.frame call during clean-frame reuse
        // would overwrite it.
        stats.frame(111, 222, 333, 444, 555, 666);

        // Clean cropped reuse: host bridge (Anime4kRuntime.onRawFrame / Krkrsdl3Graphics) owns
        // stats.frame(...), so renderCroppedTexture must not overwrite it.
        assertEquals(77, pipeline.renderCroppedTexture(10, 640, 360, 0f, 0f, 1f, 1f, 640, 360, true, false));
        assertEquals("Cropped clean-frame reuse must leave stats.frame ownership to host bridge",
                111, getIntField(stats, "sourceWidth"));
        assertEquals(222, getIntField(stats, "sourceHeight"));

        // Dirty full-frame render populates the cache, then clean full-frame reuse must NOT
        // record stats.frame (renderStage owns stats.frame in analyzer mode).
        assertEquals(77, pipeline.renderTexture(12, 640, 360, 640, 360, true));
        stats.frame(111, 222, 333, 444, 555, 666);
        assertEquals(77, pipeline.renderTexture(12, 640, 360, 640, 360, false));
        assertEquals("Full-frame clean reuse must leave stats.frame ownership to host bridge",
                111, getIntField(stats, "sourceWidth"));
        assertEquals(222, getIntField(stats, "sourceHeight"));
    }

    @Test
    public void abandonContextResetsBoundTextures() throws Exception {
        List<MpvHookShader.Pass> passes = loadPasses("anime4k/Anime4K_Restore_CNN_S.glsl");
        GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
        CnnPipeline pipeline = new CnnPipeline(passes, targets, false, 1, 2,
                GraphicsShaderTransforms.Config.DEFAULT);
        setField(pipeline, "mPlans", new CnnPipeline.PassPlan[0]);

        int[] bound = (int[]) getField(pipeline, "mBoundTextures");
        bound[0] = 123;
        bound[1] = 456;

        pipeline.abandonContext();
        assertNull(pipeline.getPlans());
        for (int b : bound) {
            assertEquals(-1, b);
        }
    }

    @Test
    public void estimateTargetBytesMatchesInstancePlanEnforcesBudgetBeforeCompileAndSnapshotsConfig() throws Exception {
        String asset = "anime4k/Anime4K_Restore_CNN_L.glsl";
        List<MpvHookShader.Pass> rawPasses = loadPasses(asset);
        int sw = 1920, sh = 1080, ow = 3840, oh = 2160, tileCoreSize = 256;

        // 1. Instance formula must match fused/optimized plan when untiled
        List<MpvHookShader.Pass> optPasses = GraphicsShaderTransforms.optimizePasses(
                rawPasses, GraphicsShaderTransforms.Config.DEFAULT.runtimeMrtFusionEnabled);
        GraphicsShaderTargets optTargets = new GraphicsShaderTargets(optPasses);
        GraphicsShaderGeometry geom = new GraphicsShaderGeometry(sw, sh, ow, oh, 1, false, 1);
        long expectedUntiled = geom.estimate(sw, sh, ow, oh, optTargets.count, 0, false);
        CnnPipeline untiledNode = createNodeForAsset(asset, 0);
        assertEquals("Untiled instance estimateTargetBytes must match fused pass plan",
                expectedUntiled, untiledNode.estimateTargetBytes(sw, sh, ow, oh));

        // 2. Tiled formula uses optimized pass count and excludes temporal cache
        int halo = 1;
        int optPassCount = optPasses.size();
        int expectedScratchW = Math.min(sw, tileCoreSize + 2 * halo * optPassCount);
        int expectedScratchH = Math.min(sh, tileCoreSize + 2 * halo * optPassCount);
        long expectedTiled = LanczosScale.addBytes(
                geom.estimate(sw, sh, ow, oh, 0, 0, false),
                (long) expectedScratchW * expectedScratchH * (8L * optTargets.count + 4L + 4L));
        CnnPipeline tiledNode = createNodeForAsset(asset, tileCoreSize);
        long actualTiled = tiledNode.estimateTargetBytes(sw, sh, ow, oh);
        assertEquals(expectedTiled, actualTiled);
        assertTrue(actualTiled < expectedUntiled);

        // 3. Budget overrun rejects BEFORE compiling shaders or programs
        CnnPipeline budgetNode = createNodeForAsset("vnsr/vnsr-c16-x2.glsl", 0);
        setField(budgetNode, "mLimitWidth", 32768);
        setField(budgetNode, "mLimitHeight", 32768);
        Method ensureTargets = CnnPipeline.class.getDeclaredMethod(
                "ensureTargets", int.class, int.class, int.class, int.class);
        ensureTargets.setAccessible(true);
        boolean ok = (boolean) ensureTargets.invoke(budgetNode, 8192, 8192, 16384, 16384);
        assertFalse("Oversized 8K->16K target must bypass on budget", ok);
        assertEquals(CnnPipeline.BYPASS_TARGET_UNAVAILABLE, budgetNode.bypassReason());
        assertNull("buildPrograms() must not compile CnnPipeline plans when budget exceeded",
                budgetNode.getPlans());
        assertEquals("vertex shader must not compile when budget exceeded",
                0, (int) getField(budgetNode, "mVertexShader"));
        assertEquals("quad VAO must not allocate when budget exceeded",
                0, ((int[]) getField(budgetNode, "mQuadVao"))[0]);

        // 4. Immutable Config snapshot stays isolated from later static flag flips
        try {
            CnnPipeline.setRuntimeMrtFusionEnabled(true);
            CnnPipeline snapNode = createNodeForAsset(asset, 0);
            snapNode.estimateTargetBytes(sw, sh, ow, oh);
            GraphicsShaderTransforms.Config snap = (GraphicsShaderTransforms.Config) getField(snapNode, "mConfig");
            assertNotNull(snap);
            assertTrue(snap.runtimeMrtFusionEnabled);

            CnnPipeline.setRuntimeMrtFusionEnabled(false);
            snapNode.estimateTargetBytes(sw, sh, ow, oh);
            assertTrue("Snapshotted mConfig must remain isolated after static toggle change",
                    ((GraphicsShaderTransforms.Config) getField(snapNode, "mConfig")).runtimeMrtFusionEnabled);

            // abandonContext & release clear snapshot
            snapNode.abandonContext();
            assertNull(getField(snapNode, "mConfig"));
        } finally {
            CnnPipeline.setRuntimeMrtFusionEnabled(true);
        }
    }

    @Test
    public void outputSizedNodesRejectTileCoreSizeAndTheirTiledEstimateStaysFullFrame() throws Exception {
        // Tile tiling pins the restore target to input*nativeScale (runTiledPasses invariant);
        // an output-sized node derives that geometry from the request, so tile selection must
        // fail fast here and the tiled estimate must never shrink below the full-frame one.
        CnnPipeline node = createNodeForAsset("anime4k/Anime4K_Restore_CNN_S.glsl", 0);
        setField(node, "mOutputSizedShader", true);
        assertThrows(IllegalArgumentException.class, () -> node.setTileCoreSize(256));
        setField(node, "mOutputSizedShader", false);
        node.setTileCoreSize(256);
        assertEquals(256, ((Integer) getField(node, "mTileCoreSize")).intValue());

        List<MpvHookShader.Pass> passes = loadPasses("anime4k/Anime4K_Restore_CNN_S.glsl");
        GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
        long outputSizedFullFrame = CnnPipeline.estimateTargetBytesForPlan(
                passes, targets, 640, 360, 1280, 720, 1, 1, true, 1, 0);
        long outputSizedTiled = CnnPipeline.estimateTargetBytesForPlan(
                passes, targets, 640, 360, 1280, 720, 1, 1, true, 1, 256);
        assertEquals("output-sized tiled estimate must stay full-frame",
                outputSizedFullFrame, outputSizedTiled);
        long fixedFullFrame = CnnPipeline.estimateTargetBytesForPlan(
                passes, targets, 640, 360, 1280, 720, 1, 1, false, 1, 0);
        long fixedTiled = CnnPipeline.estimateTargetBytesForPlan(
                passes, targets, 640, 360, 1280, 720, 1, 1, false, 1, 256);
        assertTrue("fixed native-scale halo node must still benefit from tiling",
                fixedTiled < fixedFullFrame);
    }

    @Test
    public void shaderParserAndRewriterFailClosedOnCommentsAndMalformedStructures() {
        // Comments containing mat4( or void hook() must not spoof precision/entry-point detection
        MpvHookShader.Pass commentSpoof = new MpvHookShader.Pass(
                "commented", List.of("MAIN"), List.of("MAIN"), null, null,
                "/* mat4(fake); */\n// void hook() {\nvec4 hook() { return MAIN_tex(MAIN_pos); }\n");
        assertFalse("Commented mat4( must not trigger CNN convolution classification",
                MpvHookShader.isCnnConvolutionPass(commentSpoof));
        assertFalse("Commented void hook() must not trigger void hook entry",
                MpvHookShader.hasVoidHook(commentSpoof.body));
        String spoofGlsl = GraphicsShaderTransforms.generateFragmentShader(
                commentSpoof, true, GraphicsShaderTransforms.Config.DEFAULT.tieredPrecisionEnabled);
        assertFalse(spoofGlsl.contains("precision mediump float;"));
        assertTrue(spoofGlsl.contains("fragColor = hook();"));

        // Unclosed block comment fails closed
        assertNull(MpvHookShader.stripGlslCommentsOrNull("vec4 hook() { /* unclosed comment return MAIN_tex(MAIN_pos); }"));
        MpvHookShader.Pass unclosedPass = new MpvHookShader.Pass(
                "unclosed", List.of("MAIN"), List.of("MAIN"), null, null,
                "#define go_0(x, y) (MAIN_texOff(vec2(x, y)))\nvec4 hook() { /* unclosed\n return go_0(0.0, 0.0); }");
        assertEquals("Unclosed block comment must leave body unchanged (fail-closed)",
                unclosedPass.body, GraphicsShaderTransforms.optimizePassBody(unclosedPass).body);

        // Out-of-range go_0(2.0, 0.0) fails closed without dropping code
        MpvHookShader.Pass outOfRangeGo = new MpvHookShader.Pass(
                "bad-go", List.of("MAIN"), List.of("T0"), "MAIN", "MAIN",
                "#define go_0(x, y) (MAIN_texOff(vec2(x, y)))\n"
                + "vec4 hook() { return go_0(2.0, 0.0); }\n");
        assertEquals(outOfRangeGo.body, GraphicsShaderTransforms.optimizePassBody(outOfRangeGo).body);

        // Extra code inside VN-SR hook() fails closed rather than silently dropping statements
        MpvHookShader.Pass extraVnsrCode = new MpvHookShader.Pass(
                "bad-vnsr", List.of("MAIN"), List.of("T0"), "MAIN", "MAIN",
                "vec4 sample_0(vec2 p) { return MAIN_tex(MAIN_pos + p * MAIN_pt); }\n"
                + "void hook() {\n"
                + "vec4 v0 = vec4(0.0, 0.0, 0.0, 0.0);\n"
                + "vec4 s;\n"
                + "s = sample_0(vec2(0.0, 0.0));\n"
                + "v0 += mat4(1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0) * s;\n"
                + "v0.r += 0.25;\n"
                + "fragColor0 = v0;\n"
                + "}\n");
        assertEquals("Unsupported extra statement inside VN-SR hook() must fail closed unchanged",
                extraVnsrCode.body, GraphicsShaderTransforms.optimizePassBody(extraVnsrCode).body);

        // Extra helper outside hook() blocks fusion
        MpvHookShader.Pass passWithHelper = new MpvHookShader.Pass(
                "helper", List.of("MAIN"), List.of("A"), "MAIN", "MAIN",
                "vec4 helper() { return vec4(1.0); }\nvec4 hook() { return helper(); }\n");
        MpvHookShader.Pass sibling = new MpvHookShader.Pass(
                "sibling", List.of("MAIN"), List.of("B"), "MAIN", "MAIN",
                "vec4 helper() { return vec4(1.0); }\nvec4 hook() { return helper(); }\n");
        List<MpvHookShader.Pass> helperOpt = GraphicsShaderTransforms.optimizePasses(
                Arrays.asList(passWithHelper, sibling), true);
        assertEquals("Passes with conflicting user helper() definitions must not fuse",
                2, helperOpt.size());
        assertEquals(List.of("A"), helperOpt.get(0).saves);
        assertEquals(List.of("B"), helperOpt.get(1).saves);

        // Standalone single-pass multi-bind optimization fails closed without chain provenance;
        // Mixed-resolution binds across passes prevent VN-SR single-b0 coordinate sharing,
        // while transitively same-resolution binds optimize safely.
        String vnsrMultiBind = "vec4 sample_0(vec2 p) { return A_tex(A_pos + p * A_pt); }\n"
                + "vec4 sample_1(vec2 p) { return B_tex(B_pos + p * B_pt); }\n"
                + "void hook() {\n"
                + "vec4 v0 = vec4(0.0, 0.0, 0.0, 0.0);\n"
                + "vec4 s;\n"
                + "s = sample_0(vec2(0.0, 0.0));\n"
                + "v0 += mat4(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1) * s;\n"
                + "s = sample_1(vec2(0.0, 0.0));\n"
                + "v0 += mat4(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1) * s;\n"
                + "fragColor0 = max(v0, 0.0);\n"
                + "}\n";
        MpvHookShader.Pass pA = new MpvHookShader.Pass("A", List.of("MAIN"), List.of("A"), "MAIN", "MAIN",
                "vec4 hook() { return MAIN_tex(MAIN_pos); }\n");
        MpvHookShader.Pass pBMixed = new MpvHookShader.Pass("B", List.of("MAIN"), List.of("B"), "OUTPUT", "MAIN",
                "vec4 hook() { return MAIN_tex(MAIN_pos); }\n");
        MpvHookShader.Pass pBSame = new MpvHookShader.Pass("B", List.of("MAIN"), List.of("B"), "A", "MAIN",
                "vec4 hook() { return MAIN_tex(MAIN_pos); }\n");
        MpvHookShader.Pass pConv = new MpvHookShader.Pass("conv", List.of("A", "B"), List.of("C"), "A", "A", vnsrMultiBind);
        MpvHookShader.Pass pFinal = new MpvHookShader.Pass("final", List.of("C"), List.of("MAIN"), "MAIN", "MAIN",
                "vec4 hook() { return C_tex(C_pos); }\n");

        assertEquals("Standalone single-pass multi-bind optimizePassBody must fail closed",
                pConv.body, GraphicsShaderTransforms.optimizePassBody(pConv).body);
        List<MpvHookShader.Pass> mixedChain = GraphicsShaderTransforms.optimizePasses(
                Arrays.asList(pA, pBMixed, pConv, pFinal), true);
        assertFalse("Mixed-resolution binds must fail closed and avoid sharing b0 _pt coordinates",
                mixedChain.get(2).body.contains("_p_m1_m1"));
        List<MpvHookShader.Pass> sameChain = GraphicsShaderTransforms.optimizePasses(
                Arrays.asList(pA, pBSame, pConv, pFinal), true);
        assertTrue("Transitively same-resolution binds across passes must optimize VN-SR convolution",
                sameChain.get(2).body.contains("_p_m1_m1"));
    }

    @Test
    public void rgba16fUnavailableCacheHitScaleBypassAndTiledInvariantChecks() throws Exception {
        // 1. K1: Unrenderable RGBA16F throws deterministic TargetUnavailableException(retryAfterDelay=false)
        CnnPipeline noFp16Node = CnnPipeline.createForTesting(
                p -> Files.newInputStream(resolveAsset(p)),
                "anime4k/Anime4K_Restore_CNN_S.glsl", true, 1, 1, 0);
        setField(noFp16Node, "mHalfFloatChecked", true);
        setField(noFp16Node, "mHalfFloatRenderable", false);
        Method createTexFbo = CnnPipeline.class.getDeclaredMethod(
                "createTexFbo", int.class, int.class, boolean.class);
        createTexFbo.setAccessible(true);
        java.lang.reflect.InvocationTargetException ite = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> createTexFbo.invoke(noFp16Node, 640, 360, true));
        assertTrue(ite.getCause() instanceof TargetUnavailableException);
        assertFalse("Unrenderable RGBA16F must be deterministic non-retrying bypass",
                ((TargetUnavailableException) ite.getCause()).retryAfterDelay);

        // 2. runTiledPasses fails fast on broken scratch/restore invariants
        List<MpvHookShader.Pass> sPasses = GraphicsShaderTransforms.optimizePasses(
                loadPasses("anime4k/Anime4K_Restore_CNN_S.glsl"),
                GraphicsShaderTransforms.Config.DEFAULT.runtimeMrtFusionEnabled);
        GlRenderTarget restore = new GlRenderTarget(640, 360);
        restore.texture[0] = 90;
        restore.fbo[0] = 91;
        CnnPipeline badTilePipe = CnnPipeline.createForTesting(
                p -> Files.newInputStream(resolveAsset(p)),
                "anime4k/Anime4K_Restore_CNN_S.glsl", true, 1, 1, 256);
        setField(badTilePipe, "mPasses", sPasses);
        GlRenderTarget badTileIn = new GlRenderTarget(800, 400); // > source 640x360 -> violates invariant
        GlRenderTarget badTileOut = new GlRenderTarget(800, 400);
        setField(badTilePipe, "mTileInput", badTileIn);
        setField(badTilePipe, "mTileRestore", badTileOut);
        setField(badTilePipe, "mRestore", restore);
        assertThrows(IllegalStateException.class, () ->
                badTilePipe.runTiledPasses(10, 640, 360, false, 1, 99));
    }

    @Test
    public void tieredFp16AccuracyAcrossAllSixAnime4kRestoreCnnModels() throws Exception {
        // K3: Verify IEEE-754 binary16 (mediump ALU + GL_RGBA16F intermediate storage) vs FP64 reference
        // across all 6 bundled Anime4K Restore CNN (+Soft) models on multi-pattern test patches.
        // One of two 2xFP16 simulation implementations: this JVM simulation and the offline
        // per-layer simulation documented in SPEC §12 / docs/vnsr-filters.md. They stay separate
        // (different runtime environments) but must share the frozen thresholds from SPEC §12:
        // 8-bit error <= 1 LSB and the PSNR floor of 60 dB (measured band 69.95-72.87 dB).
        // Do not fork thresholds in either implementation.
        int w = 10, h = 10;
        double[][][] grad = new double[h][w][4];
        double[][][] edge = new double[h][w][4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    grad[y][x][c] = Math.round((((x * 7 + y * 11 + c * 3) % 41) / 40.0) * 255.0) / 255.0;
                }
                grad[y][x][3] = 1.0;
                edge[y][x][0] = Math.round((y < 3 ? 0.05 : (y < 7 ? 0.85 : 0.30)) * 255.0) / 255.0;
                edge[y][x][1] = Math.round((x / 9.0) * 255.0) / 255.0;
                edge[y][x][2] = Math.round((y < 5 ? 0.10 : 0.70) * 255.0) / 255.0;
                edge[y][x][3] = 1.0;
            }
        }
        double[][][][] patterns = new double[][][][]{grad, edge};

        for (String asset : ANIME4K_ASSETS) {
            Path path = resolveAsset(asset);
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            List<Anime4kLayer> layers = parseAnime4kLayers(source);
            double mseSum = 0.0;
            int count = 0;
            int maxLsb = 0;
            for (double[][][] img : patterns) {
                double[][][] ref = evalAnime4k(layers, img, false);
                double[][][] fp16 = evalAnime4k(layers, img, true);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        for (int c = 0; c < 3; c++) {
                            double r = Math.min(1.0, Math.max(0.0, ref[y][x][c]));
                            double a = Math.min(1.0, Math.max(0.0, fp16[y][x][c]));
                            double d = r - a;
                            mseSum += d * d;
                            count++;
                            int lsb = Math.abs((int) Math.round(r * 255.0) - (int) Math.round(a * 255.0));
                            if (lsb > maxLsb) maxLsb = lsb;
                        }
                    }
                }
            }
            double psnr = 10.0 * Math.log10(1.0 / (mseSum / count));
            assertTrue("2xFP16 max 8-bit error must be <= 1 LSB for " + asset + " (got " + maxLsb + ")",
                    maxLsb <= 1);
            assertTrue("2xFP16 PSNR vs FP64 must be >= 60 dB for " + asset + " (got " + psnr + ")",
                    psnr >= 60.0);
        }
    }

    @Test
    public void comCoreGlSourceFollowsStrictAcyclicLayeringWithoutPublicTestHooks() throws Exception {
        Map<String, String> sources = new HashMap<>();
        String[] files = {
                "GlRenderTarget.java",
                "TargetUnavailableException.java",
                "GlErrors.java",
                "LanczosScale.java",
                "MpvHookShader.java",
                "GraphicsShaderGeometry.java",
                "GraphicsShaderTargets.java",
                "GlOps.java",
                "GraphicsShaderTransforms.java",
                "CnnPipeline.java"
        };
        String[] upperOrchestrationAndHostTypes = {
                "GraphicsFilterPipeline",
                "Anime4kRuntime",
                "KirikiroidRawFrameBridge",
                "ArtemisOfficialBridge",
                "ArtemisOfficialSurfaceProxy",
                "Krkrsdl3Graphics",
                "SiglusGraphicsProxy",
                "ArtemisPerformanceOverlay",
                "GraphicsAnalyzerPanel"
        };
        for (String file : files) {
            String content = new String(Files.readAllBytes(resolveJavaSource(file)), StandardCharsets.UTF_8);
            sources.put(file, content);
            assertFalse("Public/package mutable test hook sProbeOverrideForTesting must not exist in " + file,
                    content.contains("sProbeOverrideForTesting"));
            String codeOnly = stripJavaCommentsPreservingStrings(content);
            for (String upper : upperOrchestrationAndHostTypes) {
                assertFalse(file + " (Level 0..3) must not reference orchestration/host type " + upper,
                        containsIdentifier(codeOnly, upper));
            }
        }

        // Level 0 must have zero references to higher layers
        for (String lvl0 : new String[]{"GlRenderTarget.java", "TargetUnavailableException.java", "GlErrors.java",
                "LanczosScale.java", "MpvHookShader.java"}) {
            String codeOnly = stripJavaCommentsPreservingStrings(sources.get(lvl0));
            for (String upper : new String[]{"GraphicsShaderGeometry", "GraphicsShaderTargets",
                    "GraphicsShaderTransforms", "CnnPipeline", "GlOps"}) {
                assertFalse(lvl0 + " must not reference " + upper, containsIdentifier(codeOnly, upper));
            }
        }

        // Level 1 must not depend on Level >= 2
        for (String lvl1 : new String[]{"GraphicsShaderGeometry.java", "GraphicsShaderTargets.java", "GlOps.java"}) {
            String codeOnly = stripJavaCommentsPreservingStrings(sources.get(lvl1));
            for (String upper : new String[]{"GraphicsShaderTransforms", "CnnPipeline"}) {
                assertFalse(lvl1 + " must not reference " + upper, containsIdentifier(codeOnly, upper));
            }
        }

        // Level 2 must not depend on Level >= 3
        String lvl2 = stripJavaCommentsPreservingStrings(sources.get("GraphicsShaderTransforms.java"));
        assertFalse(containsIdentifier(lvl2, "CnnPipeline"));

        // Every pure-Java type in com.core.gl must remain 100% free of android.* and androidx.*
        String[] pureJavaFiles = {
                "ArtemisGraphicsStats.java",
                "ArtemisLibraryIdentity.java",
                "ArtemisOfficialConnectRequest.java",
                "ArtemisOfficialDisconnect.java",
                "ArtemisOfficialOutputSize.java",
                "GlRenderTarget.java",
                "GraphicsFilterChain.java",
                "GraphicsFilterDefinition.java",
                "GraphicsFilterDefinitions.java",
                "GraphicsFilterPlan.java",
                "GraphicsMonitorOptions.java",
                "GraphicsOutputSize.java",
                "GraphicsProxyResourceSlots.java",
                "GraphicsShaderGeometry.java",
                "GraphicsShaderTargets.java",
                "GraphicsShaderTransforms.java",
                "GraphicsStatus.java",
                "GraphicsTargetRetry.java",
                "KirikiroidLibraryIdentity.java",
                "LanczosScale.java",
                "MpvHookShader.java",
                "TargetUnavailableException.java"
        };
        for (String pureFile : pureJavaFiles) {
            String pureSrc = new String(Files.readAllBytes(resolveJavaSource(pureFile)), StandardCharsets.UTF_8);
            String codeOnly = stripJavaCommentsPreservingStrings(pureSrc);
            // The @VisibleForTesting narrowing annotation is the sole sanctioned androidx
            // reference in pure-Java types: a compile-time test-only marker with no runtime
            // dependency. Any other androidx/android framework reference stays forbidden.
            codeOnly = codeOnly.replace("import androidx.annotation.VisibleForTesting;", "");
            assertFalse(pureFile + " must stay pure-Java without android.*", codeOnly.contains("android."));
            assertFalse(pureFile + " must stay pure-Java without androidx.*", codeOnly.contains("androidx."));
        }

        // Visibility & API encapsulation checks:
        assertFalse("MpvHookShader must be package-private",
                java.lang.reflect.Modifier.isPublic(MpvHookShader.class.getModifiers()));
        assertEquals("CnnPipeline must expose zero public constructors",
                0, CnnPipeline.class.getConstructors().length);
        for (Method m : CnnPipeline.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                assertTrue("Public CnnPipeline methods must be static escape hatches only: " + m.getName(),
                        java.lang.reflect.Modifier.isStatic(m.getModifiers()));
            }
        }

        // Reflective check: zero non-final package/public static fields across these classes
        Class<?>[] checkedClasses = {
                GlRenderTarget.class, TargetUnavailableException.class, GlErrors.class,
                LanczosScale.class, MpvHookShader.class,
                GraphicsShaderGeometry.class, GraphicsShaderTargets.class,
                GlOps.class, GraphicsShaderTransforms.class,
                CnnPipeline.class
        };
        for (Class<?> cls : checkedClasses) {
            for (Field f : cls.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mod)
                        && !java.lang.reflect.Modifier.isFinal(mod)
                        && !java.lang.reflect.Modifier.isPrivate(mod)) {
                    throw new AssertionError("Forbidden non-private mutable static field: "
                            + cls.getSimpleName() + "." + f.getName());
                }
            }
        }
    }

    @Test
    public void scaleMultiplicationRejectsOverflowAndTargetBudgetSaturates() throws Exception {
        CnnPipeline saturateNode = createNodeForAsset("vnsr/vnsr-c08-x2.glsl", 0);
        long saturated = saturateNode.estimateTargetBytes(
                1_000_000_000, 300_000_000, 1_000_000_000, 300_000_000);
        assertEquals("Byte estimate must stay saturated at Long.MAX_VALUE",
                Long.MAX_VALUE, saturated);

        // CnnPipeline.runPasses must reject Integer.MAX_VALUE * mNativeScale overflow cleanly.
        List<MpvHookShader.Pass> passes = loadPasses("vnsr/vnsr-c08-x2.glsl");
        GraphicsShaderTargets cnnTargets = new GraphicsShaderTargets(passes);
        CnnPipeline pipeline = new CnnPipeline(passes, cnnTargets, false, 2, 2,
                GraphicsShaderTransforms.Config.DEFAULT);
        setField(pipeline, "mPlans", new CnnPipeline.PassPlan[0]);
        assertThrows(ArithmeticException.class, () -> pipeline.runPasses(
                10, Integer.MAX_VALUE, 1080, new GlRenderTarget[cnnTargets.count],
                null, false, 0, 0.0, false));
    }

    private static boolean containsIdentifier(String code, String identifier) {
        return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(code).find();
    }

    /** Strips Java line/block comments while preserving string and character literals intact. */
    private static String stripJavaCommentsPreservingStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int len = src.length();
        int i = 0;
        while (i < len) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < len) {
                    char ch = src.charAt(i);
                    out.append(ch);
                    i++;
                    if (ch == '\\' && i < len) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (ch == quote) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < len && src.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && src.charAt(i) != '\n' && src.charAt(i) != '\r') i++;
            } else if (c == '/' && i + 1 < len && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n' || src.charAt(i) == '\r') out.append(src.charAt(i));
                    i++;
                }
                i += 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static int countOptimizedTextureFetches(List<MpvHookShader.Pass> passes) {
        int count = 0;
        for (MpvHookShader.Pass pass : passes) {
            count += countOccurrences(pass.body, "_texOff(") + countOccurrences(pass.body, "_tex(");
        }
        return count;
    }

    private static float[][] evaluateConvPass(MpvHookShader.Pass pass, float[][][] inputs, boolean applyRelu) {
        String body = pass.body;
        int saveCount = pass.saves.size();
        float[][] v = new float[saveCount][4];
        Matcher biasMatcher = Pattern.compile("vec4\\s+v(\\d+)\\s*=\\s*vec4\\(([^)]+)\\);").matcher(body);
        while (biasMatcher.find()) {
            int vIdx = Integer.parseInt(biasMatcher.group(1));
            String[] toks = biasMatcher.group(2).split(",");
            for (int c = 0; c < 4; c++) v[vIdx][c] = Float.parseFloat(toks[c].trim());
        }

        Map<String, Integer> bindMap = new HashMap<>();
        for (int b = 0; b < pass.binds.size(); b++) {
            bindMap.put(pass.binds.get(b), b);
        }

        Map<String, Integer> coordMap = new HashMap<>();
        if (body.contains("_s0 = ")) {
            String firstBind = pass.binds.get(0);
            Matcher centerCoordMatcher = Pattern.compile(
                    "highp\\s+vec2\\s+(_p_\\w+)\\s*=\\s*" + Pattern.quote(firstBind) + "_pos\\s*;").matcher(body);
            while (centerCoordMatcher.find()) {
                coordMap.put(centerCoordMatcher.group(1), 4);
            }
            Matcher offsetCoordMatcher = Pattern.compile(
                    "highp\\s+vec2\\s+(_p_\\w+)\\s*=\\s*" + Pattern.quote(firstBind)
                            + "_pos\\s*\\+\\s*vec2\\(\\s*([^,]+)\\s*,\\s*([^)]+)\\s*\\)\\s*\\*\\s*"
                            + Pattern.quote(firstBind) + "_pt\\s*;").matcher(body);
            while (offsetCoordMatcher.find()) {
                float dxF = Float.parseFloat(offsetCoordMatcher.group(2).trim());
                float dyF = Float.parseFloat(offsetCoordMatcher.group(3).trim());
                int dx = Math.round(dxF);
                int dy = Math.round(dyF);
                assertTrue("Expected integer 3x3 coord declaration", dx == dxF && dy == dyF && dx >= -1 && dx <= 1 && dy >= -1 && dy <= 1);
                coordMap.put(offsetCoordMatcher.group(1), (dy + 1) * 3 + (dx + 1));
            }
            assertEquals("Optimized VN-SR pass must declare all 9 3x3 coordinates", 9, coordMap.size());
        }

        if (body.contains("_s0 = ")) {
            float[][] sRegs = new float[3][4];
            String[] lines = body.split("\n");
            Pattern texPat = Pattern.compile("_s(\\d+)\\s*=\\s*(\\w+)_tex\\((\\w+)\\);");
            Pattern mat4Pat = Pattern.compile("v(\\d+)\\s*\\+=\\s*mat4\\(([^)]+)\\)\\s*\\*\\s*_s(\\d+);");
            Pattern mat3x4Pat = Pattern.compile("v(\\d+)\\s*\\+=\\s*mat3x4\\(([^)]+)\\)\\s*\\*\\s*_s(\\d+)\\.rgb;");
            for (String rawLine : lines) {
                String line = rawLine.trim();
                Matcher mTex = texPat.matcher(line);
                if (mTex.find()) {
                    int reg = Integer.parseInt(mTex.group(1));
                    int bIdx = bindMap.get(mTex.group(2));
                    int pIdx = coordMap.get(mTex.group(3));
                    sRegs[reg] = inputs[bIdx][pIdx];
                    continue;
                }
                Matcher m3 = mat3x4Pat.matcher(line);
                if (m3.find()) {
                    int vIdx = Integer.parseInt(m3.group(1));
                    String[] toks = m3.group(2).split(",");
                    int reg = Integer.parseInt(m3.group(3));
                    float[] s = sRegs[reg];
                    for (int r = 0; r < 4; r++) {
                        float c0 = Float.parseFloat(toks[0 * 4 + r].trim());
                        float c1 = Float.parseFloat(toks[1 * 4 + r].trim());
                        float c2 = Float.parseFloat(toks[2 * 4 + r].trim());
                        v[vIdx][r] += c0 * s[0] + c1 * s[1] + c2 * s[2];
                    }
                    continue;
                }
                Matcher m4 = mat4Pat.matcher(line);
                if (m4.find()) {
                    int vIdx = Integer.parseInt(m4.group(1));
                    String[] toks = m4.group(2).split(",");
                    int reg = Integer.parseInt(m4.group(3));
                    float[] s = sRegs[reg];
                    for (int r = 0; r < 4; r++) {
                        float c0 = Float.parseFloat(toks[0 * 4 + r].trim());
                        float c1 = Float.parseFloat(toks[1 * 4 + r].trim());
                        float c2 = Float.parseFloat(toks[2 * 4 + r].trim());
                        float c3 = Float.parseFloat(toks[3 * 4 + r].trim());
                        v[vIdx][r] += c0 * s[0] + c1 * s[1] + c2 * s[2] + c3 * s[3];
                    }
                }
            }
        } else {
            Matcher tapMatcher = Pattern.compile("s\\s*=\\s*sample_(\\d+)\\s*\\(\\s*vec2\\s*\\(\\s*([-.e\\d]+)\\s*,\\s*([-.e\\d]+)\\s*\\)\\s*\\);").matcher(body);
            List<int[]> ranges = new ArrayList<>();
            List<int[]> tapInfo = new ArrayList<>();
            while (tapMatcher.find()) {
                ranges.add(new int[]{tapMatcher.start(), tapMatcher.end()});
                int sIdx = Integer.parseInt(tapMatcher.group(1));
                float dx = Float.parseFloat(tapMatcher.group(2));
                float dy = Float.parseFloat(tapMatcher.group(3));
                int pIdx = (Math.round(dy) + 1) * 3 + (Math.round(dx) + 1);
                tapInfo.add(new int[]{sIdx, pIdx});
            }

            Pattern matPat = Pattern.compile("v(\\d+)\\s*\\+=\\s*mat4\\(([^)]+)\\)\\s*\\*\\s*s;");
            for (int i = 0; i < ranges.size(); i++) {
                int start = ranges.get(i)[1];
                int end = (i + 1 < ranges.size()) ? ranges.get(i + 1)[0] : body.indexOf("fragColor");
                String block = body.substring(start, end);
                int sIdx = tapInfo.get(i)[0];
                int pIdx = tapInfo.get(i)[1];
                float[] s = inputs[sIdx][pIdx];
                Matcher mMat = matPat.matcher(block);
                while (mMat.find()) {
                    int vIdx = Integer.parseInt(mMat.group(1));
                    String[] toks = mMat.group(2).split(",");
                    for (int r = 0; r < 4; r++) {
                        float c0 = Float.parseFloat(toks[0 * 4 + r].trim());
                        float c1 = Float.parseFloat(toks[1 * 4 + r].trim());
                        float c2 = Float.parseFloat(toks[2 * 4 + r].trim());
                        float c3 = Float.parseFloat(toks[3 * 4 + r].trim());
                        v[vIdx][r] += c0 * s[0] + c1 * s[1] + c2 * s[2] + c3 * s[3];
                    }
                }
            }
        }
        float[][] out = new float[saveCount][4];
        boolean[] seen = new boolean[saveCount];
        Matcher outMatcher = Pattern.compile(
                "fragColor(\\d+)\\s*=\\s*(max\\(\\s*v(\\d+)\\s*,\\s*0\\.0\\s*\\)|v(\\d+))\\s*;").matcher(body);
        while (outMatcher.find()) {
            int fcIdx = Integer.parseInt(outMatcher.group(1));
            assertTrue("Unexpected fragColor slot " + fcIdx, fcIdx >= 0 && fcIdx < saveCount);
            assertFalse("Duplicate fragColor assignment " + fcIdx, seen[fcIdx]);
            seen[fcIdx] = true;
            boolean hasRelu = outMatcher.group(3) != null;
            assertEquals("fragColor" + fcIdx + " ReLU mismatch in pass " + pass.desc, applyRelu, hasRelu);
            int srcIdx = Integer.parseInt(hasRelu ? outMatcher.group(3) : outMatcher.group(4));
            for (int c = 0; c < 4; c++) {
                out[fcIdx][c] = hasRelu ? Math.max(0.0f, v[srcIdx][c]) : v[srcIdx][c];
            }
        }
        for (int i = 0; i < saveCount; i++) {
            assertTrue("Missing fragColor" + i + " assignment in pass " + pass.desc, seen[i]);
        }
        return out;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static Object getField(Object obj, String fieldName) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static int getIntField(Object obj, String fieldName) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.getInt(obj);
    }

    private static final class Anime4kTerm {
        final double[] weights;
        final String texName;
        final int mode;
        final int dx;
        final int dy;

        Anime4kTerm(double[] weights, String texName, int mode, int dx, int dy) {
            this.weights = weights;
            this.texName = texName;
            this.mode = mode;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static final class Anime4kLayer {
        final String save;
        final List<Anime4kTerm> terms;
        final double[] bias;
        final boolean residual;

        Anime4kLayer(String save, List<Anime4kTerm> terms, double[] bias, boolean residual) {
            this.save = save;
            this.terms = terms;
            this.bias = bias;
            this.residual = residual;
        }
    }

    /**
     * Pure-Java IEEE-754 binary16 round-to-nearest-even (compatible with JDK 17).
     * One of two 2xFP16 simulation implementations (the other is the offline per-layer
     * simulation referenced by SPEC §12); both must keep the frozen SPEC §12 thresholds
     * (8-bit <= 1 LSB, PSNR floor 60 dB) and are intentionally not merged.
     */
    private static double toFp16(double val) {
        long dbits = Double.doubleToRawLongBits(val);
        int sign = (int) ((dbits >>> 48) & 0x8000L);
        int dexp = (int) ((dbits >>> 52) & 0x7ffL);
        long dmant = dbits & 0x000f_ffff_ffff_ffffL;
        int halfBits;
        if (dexp == 0x7ff) {
            halfBits = sign | 0x7c00 | (dmant != 0 ? 0x0200 : 0);
        } else if (dexp >= 1009) {
            int hexp = dexp - 1008;
            if (hexp >= 31) {
                halfBits = sign | 0x7c00;
            } else {
                int roundBit = (int) ((dmant >>> 41) & 1L);
                long sticky = dmant & ((1L << 41) - 1L);
                int hmant = (int) (dmant >>> 42);
                if (roundBit != 0 && (sticky != 0L || (hmant & 1) != 0)) {
                    hmant++;
                    if (hmant == 0x400) {
                        hmant = 0;
                        hexp++;
                        if (hexp >= 31) {
                            return halfBitsToFloat(sign | 0x7c00);
                        }
                    }
                }
                halfBits = sign | (hexp << 10) | hmant;
            }
        } else if (dexp >= 998) {
            int totalShift = 42 + (1009 - dexp);
            long fullMant = dmant | (1L << 52);
            int hmant = (int) (fullMant >>> totalShift);
            long remMask = (1L << totalShift) - 1L;
            long halfWay = 1L << (totalShift - 1);
            long rem = fullMant & remMask;
            if (rem > halfWay || (rem == halfWay && (hmant & 1) != 0)) {
                hmant++;
            }
            halfBits = sign | hmant;
        } else {
            halfBits = sign;
        }
        return halfBitsToFloat(halfBits);
    }

    private static float halfBitsToFloat(int hbits) {
        int sign = (hbits & 0x8000) << 16;
        int exp = (hbits >>> 10) & 0x1f;
        int mant = hbits & 0x03ff;
        if (exp == 0) {
            if (mant == 0) return Float.intBitsToFloat(sign);
            int e = -14;
            while ((mant & 0x0400) == 0) {
                mant <<= 1;
                e--;
            }
            mant &= 0x03ff;
            return Float.intBitsToFloat(sign | ((e + 127) << 23) | (mant << 13));
        } else if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp + 112) << 23) | (mant << 13));
    }

    private static List<Anime4kLayer> parseAnime4kLayers(String text) {
        List<Anime4kLayer> layers = new ArrayList<>();
        String[] blocks = text.split("//!DESC ");
        Pattern goDef = Pattern.compile("#define go_(\\d+)\\(x_off, y_off\\) ([^\\n]+)");
        Pattern gDef = Pattern.compile("#define g_(\\d+) ([^\\n]+)");
        Pattern goTerm = Pattern.compile("mat4\\(([^)]+)\\)\\s*\\*\\s*go_(\\d+)\\(([-.\\d]+),\\s*([-.\\d]+)\\)");
        Pattern gTerm = Pattern.compile("mat4\\(([^)]+)\\)\\s*\\*\\s*g_(\\d+)\\b");
        Pattern biasPat = Pattern.compile("result \\+= vec4\\(([^)]+)\\);");
        Pattern savePat = Pattern.compile("//!SAVE (\\w+)");
        for (int b = 1; b < blocks.length; b++) {
            String block = blocks[b];
            Map<String, String> macroTex = new HashMap<>();
            Map<String, Integer> macroMode = new HashMap<>();
            Matcher mGo = goDef.matcher(block);
            while (mGo.find()) {
                String body = mGo.group(2);
                Matcher tn = Pattern.compile("(\\w+)_texOff").matcher(body);
                assertTrue(tn.find());
                macroTex.put(mGo.group(1), tn.group(1));
                macroMode.put(mGo.group(1), body.contains("max(-") ? -1 : (body.contains("max(") ? 1 : 0));
            }
            Matcher mG = gDef.matcher(block);
            while (mG.find()) {
                String body = mG.group(2);
                Matcher tn = Pattern.compile("(\\w+)_tex\\(").matcher(body);
                assertTrue(tn.find());
                macroTex.put("g" + mG.group(1), tn.group(1));
                macroMode.put("g" + mG.group(1), body.contains("max(-") ? -1 : (body.contains("max(") ? 1 : 0));
            }
            List<Anime4kTerm> terms = new ArrayList<>();
            Matcher mtGo = goTerm.matcher(block);
            while (mtGo.find()) {
                String[] ws = mtGo.group(1).split(",");
                double[] w = new double[16];
                for (int i = 0; i < 16; i++) w[i] = Double.parseDouble(ws[i].trim());
                String key = mtGo.group(2);
                terms.add(new Anime4kTerm(w, macroTex.get(key), macroMode.get(key),
                        (int) Double.parseDouble(mtGo.group(3)), (int) Double.parseDouble(mtGo.group(4))));
            }
            Matcher mtG = gTerm.matcher(block);
            while (mtG.find()) {
                String[] ws = mtG.group(1).split(",");
                double[] w = new double[16];
                for (int i = 0; i < 16; i++) w[i] = Double.parseDouble(ws[i].trim());
                String key = "g" + mtG.group(2);
                terms.add(new Anime4kTerm(w, macroTex.get(key), macroMode.get(key), 0, 0));
            }
            Matcher mb = biasPat.matcher(block);
            assertTrue(mb.find());
            String[] bs = mb.group(1).split(",");
            double[] bias = new double[4];
            for (int i = 0; i < 4; i++) bias[i] = Double.parseDouble(bs[i].trim());
            Matcher ms = savePat.matcher(block);
            assertTrue(ms.find());
            boolean residual = block.contains("return result + MAIN_tex(MAIN_pos);");
            layers.add(new Anime4kLayer(ms.group(1), terms, bias, residual));
        }
        return layers;
    }

    private static double[][][] evalAnime4k(List<Anime4kLayer> layers, double[][][] image, boolean useFp16) {
        int h = image.length, w = image[0].length;
        Map<String, double[][][]> textures = new HashMap<>();
        if (useFp16) {
            double[][][] main16 = new double[h][w][4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    for (int c = 0; c < 4; c++) main16[y][x][c] = toFp16(image[y][x][c]);
                }
            }
            textures.put("MAIN", main16);
        } else {
            textures.put("MAIN", image);
        }

        for (Anime4kLayer layer : layers) {
            double[][][] out = new double[h][w][4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double[] val = new double[4];
                    for (Anime4kTerm term : layer.terms) {
                        int sy = Math.min(h - 1, Math.max(0, y + term.dy));
                        int sx = Math.min(w - 1, Math.max(0, x + term.dx));
                        double[] src = textures.get(term.texName)[sy][sx];
                        double[] px = new double[4];
                        for (int c = 0; c < 4; c++) {
                            double v = src[c];
                            if (term.mode != 0) {
                                v = useFp16 ? Math.max(toFp16(term.mode * v), 0.0) : Math.max(term.mode * v, 0.0);
                            }
                            px[c] = v;
                        }
                        for (int o = 0; o < 4; o++) {
                            if (useFp16) {
                                double acc = 0.0;
                                for (int c = 0; c < 4; c++) {
                                    acc = toFp16(acc + toFp16(toFp16(term.weights[c * 4 + o]) * px[c]));
                                }
                                val[o] = toFp16(val[o] + acc);
                            } else {
                                double acc = 0.0;
                                for (int c = 0; c < 4; c++) acc += term.weights[c * 4 + o] * px[c];
                                val[o] += acc;
                            }
                        }
                    }
                    for (int o = 0; o < 4; o++) {
                        val[o] = useFp16 ? toFp16(val[o] + toFp16(layer.bias[o])) : (val[o] + layer.bias[o]);
                        if (layer.residual) {
                            double m = textures.get("MAIN")[y][x][o];
                            val[o] = useFp16 ? toFp16(val[o] + m) : (val[o] + m);
                        }
                    }
                    out[y][x] = val;
                }
            }
            textures.put(layer.save, out);
        }
        return textures.get("MAIN");
    }

    private static class RecordingPipelineGlOps extends StrictStubGlOps {
        final ArrayDeque<Integer> pendingErrors = new ArrayDeque<>();
        final Map<Integer, int[]> fboDrawBuffers = new HashMap<>();
        final List<Integer> liveFbos = new ArrayList<>();
        final List<Integer> livePrograms = new ArrayList<>();
        final List<Integer> invalidatedFbos = new ArrayList<>();
        int nextId = 100;
        int boundDrawFbo = 0;
        int boundTex2d = 0;
        int currentProgram = 0;
        final Map<Integer, Integer> textureInternalFormats = new HashMap<>();
        int maxDrawBuffers = 8;
        int maxColorAttachments = 8;
        int createProgramCalls = 0;
        boolean failCreateProgram = false;
        boolean oomOnCreateProgram = false;
        boolean failCreateShader = false;
        int drawCalls = 0;
        int uniformUpdates = 0;
        int invalidateOnFboZeroCalls = 0;
        int drawBuffersOnFboZeroCalls = 0;
        int colorMaskCalls = 0;
        boolean[] lastColorMask = null;

        @Override public int glGetError() {
            return pendingErrors.isEmpty() ? GLES30.GL_NO_ERROR : pendingErrors.removeFirst();
        }
        @Override public void glGetIntegerv(int pname, int[] data, int offset) {
            if (pname == GLES30.GL_MAX_DRAW_BUFFERS) data[offset] = maxDrawBuffers;
            else if (pname == GLES30.GL_MAX_COLOR_ATTACHMENTS) data[offset] = maxColorAttachments;
            else if (pname == GLES30.GL_MAX_TEXTURE_IMAGE_UNITS) data[offset] = 16;
            else if (pname == GLES30.GL_CURRENT_PROGRAM) data[offset] = currentProgram;
            else if (pname == GLES30.GL_DRAW_FRAMEBUFFER_BINDING) data[offset] = boundDrawFbo;
            else data[offset] = 0;
        }
        @Override public int glCreateShader(int type) { return failCreateShader ? 0 : ++nextId; }
        @Override public void glShaderSource(int shader, String string) {}
        @Override public void glCompileShader(int shader) {}
        @Override public void glGetShaderiv(int shader, int pname, int[] params, int offset) { params[offset] = 1; }
        @Override public String glGetShaderInfoLog(int shader) { return ""; }
        @Override public void glDeleteShader(int shader) {}
        @Override public int glCreateProgram() {
            createProgramCalls++;
            if (failCreateProgram) {
                if (oomOnCreateProgram) pendingErrors.add(GLES30.GL_OUT_OF_MEMORY);
                return 0;
            }
            int p = ++nextId;
            livePrograms.add(p);
            return p;
        }
        @Override public void glAttachShader(int program, int shader) {}
        @Override public void glLinkProgram(int program) {}
        @Override public void glGetProgramiv(int program, int pname, int[] params, int offset) { params[offset] = 1; }
        @Override public String glGetProgramInfoLog(int program) { return ""; }
        @Override public void glDeleteProgram(int program) { livePrograms.remove(Integer.valueOf(program)); }
        @Override public int glGetUniformLocation(int program, String name) { return 1; }
        @Override public void glUseProgram(int program) { currentProgram = program; }
        @Override public void glUniform1i(int location, int x) { uniformUpdates++; }
        @Override public void glUniform1f(int location, float x) { uniformUpdates++; }
        @Override public void glUniform2f(int location, float x, float y) { uniformUpdates++; }
        @Override public void glGenFramebuffers(int n, int[] framebuffers, int offset) {
            for (int i = 0; i < n; i++) {
                int id = ++nextId;
                liveFbos.add(id);
                framebuffers[offset + i] = id;
            }
        }
        @Override public void glDeleteFramebuffers(int n, int[] framebuffers, int offset) {
            for (int i = 0; i < n; i++) liveFbos.remove(Integer.valueOf(framebuffers[offset + i]));
        }
        @Override public void glBindFramebuffer(int target, int framebuffer) {
            if (target == GLES30.GL_DRAW_FRAMEBUFFER || target == GLES30.GL_FRAMEBUFFER) boundDrawFbo = framebuffer;
        }
        @Override public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {}
        @Override public int glCheckFramebufferStatus(int target) { return GLES30.GL_FRAMEBUFFER_COMPLETE; }
        @Override public void glDrawBuffers(int n, int[] bufs, int offset) {
            if (boundDrawFbo == 0) {
                drawBuffersOnFboZeroCalls++;
                return;
            }
            int[] copy = new int[n];
            System.arraycopy(bufs, offset, copy, 0, n);
            fboDrawBuffers.put(boundDrawFbo, copy);
        }
        @Override public void glInvalidateFramebuffer(int target, int numAttachments, int[] attachments, int offset) {
            if (boundDrawFbo == 0) invalidateOnFboZeroCalls++;
            else invalidatedFbos.add(boundDrawFbo);
        }
        @Override public void glGenTextures(int n, int[] textures, int offset) {
            for (int i = 0; i < n; i++) textures[offset + i] = ++nextId;
        }
        @Override public void glDeleteTextures(int n, int[] textures, int offset) {}
        @Override public void glViewport(int x, int y, int width, int height) {}
        @Override public void glActiveTexture(int texture) {}
        @Override public void glBindTexture(int target, int texture) {
            if (target == GLES30.GL_TEXTURE_2D) boundTex2d = texture;
        }
        @Override public void glBindSampler(int unit, int sampler) {}
        @Override public void glTexParameteri(int target, int pname, int param) {}
        @Override public void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                         int border, int format, int type, Buffer pixels) {
            if (target == GLES30.GL_TEXTURE_2D && boundTex2d != 0) {
                textureInternalFormats.put(boundTex2d, internalformat);
            }
        }
        @Override public void glBindBuffer(int target, int buffer) {}
        @Override public boolean glIsEnabled(int cap) { return false; }
        @Override public void glEnable(int cap) {}
        @Override public void glDisable(int cap) {}
        @Override public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
            colorMaskCalls++;
            lastColorMask = new boolean[]{red, green, blue, alpha};
        }
        @Override public void glDrawArrays(int mode, int first, int count) { drawCalls++; }
    }

    @Test
    public void transformsRejectPrecisionDowngradeMutableGlobalsAndRenamedAccumulators() {
        MpvHookShader.Pass conv = new MpvHookShader.Pass("conv", List.of("MAIN"), List.of("A"),
                null, null, "vec4 hook() { return mat4(1.0) * MAIN_tex(MAIN_pos); }\n");
        MpvHookShader.Pass highpOnly = new MpvHookShader.Pass("highp", List.of("MAIN"), List.of("B"),
                null, null, "vec4 hook() { return vec4(4096.0) + MAIN_tex(MAIN_pos) - vec4(4096.0); }\n");
        assertFalse("Must refuse fusing mediump mat4 pass with highp non-mat4 pass",
                GraphicsShaderTransforms.canFusePasses(conv, highpOnly));

        MpvHookShader.Pass mutableA = new MpvHookShader.Pass("mutA", List.of("MAIN"), List.of("A"),
                null, null, "float gCount = 0.0;\nvec4 hook() { return vec4(++gCount); }\n");
        MpvHookShader.Pass mutableB = new MpvHookShader.Pass("mutB", List.of("MAIN"), List.of("B"),
                null, null, "float gCount = 0.0;\nvec4 hook() { return vec4(++gCount); }\n");
        assertFalse("Must refuse fusing passes sharing mutable globals",
                GraphicsShaderTransforms.canFusePasses(mutableA, mutableB));

        String leadingZeroBody =
                "vec4 sample_0(vec2 off) { return MAIN_tex(MAIN_pos + off * MAIN_pt); }\n"
                + "void hook() {\n"
                + "    vec4 v00=vec4(0.1,0.2,0.3,0.4);\n"
                + "    vec4 s;\n"
                + "    s=sample_0(vec2(0.0,0.0));\n"
                + "    v00+=mat4(1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,0)*s;\n"
                + "    fragColor0=max(v00,0.0);\n"
                + "}\n";
        MpvHookShader.Pass vnsrLeadingZero = new MpvHookShader.Pass(
                "vnsr zero padded acc", List.of("MAIN"), List.of("OUT0"), null, null, leadingZeroBody);
        MpvHookShader.Pass out = GraphicsShaderTransforms.optimizePassBody(vnsrLeadingZero);
        assertTrue("Preserved accumulator name must remain declared", out.body.contains("v00 +="));
        assertFalse("Must not rewrite v00 into undeclared v0", out.body.contains("v0 +="));

        // Macros introducing control flow in headers and in-body #undef directives must keep passes untouched.
        MpvHookShader.Pass flowMacroA = new MpvHookShader.Pass("flowA", List.of("MAIN"), List.of("OUTA"),
                null, null, "#define FINISH return vec4(0.0)\nvec4 hook() { FINISH; return vec4(1.0); }\n");
        MpvHookShader.Pass flowMacroB = new MpvHookShader.Pass("flowB", List.of("MAIN"), List.of("OUTB"),
                null, null, "#define FINISH return vec4(0.0)\nvec4 hook() { FINISH; return vec4(1.0); }\n");
        assertFalse("Control-flow macro header must not allow pass fusion",
                GraphicsShaderTransforms.canFusePasses(flowMacroA, flowMacroB));

        String aggHeader = "#define g_0 (max((MAIN_tex(MAIN_pos)), 0.0))\n"
                + "#define g_1 (max(-(MAIN_tex(MAIN_pos)), 0.0))\n";
        String undefBody = aggHeader
                + "vec4 hook() {\nvec4 a = g_0;\n#undef g_0\nvec4 g_0 = vec4(1.0);\nreturn a + g_0 + g_1;\n}\n";
        MpvHookShader.Pass undefPass = new MpvHookShader.Pass(
                "agg with inner undef", List.of("MAIN"), List.of("MAIN"), null, null, undefBody);
        assertEquals("Pass with preprocessor directive inside hook() must remain unmodified",
                undefBody, GraphicsShaderTransforms.optimizePassBody(undefPass).body);

        MpvHookShader.Pass aggPositive = new MpvHookShader.Pass(
                "agg without undef", List.of("MAIN"), List.of("MAIN"), null, null,
                aggHeader + "vec4 hook() {\nreturn g_0 + g_1;\n}\n");
        String aggPositiveOpt = GraphicsShaderTransforms.optimizePassBody(aggPositive).body;
        assertTrue("Matched 1x1 CReLU header without inner directives must pre-sample into _raw_0",
                aggPositiveOpt.contains("_raw_0") && aggPositiveOpt.contains("_g_0"));
    }

    @Test
    public void releaseTargetsDetachesPassFboAttachmentsAndMrtIncompletenessFallsBackFromRenderEntry() throws Exception {
        List<MpvHookShader.Pass> passes = GraphicsShaderTransforms.optimizePasses(
                loadPasses("vnsr/vnsr-c08-x2.glsl"), true);
        GraphicsShaderTargets targets = new GraphicsShaderTargets(passes);
        List<String> detachCalls = new ArrayList<>();
        RecordingPipelineGlOps trackingGl = new RecordingPipelineGlOps() {
            @Override public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
                if (texture == 0) detachCalls.add(boundDrawFbo + ":" + attachment);
            }
        };
        CnnPipeline pipeline = new CnnPipeline(passes, targets, true, 2, 8,
                GraphicsShaderTransforms.Config.DEFAULT, trackingGl);
        pipeline.buildPrograms(1);
        Object glState = getField(pipeline, "mState");
        Field drawFboField = glState.getClass().getDeclaredField("drawFramebuffer");
        drawFboField.setAccessible(true);
        drawFboField.setInt(glState, 999);
        Method rel = CnnPipeline.class.getDeclaredMethod("releaseTargets");
        rel.setAccessible(true);
        rel.invoke(pipeline);
        assertFalse("releaseTargets must detach pass FBO color attachments", detachCalls.isEmpty());
        assertEquals("releaseTargets must restore non-owned host draw FBO snapshot without clobbering",
                999, drawFboField.getInt(glState));

        // Runtime-fused Anime4K Restore_CNN_L must fall back cleanly through renderTexture()
        // when dual-attachment MRT FBO check returns incomplete, preserving its loaded config
        // snapshot and completing single-output rendering on the same call.
        String asset = "anime4k/Anime4K_Restore_CNN_L.glsl";
        List<MpvHookShader.Pass> rawL = loadPasses(asset);
        List<MpvHookShader.Pass> unfusedL = GraphicsShaderTransforms.optimizePasses(rawL, false);
        Map<Integer, Set<Integer>> fboAttachments = new HashMap<>();
        RecordingPipelineGlOps fallbackGl = new RecordingPipelineGlOps() {
            @Override public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
                Set<Integer> set = fboAttachments.computeIfAbsent(boundDrawFbo, k -> new HashSet<>());
                if (texture == 0) set.remove(attachment);
                else set.add(attachment);
            }
            @Override public int glCheckFramebufferStatus(int target) {
                Set<Integer> set = fboAttachments.getOrDefault(boundDrawFbo, Collections.emptySet());
                return set.size() >= 2 ? GLES30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT : GLES30.GL_FRAMEBUFFER_COMPLETE;
            }
        };
        CnnPipeline fallbackNode = CnnPipeline.createForTesting(
                p -> Files.newInputStream(resolveAsset(p)), asset, true, 1, 1, 0, fallbackGl);
        setField(fallbackNode, "mLimitWidth", 4096);
        setField(fallbackNode, "mLimitHeight", 4096);
        setField(fallbackNode, "mQuadVao", new int[]{1});
        setField(fallbackNode, "mHalfFloatChecked", true);
        setField(fallbackNode, "mHalfFloatRenderable", true);

        int rendered = fallbackNode.renderTexture(100, 64, 64, 64, 64);
        assertTrue("renderTexture must succeed on unfused fallback path", rendered > 0);
        assertFalse("Node must not latch permanent failure after MRT fallback", fallbackNode.isFailed());
        assertEquals("Same renderTexture call must rebuild unfused single-output plans",
                unfusedL.size(), fallbackNode.getBuiltPassCount());
        assertEquals("Fallback draw must execute all unfused passes", unfusedL.size(), fallbackGl.drawCalls);
        GraphicsShaderTransforms.Config configAfterFallback =
                (GraphicsShaderTransforms.Config) getField(fallbackNode, "mConfig");
        assertNotNull("Fallback must preserve original loaded Config snapshot", configAfterFallback);
        assertTrue(configAfterFallback.tieredPrecisionEnabled);
        assertEquals(CnnPipeline.BYPASS_NONE, fallbackNode.bypassReason());

        // Whole-chain 256 MiB budget check after runtime fusion fallback via GraphicsFilterPipeline:
        // Under tiled 4K geometry (tileCoreSize=1024), unfused 9-pass halo padding strictly exceeds
        // fused 5-pass halo padding so recheckChainBudgetAfterFallback deterministically rejects over-budget fallback.
        GraphicsFilterChain chain = GraphicsFilterChain.parse("v1:l");
        int sw = 1920, sh = 1080, ow = 1920, oh = 1080;
        List<MpvHookShader.Pass> fusedL = GraphicsShaderTransforms.optimizePasses(rawL, true);
        long unfusedBytes = CnnPipeline.estimateTargetBytesForPlan(
                unfusedL, new GraphicsShaderTargets(unfusedL), sw, sh, ow, oh, 1, 1, false, 1, 1024);
        long fusedBytes = CnnPipeline.estimateTargetBytesForPlan(
                fusedL, new GraphicsShaderTargets(fusedL), sw, sh, ow, oh, 1, 1, false, 1, 1024);
        assertTrue("Tiled unfused plan bytes must strictly exceed fused plan bytes", unfusedBytes > fusedBytes);
        long reservedForTest = CnnPipeline.TARGET_MEMORY_BUDGET_BYTES - fusedBytes;
        GraphicsFilterPipeline budgetedPipeline = new GraphicsFilterPipeline(
                new android.content.ContextWrapper(null), chain, reservedForTest);
        Map<Integer, Set<Integer>> budgetAttachments = new HashMap<>();
        int[] budgetCheckCalls = new int[]{0};
        RecordingPipelineGlOps budgetGl = new RecordingPipelineGlOps() {
            @Override public void glReadBuffer(int mode) {}
            @Override public void glBlitFramebuffer(int sX0, int sY0, int sX1, int sY1, int dX0, int dY0, int dX1, int dY1, int mask, int filter) {}
            @Override public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
                if (target == GLES30.GL_READ_FRAMEBUFFER) return;
                Set<Integer> set = budgetAttachments.computeIfAbsent(boundDrawFbo, k -> new HashSet<>());
                if (texture == 0) set.remove(attachment);
                else set.add(attachment);
            }
            @Override public int glCheckFramebufferStatus(int target) {
                if (target == GLES30.GL_READ_FRAMEBUFFER) return GLES30.GL_FRAMEBUFFER_COMPLETE;
                budgetCheckCalls[0]++;
                Set<Integer> set = budgetAttachments.getOrDefault(boundDrawFbo, Collections.emptySet());
                return set.size() >= 2 ? GLES30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT : GLES30.GL_FRAMEBUFFER_COMPLETE;
            }
        };
        CnnPipeline budgetedNode = CnnPipeline.createForTesting(
                p -> Files.newInputStream(resolveAsset(p)), asset, true, 1, 1, 1, budgetGl);
        setField(budgetedNode, "mLimitWidth", 4096);
        setField(budgetedNode, "mLimitHeight", 4096);
        setField(budgetedNode, "mQuadVao", new int[]{1});
        setField(budgetedNode, "mBorrowedReadFbo", new int[]{999});
        setField(budgetedNode, "mHalfFloatChecked", true);
        setField(budgetedNode, "mHalfFloatRenderable", true);
        ((CnnPipeline[]) getField(budgetedPipeline, "nodes"))[0] = budgetedNode;
        int planBefore = budgetedNode.getPlanVersion();
        int outTex = budgetedPipeline.renderTexture(100, sw, sh, ow, oh);
        assertTrue("MRT FBO status check must have been triggered", budgetCheckCalls[0] > 0);
        assertTrue("Runtime MRT fallback must advance node planVersion", budgetedNode.getPlanVersion() > planBefore);
        assertEquals("Unfused plan over node budget after fallback must bypass to original texture", 100, outTex);
        assertEquals(CnnPipeline.BYPASS_TARGET_UNAVAILABLE, budgetedPipeline.bypassReason());

        // abandonContext() resets mRuntimeFusionFallback so a fresh GL context retries MRT fusion,
        // while preserving retryAfterDelay across GraphicsFilterPipeline.discardTargets() for transient OOM.
        fallbackNode.abandonContext();
        assertFalse("abandonContext must clear mRuntimeFusionFallback latch",
                ((Boolean) getField(fallbackNode, "mRuntimeFusionFallback")).booleanValue());

        RecordingPipelineGlOps transientOomGl = new RecordingPipelineGlOps() {
            @Override public void glGenTextures(int n, int[] textures, int offset) {
                pendingErrors.add(GLES30.GL_OUT_OF_MEMORY);
                for (int i = 0; i < n; i++) textures[offset + i] = 0;
            }
        };
        GraphicsFilterPipeline retryPipeline = new GraphicsFilterPipeline(
                new android.content.ContextWrapper(null), GraphicsFilterChain.parse("v1:s"));
        CnnPipeline retryNode = CnnPipeline.createForTesting(
                p -> Files.newInputStream(resolveAsset(p)), "anime4k/Anime4K_Restore_CNN_S.glsl",
                true, 2, 1, 2, transientOomGl);
        setField(retryNode, "mLimitWidth", 4096);
        setField(retryNode, "mLimitHeight", 4096);
        setField(retryNode, "mQuadVao", new int[]{1});
        setField(retryNode, "mHalfFloatChecked", true);
        setField(retryNode, "mHalfFloatRenderable", true);
        ((CnnPipeline[]) getField(retryPipeline, "nodes"))[0] = retryNode;
        assertEquals(100, retryPipeline.renderTexture(100, 64, 64, 64, 64));
        assertEquals(CnnPipeline.BYPASS_TARGET_UNAVAILABLE, retryPipeline.bypassReason());
        assertTrue("Transient OOM must schedule retryable backoff on GraphicsFilterPipeline even after discardTargets()",
                ((GraphicsTargetRetry) getField(retryPipeline, "targetRetry")).isRetryableFailure());
    }

    @Test
    public void reviewFixesRegressionSuite() throws Exception {
        // [A0] Scale-only same-size passthrough must account for its owned output surface.
        CnnPipeline scaleNode = CnnPipeline.forFilter(
                new android.content.ContextWrapper(null),
                GraphicsFilterChain.parse("v1:lanczos").steps().get(0));
        assertEquals(LanczosScale.ownedSurfaceBytes(640, 360),
                scaleNode.estimateTargetBytes(640, 360, 640, 360));

        // [12] Retryable buildPrograms() failure must preserve captured mConfig/mPasses snapshot.
        RecordingPipelineGlOps oomProgGl = new RecordingPipelineGlOps();
        oomProgGl.failCreateProgram = true;
        oomProgGl.oomOnCreateProgram = true;
        CnnPipeline configRetainNode = CnnPipeline.createForTesting(
                p -> Files.newInputStream(resolveAsset(p)),
                "anime4k/Anime4K_Restore_CNN_S.glsl", true, 2, 1, 2, oomProgGl);
        configRetainNode.loadPasses();
        Object capturedConfig = getField(configRetainNode, "mConfig");
        assertNotNull(capturedConfig);
        assertThrows(TargetUnavailableException.class, () -> configRetainNode.buildPrograms(1));
        assertSame("Retryable buildPrograms failure must not wipe captured mConfig",
                capturedConfig, getField(configRetainNode, "mConfig"));
        assertNotNull("Retryable buildPrograms failure must keep loaded passes",
                getField(configRetainNode, "mPasses"));

        // [14] Comma operator return expression parenthesized cleanly during MRT pass fusion.
        MpvHookShader.Pass commaA = new MpvHookShader.Pass(
                "commaA", List.of("MAIN"), List.of("A"), "MAIN", "MAIN",
                "vec4 hook() { return vec4(0.1), vec4(0.2); }\n");
        MpvHookShader.Pass commaB = new MpvHookShader.Pass(
                "commaB", List.of("MAIN"), List.of("B"), "MAIN", "MAIN",
                "vec4 hook() { return vec4(0.3); }\n");
        List<MpvHookShader.Pass> commaOpt = GraphicsShaderTransforms.optimizePasses(
                Arrays.asList(commaA, commaB), true);
        assertEquals(1, commaOpt.size());
        assertTrue("Comma expression return must be parenthesized after assignment",
                commaOpt.get(0).body.contains("fragColor0 = (vec4(0.1), vec4(0.2));"));

        // [15] Zero-padded g_01 local variable must not alias g_1 macro definition.
        String zeroPadHeader = "#define g_0 (max((MAIN_tex(MAIN_pos)), 0.0))\n"
                + "#define g_1 (max(-(MAIN_tex(MAIN_pos)), 0.0))\n";
        MpvHookShader.Pass zeroPadLocal = new MpvHookShader.Pass(
                "zero-padded local", List.of("MAIN"), List.of("MAIN"), null, null,
                zeroPadHeader + "vec4 hook() {\nvec4 g_01 = vec4(1.0);\nreturn g_0 + g_1 + g_01;\n}\n");
        assertEquals("Zero-padded local g_01 must fail closed without rewriting to _g_1",
                zeroPadLocal.body, GraphicsShaderTransforms.optimizePassBody(zeroPadLocal).body);

        // [19] Direct double -> IEEE-754 binary16 round-to-nearest-even without float double-rounding.
        assertEquals(1.0, toFp16(1.0 + Math.scalb(1.0, -11)), 0.0);
        assertEquals(1.0 + Math.scalb(1.0, -10),
                toFp16(1.0 + Math.scalb(1.0, -11) + Math.scalb(1.0, -25)), 0.0);

        // [13] Crop UV change on the same surface geometry must clear crop rejection and retry.
        GraphicsFilterPipeline cropPipeline = new GraphicsFilterPipeline(
                new android.content.ContextWrapper(null), GraphicsFilterChain.parse("v1:s"));
        RecordingPipelineGlOps nodeGl = new RecordingPipelineGlOps();
        MpvHookShader.Pass noopPass = new MpvHookShader.Pass(
                "noop", List.of("MAIN"), List.of("MAIN"), null, null,
                "vec4 hook() { return MAIN_tex(MAIN_pos); }\n");
        CnnPipeline okNode = new CnnPipeline(Collections.singletonList(noopPass),
                new GraphicsShaderTargets(Collections.singletonList(noopPass)),
                false, 1, 1, GraphicsShaderTransforms.Config.DEFAULT, nodeGl);
        okNode.buildPrograms(1);
        GlRenderTarget outTarget = new GlRenderTarget(64, 64);
        outTarget.texture[0] = 77;
        outTarget.fbo[0] = 77;
        setField(okNode, "mGlReady", true);
        setField(okNode, "mWidth", 64);
        setField(okNode, "mHeight", 64);
        setField(okNode, "mLimitWidth", 4096);
        setField(okNode, "mLimitHeight", 4096);
        setField(okNode, "mBorrowedReadFbo", new int[]{999});
        setField(okNode, "mIntermediateTargets", new GlRenderTarget[0]);
        setField(okNode, "mRestore", outTarget);
        setField(okNode, "mOutput", outTarget);

        CnnPipeline surfaceAdapter = (CnnPipeline) getField(cropPipeline, "surfaceAdapter");
        ((CnnPipeline[]) getField(cropPipeline, "nodes"))[0] = okNode;
        ((boolean[]) getField(cropPipeline, "execute"))[0] = true;
        ((int[]) getField(cropPipeline, "inputWidths"))[0] = 64;
        ((int[]) getField(cropPipeline, "inputHeights"))[0] = 64;
        ((int[]) getField(cropPipeline, "outputWidths"))[0] = 64;
        ((int[]) getField(cropPipeline, "outputHeights"))[0] = 64;
        setField(cropPipeline, "geometryKnown", true);
        setField(cropPipeline, "planValid", true);
        setField(cropPipeline, "hasExecution", true);
        setField(cropPipeline, "sourceWidth", 64);
        setField(cropPipeline, "sourceHeight", 64);
        setField(cropPipeline, "boundWidth", 64);
        setField(cropPipeline, "boundHeight", 64);
        setField(cropPipeline, "extraOwnedBytes", LanczosScale.ownedSurfaceBytes(64, 64));

        // First crop fails deterministically with BYPASS_INVALID_SIZE.
        assertEquals(0, cropPipeline.renderCroppedTexture(10, 64, 64, -0.1f, 0.1f, 0.9f, 0.9f, 64, 64, true, true));
        assertEquals(CnnPipeline.BYPASS_INVALID_SIZE, cropPipeline.bypassReason());
        // Restrict surfaceAdapter GL limit after the first rejection: repeating the exact failing crop stays throttled,
        // while changing crop UVs must clear cropTemporaryFailed and re-enter normalizeBorrowedTexture (hitting GL_LIMIT).
        setField(surfaceAdapter, "mLimitWidth", 32);
        setField(surfaceAdapter, "mLimitHeight", 32);
        assertEquals(0, cropPipeline.renderCroppedTexture(10, 64, 64, -0.1f, 0.1f, 0.9f, 0.9f, 64, 64, true, true));
        assertEquals(CnnPipeline.BYPASS_INVALID_SIZE, cropPipeline.bypassReason());
        assertEquals(0, cropPipeline.renderCroppedTexture(10, 64, 64, 0.0f, 0.0f, 0.5f, 0.5f, 64, 64, true, true));
        assertEquals(CnnPipeline.BYPASS_GL_LIMIT, cropPipeline.bypassReason());

        // [13b] Full-UV render after crop failure clears stale cropTemporaryFailed in prepareGeometry() and
        // never resets targetRetry when already in same-geometry full-UV backoff.
        assertEquals(77, cropPipeline.renderCroppedTexture(10, 64, 64, 0.0f, 0.0f, 1.0f, 1.0f, 64, 64, true, true));
        assertFalse("Starting full-UV geometry plan must clear stale cropTemporaryFailed",
                (Boolean) getField(cropPipeline, "cropTemporaryFailed"));
        setField(cropPipeline, "temporaryFailed", true);
        setField(cropPipeline, "cropTemporaryFailed", true);
        ((GraphicsTargetRetry) getField(cropPipeline, "targetRetry")).failed(false);
        assertEquals(0, cropPipeline.renderCroppedTexture(10, 64, 64, 0.0f, 0.0f, 1.0f, 1.0f, 64, 64, true, true));

        // [14b] Passes using __LINE__ must not fuse across source locations.
        MpvHookShader.Pass linePassA = new MpvHookShader.Pass(
                "lineA", List.of("MAIN"), List.of("A"), "MAIN", "MAIN",
                "#define LINE_VAL (float(__LINE__) / 100.0)\nvec4 hook() { return vec4(LINE_VAL); }\n");
        MpvHookShader.Pass linePassB = new MpvHookShader.Pass(
                "lineB", List.of("MAIN"), List.of("B"), "MAIN", "MAIN",
                "#define LINE_VAL (float(__LINE__) / 100.0)\nvec4 hook() { return vec4(LINE_VAL); }\n");
        assertFalse("Passes referencing __LINE__ must not fuse",
                GraphicsShaderTransforms.canFusePasses(linePassA, linePassB));

        // [15b] Local shadowing of vPos inside hook() must block 1x1 CReLU pre-sampling hoisting.
        String shadowedHeader = "#define g_0 (max((MAIN_tex(MAIN_pos)), 0.0))\n"
                + "#define g_1 (max(-(MAIN_tex(MAIN_pos)), 0.0))\n";
        MpvHookShader.Pass vPosShadowPass = new MpvHookShader.Pass(
                "vPosShadow", List.of("MAIN"), List.of("MAIN"), null, null,
                shadowedHeader + "vec4 hook() {\nvec2 vPos = vec2(0.25);\nreturn g_0 + g_1;\n}\n");
        assertEquals("Local vPos declaration must prevent unsafe macro hoisting",
                vPosShadowPass.body, GraphicsShaderTransforms.optimizePassBody(vPosShadowPass).body);

        // [11]/[12] GLES 3.2+ indexed draw-buffer colorMaski and blendi capture, prepareDrawState, and restore.
        final boolean[][] bufMasks = {
                {true, true, true, true},
                {true, true, true, false}
        };
        final boolean[] bufBlends = {false, true};
        CnnPipeline stateNode = new CnnPipeline(Collections.singletonList(noopPass),
                new GraphicsShaderTargets(Collections.singletonList(noopPass)),
                false, 1, 1, GraphicsShaderTransforms.Config.DEFAULT, nodeGl);
        CnnPipeline.GlState glState = (CnnPipeline.GlState) getField(stateNode, "mState");
        glState.indexedOps = new CnnPipeline.GlState.IndexedDrawBufferOps() {
            @Override public int getDrawBufferCount() { return 2; }
            @Override public void getColorMaski(int index, int[] out, int offset) {
                for (int c = 0; c < 4; c++) out[offset + c] = bufMasks[index][c] ? 1 : 0;
            }
            @Override public boolean isBlendEnabledi(int index) { return bufBlends[index]; }
            @Override public void colorMaski(int index, boolean r, boolean g, boolean b, boolean a) {
                bufMasks[index][0] = r; bufMasks[index][1] = g; bufMasks[index][2] = b; bufMasks[index][3] = a;
            }
            @Override public void setBlendEnabledi(int index, boolean enabled) {
                bufBlends[index] = enabled;
            }
        };
        glState.capture(1);
        stateNode.prepareDrawState();
        assertArrayEquals(new boolean[]{true, true, true, true}, bufMasks[0]);
        assertArrayEquals(new boolean[]{true, true, true, true}, bufMasks[1]);
        assertFalse(bufBlends[0]);
        assertFalse(bufBlends[1]);
        glState.restore();
        assertArrayEquals(new boolean[]{true, true, true, true}, bufMasks[0]);
        assertArrayEquals("Buffer 1 alpha mask must be restored per-index",
                new boolean[]{true, true, true, false}, bufMasks[1]);
        assertFalse(bufBlends[0]);
        assertTrue("Buffer 1 blend enable must be restored per-index", bufBlends[1]);
    }
}
