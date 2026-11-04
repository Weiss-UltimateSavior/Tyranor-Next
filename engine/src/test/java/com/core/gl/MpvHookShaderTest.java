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
}
