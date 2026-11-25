package com.yuri.onscripter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

import com.core.engine.DoubleBackExit;
import com.core.engine.EnginePlayerOrientation;
import com.core.engine.EngineSurfaceStartupController;
import com.core.engine.LaunchContract;
import com.core.engine.R;
import com.core.nativeplugin.IjkRuntime;
import com.core.gl.ArtemisPerformanceOverlay;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.GraphicsFilterGate;
import com.core.gl.TextureDisplayWindow;
import com.core.ons.OnsGraphicsProxy;
import com.core.ons.OnsInput;
import com.core.ons.OnsLibLoader;
import com.core.ons.OnsScriptResolution;
import com.core.ons.OnsSettings;
import com.core.ons.OnsSurface;
import com.core.ons.OnsVideoOverlay;
import com.core.ons.OnsViewport;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ONScripter extends SDLActivity implements OnsSurface.SurfaceProvider, OnsGraphicsProxy.Listener {
    private static final String TAG = "YukiONS";
    public static final String YURI_VERSION = "Yuri_0.7.7";
    private static final String PREF_OVERLAY = "ons_overlay";
    private static final String KEY_OVERLAY_VISIBLE = "visible";
    private static final int CONTROL_BUTTON_SIZE_DP = 40;
    private static final int CONTROL_BUTTON_GAP_DP = 5;
    private static final int TOGGLE_BUTTON_SIZE_DP = 32;

    private ArrayList<String> onsArgs;
    private boolean ignoreCutout = true;
    private String gameRoot;
    private FrameLayout onsOverlay;
    private LinearLayout leftControls;
    private LinearLayout rightControls;
    private ImageButton toggleButton;
    private final ArrayList<TextView> autoButtons = new ArrayList<>();
    private boolean controlsVisible = true;
    private boolean autoMode = false;
    /** 视频覆盖播放控制器：在本 Activity 窗口内覆盖播放，不启动独立 Activity。 */
    private OnsVideoOverlay videoOverlay;
    private EngineSurfaceStartupController startupController;

    private GraphicsFilterChain requestedChain;
    // SDL 线程经 isFilterMode 读取，UI 线程在回退时写 false。
    private volatile boolean filterMode;
    private boolean analyzerRequested;
    // SDL 线程经 isFilterMode/provideProxySurface 读取，UI 线程在回退时写 null。
    private volatile OnsGraphicsProxy graphicsProxy;
    private volatile TextureDisplayWindow displayWindow;
    private volatile TextureView filterView;
    private volatile SurfaceTexture displayTexture;
    private volatile OnsSurface onsSurface;
    /** 从黑边起手的手势整段吞掉，直到该手势结束。 */
    private boolean filterTouchOutsideStage;
    private boolean filterTouchActive;
    private boolean filterMouseActive;
    private float lastFilterTouchNormX = 0.5f;
    private float lastFilterTouchNormY = 0.5f;
    private float lastFilterMouseX;
    private float lastFilterMouseY;
    private int lastFilterMouseButton = 1;
    private ArtemisPerformanceOverlay graphicsOverlay;
    private volatile int detectedStageWidth = OnsScriptResolution.DEFAULT_WIDTH;
    private volatile int detectedStageHeight = OnsScriptResolution.DEFAULT_HEIGHT;
    private volatile boolean uiResumed;

    // 滤镜输出看门狗 + 自愈（从 Framebuffer 宿主移植）：
    // 代理 producer 交给 SDL 后，若 TextureView 输出在超时内未接通（attach 良性
    // 失败静默返回），按 1Hz 重挂；持续未接通、或首帧始终未呈现则超时后回退
    // 引擎直出。首帧呈现后判据退回「输出面还在」——ONS 是事件驱动的，静止画面
    // 长时间不换帧属于健康态。健康期退化为低频巡检；基线只在「健康」「stage 未
    // 就绪」与「OP 视频播放」时清零，因此稳态下的瞬时断连从断连那一刻起算，
    // 而不是复用多久以前的时间戳。
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final long ONS_OUTPUT_POLL_MS = 1000L;
    private static final long ONS_HEALTH_POLL_MS = 5000L;
    private static final long ONS_OUTPUT_TIMEOUT_MS = 10000L;
    /** 滤镜模式启动的最终期限：远长于正常冷启动，超时即回退直出。 */
    private static final long ONS_FILTER_STARTUP_TIMEOUT_MS = 60000L;
    private long onsUnhealthySinceMs = 0;
    private boolean onsStartupDeferredByVideo;
    private final Runnable onsOutputPoll = this::onsOutputPoll;
    private final Runnable onsFilterStartupTimeout = this::onFilterStartupTimeout;

    // onNativeResize 只在「逻辑尺寸变化」时触发一轮，由单个 daemon worker 串行执行，
    // 新一轮请求复用运行中的 worker 并接管代次，不再每次 provideProxySurface 新起线程。
    private static final int PROXY_RESIZE_ATTEMPTS = 20;
    private static final long PROXY_RESIZE_INTERVAL_MS = 10L;
    private final Object proxyResizeLock = new Object();
    private int proxyResizeTargetWidth;
    private int proxyResizeTargetHeight;
    private int proxyResizeGeneration;
    private boolean proxyResizeRunning;
    private boolean proxyResizeConverged;

    private native int nativeInitJavaCallbacks();
    native int nativeGetWidth();
    native int nativeGetHeight();

    @Override public void loadLibraries() {
        OnsLibLoader.load(this);
    }

    @Override public String[] getLibraries() {
        return new String[]{"SDL2", "lua", "jpeg", "bz2", "modplug", "SDL2_image", "SDL2_mixer", "SDL2_ttf", OnsSettings.PREF_NAME};
    }

    @Override public String getMainSharedObject() {
        return OnsLibLoader.getMainSharedObject(this).getAbsolutePath();
    }

    @Override public String[] getArguments() {
        if (onsArgs == null) onsArgs = new ArrayList<>();
        return onsArgs.toArray(new String[0]);
    }

    @Override
    public SDLSurface createSDLSurface(Context context) {
        if (!filterMode) {
            return super.createSDLSurface(context);
        }
        onsSurface = new OnsSurface(context, this);
        return onsSurface;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        EnginePlayerOrientation.lockOnCreate(this, savedInstanceState);
        gameRoot = firstNonEmpty(
                getIntent().getStringExtra(LaunchContract.PATH),
                getIntent().getStringExtra(LaunchContract.GAME_PATH),
                getIntent().getStringExtra(LaunchContract.ROOT_URI),
                getIntent().getStringExtra(LaunchContract.GAME_URI));
        gameRoot = normalizeRootPath(gameRoot);
        onsArgs = getIntent().getStringArrayListExtra(LaunchContract.GAME_ARGS);
        OnsSettings settings = OnsSettings.load(this);
        if (onsArgs == null) onsArgs = settings.buildArgs(this, gameRoot);
        ignoreCutout = getIntent().getBooleanExtra(LaunchContract.IGNORE_CUTOUT, settings.ignoreCutout);

        requestedChain = GraphicsFilterGate.requestedChain(
                getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false),
                getIntent().getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN));
        filterMode = requestedChain != null;
        analyzerRequested = filterMode && getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false);
        if (filterMode) {
            // 脚本分辨率探测仅滤镜路径需要；经典路径不花主线程 I/O。
            OnsScriptResolution scriptRes = OnsScriptResolution.detect(gameRoot);
            detectedStageWidth = scriptRes.width;
            detectedStageHeight = scriptRes.height;
            // 滤镜与 --sharpness 互斥：app 侧已过滤，buildArgs 兜底路径在此再兜一次。
            if (onsArgs != null) {
                for (int i = onsArgs.size() - 2; i >= 0; i--) {
                    if ("--sharpness".equals(onsArgs.get(i))) {
                        onsArgs.remove(i + 1);
                        onsArgs.remove(i);
                    }
                }
            }
            try {
                graphicsProxy = new OnsGraphicsProxy(this, requestedChain, analyzerRequested, this);
            } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
                Log.w(TAG, "Failed to create OnsGraphicsProxy, falling back to direct", t);
                filterMode = false;
                graphicsProxy = null;
            }
        }

        // 提前加载/释放 assets：确保 libonsyuri 与内置 DroidSansFallback.ttf 在 SDLActivity 启动前可用。
        OnsLibLoader.load(this);
        // IJK 三件套从运行库包按绝对路径加载；失败时视频覆盖层走既有失败收场路径。
        IjkRuntime.load(this);
        ensureDefaultFont();
        super.onCreate(savedInstanceState);
        fixSurfaceCentering();  // 修复平板设备上画面不居中的问题

        if (filterMode && graphicsProxy != null && mLayout != null) {
            filterView = new TextureView(getApplicationContext()); // 与另两个宿主一致
            filterView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                    // 回退后迟到的可用回调不得再登记纹理：滤镜已关闭，没有代理消费它。
                    if (!filterMode || graphicsProxy == null) return;
                    // 第二次 available 若无守卫会直接覆盖旧 displayWindow，旧租约引用永不归零。
                    if (displayTexture != null) return;
                    displayTexture = surfaceTexture;
                    attachFilterWindow(surfaceTexture, width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                    if (displayTexture != surfaceTexture || graphicsProxy == null) return;
                    // 与 attachOnsOutputNow / Framebuffer attachFilterDisplay 同门禁：暂停 /
                    // 分屏 paused-but-visible 期间不发起 attach，onResume 会立即重挂。
                    if (!uiResumed) return;
                    // 与 attachOnsOutputNow / Framebuffer attachFilterDisplay 同口径：布局未完成时
                    // 不用 1×1 建面（swapBuffers 成功即锁死首帧标记），交给后续回调重试。
                    if (width <= 1 || height <= 1) return;
                    if (displayWindow != null) graphicsProxy.attachOutput(displayWindow, width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    if (displayTexture != surfaceTexture) return true;
                    displayTexture = null;
                    if (graphicsProxy != null) {
                        graphicsProxy.detachOutput();
                    }
                    boolean owned = displayWindow != null;
                    if (owned) {
                        displayWindow.release();
                        displayWindow = null;
                    }
                    // 租约包装时由租约释放（返回 false）；未包装时交还系统释放。
                    return !owned;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {}
            });
            filterView.setFocusable(false);
            filterView.setFocusableInTouchMode(false);
            filterView.setOnTouchListener(this::onFilterTouch);
            filterView.setOnGenericMotionListener(this::onFilterGenericMotion);
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
            lp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
            mLayout.addView(filterView, lp);
            if (analyzerRequested) {
                graphicsOverlay = new ArtemisPerformanceOverlay(this, graphicsProxy);
            }
        }

        try { nativeInitJavaCallbacks(); } catch (Throwable t) { Log.w(TAG, "nativeInitJavaCallbacks failed", t); }
        setupVirtualControls();
        fullscreen();
        attachStartupOverlay();
    }

    /** 构造显示租约并挂到代理；任何失败都不让异常冒进主线程。构造与挂载分开捕获：
     *  租约一旦构造成功就归 {@code onSurfaceTextureDestroyed} 释放，这里不得提前 release
     *  仍在 TextureView 上的活纹理（否则销毁回调会二次释放同一 SurfaceTexture）。 */
    private void attachFilterWindow(SurfaceTexture surfaceTexture, int width, int height) {
        if (!filterMode || graphicsProxy == null) return;
        TextureDisplayWindow window;
        try {
            window = new TextureDisplayWindow(surfaceTexture);
            displayWindow = window;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            // 未构造出租约：纹理仍归 TextureView，销毁回调返回 true 交系统释放。
            Log.w(TAG, "Failed to create display window, keeping the texture view", failure);
            displayTexture = null;
            return;
        }
        // 回调参数在布局未完成时可能是 0/1：①用已布局的 View 尺寸覆盖；②仍无效就只保留
        // 租约、不建 EGL 面（swapBuffers 成功会把首帧标记锁死），等尺寸回调或 1Hz 轮询重挂。
        int surfaceW = filterView != null ? filterView.getWidth() : 0;
        int surfaceH = filterView != null ? filterView.getHeight() : 0;
        if (surfaceW <= 1 || surfaceH <= 1) {
            surfaceW = width;
            surfaceH = height;
        }
        if (surfaceW <= 1 || surfaceH <= 1) {
            Log.i(TAG, "Deferring filter attach until the texture view is laid out");
            return;
        }
        if (!uiResumed) {
            // 暂停 / 分屏 paused-but-visible 期间不发起 attach（与 attachOnsOutputNow、
            // Framebuffer attachFilterDisplay 同门禁）；租约与纹理已登记，onResume 的
            // attachOnsOutputNow 会立即完成接通。
            Log.i(TAG, "Deferring filter attach until the host resumes");
            return;
        }
        try {
            graphicsProxy.attachOutput(window, surfaceW, surfaceH);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            // 租约已构造：保留 displayWindow / displayTexture，交给销毁回调正常归还引用。
            Log.w(TAG, "Failed to attach filter display, keeping the display lease", failure);
        }
    }

    private void attachStartupOverlay() {
        if (mBrokenLibraries || mSurface == null || mLayout == null) return;
        ViewGroup content = findViewById(android.R.id.content);
        // 滤镜模式下真实 SurfaceView 已不再有 producer（SDL 拿的是代理 producer Surface，
        // 画面由 filterView 呈现），EngineSurfaceReadyProbe 对它做 PixelCopy 恒 NO_DATA，
        // 会一直重试到 120s 超时再弹「启动失败」。故滤镜模式不挂探针，改由代理首帧揭层。
        if (filterMode && graphicsProxy != null) {
            startupController = new EngineSurfaceStartupController(this, null, false);
            startupController.attachOverlayOnly(content != null ? content : mLayout);
            // 摘掉探针后滤镜模式没有任何内部定时器；脚本损坏、SDL 建窗失败等
            // 「永不请求 native surface」的形态必须有最终期限，超时回退直出并揭层。
            armFilterStartupTimeout();
        } else {
            startupController = new EngineSurfaceStartupController(this);
            startupController.attachTo(content != null ? content : mLayout, mSurface.getHolder());
        }
    }

    /** 滤镜路径的揭层：首次成功呈现后才撤掉加载层。 */
    private void revealStartupOverlay() {
        cancelFilterStartupTimeout();
        if (startupController != null && startupController.isLoading()) startupController.reveal();
    }

    private void armFilterStartupTimeout() {
        if (!filterMode || graphicsProxy == null || startupController == null || !startupController.isLoading()) return;
        uiHandler.removeCallbacks(onsFilterStartupTimeout);
        if (videoOverlay != null && videoOverlay.isPlaying()) {
            onsStartupDeferredByVideo = true;
            uiHandler.postDelayed(onsFilterStartupTimeout, ONS_OUTPUT_POLL_MS);
        } else {
            onsStartupDeferredByVideo = false;
            uiHandler.postDelayed(onsFilterStartupTimeout, ONS_FILTER_STARTUP_TIMEOUT_MS);
        }
    }

    private void cancelFilterStartupTimeout() {
        uiHandler.removeCallbacks(onsFilterStartupTimeout);
        onsStartupDeferredByVideo = false;
    }

    /** 滤镜启动最终期限：仍在加载层时先回退直出，再揭开（回退后的直出未必有画面，
     *  但必须给用户可操作的界面，而不是无限期不透明加载层）。 */
    private void onFilterStartupTimeout() {
        if (!filterMode || graphicsProxy == null) {
            onsStartupDeferredByVideo = false;
            return;
        }
        // 暂停 / 分屏 paused-but-visible 期间不判失败：onResume 会重新武装剩余期限。
        if (!uiResumed) return;
        if (startupController == null || !startupController.isLoading()) {
            onsStartupDeferredByVideo = false;
            return;
        }
        // 开场 OP 视频播放期间引擎不产帧（画面由视频覆盖层呈现）：顺延启动期限，
        // 避免 >60s 的片头播放中途触发回退并永久丢失滤镜；播片结束后补足完整窗口。
        if (videoOverlay != null && videoOverlay.isPlaying()) {
            onsStartupDeferredByVideo = true;
            uiHandler.removeCallbacks(onsFilterStartupTimeout);
            uiHandler.postDelayed(onsFilterStartupTimeout, ONS_OUTPUT_POLL_MS);
            return;
        }
        if (onsStartupDeferredByVideo) {
            onsStartupDeferredByVideo = false;
            uiHandler.removeCallbacks(onsFilterStartupTimeout);
            uiHandler.postDelayed(onsFilterStartupTimeout, ONS_FILTER_STARTUP_TIMEOUT_MS);
            return;
        }
        Log.w(TAG, "Filter startup never reached a frame within " + ONS_FILTER_STARTUP_TIMEOUT_MS
                + "ms; falling back to direct");
        fallbackToDirect();
    }

    @Override public void onResume() {
        super.onResume();
        uiResumed = true;
        fullscreen();
        if (startupController != null) startupController.onResume();
        // 播片期间切后台会暂停解码，回到前台必须恢复，否则视频停在暂停帧。
        if (videoOverlay != null) videoOverlay.onHostResume();
        if (graphicsOverlay != null) graphicsOverlay.show();
        if (filterMode && graphicsProxy != null) {
            // onPause 已 detachOutput，重挂不能等 1Hz 轮询的第一个 tick（最长 1s 黑窗）。
            // 与 Framebuffer onResume 一致，先立即重挂，再启动看门狗轮询。
            attachOnsOutputNow();
            startOnsOutputPoll(); // 无条件启动：覆盖「恢复前台到首帧」之间的快速恢复窗口。
            // 分屏 paused-but-visible 冷启动或首帧 200ms 内短暂暂停取消后，回前台补跑未收敛的逻辑尺寸。
            resumeProxyResizeIfNeeded();
            // 启动期限在 onPause 被撤销；仍未揭开时重新武装剩余期限（OP 播片期保持顺延）。
            armFilterStartupTimeout();
        }
    }

    @Override public void onPause() {
        if (startupController != null) startupController.onPause();
        super.onPause();
        uiResumed = false;
        // 切后台时暂停解码：overlay 不随 Activity 销毁，回来还在。
        if (videoOverlay != null) videoOverlay.onHostPause();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        // 与 Siglus / Framebuffer 两个参照宿主一致：暂停时断开滤镜输出面。
        if (filterMode && graphicsProxy != null) graphicsProxy.detachOutput();
        cancelOnsOutputPoll();
        // 在途 resize 轮次作废，但保留未收敛目标供 onResume() 补跑。
        pauseProxyResizeWorker();
        cancelFilterStartupTimeout();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (startupController != null) startupController.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            fullscreen();
            if (graphicsOverlay != null) graphicsOverlay.show();
        } else {
            if (graphicsOverlay != null) graphicsOverlay.hide();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // 播片期间按键优先给视频层：任意键跳过，音量键仍交还系统。
        // BACK 不在此列——它沿用下面 tyn 自己的双击退出语义，
        // 避免播 OP 时误触退出游戏。
        if (event != null && videoOverlay != null && videoOverlay.isPlaying()) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP
                    || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE
                    || code == KeyEvent.KEYCODE_MUTE) {
                return super.dispatchKeyEvent(event);
            }
            // BACK 不在此处吞掉：落到下方既有的双击退出 / ESC 透传分支。
            // 交给 super 会绕过 ONScripter 自身的本地处理——鼠标侧键 BACK 不被静默消费，
            // 普通 BACK 首按也不会透传 ESC 给游戏。
            if (code != KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) videoOverlay.skipByKey();
                return true;
            }
        }
        // BACK 首按透传 ESC 给 ONS（游戏内取消/右键语义），2 秒内双击真正退出；
        // ESC 的 down+up 在 DOWN 时成对发送，UP 一律吞掉，避免重复/悬空事件。
        // 鼠标来源的 BACK（侧键）静默消费：不透传 ESC、不计入双击退出
        // （与 libsdl3 链 SDLSurface 吞掉鼠标 BACK 的净行为一致）。
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if ((event.getSource() & InputDevice.SOURCE_MOUSE) != 0) return true;
            switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    if (event.getRepeatCount() == 0) {
                        if (DoubleBackExit.shouldExit(this)) finish();
                        else sendEscToOns();
                    }
                    return true;
                default:
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        // 无视图消费 BACK 时的兜底路径：只布防退出窗口，不向游戏透传
        if (DoubleBackExit.shouldExit(this)) finish();
    }

    @Override protected void exitFromBack() {
        // 基类旧路径（dispatchBackKey/预测回调）的兜底：真正退出而非发 ESC
        finish();
    }

    private void sendEscToOns() {
        Log.d(TAG, "send ESC to ONS");
        try {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
        } catch (Throwable t) {
            Log.w(TAG, "send ESC failed", t);
        }
    }

    public int getFD(byte[] pathbyte, int mode) {
        String utf8Path = null;
        String gbkPath = null;
        File file = null;
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            utf8Path = decodePath(pathbyte);
            gbkPath = decodePath(pathbyte, "GBK");
            file = resolveGameFile(utf8Path);
            if (file == null) return -1;
            if (mode != 0) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
            }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, mode == 0 ? ParcelFileDescriptor.MODE_READ_ONLY : (ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE));
            int fd = pfd.detachFd();
            Log.i(TAG, "getFD fd=" + fd + " mode=" + mode + " file=" + file);
            return fd;
        } catch (FileNotFoundException expectedMiss) {
            // ONS probes many optional archive/script/image names during startup.
            // A missing read target is normal and must not emit thousands of stack traces.
            if (mode != 0 || (file != null && file.exists())) {
                logGetFdFailure(pathbyte, mode, utf8Path, gbkPath, file);
                Log.w(TAG, "getFD open failed: " + expectedMiss.getMessage());
            }
            return -1;
        } catch (Throwable t) {
            logGetFdFailure(pathbyte, mode, utf8Path, gbkPath, file);
            Log.w(TAG, "getFD failed", t);
            return -1;
        }
    }

    public int mkdir(byte[] pathbyte) {
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            File f = resolveGameFile(decodePath(pathbyte));
            return f != null && (f.exists() || f.mkdirs()) ? 0 : -1;
        } catch (Throwable t) {
            Log.w(TAG, "mkdir failed", t);
            return -1;
        }
    }

    public void playVideo(byte[] pathbyte) {
        if (pathbyte == null) return;
        String path = decodePath(pathbyte);
        Log.i(TAG, "playVideo " + path);
        try {
            File file = resolveVideoFile(path);
            if (file == null || !file.exists()) {
                Log.w(TAG, "video not found: " + path);
                return;
            }
            final String real = file.getAbsolutePath();
            // native 侧从 SDL 线程调用本方法，视图操作必须切到主线程。
            runOnUiThread(() -> startVideoOverlay(real));
        } catch (Throwable t) {
            Log.e(TAG, "playVideo failed", t);
        }
    }

    /**
     * 在本 Activity 窗口内覆盖播放，不启动新 Activity。
     *
     * 为什么不用独立 Activity：本类是 singleInstance + 独立 taskAffinity，
     * 拉起别的 Activity 会引发 task 切换，实测导致 SDL 收到 onStop()，
     * 且视频结束后返回的是启动页而非游戏本身。
     */
    private void startVideoOverlay(String realPath) {
        if (isFinishing() || isDestroyed()) return;
        if (videoOverlay == null) videoOverlay = new OnsVideoOverlay(this);
        boolean ok = videoOverlay.play(realPath, true);
        if (ok) armFilterStartupTimeout();
        else Log.w(TAG, "overlay refused to play: " + realPath);
    }

    public void playVideo(Uri uri) {
        if (uri == null) return;
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "playVideo(uri) has no path: " + uri);
            return;
        }
        final String real = path;
        runOnUiThread(() -> startVideoOverlay(real));
    }

    public void testVideo() {
        File file = resolveVideoFile("test.mp4");
        if (file != null && file.exists()) playVideo(Uri.fromFile(file));
    }

    private void setupVirtualControls() {
        try {
            controlsVisible = getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE).getBoolean(KEY_OVERLAY_VISIBLE, true);
            onsOverlay = new FrameLayout(this);
            onsOverlay.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            onsOverlay.setClickable(false);
            onsOverlay.setFocusable(false);

            leftControls = buildControlColumn();
            rightControls = null;
            FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            leftLp.leftMargin = dp(10);
            onsOverlay.addView(leftControls, leftLp);

            toggleButton = makeToggleButton();
            FrameLayout.LayoutParams toggleLp = new FrameLayout.LayoutParams(dp(TOGGLE_BUTTON_SIZE_DP), dp(TOGGLE_BUTTON_SIZE_DP), Gravity.END | Gravity.TOP);
            toggleLp.topMargin = dp(12);
            toggleLp.rightMargin = dp(12);
            onsOverlay.addView(toggleButton, toggleLp);

            addContentView(onsOverlay, new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
            applyVirtualControlsVisibility();
        } catch (Throwable t) {
            Log.w(TAG, "setupVirtualControls failed", t);
        }
    }

    private void toggleVirtualControls() {
        controlsVisible = !controlsVisible;
        getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY_VISIBLE, controlsVisible).apply();
        applyVirtualControlsVisibility();
    }

    private void applyVirtualControlsVisibility() {
        int vis = controlsVisible ? View.VISIBLE : View.GONE;
        if (leftControls != null) leftControls.setVisibility(vis);
        if (rightControls != null) rightControls.setVisibility(vis);
        if (toggleButton != null) toggleButton.setImageResource(
                controlsVisible ? R.drawable.ons_toggle_up : R.drawable.ons_toggle_down);
    }

    private LinearLayout buildControlColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int[] keys = new int[]{
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_A,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_SPACE
        };
        String[] labels = new String[]{
                "ESC",
                "SKIP",
                "AUTO",
                "MENU",
                "OK",
                "NEXT"
        };
        for (int i = 0; i < keys.length; i++) {
            TextView button = makeActionButton(labels[i], keys[i], false);
            if (labels[i].contains("AUTO")) autoButtons.add(button);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CONTROL_BUTTON_SIZE_DP), dp(CONTROL_BUTTON_SIZE_DP));
            lp.topMargin = dp(CONTROL_BUTTON_GAP_DP);
            lp.bottomMargin = dp(CONTROL_BUTTON_GAP_DP);
            column.addView(button, lp);
        }
        return column;
    }

    private void updateAutoButtons() {
        for (TextView b : autoButtons) {
            if (b == null) continue;
            styleVirtualButton(b, autoMode, false);
            b.setText("AUTO");
        }
    }

    private void styleVirtualButton(TextView tv, boolean active, boolean toggle) {
        tv.setBackground(makeVirtualButtonBackground(active, toggle));
        tv.setTextColor(Color.WHITE);
    }

    private GradientDrawable makeVirtualButtonBackground(boolean active, boolean toggle) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(toggle ? 11 : 10));
        if (active) {
            bg.setColor(Color.argb(210, 0, 122, 255));
            bg.setStroke(dp(2), Color.argb(230, 255, 255, 255));
        } else {
            bg.setColor(Color.argb(166, 16, 16, 16));
            bg.setStroke(dp(1), Color.argb(135, 255, 255, 255));
        }
        return bg;
    }

    @SuppressLint("AppCompatCustomView") // SDLActivity is a platform Activity; its lightweight overlay intentionally uses platform widgets.
    private TextView makeActionButton(String label, int keyCode, boolean toggle) {
        TextView tv = new TextView(this) {
            @Override
            public boolean performClick() {
                return super.performClick();
            }
        };
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(toggle ? 16 : 9);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setLines(toggle ? 1 : 2);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(0f, 0.92f);
        tv.setTag(label.contains("AUTO") ? "auto" : "normal");
        styleVirtualButton(tv, label.contains("AUTO") && autoMode, toggle);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                toggle ? dp(TOGGLE_BUTTON_SIZE_DP) : dp(CONTROL_BUTTON_SIZE_DP),
                toggle ? dp(TOGGLE_BUTTON_SIZE_DP) : dp(CONTROL_BUTTON_SIZE_DP));
        tv.setLayoutParams(lp);
        tv.setPadding(dp(1), dp(3), dp(1), dp(3));
        final boolean[] touchActivation = {false};
        tv.setOnClickListener(v -> {
            if (touchActivation[0]) return;
            if (toggle) {
                toggleVirtualControls();
            } else {
                SDLActivity.onNativeKeyDown(keyCode);
                SDLActivity.onNativeKeyUp(keyCode);
                if ("auto".equals(v.getTag())) {
                    autoMode = !autoMode;
                    updateAutoButtons();
                }
            }
        });
        tv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.65f);
                if (!toggle) {
                    SDLActivity.onNativeKeyDown(keyCode);
                }
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                if (toggle) {
                    toggleVirtualControls();
                } else {
                    SDLActivity.onNativeKeyUp(keyCode);
                    if ("auto".equals(v.getTag())) {
                        autoMode = !autoMode;
                        updateAutoButtons();
                    }
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    touchActivation[0] = true;
                    try {
                        v.performClick();
                    } finally {
                        touchActivation[0] = false;
                    }
                }
                return true;
            }
            return true;
        });
        return tv;
    }

    @SuppressLint("AppCompatCustomView") // SDLActivity is a platform Activity; its lightweight overlay intentionally uses platform widgets.
    private ImageButton makeToggleButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(controlsVisible ? R.drawable.ons_toggle_up : R.drawable.ons_toggle_down);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(makeVirtualButtonBackground(false, true));
        button.setColorFilter(Color.WHITE);
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        button.setLayoutParams(new FrameLayout.LayoutParams(dp(TOGGLE_BUTTON_SIZE_DP), dp(TOGGLE_BUTTON_SIZE_DP)));
        button.setOnClickListener(v -> toggleVirtualControls());
        button.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.65f);
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    v.performClick();
                }
                return true;
            }
            return true;
        });
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ensureDefaultFont() {
        if (gameRoot == null || gameRoot.isEmpty()) return;
        try {
            File fallbackFont = new File(gameRoot, "default.ttf");
            if (fallbackFont.exists()) return;
            File builtin = new File(getFilesDir(), "DroidSansFallback.ttf");
            if (!builtin.exists()) return;
            File parent = fallbackFont.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copyFile(builtin, fallbackFont);
            Log.i(TAG, "default.ttf fallback installed: " + fallbackFont);
        } catch (Throwable t) {
            Log.w(TAG, "install default.ttf fallback failed", t);
        }
    }

    private File resolveVideoFile(String raw) {
        File file = resolveGameFile(raw);
        if (file == null) return null;
        if (file.exists()) return file;
        File mp4 = replaceExtension(file, ".mp4");
        if (mp4.exists()) return mp4;
        File ci = findFileIgnoreCase(file);
        if (ci != null && ci.exists()) return ci;
        File ciMp4 = findFileIgnoreCase(mp4);
        return ciMp4 != null ? ciMp4 : file;
    }

    private File resolveGameFile(String raw) {
        if (raw == null) return null;
        String path = raw.replace('\\', '/').trim();
        if (path.startsWith("file://")) path = path.substring("file://".length());
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(gameRoot, path);
        File ci = findFileIgnoreCase(file);
        return ci != null ? ci : file;
    }

    private File findFileIgnoreCase(File file) {
        if (file == null || file.exists()) return file;
        File parent = file.getParentFile();
        if (parent == null) return null;
        File fixedParent = parent.exists() ? parent : findFileIgnoreCase(parent);
        if (fixedParent == null || !fixedParent.isDirectory()) return null;
        File[] list = fixedParent.listFiles();
        if (list == null) return null;
        String wanted = file.getName();
        for (File f : list) {
            if (f.getName().equalsIgnoreCase(wanted)) return f;
        }
        return null;
    }

    private File replaceExtension(File file, String ext) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        File parent = file.getParentFile();
        return new File(parent == null ? new File(".") : parent, base + ext);
    }

    @Override
    @SuppressLint("MissingSuperCall")
    public void onDestroy() {
        cancelFilterStartupTimeout();
        cancelOnsOutputPoll();
        cancelProxyResizeWorker();
        if (startupController != null) {
            startupController.dispose();
            startupController = null;
        }
        if (graphicsOverlay != null) {
            graphicsOverlay.hide();
            graphicsOverlay = null;
        }
        // 先摘除 TextureView（同步销毁回调归还 UI 引用与租约），再释放代理与剩余租约：
        // 反过来会让系统在 detach 时二次 release 同一 SurfaceTexture。
        if (filterView != null) {
            if (mLayout != null) mLayout.removeView(filterView);
            filterView.setOnTouchListener(null);
            filterView.setOnGenericMotionListener(null);
            filterView.setSurfaceTextureListener(null);
            filterView = null;
        }
        if (graphicsProxy != null) {
            graphicsProxy.release();
            graphicsProxy = null;
        }
        if (displayWindow != null) {
            displayWindow.release();
            displayWindow = null;
        }
        // 先收掉视频覆盖层：ijk 的 Surface/播放器必须在本进程被杀之前释放，
        // 否则 HWUI 渲染线程会操作已销毁的 SurfaceTexture，退出游戏时崩溃
        // （FORTIFY: pthread_mutex_lock called on a destroyed mutex / hwuiTask1 SIGABRT）。
        if (videoOverlay != null) {
            videoOverlay.dismiss();
            videoOverlay = null;
        }
        // ONS runs in a dedicated process. SDL native teardown can destroy
        // graphics mutexes while an OEM HWUI worker still references them.
        Log.i(TAG, "terminate dedicated ONS process before SDL/HWUI teardown");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean isFilterMode() {
        // graphicsProxy 是 volatile 且 UI 线程会置 null：必须快照到局部变量，
        // 否则两次读取之间被置空会让 SDL native 线程在 getNativeSurface 里撞 NPE。
        OnsGraphicsProxy proxy = graphicsProxy;
        return filterMode && proxy != null && !proxy.hasFailed();
    }

    @Override
    public int getLogicalWidth() {
        int w = 0;
        try { w = nativeGetWidth(); } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {}
        if (w > 0) return w;
        return detectedStageWidth > 0 ? detectedStageWidth : OnsScriptResolution.DEFAULT_WIDTH;
    }

    @Override
    public int getLogicalHeight() {
        int h = 0;
        try { h = nativeGetHeight(); } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {}
        if (h > 0) return h;
        return detectedStageHeight > 0 ? detectedStageHeight : OnsScriptResolution.DEFAULT_HEIGHT;
    }

    @Override
    public Surface provideProxySurface(Surface fallback) {
        // 与 isFilterMode 同因：SDL native 线程调用本方法，volatile 字段必须快照一次。
        OnsGraphicsProxy proxy = graphicsProxy;
        if (!filterMode || proxy == null || proxy.hasFailed()) return fallback;
        int w;
        int h;
        try {
            w = nativeGetWidth();
            h = nativeGetHeight();
        } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
            Log.w(TAG, "Failed to query native game resolution", t);
            w = 0;
            h = 0;
        }
        if (w <= 0 || h <= 0) {
            w = getLogicalWidth();
            h = getLogicalHeight();
        }
        detectedStageWidth = w;
        detectedStageHeight = h;

        try {
            Surface s = proxy.getProducerSurface(w, h);
            if (s != null && s.isValid()) {
                OnsSurface surface = onsSurface;
                if (surface != null) {
                    surface.applyScreenResolution(w, h, false);
                }
                // 引擎在 surfaceChanged 期间以 640×480（detect 默认值）建窗，真实脚本尺寸
                // （如 800×450）要到此处才知道；SDL 需要连续多次 onNativeResize 才会把窗口尺寸
                // 收敛到新逻辑尺寸，避免停在旧尺寸导致滤镜触控按物理/逻辑比例错位。
                // 按逻辑尺寸与收敛状态去重，由单个串行 worker 执行。
                postProxyResizeWorker(w, h);
                startOnsOutputPoll();
                return s;
            }
            Log.w(TAG, "Proxy producer surface unavailable; falling back to direct");
            fallbackToDirect();
        } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
            Log.w(TAG, "Failed to get proxy producer surface", t);
            fallbackToDirect();
        }
        return fallback;
    }

    /** 滤镜输出看门狗 + 自愈轮询。producer 交给 SDL 后启动；健康期转为低频巡检
     *  （中期断连时重挂），持续未呈现超时后回退引擎直出。武装动作经 uiHandler
     *  投递并在主线程内重查 uiResumed，避免暂停期间误武装。
     *
     *  <p>判据是「输出面在且已呈现过」：只看 {@code hasOutput()} 会把「接上了却
     *  一帧不出」（代理建面或归一化失败、脚本停摆）当成健康，加载层于是永不揭开。
     *  但 ONS 是事件驱动引擎——静止画面（等待点击、长文本）可以长时间不产生新帧，
     *  所以呈现期限只用于「首帧之前」：首帧一旦呈现，健康判据退回「输出面还在」，
     *  否则静止场景会被看门狗误判成失败并丢掉滤镜。</p>
     *
     *  计时口径：基线在「stage 就绪」之后起表，慢启动（大脚本解析、OP 视频、低端机
     *  布局）不被静默判成失败；健康态与 stage 未就绪态都会把基线清零，因此稳态下的
     *  瞬时断连从断连那一刻重新起表，1Hz 自愈才有意义。 */
    private void startOnsOutputPoll() {
        if (!filterMode || graphicsProxy == null) return;
        uiHandler.post(() -> {
            if (!filterMode || graphicsProxy == null || !uiResumed) return;
            uiHandler.removeCallbacks(onsOutputPoll);
            uiHandler.postDelayed(onsOutputPoll, ONS_OUTPUT_POLL_MS);
        });
    }

    private void cancelOnsOutputPoll() {
        uiHandler.removeCallbacks(onsOutputPoll);
        onsUnhealthySinceMs = 0;
    }

    /** 立即重挂输出面（resume 用），不等轮询 tick。尺寸未布局时不白 attach。 */
    private void attachOnsOutputNow() {
        if (graphicsProxy == null || displayWindow == null || filterView == null) return;
        int w = filterView.getWidth();
        int h = filterView.getHeight();
        if (w > 1 && h > 1) graphicsProxy.attachOutput(displayWindow, w, h);
    }

    private void onsOutputPoll() {
        if (!filterMode || !uiResumed || isFinishing() || isDestroyed()
                || graphicsProxy == null || graphicsProxy.hasFailed() || graphicsProxy.isClosed()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // OP 视频播放期间引擎不产帧（画面由视频覆盖层呈现）：不计时也不重挂，
        // 否则超过 10s 的片头会被看门狗误判并丢掉滤镜。视频结束后重新起表。
        if (videoOverlay != null && videoOverlay.isPlaying()) {
            onsUnhealthySinceMs = 0;
            uiHandler.postDelayed(onsOutputPoll, ONS_OUTPUT_POLL_MS);
            return;
        }
        // stage 未就绪（脚本解析、OP 视频、布局未完成）时没有可比对的输出面；清零基线
        // 让期限从「stage 就绪那一刻」起算，而不是从武装时刻，慢启动不被判成失败。
        if (!graphicsProxy.isStageConfigured()) {
            onsUnhealthySinceMs = 0;
            uiHandler.postDelayed(onsOutputPoll, ONS_OUTPUT_POLL_MS);
            return;
        }
        if (graphicsProxy.hasOutput() && graphicsProxy.hasPresented()) {
            // 健康：输出面在且已呈现过。清零基线，使下一次瞬时断连从断连时刻重新起表，
            // 而不是沿用很久以前的时间戳（否则稳态断连会在首个 tick 立即回退）。
            onsUnhealthySinceMs = 0;
            uiHandler.postDelayed(onsOutputPoll, ONS_HEALTH_POLL_MS);
            return;
        }
        // 非健康：首帧未呈现，或呈现后输出面断开。进入本态的首个 tick 起表。
        if (onsUnhealthySinceMs == 0) onsUnhealthySinceMs = now;
        if (now - onsUnhealthySinceMs >= ONS_OUTPUT_TIMEOUT_MS) {
            Log.w(TAG, "ONS filter output not presented within the watchdog window; falling back to direct");
            fallbackToDirect();
            return;
        }
        // 输出面不在才重挂；首帧前与断连后的轮询都保持 1Hz，尽快自愈。
        if (!graphicsProxy.hasOutput()) attachOnsOutputNow();
        uiHandler.postDelayed(onsOutputPoll, ONS_OUTPUT_POLL_MS);
    }

    /**
     * 有界重配 SDL 窗口尺寸。
     *
     * <p>surfaceChanged 以 detect 猜测值（640×480）建窗，真实的脚本逻辑尺寸要等
     * {@link #provideProxySurface} 才能拿到；SDL 不会一次 resize 就采纳新尺寸，
     * 需要连续若干次 {@code onNativeResize} 才收敛。跳过该重试会让窗口停在
     * 旧逻辑尺寸，滤镜触控按物理/逻辑比例整体错位。</p>
     *
     * <p>只在逻辑尺寸变化或尚未收敛时触发一轮；同一时间只有一个 daemon worker，重复请求
     * 只更新目标并让运行中的 worker 再接一轮，不再叠加线程，也不再让 UI 线程与 SDL
     * 线程各自连打 20 次。</p>
     */
    private void postProxyResizeWorker(int width, int height) {
        if (width <= 0 || height <= 0) return;
        synchronized (proxyResizeLock) {
            if (proxyResizeTargetWidth == width && proxyResizeTargetHeight == height
                    && (proxyResizeConverged || proxyResizeRunning)) {
                return;
            }
            proxyResizeTargetWidth = width;
            proxyResizeTargetHeight = height;
            proxyResizeConverged = false;
            proxyResizeGeneration++;
            // 分屏 paused-but-visible 冷启动时先记下未收敛目标，待 onResume() 补跑一轮。
            if (!filterMode || !uiResumed) return;
            if (proxyResizeRunning) return;
            proxyResizeRunning = true;
        }
        startProxyResizeThread();
    }

    /** 在分屏 paused-but-visible 冷启动或短暂 onPause() 取消后，回前台补跑未收敛的逻辑尺寸。 */
    private void resumeProxyResizeIfNeeded() {
        synchronized (proxyResizeLock) {
            if (!filterMode || !uiResumed || proxyResizeConverged || proxyResizeRunning
                    || proxyResizeTargetWidth <= 0 || proxyResizeTargetHeight <= 0) {
                return;
            }
            proxyResizeGeneration++;
            proxyResizeRunning = true;
        }
        startProxyResizeThread();
    }

    private void startProxyResizeThread() {
        Thread worker = new Thread(() -> {
            while (true) {
                int generation;
                synchronized (proxyResizeLock) {
                    if (!filterMode || !uiResumed
                            || proxyResizeTargetWidth <= 0 || proxyResizeTargetHeight <= 0) {
                        proxyResizeRunning = false;
                        return;
                    }
                    generation = proxyResizeGeneration;
                }
                for (int i = 0; i < PROXY_RESIZE_ATTEMPTS; i++) {
                    try {
                        Thread.sleep(PROXY_RESIZE_INTERVAL_MS);
                    } catch (InterruptedException ignored) {
                        synchronized (proxyResizeLock) {
                            proxyResizeRunning = false;
                            proxyResizeConverged = false;
                        }
                        return;
                    }
                    synchronized (proxyResizeLock) {
                        // 有更新的一轮在等：让给它，避免两轮各打满 20 次。
                        if (proxyResizeGeneration != generation) break;
                        // 回退 / 暂停 / 销毁之后不得再向 SDL 连打 resize：此时 SDL 已按物理
                        // 窗口尺寸恢复或宿主要暂停，逻辑尺寸的重复下发会把画面与触点推偏。
                        if (!filterMode || !uiResumed) {
                            proxyResizeRunning = false;
                            return;
                        }
                    }
                    try {
                        SDLActivity.onNativeResize();
                    } catch (RuntimeException | LinkageError | OutOfMemoryError ignored) {}
                }
                synchronized (proxyResizeLock) {
                    if (proxyResizeGeneration == generation) {
                        proxyResizeConverged = filterMode && uiResumed
                                && proxyResizeTargetWidth > 0 && proxyResizeTargetHeight > 0;
                        proxyResizeRunning = false;
                        return;
                    }
                }
            }
        }, "OnsProxyResizeWorker");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | OutOfMemoryError failure) {
            // start 失败（OOM 等）必须无条件复位运行与收敛标记并保留目标尺寸，
            // 以便下次提供面或 onResume() 可重新起一轮。
            synchronized (proxyResizeLock) {
                proxyResizeRunning = false;
                proxyResizeConverged = false;
            }
            Log.w(TAG, "Failed to start proxy resize worker", failure);
        }
    }

    /** 暂停时作废在途 resize 轮次，但保留未收敛目标供 onResume() 补跑。 */
    private void pauseProxyResizeWorker() {
        synchronized (proxyResizeLock) {
            proxyResizeGeneration++;
        }
    }

    /** 回退 / 销毁时作废在途 resize 轮次并清零目标；worker 会在下一次循环检查时自行退出。 */
    private void cancelProxyResizeWorker() {
        synchronized (proxyResizeLock) {
            proxyResizeGeneration++;
            proxyResizeTargetWidth = 0;
            proxyResizeTargetHeight = 0;
            proxyResizeConverged = false;
        }
    }

    private void fallbackToDirect() {
        runOnUiThread(() -> {
            if (!filterMode) return;
            filterMode = false;
            cancelOnsOutputPoll();
            cancelProxyResizeWorker();
            if (graphicsOverlay != null) {
                graphicsOverlay.hide();
                graphicsOverlay = null;
            }
            // 先让 SDL 停止使用代理 producer，再释放代理 —— 否则 SDL 线程仍绑在
            // 即将销毁的 ANativeWindow 上，下一次 eglSwapBuffers 会命中已回收的 BufferQueue。
            // 顺序：摘除视图（同步触发销毁回调归还 UI 引用并释放租约）→ 通知 SDL 重新
            // 取 native surface → 最后释放代理与剩余租约。
            if (filterView != null) {
                // 与 Framebuffer switchToClassicPresentation 同口径：先 removeView 走正常
                // 销毁回调（displayWindow 由租约释放、回调返回 false 不交系统二次释放），
                // 再摘监听；只 setVisibility(GONE) 会让租约与 TextureView 双重持有同一纹理。
                if (mLayout != null) mLayout.removeView(filterView);
                filterView.setOnTouchListener(null);
                filterView.setOnGenericMotionListener(null);
                filterView.setSurfaceTextureListener(null);
                filterView = null;
            }
            filterTouchActive = false;
            filterMouseActive = false;
            filterTouchOutsideStage = false;
            try {
                SDLActivity.onNativeSurfaceDestroyed();
            } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
                Log.w(TAG, "Failed to notify SDL of direct surface fallback", t);
            }
            // 严格对齐 SDLSurface.surfaceChanged() 时序：mWidth/mHeight →
            // nativeSetScreenResolution → onNativeResize → onNativeSurfaceChanged。
            // 滤镜路径把 mWidth/mHeight 与 native 逻辑分辨率都设成脚本尺寸，而回退不会再走
            // SDLSurface.surfaceChanged（父类唯一写物理值处），必须在这两次通知之间写回
            // 物理窗口尺寸，否则 SDL 重取 native surface 时拿到的还是脚本尺寸。
            restoreDirectGeometry();
            try {
                SDLActivity.onNativeSurfaceChanged();
            } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
                Log.w(TAG, "Failed to notify SDL of direct surface fallback", t);
            }
            if (graphicsProxy != null) {
                graphicsProxy.release();
                graphicsProxy = null;
            }
            if (displayWindow != null) {
                displayWindow.release();
                displayWindow = null;
            }
            displayTexture = null;
            // 回退后加载层必须揭开——经典直出已经有画面（或至少要给用户可操作的
            // 界面），不能停在永久 LOADING，更不能钉死不可关闭的「启动失败」面板。
            revealStartupOverlay();
        });
    }

    /** 把触点归一化分母与 SDL 逻辑分辨率从脚本尺寸恢复为物理窗口尺寸。 */
    private void restoreDirectGeometry() {
        OnsSurface surface = onsSurface;
        if (surface != null) {
            surface.restoreDirectGeometry();
        }
    }

    @Override
    public void onOutputReady(OnsGraphicsProxy proxy) {
        Log.i(TAG, "OnsGraphicsProxy output ready");
    }

    @Override
    public void onFirstFrame(OnsGraphicsProxy proxy) {
        // 滤镜路径唯一的揭层信号：首帧真正呈现后才撤掉加载层。
        if (isFinishing() || isDestroyed()) return;
        revealStartupOverlay();
    }

    @Override
    public void onFailure(OnsGraphicsProxy proxy, RuntimeException failure) {
        Log.w(TAG, "OnsGraphicsProxy failure reported", failure);
        // 回退本身会揭开加载层；只有「滤镜与直出都救不回」才走 fail() 终端失败
        // （fail() 是不可关闭的全屏面板），与 Framebuffer 的 reveal 收尾对齐。
        fallbackToDirect();
    }

    private static boolean isMouseDevice(MotionEvent motionEvent) {
        return OnsInput.isMouseDevice(motionEvent);
    }

    private boolean onFilterTouch(View v, MotionEvent motionEvent) {
        if (motionEvent == null) return true;
        OnsViewport vp = graphicsProxy != null ? graphicsProxy.viewport() : null;
        int actionMasked = motionEvent.getActionMasked();
        int deviceId = motionEvent.getDeviceId();
        if (deviceId < 0) deviceId--;
        int pointerCount = motionEvent.getPointerCount();
        if (vp == null || vp.width <= 0 || vp.height <= 0) {
            // 视口未就绪（首帧前 / 重建窗口）时不能把整段手势静默放过：
            // ① 新 DOWN 记为黑边起手，避免中途视口就绪后收到无 DOWN 的 MOVE/UP；
            // ② 若之前已在视口内按下（拖拽中途窗口重挂导致视口短暂为空），收到 UP/CANCEL
            //    必须向 SDL 补发抬起，避免按键或触点永久粘滞。
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                filterTouchOutsideStage = true;
                filterTouchActive = false;
                filterMouseActive = false;
            } else if (filterMouseActive
                    && (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL)) {
                filterMouseActive = false;
                filterTouchOutsideStage = false;
                sendFilteredMouse(lastFilterMouseButton, MotionEvent.ACTION_UP, lastFilterMouseX, lastFilterMouseY);
            } else if (filterTouchActive) {
                if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                    filterTouchActive = false;
                    filterTouchOutsideStage = false;
                    for (int i = 0; i < pointerCount; i++) {
                        sendSyntheticTouchUp(deviceId, motionEvent, i, MotionEvent.ACTION_UP);
                    }
                } else if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    int pointerIndex = motionEvent.getActionIndex();
                    sendSyntheticTouchUp(deviceId, motionEvent, pointerIndex, MotionEvent.ACTION_POINTER_UP);
                }
            } else if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                filterTouchOutsideStage = false;
            }
            return true;
        }

        // 信箱外起手的手势整段吞掉（含 MOVE/UP），否则纯黑边上会照常推文本 /
        // 误触发菜单，引擎还会收到「没有 DOWN 的 MOVE/UP」。
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            filterTouchOutsideStage = OnsInput.startsOutsideStage(vp, motionEvent);
            if (filterTouchOutsideStage) return true;
        } else if (filterTouchOutsideStage) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                filterTouchOutsideStage = false;
            }
            return true;
        }

        // 鼠标处理：若为真实鼠标设备，将触控点直接投影为游戏舞台窗口绝对像素坐标
        if (isMouseDevice(motionEvent)) {
            if (pointerCount <= 0) return true;
            float rawX = motionEvent.getX();
            float rawY = motionEvent.getY();
            int buttonState = 1;
            try {
                int b = motionEvent.getButtonState();
                if (b != 0) buttonState = b;
            } catch (RuntimeException ignored) {}
            float stageX = (float) vp.sourceX(rawX);
            float stageY = (float) vp.sourceY(rawY);
            lastFilterMouseX = stageX;
            lastFilterMouseY = stageY;
            lastFilterMouseButton = buttonState;
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                filterMouseActive = true;
            } else if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                filterMouseActive = false;
            }
            int mouseAction = actionMasked == MotionEvent.ACTION_CANCEL ? MotionEvent.ACTION_UP : actionMasked;
            sendFilteredMouse(buttonState, mouseAction, stageX, stageY);
            return true;
        }

        // 触控处理：将物理视图坐标归一化到等比视口 [0.0, 1.0] 并无状态派发给 SDL
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            filterTouchActive = true;
        } else if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            filterTouchActive = false;
        }
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            int pointerIndex = motionEvent.getActionIndex();
            if (pointerIndex < 0 || pointerIndex >= pointerCount) return true;
            sendFilteredTouch(deviceId, motionEvent, pointerIndex, actionMasked, vp);
        } else if (actionMasked == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < pointerCount; i++) {
                sendFilteredTouch(deviceId, motionEvent, i, actionMasked, vp);
            }
        } else if (actionMasked == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < pointerCount; i++) {
                sendFilteredTouch(deviceId, motionEvent, i, MotionEvent.ACTION_UP, vp);
            }
        }
        return true;
    }

    private boolean onFilterGenericMotion(View v, MotionEvent motionEvent) {
        if (motionEvent == null) return false;
        OnsViewport vp = graphicsProxy != null ? graphicsProxy.viewport() : null;
        if (vp == null || vp.width <= 0 || vp.height <= 0) return false;
        if (isMouseDevice(motionEvent)) {
            if (motionEvent.getPointerCount() <= 0) return false;
            int actionMasked = motionEvent.getActionMasked();
            if (actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                float rawX = motionEvent.getX(0);
                float rawY = motionEvent.getY(0);
                float stageX = (float) vp.sourceX(rawX);
                float stageY = (float) vp.sourceY(rawY);
                sendFilteredMouse(0, actionMasked, stageX, stageY);
                return true;
            } else if (actionMasked == MotionEvent.ACTION_SCROLL) {
                float vScroll = OnsInput.sanitizeScrollAxis(motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL, 0));
                float hScroll = OnsInput.sanitizeScrollAxis(motionEvent.getAxisValue(MotionEvent.AXIS_HSCROLL, 0));
                sendFilteredMouse(0, actionMasked, hScroll, vScroll);
                return true;
            }
        }
        return false;
    }

    private void sendFilteredMouse(int buttonState, int action, float x, float y) {
        try {
            SDLActivity.onNativeMouse(buttonState, action, x, y, false);
        } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
            Log.w(TAG, "drop invalid filtered mouse event", t);
        }
    }

    private void sendSyntheticTouchUp(int deviceId, MotionEvent event, int pointerIndex, int action) {
        try {
            if (event == null || pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
            SDLActivity.onNativeTouch(
                    deviceId,
                    event.getPointerId(pointerIndex),
                    action,
                    lastFilterTouchNormX,
                    lastFilterTouchNormY,
                    0.0f
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
            Log.w(TAG, "drop invalid synthetic touch up", t);
        }
    }

    private void sendFilteredTouch(int deviceId, MotionEvent event, int pointerIndex, int action, OnsViewport vp) {
        try {
            if (event == null || pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return;
            float rawX = event.getX(pointerIndex);
            float rawY = event.getY(pointerIndex);
            float normX = OnsInput.normalizeX(vp, rawX);
            float normY = OnsInput.normalizeY(vp, rawY);
            lastFilterTouchNormX = normX;
            lastFilterTouchNormY = normY;
            float pressure = event.getPressure(pointerIndex);
            if (!Float.isFinite(pressure)) pressure = 1.0f;
            if (pressure > 1.0f) pressure = 1.0f;
            if (pressure < 0.0f) pressure = 0.0f;
            SDLActivity.onNativeTouch(
                    deviceId,
                    event.getPointerId(pointerIndex),
                    action,
                    normX,
                    normY,
                    pressure
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
            Log.w(TAG, "drop invalid filtered touch event", t);
        }
    }

    /**
     * 修复平板设备上画面不居中的问题
     * SDLActivity默认不设置SurfaceView居中，导致在平板(4:3/16:10)上运行16:9游戏时画面底部对齐
     */
    private void fixSurfaceCentering() {
        try {
            if (mLayout != null && mSurface != null) {
                // 获取当前的LayoutParams
                ViewGroup.LayoutParams lp = mSurface.getLayoutParams();
                if (lp instanceof RelativeLayout.LayoutParams) {
                    RelativeLayout.LayoutParams rlp = (RelativeLayout.LayoutParams) lp;
                    // 添加居中规则
                    rlp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                    mSurface.setLayoutParams(rlp);
                    Log.i(TAG, "Fixed surface centering for tablet display");
                } else {
                    // 如果不是RelativeLayout.LayoutParams，重新创建
                    RelativeLayout.LayoutParams newLp = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT);
                    newLp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                    mSurface.setLayoutParams(newLp);
                    Log.i(TAG, "Recreated layout params with centering for tablet display");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixSurfaceCentering failed", t);
        }
    }

    private void fullscreen() {
        Window window = getWindow();
        if (ignoreCutout && Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = window.getDecorView().getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        window.getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        EnginePlayerOrientation.applyLocked(this);
    }

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        EnginePlayerOrientation.applyWithSuper(this, super::setRequestedOrientation);
    }

    private String normalizeRootPath(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("file://")) v = v.substring("file://".length());
        if (v.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(v);
                String docId = null;
                // tree-document 混合 URI（tree/<treeId>/document/<docId>）与纯 document URI：
                // DocumentsContract.getDocumentId 只接受纯 document 形式，混合形式（游戏目录以
                // 子文档挂在游戏库 tree 下，如 tree/primary:lib/game/document/primary:lib/game/<dir>）
                // 会抛 IllegalArgumentException；此处取 /document/ 之后的编码段解码得完整子文档 id，
                // 避免回退 getTreeDocumentId 只取到 tree 根目录（Artemis 等严格按目录加载的引擎会因此崩溃）。
                String encodedPath = uri.getEncodedPath();
                if (encodedPath != null) {
                    int marker = encodedPath.indexOf("/document/");
                    if (marker >= 0) {
                        try { docId = Uri.decode(encodedPath.substring(marker + "/document/".length())); } catch (Throwable ignored) { }
                    }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = android.provider.DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { docId = null; }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = android.provider.DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { docId = null; }
                }
                String path = docIdToPath(docId);
                if (path != null) return path;
            } catch (Throwable ignored) { }
        }
        return v;
    }

    private String docIdToPath(String docId) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        String volume = colon >= 0 ? docId.substring(0, colon) : docId;
        String rel = colon >= 0 ? docId.substring(colon + 1) : "";
        // document IDs come from the provider and are untrusted: reject traversal.
        if (rel.indexOf('\\') >= 0 || java.util.Arrays.asList(rel.split("/")).contains("..")) return null;
        if ("primary".equalsIgnoreCase(volume)) return bridge.PrimaryStorageRoot.get() + (rel.isEmpty() ? "" : "/" + rel);
        if (volume != null && !volume.isEmpty()) return "/storage/" + volume + (rel.isEmpty() ? "" : "/" + rel);
        return null;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private String decodePath(byte[] pathbyte) {
        return new String(pathbyte, StandardCharsets.UTF_8).replace('\\', '/');
    }

    private String decodePath(byte[] pathbyte, String charsetName) {
        try {
            return new String(pathbyte, Charset.forName(charsetName)).replace('\\', '/');
        } catch (Throwable ignored) {
            return "<" + charsetName + " unavailable>";
        }
    }

    private void logGetFdFailure(byte[] pathbyte, int mode, String utf8Path, String gbkPath, File file) {
        try {
            File root = gameRoot == null ? null : new File(gameRoot);
            File parent = file == null ? null : file.getParentFile();
            boolean allFilesAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    || Environment.isExternalStorageManager();
            Log.w(TAG, "getFD diagnostic"
                    + " mode=" + mode
                    + " bytes=" + bytesToHex(pathbyte, 96)
                    + " utf8=" + printable(utf8Path)
                    + " gbk=" + printable(gbkPath)
                    + " resolved=" + printable(file == null ? null : file.getAbsolutePath())
                    + " exists=" + (file != null && file.exists())
                    + " readable=" + (file != null && file.canRead())
                    + " parentExists=" + (parent != null && parent.exists())
                    + " parentReadable=" + (parent != null && parent.canRead())
                    + " root=" + printable(gameRoot)
                    + " rootExists=" + (root != null && root.exists())
                    + " rootReadable=" + (root != null && root.canRead())
                    + " allFilesAccess=" + allFilesAccess);
        } catch (Throwable diagnosticError) {
            Log.w(TAG, "getFD diagnostic failed", diagnosticError);
        }
    }

    private String bytesToHex(byte[] bytes, int maxBytes) {
        if (bytes == null) return "null";
        int count = Math.min(bytes.length, Math.max(0, maxBytes));
        StringBuilder out = new StringBuilder(count * 2 + 16);
        for (int i = 0; i < count; i++) {
            int value = bytes[i] & 0xff;
            if (value < 16) out.append('0');
            out.append(Integer.toHexString(value));
        }
        if (bytes.length > count) out.append("…(").append(bytes.length).append(" bytes)");
        return out.toString();
    }

    private String printable(String value) {
        return value == null ? "<null>" : '"' + value.replace("\n", "\\n").replace("\r", "\\r") + '"';
    }

    private void copyFile(File from, File to) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
