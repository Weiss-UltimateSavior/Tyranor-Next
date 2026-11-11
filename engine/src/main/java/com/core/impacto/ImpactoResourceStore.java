package com.core.impacto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tukaani.xz.XZInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Immutable, content-addressed resources. Never visits native packs or persistent user data. */
final class ImpactoResourceStore {
    static final int MAX_FILES = 4096;
    static final long MAX_BYTES = 512L * 1024 * 1024;
    static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    static final String ARCHIVE = "resources.zip.xz";
    interface Source { InputStream open() throws IOException; }
    interface Space { long availableBytes(); }
    static final class Entry {
        final String path;
        final long size;
        final String hash;
        Entry(String path, long size, String hash) {
            this.path = path; this.size = size; this.hash = hash;
        }
    }
    static final class Manifest {
        final String generation;
        final long archiveSize;
        final String archiveHash;
        final Map<String, Entry> files;
        final long totalBytes;
        Manifest(String generation, long archiveSize, String archiveHash, Map<String, Entry> files) throws IOException {
            if (!isHash(generation) || !isHash(archiveHash) || archiveSize <= 0 || archiveSize > MAX_BYTES
                    || files.isEmpty() || files.size() > MAX_FILES) throw new IOException("Invalid resource manifest");
            long total = 0;
            File virtualRoot = new File("/impacto-manifest");
            for (Entry entry : files.values()) {
                ImpactoFiles.child(virtualRoot, entry.path);
                if (!entry.path.matches("(profiles|resources|shaders|licenses)/.+")
                        || !isHash(entry.hash) || entry.size < 0 || entry.size > MAX_FILE_BYTES) {
                    throw new IOException("Invalid resource entry");
                }
                total += entry.size;
                if (total > MAX_BYTES) throw new IOException("Resources exceed quota");
            }
            this.generation = generation; this.archiveSize = archiveSize;
            this.archiveHash = archiveHash; this.files = new LinkedHashMap<>(files); this.totalBytes = total;
        }
        static Manifest read(InputStream input) throws IOException {
            try {
                JSONObject json = new JSONObject(new String(ImpactoFiles.read(input, 2 * 1024 * 1024), StandardCharsets.UTF_8));
                if (json.getInt("version") != 1) throw new IOException("Unsupported resource manifest");
                JSONObject archive = json.getJSONObject("archive");
                if (!ARCHIVE.equals(archive.getString("name"))) throw new IOException("Invalid resource archive");
                JSONArray entries = json.getJSONArray("files");
                if (entries.length() > MAX_FILES) throw new IOException("Resource count exceeds quota");
                Map<String, Entry> files = new LinkedHashMap<>();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject item = entries.getJSONObject(i);
                    Entry entry = new Entry(item.getString("path"), item.getLong("size"), item.getString("sha256"));
                    if (files.put(entry.path, entry) != null) throw new IOException("Duplicate resource entry");
                }
                return new Manifest(json.getString("generation"), archive.getLong("size"), archive.getString("sha256"), files);
            } catch (JSONException e) { throw new IOException("Invalid resource manifest", e); }
        }
    }
    private static boolean isHash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }

    static File current(File store, Manifest manifest) throws IOException {
        File generation = ImpactoFiles.child(store, "generations/" + manifest.generation);
        verify(generation, manifest);
        return generation;
    }
    static void verify(File generation, Manifest manifest) throws IOException {
        File stamp = ImpactoFiles.child(generation, ".ready");
        if (!stamp.isFile() || stamp.length() != 64 || !new String(Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8).equals(manifest.generation)) {
            throw new IOException("Resource generation is incomplete");
        }
        for (Entry entry : manifest.files.values()) {
            File file = ImpactoFiles.child(generation, entry.path);
            if (!file.isFile() || file.length() != entry.size || !ImpactoFiles.hash(file).equals(entry.hash)) {
                throw new IOException("Resource integrity mismatch: " + entry.path);
            }
        }
    }
    /** Caller holds the launch admission lock. Failed installs leave every older generation untouched. */
    static File ensure(File store, Manifest manifest, Source source, long availableBytes) throws IOException {
        return ensure(store, manifest, source, () -> availableBytes);
    }
    static File ensure(File store, Manifest manifest, Source source, Space space) throws IOException {
        cleanupStages(store);
        try { return current(store, manifest); } catch (IOException missingOrStale) { /* Stage a verified replacement. */ }
        if (space.availableBytes() < manifest.totalBytes + manifest.archiveSize + 16L * 1024 * 1024) {
            throw new IOException("Insufficient space for Impacto resources");
        }
        ImpactoFiles.directory(store, "generations");
        File staging = ImpactoFiles.directory(store, "staging-" + UUID.randomUUID());
        try {
            File archive = ImpactoFiles.child(staging, "archive.xz");
            MessageDigest hash = ImpactoFiles.digest();
            long bytes = 0;
            try (InputStream input = source.open(); FileOutputStream out = new FileOutputStream(archive)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > manifest.archiveSize) throw new IOException("Archive exceeds declared size");
                    hash.update(buffer, 0, count); out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            if (bytes != manifest.archiveSize || !ImpactoFiles.hex(hash.digest()).equals(manifest.archiveHash)) {
                throw new IOException("Resource archive integrity mismatch");
            }
            File payload = ImpactoFiles.directory(staging, "payload");
            try (InputStream input = Files.newInputStream(archive.toPath());
                 XZInputStream xz = new XZInputStream(input, 128 * 1024);
                 ZipInputStream zip = new ZipInputStream(xz)) {
                extract(payload, manifest, zip);
            }
            Files.write(new File(payload, ".ready").toPath(), manifest.generation.getBytes(StandardCharsets.UTF_8));
            verify(payload, manifest);
            File target = ImpactoFiles.child(store, "generations/" + manifest.generation);
            // A corrupt same-generation directory is quarantined, never deleted or changed in place.
            if (target.exists()) Files.move(target.toPath(), ImpactoFiles.child(store, "corrupt-" + UUID.randomUUID()).toPath(), StandardCopyOption.ATOMIC_MOVE);
            Files.move(payload.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally { ImpactoFiles.removeTree(staging); }
    }
    private static void cleanupStages(File store) throws IOException {
        File[] children = store.listFiles();
        if (children == null) throw new IOException("Cannot list resource store");
        for (File child : children) {
            if (child.getName().matches("staging-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
                // No-follow deletion cannot enter a hostile link; no other resource roots are touched.
                ImpactoFiles.removeTree(child);
            }
        }
    }
    static void extract(File root, Manifest manifest, ZipInputStream zip) throws IOException {
        Set<String> seen = new HashSet<>(); long total = 0; int countEntries = 0;
        ZipEntry zipEntry;
        while ((zipEntry = zip.getNextEntry()) != null) {
            if (++countEntries > MAX_FILES) throw new IOException("Archive entry count exceeds quota");
            String path = zipEntry.getName();
            File target = ImpactoFiles.child(root, path);
            Entry expected = manifest.files.get(path);
            if (zipEntry.isDirectory() || expected == null || !seen.add(path)) throw new IOException("Unexpected resource entry");
            if (zipEntry.getSize() > expected.size) throw new IOException("Entry exceeds declared size");
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create resource directory");
            MessageDigest digest = ImpactoFiles.digest(); long bytes = 0;
            try (FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = zip.read(buffer)) != -1) {
                    bytes += count; total += count;
                    if (bytes > expected.size || total > manifest.totalBytes) throw new IOException("Resource quota exceeded");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (bytes != expected.size || !ImpactoFiles.hex(digest.digest()).equals(expected.hash)) throw new IOException("Resource hash mismatch");
            if (!target.setReadOnly()) throw new IOException("Cannot protect immutable resource");
            zip.closeEntry();
        }
        if (!seen.equals(manifest.files.keySet())) throw new IOException("Missing resource entries");
    }
}
