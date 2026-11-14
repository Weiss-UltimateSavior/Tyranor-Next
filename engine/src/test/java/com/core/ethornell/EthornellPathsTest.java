package com.core.ethornell;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class EthornellPathsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void userRootIsBoundToExactIdentityUsingStandardUtf8() throws Exception {
        File files = temp.newFolder("files").getCanonicalFile();
        String identity = "content://games/tree/日本語/中文 😀";
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)))
            digest.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        File expected = new File(files, "ethornell/userdata/" + digest);
        assertEquals(expected, EthornellPaths.userDataDir(files, identity));
        assertEquals(expected, EthornellPaths.validateUserDataDir(files, identity, expected.getPath()));
        assertNotEquals(expected, EthornellPaths.userDataDir(files, identity + "/"));
        rejects(() -> EthornellPaths.validateUserDataDir(files, identity + "/", expected.getPath()));
    }
    @Test public void userRootRejectsOtherPrivateExternalAndRelativeDirectories() throws Exception {
        File files = temp.newFolder("files").getCanonicalFile();
        String identity = "game://a";
        rejects(() -> EthornellPaths.validateUserDataDir(files, identity, new File(files, "fonts").getPath()));
        rejects(() -> EthornellPaths.validateUserDataDir(files, identity, temp.newFolder("external").getPath()));
        rejects(() -> EthornellPaths.validateUserDataDir(files, identity, "ethornell/userdata/a"));
        File expected = EthornellPaths.userDataDir(files, identity);
        rejects(() -> EthornellPaths.validateUserDataDir(files, identity,
                new File(expected.getParentFile(), "../userdata/" + expected.getName()).getPath()));
    }
    @Test public void userRootRejectsSymlinkedAncestorsAndIdentityDirectories() throws Exception {
        File files = temp.newFolder("files").getCanonicalFile();
        File other = temp.newFolder("other").getCanonicalFile();
        File expected = EthornellPaths.userDataDir(files, "game://a");
        Files.createSymbolicLink(new File(files, "ethornell").toPath(), other.toPath());
        rejects(() -> EthornellPaths.validateUserDataDir(files, "game://a", expected.getPath()));
        Files.delete(new File(files, "ethornell").toPath());
        assertTrue(expected.getParentFile().mkdirs());
        Files.createSymbolicLink(expected.toPath(), other.toPath());
        rejects(() -> EthornellPaths.validateUserDataDir(files, "game://a", expected.getPath()));
    }
    @Test public void userRootRejectsMalformedIdentities() throws Exception {
        File files = temp.newFolder("files");
        for (String identity : new String[]{"", " ", "x\0y", "\uD800", "\uDC00", "\uD800x"})
            rejects(() -> EthornellPaths.userDataDir(files, identity));
    }

    @Test public void gameUserRootUsesCanonicalGameRootAndAllowsMissingSavedata() throws Exception {
        File gameRoot = temp.newFolder("game").getCanonicalFile();
        File expected = new File(gameRoot, "savedata");

        assertFalse("Path contract must not create savedata implicitly", expected.exists());
        assertEquals(expected, EthornellPaths.gameUserDataDir(gameRoot));
        assertEquals(expected, EthornellPaths.validateGameUserDataDir(gameRoot, expected.getPath()));
        assertFalse("Validation must not create savedata implicitly", expected.exists());

        assertEquals(expected, EthornellPaths.gameUserDataDir(new File(gameRoot, ".")));
        rejects(() -> EthornellPaths.validateGameUserDataDir(gameRoot,
                new File(gameRoot, "savedata/../savedata").getPath()));
    }

    @Test public void gameUserRootRejectsOtherRelativeExternalAndSymlinkAliasPaths() throws Exception {
        File gameRoot = temp.newFolder("game").getCanonicalFile();
        File other = temp.newFolder("other").getCanonicalFile();
        rejects(() -> EthornellPaths.validateGameUserDataDir(gameRoot, new File(gameRoot, "other").getPath()));
        rejects(() -> EthornellPaths.validateGameUserDataDir(gameRoot, other.getPath()));
        rejects(() -> EthornellPaths.validateGameUserDataDir(gameRoot, "savedata"));

        File rootAlias = new File(temp.getRoot(), "game-alias");
        Files.createSymbolicLink(rootAlias.toPath(), gameRoot.toPath());
        File canonicalExpected = new File(gameRoot, "savedata");
        assertEquals(canonicalExpected, EthornellPaths.validateGameUserDataDir(rootAlias, canonicalExpected.getPath()));
        rejects(() -> EthornellPaths.validateGameUserDataDir(rootAlias,
                new File(rootAlias, "savedata").getPath()));
    }

    @Test public void gameUserRootRejectsSymlinkedSavedataAndRequiresExistingDirectoryRoot() throws Exception {
        File gameRoot = temp.newFolder("game").getCanonicalFile();
        File savedata = new File(gameRoot, "savedata");
        File target = temp.newFolder("target").getCanonicalFile();
        Files.createSymbolicLink(savedata.toPath(), target.toPath());
        rejects(() -> EthornellPaths.validateGameUserDataDir(gameRoot, savedata.getPath()));

        File missing = new File(temp.getRoot(), "missing-game");
        rejects(() -> EthornellPaths.gameUserDataDir(missing));
        rejects(() -> EthornellPaths.validateGameUserDataDir(missing, new File(missing, "savedata").getPath()));
        File regularFile = temp.newFile("not-a-game-directory");
        rejects(() -> EthornellPaths.gameUserDataDir(regularFile));
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation) throws Exception {
        try { operation.run(); fail("must reject invalid identity/root"); }
        catch (IOException expected) { /* Expected policy rejection, not an ignored test. */ }
    }
}
