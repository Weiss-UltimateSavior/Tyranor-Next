package com.core.fvp;

import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;
import com.core.engine.R;

import java.io.File;

/**
 * FVPEngine（rfvp）宿主 Activity。
 *
 * <p>宿主模型与上游一致：Java 持有 SurfaceView 生命周期与 Choreographer 主循环，Rust 引擎
 * 经 {@code rfvp_android_*} C ABI 被逐步驱动。差异点：游戏路径/编码/字体设置取自
 * {@link LaunchContract} extras；引擎进程隔离在 {@code :fvp}。
 */
public final class FvpActivity extends AppCompatActivity implements
        SurfaceHolder.Callback,
        Choreographer.FrameCallback,
        View.OnTouchListener {

    private static final String TAG = "FvpActivity";

    /** 引擎侧取消/返回键（Windows VK Escape）。 */
    private static final int VK_ESCAPE = 0x1B;

    private static final long BACK_EXIT_WINDOW_MS = 2000L;
    private static final int MAX_FRAME_DT_MS = 250;

    private FrameLayout rootLayout;
    private SurfaceView surfaceView;
    private View loadingOverlay;

    private long handle = 0L;
    private boolean running = false;
    private boolean loadingOverlayVisible = true;
    private long lastFrameNs = 0L;

    private String gameRoot;
    private String nls;

    private long lastBackMs = 0L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(buildContentView());

        gameRoot = firstNonEmpty(
                getIntent().getStringExtra(LaunchContract.PATH),
                getIntent().getStringExtra(LaunchContract.GAME_PATH),
                getIntent().getStringExtra(LaunchContract.PROJECT_ROOT),
                getIntent().getStringExtra(LaunchContract.GAME_DIR));
        if (gameRoot == null || gameRoot.trim().isEmpty()) {
            Toast.makeText(this, uiString(R.string.engine_fvp_missing_game_dir),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        nls = getIntent().getStringExtra(LaunchContract.FVP_NLS);

        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setKeepScreenOn(true);

        applyImmersive();
        installBackHandling();
    }

    private View buildContentView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loadingOverlay = buildLoadingOverlay();
        rootLayout.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return rootLayout;
    }

    private View buildLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.BLACK);
        ProgressBar spinner = new ProgressBar(this);
        overlay.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView label = new TextView(this);
        label.setText(uiString(R.string.engine_starting_game));
        label.setTextColor(Color.LTGRAY);
        label.setTextSize(14.0f);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        labelParams.topMargin = (int) (72 * getResources().getDisplayMetrics().density);
        overlay.addView(label, labelParams);
        return overlay;
    }

    private void hideLoadingOverlay() {
        if (loadingOverlayVisible && loadingOverlay != null) {
            loadingOverlayVisible = false;
            rootLayout.removeView(loadingOverlay);
            loadingOverlay = null;
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // singleInstance：游戏运行中再次收到启动请求时不叠加新引擎实例。
        Toast.makeText(this, uiString(R.string.engine_another_game_running),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        maybeStartFrameLoop();
    }

    @Override
    protected void onPause() {
        stopFrameLoop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopFrameLoop();
        destroyEngine();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    private void applyImmersive() {
        WindowInsetsControllerCompat controller =
                ViewCompat.getWindowInsetsController(getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void installBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                long now = SystemClock.uptimeMillis();
                if (lastBackMs != 0L && now - lastBackMs <= BACK_EXIT_WINDOW_MS) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    return;
                }
                lastBackMs = now;
                forwardKey(VK_ESCAPE, 0);
                forwardKey(VK_ESCAPE, 1);
                Toast.makeText(FvpActivity.this,
                        uiString(R.string.engine_fvp_back_again_to_exit),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- Surface ----------

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        ensureEngine(holder);
        maybeStartFrameLoop();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (handle == 0L) {
            ensureEngine(holder);
        } else {
            NativeRfvp.resize(handle, Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // 一期沿用上游语义：Surface 失效即结束本局（P2 计划改为 setSurface 保活）。
        stopFrameLoop();
        destroyEngine();
        finish();
    }

    private int surfaceWidth(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        int width = frame != null ? frame.width() : surfaceView.getWidth();
        return Math.max(1, width);
    }

    private int surfaceHeight(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        int height = frame != null ? frame.height() : surfaceView.getHeight();
        return Math.max(1, height);
    }

    private void ensureEngine(@NonNull SurfaceHolder holder) {
        if (handle != 0L) {
            return;
        }
        // 必须先初始化 ndk-context，音频后端（cpal/AAudio）依赖它。
        NativeRfvp.nativeInitAndroidContext(getApplicationContext());

        int width = surfaceWidth(holder);
        int height = surfaceHeight(holder);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        double scale = metrics.density;

        long created = NativeRfvp.create(holder.getSurface(), width, height, scale, gameRoot, nls);
        if (created == 0L) {
            Toast.makeText(this, uiString(R.string.engine_fvp_init_failed),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        handle = created;

        boolean textHidpi = getIntent().getBooleanExtra(LaunchContract.FVP_TEXT_HIDPI, true);
        boolean systemFont = getIntent().getBooleanExtra(LaunchContract.FVP_SYSTEM_FONT, true);
        NativeRfvp.setTextHidpi(handle, textHidpi);
        NativeRfvp.setSystemFont(handle, systemFont);
        Log.i(TAG, "engine created: " + width + "x" + height + " nls=" + nls
                + " hidpi=" + textHidpi + " systemFont=" + systemFont);
    }

    private void destroyEngine() {
        if (handle != 0L) {
            NativeRfvp.destroy(handle);
            handle = 0L;
        }
    }

    // ---------- 帧循环 ----------

    private void maybeStartFrameLoop() {
        if (!running && handle != 0L) {
            running = true;
            lastFrameNs = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void stopFrameLoop() {
        if (running) {
            running = false;
            lastFrameNs = 0L;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running || handle == 0L) {
            return;
        }
        if (lastFrameNs == 0L) {
            lastFrameNs = frameTimeNanos;
        }
        long dtNs = frameTimeNanos - lastFrameNs;
        lastFrameNs = frameTimeNanos;
        int dtMs = (int) (dtNs / 1_000_000L);
        if (dtMs < 0) {
            dtMs = 0;
        }
        if (dtMs > MAX_FRAME_DT_MS) {
            dtMs = MAX_FRAME_DT_MS;
        }

        int status = NativeRfvp.step(handle, dtMs);
        if (status != 0) {
            finish();
            return;
        }
        hideLoadingOverlay();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------- 输入 ----------

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (handle == 0L || event == null) {
            return false;
        }
        int phase;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                phase = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                phase = 1;
                break;
            case MotionEvent.ACTION_UP:
                phase = 2;
                break;
            case MotionEvent.ACTION_CANCEL:
                phase = 3;
                break;
            default:
                return false;
        }
        NativeRfvp.touch(handle, phase, event.getX(), event.getY());
        return true;
    }

    private void forwardKey(int vk, int phase) {
        if (handle == 0L) {
            return;
        }
        NativeRfvp.keyEvent(handle, vk, phase);
    }

    // ---------- 工具 ----------

    private String uiString(int resourceId) {
        return EngineUiText.get(this, resourceId);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
