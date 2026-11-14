package com.tyranor.next.impacto;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One validated launch request from the Tyranor Next app. Pure Java (no Android types) so it can be unit tested on
 * the JVM. The launcher activity is exported, so every field is treated as untrusted input.
 */
final class ImpactoRequest {
    static final String ACTION_RUN = "com.tyranor.next.impacto.action.RUN";
    static final String EXTRA_GAME_DIR = "com.tyranor.next.impacto.extra.GAME_DIR";
    static final String EXTRA_PROFILE = "com.tyranor.next.impacto.extra.PROFILE";
    static final String EXTRA_GAME_KEY = "com.tyranor.next.impacto.extra.GAME_KEY";
    static final String EXTRA_TITLE = "com.tyranor.next.impacto.extra.TITLE";

    /** Upstream game profiles the app can detect (see the app's ImpactoGameCatalog); viewers are not games. */
    static final Set<String> PROFILES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "chlcc", "cclcc", "cclcc-switch", "mo6tw", "darling", "dash", "rne")));

    private static final Pattern GAME_KEY = Pattern.compile("[0-9a-f]{24}");
    private static final int MAX_TITLE = 200;

    final String gameDir;
    final String profile;
    final String gameKey;
    final String title;

    private ImpactoRequest(String gameDir, String profile, String gameKey, String title) {
        this.gameDir = gameDir;
        this.profile = profile;
        this.gameKey = gameKey;
        this.title = title;
    }

    /**
     * Same derivation as the app's {@code ImpactoExternalEngineModule.gameKeyFor}: the first 12 bytes of
     * SHA-256 over {@code profile NUL folder}, as lowercase hex. Binding the key to the folder means a caller cannot
     * point another game's data root at a different directory.
     */
    static String gameKeyFor(String folder, String profile) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((profile + "\0" + folder).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException when any field is missing or not acceptable. */
    static ImpactoRequest parse(String gameDir, String profile, String gameKey, String title) {
        if (profile == null || !PROFILES.contains(profile)) {
            throw new IllegalArgumentException("unknown profile");
        }
        if (gameKey == null || !GAME_KEY.matcher(gameKey).matches()) {
            throw new IllegalArgumentException("bad game key");
        }
        if (gameDir == null || gameDir.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("bad game directory");
        }
        String dir = gameDir.trim();
        while (dir.length() > 1 && dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        checkSharedStoragePath(dir);
        if (!gameKey.equals(gameKeyFor(dir, profile))) {
            throw new IllegalArgumentException("game key does not belong to this directory");
        }
        String shownTitle = title == null ? "" : title.trim();
        if (shownTitle.length() > MAX_TITLE) {
            shownTitle = shownTitle.substring(0, MAX_TITLE);
        }
        return new ImpactoRequest(dir, profile, gameKey, shownTitle);
    }

    /**
     * The game folder must be a normalized path on shared storage. Other apps' private areas ({@code Android/data},
     * {@code Android/obb}) and system paths are refused, because this app holds all-files access and must not be
     * steered into them by a caller.
     */
    private static void checkSharedStoragePath(String dir) {
        if (!(dir.startsWith("/storage/") || dir.startsWith("/sdcard/"))) {
            throw new IllegalArgumentException("game directory must be on shared storage");
        }
        String[] segments = dir.split("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException("game directory must be normalized");
            }
            if (segment.equalsIgnoreCase("Android") && i + 1 < segments.length
                    && (segments[i + 1].equalsIgnoreCase("data") || segments[i + 1].equalsIgnoreCase("obb"))) {
                throw new IllegalArgumentException("game directory must not be an app-private area");
            }
        }
    }

    /** Per-game root handed to the engine as its "chosen directory": gamedata/, patches/, saves/ live under it. */
    static File dataRoot(File externalFilesDir, String gameKey) {
        return new File(new File(externalFilesDir, "games"), gameKey);
    }

    /**
     * Creates {@code <root>/gamedata/<profile>} as a symbolic link to the game folder, so the unmodified engine finds
     * {@code RootGamedataDir/<profile>/<archive>} without copying game data. An existing link is repointed; anything
     * else at that path (a real directory or file) is refused rather than deleted.
     */
    File prepareDataRoot(File externalFilesDir) throws IOException {
        return linkGamedata(dataRoot(externalFilesDir, gameKey), profile, Paths.get(gameDir));
    }

    /** Link creation without the Android path rules, so JVM tests can exercise it with native paths. */
    static File linkGamedata(File root, String profile, Path target) throws IOException {
        Path gamedata = root.toPath().resolve("gamedata");
        Files.createDirectories(gamedata);
        Path link = gamedata.resolve(profile);
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).equals(target)) {
                return root;
            }
            Files.delete(link);
        } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing to replace a non-link at " + link);
        }
        Files.createSymbolicLink(link, target);
        return root;
    }
}
