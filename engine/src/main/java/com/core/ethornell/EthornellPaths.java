package com.core.ethornell;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Single identity-to-user-root contract shared by launcher, host and device harness. */
public final class EthornellPaths {
    private EthornellPaths() {}

    public static File userDataDir(File filesDir, String gameIdentity) throws IOException {
        if (gameIdentity == null || gameIdentity.trim().isEmpty() || gameIdentity.length() > 8192
                || gameIdentity.indexOf('\0') >= 0) throw new IOException("Invalid game identity");
        // CharsetEncoder defaults must not silently replace malformed identity surrogates.
        for (int i = 0; i < gameIdentity.length(); i++) {
            char c = gameIdentity.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= gameIdentity.length() || !Character.isLowSurrogate(gameIdentity.charAt(i)))
                    throw new IOException("Invalid game identity UTF-16");
            } else if (Character.isLowSurrogate(c)) throw new IOException("Invalid game identity UTF-16");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(gameIdentity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b & 255) >>> 4, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return new File(new File(new File(filesDir.getCanonicalFile(), "ethornell"), "userdata"), hex.toString());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Exact spelling and canonical destination must both match; no aliases or symlink redirection. */
    public static File validateUserDataDir(File filesDir, String gameIdentity, String suppliedPath) throws IOException {
        if (suppliedPath == null || suppliedPath.indexOf('\0') >= 0 || suppliedPath.length() > 8192)
            throw new IOException("Invalid user path");
        File expected = userDataDir(filesDir, gameIdentity);
        File supplied = new File(suppliedPath);
        if (!supplied.isAbsolute() || !supplied.getAbsolutePath().equals(expected.getAbsolutePath())
                || !supplied.getCanonicalFile().equals(expected)) throw new IOException("Identity/user-root mismatch");
        return expected;
    }
}
