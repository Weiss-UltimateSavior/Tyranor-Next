package com.core.gl;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavioral CPU check of the emitted coordinate wrapper. The fragment source produced by
 * {@link MpvHookShader#fragmentSource} is parsed and evaluated numerically, so any edit to the
 * emitted hookCoord algebra must keep the declared-row-order semantics for every combination of
 * descriptor declaration and physical input row order, or this test fails.
 */
public final class FilterCoordinateWrapperTest {

    @Test public void flipDecisionCoversEveryDeclaredAndPhysicalCombination() {
        assertEquals(0, Anime4kPostProcessor.flipYUniform(true, true));
        assertEquals(1, Anime4kPostProcessor.flipYUniform(true, false));
        assertEquals(1, Anime4kPostProcessor.flipYUniform(false, true));
        assertEquals(0, Anime4kPostProcessor.flipYUniform(false, false));
    }

    @Test public void emittedWrapperSemanticsMatchDeclaredRowOrderAcrossCombinations() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC wrapper\n//!BIND MAIN\n//!SAVE MAIN\n"
                        + "vec4 hook() { return MAIN_tex(MAIN_pos) + MAIN_texOff(MAIN_pt); }").get(0);
        Wrapper wrapper = Wrapper.parse(MpvHookShader.fragmentSource(pass), "MAIN");
        int w = 4, h = 8;
        double[] size = {w, h};

        // Construct an asymmetric 2D image in logical image space (visual row 0 = top, row 7 = bottom).
        double[][][] image = new double[h][w][4];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                image[r][c] = new double[]{
                        (r * 10.0 + c + 1.0) / 100.0,
                        (r * 10.0 + c + 2.0) / 100.0,
                        (r * 10.0 + c + 3.0) / 100.0,
                        1.0
                };
            }
        }

        // Explicit 4-case truth table of concrete expected values + physical texture lookup:
        // Case 0: declared=true,  input=true  -> flip=0, vPosY=0.5/h, expectedMainPosY=0.5/h, sampleRow=0, stepRow=1 (downward)
        // Case 1: declared=true,  input=false -> flip=1, vPosY=(h-0.5)/h, expectedMainPosY=0.5/h, sampleRow=0, stepRow=1 (downward)
        // Case 2: declared=false, input=false -> flip=0, vPosY=0.5/h, expectedMainPosY=0.5/h, sampleRow=h-1, stepRow=h-2 (upward)
        // Case 3: declared=false, input=true  -> flip=1, vPosY=(h-0.5)/h, expectedMainPosY=0.5/h, sampleRow=h-1, stepRow=h-2 (upward)
        Object[][] cases = new Object[][]{
                {true,  true,  0, 0.5 / h,         0.5 / h, 0,     1,     "declared=TL input=TL (KR2 host top-left)"},
                {true,  false, 1, (h - 0.5) / h,   0.5 / h, 0,     1,     "declared=TL input=BL (standard GLES FBO)"},
                {false, false, 0, 0.5 / h,         0.5 / h, h - 1, h - 2, "declared=BL input=BL (standard GLES FBO)"},
                {false, true,  1, (h - 0.5) / h,   0.5 / h, h - 1, h - 2, "declared=BL input=TL (KR2 host top-left)"}
        };

        for (Object[] tc : cases) {
            boolean declared = (boolean) tc[0];
            boolean inputTopLeft = (boolean) tc[1];
            int flip = (int) tc[2];
            double vPosY = (double) tc[3];
            double expectedMainPosY = (double) tc[4];
            int expectedSampleRow = (int) tc[5];
            int expectedStepRow = (int) tc[6];
            String desc = (String) tc[7];

            assertEquals("flipYUniform mismatch for " + desc, flip, Anime4kPostProcessor.flipYUniform(declared, inputTopLeft));

            // Populate physical OpenGL texture array according to inputTopLeft
            Texture2D tex = new Texture2D(w, h, 4);
            for (int p = 0; p < h; p++) {
                int logicalRow = inputTopLeft ? p : (h - 1 - p);
                for (int c = 0; c < w; c++) {
                    System.arraycopy(image[logicalRow][c], 0, tex.data[p][c], 0, 4);
                }
            }
            wrapper.setTexture("u_MAIN", tex);

            for (int col = 0; col < w; col++) {
                double vPosX = (col + 0.5) / w;
                double[] vPos = {vPosX, vPosY};

                // 1. Direct vPos evaluation against explicit expected logical coordinate
                double[] mainPos = wrapper.evalPosDefine("MAIN", vPos, flip);
                assertEquals(vPosX, mainPos[0], 1e-9);
                assertEquals("MAIN_pos.y mismatch for " + desc, expectedMainPosY, mainPos[1], 1e-9);

                // 2. Physical texture lookup through MAIN_tex(MAIN_pos)
                double[] sampled = wrapper.sampleTex("MAIN", mainPos, flip);
                assertVec(sampled, image[expectedSampleRow][col]);

                // 3. Physical texture lookup through MAIN_texOff(vec2(0, +1))
                double[] stepped = wrapper.sampleTexOff("MAIN", vPos, new double[]{0.0, 1.0}, size, flip);
                assertVec(stepped, image[expectedStepRow][col]);

                // 4. Discriminator: inverting flip alters the output by more than one texel / shifts row
                double[] wrongStepped = wrapper.sampleTexOff("MAIN", vPos, new double[]{0.0, 1.0}, size, 1 - flip);
                assertTrue("wrong u_FLIP_Y must be distinguishable for " + desc,
                        Math.abs(wrongStepped[0] - image[expectedStepRow][col][0]) > 0.05);
            }
        }
    }

    /**
     * Known-displacement oracle stated in image space rather than as a mirror of the emitted
     * algebra: texOff(0, +1) moves one row toward the image bottom exactly when the shader is
     * written in top-left coordinates, and the physical row holding the image top is row 0
     * exactly when the input texture is stored top-left. Both statements are independent of the
     * implementation, so a consistently wrong wrapper cannot satisfy them.
     */
    @Test public void texOffDisplacementStaysInImageSpaceAcrossCombinations() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC wrapper\n//!BIND MAIN\n//!SAVE MAIN\n"
                        + "vec4 hook() { return MAIN_tex(MAIN_pos) + MAIN_texOff(MAIN_pt); }").get(0);
        Wrapper wrapper = Wrapper.parse(MpvHookShader.fragmentSource(pass), "MAIN");
        double[] size = {16.0, 9.0};
        for (boolean declared : new boolean[]{true, false}) {
            for (boolean inputTopLeft : new boolean[]{true, false}) {
                int flip = Anime4kPostProcessor.flipYUniform(declared, inputTopLeft);
                int imageDownRows = declared ? 1 : -1;
                int imageDownPhysical = inputTopLeft ? 1 : -1;
                for (int row = 1; row < 8; row++) {
                    double[] vPos = {0.3, (row + 0.5) / size[1]};
                    for (int offY : new int[]{-1, 1}) {
                        double[] emitted = wrapper.evalTexOff(vPos, new double[]{0, offY}, size, flip);
                        double expectedRows = offY * imageDownRows * imageDownPhysical;
                        assertEquals(expectedRows / size[1], emitted[1] - vPos[1], 1e-9);
                    }
                    double[] emittedX = wrapper.evalTexOff(vPos, new double[]{-1, 0}, size, flip);
                    assertEquals(-1.0 / size[0], emittedX[0] - vPos[0], 1e-9);
                }
            }
        }
    }

    /** Evaluates the real hook() body so composite sampling paths cannot drift from the wrapper. */
    @Test public void emittedHookBodyCompositionMatchesDeclaredSemantics() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC wrapper\n//!BIND MAIN\n//!SAVE MAIN\n"
                        + "vec4 hook() { return MAIN_tex(MAIN_pos) + MAIN_texOff(vec2(1.0)); }").get(0);
        Wrapper wrapper = Wrapper.parse(MpvHookShader.fragmentSource(pass), "MAIN");
        double[] size = {16.0, 9.0};
        for (boolean declared : new boolean[]{true, false}) {
            for (boolean inputTopLeft : new boolean[]{true, false}) {
                int flip = Anime4kPostProcessor.flipYUniform(declared, inputTopLeft);
                for (double[] vPos : new double[][]{{0.25, 0.25}, {0.5, 0.5}, {0.9375, 0.0625}}) {
                    double[] emitted = wrapper.evalHookBody(Wrapper.baseVars(vPos, size, flip));
                    // MAIN_pos is hookCoord(vPos); MAIN_tex applies hookCoord(MAIN_pos) = hookCoord(hookCoord(vPos)) = vPos.
                    // MAIN_texOff(vec2(1.0)) evaluates hookCoord(hookCoord(vPos) + vec2(1.0)/size).
                    // When flip == 0: vPos + (vPos + vec2(1.0)/size)
                    // When flip == 1: vPos + [vPos.x + 1/size.x, vPos.y - 1/size.y]
                    double[] expected = (flip == 0)
                            ? new double[]{vPos[0] + (vPos[0] + 1.0 / size[0]), vPos[1] + (vPos[1] + 1.0 / size[1])}
                            : new double[]{vPos[0] + (vPos[0] + 1.0 / size[0]), vPos[1] + (vPos[1] - 1.0 / size[1])};
                    assertVec(emitted, expected);
                }
            }
        }
    }

    @Test public void expandDefinesDoesNotDoubleExpandChainedMacros() {
        String source = "#define A B_plus_1\n#define B_plus_1 999.0\n";
        String body = "A + B_plus_1";
        String expanded = Wrapper.expandDefines(source, body);
        assertEquals("B_plus_1 + 999.0", expanded);
    }

    @Test public void stripCommentsPreventsFalsePositivesFromCommentedWrapperSurface() {
        String commentedOut = "// vec4 a = MAIN_tex(MAIN_pos);\n"
                + "/* vec4 b = AUX_texOff(vec2(1.0));\n"
                + "   vec2 p = MAIN_pos; */\n"
                + "vec4 hook() { return vec4(1.0); }\n";
        String stripped = stripComments(commentedOut);
        assertFalse(stripped.contains("MAIN_tex"));
        assertFalse(stripped.contains("AUX_texOff"));
        assertFalse(stripped.contains("MAIN_pos"));
        assertFalse("Commented-out wrapper syntax must not match wrapper surface",
                WRAPPER_SURFACE.matcher(stripped).find());
    }

    /** Any asset that samples through the wrapper surface must declare its row order explicitly. */
    @Test public void coordinateWrapperUsersDeclareTopLeft() throws Exception {
        Path assets = resolveAssetsDir();
        assertTrue("assets directory must exist: " + assets, assets != null && Files.isDirectory(assets));

        Map<String, List<GraphicsFilterDefinition>> assetToDefs = new HashMap<>();
        for (GraphicsFilterDefinition definition : GraphicsFilterDefinitions.all()) {
            if (definition.shaderAsset != null) {
                String normalized = definition.shaderAsset.replace('\\', '/');
                assetToDefs.computeIfAbsent(normalized, k -> new ArrayList<>()).add(definition);
            }
        }

        int checkedGlslFiles = 0;
        int wrapperUsingGlslFiles = 0;
        int legacyGlslFiles = 0;
        try (var stream = Files.walk(assets)) {
            // Case-insensitive on purpose: a case-variant suffix would otherwise skip both pins
            // entirely; this way it lands in the shape gate below and forces a conscious sync.
            for (Path path : (Iterable<Path>) stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".glsl"))::iterator) {
                String relPath = assets.relativize(path).toString().replace('\\', '/');
                if (!GraphicsFilterDefinitions.isValidAssetPath(relPath)) {
                    // Such a path can never be referenced by a filter descriptor (the descriptor
                    // gate uses the same pattern), so it stays inert - but it must not slip past
                    // this guard silently. Confine it to the pinned Krkr legacy family and force
                    // a conscious sync if that set changes.
                    assertTrue("Shape-noncompliant .glsl path outside the legacy shaders/ family: " + relPath,
                            relPath.startsWith("shaders/"));
                    legacyGlslFiles++;
                    continue;
                }
                checkedGlslFiles++;
                String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                String clean = stripComments(source);
                if (!WRAPPER_SURFACE.matcher(clean).find()) {
                    continue;
                }
                wrapperUsingGlslFiles++;
                List<GraphicsFilterDefinition> defs = assetToDefs.get(relPath);
                assertTrue("Orphan asset using coordinate wrapper with no filter definition: " + relPath,
                        defs != null && !defs.isEmpty());
                for (GraphicsFilterDefinition def : defs) {
                    assertTrue(def.id + " (" + relPath + ") samples through coordinate wrapper but does not declare topLeftCoordinates",
                            def.topLeftCoordinates);
                }
            }
        }
        assertTrue("Expected to inspect .glsl files under assets", checkedGlslFiles > 0);
        assertEquals("Expected exactly 15 wrapper-using shaders (12 Anime4k + 3 VN-SR)", 15, wrapperUsingGlslFiles);
        assertEquals("Legacy (non-descriptor) .glsl count drifted; such paths bypass the orphan and "
                + "declaration asserts above, so any change must be re-verified consciously",
                53, legacyGlslFiles);
    }

    @Test public void finalPassAlphaClampedToScreenWhileIntermediatePreservesAlpha() {
        // Final to screen pass: fragColor.a forced to 1.0
        MpvHookShader.Pass finalPass = MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\n//!SAVE MAIN\nvec4 hook() { return vec4(0.2, 0.4, 0.6, 0.25); }").get(0);
        assertTrue("MAIN pass must be final to screen", finalPass.isFinalToScreen());
        String finalSource = MpvHookShader.fragmentSource(finalPass);
        assertTrue("emitted main must contain fragColor.a = 1.0", finalSource.contains("fragColor.a = 1.0;"));
        Wrapper finalWrapper = Wrapper.parse(finalSource, "MAIN");
        Map<String, double[]> vars = Wrapper.baseVars(new double[]{0.5, 0.5}, new double[]{16.0, 9.0}, 0);
        double[] finalMain = finalWrapper.evalMain(vars);
        assertEquals(1.0, finalMain[3], 1e-9);
        int[] finalRgba8 = finalWrapper.evalMainRgba8(vars);
        assertEquals(255, finalRgba8[3]);

        // Also verify with alpha > 1.0
        MpvHookShader.Pass finalOverAlpha = MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\n//!SAVE MAIN\nvec4 hook() { return vec4(0.2, 0.4, 0.6, 1.5); }").get(0);
        Wrapper finalOverWrapper = Wrapper.parse(MpvHookShader.fragmentSource(finalOverAlpha), "MAIN");
        assertEquals(255, finalOverWrapper.evalMainRgba8(vars)[3]);

        // Intermediate feature pass: fragColor.a preserves hook().a
        List<MpvHookShader.Pass> chain = MpvHookShader.parse(
                "//!DESC feat\n//!BIND MAIN\n//!SAVE feat\nvec4 hook() { return vec4(0.2, 0.4, 0.6, 0.25); }\n"
                        + "//!DESC final\n//!BIND MAIN\n//!BIND feat\n//!SAVE MAIN\nvec4 hook() { return feat_tex(feat_pos); }\n");
        MpvHookShader.Pass featPass = chain.get(0);
        assertFalse("feat pass must not be final to screen", featPass.isFinalToScreen());
        String featSource = MpvHookShader.fragmentSource(featPass);
        assertFalse("intermediate pass must not force fragColor.a", featSource.contains("fragColor.a = 1.0;"));
        Wrapper featWrapper = Wrapper.parse(featSource, "MAIN");
        double[] featMain = featWrapper.evalMain(vars);
        assertEquals(0.25, featMain[3], 1e-9);
        int[] featRgba8 = featWrapper.evalMainRgba8(vars);
        assertEquals((int) Math.round(0.25 * 255.0), featRgba8[3]);
    }

    /**
     * Multi-bind evaluation anchored to an absolute oracle stated in logical image space, plus
     * explicit discrimination guards. hookCoord is an involution, so {@code BIND_tex(BIND_pos)}
     * cancels any flip; the AUX term therefore samples through a non-zero, size-dependent
     * texOff ({@code AUX_pt * vec2(1.0, 3.0)}) whose physical direction depends on u_FLIP_Y and
     * whose magnitude depends on u_AUX_size. A wrongly wired flip or a per-bind size uniform
     * bound to the wrong texture moves the output, and the guards at the end fail if that
     * observability ever disappears. Both u_OUTPUT_SIZED branches run across every combination.
     */
    @Test public void multiBindPassEvaluatesEquivalentlyAcrossCoordinateCombinations() {
        String passCode = "//!DESC feat\n//!BIND MAIN\n//!SAVE AUX\nvec4 hook() { return MAIN_tex(MAIN_pos); }\n"
                + "//!DESC multi\n//!BIND MAIN\n//!BIND AUX\n//!SAVE MAIN\n"
                + "vec4 hook() {\n"
                + "    return MAIN_tex(MAIN_pos) + AUX_tex(AUX_pos) * (u_OUTPUT_SIZED ? (u_SHARPNESS * 0.1) : 0.05)"
                + "           + AUX_texOff(AUX_pt * vec2(1.0, 3.0)) * 0.25"
                + "           + vec4((OUTPUT_size / MAIN_size) * 0.001, 0.0, 0.0);\n"
                + "}\n";
        List<MpvHookShader.Pass> passes = MpvHookShader.parse(passCode);
        MpvHookShader.Pass pass = passes.get(1);
        Wrapper wrapper = Wrapper.parse(MpvHookShader.fragmentSource(pass), "MAIN", "AUX");

        int mainW = 4, mainH = 4;
        int auxW = 2, auxH = 2;
        int outW = 8, outH = 8;
        double[] mainSize = {mainW, mainH};
        double[] auxSize = {auxW, auxH};
        double[] outputSize = {outW, outH};

        // Logical images: row 0 is the image top. The oracle samples these directly; the emitted
        // path must land on the same logical texels through whatever physical orientation and
        // u_FLIP_Y each combination produces.
        double[][][] imageMain = new double[mainH][mainW][4];
        for (int r = 0; r < mainH; r++) {
            for (int c = 0; c < mainW; c++) {
                imageMain[r][c] = new double[]{(r * 7.0 + c + 1) / 50.0, (r * 3.0 + c + 2) / 50.0, (r * 5.0 + c + 3) / 50.0, 1.0};
            }
        }
        double[][][] imageAux = new double[auxH][auxW][4];
        for (int r = 0; r < auxH; r++) {
            for (int c = 0; c < auxW; c++) {
                imageAux[r][c] = new double[]{(r * 11.0 + c + 1) / 30.0, (r * 13.0 + c + 2) / 30.0, (r * 17.0 + c + 3) / 30.0, 0.8};
            }
        }
        Texture2D logicalMain = logicalTexture(imageMain, mainW, mainH);
        Texture2D logicalAux = logicalTexture(imageAux, auxW, auxH);

        // AUX_texOff(AUX_pt * vec2(1.0, 3.0)) resolves to hookCoord(vPos) + off / u_AUX_size with
        // off = (1.0 / u_AUX_size) * (1, 3): half a texel across, 1.5 texels down in AUX space.
        double offX = 1.0 / auxW / auxW;
        double offY = 3.0 / auxH / auxH;

        for (int sized : new int[]{1, 0}) {
            double featureGain = sized == 1 ? 1.5 * 0.1 : 0.05;
            for (boolean declared : new boolean[]{true, false}) {
                for (boolean inputTopLeft : new boolean[]{true, false}) {
                    int flip = Anime4kPostProcessor.flipYUniform(declared, inputTopLeft);

                    // Populate physical OpenGL textures according to inputTopLeft.
                    wrapper.setTexture("u_MAIN", physicalTexture(imageMain, mainW, mainH, inputTopLeft));
                    wrapper.setTexture("u_AUX", physicalTexture(imageAux, auxW, auxH, inputTopLeft));

                    for (int visR = 0; visR < outH; visR++) {
                        int physR = inputTopLeft ? visR : (outH - 1 - visR);
                        double vPosY = (physR + 0.5) / outH;
                        double logY = (visR + 0.5) / outH;
                        for (int c = 0; c < outW; c++) {
                            double vPosX = (c + 0.5) / outW;
                            // Oracle in logical image space: the fragment sits at logical output
                            // position (c, visR); texOff(+y) walks toward the image bottom exactly
                            // when the pass is written in top-left coordinates.
                            double[] expected = multiBindExpected(logicalMain, logicalAux, vPosX, logY,
                                    declared, featureGain, offX, offY, mainSize, outputSize);
                            Map<String, double[]> vars = multiBindVars(new double[]{vPosX, vPosY}, flip,
                                    mainSize, auxSize, outputSize, sized);
                            assertVec(wrapper.evalMain(vars), expected);
                            // Exact bytes are the canary: the two arithmetic paths differ by up
                            // to ~1 ulp from bilinear term reordering on mirrored textures, which
                            // byte quantization absorbs on this platform (pure +,-,*,/ under
                            // JEP 306), and pixel (7,7) blue sits exactly on the 178.5 rounding
                            // tie, bit-identical on both paths. A byte mismatch signals an
                            // evaluator refactor, not a coordinate bug.
                            assertArrayEquals(quantize8(expected), wrapper.evalMainRgba8(vars));
                        }
                    }
                }
            }
        }

        // Discrimination guards on the declared=TL / input=TL / sized reference: each deliberately
        // wrong wiring must move some output channel, or this test has gone blind.
        wrapper.setTexture("u_MAIN", physicalTexture(imageMain, mainW, mainH, true));
        wrapper.setTexture("u_AUX", physicalTexture(imageAux, auxW, auxH, true));
        int flip = Anime4kPostProcessor.flipYUniform(true, true);
        boolean flipMoved = false;
        boolean sizeMoved = false;
        boolean modeMoved = false;
        for (int visR = 0; visR < outH; visR++) {
            for (int c = 0; c < outW; c++) {
                double vPosX = (c + 0.5) / outW;
                double vPosY = (visR + 0.5) / outH;
                int[] correct = wrapper.evalMainRgba8(multiBindVars(new double[]{vPosX, vPosY}, flip,
                        mainSize, auxSize, outputSize, 1));
                flipMoved |= !Arrays.equals(correct, wrapper.evalMainRgba8(multiBindVars(new double[]{vPosX, vPosY}, 1 - flip,
                        mainSize, auxSize, outputSize, 1)));
                sizeMoved |= !Arrays.equals(correct, wrapper.evalMainRgba8(multiBindVars(new double[]{vPosX, vPosY}, flip,
                        mainSize, mainSize, outputSize, 1)));
                modeMoved |= !Arrays.equals(correct, wrapper.evalMainRgba8(multiBindVars(new double[]{vPosX, vPosY}, flip,
                        mainSize, auxSize, outputSize, 0)));
            }
        }
        assertTrue("wrong u_FLIP_Y binding stopped being observable in the multi-bind output", flipMoved);
        assertTrue("u_AUX_size wired to u_MAIN_size stopped being observable in the multi-bind output", sizeMoved);
        assertTrue("u_OUTPUT_SIZED ternary branches stopped being observable in the multi-bind output", modeMoved);
    }

    /** Logical-space oracle of the multi-bind hook body; alpha is the final-to-screen clamp. */
    private static double[] multiBindExpected(Texture2D logicalMain, Texture2D logicalAux, double logX, double logY,
            boolean declared, double featureGain, double offX, double offY, double[] mainSize, double[] outputSize) {
        double[] mainSample = logicalMain.sampleBilinear(logX, logY);
        double[] auxSample = logicalAux.sampleBilinear(logX, logY);
        double[] auxOffSample = logicalAux.sampleBilinear(logX + offX, logY + offY * (declared ? 1 : -1));
        return new double[]{
                mainSample[0] + auxSample[0] * featureGain + auxOffSample[0] * 0.25
                        + outputSize[0] / mainSize[0] * 0.001,
                mainSample[1] + auxSample[1] * featureGain + auxOffSample[1] * 0.25
                        + outputSize[1] / mainSize[1] * 0.001,
                mainSample[2] + auxSample[2] * featureGain + auxOffSample[2] * 0.25,
                1.0
        };
    }

    private static Map<String, double[]> multiBindVars(double[] vPos, int flip, double[] mainSize,
            double[] auxSize, double[] outputSize, int sized) {
        Map<String, double[]> vars = new HashMap<>();
        vars.put("vPos", vPos);
        vars.put("u_FLIP_Y", new double[]{flip});
        vars.put("u_MAIN_size", mainSize);
        vars.put("u_AUX_size", auxSize);
        vars.put("u_OUTPUT_size", outputSize);
        vars.put("u_OUTPUT_SIZED", new double[]{sized});
        vars.put("u_SHARPNESS", new double[]{1.5});
        return vars;
    }

    /** Logical texture whose row index is the image row (row 0 = top). */
    private static Texture2D logicalTexture(double[][][] image, int width, int height) {
        Texture2D tex = new Texture2D(width, height, 4);
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                System.arraycopy(image[r][c], 0, tex.data[r][c], 0, 4);
            }
        }
        return tex;
    }

    /** Physical texture storage: row p holds image row p when stored top-left, else the mirrored row. */
    private static Texture2D physicalTexture(double[][][] image, int width, int height, boolean inputTopLeft) {
        Texture2D tex = new Texture2D(width, height, 4);
        for (int p = 0; p < height; p++) {
            int logicalRow = inputTopLeft ? p : (height - 1 - p);
            for (int c = 0; c < width; c++) {
                System.arraycopy(image[logicalRow][c], 0, tex.data[p][c], 0, 4);
            }
        }
        return tex;
    }

    private static int[] quantize8(double[] color) {
        int[] rgba8 = new int[4];
        for (int i = 0; i < 4; i++) {
            double clamped = Math.max(0.0, Math.min(1.0, color[i]));
            rgba8[i] = (int) Math.round(clamped * 255.0);
        }
        return rgba8;
    }

    @Test public void rejectsWrapperStructuresItCannotEvaluate() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC wrapper\n//!BIND MAIN\n//!SAVE MAIN\nvec4 hook(){return MAIN_tex(MAIN_pos);}").get(0);
        String source = MpvHookShader.fragmentSource(pass).replace("u_FLIP_Y ?", "u_FLIP_Y @");
        try {
            Wrapper.parse(source, "MAIN");
            fail("wrapper syntax outside the evaluated grammar must fail closed, not pass silently");
        } catch (AssertionError expected) {
            // Distinguish the tokenizer's rejection from a swallowed fail() above.
            assertTrue("expected the tokenizer to reject the corrupt wrapper syntax, got: " + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("unexpected character"));
            // Structural edits to the wrapper need explicit re-verification of the algebra.
        }
    }

    /**
     * The evaluator must mirror GLSL ES 3.00 exactly: 5.4.2 vector construction (insufficient
     * components and extra arguments beyond the last used argument are errors; vector
     * shortening through a partially consumed final argument is legal) and 5.9 expressions
     * (mismatched componentwise operands are compile errors) - so a future wrapper expression
     * can never receive invented math (zero-filled constructor tails, tail-clamped operands)
     * and never silently loses legal truncation.
     */
    @Test public void evaluatorRejectsGlslIllegalVectorShapes() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC wrapper\n//!BIND MAIN\n//!SAVE MAIN\nvec4 hook(){return MAIN_tex(MAIN_pos);}").get(0);
        Wrapper wrapper = Wrapper.parse(MpvHookShader.fragmentSource(pass), "MAIN");
        Map<String, double[]> vars = Wrapper.baseVars(new double[]{0.5, 0.5}, new double[]{4.0, 4.0}, 0);
        // GLSL has no vec4(vec2) constructor: the missing tail must fail, not zero-fill.
        assertEvalFails(wrapper, vars, "vec4(vPos)");
        // GLSL is a compile error for componentwise operands of different lengths: no tail clamp.
        assertEvalFails(wrapper, vars, "vPos + vec3(1.0, 1.0, 1.0)");
        // Extra arguments beyond the last used constructor argument are an error.
        assertEvalFails(wrapper, vars, "vec2(1.0, 2.0, 3.0)");
        // GLSL requires a scalar ternary condition; a vector condition must not silently take x.
        assertEvalFails(wrapper, vars, "vPos ? 1.0 : 0.0");
        // GLSL 5.9 requires a scalar Boolean: a float condition is a device compile error,
        // and the evaluator models bools as 0.0/1.0 only.
        assertEvalFails(wrapper, vars, "1.5 ? 1.0 : 0.0");
        // Vector shortening through a partially consumed final argument is legal (5.4.2).
        assertArrayEquals(new double[]{1.0, 2.0}, wrapper.evalExpression("vec2(vec3(1.0, 2.0, 3.0))", vars), 0.0);
        // The legal single-scalar fill stays usable.
        assertArrayEquals(new double[]{1.5, 1.5}, wrapper.evalExpression("vPos + vec2(1.0)", vars), 0.0);
    }

    private static void assertEvalFails(Wrapper wrapper, Map<String, double[]> vars, String expression) {
        try {
            wrapper.evalExpression(expression, vars);
            fail("evaluator must reject GLSL-illegal expression: " + expression);
        } catch (AssertionError expected) {
            // Distinguish the evaluator's shape rejection from a swallowed fail() above: only
            // the former carries the "GLSL rejects" rule text, so a lenient regression here
            // fails instead of passing silently.
            assertTrue("evaluator rejection must name the GLSL shape rule, got: " + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("GLSL rejects"));
        }
    }

    private static final Pattern COMMENTS_PATTERN = Pattern.compile("//.*?$|/\\*.*?\\*/", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern WRAPPER_SURFACE = Pattern.compile("\\b\\w+_tex\\s*\\(|\\b\\w+_texOff\\s*\\(|\\b\\w+_pos\\b");

    static String stripComments(String source) {
        return COMMENTS_PATTERN.matcher(source).replaceAll("");
    }

    static Path resolveAssetsDir() {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            Path candidate = cur.resolve("engine/src/main/assets");
            if (Files.isDirectory(candidate)) return candidate;
            candidate = cur.resolve("src/main/assets");
            if (Files.isDirectory(candidate)) return candidate;
            cur = cur.getParent();
        }
        fail("Could not resolve assets directory from " + Path.of("").toAbsolutePath());
        return null;
    }

    private static void assertVec(double[] actual, double[] expected) {
        assertEquals(expected.length, actual.length, 0.0);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-9);
        }
    }

    static final class Texture2D {
        final int width, height, channels;
        final double[][][] data; // data[row][col][channel]

        Texture2D(int width, int height, int channels) {
            this.width = width;
            this.height = height;
            this.channels = channels;
            this.data = new double[height][width][channels];
        }

        double[] sampleBilinear(double u, double v) {
            double uTexel = u * width - 0.5;
            double vTexel = v * height - 0.5;
            int x0 = (int) Math.floor(uTexel);
            int y0 = (int) Math.floor(vTexel);
            int x1 = x0 + 1;
            int y1 = y0 + 1;
            double fx = uTexel - x0;
            double fy = vTexel - y0;
            int cx0 = Math.max(0, Math.min(width - 1, x0));
            int cx1 = Math.max(0, Math.min(width - 1, x1));
            int cy0 = Math.max(0, Math.min(height - 1, y0));
            int cy1 = Math.max(0, Math.min(height - 1, y1));
            double[] out = new double[channels];
            for (int ch = 0; ch < channels; ch++) {
                double c00 = data[cy0][cx0][ch];
                double c10 = data[cy0][cx1][ch];
                double c01 = data[cy1][cx0][ch];
                double c11 = data[cy1][cx1][ch];
                out[ch] = (1.0 - fx) * (1.0 - fy) * c00 + fx * (1.0 - fy) * c10
                        + (1.0 - fx) * fy * c01 + fx * fy * c11;
            }
            return out;
        }
    }

    static final class BindingInfo {
        final String name;
        final Node texArg;
        final Node texOffArg;
        final Node posDefine;

        BindingInfo(String name, Node texArg, Node texOffArg, Node posDefine) {
            this.name = name;
            this.texArg = texArg;
            this.texOffArg = texOffArg;
            this.posDefine = posDefine;
        }
    }

    /** Extracts the emitted hookCoord, _tex, _texOff, _pos and hook() definitions for numerical evaluation. */
    static final class Wrapper {
        final Map<String, BindingInfo> bindings;
        final String primaryBind;
        final String hookParam;
        final Node hookX, hookY;
        final Node hookBody;
        final boolean hasFinalAlphaClamp;
        final Map<String, Texture2D> textures = new HashMap<>();

        private Wrapper(Map<String, BindingInfo> bindings, String primaryBind,
                String hookParam, Node hookX, Node hookY, Node hookBody, boolean hasFinalAlphaClamp) {
            this.bindings = bindings;
            this.primaryBind = primaryBind;
            this.hookParam = hookParam;
            this.hookX = hookX;
            this.hookY = hookY;
            this.hookBody = hookBody;
            this.hasFinalAlphaClamp = hasFinalAlphaClamp;
        }

        void setTexture(String samplerName, Texture2D tex) {
            textures.put(samplerName, tex);
        }

        static Wrapper parse(String source) {
            return parse(source, "MAIN");
        }

        static Wrapper parse(String source, String... expectedBinds) {
            Matcher hook = Pattern.compile("vec2 hookCoord\\(vec2 (\\w+)\\) \\{ return vec2\\(([^,]+),([^;]+)\\); \\}")
                    .matcher(source);
            assertTrue("emitted hookCoord definition not found", hook.find());
            Node hookX = FilterCoordinateWrapperTest.parse(hook.group(2));
            Node hookY = FilterCoordinateWrapperTest.parse(hook.group(3));

            Map<String, String> defines = collectDefines(source);
            Map<String, BindingInfo> bindings = new HashMap<>();
            Matcher bindMatcher = Pattern.compile("uniform sampler2D u_(\\w+);").matcher(source);
            while (bindMatcher.find()) {
                String bind = bindMatcher.group(1);
                Matcher tex = Pattern.compile("vec4 " + bind + "_tex\\(vec2 pos\\) \\{ return texture\\(u_" + bind + ", ([^;]+)\\); \\}")
                        .matcher(source);
                assertTrue("emitted " + bind + "_tex definition not found", tex.find());
                Matcher texOff = Pattern.compile("vec4 " + bind + "_texOff\\(vec2 off\\) \\{ return " + bind + "_tex\\(([^;]+)\\); \\}")
                        .matcher(source);
                assertTrue("emitted " + bind + "_texOff definition not found", texOff.find());
                String posDefStr = defines.get(bind + "_pos");
                Node posDef = posDefStr != null ? FilterCoordinateWrapperTest.parse(posDefStr) : null;
                bindings.put(bind, new BindingInfo(bind, FilterCoordinateWrapperTest.parse(tex.group(1)),
                        FilterCoordinateWrapperTest.parse(texOff.group(1)), posDef));
            }
            if (expectedBinds != null && expectedBinds.length > 0) {
                for (String b : expectedBinds) {
                    assertTrue("expected binding u_" + b + " not found", bindings.containsKey(b));
                }
            }

            Matcher hookFn = Pattern.compile("vec4 hook\\(\\)\\s*\\{\\s*return ([^;]+);\\s*\\}").matcher(source);
            assertTrue("emitted hook body not found", hookFn.find());
            String rawBody = hookFn.group(1).trim();
            String expandedBody = expandDefines(defines, rawBody);
            Node hookBody = FilterCoordinateWrapperTest.parse(expandedBody);

            boolean hasFinalAlphaClamp = source.contains("fragColor.a = 1.0;");
            String prim = (expectedBinds != null && expectedBinds.length > 0) ? expectedBinds[0] : "MAIN";

            return new Wrapper(bindings, prim, hook.group(1), hookX, hookY, hookBody, hasFinalAlphaClamp);
        }

        static Map<String, String> collectDefines(String source) {
            Map<String, String> defines = new HashMap<>();
            Matcher define = Pattern.compile("#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([^\\r\\n]+)").matcher(source);
            while (define.find()) {
                defines.put(define.group(1), define.group(2).trim());
            }
            return defines;
        }

        static String expandDefines(String source, String body) {
            return expandDefines(collectDefines(source), body);
        }

        static String expandDefines(Map<String, String> defines, String body) {
            if (defines.isEmpty()) return body;
            Matcher matcher = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b").matcher(body);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String token = matcher.group();
                String replacement = defines.get(token);
                if (replacement != null) {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(token));
                }
            }
            matcher.appendTail(sb);
            return sb.toString();
        }

        static Map<String, double[]> baseVars(double[] vPos, double[] size, int flip) {
            Map<String, double[]> vars = new HashMap<>();
            vars.put("vPos", vPos);
            vars.put("u_FLIP_Y", new double[]{flip});
            vars.put("u_MAIN_size", size);
            vars.put("u_OUTPUT_size", size);
            return vars;
        }

        double[] evalHookBody(Map<String, double[]> vars) {
            return hookBody.eval(vars, this);
        }

        /** Evaluates an arbitrary wrapper-grammar expression against this wrapper (negative-test entry). */
        double[] evalExpression(String expression, Map<String, double[]> vars) {
            return FilterCoordinateWrapperTest.parse(expression).eval(vars, this);
        }

        double[] evalMain(Map<String, double[]> vars) {
            double[] color = evalHookBody(vars);
            if (color.length != 4) {
                throw new AssertionError("hook() must return vec4, got length " + color.length);
            }
            double[] fragColor = color.clone();
            if (hasFinalAlphaClamp) {
                fragColor[3] = 1.0;
            }
            return fragColor;
        }

        int[] evalMainRgba8(Map<String, double[]> vars) {
            double[] color = evalMain(vars);
            int[] rgba8 = new int[4];
            for (int i = 0; i < 4; i++) {
                double clamped = Math.max(0.0, Math.min(1.0, color[i]));
                rgba8[i] = (int) Math.round(clamped * 255.0);
            }
            return rgba8;
        }

        double[] evalTex(double[] pos, int flip) {
            return evalTex(primaryBind, pos, flip);
        }

        double[] evalTex(String bind, double[] pos, int flip) {
            BindingInfo b = bindings.get(bind);
            return b.texArg.eval(vars("pos", pos, flip), this);
        }

        double[] evalTexOff(double[] vPos, double[] off, double[] size, int flip) {
            return evalTexOff(primaryBind, vPos, off, size, flip);
        }

        double[] evalTexOff(String bind, double[] vPos, double[] off, double[] size, int flip) {
            BindingInfo b = bindings.get(bind);
            Map<String, double[]> vars = vars("vPos", vPos, flip);
            vars.put("off", off);
            vars.put("u_" + bind + "_size", size);
            vars.put("pos", b.texOffArg.eval(vars, this));
            return b.texArg.eval(vars, this);
        }

        double[] sampleTex(String bind, double[] pos, int flip) {
            BindingInfo b = bindings.get(bind);
            double[] coord = b.texArg.eval(vars("pos", pos, flip), this);
            Texture2D tex = textures.get("u_" + bind);
            if (tex == null) return coord;
            return tex.sampleBilinear(coord[0], coord[1]);
        }

        double[] sampleTexOff(String bind, double[] vPos, double[] off, double[] size, int flip) {
            BindingInfo b = bindings.get(bind);
            Map<String, double[]> vars = vars("vPos", vPos, flip);
            vars.put("off", off);
            vars.put("u_" + bind + "_size", size);
            vars.put("pos", b.texOffArg.eval(vars, this));
            double[] coord = b.texArg.eval(vars, this);
            Texture2D tex = textures.get("u_" + bind);
            if (tex == null) return coord;
            return tex.sampleBilinear(coord[0], coord[1]);
        }

        double[] evalPosDefine(double[] vPos, int flip) {
            return evalPosDefine(primaryBind, vPos, flip);
        }

        double[] evalPosDefine(String bind, double[] vPos, int flip) {
            BindingInfo b = bindings.get(bind);
            return b.posDefine.eval(vars("vPos", vPos, flip), this);
        }

        private static Map<String, double[]> vars(String name, double[] value, int flip) {
            Map<String, double[]> vars = new HashMap<>();
            vars.put(name, value);
            vars.put("u_FLIP_Y", new double[]{flip});
            return vars;
        }
    }

    private interface Node {
        double[] eval(Map<String, double[]> vars, Wrapper wrapper);
    }

    /** Parses the small GLSL expression grammar the emitted wrapper is allowed to use. */
    private static Node parse(String expression) {
        return new Parser(tokenize(expression)).parseTernary();
    }

    private static List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < expression.length() && (Character.isDigit(expression.charAt(j)) || expression.charAt(j) == '.')) j++;
                tokens.add(expression.substring(i, j));
                i = j;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < expression.length() && Character.isJavaIdentifierPart(expression.charAt(j))) j++;
                tokens.add(expression.substring(i, j));
                i = j;
            } else if ("+-*/(),?:.".indexOf(c) >= 0) {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                throw new AssertionError("unexpected character in wrapper expression: " + c);
            }
        }
        return tokens;
    }

    private static final class Parser {
        private final List<String> tokens;
        private int pos;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        Node parseTernary() {
            Node condition = parseAdditive();
            if (match("?")) {
                Node whenTrue = parseTernary();
                expect(":");
                Node whenFalse = parseTernary();
                return (vars, wrapper) -> {
                    double[] condVal = condition.eval(vars, wrapper);
                    // GLSL requires a scalar condition; silently taking component x would
                    // invent a boolean from a vector.
                    if (condVal.length != 1) {
                        throw new AssertionError("GLSL rejects non-scalar ternary condition (length "
                                + condVal.length + ")");
                    }
                    // GLSL 5.9 requires a scalar Boolean. The grammar has no bool type and
                    // models bools as 0.0/1.0, so a condition value outside that domain (a
                    // float such as 1.5) is a device compile error and must not pick a
                    // branch. A float expression landing on exactly 0.0/1.0 still passes:
                    // that residual type blindness is inherent to this grammar and recorded.
                    if (condVal[0] != 0.0 && condVal[0] != 1.0) {
                        throw new AssertionError("GLSL rejects non-boolean ternary condition value "
                                + condVal[0]);
                    }
                    return condVal[0] != 0.0 ? whenTrue.eval(vars, wrapper) : whenFalse.eval(vars, wrapper);
                };
            }
            return condition;
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (match("+") || match("-")) {
                String op = tokens.get(pos - 1);
                Node lhs = left;
                Node rhs = parseMultiplicative();
                left = (vars, wrapper) -> componentwise(op, lhs.eval(vars, wrapper), rhs.eval(vars, wrapper));
            }
            return left;
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (match("*") || match("/")) {
                String op = tokens.get(pos - 1);
                Node lhs = left;
                Node rhs = parseUnary();
                left = (vars, wrapper) -> componentwise(op, lhs.eval(vars, wrapper), rhs.eval(vars, wrapper));
            }
            return left;
        }

        private Node parseUnary() {
            if (match("-")) {
                Node inner = parseUnary();
                return (vars, wrapper) -> {
                    double[] v = inner.eval(vars, wrapper);
                    double[] out = new double[v.length];
                    for (int i = 0; i < v.length; i++) out[i] = -v[i];
                    return out;
                };
            }
            return parsePostfix();
        }

        private Node parsePostfix() {
            Node primary = parsePrimary();
            while (match(".")) {
                String field = expectIdent();
                Node base = primary;
                primary = (vars, wrapper) -> {
                    double[] v = base.eval(vars, wrapper);
                    if ("x".equals(field) || "r".equals(field)) return new double[]{v[0]};
                    if ("y".equals(field) || "g".equals(field)) return new double[]{v[1]};
                    if ("z".equals(field) || "b".equals(field)) return new double[]{v[2]};
                    if ("w".equals(field) || "a".equals(field)) return new double[]{v[3]};
                    if ("rgb".equals(field)) return new double[]{v[0], v[1], v[2]};
                    if ("rgba".equals(field)) return new double[]{v[0], v[1], v[2], v[3]};
                    throw new AssertionError("unsupported swizzle: " + field);
                };
            }
            return primary;
        }

        private Node parsePrimary() {
            String token = peek();
            if (token == null) throw new AssertionError("unexpected end of wrapper expression");
            if (Character.isDigit(token.charAt(0))) {
                pos++;
                double value = Double.parseDouble(token);
                return (vars, wrapper) -> new double[]{value};
            }
            if (Character.isJavaIdentifierStart(token.charAt(0))) {
                pos++;
                if ("(".equals(peek())) {
                    pos++;
                    List<Node> args = new ArrayList<>();
                    if (!")".equals(peek())) {
                        args.add(parseTernary());
                        while (match(",")) args.add(parseTernary());
                    }
                    expect(")");
                    String name = token;
                    return (vars, wrapper) -> {
                        if ("hookCoord".equals(name)) {
                            double[] arg = args.get(0).eval(vars, wrapper);
                            Map<String, double[]> scope = new HashMap<>(vars);
                            scope.put(wrapper.hookParam, arg);
                            return new double[]{wrapper.hookX.eval(scope, wrapper)[0],
                                    wrapper.hookY.eval(scope, wrapper)[0]};
                        }
                        if ("vec2".equals(name) || "vec3".equals(name) || "vec4".equals(name)) {
                            int targetLen = "vec2".equals(name) ? 2 : "vec3".equals(name) ? 3 : 4;
                            List<double[]> argVals = new ArrayList<>();
                            for (Node arg : args) {
                                argVals.add(arg.eval(vars, wrapper));
                            }
                            if (argVals.size() == 1 && argVals.get(0).length == 1) {
                                // GLSL: a single scalar argument initializes every component.
                                double[] out = new double[targetLen];
                                Arrays.fill(out, argVals.get(0)[0]);
                                return out;
                            }
                            // GLSL ES 3.00 5.4.2: arguments are consumed left to right; a
                            // partially consumed final argument shortens the vector legally
                            // (vec2(vec3)), but extra arguments beyond the last used one are an
                            // error, and so is running out of components before the target is
                            // filled.
                            int filled = 0;
                            int lastUsedArg = -1;
                            for (int a = 0; a < argVals.size() && filled < targetLen; a++) {
                                filled += argVals.get(a).length;
                                lastUsedArg = a;
                            }
                            if (filled < targetLen) {
                                throw new AssertionError("GLSL rejects " + name + " built from "
                                        + filled + " components (not enough arguments; expected "
                                        + targetLen + ")");
                            }
                            if (lastUsedArg < argVals.size() - 1) {
                                throw new AssertionError("GLSL rejects extra arguments beyond the "
                                        + "last used " + name + " constructor argument ("
                                        + argVals.size() + " arguments for " + targetLen
                                        + " components)");
                            }
                            double[] out = new double[targetLen];
                            int k = 0;
                            for (double[] arg : argVals) {
                                for (double d : arg) {
                                    if (k < targetLen) out[k++] = d;
                                }
                            }
                            return out;
                        }
                        if (name.endsWith("_tex") || name.endsWith("_texOff")) {
                            boolean isOff = name.endsWith("_texOff");
                            String bind = isOff ? name.substring(0, name.length() - 7) : name.substring(0, name.length() - 4);
                            BindingInfo b = wrapper.bindings.get(bind);
                            if (b == null) throw new AssertionError("unbound texture call: " + name);
                            if (args.size() != 1) throw new AssertionError(name + " expects one argument");
                            Map<String, double[]> scope = new HashMap<>(vars);
                            if (isOff) {
                                scope.put("off", args.get(0).eval(vars, wrapper));
                                scope.put("pos", b.texOffArg.eval(scope, wrapper));
                            } else {
                                scope.put("pos", args.get(0).eval(vars, wrapper));
                            }
                            double[] coord = b.texArg.eval(scope, wrapper);
                            Texture2D tex = wrapper.textures.get("u_" + bind);
                            if (tex != null) {
                                return tex.sampleBilinear(coord[0], coord[1]);
                            }
                            return coord;
                        }
                        throw new AssertionError("unexpected call in wrapper expression: " + name);
                    };
                }
                String name = token;
                return (vars, wrapper) -> {
                    double[] value = vars.get(name);
                    if (value == null) throw new AssertionError("unbound variable in wrapper expression: " + name);
                    return value;
                };
            }
            if ("(".equals(token)) {
                pos++;
                Node inner = parseTernary();
                expect(")");
                return inner;
            }
            throw new AssertionError("unexpected token in wrapper expression: " + token);
        }

        private boolean match(String token) {
            if (token.equals(peek())) {
                pos++;
                return true;
            }
            return false;
        }

        private String peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        private String expectIdent() {
            String token = peek();
            if (token == null || !Character.isJavaIdentifierStart(token.charAt(0))) {
                throw new AssertionError("expected identifier, got: " + token);
            }
            pos++;
            return token;
        }

        private void expect(String token) {
            if (!match(token)) throw new AssertionError("expected " + token + " at token " + pos);
        }

        private static double[] componentwise(String op, double[] left, double[] right) {
            if (left.length != right.length && left.length != 1 && right.length != 1) {
                // GLSL is a compile error for componentwise operands of different lengths:
                // fail closed instead of clamping to the tail element.
                throw new AssertionError("GLSL rejects " + op + " on lengths "
                        + left.length + " and " + right.length);
            }
            int len = Math.max(left.length, right.length);
            double[] out = new double[len];
            for (int i = 0; i < len; i++) {
                double a = left.length == 1 ? left[0] : left[i];
                double b = right.length == 1 ? right[0] : right[i];
                switch (op) {
                    case "+": out[i] = a + b; break;
                    case "-": out[i] = a - b; break;
                    case "*": out[i] = a * b; break;
                    case "/": out[i] = a / b; break;
                    default: throw new AssertionError("unsupported operator: " + op);
                }
            }
            return out;
        }
    }
}
