package com.core.impacto;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;

/** Main-process preparation; the SDL process only validates/claims existing installations. */
public final class ImpactoLaunchPreparation {
    private static final String ASSETS = "impacto-runtime/";
    private static final String TAG = "ImpactoLaunch";
    private ImpactoLaunchPreparation() { }

    public static final class PreparedLaunch implements AutoCloseable {
        private final File root, launchDirectory;
        private final String profileId;
        private boolean dispatched;
        PreparedLaunch(File root, File launchDirectory, String profileId) {
            this.root = root; this.launchDirectory = launchDirectory; this.profileId = profileId;
        }
        public File getLaunchDirectory() { return launchDirectory; }
        public String getProfileId() { return profileId; }
        /** Invoke only after startActivity returns successfully. */
        public void markDispatched() { dispatched = true; }
        @Override public void close() {
            if (dispatched) return;
            try (ImpactoSession guard = ImpactoSession.acquire(root)) {
                // A host which already claimed the dispatch owns its directory until it exits.
                if (!ImpactoFiles.child(launchDirectory, ".claimed").exists()) {
                    guard.clear(launchDirectory.getName());
                    ImpactoFiles.removeTree(launchDirectory);
                }
            } catch (IOException busyOrUnavailable) {
                Log.w(TAG, "Deferred abandoned launch cleanup", busyOrUnavailable);
            }
        }
    }
    public static boolean isRuntimeBundled(Context context) {
        for (String asset : new String[]{"engine-runtime/impacto.zip.xz", ASSETS + "manifest.json", ASSETS + ImpactoResourceStore.ARCHIVE}) {
            try (InputStream input = context.getAssets().open(asset)) { if (input.read() < 0) return false; }
            catch (IOException missing) { return false; }
        }
        return true;
    }
    static File privateRoot(Context context) throws IOException {
        return ImpactoFiles.directory(context.getFilesDir(), "impacto");
    }
    static ImpactoResourceStore.Manifest manifest(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(ASSETS + "manifest.json")) {
            return ImpactoResourceStore.Manifest.read(input);
        }
    }
    public static PreparedLaunch prepare(Context context, File gameDirectory, String stableRootUri, String profileId)
            throws ImpactoPreparationException {
        ImpactoProfiles.Profile profile = ImpactoProfiles.get(profileId);
        if (profile == null) throw failure(ImpactoPreparationException.Reason.INVALID_PROFILE, null);
        final File game;
        try { game = validateGame(gameDirectory, profile); }
        catch (IOException e) { throw failure(ImpactoPreparationException.Reason.GAME_LAYOUT, e); }
        final File root;
        final String identity;
        try { root = privateRoot(context); identity = ImpactoFiles.identityKey(stableRootUri); }
        catch (IOException e) { throw failure(ImpactoPreparationException.Reason.USER_DATA, e); }
        final ImpactoSession admission;
        try {
            admission = ImpactoSession.acquire(root);
        } catch (IOException e) { throw failure(ImpactoPreparationException.Reason.SESSION_BUSY, e); }
        try (ImpactoSession ignored = admission) {
            if (admission.pending(System.currentTimeMillis()) != null) throw failure(ImpactoPreparationException.Reason.SESSION_BUSY, null);
            final File resources;
            try {
                ImpactoResourceStore.Manifest manifest = manifest(context);
                File store = ImpactoFiles.directory(root, "resources");
                resources = ImpactoResourceStore.ensure(store, manifest,
                        () -> context.getAssets().open(ASSETS + ImpactoResourceStore.ARCHIVE), () -> new StatFs(store.getPath()).getAvailableBytes());
            } catch (IOException e) { throw failure(ImpactoPreparationException.Reason.RESOURCES, e); }
            final File user;
            try { user = prepareUser(root, identity, profile); }
            catch (IOException e) { throw failure(ImpactoPreparationException.Reason.USER_DATA, e); }
            File session = ImpactoFiles.directory(root, "sessions/" + UUID.randomUUID());
            try {
                Map<String, String> scripts = ImpactoLaunchConfig.scripts(game, resources, user, session, profile);
                ImpactoLaunchConfig.write(session, scripts);
                // Bind this session to all launch extras without writing a user-controlled Lua fragment.
                Files.write(ImpactoFiles.child(session, "binding").toPath(), binding(game, identity, profileId, resources));
                ImpactoLaunchConfig.verify(session, scripts);
                admission.dispatch(session.getName(), System.currentTimeMillis());
                return new PreparedLaunch(root, session, profileId);
            } catch (IOException e) {
                ImpactoFiles.removeTree(session);
                throw e;
            }
        } catch (IOException e) { throw failure(ImpactoPreparationException.Reason.PREPARATION, e); }
    }
    static File prepareUser(File root, String identity, ImpactoProfiles.Profile profile) throws IOException {
        File user = ImpactoFiles.directory(root, "users/" + identity);
        File saves = ImpactoFiles.directory(user, "saves");
        for (String file : profile.saveFields.values()) ImpactoFiles.child(saves, file);
        ImpactoFiles.child(saves, "ACHIEVEMENTS.DAT");
        File config = ImpactoFiles.child(user, "user.toml");
        if (!config.exists()) {
            // Initial defaults only. Never replace a user's TOML or fabricate proprietary save templates.
            String log = ImpactoFiles.child(user, "Impacto_Log.txt").getPath();
            String toml = "[CommonSettings]\nLoggingToFile = false\nLogFile = " + tomlString(log) + "\n";
            Files.write(config.toPath(), toml.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        if (!config.isFile() || !config.canWrite()) throw new IOException("User configuration is unavailable");
        return user;
    }
    private static String tomlString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || c == 127) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    static File validateGame(File game, ImpactoProfiles.Profile profile) throws IOException {
        if (game == null || !game.isAbsolute() || !game.isDirectory() || !game.canRead()) throw new IOException("Missing native game directory");
        File canonical = game.getCanonicalFile();
        if (!canonical.equals(game)) throw new IOException("Game directory must be canonical");
        final int[] entries = {0};
        for (ImpactoProfiles.Requirement requirement : profile.required) {
            File path = ImpactoFiles.child(canonical, requirement.path);
            if (!path.canRead() || (requirement.directory ? !path.isDirectory() : (!path.isFile() || path.length() == 0))) {
                throw new IOException("Missing game resource: " + requirement.path);
            }
            if (requirement.directory) {
                Files.walkFileTree(path.toPath(), new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                        inspect(dir, attributes); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        inspect(file, attributes); return FileVisitResult.CONTINUE;
                    }
                    private void inspect(Path path, BasicFileAttributes attributes) throws IOException {
                        if (++entries[0] > 200000 || attributes.isSymbolicLink() || (!attributes.isDirectory() && !attributes.isRegularFile())
                                || !path.toFile().getCanonicalFile().toPath().startsWith(canonical.toPath())) {
                            throw new IOException("Game directory contains an unsafe mount");
                        }
                    }
                });
            }
        }
        return canonical;
    }
    static byte[] binding(File game, String identity, String profile, File resources) {
        // Each value is hashed or fixed except paths; length prefixes make newlines unambiguous.
        String[] values = {game.getPath(), identity, profile, resources.getPath(), ImpactoProfiles.SOURCE_SHA256};
        StringBuilder value = new StringBuilder();
        for (String item : values) value.append(item.length()).append(':').append(item);
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static ImpactoPreparationException failure(ImpactoPreparationException.Reason reason, Throwable cause) {
        return new ImpactoPreparationException(reason, cause);
    }
}
