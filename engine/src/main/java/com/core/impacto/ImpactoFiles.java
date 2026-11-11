package com.core.impacto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small, Android-independent filesystem primitives shared by preparation and validation. */
final class ImpactoFiles {
    private ImpactoFiles() { }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    static String identityKey(String identity) throws IOException {
        if (identity == null || identity.trim().isEmpty() || identity.length() > 16384
                || identity.indexOf('\0') >= 0) throw new IOException("Invalid game identity");
        return hex(digest().digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    static File child(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.startsWith("/")
                || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0
                || relative.indexOf('\0') >= 0) throw new IOException("Invalid relative path");
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Invalid path component");
        }
        File canonicalRoot = root.getCanonicalFile();
        File target = new File(canonicalRoot, relative);
        // Reject even contained symlinks: immutable installations and user roots must not alias.
        File cursor = target;
        while (!cursor.equals(canonicalRoot)) {
            if (Files.isSymbolicLink(cursor.toPath())) throw new IOException("Symbolic link rejected");
            cursor = cursor.getParentFile();
            if (cursor == null) throw new IOException("Path escaped root");
        }
        File canonical = target.getCanonicalFile();
        if (!canonical.toPath().startsWith(canonicalRoot.toPath()) || canonical.equals(canonicalRoot)) {
            throw new IOException("Path escaped root");
        }
        return canonical;
    }
    static File directory(File parent, String relative) throws IOException {
        File result = child(parent, relative);
        if (!result.isDirectory() && !result.mkdirs()) throw new IOException("Cannot create directory");
        return child(parent, relative);
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - bytes.size()) throw new IOException("Input exceeds quota");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    static String hash(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    static void removeTree(File root) throws IOException {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] children = root.listFiles();
            if (children == null) throw new IOException("Cannot list private staging directory");
            for (File child : children) removeTree(child);
        }
        Files.delete(root.toPath());
    }
}
