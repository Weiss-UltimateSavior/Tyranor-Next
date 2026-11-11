package com.core.impacto;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;
import android.widget.TextView;

import com.core.engine.LaunchContract;
import com.core.engine.EngineUiText;
import com.core.engine.R;
import com.core.nativeplugin.NativeRuntimePack;

import org.libsdl.impacto.SDLActivity;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** Private SDL 3.4.10 host. Native failure can terminate only the dedicated :impacto process. */
public final class ImpactoActivity extends SDLActivity {
    private static final String TAG = "ImpactoActivity";
    private ImpactoSession activeSession;
    private String library;
    private String[] arguments;

    private volatile boolean destroyed;
    private Throwable startupFailure;
    private String preparedLibrary;

    @Override protected void onSDLCreate() {
        // Activity.onCreate has already run. SDL lifecycle callbacks must stay inert until the
        // background checks finish; a large loose-file game tree must never block Android's UI.
        mBrokenLibraries = true;
        TextView loading = new TextView(this);
        loading.setText(EngineUiText.get(this, R.string.engine_preparing_game));
        loading.setGravity(android.view.Gravity.CENTER);
        setContentView(loading);
        final Intent launchIntent = getIntent() == null ? null : new Intent(getIntent());
        new Thread(() -> {
            ValidatedLaunch ready = null;
            Throwable failure = null;
            try { ready = validateLaunch(launchIntent); }
            catch (Exception e) { failure = e; }
            final ValidatedLaunch validated = ready;
            final Throwable validationFailure = failure;
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) {
                    if (validated != null) validated.close();
                    return;
                }
                startupFailure = validationFailure;
                if (validated != null) {
                    activeSession = validated.session;
                    preparedLibrary = validated.library;
                    arguments = validated.arguments;
                }
                // Exactly one continuation. Even failures use SDL's localized broken-library
                // branch, after Android's mandatory superclass lifecycle has run.
                startSDL();
                if (mBrokenLibraries) releaseSession();
            });
        }, "ImpactoLaunchValidation").start();
    }
    private static final class ValidatedLaunch {
        final ImpactoSession session;
        final String library;
        final String[] arguments;
        ValidatedLaunch(ImpactoSession session, String library, String[] arguments) {
            this.session = session; this.library = library; this.arguments = arguments;
        }
        void close() {
            try { session.close(); } catch (IOException failure) { Log.w(TAG, "Unable to release cancelled launch", failure); }
        }
    }
    private ValidatedLaunch validateLaunch(Intent intent) throws IOException {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT < 28 || !android.os.Process.is64Bit() || manager == null
                || manager.getDeviceConfigurationInfo().reqGlEsVersion < 0x00030002) {
            throw new IOException("Unsupported Impacto device");
        }
        if (intent == null) throw new IOException("Missing launch intent");
        String profileId = intent.getStringExtra(LaunchContract.IMPACTO_PROFILE);
        ImpactoProfiles.Profile profile = ImpactoProfiles.get(profileId);
        if (profile == null) throw new IOException("Unknown profile");
        String gamePath = intent.getStringExtra(LaunchContract.PATH);
        String sessionPath = intent.getStringExtra(LaunchContract.IMPACTO_LAUNCH_DIR);
        if (gamePath == null || sessionPath == null) throw new IOException("Missing launch paths");
        File root = ImpactoLaunchPreparation.privateRoot(this);
        File requestedSession = new File(sessionPath);
        if (!requestedSession.isAbsolute() || !requestedSession.getName().matches("[a-f0-9-]{36}")) {
            throw new IOException("Invalid launch session");
        }
        File session = ImpactoFiles.child(root, "sessions/" + requestedSession.getName());
        if (!requestedSession.equals(session) || !session.isDirectory()) throw new IOException("Launch session escaped private root");
        String identity = ImpactoFiles.identityKey(intent.getStringExtra(LaunchContract.ROOT_URI));
        File user = ImpactoFiles.child(root, "users/" + identity);
        File userConfig = ImpactoFiles.child(user, "user.toml");
        if (!userConfig.isFile() || !userConfig.canWrite()) throw new IOException("Missing persistent user configuration");
        File saves = ImpactoFiles.child(user, "saves");
        if (!saves.isDirectory() || !saves.canWrite()) throw new IOException("Missing persistent save directory");
        for (String path : profile.saveFields.values()) ImpactoFiles.child(saves, path);
        ImpactoFiles.child(saves, "ACHIEVEMENTS.DAT");
        ImpactoSession guard = ImpactoSession.acquire(root);
        try {
            // Claim before expensive verification so a valid dispatch does not expire while
            // checking a large game. The retained OS lease excludes every other launch.
            guard.claim(session, System.currentTimeMillis());
            File game = ImpactoLaunchPreparation.validateGame(new File(gamePath), profile);
            // No host extraction. Full hashes are safe here on the background thread, including
            // restored Recents intents which have not passed through the launcher this time.
            File resources = ImpactoResourceStore.current(ImpactoFiles.child(root, "resources"), ImpactoLaunchPreparation.manifest(this));
            byte[] expected = ImpactoLaunchPreparation.binding(game, identity, profileId, resources);
            File binding = ImpactoFiles.child(session, "binding");
            if (!binding.isFile() || binding.length() != expected.length || !Arrays.equals(Files.readAllBytes(binding.toPath()), expected)) {
                throw new IOException("Launch identity changed");
            }
            ImpactoLaunchConfig.verify(session, ImpactoLaunchConfig.scripts(game, resources, user, session, profile));
            String path = NativeRuntimePack.libraryPath(this, NativeRuntimePack.PACK_IMPACTO, "libimpacto.so");
            if (path == null) throw new IOException("Missing or stale Impacto library");
            return new ValidatedLaunch(guard, path, ImpactoLaunchConfig.arguments(profile, session, user, resources));
        } catch (IOException | RuntimeException failure) {
            guard.close();
            throw failure;
        }
    }
    @Override public void loadLibraries() {
        try {
            if (startupFailure != null || preparedLibrary == null || arguments == null) {
                throw new IllegalStateException("Impacto launch validation failed", startupFailure);
            }
            // The artifact statically contains its private SDL; never load shared SDL2/SDL3.
            System.load(preparedLibrary);
            library = preparedLibrary;
        } catch (Exception | UnsatisfiedLinkError failure) {
            releaseSession();
            library = null;
            arguments = null;
            Log.e(TAG, "Impacto launch validation or library loading failed", failure);
            throw new IllegalStateException(EngineUiText.get(this, R.string.engine_impacto_init_failed), failure);
        }
    }
    @Override protected String getMainSharedObject() {
        if (library == null) throw new IllegalStateException("Impacto library is not loaded");
        return library;
    }
    @Override protected String[] getArguments() {
        if (arguments == null) throw new IllegalStateException("Impacto launch arguments are unavailable");
        return arguments.clone();
    }
    @Override protected String getBrokenLibrariesMessage(String detail) { return EngineUiText.get(this, R.string.engine_impacto_init_failed); }
    @Override protected String getBrokenLibrariesTitle() { return EngineUiText.get(this, R.string.engine_launch_failed); }
    @Override protected String getBrokenLibrariesExitLabel() { return EngineUiText.get(this, R.string.engine_ok); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleInstance must not replace argv or reset SDL while an existing native writer lives.
        Toast.makeText(this, EngineUiText.get(this, R.string.engine_another_game_running), Toast.LENGTH_LONG).show();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        // Capture before SDLMain can clear its static reference. The private SDL superclass
        // preserves pending quit events while this thread is alive and always calls Activity.
        Thread nativeThread = mSDLThread;
        try { super.onDestroy(); } finally {
            Runnable shutdown = () -> ImpactoShutdownPolicy.finish(library != null, nativeThread,
                    ImpactoShutdownPolicy.BACKGROUND_GRACE_MS, new ImpactoShutdownPolicy.Actions() {
                        @Override public void releaseUnstartedSession() {
                            releaseSession();
                        }
                        @Override public void terminateProcess(boolean forced) {
                            if (forced) Log.w(TAG, "Impacto ignored shutdown grace; forcing isolated process exit. Pending saves may be incomplete.");
                            // Do not close the lease first: no second writer may enter during exit.
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    });
            if (!ImpactoShutdownPolicy.mayQuitNative(nativeThread)) {
                new Thread(shutdown, "ImpactoSessionReaper").start();
            } else {
                shutdown.run();
            }
        }
    }
    private synchronized void releaseSession() {
        if (activeSession == null) return;
        try { activeSession.close(); } catch (IOException failure) { Log.w(TAG, "Unable to close Impacto session lock", failure); }
        activeSession = null;
    }
}
