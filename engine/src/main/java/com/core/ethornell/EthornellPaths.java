package com.core.ethornell;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Ethornell save-path contracts for legacy dispatch roots and game-directory saves. */
public final class EthornellPaths {
    private EthornellPaths() {}

    /** Legacy app-private root retained for identity-bound dispatch metadata and older callers. */
    public static File userDataDir(File filesDir, String gameIdentity) throws IOException {
        String identityHash = identitySha256(gameIdentity);
        return new File(new File(new File(filesDir.getCanonicalFile(), "ethornell"), "userdata"), identityHash);
    }

    /** Stable UTF-8 SHA-256 identity retained by dispatch and lifecycle evidence. */
    public static String identitySha256(String gameIdentity) throws IOException {
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
            return hex.toString();
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

    /** The game-owned save root: {@code <canonical gameRoot>/savedata}. */
    public static File gameUserDataDir(File gameRoot) throws IOException {
        if (gameRoot == null) throw new IOException("Invalid game root");
        File canonicalRoot = gameRoot.getCanonicalFile();
        if (!canonicalRoot.exists() || !canonicalRoot.isDirectory())
            throw new IOException("Game root must be an existing directory");
        return new File(canonicalRoot, "savedata");
    }

    /**
     * Validates the exact canonical spelling of the game-owned savedata path.
     * Canonical comparison rejects a savedata symlink and any path alias.
     */
    public static File validateGameUserDataDir(File gameRoot, String suppliedPath) throws IOException {
        if (suppliedPath == null || suppliedPath.isEmpty()
                || suppliedPath.indexOf('\0') >= 0 || suppliedPath.length() > 8192)
            throw new IOException("Invalid game user path");
        File expected = gameUserDataDir(gameRoot);
        File supplied = new File(suppliedPath);
        if (!supplied.isAbsolute() || !supplied.getAbsolutePath().equals(expected.getAbsolutePath())
                || !supplied.getCanonicalFile().equals(expected))
            throw new IOException("Game user-root mismatch");
        return expected;
    }
}
