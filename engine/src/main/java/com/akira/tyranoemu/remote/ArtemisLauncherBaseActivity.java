package com.akira.tyranoemu.remote;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.view.KeyEvent;
import android.view.SurfaceHolder;

import com.core.engine.ArtemisVersion;
import com.core.engine.DoubleBackExit;
import com.core.engine.EngineSurfaceStartupController;
import com.core.engine.EnginePrefs;
import com.core.engine.LaunchContract;

public abstract class ArtemisLauncherBaseActivity extends com.ies_net.artemis.ArtemisActivity {
    private static final long EARLY_EXIT_WINDOW_MS = 3_000L;
    private static final String KEY_ARTEMIS_ENGINE_PREFIX = "artemis_engine.";
    private static final String KEY_ARTEMIS_ENGINE_SUCCESS_PREFIX = "artemis_engine_success.";
    private com.core.gl.ArtemisPerformanceOverlay graphicsOverlay;
    private EngineSurfaceStartupController startupController;
    private volatile SurfaceHolder startupSurfaceHolder;
    private boolean officialGraphics;
    private long createdAtElapsed;
    private boolean userRequestedFinish;
    private String resolvedLibraryName;
    private String resolvedLibraryPath;

    /** Called by the native bootstrap before engine startup, then reused by System.load. */
    public final synchronized String resolveArtemisEngineLibraryPath(String engineLibName) throws java.io.IOException {
        String fileName = "lib" + engineLibName + ".so";
        if (!com.core.nativeplugin.NativePluginConstants.INSTANCE.getARTEMIS_REQUIRED_LIBS().contains(fileName)) {
            throw new java.io.IOException("Invalid Artemis library name");
        }
        if (resolvedLibraryPath != null) {
            if (!engineLibName.equals(resolvedLibraryName)) throw new java.io.IOException("Artemis library already selected");
            return resolvedLibraryPath;
        }
        String original = com.core.nativeplugin.NativePluginManager.artemisLibPath(this, fileName);
        if (original == null) throw new java.io.IOException("Artemis library unavailable");
        java.io.File runtime = com.core.nativeplugin.ArtemisVorbisRepair.prepare(
                new java.io.File(original),
                new java.io.File(com.core.nativeplugin.NativePluginManager.artemisRootDir(this), "derived"));
        resolvedLibraryName = engineLibName;
        resolvedLibraryPath = runtime.getAbsolutePath();
        Log.i("YukiArtemis", "resolved engine library " + resolvedLibraryPath);
        return resolvedLibraryPath;
    }

    protected final void loadResolvedEngineLibrary(String engineLibName) {
        try {
            System.load(resolveArtemisEngineLibraryPath(engineLibName));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Artemis library preparation failed", e);
        }
    }
    /** Loads the revision-specific Artemis native library (e.g. libartemis.so). Called once from onCreate. */
    public abstract void loadEngineLibrary();

    @Override
    public java.io.File getExternalFilesDir(String type) {
        Intent intent = getIntent();
        String path = intent == null ? null : intent.getStringExtra(LaunchContract.PATH);
        if (path == null || path.isEmpty()) {
            java.io.File fallback = super.getExternalFilesDir(type);
            Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " fallback=" + (fallback == null ? "null" : fallback.getAbsolutePath()));
            return fallback;
        }
        if (path.startsWith("file://")) path = path.substring("file://".length());
        java.io.File out = new java.io.File(path);
        Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " path=" + out.getAbsolutePath() + " scoped=" + (intent != null && intent.getBooleanExtra(LaunchContract.SCOPED_SAVE_DIR, false)));
        return out;
    }

    @Override
    public final void onCreate(Bundle bundle) {
        Log.i("YukiArtemis", "onCreate enter pid=" + android.os.Process.myPid());
        Intent intent = getIntent();
        officialGraphics = intent != null && !"artemis-clean".equals(intent.getStringExtra(LaunchContract.ENGINE_LIB_NAME));
        if (officialGraphics) com.core.gl.ArtemisOfficialBridge.prepare(this);
        super.onCreate(bundle);
        if (officialGraphics) graphicsOverlay = new com.core.gl.ArtemisPerformanceOverlay(this, com.core.gl.ArtemisOfficialBridge.source());
        startupController = new EngineSurfaceStartupController(this,
                () -> { if (graphicsOverlay != null) graphicsOverlay.show(); });
        startupController.attachToWindow();
        SurfaceHolder surfaceHolder = startupSurfaceHolder;
        if (surfaceHolder != null && surfaceHolder.getSurface().isValid()) {
            startupController.onNativeSurfaceCreated(surfaceHolder);
        }
        createdAtElapsed = SystemClock.elapsedRealtime();
        Log.i("YukiArtemis", "onCreate path=" + (getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.PATH)) + " scoped=" + (getIntent() != null && getIntent().getBooleanExtra(LaunchContract.SCOPED_SAVE_DIR, false)) + " saveName=" + (getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.SCOPED_SAVE_NAME)));
        loadEngineLibrary();
    }

    @Override
    public final void onResume() {
        super.onResume();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        nativeResumeAllSound();
        if (startupController != null) startupController.onResume();
        if (graphicsOverlay != null && (startupController == null || !startupController.isLoading())) {
            graphicsOverlay.show();
        }
    }

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Override
    protected void onPause() {
        Log.i("YukiArtemis", "onPause finishing=" + isFinishing() + " pid=" + android.os.Process.myPid());
        if (startupController != null) startupController.onPause();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        nativePauseAllSound();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (DoubleBackExit.dispatchBackKey(this, event, this::exitFromBack)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        DoubleBackExit.handleBack(this, this::exitFromBack);
    }

    private void exitFromBack() {
        userRequestedFinish = true;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (startupController != null) {
            startupController.dispose();
            startupController = null;
        }
        startupSurfaceHolder = null;
        if (graphicsOverlay != null) graphicsOverlay.hide();
        DoubleBackExit.clear(this);
        // 兼容回退改为「先按动态候选链/旧 stage 拉起下一版本（独立进程），未回退时再记录成功版本」：
        // 兼容版本在各自独立进程（如 :artemis.compat / :artemis.compat.v2）启动，当前进程
        // （无论正常退出还是早退回退）都直接终结，避免引擎 native 进程级全局状态
        // （android_app/音频/GL/dlopen lib）被二次初始化污染——同进程二次 init 挂起黑屏
        // 是部分设备黑屏/闪退的根因，与 KRKR 退出即杀进程同策略。
        boolean retryStarted = maybeRetryWithCompatibleArtemis();
        if (!retryStarted) recordSuccessfulArtemisVersionIfNeeded();
        Log.i("YukiArtemis", "onDestroy before super retry=" + retryStarted + " pid=" + android.os.Process.myPid());
        super.onDestroy();
        if (officialGraphics) com.core.gl.ArtemisOfficialBridge.close();
        Log.i("YukiArtemis", "onDestroy killing pid=" + android.os.Process.myPid());
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {
            // 进程终结失败可安全忽略：系统会回收进程，下次启动仍为全新进程
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startupSurfaceHolder = holder;
        super.surfaceCreated(holder);
        if (startupController != null) startupController.onNativeSurfaceCreated(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        startupSurfaceHolder = holder;
        super.surfaceChanged(holder, format, width, height);
        if (startupController != null) startupController.onNativeSurfaceChanged(holder);
    }

    @Override
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        startupSurfaceHolder = holder;
        super.surfaceRedrawNeeded(holder);
        if (startupController != null) startupController.onNativeSurfaceRedrawNeeded(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (startupSurfaceHolder == holder) startupSurfaceHolder = null;
        super.surfaceDestroyed(holder);
        if (startupController != null) startupController.onNativeSurfaceDestroyed(holder);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (startupController != null) startupController.onWindowFocusChanged(hasFocus);
    }

    /**
     * Artemis titles target several mutually incompatible native revisions.  A bad
     * revision returns to the launcher almost immediately without a Java exception.
     * Retry only that short startup failure, and never override a user-selected
     * revision or a normal, longer-running game exit.
     *
     * @return true 表示已启动兼容回退 Activity（进程不得终结）；false 表示正常退出
     */
    private boolean maybeRetryWithCompatibleArtemis() {
        Intent source = getIntent();
        if (source == null || userRequestedFinish
                || !source.getBooleanExtra(LaunchContract.ARTEMIS_AUTO_FALLBACK, false)
                || SystemClock.elapsedRealtime() - createdAtElapsed > EARLY_EXIT_WINDOW_MS) return false;
        boolean dynamicRetried = maybeRetryWithDynamicArtemisPlan(source);
        if (dynamicRetried) return true;
        int stage = source.getIntExtra(LaunchContract.ARTEMIS_FALLBACK_STAGE, 0);
        ArtemisVersion next = ArtemisVersion.legacyNext(stage);
        String path = source.getStringExtra(LaunchContract.PATH);
        if (next == null || path == null || path.trim().isEmpty()) return false;

        Intent retry = new Intent(this, next.activityClass);
        retry.putExtras(source);
        retry.putExtra(LaunchContract.ARTEMIS_FALLBACK_STAGE, next.fallbackStage());
        retry.putExtra(LaunchContract.ENGINE_LIB_NAME, next.engineLibraryName);
        retry.putExtra(LaunchContract.ARTEMIS_CURRENT_VERSION, next.id);
        updateGraphicsRequest(retry);
        retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.w("YukiArtemis", "Artemis exited during startup; retrying with " + next.legacyId + " path=" + path);
        try {
            startActivity(retry);
        } catch (Throwable t) {
            Log.e("YukiArtemis", "Artemis compatibility retry failed", t);
            return false;
        }
        return true;
    }

    private boolean maybeRetryWithDynamicArtemisPlan(Intent source) {
        String chainText = source.getStringExtra(LaunchContract.ARTEMIS_FALLBACK_VERSIONS);
        int currentIndex = source.getIntExtra(LaunchContract.ARTEMIS_FALLBACK_INDEX, -1);
        String path = source.getStringExtra(LaunchContract.PATH);
        if (chainText == null || chainText.trim().isEmpty() || currentIndex < 0
                || path == null || path.trim().isEmpty()) return false;
        String[] rawVersions = chainText.split(",");
        java.util.ArrayList<String> versions = new java.util.ArrayList<>();
        for (String raw : rawVersions) {
            String version = normalizeArtemisVersion(raw);
            if (version != null && !versions.contains(version)) versions.add(version);
        }
        int nextIndex = currentIndex + 1;
        if (nextIndex >= versions.size()) return false;
        String nextVersion = versions.get(nextIndex);
        ArtemisVersion next = ArtemisVersion.find(nextVersion);
        if (next == null) return false;

        Intent retry = new Intent(this, next.activityClass);
        retry.putExtras(source);
        retry.putExtra(LaunchContract.ARTEMIS_FALLBACK_INDEX, nextIndex);
        retry.putExtra(LaunchContract.ARTEMIS_CURRENT_VERSION, next.id);
        retry.putExtra(LaunchContract.ENGINE_LIB_NAME, next.engineLibraryName);
        retry.putExtra(LaunchContract.ARTEMIS_FALLBACK_STAGE, next.fallbackStage());
        updateGraphicsRequest(retry);
        retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.w("YukiArtemis", "Artemis exited during startup; retrying dynamic version=" + nextVersion
                + " lib=" + next.engineLibraryName + " index=" + nextIndex + "/" + versions.size()
                + " path=" + path);
        try {
            startActivity(retry);
        } catch (Throwable t) {
            Log.e("YukiArtemis", "Artemis dynamic compatibility retry failed", t);
            return false;
        }
        return true;
    }

    /** Every candidate recomputes its gate; a rejected first candidate must not erase the user's request. */
    private static void updateGraphicsRequest(Intent retry) {
        String library = retry.getStringExtra(LaunchContract.ENGINE_LIB_NAME);
        boolean requested = retry.getBooleanExtra(LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ENABLED,
                retry.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false));
        boolean analyzer = retry.getBooleanExtra(LaunchContract.ARTEMIS_GRAPHICS_REQUESTED_ANALYZER,
                retry.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false));
        boolean enabled = com.core.gl.ArtemisLibraryIdentity.supportsEngine(library, requested);
        retry.putExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, enabled);
        retry.putExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, enabled && analyzer);
    }

    private void recordSuccessfulArtemisVersionIfNeeded() {
        Intent source = getIntent();
        if (source == null || !source.getBooleanExtra(LaunchContract.ARTEMIS_AUTO_FALLBACK, false)) return;
        long aliveMs = SystemClock.elapsedRealtime() - createdAtElapsed;
        if (!userRequestedFinish && aliveMs <= EARLY_EXIT_WINDOW_MS) return;
        String path = source.getStringExtra(LaunchContract.PATH);
        String version = normalizeArtemisVersion(source.getStringExtra(LaunchContract.ARTEMIS_CURRENT_VERSION));
        if (path == null || path.trim().isEmpty() || version == null) return;
        String keySuffix = Integer.toHexString(path.hashCode());
        getSharedPreferences(EnginePrefs.APP_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ARTEMIS_ENGINE_SUCCESS_PREFIX + keySuffix, version)
                .putString(KEY_ARTEMIS_ENGINE_PREFIX + keySuffix, version)
                .apply();
        Log.i("YukiArtemis", "Artemis recorded successful version=" + version + " aliveMs=" + aliveMs + " path=" + path);
    }

    private static String normalizeArtemisVersion(String value) {
        ArtemisVersion version = ArtemisVersion.find(value);
        return version == null ? null : version.id;
    }
}
