package com.akira.tyranoemu.remote;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.content.Intent;
import android.view.KeyEvent;

import com.core.engine.ArtemisVersion;
import com.core.engine.DoubleBackExit;
import com.core.engine.EnginePrefs;
import com.core.engine.LaunchContract;

public abstract class ArtemisLauncherBaseActivity extends com.ies_net.artemis.ArtemisActivity {
    private static final long EARLY_EXIT_WINDOW_MS = 3_000L;
    private static final String KEY_ARTEMIS_ENGINE_PREFIX = "artemis_engine.";
    private static final String KEY_ARTEMIS_ENGINE_SUCCESS_PREFIX = "artemis_engine_success.";
    private com.core.gl.ArtemisPerformanceOverlay graphicsOverlay;
    private boolean officialGraphics;
    private long createdAtElapsed;
    private boolean userRequestedFinish;
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
        createdAtElapsed = SystemClock.elapsedRealtime();
        Log.i("YukiArtemis", "onCreate path=" + (getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.PATH)) + " scoped=" + (getIntent() != null && getIntent().getBooleanExtra(LaunchContract.SCOPED_SAVE_DIR, false)) + " saveName=" + (getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.SCOPED_SAVE_NAME)));
        loadEngineLibrary();
    }

    @Override
    public final void onResume() {
        super.onResume();
        Intent intent = getIntent();
        setRequestedOrientation(intent == null ? 6 : intent.getIntExtra(LaunchContract.ORIENTATION, 6));
        nativeResumeAllSound();
        if (graphicsOverlay != null) graphicsOverlay.show();
    }

    @Override
    protected void onPause() {
        Log.i("YukiArtemis", "onPause finishing=" + isFinishing() + " pid=" + android.os.Process.myPid());
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
