package com.core.siglus;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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

import com.core.engine.EnginePrefs;
import com.core.engine.R;
import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.ArtemisPerformanceOverlay;

import java.io.File;

/**
 * SiglusEngine（siglus_rs）宿主 Activity。
 *
 * <p>宿主模型与上游一致：Java 持有 SurfaceView 生命周期与 Choreographer 主循环，Rust 引擎
 * 经 {@code siglus_android_*} C ABI 被逐步驱动。差异点：游戏路径/语言/回写键取自
 * {@link LaunchContract} extras，消息框复用 engine 既有文案资源，IME 与键事件按
 * TyranorNext 契约转发。
 */
public final class SiglusActivity extends AppCompatActivity implements
        SurfaceHolder.Callback,
        Choreographer.FrameCallback,
        View.OnTouchListener,
        SiglusTextInputView.Listener,
        NativeSiglus.MessageboxListener {

    private static final String TAG = "SiglusActivity";

    /** 引擎侧取消/返回键（Windows VK Escape）。 */
    private static final int VK_ESCAPE = 0x1B;
    private static final int VK_ENTER = 0x0D;
    private static final int VK_SPACE = 0x20;
    private static final int VK_BACK = 0x08;
    private static final int VK_DELETE = 0x2E;
    private static final int VK_TAB = 0x09;
    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_ALT = 0x12;
    private static final int VK_META = 0x5B;

    private static final long BACK_EXIT_WINDOW_MS = 2000L;
    private static final int MAX_FRAME_DT_MS = 250;
    private static final long FALLBACK_RETRY_MS = 1000L;
    private static final int MAX_FALLBACK_RETRIES = 5;

    private FrameLayout rootLayout;
    private SurfaceView surfaceView;
    private SiglusTextInputView textInputView;
    private View loadingOverlay;

    private long handle = 0L;
    private boolean running = false;
    private volatile boolean surfaceReady = false;
    private long lastFrameNs = 0L;
    private boolean loadingOverlayVisible = true;
    private boolean imeVisible = false;

    private String gameRoot;
    private String gameDirName;
    private String pathHash;

    private long lastBackMs = 0L;
    private boolean resumed, destroying, faulted, proxyPreparing, proxyAttached, proxyFailed;
    private boolean fallbackPending;
    private int fallbackRetries;
    private boolean pointerActive;
    private double lastTouchX, lastTouchY;
    private final SiglusSurfaceBinding surfaceBinding = new SiglusSurfaceBinding();
    private SiglusGraphicsProxy graphicsProxy;
    private ArtemisPerformanceOverlay graphicsOverlay;
    private final int[] stageGeometry = new int[10];
    private final Runnable fallbackRetry = () -> {
        if (resumed && !destroying && graphicsProxy != null && proxyFailed)
            restoreDirect(graphicsProxy);
    };

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
            Toast.makeText(this, uiString(R.string.engine_siglus_missing_game_dir),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        gameDirName = new File(gameRoot).getName();
        pathHash = getIntent().getStringExtra(LaunchContract.SIGLUS_PATH_HASH);

        NativeSiglus.setMessageboxListener(this);

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

        textInputView = new SiglusTextInputView(this, this);
        textInputView.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(1, 1);
        inputParams.gravity = Gravity.TOP | Gravity.START;
        rootLayout.addView(textInputView, inputParams);

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
        resumed = true;
        fallbackRetries = 0; // A new foreground session permits another bounded recovery attempt.
        applyImmersive();
        if (graphicsProxy != null && proxyFailed) {
            restoreDirect(graphicsProxy);
        } else if (graphicsProxy != null && proxyAttached && surfaceView.getHolder().getSurface().isValid()) {
            cancelPointer();
            surfaceReady = false;
            graphicsProxy.attachOutput(surfaceView.getHolder().getSurface(),
                    surfaceWidth(surfaceView.getHolder()), surfaceHeight(surfaceView.getHolder()));
        }
        maybeStartFrameLoop();
    }

    @Override
    protected void onPause() {
        resumed = false;
        surfaceView.removeCallbacks(fallbackRetry);
        stopFrameLoop();
        cancelPointer();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        if (graphicsProxy != null) graphicsProxy.detachOutput();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroying = true;
        resumed = false;
        surfaceView.removeCallbacks(fallbackRetry);
        stopFrameLoop();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        hideIme();
        destroyEngine(); // Rust releases the producer before its Java/GL owner.
        if (graphicsProxy != null) { graphicsProxy.release(); graphicsProxy = null; }
        NativeSiglus.setMessageboxListener(null);
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
                if (handle != 0L) {
                    forwardKey(VK_ESCAPE, null, false);
                }
                Toast.makeText(SiglusActivity.this,
                        uiString(R.string.engine_siglus_back_again_to_exit),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- Surface ----------

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surfaceBinding.surfaceCreated();
        if (graphicsProxy != null && (proxyAttached || proxyFailed)) {
            if (proxyFailed) restoreDirect(graphicsProxy);
            else if (proxyAttached) {
                cancelPointer();
                surfaceReady = false;
                graphicsProxy.attachOutput(holder.getSurface(), surfaceWidth(holder), surfaceHeight(holder));
            }
        } else if (handle != 0L) {
            // 后台恢复：Android 换了新的 ANativeWindow，保留运行中的引擎并重挂窗口。
            Surface surface = holder.getSurface();
            int width = surfaceWidth(holder);
            int height = surfaceHeight(holder);
            surfaceReady = NativeSiglus.setSurface(handle, surface, width, height);
            if (surfaceReady) surfaceBinding.directConnected();
            if (!surfaceReady) {
                Toast.makeText(this, uiString(R.string.engine_launch_failed),
                        Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            ensureEngine(holder);
        }
        maybeStartFrameLoop();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (handle == 0L) {
            ensureEngine(holder);
        } else if (graphicsProxy != null && (proxyAttached || proxyFailed)) {
            if (proxyFailed) restoreDirect(graphicsProxy);
            else if (proxyAttached) {
                cancelPointer();
                surfaceReady = false;
                graphicsProxy.attachOutput(holder.getSurface(), Math.max(1, width), Math.max(1, height));
            }
        } else {
            NativeSiglus.resize(handle, Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceBinding.surfaceDestroyed();
        // ANativeWindow 即将失效，但引擎必须存活：切后台不重启游戏，onDestroy 才销毁。
        surfaceReady = false;
        surfaceView.removeCallbacks(fallbackRetry);
        stopFrameLoop();
        cancelPointer();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        if (graphicsProxy != null) graphicsProxy.detachOutput();
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
        NativeSiglus.nativeInitContext(getApplicationContext());

        String language = getIntent().getStringExtra(LaunchContract.SIGLUS_LANGUAGE);
        if (language != null && !language.trim().isEmpty()) {
            NativeSiglus.nativeSetLanguage(language.trim());
        }

        int width = surfaceWidth(holder);
        int height = surfaceHeight(holder);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        double scale = metrics.density;

        long created = NativeSiglus.create(holder.getSurface(), width, height, scale, gameRoot);
        if (created == 0L) {
            Toast.makeText(this, uiString(R.string.engine_siglus_init_failed),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        handle = created;
        surfaceBinding.directConnected();
        surfaceReady = true;
        NativeSiglus.setNativeMessageboxCallback(handle);
        prepareGraphics(holder);
        writeBackGameTitleAsync();
    }

    private void prepareGraphics(SurfaceHolder holder) {
        GraphicsFilterChain chain = GraphicsFilterChain.parse(
                getIntent().getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN));
        if (!getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false)
                || chain == null || !chain.hasActive()) return;
        if (!SiglusStageBridge.geometry(handle, stageGeometry)) {
            Log.w(TAG, "Optional stage geometry ABI unavailable; using direct presentation");
            return;
        }
        long engineHandle = handle;
        SiglusGraphicsProxy candidate;
        try {
            candidate = new SiglusGraphicsProxy(this, chain, stageGeometry[0], stageGeometry[1],
                getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false),
                new SiglusGraphicsProxy.Listener() {
                    @Override public void onPrepared(SiglusGraphicsProxy proxy, Surface producer) {
                        if (destroying || graphicsProxy != proxy || handle != engineHandle) return;
                        if (!NativeSiglus.setSurface(handle, producer, stageGeometry[0], stageGeometry[1])) {
                            restoreDirect(proxy);
                            return;
                        }
                        proxyAttached = true;
                        if (!SiglusStageBridge.geometry(handle, stageGeometry)
                                || !proxy.matchesGeometry(stageGeometry)) {
                            Log.e(TAG, "Producer is not a verified 1:1 stage; refusing raw-frame filtering");
                            restoreDirect(proxy);
                            return;
                        }
                        proxyPreparing = false;
                        proxy.activate();
                        surfaceReady = false;
                        SurfaceHolder current = surfaceView.getHolder();
                        if (resumed && current.getSurface().isValid())
                            proxy.attachOutput(current.getSurface(), surfaceWidth(current), surfaceHeight(current));
                        graphicsOverlay = new ArtemisPerformanceOverlay(SiglusActivity.this, proxy);
                        if (resumed) graphicsOverlay.show();
                        maybeStartFrameLoop();
                    }
                    @Override public void onPreparationTimeout(SiglusGraphicsProxy proxy) {
                        if (destroying || graphicsProxy != proxy || handle != engineHandle
                                || !proxyPreparing || proxyAttached) return;
                        // The proxy closed before this callback and never delivered a producer.
                        // Rust keeps direct output; only a current holder generation may resume.
                        graphicsProxy = null;
                        proxyPreparing = false;
                        proxyFailed = false;
                        SurfaceHolder current = surfaceView.getHolder();
                        surfaceReady = current.getSurface().isValid() && surfaceBinding.isDirectCurrent();
                        Log.w(TAG, "Graphics preparation timed out; keeping Rust direct output");
                        Toast.makeText(SiglusActivity.this, uiString(R.string.graphics_siglus_fallback),
                                Toast.LENGTH_LONG).show();
                        maybeStartFrameLoop();
                    }
                    @Override public void onOutputReady(SiglusGraphicsProxy proxy) {
                        if (destroying || graphicsProxy != proxy || handle != engineHandle || !proxyAttached) return;
                        surfaceReady = surfaceView.getHolder().getSurface().isValid();
                        if (resumed && graphicsOverlay != null) graphicsOverlay.show();
                        maybeStartFrameLoop();
                    }
                    @Override public void onFirstFrame(SiglusGraphicsProxy proxy) {
                        if (!destroying && graphicsProxy == proxy) hideLoadingOverlay();
                    }
                    @Override public void onFailure(SiglusGraphicsProxy proxy, RuntimeException failure) {
                        if (!destroying && graphicsProxy == proxy && handle == engineHandle) restoreDirect(proxy);
                    }
                });
        } catch (RuntimeException failure) {
            Log.w(TAG, "Surface proxy unavailable; keeping direct output", failure);
            return;
        }
        proxyPreparing = true;
        surfaceReady = false;
        graphicsProxy = candidate;
        candidate.prepare();
    }

    private void restoreDirect(SiglusGraphicsProxy proxy) {
        if (graphicsProxy != proxy || destroying || fallbackPending) return;
        fallbackPending = true;
        surfaceView.removeCallbacks(fallbackRetry);
        stopFrameLoop();
        cancelPointer();
        if (graphicsOverlay != null) { graphicsOverlay.hide(); graphicsOverlay = null; }
        proxyFailed = true;
        proxyPreparing = false;
        surfaceReady = false;
        // Rust must reconnect only after GLES has relinquished the BufferQueue.
        // Read the current holder in completion: it may change while GL work is queued.
        proxy.detachOutput(detached -> {
            if (graphicsProxy != proxy || destroying) return;
            fallbackPending = false;
            if (handle == 0L) return;
            if (!detached) {
                scheduleFallbackRetry();
                return;
            }
            SurfaceHolder holder = surfaceView.getHolder();
            if (!resumed || !holder.getSurface().isValid() || !surfaceBinding.restoreDirect(proxyAttached,
                    new SiglusSurfaceBinding.Connection() {
                        @Override public boolean connect() {
                            return NativeSiglus.setSurface(handle, holder.getSurface(),
                                    surfaceWidth(holder), surfaceHeight(holder));
                        }
                        @Override public void resize() {
                            NativeSiglus.resize(handle, surfaceWidth(holder), surfaceHeight(holder));
                        }
                    })) {
                // Retry on a later Surface callback, preserving the producer and game VM.
                scheduleFallbackRetry();
                return;
            }
            surfaceView.removeCallbacks(fallbackRetry);
            fallbackRetries = 0;
            graphicsProxy = null;
            proxyAttached = proxyFailed = false;
            surfaceReady = true;
            proxy.release();
            Toast.makeText(this, uiString(R.string.graphics_siglus_fallback), Toast.LENGTH_LONG).show();
            maybeStartFrameLoop();
        });
    }

    private void scheduleFallbackRetry() {
        surfaceView.removeCallbacks(fallbackRetry);
        if (!resumed || destroying || !surfaceView.getHolder().getSurface().isValid()) return;
        if (fallbackRetries >= MAX_FALLBACK_RETRIES) {
            // Keep the VM and producer alive. A late owner-thread completion or
            // a new lifecycle/Surface callback can resume without polling forever.
            if (fallbackRetries == MAX_FALLBACK_RETRIES) {
                Log.e(TAG, "Direct fallback retries exhausted; awaiting a new Surface or owner completion");
                fallbackRetries++;
            }
            return;
        }
        if (fallbackRetries == 0) Log.w(TAG, "Direct fallback pending; retrying with bounded backoff");
        surfaceView.postDelayed(fallbackRetry, FALLBACK_RETRY_MS << fallbackRetries++);
    }

    private void cancelPointer() {
        if (pointerActive && handle != 0L) NativeSiglus.touch(handle, 3, lastTouchX, lastTouchY);
        pointerActive = false;
    }

    private void destroyEngine() {
        surfaceBinding.clear();
        if (handle != 0L) {
            NativeSiglus.destroy(handle);
            handle = 0L;
        }
    }

    // ---------- 帧循环 ----------

    private void maybeStartFrameLoop() {
        if (!running && canStep()) {
            running = true;
            lastFrameNs = 0L;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private boolean canStep() {
        return resumed && !destroying && !faulted && handle != 0L && surfaceReady
                && !proxyPreparing && !proxyFailed;
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
        if (!running || !canStep()) {
            stopFrameLoop();
            return;
        }
        if (graphicsProxy != null && (graphicsProxy.hasFailed() || !SiglusStageBridge.geometry(handle, stageGeometry)
                || !graphicsProxy.matchesGeometry(stageGeometry))) {
            restoreDirect(graphicsProxy);
            return;
        }
        long beforeSerial = graphicsProxy != null ? SiglusViewport.renderSerial(stageGeometry) : 0L;
        if (graphicsProxy != null && !graphicsProxy.beginFrame(frameTimeNanos)) {
            if (graphicsProxy.hasFailed() || graphicsProxy.frameStalled(frameTimeNanos))
                restoreDirect(graphicsProxy);
            else Choreographer.getInstance().postFrameCallback(this);
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

        int status = NativeSiglus.step(handle, dtMs);
        if (graphicsProxy != null) {
            if (!SiglusStageBridge.geometry(handle, stageGeometry)) {
                restoreDirect(graphicsProxy);
                return;
            }
            graphicsProxy.completeStep(beforeSerial, SiglusViewport.renderSerial(stageGeometry));
            if (graphicsProxy.hasFailed()) {
                restoreDirect(graphicsProxy);
                return;
            }
        }
        if (status > 0) {
            finish();
            return;
        }
        if (status < 0) {
            // VM 错误不是正常退出：停帧循环保留最后一帧供 logcat 排查。
            faulted = true;
            running = false;
            return;
        }
        if (graphicsProxy == null) hideLoadingOverlay();
        updateImeState();
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
        if (!canStep()) {
            if (phase == 2 || phase == 3) cancelPointer();
            return true;
        }
        double x = event.getX(), y = event.getY();
        if (graphicsProxy != null) {
            SiglusViewport v = graphicsProxy.viewport();
            if (v == null) return true;
            x *= (double) v.surfaceWidth / Math.max(1, surfaceView.getWidth());
            y *= (double) v.surfaceHeight / Math.max(1, surfaceView.getHeight());
            if (phase == 0 && !v.contains(x, y)) { pointerActive = false; return true; }
            x = v.sourceX(x); y = v.sourceY(y);
        }
        if (phase == 0) pointerActive = true;
        else if (!pointerActive) return true;
        hideIme();
        lastTouchX = x; lastTouchY = y;
        NativeSiglus.touch(handle, phase, x, y);
        if (phase == 2 || phase == 3) pointerActive = false;
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handle != 0L && event != null) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_MULTIPLE) {
                int keyCode = event.getKeyCode();
                int vk = vkCodeFor(keyCode);
                boolean repeat = event.getRepeatCount() > 0;
                if (vk != 0) {
                    forwardKey(vk, null, repeat);
                    return true;
                }
                int unicode = event.getUnicodeChar();
                if (unicode != 0) {
                    forwardKey(0, String.valueOf((char) unicode), repeat);
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP && vkCodeFor(event.getKeyCode()) != 0) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void forwardKey(int vk, @Nullable String text, boolean repeat) {
        if (handle == 0L) {
            return;
        }
        NativeSiglus.keyEvent(handle, vk, text, repeat);
    }

    /** Android KeyEvent → Windows 虚拟键码（与引擎 host.rs::vm_key_from_platform_code 对齐）。 */
    private static int vkCodeFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
                return VK_ESCAPE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return VK_ENTER;
            case KeyEvent.KEYCODE_SPACE:
                return VK_SPACE;
            case KeyEvent.KEYCODE_DEL:
                return VK_BACK;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return VK_DELETE;
            case KeyEvent.KEYCODE_TAB:
                return VK_TAB;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return VK_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return VK_CONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return VK_ALT;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return VK_META;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0x25;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 0x26;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 0x27;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0x28;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0x24;
            case KeyEvent.KEYCODE_MOVE_END:
                return 0x23;
            default:
                break;
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 0x41 + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return 0x30 + (keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return 0x70 + (keyCode - KeyEvent.KEYCODE_F1);
        }
        return 0;
    }

    // ---------- IME ----------

    private void updateImeState() {
        if (handle == 0L) {
            return;
        }
        int[] area = new int[4];
        boolean needed = NativeSiglus.imeArea(handle, area);
        if (needed) {
            if (!imeVisible) {
                textInputView.requestFocus();
                // Mirror the working KRKR host: post the request so the view is
                // attached/focused before showing, and use flag 0 instead of
                // SHOW_IMPLICIT (which the system may silently decline).
                textInputView.post(() -> {
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(textInputView, 0);
                    }
                });
                imeVisible = true;
            }
            SiglusViewport v = graphicsProxy == null ? null : graphicsProxy.viewport();
            double x0 = area[0], y0 = area[1], x1 = (double) area[0] + area[2], y1 = (double) area[1] + area[3];
            if (v != null) {
                x0 = v.screenX(x0); y0 = v.screenY(y0); x1 = v.screenX(x1); y1 = v.screenY(y1);
            }
            double scaleX = (double) surfaceView.getWidth() / surfaceWidth(surfaceView.getHolder());
            double scaleY = (double) surfaceView.getHeight() / surfaceHeight(surfaceView.getHolder());
            textInputView.updateCursorAnchor(new Rect(
                    (int) Math.round(x0 * scaleX) + surfaceView.getLeft(),
                    (int) Math.round(y0 * scaleY) + surfaceView.getTop(),
                    (int) Math.round(x1 * scaleX) + surfaceView.getLeft(),
                    (int) Math.round(y1 * scaleY) + surfaceView.getTop()));
        } else if (imeVisible) {
            hideIme();
        }
    }

    private void hideIme() {
        if (!imeVisible) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(textInputView.getWindowToken(), 0);
        }
        textInputView.clearFocus();
        imeVisible = false;
    }

    @Override
    public void onCommitText(String text) {
        if (handle != 0L && text != null && !text.isEmpty()) {
            NativeSiglus.textInput(handle, text);
        }
    }

    @Override
    public void onPreedit(String text, int cursorStart, int cursorEnd) {
        if (handle == 0L) {
            return;
        }
        NativeSiglus.imePreedit(handle, text, cursorStart, cursorEnd);
    }

    @Override
    public void onImeKeyEvent(int keyCode, boolean down) {
        if (handle == 0L || !down) {
            return;
        }
        int vk = vkCodeFor(keyCode);
        if (vk != 0) {
            forwardKey(vk, null, false);
        }
    }

    // ---------- 消息框 ----------

    @Override
    public void onMessagebox(long engineHandle, long requestId, int kind, String title, String message) {
        runOnUiThread(() -> showMessagebox(engineHandle, requestId, kind, title, message));
    }

    private void showMessagebox(long engineHandle, long requestId, int kind, String title, String message) {
        if (isFinishing() || isDestroyed()) {
            submitMessagebox(engineHandle, requestId, fallbackMessageboxValue(kind));
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(EngineUiText.localizeCommonDialogText(this, title));
        builder.setMessage(message == null ? "" : message);
        builder.setCancelable(true);
        switch (kind) {
            case 1: // OK / CANCEL
                builder.setPositiveButton(uiString(R.string.engine_ok),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 0));
                builder.setNegativeButton(uiString(R.string.engine_cancel),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 1));
                builder.setOnCancelListener(dialog ->
                        submitMessagebox(engineHandle, requestId, 1));
                break;
            case 2: // YES / NO
                builder.setPositiveButton(uiString(R.string.engine_yes),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 0));
                builder.setNegativeButton(uiString(R.string.engine_no),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 1));
                builder.setOnCancelListener(dialog ->
                        submitMessagebox(engineHandle, requestId, 1));
                break;
            case 3: // YES / NO / CANCEL
                builder.setPositiveButton(uiString(R.string.engine_yes),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 0));
                builder.setNegativeButton(uiString(R.string.engine_no),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 1));
                builder.setNeutralButton(uiString(R.string.engine_cancel),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 2));
                builder.setOnCancelListener(dialog ->
                        submitMessagebox(engineHandle, requestId, 2));
                break;
            case 0: // OK
            default:
                builder.setPositiveButton(uiString(R.string.engine_ok),
                        (dialog, which) -> submitMessagebox(engineHandle, requestId, 0));
                builder.setOnCancelListener(dialog ->
                        submitMessagebox(engineHandle, requestId, 0));
                break;
        }
        builder.show();
    }

    private void submitMessagebox(long engineHandle, long requestId, long value) {
        if (!destroying && handle != 0L && handle == engineHandle)
            NativeSiglus.submitMessageboxResult(handle, requestId, value);
    }

    private static long fallbackMessageboxValue(int kind) {
        switch (kind) {
            case 1:
            case 2:
                return 1;
            case 3:
                return 2;
            case 0:
            default:
                return 0;
        }
    }

    // ---------- 标题回写 ----------

    /**
     * 引擎启动成功后读取 Gameexe GAMENAME 并写入共享 prefs，App 侧在下一次入库时条件导入
     * （仅覆盖仍等于目录名的自动标题）。引擎进程无法访问 Room，故走 prefs 回写协议。
     */
    private void writeBackGameTitleAsync() {
        if (pathHash == null || pathHash.trim().isEmpty() || gameRoot == null) {
            return;
        }
        final String dir = gameRoot;
        final String hash = pathHash.trim();
        final String defaultTitle = gameDirName;
        new Thread(() -> {
            try {
                String name = NativeSiglus.gameNameFromDir(dir);
                if (name == null) {
                    return;
                }
                String title = name.trim();
                if (title.isEmpty() || title.equals(defaultTitle)) {
                    return;
                }
                SharedPreferences prefs = getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE);
                prefs.edit().putString(EnginePrefs.KEY_SIGLUS_TITLE_PREFIX + hash, title).apply();
                Log.i(TAG, "siglus title written back: " + title);
            } catch (Throwable t) {
                Log.w(TAG, "title write-back failed", t);
            }
        }, "siglus-title").start();
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
