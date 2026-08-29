package com.akira.tyranoemu.remote;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.content.Intent;
import android.view.KeyEvent;

import com.core.engine.DoubleBackExit;
import com.core.engine.EnginePrefs;

public abstract class ArtemisLauncherBaseActivity extends com.ies_net.artemis.ArtemisActivity {
    private static final long EARLY_EXIT_WINDOW_MS = 3_000L;
    private static final int FALLBACK_STAGE_V4_DIRECT = -1;
    private static final String KEY_ARTEMIS_ENGINE_PREFIX = "artemis_engine.";
    private long createdAtElapsed;
    private boolean userRequestedFinish;
    /** Loads the revision-specific Artemis native library (e.g. libartemis.so). Called once from onCreate. */
    public abstract void loadEngineLibrary();

    @Override
    public java.io.File getExternalFilesDir(String type) {
        String path = getIntent() == null ? null : getIntent().getStringExtra("path");
        if (path == null || path.isEmpty()) {
            java.io.File fallback = super.getExternalFilesDir(type);
            Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " fallback=" + (fallback == null ? "null" : fallback.getAbsolutePath()));
            return fallback;
        }
        if (path.startsWith("file://")) path = path.substring("file://".length());
        java.io.File out = new java.io.File(path);
        Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " path=" + out.getAbsolutePath() + " scoped=" + getIntent().getBooleanExtra("scopedSaveDir", false));
        return out;
    }

    @Override
    public final void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        createdAtElapsed = SystemClock.elapsedRealtime();
        Log.i("YukiArtemis", "onCreate path=" + (getIntent() == null ? null : getIntent().getStringExtra("path")) + " scoped=" + (getIntent() != null && getIntent().getBooleanExtra("scopedSaveDir", false)) + " saveName=" + (getIntent() == null ? null : getIntent().getStringExtra("scopedSaveName")));
        loadEngineLibrary();
    }

    @Override
    public final void onResume() {
        super.onResume();
        setRequestedOrientation(getIntent().getIntExtra("orientation", 6));
        nativeResumeAllSound();
    }

    @Override
    protected void onPause() {
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
        DoubleBackExit.clear(this);
        // 兼容回退改为「先写 pref + 拉起下一版本（独立进程），再终结当前进程」：
        // V2/V3 在各自独立进程（:artemis.compat / :artemis.compat.v2）启动，当前进程
        // （无论正常退出还是早退回退）都直接终结，避免引擎 native 进程级全局状态
        // （android_app/音频/GL/dlopen lib）被二次初始化污染——同进程二次 init 挂起黑屏
        // 是部分设备黑屏/闪退的根因，与 KRKR 退出即杀进程同策略。
        maybeRetryWithCompatibleArtemis();
        super.onDestroy();
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
                || !source.getBooleanExtra("artemisAutoFallback", false)
                || SystemClock.elapsedRealtime() - createdAtElapsed > EARLY_EXIT_WINDOW_MS) return false;
        int stage = source.getIntExtra("artemisFallbackStage", 0);
        String nextPackage = stage == FALLBACK_STAGE_V4_DIRECT ? "internal.artemis"
                : stage == 0 ? "internal.artemis.compat"
                : stage == 1 ? "internal.artemis.compat.v2"
                : stage == 2 ? "internal.artemis.v4"
                : null;
        String path = source.getStringExtra("path");
        if (nextPackage == null || path == null || path.trim().isEmpty()) return false;

        getSharedPreferences(EnginePrefs.APP_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ARTEMIS_ENGINE_PREFIX + Integer.toHexString(path.hashCode()), nextPackage)
                .apply();
        Intent retry = new Intent(this,
                stage == FALLBACK_STAGE_V4_DIRECT ? com.akira.tyranoemu.remote.ArtemisActivityV1.class
                        : stage == 0 ? com.akira.tyranoemu.remote.ArtemisActivityV2.class
                        : stage == 1 ? com.akira.tyranoemu.remote.ArtemisActivityV3.class
                        : com.akira.tyranoemu.remote.ArtemisActivityV4.class);
        retry.putExtras(source);
        retry.putExtra("artemisFallbackStage", stage == FALLBACK_STAGE_V4_DIRECT ? 0 : stage + 1);
        // retry 到下一 revision 时，bootstrap loader 需加载对应的插件库名。
        retry.putExtra("engineLibName",
                stage == FALLBACK_STAGE_V4_DIRECT ? "artemis"
                        : stage == 0 ? "artemis-compatible"
                        : stage == 1 ? "artemis-compatible-v2"
                        : "artemis-v4");
        retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.w("YukiArtemis", "Artemis exited during startup; retrying with " + nextPackage + " path=" + path);
        try {
            startActivity(retry);
        } catch (Throwable t) {
            Log.e("YukiArtemis", "Artemis compatibility retry failed", t);
            return false;
        }
        return true;
    }
}
