package com.core.gl;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class MpvHookShaderTest {
    @Test public void parsesEveryBundledShaderAndItsTerminalDisplayPass() throws Exception {
        Path assets = Path.of("src/main/assets");
        if (!Files.isDirectory(assets)) assets = Path.of("engine/src/main/assets");
        for (GraphicsFilterDefinition definition : GraphicsFilterDefinitions.all()) {
            if (definition.shaderAsset == null) continue;
            assertTrue(definition.id, GraphicsFilterDefinitions.isValidAssetPath(definition.shaderAsset));
            List<MpvHookShader.Pass> passes = MpvHookShader.parse(new String(Files.readAllBytes(assets.resolve(definition.shaderAsset)), StandardCharsets.UTF_8));
            assertTrue(definition.id, passes.get(passes.size() - 1).isFinalToScreen());
            for (MpvHookShader.Pass pass : passes) {
                assertTrue(definition.id, pass.binds.size() <= 8);
                assertFalse(definition.id, pass.binds.contains("HOOKED"));
                assertFalse(definition.id, pass.body.contains("HOOKED_tex"));
                assertTrue(MpvHookShader.fragmentSource(pass).contains("precision highp int;"));
            }
        }
    }

    @Test public void coordinateWrapperConvertsBothSamplingAndPixelPhase() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\n//!SAVE MAIN\nvec4 hook(){return MAIN_tex(MAIN_pos);}").get(0);
        String source = MpvHookShader.fragmentSource(pass);
        assertTrue(source.contains("u_FLIP_Y ? 1.0-p.y : p.y"));
        assertTrue(source.contains("texture(u_MAIN, hookCoord(pos))"));
        assertTrue(source.contains("#define MAIN_pos hookCoord(vPos)"));
        assertTrue(source.contains("MAIN_tex(hookCoord(vPos) + off / u_MAIN_size)"));
    }

    @Test public void deblurReadsPublishedKernelBeforeWritingItsSameNamedReplacement() throws Exception {
        Path asset = Path.of("src/main/assets/anime4k/Anime4K_Deblur_DoG.glsl");
        if (!Files.exists(asset)) asset = Path.of("engine").resolve(asset);
        List<MpvHookShader.Pass> passes = MpvHookShader.parse(new String(Files.readAllBytes(asset), StandardCharsets.UTF_8));
        assertEquals("MMKERNEL", passes.get(1).save);
        assertFalse(passes.get(1).binds.contains("MMKERNEL"));
        assertEquals("MMKERNEL", passes.get(2).save);
        assertTrue(passes.get(2).binds.contains("MMKERNEL"));
        assertTrue(passes.get(3).binds.contains("MMKERNEL"));
    }

    @Test public void normalizesAliasInMetadataAndAllGeneratedTextureSymbols() {
        MpvHookShader.Pass pass = MpvHookShader.parse(
                "//!DESC final\n//!HOOK MAIN\n//!BIND HOOKED\n//!BIND MAIN\n"
                + "//!SAVE HOOKED\n//!WIDTH HOOKED.w\n//!HEIGHT HOOKED.h\n"
                + "vec4 hook() { return HOOKED_tex(HOOKED_pos) + HOOKED_texOff(HOOKED_pt); }\n").get(0);
        assertEquals(List.of("MAIN"), pass.binds);
        assertEquals("MAIN", pass.save);
        assertEquals("MAIN", pass.widthRef);
        assertEquals("MAIN", pass.heightRef);
        assertTrue(pass.body.contains("MAIN_tex(MAIN_pos) + MAIN_texOff(MAIN_pt)"));
    }

    @Test public void rejectsReadBeforeWriteInsteadOfSamplingUninitializedStorage() {
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC kernel\n//!BIND MMKERNEL\n//!SAVE MMKERNEL\nvec4 hook(){return vec4(0.0);}\n"
                + "//!DESC final\n//!BIND MAIN\nvec4 hook(){return vec4(0.0);}\n"));
    }

    @Test public void rejectsUnsupportedUpscaleExpressionsAndConditionalHooks() {
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\n//!WIDTH MAIN.w 2 *\nvec4 hook(){return vec4(0.0);}\n"));
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\n//!WHEN OUTPUT.w MAIN.w / 1.2 >\nvec4 hook(){return vec4(0.0);}\n"));
    }

    @Test public void rejectsNonTerminalOrMissingMainOutput() {
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\nvec4 hook(){return vec4(0.0);}\n"
                + "//!DESC late\n//!BIND MAIN\n//!SAVE LATE\nvec4 hook(){return vec4(0.0);}\n"));
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC incomplete\n//!BIND MAIN\n//!SAVE INTERMEDIATE\nvec4 hook(){return vec4(0.0);}\n"));
    }

    @Test public void rejectsNonTerminalPassWritingMainInsteadOfSilentlyRebindingRawInput() {
        // GraphicsShaderTransforms resolves a MAIN bind to the raw input texture, so a
        // non-terminal MAIN write would make later MAIN binds silently read the original
        // input instead of the pass output. Every MAIN-write shape fails closed at parse
        // time: a single intermediate SAVE MAIN hits the terminal-output rule, while
        // multi-SAVE forms (intermediate or terminal) hit the explicit MAIN-write guard.
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC kernel\n//!BIND MAIN\n//!SAVE MAIN\nvec4 hook(){return MAIN_tex(MAIN_pos);}\n"
                + "//!DESC final\n//!BIND MAIN\nvec4 hook(){return vec4(0.0);}\n"));
        IllegalArgumentException nonTerm = assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC kernel\n//!BIND MAIN\n//!SAVE MAIN AUX\nvoid hook(){fragColor0=vec4(0.0);fragColor1=vec4(0.0);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND AUX\nvec4 hook(){return vec4(0.0);}\n"));
        assertTrue(nonTerm.getMessage().contains("SAVE MAIN"));
        IllegalArgumentException multiTerm = assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC kernel\n//!BIND MAIN\n//!SAVE AUX\nvec4 hook(){return vec4(0.0);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND AUX\n//!SAVE MAIN AUX\nvoid hook(){fragColor0=vec4(0.0);fragColor1=vec4(0.0);}\n"));
        assertTrue(multiTerm.getMessage().contains("SAVE MAIN"));
        // A multi-SAVE pass without MAIN keeps working (MRT pass, parsed and validated).
        MpvHookShader.parse(
                "//!DESC kernel\n//!BIND MAIN\n//!SAVE AUX0 AUX1\nvoid hook(){fragColor0=vec4(0.0);fragColor1=vec4(0.0);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND AUX0\nvec4 hook(){return vec4(0.0);}\n");
    }

    @Test public void parsesMultipleSaveDirectivesForMrtPasses() {
        List<MpvHookShader.Pass> passes = MpvHookShader.parse(
                "//!DESC mrt\n//!BIND MAIN\n//!SAVE target0 target1 target2\n"
                + "void hook(){fragColor0=vec4(0.0);fragColor1=vec4(0.0);fragColor2=vec4(0.0);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND target0\nvec4 hook(){return vec4(1.0);}\n");
        assertEquals(2, passes.size());
        assertEquals(List.of("target0", "target1", "target2"), passes.get(0).saves);
        assertEquals("target0", passes.get(0).save);
        assertFalse(passes.get(0).isFinalToScreen());
        assertTrue(passes.get(1).isFinalToScreen());
    }

    @Test public void rejectsEmptyDuplicateAndPartiallyWrittenSaveTargetsAndEnforcesOpaqueTerminalVoidHook() {
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\n//!SAVE    \nvec4 hook(){return vec4(1.0);}\n"));
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC mrt\n//!BIND MAIN\n//!SAVE dup dup out2\n"
                + "void hook(){fragColor0=vec4(0.0);fragColor1=vec4(0.0);fragColor2=vec4(0.0);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND dup\nvec4 hook(){return vec4(1.0);}\n"));
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC partial\n//!BIND MAIN\n//!SAVE t0 t1\n"
                + "void hook(){fragColor0=vec4(1.0);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND t0\nvec4 hook(){return vec4(1.0);}\n"));
        MpvHookShader.Pass terminalVoid = MpvHookShader.parse(
                "//!DESC final\n//!BIND MAIN\nvoid hook(){fragColor=vec4(0.5,0.5,0.5,0.25);}\n").get(0);
        String src = MpvHookShader.fragmentSource(terminalVoid);
        assertTrue("Terminal void hook() must force opaque alpha", src.contains("fragColor.a = 1.0;"));
    }

    @Test public void rejectsMultiSavePassWithVec4HookLeavingMrtOutputsUndefined() {
        // A vec4 hook() writes only fragColor0; additional SAVE targets would carry
        // undefined content into later passes. Fail closed at parse time.
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC malformed\n//!BIND MAIN\n//!SAVE target0 target1\nvec4 hook(){return MAIN_tex(MAIN_pos);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND target0\nvec4 hook(){return vec4(1.0);}\n"));
        // Single SAVE with a vec4 hook stays valid.
        MpvHookShader.parse(
                "//!DESC single\n//!BIND MAIN\n//!SAVE target0\nvec4 hook(){return MAIN_tex(MAIN_pos);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND target0\nvec4 hook(){return vec4(1.0);}\n");
    }

    @Test public void validatesEffectiveEntryPointAndRejectsShadowedOrInactiveMrtWrites() {
        // Inactive #if 0 void hook() must not hide an active vec4 hook().
        MpvHookShader.Pass ifZero = MpvHookShader.parse(
                "//!DESC masked\n//!BIND MAIN\n"
                + "#if 0\nvoid hook(){fragColor=vec4(0.0);}\n#endif\n"
                + "vec4 hook(){return vec4(0.25,0.5,0.75,0.1);}\n").get(0);
        String src = MpvHookShader.fragmentSource(ifZero);
        assertTrue(src.contains("fragColor = hook();"));
        assertTrue(src.contains("fragColor.a = 1.0;"));

        // Conditional #ifdef hook() entry must fail closed instead of guessing.
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC cond\n//!BIND MAIN\n#ifdef USE_VOID\nvoid hook(){fragColor=vec4(0.0);}\n"
                + "#else\nvec4 hook(){return vec4(1.0);}\n#endif\n"));

        // Shadowed local fragColor1 inside a nested block must not satisfy SAVE A B.
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC shadowed\n//!BIND MAIN\n//!SAVE A B\n"
                + "void hook(){fragColor0=vec4(1.0);{vec4 fragColor1;fragColor1=vec4(1.0);}}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND A\n//!BIND B\nvec4 hook(){return vec4(1.0);}\n"));

        // Write gated by #if 0 or unverified #ifdef must fail closed for MRT outputs.
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC inactive_write\n//!BIND MAIN\n//!SAVE A B\n"
                + "void hook(){fragColor0=vec4(1.0);\n#if 0\nfragColor1=vec4(1.0);\n#endif\n}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND A\n//!BIND B\nvec4 hook(){return vec4(1.0);}\n"));
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC cond_write\n//!BIND MAIN\n//!SAVE A B\n"
                + "void hook(){fragColor0=vec4(1.0);\n#ifdef ENABLE_B\nfragColor1=vec4(1.0);\n#endif\n}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND A\n//!BIND B\nvec4 hook(){return vec4(1.0);}\n"));
    }

    @Test public void acceptsVoidParameterListAndSplicesLineContinuationsBeforeComments() {
        MpvHookShader.Pass vec4Void = MpvHookShader.parse(
                "//!DESC vec4_void\n//!BIND MAIN\nvec4 hook(void){return MAIN_tex(MAIN_pos);}\n").get(0);
        String vec4VoidSrc = MpvHookShader.fragmentSource(vec4Void);
        assertTrue(vec4VoidSrc.contains("fragColor = hook();"));
        assertTrue(vec4VoidSrc.contains("fragColor.a = 1.0;"));

        List<MpvHookShader.Pass> mrtVoid = MpvHookShader.parse(
                "//!DESC mrt_void\n//!BIND MAIN\n//!SAVE A B\nvoid hook(void){fragColor0=vec4(1.0);fragColor1=vec4(0.5);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND A\n//!BIND B\nvec4 hook(void){return MAIN_tex(MAIN_pos);}\n");
        String mrtVoidSrc = MpvHookShader.fragmentSource(mrtVoid.get(0));
        assertTrue(mrtVoidSrc.contains("layout(location = 0) out vec4 fragColor0;"));
        assertFalse(mrtVoidSrc.contains("fragColor = hook();"));

        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC non_empty_params\n//!BIND MAIN\nvec4 hook(int x){return vec4(1.0);}\n"));

        // Per GLSL ES 3.00 §3.3, backslash-newline splicing occurs before // comment stripping:
        // a continued // comment hides a trailing `void hook(){}` line instead of rejecting the active vec4 hook.
        MpvHookShader.Pass splicedComment = MpvHookShader.parse(
                "//!DESC spliced\n//!BIND MAIN\n// continued comment \\\nvoid hook(){fragColor=vec4(0.0);}\n"
                + "vec4 hook(){return vec4(0.25,0.5,0.75,1.0);}\n").get(0);
        String splicedSrc = MpvHookShader.fragmentSource(splicedComment);
        assertTrue(splicedSrc.contains("fragColor = hook();"));

        // Headers whose #define line boundary differs must NOT be conflated by whitespace normalization during MRT fusion.
        MpvHookShader.Pass headerA = MpvHookShader.parse(
                "//!DESC A\n//!BIND MAIN\n//!SAVE T0\n#define MAKE\nconst float k = 2.0;\nvec4 hook(){return vec4(k);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND T0\nvec4 hook(){return vec4(1.0);}\n").get(0);
        MpvHookShader.Pass headerB = MpvHookShader.parse(
                "//!DESC B\n//!BIND MAIN\n//!SAVE T1\n#define MAKE const float k = 2.0;\nvec4 hook(){float k = 3.0; float r; { MAKE r = k; } return vec4(r);}\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND T1\nvec4 hook(){return vec4(1.0);}\n").get(0);
        assertFalse("Headers separated only by directive newline must not fuse",
                GraphicsShaderTransforms.canFusePasses(headerA, headerB));

        // Multiline hook() signature inside an unknown preprocessor conditional must be rejected.
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC multiline_cond_hook\n//!BIND MAIN\n"
                + "#ifdef USE_VOID\nvoid\nhook(){fragColor=vec4(1.0);}\n#endif\n"
                + "vec4 hook(){return vec4(1.0);}\n"));

        // Convolution mat4(...) inside inactive #if 0 branch must not mark a non-CNN pass as CNN.
        MpvHookShader.Pass deadMatPass = MpvHookShader.parse(
                "//!DESC dead_mat\n//!BIND MAIN\n"
                + "vec4 hook(){\n#if 0\nvec4 x = mat4(1.0) * MAIN_tex(MAIN_pos);\n#endif\n"
                + "return MAIN_tex(MAIN_pos);\n}\n").get(0);
        assertFalse("Inactive #if 0 mat4() must not trigger CNN precision classification",
                MpvHookShader.isCnnConvolutionPass(deadMatPass));
        assertFalse("fragmentSource with tiered precision enabled must not switch to mediump for #if 0 mat4()",
                MpvHookShader.fragmentSource(deadMatPass, false, true).contains("precision mediump float;"));
        MpvHookShader.Pass activeMatPass = MpvHookShader.parse(
                "//!DESC active_mat\n//!BIND MAIN\n"
                + "vec4 hook(){\nreturn mat4(1.0) * MAIN_tex(MAIN_pos);\n}\n").get(0);
        assertTrue(MpvHookShader.isCnnConvolutionPass(activeMatPass));
        assertTrue("fragmentSource with tiered precision enabled must emit mediump for active mat4() pass",
                MpvHookShader.fragmentSource(activeMatPass, false, true).contains("precision mediump float;"));

        // Block comment containing newlines inside a preprocessor #if directive must still evaluate as #if 0 per GLSL ES.
        List<MpvHookShader.Pass> directiveBlockComment = MpvHookShader.parse(
                "//!DESC block_comment_preproc\n//!BIND MAIN\n"
                + "#if /* multiline\nnote */ 0\nvoid hook() {}\n#endif\n"
                + "vec4 hook() { return MAIN_tex(MAIN_pos); }\n");
        assertEquals(1, directiveBlockComment.size());

        // Macro remapping fragColor1 -> fragColor0 in multi-SAVE pass must be rejected fail-closed.
        assertThrows(IllegalArgumentException.class, () -> MpvHookShader.parse(
                "//!DESC remap_fragcolor\n//!BIND MAIN\n//!SAVE A B\n"
                + "#define fragColor1 fragColor0\n"
                + "void hook() { fragColor0 = vec4(1.0); fragColor1 = vec4(0.5); }\n"
                + "//!DESC final\n//!BIND MAIN\n//!BIND A\n//!BIND B\nvec4 hook() { return vec4(1.0); }\n"));
    }
}
