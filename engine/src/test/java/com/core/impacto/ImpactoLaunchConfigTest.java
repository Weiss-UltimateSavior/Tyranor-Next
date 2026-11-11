package com.core.impacto;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.*;

public class ImpactoLaunchConfigTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void escapesQuotesControlsAndUtf8BytesWithoutLuaInjection() {
        assertEquals("\"a\\\"\\\\\\010\\0009\\228\\184\\173\"", ImpactoLaunchConfig.luaString("a\"\\\n\0" + "9中"));
    }
    @Test public void stableIdentityIsSha256AndIsolatesSameProfileGames() throws Exception {
        assertEquals(64, ImpactoFiles.identityKey("content://tree/one").length());
        assertEquals(ImpactoFiles.identityKey("content://tree/one"), ImpactoFiles.identityKey("content://tree/one"));
        assertNotEquals(ImpactoFiles.identityKey("content://tree/one"), ImpactoFiles.identityKey("content://tree/two"));
        assertThrows(IOException.class, () -> ImpactoFiles.identityKey(""));
    }
    @Test public void generatedWrapperRemapsStructuredMountsAndAllSaveFields() throws Exception {
        File root = temp.newFolder();
        ImpactoProfiles.Profile profile = ImpactoProfiles.get("cclcc-switch");
        Map<String, String> scripts = ImpactoLaunchConfig.scripts(new File(root, "game"), new File(root, "resources"), new File(root, "user"), new File(root, "session"), profile);
        String wrapper = scripts.get("profile.lua");
        assertTrue(wrapper.contains("mount.Path = remap(mount.Path)"));
        assertTrue(wrapper.contains("mount.Order = remap(mount.Order)"));
        assertTrue(wrapper.contains("ipairs(mounts)"));
        assertFalse(wrapper.contains("mount.Whitelist ="));
        for (String field : new String[]{"SaveFilePath", "SystemDataPath", "QuickDataPath"}) assertTrue(wrapper.contains("root.SaveData[\"" + field + "\"]"));
        assertTrue(wrapper.contains("AchievementSystem ="));
        assertTrue(wrapper.contains("string.sub(path, 1, #prefix) ~= prefix"));
    }
    @Test public void noProfileRunsUserSuppliedLuaOrLosesSaveOverrides() throws Exception {
        File root = temp.newFolder();
        for (String id : ImpactoProfiles.ids()) {
            ImpactoProfiles.Profile p = ImpactoProfiles.get(id);
            Map<String, String> scripts = ImpactoLaunchConfig.scripts(new File(root, "game"), new File(root, "runtime"), new File(root, "user"), new File(root, "session"), p);
            String wrapper = scripts.get("profile.lua");
            assertTrue(wrapper.startsWith("-- Generated"));
            assertTrue(wrapper.contains("dofile(" + ImpactoLaunchConfig.luaString(new File(root, "runtime/" + p.lua).getPath()) + ")"));
            assertFalse(scripts.get("gamedefinitions.lua").contains("Patch = { English"));
            for (String field : p.saveFields.keySet()) assertTrue(wrapper.contains("root.SaveData[\"" + field + "\"] ="));
        }
        assertFalse(ImpactoProfiles.isSupported("characterviewer"));
        assertFalse(ImpactoProfiles.isSupported("CHLCC"));
        assertFalse(ImpactoProfiles.isSupported(null));
    }
    @Test public void argvIsFixedAndMissingOrMutatedConfigFailsClosed() throws Exception {
        File game = temp.newFolder("game"); File runtime = temp.newFolder("runtime");
        File user = temp.newFolder("user"); File session = temp.newFolder("session");
        ImpactoProfiles.Profile profile = ImpactoProfiles.get("chlcc");
        Map<String, String> scripts = ImpactoLaunchConfig.scripts(game, runtime, user, session, profile);
        assertThrows(IOException.class, () -> ImpactoLaunchConfig.verify(session, scripts));
        ImpactoLaunchConfig.write(session, scripts); ImpactoLaunchConfig.verify(session, scripts);
        String[] args = ImpactoLaunchConfig.arguments(profile, session, user, runtime);
        assertArrayEquals(new String[]{"-g", "chlcc", "-bp", new File(session, "basepaths.lua").getPath(), "-gc", new File(session, "gamedefinitions.lua").getPath(), "-uc", new File(user, "user.toml").getPath(), "--tyranor-resource-root", runtime.getPath()}, args);
        File wrapper = new File(session, "profile.lua"); assertTrue(wrapper.setWritable(true));
        Files.write(wrapper.toPath(), "arbitrary()".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ImpactoLaunchConfig.verify(session, scripts));
    }
    @Test public void userTomlAndSavesSurvivePreparationAndDistinctIdentitiesStaySeparate() throws Exception {
        File root = temp.newFolder(); ImpactoProfiles.Profile p = ImpactoProfiles.get("mo6tw");
        String first = ImpactoFiles.identityKey("first");
        File user = ImpactoLaunchPreparation.prepareUser(root, first, p);
        File config = new File(user, "user.toml"); Files.write(config.toPath(), "user choices".getBytes(StandardCharsets.UTF_8));
        File save = new File(user, "saves/SYSTEM.DAT"); Files.write(save.toPath(), new byte[]{1, 2, 3});
        assertEquals(user, ImpactoLaunchPreparation.prepareUser(root, first, p));
        assertEquals("user choices", new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(save.toPath()));
        File second = ImpactoLaunchPreparation.prepareUser(root, ImpactoFiles.identityKey("second"), p);
        assertNotEquals(user, second); assertFalse(new File(second, "saves/SYSTEM.DAT").exists());
    }
    @Test public void pathsRejectTraversalAndSymlinkEscapes() throws Exception {
        File root = temp.newFolder(); File outside = temp.newFolder();
        for (String bad : new String[]{"../outside", "/absolute", "a\\b", "a/./b", "a//b", "a/../b", "C:/a"}) assertThrows(IOException.class, () -> ImpactoFiles.child(root, bad));
        Files.createSymbolicLink(new File(root, "escape").toPath(), outside.toPath());
        assertThrows(IOException.class, () -> ImpactoFiles.child(root, "escape/file"));
    }
    @Test public void gameLayoutChecksRequiredAssetsAndLooseFileSymlinks() throws Exception {
        File game = temp.newFolder(); ImpactoProfiles.Profile profile = ImpactoProfiles.get("cclcc-switch");
        assertThrows(IOException.class, () -> ImpactoLaunchPreparation.validateGame(game, profile));
        for (ImpactoProfiles.Requirement required : profile.required) {
            File file = new File(game, required.path); file.getParentFile().mkdirs();
            if (required.directory) file.mkdirs(); else Files.write(file.toPath(), new byte[]{1});
        }
        assertEquals(game.getCanonicalFile(), ImpactoLaunchPreparation.validateGame(game.getCanonicalFile(), profile));
        File archive = new File(game, "bg_webp.mpk");
        Files.write(archive.toPath(), new byte[0]);
        assertThrows(IOException.class, () -> ImpactoLaunchPreparation.validateGame(game.getCanonicalFile(), profile));
        Files.write(archive.toPath(), new byte[]{1});
        Files.createSymbolicLink(new File(game, "script/escape").toPath(), temp.newFile().toPath());
        assertThrows(IOException.class, () -> ImpactoLaunchPreparation.validateGame(game.getCanonicalFile(), profile));
    }
}
