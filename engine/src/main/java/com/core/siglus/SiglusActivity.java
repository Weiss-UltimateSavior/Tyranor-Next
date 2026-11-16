package com.core.siglus;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
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
import com.core.engine.EngineStartupOverlay;
import com.core.engine.EngineSurfaceReadyProbe;
import com.core.engine.R;
import com.core.engine.EngineUiText;
import com.core.engine.LaunchContract;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.ArtemisPerformanceOverlay;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * SiglusEngine（siglus_rs）宿主 Activity。
 *
 * <p>关闭滤镜时沿用 SurfaceView + 主线程同步驱动；开启有效滤镜链时使用 TextureView
 * 显示窗口租约、私有 producer 与独立引擎线程。两种模式共用输入、IME 和生命周期外壳。
 * 经 {@code siglus_android_*} C ABI 被逐步驱动。差异点：游戏路径/语言/回写键取自
 * {@link LaunchContract} extras，消息框复用 engine 既有文案资源，IME 与键事件按
 * TyranorNext 契约转发。
 */
public final class SiglusActivity extends AppCompatActivity implements
        TextureView.SurfaceTextureListener,
        SurfaceHolder.Callback,
        Choreographer.FrameCallback,
        View.OnTouchListener,
        SiglusTextInputView.Listener,
        NativeSiglus.MessageboxListener {

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

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

    private FrameLayout rootLayout;
    private View surfaceView;
    private boolean filterMode;
    private GraphicsFilterChain requestedChain;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceTexture displayTexture;
    private SiglusWindowLease displayWindow;
    private final Runnable capacityAvailable = this::acquireDisplayWindow;
    private SiglusTextInputView textInputView;
    private EngineStartupOverlay loadingOverlay;
    private EngineSurfaceReadyProbe startupProbe;

    private SiglusHost engine;
    private SiglusEngineThread filteredEngine;
    private SiglusDirectHost directEngine;
    private boolean engineReady, engineCreating, windowAvailable;
    private int surfaceGeneration;
    private long surfaceConnectionRequest;
    private boolean running = false;
    private volatile boolean surfaceReady = false;
    private long lastFrameNs = 0L;
    private boolean imeVisible = false;

    private String gameRoot;
    private String gameDirName;
    private String pathHash;

    private long lastBackMs = 0L;
    private boolean resumed, destroying, faulted, proxyPreparing, proxyAttached, proxyFailed;
    private boolean pointerActive;
    private double lastTouchX, lastTouchY;
    private SiglusGraphicsProxy graphicsProxy;
    private SiglusGraphicsProxy pendingGraphicsProxy;
    private ArtemisPerformanceOverlay graphicsOverlay;
    private final Set<Long> messageboxRequests = new HashSet<>();
    private Thread stageSizeWorker;
    private int stageSizeRequest;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestedChain = SiglusHostPolicy.requestedChain(
                getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false),
                getIntent().getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN));
        filterMode = requestedChain != null;
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(buildContentView());
        startupProbe = new EngineSurfaceReadyProbe(this, new EngineSurfaceReadyProbe.SurfaceProvider() {
            @Override public Surface currentSurface() {
                return !filterMode && surfaceView instanceof SurfaceView
                        ? ((SurfaceView) surfaceView).getHolder().getSurface() : null;
            }
            @Override public long currentGeneration() { return surfaceGeneration; }
        }, new EngineSurfaceReadyProbe.Listener() {
            @Override public void onReady(long generation) {
                if (!destroying && !filterMode && generation == surfaceGeneration) hideLoadingOverlay();
            }
            @Override public void onFailure(EngineSurfaceReadyProbe.Failure failure) {
                if (!destroying && !filterMode && loadingOverlay != null)
                    loadingOverlay.fail(null);
            }
        });

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

        // 运行库已由启动编排解压到应用私有目录；缺失说明安装包不完整或解压失败。
        if (!NativeSiglus.prepareEngineLibrary(this)) {
            Toast.makeText(this, uiString(R.string.engine_siglus_init_failed),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        NativeSiglus.setMessageboxListener(this);
        try {
            SiglusHost.Listener listener = new SiglusHost.Listener() {
                @Override public void onCreated(boolean success, boolean cancelled, int generation) {
                    engineCreating = false;
                    if (!success) {
                        Log.e(TAG, "Engine creation failed");
                        failInitialization();
                        return;
                    }
                    engineReady = true;
                    if (!filterMode) {
                        surfaceReady = windowAvailable && generation == surfaceGeneration;
                        writeBackGameTitleAsync();
                        startDirectStartupProbe();
                        maybeStartFrameLoop();
                        return;
                    }
                    graphicsProxy.activate();
                    attachDisplay();
                    prepareGraphics();
                    writeBackGameTitleAsync();
                    maybeStartFrameLoop();
                }
                @Override public void onConnected(long request, boolean success, int generation) {
                    if (!filterMode) {
                        // Direct JNI and its ACK complete inside the UI callback;
                        // there is no pending request-number assignment to race.
                        surfaceReady = success && windowAvailable && generation == surfaceGeneration;
                        if (!success) Toast.makeText(SiglusActivity.this, uiString(R.string.engine_launch_failed),
                                Toast.LENGTH_LONG).show();
                        startDirectStartupProbe();
                        maybeStartFrameLoop();
                    } else completeSurfaceConnection(request, success);
                }
                @Override public void onStepped(int status, boolean imeNeeded, int[] area) {
                    if (status > 0) { stopFrameLoop(); finish(); return; }
                    if (status < 0) { faulted = true; stopFrameLoop(); return; }
                    if (resumed) updateImeState(imeNeeded, area);
                }
                @Override public void onBlocked() {
                    // A native call may still own the private producer. Its
                    // admission is independently bounded; never reconnect it
                    // to a revoked UI window when merely reporting slow work.
                    Log.w(TAG, "Native owner is busy; presentation remains privately owned");
                    // Keep the UI clock: admission already bounds in-flight work,
                    // and normal completion resumes without a lifecycle callback.
                }
                @Override public void onFailure(RuntimeException failure) {
                    Log.e(TAG, "Engine owner operation failed", failure);
                    faulted = true;
                    stopFrameLoop();
                }
            };
            if (filterMode) {
                filteredEngine = new SiglusEngineThread(this, listener);
                engine = filteredEngine;
            } else {
                directEngine = new SiglusDirectHost(this, listener);
                engine = directEngine;
            }
        } catch (RuntimeException failure) {
            Log.e(TAG, "Engine owner unavailable", failure);
            finish();
            return;
        }

        if (filterMode) {
            SiglusWindowLease.observeCapacity(capacityAvailable);
            ((TextureView) surfaceView).setSurfaceTextureListener(this);
        } else ((SurfaceView) surfaceView).getHolder().addCallback(this);
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

        // Android 16 may retain this View through a SurfaceTexture frame-rate listener.
        // The display is unthemed; its Context must not keep the Activity alive.
        surfaceView = filterMode ? new TextureView(getApplicationContext()) : new SurfaceView(this);
        rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        textInputView = new SiglusTextInputView(this, this);
        textInputView.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(1, 1);
        inputParams.gravity = Gravity.TOP | Gravity.START;
        rootLayout.addView(textInputView, inputParams);

        loadingOverlay = new EngineStartupOverlay(this);
        loadingOverlay.attachTo(rootLayout);
        return rootLayout;
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay != null) loadingOverlay.reveal();
    }

    private void startDirectStartupProbe() {
        if (startupProbe == null) return;
        if (!filterMode && surfaceReady && resumed && hasWindowFocus()) startupProbe.start();
        else startupProbe.stop();
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
        applyImmersive();
        startDirectStartupProbe();
        if (filterMode) {
            acquireDisplayWindow();
            attachDisplay();
        }
        maybeStartFrameLoop();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (startupProbe != null) startupProbe.stop();
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
        if (filterMode) SiglusWindowLease.stopObservingCapacity(capacityAvailable);
        stageSizeRequest++;
        if (stageSizeWorker != null) { stageSizeWorker.interrupt(); stageSizeWorker = null; }
        stopFrameLoop();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        hideIme();
        NativeSiglus.setMessageboxListener(null);
        if (filterMode) detachFilteredDisplay();
        if (startupProbe != null) startupProbe.dispose();
        if (loadingOverlay != null) loadingOverlay.dispose();
        destroyEngine(); // Native producer destruction precedes consumer disposal.
        super.onDestroy();
    }

    private void detachFilteredDisplay() {
        TextureView display = (TextureView) surfaceView;
        // Keep the callback until normal detach returns the UI lease. Removing
        // the child also clears its Activity-owned parent and window AttachInfo.
        rootLayout.removeView(display);
        display.setSurfaceTextureListener(null);
        display.setOnTouchListener(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) display.setContentCaptureSession(null);
        // Do not release the texture: pending/retained EGL work still owns its lease.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        startDirectStartupProbe();
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
                if (engineReady) {
                    forwardKey(VK_ESCAPE, null, false);
                }
                Toast.makeText(SiglusActivity.this,
                        uiString(R.string.engine_siglus_back_again_to_exit),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- Original direct SurfaceView path ----------

    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (filterMode || destroying || directEngine == null) return;
        surfaceGeneration++;
        windowAvailable = true;
        surfaceReady = false;
        stopFrameLoop();
        if (engineReady) directEngine.connect(holder.getSurface(), surfaceWidth(holder), surfaceHeight(holder), surfaceGeneration);
        else ensureDirectEngine(holder);
        maybeStartFrameLoop();
    }

    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (filterMode || destroying || directEngine == null) return;
        if (!engineReady) ensureDirectEngine(holder);
        else directEngine.resize(Math.max(1, width), Math.max(1, height));
    }

    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (filterMode) return;
        surfaceGeneration++;
        windowAvailable = surfaceReady = false;
        if (startupProbe != null) startupProbe.stop();
        stopFrameLoop();
        cancelPointer();
        // Every JNI render/create/set_surface/resize completed on this same UI
        // thread before this callback; the worker never receives this window.
    }

    private int surfaceWidth(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        return Math.max(1, frame == null ? surfaceView.getWidth() : frame.width());
    }
    private int surfaceHeight(SurfaceHolder holder) {
        Rect frame = holder.getSurfaceFrame();
        return Math.max(1, frame == null ? surfaceView.getHeight() : frame.height());
    }

    private void ensureDirectEngine(SurfaceHolder holder) {
        if (filterMode || engineReady || engineCreating || destroying || directEngine == null || !windowAvailable) return;
        engineCreating = true;
        directEngine.create(holder.getSurface(), surfaceWidth(holder), surfaceHeight(holder),
                getResources().getDisplayMetrics().density, gameRoot,
                getIntent().getStringExtra(LaunchContract.SIGLUS_LANGUAGE), surfaceGeneration);
    }

    // ---------- Display leases and private producer ----------

    @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
        surfaceGeneration++;
        displayTexture = texture;
        windowAvailable = true;
        acquireDisplayWindow();
    }

    private void acquireDisplayWindow() {
        if (!filterMode || destroying || !windowAvailable || displayTexture == null || displayWindow != null) return;
        try { displayWindow = SiglusWindowLease.acquire(displayTexture); }
        catch (RuntimeException failure) { Log.w(TAG, "Display lease unavailable", failure); return; }
        if (displayWindow == null) {
            // A failed driver may retain four windows. Slot-release notification
            // retries the current live texture, without an unbounded timer queue.
            Log.w(TAG, "Display window capacity exhausted; waiting for owner release");
            return;
        }
        if (graphicsProxy == null) prepareStartupProducer();
        else attachDisplay();
    }

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
        if (displayTexture != texture) return;
        cancelPointer();
        surfaceReady = false;
        attachDisplay();
    }

    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
        if (displayTexture != texture) return true;
        surfaceGeneration++;
        windowAvailable = false;
        displayTexture = null;
        surfaceReady = false;
        stopFrameLoop();
        cancelPointer();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        if (graphicsProxy != null) graphicsProxy.detachOutput();
        boolean owned = displayWindow != null;
        // A retained EGL lease needs the native window, not TextureView's UI listener.
        if (owned) texture.setOnFrameAvailableListener(null);
        releaseDisplayWindow();
        // TextureView.SurfaceTextureListener permits false only when the client
        // assumes SurfaceTexture.release(). Pending/in-flight/attached EGL work
        // retains a lease, so the callback never waits or revokes its window.
        // A capacity-rejected texture was never passed to EGL: let the view free it.
        return !owned;
    }

    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) { }

    private void releaseDisplayWindow() {
        SiglusWindowLease previous = displayWindow;
        displayWindow = null;
        if (previous != null) previous.release();
    }

    private int surfaceWidth() { return Math.max(1, surfaceView.getWidth()); }
    private int surfaceHeight() { return Math.max(1, surfaceView.getHeight()); }

    private void attachDisplay() {
        if (!resumed || destroying || !windowAvailable || displayWindow == null
                || graphicsProxy == null || !engineReady || proxyPreparing || proxyFailed) return;
        surfaceReady = false;
        graphicsProxy.attachOutput(displayWindow, surfaceWidth(), surfaceHeight());
    }

    private void failInitialization() {
        if (destroying || isFinishing()) return;
        Toast.makeText(this, uiString(R.string.engine_siglus_init_failed), Toast.LENGTH_LONG).show();
        finish();
    }

    private void prepareStartupProducer() {
        if (!filterMode || filteredEngine == null || graphicsProxy != null || destroying || displayWindow == null) return;
        try {
            // Only enabled filter mode uses this producer, even before a trusted stage is available.
            // Its screen-sized copy is never advertised as a measured raw stage.
            graphicsProxy = createProxy(GraphicsFilterChain.parse("v1:"), surfaceWidth(), surfaceHeight(), false);
            proxyPreparing = true;
            graphicsProxy.prepare();
        } catch (RuntimeException failure) {
            Log.e(TAG, "Private graphics consumer unavailable", failure);
            failInitialization(); // No unsafe direct-native screen fallback.
        }
    }

    private SiglusGraphicsProxy createProxy(GraphicsFilterChain chain, int width, int height, boolean analyzer) {
        return new SiglusGraphicsProxy(this, chain, width, height, analyzer, new SiglusGraphicsProxy.Listener() {
            @Override public void onPrepared(SiglusGraphicsProxy proxy, Surface producer) {
                if (destroying || engine == null) return;
                if (proxy == graphicsProxy && !engineReady && !engineCreating) {
                    engineCreating = true;
                    proxyPreparing = false;
                    DisplayMetrics metrics = getResources().getDisplayMetrics();
                    filteredEngine.create(producer, width, height, metrics.density, gameRoot,
                            getIntent().getStringExtra(LaunchContract.SIGLUS_LANGUAGE), surfaceGeneration);
                } else if (proxy == pendingGraphicsProxy && engineReady) {
                    // Only the old GLES owner can disconnect its display EGL
                    // surface. The latest window is attached after producer ACK,
                    // independently of the generation which began this handoff.
                    WeakReference<SiglusActivity> owner = new WeakReference<>(SiglusActivity.this);
                    graphicsProxy.detachOutput(detached -> {
                        SiglusActivity activity = owner.get();
                        if (activity != null) activity.connectStageProducer(proxy, producer, width, height, detached);
                    });
                }
            }
            @Override public void onPreparationTimeout(SiglusGraphicsProxy proxy) {
                if (destroying) return;
                if (proxy == graphicsProxy && !engineReady) { failInitialization(); return; }
                if (proxy == pendingGraphicsProxy && !proxyAttached) {
                    pendingGraphicsProxy = null;
                    proxyPreparing = false;
                    attachDisplay();
                    maybeStartFrameLoop();
                }
            }
            @Override public void onOutputReady(SiglusGraphicsProxy proxy, SiglusWindowLease window) {
                if (destroying || graphicsProxy != proxy || displayWindow != window || !windowAvailable) return;
                surfaceReady = true;
                if (resumed && graphicsOverlay != null) graphicsOverlay.show();
                maybeStartFrameLoop();
            }
            @Override public void onFirstFrame(SiglusGraphicsProxy proxy) {
                if (!destroying && graphicsProxy == proxy) hideLoadingOverlay();
            }
            @Override public void onFailure(SiglusGraphicsProxy proxy, RuntimeException failure) {
                if (!destroying && (graphicsProxy == proxy || pendingGraphicsProxy == proxy))
                    handleProxyFailure(proxy);
            }
        });
    }

    private void connectStageProducer(SiglusGraphicsProxy proxy, Surface producer, int width, int height,
                                      boolean detached) {
        if (destroying || pendingGraphicsProxy != proxy || engine == null) return;
        if (!detached) { pausePresentation(proxy); return; }
        proxyAttached = true; // May reach native before the ACK.
        surfaceConnectionRequest = filteredEngine.setSurface(producer, width, height, surfaceGeneration);
    }

    private void handleProxyFailure(SiglusGraphicsProxy proxy) {
        if (pendingGraphicsProxy == proxy && !proxyAttached) {
            pendingGraphicsProxy = null;
            proxyPreparing = false;
            proxy.release(); // No native operation could have received this producer.
            attachDisplay();
            maybeStartFrameLoop();
        } else if (graphicsProxy == proxy && !engineReady) failInitialization();
        else pausePresentation(proxy);
    }

    private void completeSurfaceConnection(long request, boolean success) {
        if (destroying || request != surfaceConnectionRequest || pendingGraphicsProxy == null) return;
        SiglusGraphicsProxy candidate = pendingGraphicsProxy;
        if (!success) {
            // set_surface returned false: the old producer is still owned by
            // native; discard only the confirmed unbound candidate.
            pendingGraphicsProxy = null;
            candidate.release();
            proxyPreparing = proxyAttached = false;
            attachDisplay();
            maybeStartFrameLoop();
            return;
        }
        SiglusGraphicsProxy previous = graphicsProxy;
        graphicsProxy = candidate;
        pendingGraphicsProxy = null;
        proxyPreparing = proxyAttached = false;
        candidate.activate();
        previous.release(); // Old producer is now confirmed detached by native.
        if (graphicsOverlay != null) graphicsOverlay.hide();
        graphicsOverlay = new ArtemisPerformanceOverlay(this, candidate);
        attachDisplay();
    }

    private void pausePresentation(SiglusGraphicsProxy failed) {
        Log.e(TAG, "Graphics owner unavailable; keeping the VM and private producer owned");
        proxyFailed = true;
        surfaceReady = false;
        stopFrameLoop();
        cancelPointer();
        if (graphicsOverlay != null) graphicsOverlay.hide();
        // The consumer keeps draining until ordered native destruction. Never
        // reconnect the engine to the screen or release a possibly bound producer.
    }

    private void prepareGraphics() {
        if (!filterMode || filteredEngine == null) return;
        GraphicsFilterChain chain = requestedChain;
        // create() may have generated key.toml. Decode only after it succeeds,
        // off the engine/UI thread; the VM keeps its private copy producer meanwhile.
        final File directory = new File(gameRoot);
        final SiglusEngineThread engineOwner = filteredEngine;
        final int request = ++stageSizeRequest;
        final Handler main = new Handler(Looper.getMainLooper());
        final WeakReference<SiglusActivity> owner = new WeakReference<>(this);
        stageSizeWorker = new Thread(() -> {
            SiglusStageSize.Size result = null;
            try { result = SiglusStageSize.read(directory); }
            catch (RuntimeException failure) { Log.w(TAG, "Stage configuration unavailable", failure); }
            if (Thread.currentThread().isInterrupted()) return;
            final SiglusStageSize.Size size = result;
            main.post(() -> {
                SiglusActivity activity = owner.get();
                if (activity == null || activity.destroying || activity.faulted || activity.isFinishing()
                        || activity.isDestroyed() || activity.engine != engineOwner || !activity.engineReady
                        || activity.stageSizeRequest != request) return;
                activity.stageSizeWorker = null;
                if (size == null) {
                    Log.w(TAG, "No trusted configured stage size; keeping private copy presentation");
                    return;
                }
                activity.prepareGraphics(chain, size, engineOwner);
            });
        }, "Siglus-stage-size");
        try { stageSizeWorker.start(); }
        catch (RuntimeException failure) {
            stageSizeWorker = null;
            Log.w(TAG, "Stage configuration worker unavailable; keeping private copy presentation", failure);
        }
    }

    private void prepareGraphics(GraphicsFilterChain chain, SiglusStageSize.Size size, SiglusEngineThread engineOwner) {
        if (engine != engineOwner || pendingGraphicsProxy != null || destroying) return;
        SiglusGraphicsProxy candidate;
        try {
            candidate = createProxy(chain, size.width, size.height,
                    getIntent().getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false));
        } catch (RuntimeException failure) {
            Log.w(TAG, "Stage proxy unavailable; keeping private copy presentation", failure);
            return;
        }
        stopFrameLoop();
        cancelPointer();
        proxyPreparing = true;
        surfaceReady = false;
        pendingGraphicsProxy = candidate;
        candidate.prepare();
    }

    private void cancelPointer() {
        if (pointerActive && engineReady && engine != null) engine.touch(3, lastTouchX, lastTouchY);
        pointerActive = false;
    }

    private void destroyEngine() {
        SiglusGraphicsProxy producerOwner = graphicsProxy;
        SiglusGraphicsProxy candidateOwner = pendingGraphicsProxy;
        graphicsProxy = pendingGraphicsProxy = null;
        SiglusHost owner = engine;
        engine = null;
        filteredEngine = null;
        directEngine = null;
        engineReady = engineCreating = false;
        if (producerOwner != null) {
            producerOwner.stopObserving();
            producerOwner.detachOutput();
        }
        if (candidateOwner != null) candidateOwner.stopObserving();
        Runnable release = () -> {
            if (producerOwner != null) producerOwner.release();
            if (candidateOwner != null) candidateOwner.release();
        };
        if (owner != null) owner.close(release);
        else release.run();
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
        return resumed && !destroying && !isFinishing() && !faulted && engineReady && engine != null && surfaceReady
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
        if (!messageboxRequests.isEmpty()) {
            lastFrameNs = 0L;
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        if (graphicsProxy != null && graphicsProxy.hasFailed()) {
            pausePresentation(graphicsProxy);
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

        // Filter mode admits at most one queued/in-flight owner step; direct
        // mode completes the upstream synchronous step before this call returns.
        engine.step(dtMs);
        // Direct step/IME callbacks run synchronously and may stop the loop.
        if (running && canStep()) Choreographer.getInstance().postFrameCallback(this);
    }

    // ---------- 输入 ----------

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (!engineReady || event == null) {
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
        engine.touch(phase, x, y);
        if (phase == 2 || phase == 3) pointerActive = false;
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (engineReady && event != null) {
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
        if (!engineReady) {
            return;
        }
        engine.keyEvent(vk, text, repeat);
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

    private void updateImeState(boolean needed, int[] area) {
        if (!engineReady || engine == null || !canStep()) return;
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
            double scaleX = (double) surfaceView.getWidth() / surfaceWidth();
            double scaleY = (double) surfaceView.getHeight() / surfaceHeight();
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
        if (engineReady && text != null && !text.isEmpty()) {
            engine.textInput(text);
        }
    }

    @Override
    public void onPreedit(String text, int cursorStart, int cursorEnd) {
        if (!engineReady) {
            return;
        }
        engine.imePreedit(text, cursorStart, cursorEnd);
    }

    @Override
    public void onImeKeyEvent(int keyCode, boolean down) {
        if (!engineReady || !down) {
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
        // Always enqueue, even for the direct main-thread owner: returning from
        // the native callback must precede any reply that re-enters the VM.
        main.post(() -> showMessagebox(engineHandle, requestId, kind, title, message));
    }

    private void showMessagebox(long engineHandle, long requestId, int kind, String title, String message) {
        if (engine == null || !engine.matchesHandle(engineHandle) || destroying) return;
        if (isFinishing() || isDestroyed()) {
            submitMessagebox(engineHandle, requestId, fallbackMessageboxValue(kind));
            return;
        }
        if (!messageboxRequests.add(requestId)) return;
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
        try { builder.show(); }
        catch (RuntimeException failure) {
            Log.w(TAG, "Native message box unavailable", failure);
            submitMessagebox(engineHandle, requestId, fallbackMessageboxValue(kind));
        }
    }

    private void submitMessagebox(long engineHandle, long requestId, long value) {
        if (!destroying && engine != null && engine.matchesHandle(engineHandle)) {
            engine.submitMessagebox(engineHandle, requestId, value);
            messageboxRequests.remove(requestId);
        }
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
