package com.core.impacto;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.Assert.*;

/** Source/manifest guardrails supplement, but never replace, actual Android lifecycle tests. */
public class ImpactoHostContractTest {
    private static File engine() {
        File current = new File(System.getProperty("user.dir"));
        while (current != null) {
            if (new File(current, "src/main/java/com/core/impacto/ImpactoActivity.java").isFile()) return current;
            if (new File(current, "engine/src/main/java/com/core/impacto/ImpactoActivity.java").isFile()) return new File(current, "engine");
            current = current.getParentFile();
        }
        throw new AssertionError("Engine sources unavailable");
    }
    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(new File(engine(), name).toPath()), StandardCharsets.UTF_8);
    }
    @Test public void activityIsNotExportedAndHasDedicatedSingleInstanceProcess() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList activities = factory.newDocumentBuilder().parse(new File(engine(), "src/main/AndroidManifest.xml")).getElementsByTagName("activity");
        String ns = "http://schemas.android.com/apk/res/android";
        Element host = null;
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (activity.getAttributeNS(ns, "name").equals("com.core.impacto.ImpactoActivity")) host = activity;
        }
        assertNotNull(host);
        assertEquals("false", host.getAttributeNS(ns, "exported"));
        assertEquals(":impacto", host.getAttributeNS(ns, "process"));
        assertEquals("singleInstance", host.getAttributeNS(ns, "launchMode"));
    }
    @Test public void hostUsesOnlyPrivateSdlAbsoluteLoadAndNoExtraction() throws Exception {
        String host = source("src/main/java/com/core/impacto/ImpactoActivity.java");
        assertTrue(host.contains("import org.libsdl.impacto.SDLActivity;"));
        assertTrue(host.contains("System.load(preparedLibrary)"));
        assertTrue(host.contains("protected String getMainSharedObject()"));
        assertFalse(host.contains("System.loadLibrary("));
        assertFalse(host.contains("NativeRuntimePack.ensure("));
        assertFalse(host.contains("ImpactoResourceStore.ensure("));
        assertTrue(host.contains("ImpactoLaunchValidation"));
        assertTrue(host.contains("ImpactoShutdownPolicy.finish("));
        assertTrue(host.contains("ImpactoShutdownPolicy.BACKGROUND_GRACE_MS"));
    }
    @Test public void privateSdlChecksWriterBeforeClearingNativeLifecycleEvents() throws Exception {
        String sdl = source("src/main/java/org/libsdl/impacto/SDLActivity.java");
        String destroy = sdl.substring(sdl.indexOf("protected void onDestroy()"), sdl.indexOf("public void onBackPressed()"));
        assertTrue(destroy.contains("ImpactoShutdownPolicy.mayQuitNative(nativeThread)"));
        assertTrue(destroy.indexOf("ImpactoShutdownPolicy.mayQuitNative(nativeThread)") < destroy.indexOf("SDLActivity.nativeQuit()"));
        assertTrue(destroy.contains("finally {\n            super.onDestroy();"));
    }
    @Test public void hostTextUsesLaunchersLanguageWithoutChangingGameLocale() throws Exception {
        String host = source("src/main/java/com/core/impacto/ImpactoActivity.java");
        for (String key : new String[]{"engine_preparing_game", "engine_impacto_init_failed", "engine_launch_failed", "engine_ok", "engine_another_game_running"}) {
            assertTrue(host.contains("EngineUiText.get(this, R.string." + key + ")"));
        }
        assertFalse(host.contains("getString(R.string."));
        assertFalse(host.contains("Locale.setDefault"));
    }
    @Test public void privateSdlRemainsKeptForStringResolvedCallbacks() throws Exception {
        assertTrue(source("consumer-rules.pro").contains("-keep class org.libsdl.impacto.** { *; }"));
    }
    @Test public void catalogMatchesSingleAuditedProfileSource() throws Exception {
        byte[] json = Files.readAllBytes(new File(engine(), "impacto/profiles.json").toPath());
        assertEquals(ImpactoProfiles.SOURCE_SHA256, ImpactoFiles.hex(ImpactoFiles.digest().digest(json)));
        assertEquals(8, ImpactoProfiles.ids().size());
        assertArrayEquals(json, Files.readAllBytes(new File(engine(), "src/main/assets/impacto-runtime/profiles.json").toPath()));
    }
}
