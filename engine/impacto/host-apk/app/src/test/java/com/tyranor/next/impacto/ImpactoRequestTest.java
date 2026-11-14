package com.tyranor.next.impacto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ImpactoRequestTest {
    private static final String DIR = "/storage/emulated/0/Games/mo6tw";
    private static final String LINKS_UNAVAILABLE = "symbolic links unavailable on this host";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static String keyFor(String dir, String profile) {
        return ImpactoRequest.gameKeyFor(dir, profile);
    }

    private static ImpactoRequest valid(String dir, String profile) {
        return ImpactoRequest.parse(dir, profile, keyFor(dir, profile), "Memories Off 6");
    }

    private static void assertRejected(String dir, String profile, String key) {
        try {
            ImpactoRequest.parse(dir, profile, key, "t");
            fail("expected rejection for " + dir + " / " + profile + " / " + key);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** Rejection of the directory itself: the key is the correct one for that directory. */
    private static void assertDirRejected(String dir) {
        assertRejected(dir, "mo6tw", dir == null ? "0123456789abcdef01234567" : keyFor(dir.trim().replaceAll("/+$", ""), "mo6tw"));
    }

    @Test
    public void gameKeyMatchesTheAppsGoldenVector() {
        // The same vector is asserted by the app's ImpactoExternalEngineModuleTest; both sides must agree.
        assertEquals("853382f8b09761cd5c6e475c", keyFor(DIR, "mo6tw"));
        assertNotEquals(keyFor(DIR, "mo6tw"), keyFor(DIR + "-2", "mo6tw"));
        assertNotEquals(keyFor(DIR, "mo6tw"), keyFor(DIR, "chlcc"));
    }

    @Test
    public void acceptsEveryDetectableProfile() {
        for (String profile : ImpactoRequest.PROFILES) {
            assertEquals(profile, valid("/storage/emulated/0/Games/x", profile).profile);
        }
    }

    @Test
    public void profileListMatchesTheAppCatalog() {
        // Same seven upstream game profiles as ImpactoGameCatalog in the app; viewers are not games.
        assertEquals(7, ImpactoRequest.PROFILES.size());
        assertTrue(ImpactoRequest.PROFILES.contains("mo6tw"));
        assertTrue(!ImpactoRequest.PROFILES.contains("characterviewer"));
    }

    @Test
    public void rejectsUnknownOrMalformedFields() {
        assertRejected(DIR, "characterviewer", keyFor(DIR, "characterviewer"));
        assertRejected(DIR, "../mo6tw", keyFor(DIR, "../mo6tw"));
        assertRejected(DIR, null, keyFor(DIR, "mo6tw"));
        assertRejected(DIR, "mo6tw", null);
        assertRejected(DIR, "mo6tw", "../../etc");
        assertRejected(DIR, "mo6tw", keyFor(DIR, "mo6tw").toUpperCase());
        assertRejected(DIR, "mo6tw", keyFor(DIR, "mo6tw") + "0");
    }

    @Test
    public void keyIsBoundToTheDirectoryAndProfile() {
        // A caller cannot reuse one game's key for a different folder or profile.
        assertRejected(DIR + "-other", "mo6tw", keyFor(DIR, "mo6tw"));
        assertRejected(DIR, "chlcc", keyFor(DIR, "mo6tw"));
    }

    @Test
    public void rejectsUnsafeDirectories() {
        assertDirRejected(null);
        assertDirRejected("relative/path");
        assertDirRejected("/");
        assertDirRejected("/data/data/com.other.app/files");
        assertDirRejected("/system/etc");
        assertDirRejected("/storage/../data");
        assertDirRejected("/storage/emulated/0/./x");
        assertDirRejected("/storage/emulated/0/x\0y");
        assertDirRejected("/storage/emulated/0/Android/data/com.other.app/files");
        assertDirRejected("/storage/emulated/0/android/OBB/com.other.app");
        assertDirRejected("/storage/1234-ABCD/Android/data");
    }

    @Test
    public void acceptsSharedStorageRoots() {
        valid("/storage/emulated/0/Games/x", "mo6tw");
        valid("/storage/1234-ABCD/Games/x", "mo6tw");
        valid("/sdcard/Games/x", "mo6tw");
        // An ordinary folder that merely contains the word "Android" is fine.
        valid("/storage/emulated/0/Android Games/x", "mo6tw");
    }

    @Test
    public void normalizesTrailingSlashesAndClipsTitle() {
        String dir = "/storage/emulated/0/Games/x";
        ImpactoRequest r = ImpactoRequest.parse(" " + dir + "// ", "mo6tw", keyFor(dir, "mo6tw"),
                new String(new char[500]).replace('\0', 'a'));
        assertEquals(dir, r.gameDir);
        assertEquals(200, r.title.length());
    }

    @Test
    public void dataRootIsPerGame() {
        File base = new File("/ext/files");
        String key = keyFor(DIR, "mo6tw");
        assertEquals(new File("/ext/files/games/" + key), ImpactoRequest.dataRoot(base, key));
        assertTrue(!ImpactoRequest.dataRoot(base, key).equals(ImpactoRequest.dataRoot(base, keyFor(DIR, "chlcc"))));
    }

    @Test
    public void linkGamedataLinksAndIsIdempotent() throws IOException {
        File game = temp.newFolder("game");
        File root = ImpactoRequest.dataRoot(temp.newFolder("ext"), keyFor(DIR, "mo6tw"));
        Path link = root.toPath().resolve("gamedata").resolve("mo6tw");
        try {
            ImpactoRequest.linkGamedata(root, "mo6tw", game.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException(LINKS_UNAVAILABLE, e);
            return;
        }
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(game.toPath(), Files.readSymbolicLink(link));
        ImpactoRequest.linkGamedata(root, "mo6tw", game.toPath());
        assertEquals(game.toPath(), Files.readSymbolicLink(link));
    }

    @Test
    public void linkGamedataRepointsAnExistingLink() throws IOException {
        File first = temp.newFolder("first");
        File second = temp.newFolder("second");
        File root = ImpactoRequest.dataRoot(temp.newFolder("ext"), keyFor(DIR, "mo6tw"));
        Path link = root.toPath().resolve("gamedata").resolve("mo6tw");
        try {
            ImpactoRequest.linkGamedata(root, "mo6tw", first.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException(LINKS_UNAVAILABLE, e);
            return;
        }
        ImpactoRequest.linkGamedata(root, "mo6tw", second.toPath());
        assertEquals(second.toPath(), Files.readSymbolicLink(link));
    }

    @Test
    public void linkGamedataRefusesToReplaceARealDirectory() throws IOException {
        File game = temp.newFolder("game");
        File root = ImpactoRequest.dataRoot(temp.newFolder("ext"), keyFor(DIR, "mo6tw"));
        File real = new File(root, "gamedata/mo6tw");
        assertTrue(real.mkdirs());
        File keep = new File(real, "keep.txt");
        assertTrue(keep.createNewFile());
        try {
            ImpactoRequest.linkGamedata(root, "mo6tw", game.toPath());
            fail("a real directory must not be replaced");
        } catch (IOException expected) {
            assertTrue(keep.exists());
        }
    }
}
