package com.core.games;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

import com.core.engine.EnginePlayerOrientation;
import com.core.engine.EngineUiText;
import com.core.engine.EngineStartupOverlay;
import com.core.engine.LaunchContract;
import com.core.engine.R;
import com.core.gl.ArtemisPerformanceOverlay;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.GraphicsFilterGate;
import com.core.gl.TextureDisplayWindow;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.tvp.kirikiri2.KrDialogStyle;

/**
 * 软件渲染引擎（RealLive / AVG32 / UK2）宿主 Activity。
 *
 * <p>引擎经 {@code game_fb_*} C ABI 被逐步驱动：每帧 {@code fbStep} 后把 RGBA 帧拷进
 * {@link Bitmap}（{@link NativeGames#fbCopyFrame}），由 {@link FramebufferView} 按
 * aspect-fit 绘制；触摸映射为鼠标（单指=左键、双指点按=右键、双指上下滑=滚轮），
 * 硬件按键映射见 {@link #mapKey(int)}，软键盘提交走 {@code game_fb_text}。
 * 进程隔离在 {@code :fbgames}。
 *
 * <p>启动请求携带有效滤镜链时走滤镜路径：帧拷进复用直接缓冲后交给
 * {@link FramebufferGraphicsProxy} 在独立 GLES 线程上传纹理、执行共用滤镜链并经
 * {@link TextureView} 上屏；滤镜路径任何初始化或运行失败都回退经典 Bitmap 路径。
 */
public final class FramebufferGameActivity extends AppCompatActivity
        implements Choreographer.FrameCallback, FramebufferTextInputView.Listener,
        TextureView.SurfaceTextureListener {

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        EnginePlayerOrientation.applyWithSuper(this, super::setRequestedOrientation);
    }

    private static final String TAG = "FramebufferGame";

    private static final long BACK_EXIT_WINDOW_MS = 2000L;
    private static final int MAX_FRAME_DT_MS = 250;
    private static final long LEFT_PRESS_DELAY_MS = 90L;
    private static final float WHEEL_STEP_PX = 48f;
    private static final long GRAPHICS_WATCHDOG_MS = 10000L;
    /** 滤镜模式启动（引擎至少产出一帧）的最终期限。 */
    private static final long FILTER_STARTUP_DEADLINE_MS = 60000L;
    private static final long OUTPUT_REATTACH_INTERVAL_NS = 1_000_000_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout rootLayout;
    private FramebufferView fbView;
    private EngineStartupOverlay loadingOverlay;
    private FramebufferTextInputView textInputView;
    private TextView imeButton;
    private TextView skipButton;

    private long handle = 0L;
    private boolean running = false;
    private boolean resumed = false;
    private boolean skipping = false;
    private boolean imeVisible = false;
    private long lastFrameNs = 0L;
    private long lastBackMs = 0L;

    // 滤镜路径状态：filterMode 是启动期一次性决定，filterActive 可因失败回退翻转为 false。
    private boolean filterMode = false;
    private boolean filterActive = false;
    // 看门狗在引擎真正开始产帧（首帧提交）后才武装：加载期不计时，慢启动不降级。
    private boolean graphicsFramesSubmitted = false;
    private long lastOutputReattachNs = 0L;
    /** 看门狗计时基线（最近一次成功呈现，或本轮武装时间）。 */
    private long watchdogBaselineMs = 0L;
    @Nullable
    private FramebufferGraphicsProxy graphicsProxy;
    @Nullable
    private TextureDisplayWindow displayWindow;
    @Nullable
    private ArtemisPerformanceOverlay graphicsOverlay;
    @Nullable
    private TextureView filterView;
    @Nullable
    private SurfaceTexture displayTexture;

    private String gameRoot;
    private String engineId;
    private String nls;

    @Nullable
    private Bitmap bitmap;
    @Nullable
    private ByteBuffer buffer;
    private int frameW = 0;
    private int frameH = 0;
    private boolean frameAllocFailed = false;

    // 触摸手势状态
    private boolean multiTouch = false;
    private boolean leftPressed = false;
    private boolean leftPressPending = false;
    private float wheelAnchorY = 0f;
    private float wheelAccum = 0f;
    private boolean wheelUsed = false;
    // 视口就绪前的按下位置（滤镜路径首帧/窗口重建窗口内的点击，见 movePointer）。
    private boolean pendingTouch = false;
    private float pendingTouchX = 0f;
    private float pendingTouchY = 0f;

    private final Runnable sendLeftPress = () -> {
        if (leftPressPending && handle != 0L) {
            leftPressPending = false;
            if (!filterActive) {
                leftPressed = true;
                NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, true);
            } else {
                // 单次视口快照：有视口时先把本次触点同步给引擎再发按下，避免落到上一次指针位置。
                FramebufferViewport viewport = graphicsProxy != null ? graphicsProxy.viewport() : null;
                if (viewport != null) {
                    if (pendingTouch) {
                        pendingTouch = false;
                        int[] point = viewport.toSource(pendingTouchX, pendingTouchY);
                        NativeGames.fbPointerMove(handle, point[0], point[1]);
                    }
                    leftPressed = true;
                    NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, true);
                }
            }
            // 视口尚未就绪：丢弃这次按下（位置已在 ACTION_DOWN 时按需缓存）。
        }
    };

    private final Runnable graphicsWatchdog = this::runGraphicsWatchdog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EnginePlayerOrientation.lockOnCreate(this, savedInstanceState);
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        gameRoot = firstNonEmpty(
                getIntent().getStringExtra(LaunchContract.PATH),
                getIntent().getStringExtra(LaunchContract.GAME_PATH),
                getIntent().getStringExtra(LaunchContract.PROJECT_ROOT),
                getIntent().getStringExtra(LaunchContract.GAME_DIR));
        if (gameRoot == null || gameRoot.trim().isEmpty()) {
            Toast.makeText(this, uiString(R.string.engine_fb_missing_game_dir), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        engineId = getIntent().getStringExtra(LaunchContract.GAMES_ENGINE);
        nls = getIntent().getStringExtra(LaunchContract.GAMES_NLS);

        // 运行库已由启动编排解压到应用私有目录；缺失说明安装包不完整或解压失败。
        if (!NativeGames.prepareEngineLibrary(this)) {
            Toast.makeText(this, uiString(R.string.engine_fb_init_failed), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 滤镜模式在构建内容视图前决定（buildContentView 依赖 filterMode）；
        // proxy 构造只启动工作线程，不显著延迟首帧布局。
        setupGraphicsProxy();
        setContentView(buildContentView());

        applyImmersive();
        installBackHandling();
        // 音频后端（cpal/AAudio）依赖 ndk-context，且引擎日志也在此初始化。
        NativeGames.initAndroidContext(getApplicationContext());
        // 先让加载层画出来，再做可能阻塞的 open。
        rootLayout.post(this::openGame);
    }

    private void setupGraphicsProxy() {
        GraphicsFilterChain requested = GraphicsFilterGate.requestedChain(
                getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false),
                getIntent().getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN));
        if (requested == null) return;
        try {
            graphicsProxy = new FramebufferGraphicsProxy(getApplicationContext(), requested,
                    getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false),
                    new FilterProxyListener());
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            // 类初始化失败 / ROM 缺 GLES3 符号（ExceptionInInitializerError /
            // NoClassDefFoundError / UnsatisfiedLinkError，均为 LinkageError）也必须落回经典
            // 路径 —— 此处仍在 setContentView 之前，逃逸会让经典路径也无法启动。
            // 捕获面刻意不含 ThreadDeath / StackOverflowError / 其它 VirtualMachineError：
            // 那些属进程级致命错误，吞掉只会让状态更不可信；OOM 单独放行是因为暂态分配
            // 失败可以安全降级。
            Log.w(TAG, "Graphics proxy unavailable; using the classic bitmap path", failure);
            graphicsProxy = null;
            return;
        }
        filterMode = true;
        filterActive = true;
        if (getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false)) {
            graphicsOverlay = new ArtemisPerformanceOverlay(this, graphicsProxy);
        }
    }

    private View buildContentView() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.BLACK);

        if (filterMode) {
            // Android 16 可能经 SurfaceTexture 帧率监听器保留该 View；display 无主题，
            // Context 不得持有 Activity（与 Siglus 滤镜路径一致）。
            filterView = new TextureView(getApplicationContext());
            filterView.setSurfaceTextureListener(this);
            filterView.setOnTouchListener((v, event) -> onGameTouch(event));
            rootLayout.addView(filterView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        fbView = new FramebufferView(this);
        // 滤镜模式下隐藏待命，失败回退时无需重建；GONE 视图不参与触摸与绘制。
        fbView.setVisibility(filterMode ? View.GONE : View.VISIBLE);
        fbView.setOnTouchListener((v, event) -> onGameTouch(event));
        fbView.setFirstFrameDrawnListener(() -> {
            // 经典路径的揭层信号；滤镜模式下 fbView 为 GONE，该回调永不触发，
            // 由 FilterProxyListener.onFirstFrame 承担。
            if (!isFinishing() && !isDestroyed() && handle != 0L && loadingOverlay != null)
                loadingOverlay.reveal();
        });
        rootLayout.addView(fbView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        textInputView = new FramebufferTextInputView(this, this);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(1, 1);
        inputParams.gravity = Gravity.TOP | Gravity.START;
        rootLayout.addView(textInputView, inputParams);

        rootLayout.addView(buildControlBar(), bottomEndParams());

        loadingOverlay = new EngineStartupOverlay(this);
        loadingOverlay.attachTo(rootLayout);
        return rootLayout;
    }

    private FrameLayout.LayoutParams bottomEndParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        int margin = dp(10);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private View buildControlBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setAlpha(0.65f);

        imeButton = addBarButton(bar, uiString(R.string.engine_fb_ime_show), v -> toggleIme());
        skipButton = addBarButton(bar, uiString(R.string.engine_fb_skip), v -> toggleSkip());
        addBarButton(bar, uiString(R.string.engine_fb_menu), v -> rightClick());
        return bar;
    }

    private TextView addBarButton(LinearLayout bar, String label, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12f);
        button.setBackgroundColor(0x66000000);
        int padH = dp(10);
        int padV = dp(6);
        button.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        bar.addView(button, params);
        button.setOnClickListener(listener);
        return button;
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        Toast.makeText(this, uiString(R.string.engine_another_game_running), Toast.LENGTH_SHORT).show();
    }

    // ---------- 引擎生命周期 ----------

    private void openGame() {
        if (handle != 0L) {
            return;
        }
        String title = getIntent().getStringExtra(LaunchContract.GAMES_TITLE);
        String[] error = new String[1];
        long created;
        try {
            created = NativeGames.fbOpen(gameRoot, engineId, nls, error);
        } catch (Throwable t) {
            created = 0L;
            error[0] = t.getMessage();
        }
        if (created == 0L) {
            String message = error[0] != null && !error[0].trim().isEmpty()
                    ? error[0]
                    : uiString(R.string.engine_fb_init_failed);
            Dialog dialog = KrDialogStyle.createDialog(this, uiString(R.string.engine_launch_failed), message,
                    null, null, true, true, (d, which) -> finish());
            dialog.setOnCancelListener(d -> finish());
            dialog.show();
            return;
        }
        handle = created;
        String engineTitle = NativeGames.fbTitle(handle);
        if (engineTitle != null && !engineTitle.trim().isEmpty()) {
            setTitle(engineTitle);
        } else if (title != null && !title.trim().isEmpty()) {
            setTitle(title);
        }
        maybeStartFrameLoop();
        // 看门狗不在 openGame 武装：引擎尚未产帧（fbFrameSize 为空）时不计时，
        // 首帧提交（presentFrameFiltered）才是武装点，慢启动不因此降级。
        // 但滤镜模式没有任何内部定时器，引擎始终不产帧时加载层会无限期遮挡；
        // 另起一条远长于正常冷启动的最终期限，超时先回退经典路径再揭开加载层。
        if (filterActive) {
            handler.removeCallbacks(startupDeadline);
            handler.postDelayed(startupDeadline, FILTER_STARTUP_DEADLINE_MS);
        }
    }

    /** 滤镜模式启动最终期限；首帧提交（{@code presentFrameFiltered}）时取消。超时先回退经典
     *  路径再揭开加载层（与 ONS 同口径，由 {@code switchToClassicPresentation} 统一补揭层）。 */
    private final Runnable startupDeadline = () -> {
        if (!filterActive || graphicsFramesSubmitted || !resumed) return;
        if (loadingOverlay == null || !loadingOverlay.isLoading()) return;
        Log.w(TAG, "Filter mode never produced an engine frame within "
                + FILTER_STARTUP_DEADLINE_MS + "ms; falling back to the classic bitmap path");
        switchToClassicPresentation();
    };

    /**
     * 输出面从未接通（布局异常或 attach 持续静默失败）时没有其他失败信号，
     * 用看门狗把会话落回经典路径，避免永久黑屏。武装时机是首帧提交（引擎开始
     * 产帧）而非 openGame；暂停期间取消计时（onPause 移除、onResume 重挂），
     * 只有帧循环在跑且仍未呈现才降级。
     */
    private void maybeStartGraphicsWatchdog() {
        // 仅前台武装：分屏 paused-but-visible 冷启动不在暂停态起表。
        if (!graphicsFramesSubmitted || !resumed || !filterActive || graphicsProxy == null) {
            return;
        }
        if (watchdogBaselineMs == 0L) watchdogBaselineMs = SystemClock.elapsedRealtime();
        handler.removeCallbacks(graphicsWatchdog);
        handler.postDelayed(graphicsWatchdog, GRAPHICS_WATCHDOG_MS);
    }

    private void runGraphicsWatchdog() {
        if (!filterActive || graphicsProxy == null || !resumed) {
            // 暂停期间 onPause 已撤销回调；恢复前台由 onResume 重新武装（不再自调用）。
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // 判据是「最近一次成功呈现」，不是「输出面还在」。三种失败形态都必须回退：
        // ①从未呈现（输出面始终没接通 / 接通了但引擎一帧不出）；②呈现过但断流超过窗口；
        // ③呈现过、当前也没有输出面。只看 hasOutput 会让 ① 与 ② 被误判成健康。
        // 本宿主由 Choreographer 逐帧驱动，每次 doFrame 都会尝试提交一帧，因此「最近
        // 呈现」在整个会话里都成立；被池门拒绝时不会 swap，断流判据仍然有效。
        long presented = graphicsProxy.lastPresentedAtMs();
        if (presented > watchdogBaselineMs) watchdogBaselineMs = presented;
        if (now - watchdogBaselineMs < GRAPHICS_WATCHDOG_MS) {
            maybeStartGraphicsWatchdog(); // 仍健康：续上涨期，随成功呈现滚动。
            return;
        }
        Log.w(TAG, "Filter output unavailable past the watchdog window; falling back to the classic bitmap path");
        switchToClassicPresentation();
    }

    private void closeGame() {
        stopFrameLoop();
        handler.removeCallbacks(sendLeftPress);
        leftPressPending = false;
        leftPressed = false;
        if (handle != 0L) {
            long closing = handle;
            handle = 0L;
            NativeGames.fbClose(closing);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        EnginePlayerOrientation.applyLocked(this);
        resumed = true;
        frameAllocFailed = false; // 暂态分配失败：回前台给经典路径一次重试。
        watchdogBaselineMs = 0L; // 暂停时长不计入呈现健康窗口。
        if (graphicsProxy != null) graphicsProxy.resetFramePoolLatch(); // 同款暂态 OOM 复位。
        applyImmersive();
        attachFilterDisplay();
        maybeStartFrameLoop();
        maybeStartGraphicsWatchdog();
        // 防御性补揭层：首帧若在暂停窗口内呈现、一次性 onFirstFrame 又被任何门禁错过，
        // 回前台时用「最近一次呈现」把加载层收掉，避免残留不透明遮罩。
        if (filterActive && graphicsProxy != null && graphicsProxy.lastPresentedAtMs() > 0L
                && loadingOverlay != null && loadingOverlay.isLoading()) {
            loadingOverlay.reveal();
        }
        // onPause 撤销了启动期限；回前台若滤镜仍未产出首帧，重新武装剩余期限。
        if (filterActive && !graphicsFramesSubmitted) {
            handler.removeCallbacks(startupDeadline);
            handler.postDelayed(startupDeadline, FILTER_STARTUP_DEADLINE_MS);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(graphicsWatchdog);
        handler.removeCallbacks(startupDeadline);
        stopFrameLoop();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        if (graphicsProxy != null) graphicsProxy.detachOutput();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (fbView != null) fbView.setFirstFrameDrawnListener(null);
        if (loadingOverlay != null) loadingOverlay.dispose();
        handler.removeCallbacks(graphicsWatchdog);
        handler.removeCallbacks(startupDeadline);
        hideIme();
        if (filterMode) {
            if (graphicsOverlay != null) {
                graphicsOverlay.hide();
                graphicsOverlay = null;
            }
            detachFilterView();
        }
        closeGame(); // 引擎先于消费方停止产帧。
        if (graphicsProxy != null) {
            graphicsProxy.release();
            graphicsProxy = null;
        }
        super.onDestroy();
    }

    private void detachFilterView() {
        // 回退已接管视图拆除（filterView 已置 null），此处只在正常路径工作。
        if (filterView == null) {
            return;
        }
        // 先移除子 View（销毁回调仍在位，异步释放租约引用），再摘除监听。
        rootLayout.removeView(filterView);
        filterView.setSurfaceTextureListener(null);
        filterView.setOnTouchListener(null);
        // 若销毁回调尚未触发（异步拆解），UI 侧引用在此归还；EGL 租约由 proxy 持有。
        releaseDisplayWindow();
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
                    confirmExit();
                    return;
                }
                lastBackMs = now;
                rightClick();
                Toast.makeText(FramebufferGameActivity.this,
                        uiString(R.string.engine_fb_back_again_to_exit), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmExit() {
        KrDialogStyle.createDialog(this, uiString(R.string.engine_exit_game),
                uiString(R.string.engine_exit_game_message), null,
                new String[]{uiString(R.string.engine_ok), uiString(R.string.engine_cancel)},
                true, true, (d, which) -> { if (which == 0) finish(); }).show();
    }

    // ---------- 帧循环 ----------

    private void maybeStartFrameLoop() {
        // 与 Siglus 宿主一致：暂停态不得（重新）启动帧循环，否则引擎在后台持续推进。
        if (!running && resumed && handle != 0L) {
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
        // 每帧自校验（对照 Siglus canStep）：门禁缺口导致的滞留回调在此自然终止。
        if (!running || !resumed || handle == 0L) {
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

        int status = NativeGames.fbStep(handle, dtMs);
        if (status != 0) {
            closeGame();
            finish();
            return;
        }
        if (filterActive && graphicsProxy != null) {
            if (graphicsProxy.hasFailed()) {
                // main 通知丢失时的轮询保险。defer 出 doFrame 调用栈：同步销毁回调时序下，
                // 栈内回退会让尾部重挂之后的下一帧被头门禁（!running）终止而冻结会话；
                // defer 到 handler 上执行时，同步时序由 maybeStartFrameLoop 恰好补一个
                // 回调，异步时序 running 未停、maybeStartFrameLoop 幂等。重复投递被
                // switchToClassicPresentation 的 !filterActive 拦截。
                handler.post(this::switchToClassicPresentation);
            } else if (graphicsFramesSubmitted && !graphicsProxy.hasOutput()
                    && frameTimeNanos - lastOutputReattachNs >= OUTPUT_REATTACH_INTERVAL_NS) {
                // 自愈：已呈现后会话中期 attach 良性失败是静默的，1Hz 重挂直到
                // 接通或终端失败回退；接通后 hasOutput 为真，不再进入本分支。
                lastOutputReattachNs = frameTimeNanos;
                attachFilterDisplay();
            }
            // 视口在本帧就绪后补发之前被丢弃的按下位置。
            flushPendingTouch();
        }
        if (filterActive) {
            presentFrameFiltered();
        } else {
            presentFrame();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void presentFrameFiltered() {
        if (graphicsProxy == null) { // 与 doFrame 分支一致的空值守卫。
            return;
        }
        int[] size = NativeGames.fbFrameSize(handle);
        if (size == null || size.length < 2 || size[0] <= 0 || size[1] <= 0) {
            return;
        }
        if (!graphicsFramesSubmitted) {
            // 武装点：引擎已开始产帧（尺寸有效）即起表，而不是「成功 submitFrame」之后。
            // 池门拒绝（超预算 / 持续 OOM / 槽位全在途）时也必须有回退信号。
            graphicsFramesSubmitted = true;
            handler.removeCallbacks(startupDeadline);
            watchdogBaselineMs = 0L;
            maybeStartGraphicsWatchdog();
        }
        FramebufferGraphicsProxy.Slot slot = graphicsProxy.acquireSlot(size[0], size[1]);
        if (slot == null) {
            // 槽位不可用：全部在途（latest-wins 自然丢弃）或超预算被池门拒绝（池内有 Log.w）。
            return;
        }
        slot.buffer.rewind();
        boolean copied = NativeGames.fbCopyFrame(handle, slot.buffer);
        // 拷贝后复读尺寸：引擎在两次读取之间改尺寸时该帧已撕裂，弃用并等下一帧。
        int[] after = copied ? NativeGames.fbFrameSize(handle) : null;
        if (!copied || after == null || after[0] != size[0] || after[1] != size[1]) {
            graphicsProxy.abandonSlot(slot);
            return;
        }
        slot.buffer.rewind();
        // 与经典路径同一 RGBA8 内存序（R,G,B,A）。
        graphicsProxy.submitFrame(slot, size[0], size[1]);
    }

    /**
     * 滤镜路径不可恢复失败后的回退：切回经典 Bitmap 呈现，滤镜资源有界释放。
     * 只能在 handler（主线程消息）上下文调用、不得在 doFrame 调用栈内直接调用：
     * 同步销毁回调时序下 removeView 会停掉帧循环，末尾的 maybeStartFrameLoop
     * 恰好补回一个回调；异步时序下 running 未停，maybeStartFrameLoop 幂等。
     */
    private void switchToClassicPresentation() {
        if (!filterActive || isFinishing() || isDestroyed()) {
            return;
        }
        Log.w(TAG, "Filter presentation failed; falling back to the classic bitmap path");
        filterActive = false;
        handler.removeCallbacks(startupDeadline);
        // 看门狗与新路径无关；不撤销会让它在回退后仍按旧判据再次触发回退（幂等但白跑）。
        handler.removeCallbacks(graphicsWatchdog);
        if (graphicsOverlay != null) {
            graphicsOverlay.hide();
            graphicsOverlay = null;
        }
        if (graphicsProxy != null) {
            graphicsProxy.release();
            graphicsProxy = null;
        }
        if (filterView != null) {
            // 先移除视图走正常销毁回调（租约引用归还），再摘除监听；回调若未触发，
            // 由下方兜底归还 UI 引用（引用计数保证幂等）。AOSP 源码裁决：子 View 的
            // detach 在 ViewGroup.removeViewInternal 内同步派发（无过渡动画时），
            // TextureView 在 onDetachedFromWindowInternal 内同步回调销毁监听。
            rootLayout.removeView(filterView);
            filterView.setSurfaceTextureListener(null);
            filterView.setOnTouchListener(null);
            filterView = null;
        }
        displayTexture = null;
        releaseDisplayWindow();
        if (fbView != null) {
            fbView.setVisibility(View.VISIBLE);
        }
        // 回退后经典路径可能同样拿不到帧（帧池/位图 OOM 等），此时 startupDeadline 已
        // 被取消、看门狗也已撤销，加载层必须有兜底揭层，否则永久黑屏遮挡并吞掉触摸。
        if (loadingOverlay != null && loadingOverlay.isLoading()) loadingOverlay.reveal();
        // 同步销毁回调时序下 removeView 已停掉帧循环，在此补回；
        // 异步时序下循环未停，maybeStartFrameLoop 幂等。
        maybeStartFrameLoop();
    }

    private void presentFrame() {
        int[] size = NativeGames.fbFrameSize(handle);
        if (size == null || size[0] <= 0 || size[1] <= 0) {
            return;
        }
        // 分配失败后不逐帧重试（回退路径使该分配在会话中期新可达）；尺寸变化才重试。
        if (frameAllocFailed && size[0] == frameW && size[1] == frameH) {
            return;
        }
        if (bitmap == null || buffer == null || size[0] != frameW || size[1] != frameH) {
            Bitmap nextBitmap = null;
            ByteBuffer nextBuffer = null;
            try {
                int bytes = Math.multiplyExact(Math.multiplyExact(size[0], size[1]), 4);
                nextBitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                nextBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            } catch (OutOfMemoryError | IllegalArgumentException | ArithmeticException failure) {
                if (nextBitmap != null) {
                    nextBitmap.recycle();
                }
                bitmap = null;
                buffer = null;
                frameW = size[0];
                frameH = size[1];
                Log.w(TAG, "Classic frame buffer allocation failed; waiting for a size change", failure);
                frameAllocFailed = true;
                return;
            }
            bitmap = nextBitmap;
            buffer = nextBuffer;
            frameW = size[0];
            frameH = size[1];
            frameAllocFailed = false;
        }
        if (buffer == null) {
            return;
        }
        buffer.rewind();
        if (NativeGames.fbCopyFrame(handle, buffer)) {
            buffer.rewind();
            // ARGB_8888 的内存字节序是 R,G,B,A，与引擎的 RGBA8 帧一致。
            bitmap.copyPixelsFromBuffer(buffer);
            fbView.setFrame(bitmap);
        }
    }

    // ---------- 滤镜显示窗口 ----------

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
        if (!filterMode || !filterActive || displayTexture != null) {
            return;
        }
        displayTexture = texture;
        if (graphicsProxy == null) {
            return;
        }
        try {
            displayWindow = new TextureDisplayWindow(texture);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            // 与 setupGraphicsProxy 同口径（含类初始化/链接失败），理由见该处注释。
            Log.w(TAG, "Display window unavailable; the texture stays with the view", failure);
            return; // displayWindow == null → destroy 回调让系统释放纹理。
        }
        // 优先用已布局的 View 尺寸。回调参数在布局未完成时可能为 0/1，
        // 若用 1×1 建面则 swapBuffers 成功即锁死首帧标记，分析器与看门狗全失效。
        int surfaceW = filterView != null ? filterView.getWidth() : 0;
        int surfaceH = filterView != null ? filterView.getHeight() : 0;
        if (surfaceW <= 1 || surfaceH <= 1) {
            surfaceW = width;
            surfaceH = height;
        }
        if (surfaceW <= 1 || surfaceH <= 1) {
            // 仍无有效尺寸：不建面；onSurfaceTextureSizeChanged 会在布局完成后重试。
            Log.i(TAG, "Deferring filter attach until the texture view is laid out");
            return;
        }
        if (!resumed) {
            // 暂停 / 分屏 paused-but-visible 期间不发起 attach（与 attachFilterDisplay 同门禁）；
            // 租约与 texture 已登记，onResume 的 attachFilterDisplay 会完成接通。
            Log.i(TAG, "Deferring filter attach until the host resumes");
            return;
        }
        graphicsProxy.attachOutput(displayWindow, surfaceW, surfaceH);
        // Surface 在会话中途被销毁重建时（不经历 onResume），帧循环从这里恢复。
        maybeStartFrameLoop();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
        if (displayTexture != texture) {
            return;
        }
        attachFilterDisplay();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
        if (displayTexture != texture) {
            return true;
        }
        displayTexture = null;
        stopFrameLoop();
        if (graphicsProxy != null) {
            graphicsProxy.detachOutput();
        }
        boolean owned = displayWindow != null;
        releaseDisplayWindow();
        // 返回 false 仅当窗口租约负责 texture.release()（proxy 可能仍持有 EGL 引用）；
        // 容量拒绝或从未接管的纹理交还系统释放。
        return !owned;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        // GLES 线程直接向窗口渲染；无需逐帧通知。
    }

    private void attachFilterDisplay() {
        // Siglus attachDisplay 同款 !resumed 门：暂停态（含分屏 paused-but-visible）
        // 不发起 attach，避免输出接通回调在暂停期间重启帧循环。
        if (!resumed || !filterMode || !filterActive || graphicsProxy == null || displayWindow == null
                || displayTexture == null || filterView == null) {
            return;
        }
        // 与 onSurfaceTextureAvailable 同口径。布局未完成时不能用 1×1 建面——
        // swapBuffers 成功即把 firstPresented 锁死，分析器与看门狗判据全部失真。
        int width = filterView.getWidth();
        int height = filterView.getHeight();
        if (width <= 1 || height <= 1) {
            Log.i(TAG, "Deferring filter re-attach until the texture view is laid out");
            return;
        }
        graphicsProxy.attachOutput(displayWindow, width, height);
    }

    private void releaseDisplayWindow() {
        TextureDisplayWindow previous = displayWindow;
        displayWindow = null;
        if (previous != null) {
            previous.release();
        }
    }

    private final class FilterProxyListener implements FramebufferGraphicsProxy.Listener {
        @Override
        public void onOutputReady(FramebufferGraphicsProxy proxy) {
            // Siglus 同款门禁：暂停态或销毁中的滞留回调不重启循环、不拉起分析器；
            // 恢复前台时 attachFilterDisplay 会重新 attach 并再次回调。
            if (!resumed || isFinishing() || isDestroyed()) {
                return;
            }
            if (graphicsOverlay != null) {
                graphicsOverlay.show();
            }
            maybeStartFrameLoop(); // 与 Siglus 宿主一致：输出接通即恢复帧循环。
        }

        @Override
        public void onFirstFrame(FramebufferGraphicsProxy proxy) {
            // 滤镜路径唯一的揭层信号：fbView 为 GONE，经典首帧回调永不触发。
            // 不得在这里判 resumed —— 代理的 firstPresented 是会话内一次性闩锁，暂停窗口内
            // 丢掉这次回调后就再也不会重投（首帧提交时 startupDeadline 也已取消），
            // 结果是不透明加载层永久遮挡。经典路径 (:244) 同样只判 finishing/destroyed。
            if (isFinishing() || isDestroyed()) return;
            if (loadingOverlay != null && loadingOverlay.isLoading()) loadingOverlay.reveal();
        }

        @Override
        public void onFailure(FramebufferGraphicsProxy proxy, RuntimeException failure) {
            // 滤镜不可恢复失败：回退经典路径并由 switchToClassicPresentation 统一补揭层，
            // 若回退后经典首帧也拿不到帧，至少不会永远停在黑屏加载层。
            switchToClassicPresentation();
        }
    }

    // ---------- 输入 ----------

    private boolean onGameTouch(MotionEvent event) {
        if (handle == 0L || event == null) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                multiTouch = false;
                wheelUsed = false;
                movePointer(event.getX(), event.getY());
                leftPressPending = true;
                handler.postDelayed(sendLeftPress, LEFT_PRESS_DELAY_MS);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (!multiTouch) {
                    multiTouch = true;
                    handler.removeCallbacks(sendLeftPress);
                    leftPressPending = false;
                    wheelAnchorY = averageY(event);
                    wheelAccum = 0f;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (multiTouch) {
                    float y = averageY(event);
                    wheelAccum += y - wheelAnchorY;
                    wheelAnchorY = y;
                    while (Math.abs(wheelAccum) >= WHEEL_STEP_PX) {
                        boolean up = wheelAccum > 0;
                        NativeGames.fbWheel(handle, up);
                        wheelAccum += up ? -WHEEL_STEP_PX : WHEEL_STEP_PX;
                        wheelUsed = true;
                    }
                } else {
                    movePointer(event.getX(), event.getY());
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (multiTouch) {
                    if (leftPressed) {
                        leftPressed = false;
                        NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                    }
                    if (!wheelUsed) {
                        rightClick();
                    }
                } else {
                    // 轻点（< LEFT_PRESS_DELAY_MS）必须与坐标移动共用同一次视口读取，
                    // 否则移动读到空而点击判断读到非空时 down/up 会落到旧指针位置。
                    if (movePointer(event.getX(), event.getY())) {
                        if (leftPressPending) {
                            handler.removeCallbacks(sendLeftPress);
                            leftPressPending = false;
                            NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, true);
                            NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                        } else if (leftPressed) {
                            // 慢按已发过按下；抬手必须补发松开，否则引擎左键粘滞。
                            NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                        }
                    } else {
                        // 视口未就绪：丢弃这次点击，位置已由 movePointer 缓存待补发。
                        handler.removeCallbacks(sendLeftPress);
                        leftPressPending = false;
                        // 之前已发出按下（拖拽中视口被清空）也必须补发松开，否则左键粘滞；
                        // 从未按下则不发悬空的 BUTTON_LEFT false。
                        if (leftPressed) {
                            NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                        }
                    }
                    leftPressed = false;
                }
                multiTouch = false;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(sendLeftPress);
                leftPressPending = false;
                if (leftPressed) {
                    leftPressed = false;
                    NativeGames.fbPointerButton(handle, NativeGames.BUTTON_LEFT, false);
                }
                multiTouch = false;
                break;
            }
            default:
                break;
        }
        return true;
    }

    private static float averageY(MotionEvent event) {
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getY(i);
        }
        return sum / Math.max(1, event.getPointerCount());
    }

    private boolean movePointer(float x, float y) {
        if (filterActive && graphicsProxy != null) {
            // 视口未就绪（首帧前/重建窗口）时无有效映射，不移动引擎指针；
            // 缓存本点，视口就绪后补发，避免这次点击落到上一次的指针位置。
            FramebufferViewport viewport = graphicsProxy.viewport();
            if (viewport == null) {
                pendingTouchX = x;
                pendingTouchY = y;
                pendingTouch = true;
                return false;
            }
            pendingTouch = false;
            int[] point = viewport.toSource(x, y);
            NativeGames.fbPointerMove(handle, point[0], point[1]);
            return true;
        }
        int[] point = fbView.toFrame(x, y);
        NativeGames.fbPointerMove(handle, point[0], point[1]);
        return true;
    }

    /** 视口就绪时补发被缓存的按下位置；仅滤镜路径使用。 */
    private void flushPendingTouch() {
        if (!pendingTouch || !filterActive || graphicsProxy == null) return;
        FramebufferViewport viewport = graphicsProxy.viewport();
        if (viewport == null || handle == 0L) return;
        pendingTouch = false;
        int[] point = viewport.toSource(pendingTouchX, pendingTouchY);
        NativeGames.fbPointerMove(handle, point[0], point[1]);
    }

    private void rightClick() {
        if (handle == 0L) {
            return;
        }
        NativeGames.fbPointerButton(handle, NativeGames.BUTTON_RIGHT, true);
        NativeGames.fbPointerButton(handle, NativeGames.BUTTON_RIGHT, false);
    }

    private void toggleSkip() {
        if (handle == 0L) {
            return;
        }
        skipping = !skipping;
        NativeGames.fbKey(handle, NativeGames.KEY_CTRL, skipping);
        if (skipButton != null) {
            skipButton.setText(uiString(skipping ? R.string.engine_fb_skip_off : R.string.engine_fb_skip));
        }
    }

    private void toggleIme() {
        if (imeVisible) {
            hideIme();
        } else {
            showIme();
        }
    }

    private void showIme() {
        if (handle == 0L || textInputView == null) {
            return;
        }
        textInputView.requestFocus();
        textInputView.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(textInputView, 0);
            }
        });
        imeVisible = true;
        if (imeButton != null) {
            imeButton.setText(uiString(R.string.engine_fb_ime_hide));
        }
    }

    private void hideIme() {
        if (textInputView == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(textInputView.getWindowToken(), 0);
        }
        textInputView.clearFocus();
        imeVisible = false;
        if (imeButton != null) {
            imeButton.setText(uiString(R.string.engine_fb_ime_show));
        }
    }

    @Override
    public void onCommitText(String text) {
        if (handle != 0L && text != null && !text.isEmpty()) {
            NativeGames.fbText(handle, text);
        }
    }

    @Override
    public void onBackspace() {
        if (handle != 0L) {
            NativeGames.fbKey(handle, NativeGames.KEY_BACKSPACE, true);
            NativeGames.fbKey(handle, NativeGames.KEY_BACKSPACE, false);
        }
    }

    @Override
    public void onEnter() {
        if (handle != 0L) {
            NativeGames.fbKey(handle, NativeGames.KEY_ENTER, true);
            NativeGames.fbKey(handle, NativeGames.KEY_ENTER, false);
        }
    }

    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return NativeGames.KEY_ENTER;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return NativeGames.KEY_ESCAPE;
            case KeyEvent.KEYCODE_SPACE:
                return NativeGames.KEY_SPACE;
            case KeyEvent.KEYCODE_DPAD_UP:
                return NativeGames.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return NativeGames.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return NativeGames.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return NativeGames.KEY_RIGHT;
            case KeyEvent.KEYCODE_PAGE_UP:
                return NativeGames.KEY_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return NativeGames.KEY_PAGE_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return NativeGames.KEY_HOME;
            case KeyEvent.KEYCODE_MOVE_END:
                return NativeGames.KEY_END;
            case KeyEvent.KEYCODE_DEL:
                return NativeGames.KEY_BACKSPACE;
            case KeyEvent.KEYCODE_TAB:
                return NativeGames.KEY_TAB;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return NativeGames.KEY_CTRL;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return NativeGames.KEY_SHIFT;
            default:
                if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
                    return NativeGames.KEY_F_BASE + (keyCode - KeyEvent.KEYCODE_F1 + 1);
                }
                return 0;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int code = mapKey(keyCode);
        if (handle != 0L && code != 0) {
            if (event.getRepeatCount() == 0 || code <= NativeGames.KEY_SHIFT) {
                NativeGames.fbKey(handle, code, true);
            }
            return true;
        }
        if (handle != 0L && event.getUnicodeChar() > 0 && !event.isCtrlPressed()) {
            NativeGames.fbText(handle, String.valueOf((char) event.getUnicodeChar()));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int code = mapKey(keyCode);
        if (handle != 0L && code != 0) {
            NativeGames.fbKey(handle, code, false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------- 工具 ----------

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

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
