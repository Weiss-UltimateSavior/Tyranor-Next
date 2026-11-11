package com.core.impacto;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ImpactoResourceStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] CONTENT = "trusted resource".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "profiles/test/game.lua";
    private static String hash(byte[] bytes) { return ImpactoFiles.hex(ImpactoFiles.digest().digest(bytes)); }
    private static Map<String, ImpactoResourceStore.Entry> entries(String path, byte[] content) {
        Map<String, ImpactoResourceStore.Entry> result = new LinkedHashMap<>();
        result.put(path, new ImpactoResourceStore.Entry(path, content.length, hash(content)));
        return result;
    }
    private static byte[] zip(String path, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(path)); zip.write(content); zip.closeEntry();
        }
        return output.toByteArray();
    }
    private static byte[] xz(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XZOutputStream xz = new XZOutputStream(output, new LZMA2Options(1))) { xz.write(content); }
        return output.toByteArray();
    }
    private static ImpactoResourceStore.Manifest manifest(byte[] archive) throws IOException {
        return new ImpactoResourceStore.Manifest(hash(CONTENT), archive.length, hash(archive), entries(PATH, CONTENT));
    }
    @Test public void installsVerifiedGenerationAndReusesItWithoutReadingArchive() throws Exception {
        byte[] archive = xz(zip(PATH, CONTENT));
        File store = temp.newFolder("resources");
        ImpactoResourceStore.Manifest manifest = manifest(archive);
        File installed = ImpactoResourceStore.ensure(store, manifest, () -> new ByteArrayInputStream(archive), Long.MAX_VALUE);
        assertArrayEquals(CONTENT, Files.readAllBytes(new File(installed, PATH).toPath()));
        assertEquals(installed, ImpactoResourceStore.ensure(store, manifest, () -> { throw new AssertionError("Must reuse verified install"); }, 0));
        assertEquals(installed, ImpactoResourceStore.current(store, manifest));
    }
    @Test public void rejectsCorruptCompressedArchiveAndKeepsEarlierVersion() throws Exception {
        byte[] archive = xz(zip(PATH, CONTENT));
        File store = temp.newFolder("resources");
        File earlier = new File(store, "generations/previous/resources");
        assertTrue(earlier.mkdirs());
        File old = new File(earlier, "keep"); Files.write(old.toPath(), CONTENT);
        byte[] corrupt = archive.clone(); corrupt[corrupt.length / 2] ^= 1;
        assertThrows(IOException.class, () -> ImpactoResourceStore.ensure(store, manifest(archive), () -> new ByteArrayInputStream(corrupt), Long.MAX_VALUE));
        assertArrayEquals(CONTENT, Files.readAllBytes(old.toPath()));
        assertEquals(1, store.listFiles().length);
    }
    @Test public void rejectsTraversalEvenWhenArchiveHashMatches() throws Exception {
        byte[] archive = xz(zip("../escape", CONTENT));
        File store = temp.newFolder("resources");
        assertThrows(IOException.class, () -> ImpactoResourceStore.ensure(store, manifest(archive), () -> new ByteArrayInputStream(archive), Long.MAX_VALUE));
        assertFalse(new File(store, "escape").exists());
    }
    @Test public void rejectsAbsoluteAndWindowsArchivePaths() throws Exception {
        for (String path : new String[]{"/escape", "C:/escape", "resources\\escape", "resources/../escape", "resources//x"}) {
            byte[] data = zip(path, CONTENT);
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
                assertThrows(IOException.class, () -> ImpactoResourceStore.extract(temp.newFolder(), manifest(data), zip));
            }
        }
    }
    @Test public void rejectsUnexpectedOrMissingEntries() throws Exception {
        for (byte[] data : new byte[][]{zip("resources/other", CONTENT), zip(PATH, new byte[]{1, 2})}) {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
                assertThrows(IOException.class, () -> ImpactoResourceStore.extract(temp.newFolder(), manifest(data), zip));
            }
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(new byte[0]))) {
            assertThrows(IOException.class, () -> ImpactoResourceStore.extract(temp.newFolder(), manifest(CONTENT), zip));
        }
    }
    @Test public void rejectsEntryLargerThanDeclaredQuota() throws Exception {
        byte[] archive = zip(PATH, new byte[CONTENT.length + 1]);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            assertThrows(IOException.class, () -> ImpactoResourceStore.extract(temp.newFolder(), manifest(archive), zip));
        }
    }
    @Test public void rejectsOversizedManifestAndInvalidGeneration() throws Exception {
        Map<String, ImpactoResourceStore.Entry> entries = entries(PATH, CONTENT);
        entries.put(PATH, new ImpactoResourceStore.Entry(PATH, ImpactoResourceStore.MAX_FILE_BYTES + 1, hash(CONTENT)));
        assertThrows(IOException.class, () -> new ImpactoResourceStore.Manifest(hash(CONTENT), 5, hash(CONTENT), entries));
        assertThrows(IOException.class, () -> new ImpactoResourceStore.Manifest("../bad", 5, hash(CONTENT), entries(PATH, CONTENT)));
        Map<String, ImpactoResourceStore.Entry> many = new LinkedHashMap<>();
        for (int i = 0; i <= ImpactoResourceStore.MAX_FILES; i++) many.put("resources/" + i, new ImpactoResourceStore.Entry("resources/" + i, 0, hash(new byte[0])));
        assertThrows(IOException.class, () -> new ImpactoResourceStore.Manifest(hash(CONTENT), 5, hash(CONTENT), many));
    }
    @Test public void rejectsManifestDuplicatePathsAndUnsupportedVersion() throws Exception {
        String entry = "{\"path\":\"" + PATH + "\",\"size\":1,\"sha256\":\"" + hash(CONTENT) + "\"}";
        String json = "{\"version\":1,\"generation\":\"" + hash(CONTENT) + "\",\"archive\":{\"name\":\"resources.zip.xz\",\"size\":1,\"sha256\":\"" + hash(CONTENT) + "\"},\"files\":[" + entry + "," + entry + "]}";
        assertThrows(IOException.class, () -> ImpactoResourceStore.Manifest.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> ImpactoResourceStore.Manifest.read(new ByteArrayInputStream("{\"version\":2}".getBytes(StandardCharsets.UTF_8))));
    }
    @Test public void noSpaceFailsBeforeReadingSource() throws Exception {
        byte[] archive = xz(zip(PATH, CONTENT));
        assertThrows(IOException.class, () -> ImpactoResourceStore.ensure(temp.newFolder(), manifest(archive), () -> { throw new AssertionError("No space must preflight"); }, 0));
    }
    @Test public void failedCopyCleansOnlyOwnStagingAndPreservesUserData() throws Exception {
        File base = temp.newFolder(); File store = new File(base, "resources"); assertTrue(store.mkdir());
        File saves = new File(base, "users/game/saves"); assertTrue(saves.mkdirs());
        File save = new File(saves, "SYSTEM.DAT"); Files.write(save.toPath(), CONTENT);
        assertThrows(IOException.class, () -> ImpactoResourceStore.ensure(store, manifest(CONTENT), () -> { throw new IOException("Simulated read error"); }, Long.MAX_VALUE));
        assertArrayEquals(CONTENT, Files.readAllBytes(save.toPath()));
        assertEquals(1, store.listFiles().length); // generations only, no abandoned staging
    }
    @Test public void interruptedStagesAreRemovedBeforeSpaceCheckAndExistingGenerationsStay() throws Exception {
        byte[] archive = xz(zip(PATH, CONTENT)); File store = temp.newFolder();
        File abandoned = new File(store, "staging-" + java.util.UUID.randomUUID());
        assertTrue(abandoned.mkdir()); Files.write(new File(abandoned, "partial").toPath(), CONTENT);
        File unrelated = new File(store, "staging-user-data"); assertTrue(unrelated.mkdir());
        File old = new File(store, "generations/old"); assertTrue(old.mkdirs());
        ImpactoResourceStore.ensure(store, manifest(archive), () -> new ByteArrayInputStream(archive), () -> {
            assertFalse("Reclaim interrupted stage before computing free space", abandoned.exists());
            return Long.MAX_VALUE;
        });
        assertTrue(old.isDirectory()); assertTrue(unrelated.isDirectory());
    }
    @Test public void partialStampAndChangedInstalledBytesAreNotCurrent() throws Exception {
        byte[] archive = xz(zip(PATH, CONTENT)); File store = temp.newFolder();
        ImpactoResourceStore.Manifest manifest = manifest(archive);
        File installed = ImpactoResourceStore.ensure(store, manifest, () -> new ByteArrayInputStream(archive), Long.MAX_VALUE);
        File resource = new File(installed, PATH); assertTrue(resource.setWritable(true));
        byte[] altered = CONTENT.clone(); altered[0] ^= 1; Files.write(resource.toPath(), altered);
        assertThrows(IOException.class, () -> ImpactoResourceStore.current(store, manifest));
        Files.delete(new File(installed, ".ready").toPath());
        assertThrows(IOException.class, () -> ImpactoResourceStore.current(store, manifest));
    }
    @Test public void symlinkEscapeInInstalledResourceIsRejected() throws Exception {
        byte[] archive = xz(zip(PATH, CONTENT)); File store = temp.newFolder();
        ImpactoResourceStore.Manifest manifest = manifest(archive);
        File installed = ImpactoResourceStore.ensure(store, manifest, () -> new ByteArrayInputStream(archive), Long.MAX_VALUE);
        File target = new File(installed, PATH); Files.delete(target.toPath());
        File external = temp.newFile(); Files.write(external.toPath(), CONTENT);
        Files.createSymbolicLink(target.toPath(), external.toPath());
        assertThrows(IOException.class, () -> ImpactoResourceStore.current(store, manifest));
    }
}
