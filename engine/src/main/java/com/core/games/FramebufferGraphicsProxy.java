package com.core.games;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.core.gl.ArtemisGraphicsStats;
import com.core.gl.ArtemisPerformanceOverlay;
import com.core.gl.CnnPipeline;
import com.core.gl.EglTeardown;
import com.core.gl.GlErrors;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.GraphicsFilterPipeline;
import com.core.gl.GraphicsProxyResourceSlots;
import com.core.gl.GraphicsStatus;
import com.core.gl.TextureDisplayWindow;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * Consumes CPU RGBA8 frames from the {@code game_fb_*} ABI on a dedicated GLES
 * thread and presents the shared filter chain output on a TextureView window.
 *
 * <p>Unlike the Siglus proxy there is no producer Surface: the engine never owns
 * a display window, frames arrive as reusable direct buffers (latest wins), and
 * the input texture is uploaded with {@code glTexImage2D}. Engine frames are
 * top-down, so the upload stores a top-left texture; the chain keeps that row
 * order ({@code renderCroppedTexture(..., topLeft=true)}) and the copy program
 * flips V once at presentation. Any GLES failure is terminal and reported to the
 * host, which falls back to the classic bitmap path.
 *
 * <h2>与 Siglus / ONScripter 代理的共享与取舍</h2>
 * <p>本类与 {@code SiglusGraphicsProxy}、{@code OnsGraphicsProxy} 共用 {@code GraphicsFilterPipeline}、
 * {@code GraphicsProxyResourceSlots}、{@code EglTeardown}、{@code ArtemisGraphicsStats} 与 {@code GlErrors}；
 * 仅保留围绕它们的宿主特异 EGL 管线（上传/producer、行序、视口推导与回退语义）。</p>
 * <p>EGL 释放不变式统一收口在 {@link EglTeardown}：有界指数退避、解绑门禁、只在销毁成功时置空句柄、
 * 确认销毁上下文后清理管线/GL 名字、全部句柄销毁后才释放窗口并按需 {@code eglTerminate}；
 * 清理耗尽时一律保留句柄与资源名额至进程退出（以进程容量为自然上界，绝不跨线程析构窗口）。</p>
 */
final class FramebufferGraphicsProxy implements ArtemisPerformanceOverlay.Source {
    interface Listener {
        void onOutputReady(FramebufferGraphicsProxy proxy);
        /** First successful presentation of a filtered frame: the only readiness signal the
         *  startup overlay may use. The classic bitmap view is GONE in filter mode, so its
         *  first-draw callback can never fire. */
        void onFirstFrame(FramebufferGraphicsProxy proxy);
        void onFailure(FramebufferGraphicsProxy proxy, RuntimeException failure);
    }

    /** A direct RGBA8 buffer owned by the pool; indices flow back after upload. */
    static final class Slot {
        final ByteBuffer buffer;
        private final int index;
        Slot(ByteBuffer buffer, int index) { this.buffer = buffer; this.index = index; }
    }

    private static final String TAG = "FbgamesGraphics";
    private static final long RELEASE_RETRY_MS = 1000L;
    private static final int MAX_RELEASE_ATTEMPTS = 5;
    private static final int FRAME_SLOT_COUNT = 3;
    // Permanent driver failures retain only resources until process exit, never
    // the proxy's Listener (which owns an Activity) or its worker thread.
    // Bounded by GraphicsProxyResourceSlots' capacity: every retained entry also holds a
    // resource slot, so an unbounded list is impossible and no extra cap is needed (a cap
    // would have to drop a live handle to free a slot).
    private static final List<RetainedHandles> RETAINED_HANDLES = new ArrayList<>();

    private static final String VERTEX = "#version 300 es\n"
            + "out vec2 uv; void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));"
            + "uv=p; gl_Position=vec4(p*2.0-1.0,0.0,1.0); }";
    // Engine frames upload top-down; the chain preserves that physical row order,
    // so presentation samples with an inverted V to restore screen orientation.
    private static final String COPY_FRAGMENT = "#version 300 es\n"
            + "precision highp float; in vec2 uv; uniform sampler2D tex; out vec4 color;"
            + "void main(){color=vec4(texture(tex,vec2(uv.x,1.0-uv.y)).rgb,1.0);}";

    private final Context context;
    private final GraphicsFilterChain chain;
    private final boolean analyzerRequested;
    private volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Fbgames-GLES");
    private final Handler worker;
    private final GraphicsProxyResourceSlots.Slot resourceSlot;
    private final ArtemisGraphicsStats stats = new ArtemisGraphicsStats();

    private final FrameSlotPool framePool = new FrameSlotPool();
    private final AtomicReference<PendingFrame> pending = new AtomicReference<>();
    private final AtomicBoolean consumeQueued = new AtomicBoolean();

    private volatile boolean closed, terminalFailure;
    private volatile FramebufferViewport viewport;
    private volatile int status = GraphicsStatus.WAITING;
    // Written on the GLES thread, read by the analyzer overlay on the main thread.
    private volatile boolean firstPresented;
    // 最近一次成功 eglSwapBuffers 的时间（elapsedRealtime）。宿主看门狗用它区分
    // 「输出面在」与「真的在出帧」——只判 hasOutput 会把「接上了却零帧」当成健康。
    private volatile long lastPresentedAtMs;
    // Whether an output EGL surface exists right now; the host's doFrame poll uses
    // it to re-attach after a benign attach failure instead of presenting to none.
    private volatile boolean outputAttached;

    // GLES-thread state only.
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface parking = EGL14.EGL_NO_SURFACE, output = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    @Nullable private TextureDisplayWindow outputWindow;
    private GraphicsFilterPipeline pipeline;
    private int copyProgram, inputTexture, vao;
    private int textureWidth, textureHeight;
    private int outputWidth, outputHeight;
    private boolean initialized;
    private int releaseAttempts;

    private static final class PendingFrame {
        final ByteBuffer data;
        final int width, height, slot;
        PendingFrame(ByteBuffer data, int width, int height, int slot) {
            this.data = data; this.width = width; this.height = height; this.slot = slot;
        }
    }

    /** Bounds reusable direct frame buffers; slots are recycled after upload. */
    static final class FrameSlotPool {
        private final ByteBuffer[] buffers = new ByteBuffer[FRAME_SLOT_COUNT];
        private final int[] widths = new int[FRAME_SLOT_COUNT];
        private final int[] heights = new int[FRAME_SLOT_COUNT];
        private final boolean[] inFlight = new boolean[FRAME_SLOT_COUNT];
        private final IntFunction<ByteBuffer> allocator;
        private int budgetLoggedWidth, budgetLoggedHeight;
        // OOM 后同一尺寸不再逐帧重试（否则日志风暴 + 主线程 GC 抖动）；
        // 尺寸变化时才重新给机会，与经典路径 frameAllocFailed 闩锁同语义。
        private int oomFailedWidth, oomFailedHeight;

        FrameSlotPool() {
            this(ByteBuffer::allocateDirect);
        }

        FrameSlotPool(IntFunction<ByteBuffer> allocator) {
            this.allocator = allocator;
        }

        @Nullable synchronized Slot acquire(int width, int height) {
            if (width <= 0 || height <= 0) return null;
            // Same gate as the GLES-side texture validation (overflow-safe long math):
            // a frame the input texture would reject never allocates pool buffers,
            // so UI-thread crashes and dead 256MB-class allocations stay impossible.
            if (!frameWithinBudget(width, height)) {
                // Leave a trace instead of dropping silently; log once per size.
                if (width != budgetLoggedWidth || height != budgetLoggedHeight) {
                    budgetLoggedWidth = width; budgetLoggedHeight = height;
                    Log.w(TAG, "Frame " + width + "x" + height
                            + " exceeds the proxy texture budget; dropping frames");
                }
                return null;
            }
            // 先复用已按同尺寸分配好的空闲槽位——暂态 OOM 闩锁只该拦住「需要重新分配」
            // 的槽位，不能把整池封死（曾因槽位 1 的一次 OOM 让槽位 0 也永久不可用）。
            for (int i = 0; i < FRAME_SLOT_COUNT; i++) {
                if (inFlight[i] || buffers[i] == null) continue;
                if (widths[i] != width || heights[i] != height) continue;
                inFlight[i] = true;
                buffers[i].rewind();
                return new Slot(buffers[i], i);
            }
            if (oomFailedWidth == width && oomFailedHeight == height) return null;
            for (int i = 0; i < FRAME_SLOT_COUNT; i++) {
                if (inFlight[i]) continue;
                if (buffers[i] == null || widths[i] != width || heights[i] != height) {
                    try {
                        buffers[i] = allocator.apply(width * height * 4).order(ByteOrder.nativeOrder());
                    } catch (OutOfMemoryError failure) {
                        Log.w(TAG, "Frame buffer allocation failed; latching until the size changes", failure);
                        oomFailedWidth = width;
                        oomFailedHeight = height;
                        return null;
                    }
                    widths[i] = width; heights[i] = height;
                }
                inFlight[i] = true;
                buffers[i].rewind();
                return new Slot(buffers[i], i);
            }
            return null; // Every slot is in flight; drop this frame.
        }

        synchronized void recycle(int index) {
            if (index >= 0 && index < FRAME_SLOT_COUNT) inFlight[index] = false;
        }

        synchronized void resetOomLatch() {
            oomFailedWidth = 0;
            oomFailedHeight = 0;
        }
    }

    private static final class RetainedHandles {
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface parking, output;
        final GraphicsFilterPipeline pipeline;
        final TextureDisplayWindow window;
        final int[] glNames;
        final GraphicsProxyResourceSlots.Slot resourceSlot;
        RetainedHandles(FramebufferGraphicsProxy proxy) {
            display = proxy.display; context = proxy.eglContext; resourceSlot = proxy.resourceSlot;
            parking = proxy.parking; output = proxy.output; pipeline = proxy.pipeline;
            window = proxy.outputWindow;
            glNames = new int[]{proxy.copyProgram, proxy.inputTexture, proxy.vao};
        }
    }

    FramebufferGraphicsProxy(Context context, GraphicsFilterChain chain, boolean analyzer, Listener listener) {
        this.context = context.getApplicationContext();
        this.chain = chain;
        analyzerRequested = analyzer; this.listener = listener;
        resourceSlot = GraphicsProxyResourceSlots.acquireForProcess();
        if (resourceSlot == null) throw new IllegalStateException("Proxy resource capacity exhausted");
        try {
            thread.start(); worker = new Handler(thread.getLooper());
        } catch (RuntimeException | Error failure) {
            // No EGL work can have been queued before construction returns.
            try { thread.quitSafely(); }
            finally { resourceSlot.release(); }
            throw failure;
        }
    }

    // ---------- UI-thread API ----------

    /** Acquire a reusable RGBA8 direct buffer on the producer (UI) thread. */
    @Nullable Slot acquireSlot(int width, int height) {
        if (closed || terminalFailure) return null;
        return framePool.acquire(width, height);
    }

    /** Hand a filled slot to the GLES thread; latest wins, displaced frames recycle. */
    void submitFrame(Slot slot, int width, int height) {
        if (slot == null) return;
        if (closed || terminalFailure) { framePool.recycle(slot.index); return; }
        PendingFrame displaced = pending.getAndSet(new PendingFrame(slot.buffer, width, height, slot.index));
        if (displaced != null) framePool.recycle(displaced.slot);
        if (consumeQueued.compareAndSet(false, true)) {
            if (!worker.post(this::consume)) consumeQueued.set(false);
        }
    }

    /** Return an unfilled slot; the copy failed before the frame became pending. */
    void abandonSlot(@Nullable Slot slot) {
        if (slot != null) framePool.recycle(slot.index);
    }

    /** 回前台时清除同尺寸 OOM 闩锁，给暂态分配失败一次重试（与经典路径同语义）。 */
    void resetFramePoolLatch() { framePool.resetOomLatch(); }

    void attachOutput(TextureDisplayWindow window, int width, int height) {
        if (closed || terminalFailure || window == null || !window.retain()) return;
        boolean posted = worker.post(() -> {
            try { attach(window, width, height); }
            // Catch Throwable: any worker death would freeze a presented session
            // with no failure signal; the fallback contract is the safer outcome.
            catch (Throwable failure) { fail(asFailure(failure)); }
            finally { window.release(); }
        });
        if (!posted) window.release(); // Looper already quit; the lambda will never run.
    }

    void detachOutput() {
        // worker 已退出时不静默丢请求——记录一次，宿主仍有看门狗兜底。
        if (!worker.post(this::detach)) Log.w(TAG, "Detach ignored; graphics worker is gone");
    }

    /** Call only from the UI thread after the host stopped producing frames. */
    void release() {
        if (closed) return;
        closed = true;
        listener = null;
        // 投递失败时 worker 已死，finishRelease 永不会执行 → 资源名额与 EGL 句柄
        // 泄漏且 closed 已置位无法再触发。此时退化为同步清理路径由调用方进程退出回收。
        if (!worker.post(this::finishRelease)) {
            Log.w(TAG, "Release ignored; graphics worker is gone, resource slot retained until process exit");
        }
    }

    FramebufferViewport viewport() { return viewport; }
    boolean hasFailed() { return terminalFailure; }
    /** Whether an output EGL surface exists right now; drives the host's re-attach poll. */
    boolean hasOutput() { return outputAttached; }
    /** 最近一次成功呈现的时间戳（elapsedRealtime），0 表示从未呈现。 */
    long lastPresentedAtMs() { return lastPresentedAtMs; }

    // ---------- GLES-thread internals ----------

    private void consume() {
        consumeQueued.set(false);
        PendingFrame frame = pending.getAndSet(null);
        if (frame == null) return;
        try {
            draw(frame);
        } catch (Throwable failure) {
            // draw() owns slot recycling at its upload point; any failure past that
            // is terminal and the whole pool is discarded with the fallback. Catch
            // Throwable so a worker death cannot freeze a presented session silently.
            fail(asFailure(failure));
        }
    }

    private static RuntimeException asFailure(Throwable failure) {
        if (failure instanceof RuntimeException) return (RuntimeException) failure;
        return new IllegalStateException("Graphics worker failure", failure);
    }

    /** Shared frame admission: long-safe pixel math with the same 16x-per-pixel
     * texture budget gate as the GLES-side validation, so the pool never allocates
     * buffers that the input texture would reject. */
    private static boolean frameWithinBudget(int width, int height) {
        long pixels;
        try { pixels = Math.multiplyExact((long) width, height); }
        catch (ArithmeticException overflow) { return false; }
        try {
            return pixels > 0 && Math.multiplyExact(pixels, 16L) < CnnPipeline.TARGET_MEMORY_BUDGET_BYTES;
        } catch (ArithmeticException overflow) { return false; }
    }

    /** GLES-thread gate shared by first allocation and mid-session resizes. */
    private void validateFrameSize(int width, int height) {
        int[] scratch = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, scratch, 0);
        if (width > scratch[0] || height > scratch[0])
            throw new IllegalStateException("Frame exceeds GL texture limit");
        if (!frameWithinBudget(width, height))
            throw new IllegalStateException("Frame exceeds proxy texture budget");
    }

    private void ensureContext() {
        if (initialized) return;
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] versions = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            display = EGL14.EGL_NO_DISPLAY;
            throw new IllegalStateException("EGL display unavailable");
        }
        // From here on dispose() must be able to clean up partial EGL state.
        initialized = true;
        int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        int[] scratch = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, scratch, 0) || scratch[0] < 1)
            throw new IllegalStateException("GLES3 EGL configuration unavailable");
        config = configs[0];
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        parking = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT || parking == EGL14.EGL_NO_SURFACE || !makeCurrent(parking))
            throw new IllegalStateException("GLES3 context unavailable");
        copyProgram = program(COPY_FRAGMENT);
        GLES30.glGenVertexArrays(1, scratch, 0); vao = scratch[0];
        checkGl("initialize");
        Log.i(TAG, "Framebuffer filter consumer ready | " + GLES30.glGetString(GLES30.GL_RENDERER));
    }

    private void allocateInput(int width, int height) {
        validateFrameSize(width, height);
        int[] scratch = new int[1];
        // Reserve the RGBA8 upload plus chain headroom; the pipeline re-plans on
        // later size changes and only this reservation stays fixed.
        long reserved = Math.multiplyExact(Math.multiplyExact((long) width, height), 16L);
        GLES30.glGenTextures(1, scratch, 0); inputTexture = scratch[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture);
        textureParameters(GLES30.GL_TEXTURE_2D);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height,
                0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        textureWidth = width; textureHeight = height;
        pipeline = new GraphicsFilterPipeline(context, chain, reserved);
        if (analyzerRequested) pipeline.setProfiler(stats, analyzerRequested);
        checkGl("input allocation");
        Log.i(TAG, "Filter input " + width + "x" + height);
    }

    private void draw(PendingFrame frame) {
        if (closed || terminalFailure) { framePool.recycle(frame.slot); return; }
        if (output == EGL14.EGL_NO_SURFACE) { framePool.recycle(frame.slot); return; }
        ensureContext();
        if (!makeCurrent(output)) throw new IllegalStateException("Display context lost");
        // Clear only inherited errors at an owned allocation/upload boundary;
        // texture validation, resize and frame upload must not observe stale leftovers.
        beginGlOperations();
        if (inputTexture == 0) {
            allocateInput(frame.width, frame.height);
        } else if (frame.width != textureWidth || frame.height != textureHeight) {
            // Mid-session resizes pass the same gates as the first allocation. The
            // pool admission already rejects over-budget frames, so the budget half
            // of validateFrameSize is defense in depth; GL_MAX is the live check here.
            validateFrameSize(frame.width, frame.height);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, frame.width, frame.height,
                    0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            textureWidth = frame.width; textureHeight = frame.height;
            checkGl("input resize");
        }
        frame.data.rewind();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, frame.data);
        checkGl("frame upload");
        framePool.recycle(frame.slot);

        FramebufferViewport v = FramebufferViewport.fit(frame.width, frame.height, outputWidth, outputHeight);
        if (v == null) return;
        viewport = v;
        int result = pipeline.renderCroppedTexture(inputTexture, frame.width, frame.height,
                0f, 0f, 1f, 1f, v.surfaceWidth, v.surfaceHeight, true);
        // Do not clear errors produced by the chain/state restoration before presenting.
        checkGl("filter output");
        int presentTexture = result != 0 ? result : inputTexture;
        int presentedWidth = result != 0 ? pipeline.outputWidth() : frame.width;
        int presentedHeight = result != 0 ? pipeline.outputHeight() : frame.height;
        status = pipeline.isFailed() ? GraphicsStatus.FILTER_FAILED
                : result == 0 && pipeline.bypassReason() != CnnPipeline.BYPASS_NONE
                ? GraphicsStatus.SIZE_UNAVAILABLE : GraphicsStatus.READY;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        setupDraw(v.surfaceWidth, v.surfaceHeight);
        GLES30.glClearColor(0, 0, 0, 1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport(v.left, v.surfaceHeight - v.top - v.height, v.width, v.height);
        GLES30.glUseProgram(copyProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindSampler(0, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, presentTexture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "tex"), 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        checkGl("presentation");
        if (!EGL14.eglSwapBuffers(display, output)) throw new IllegalStateException("Display swap failed");
        boolean firstFrame = !firstPresented;
        firstPresented = true;
        lastPresentedAtMs = SystemClock.elapsedRealtime();
        if (analyzerRequested) {
            stats.frame(frame.width, frame.height, presentedWidth, presentedHeight, v.width, v.height);
            stats.gpu(-1); // No blocking readback or CPU duration mislabeled as GPU time.
            stats.presented();
        }
        if (firstFrame) {
            // Startup overlay readiness: the classic bitmap view is GONE in filter mode, so
            // its first-draw callback never fires and this is the only unveil signal.
            Listener callback = listener;
            main.post(() -> {
                if (!closed && !terminalFailure && callback != null)
                    callback.onFirstFrame(FramebufferGraphicsProxy.this);
            });
        }
    }

    private boolean attach(TextureDisplayWindow window, int width, int height) {
        if (closed || terminalFailure) return false;
        if (window.surface == null || !window.surface.isValid() || width <= 0 || height <= 0) return false;
        try {
            ensureContext();
            if (!detachSurface()) throw new IllegalStateException("Previous display could not disconnect");
            if (closed || terminalFailure || !window.surface.isValid()) return false;
            // The attached surface owns its own window reference before EGL binds it;
            // detach releases it only after the EGL surface is confirmed destroyed.
            if (!window.retain()) return false;
            outputWindow = window;
            output = EGL14.eglCreateWindowSurface(display, config, window.surface, new int[]{EGL14.EGL_NONE}, 0);
            if (output == EGL14.EGL_NO_SURFACE || !makeCurrent(output)) {
                int error = EGL14.eglGetError();
                if (!detachSurface()) throw new IllegalStateException("Failed display could not disconnect");
                // Benign teardown races retry on the next attach; other failures
                // stay terminal so the host falls back instead of hanging black.
                // Exemption mirrors the Siglus host (SiglusGraphicsProxy.attach).
                if (error == EGL14.EGL_BAD_NATIVE_WINDOW || error == EGL14.EGL_BAD_SURFACE
                        || (!window.surface.isValid() && error != EGL14.EGL_CONTEXT_LOST)) return false;
                throw new IllegalStateException("Display EGLSurface unavailable: " + error);
            }
            outputAttached = true;
            EGL14.eglSwapInterval(display, 0);
            outputWidth = width; outputHeight = height;
            // Stale touch is dropped by the host until the next frame recomputes the
            // viewport; the engine pointer keeps its previous position (see the proxy attach comment).
            viewport = null;
            // No stats.reset() here: the stats instance is proxy-private and starts
            // zeroed, and a reset after the pipeline's one-shot configureNodes would
            // permanently sever nodeFrame (length guard) — the analyzer would lose
            // node cards, GPU totals and fps sampling across re-attach (resume).
            // Siglus resets once per proxy activation, never on re-attach.
            stats.surface(width, height);
            // Read the listener once on the worker; outputWindow is not part of the
            // decision, so no cross-thread field crosses into this callback.
            Listener callback = listener;
            main.post(() -> {
                if (!closed && !terminalFailure && callback != null)
                    callback.onOutputReady(FramebufferGraphicsProxy.this);
            });
            return true;
        } catch (RuntimeException failure) {
            detachSurface(); // Destroy any partially created surface and drop its window reference.
            throw failure;
        }
    }

    private boolean detachSurface() {
        if (display == EGL14.EGL_NO_DISPLAY) {
            if (outputWindow != null) { outputWindow.release(); outputWindow = null; }
            viewport = null;
            return true;
        }
        // Destroying a current EGLSurface defers disconnection; park the context first.
        if (!makeCurrent(parking) && !EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) return false;
        if (output != EGL14.EGL_NO_SURFACE && !EGL14.eglDestroySurface(display, output)) return false;
        output = EGL14.EGL_NO_SURFACE;
        outputAttached = false;
        // 与 OnsGraphicsProxy.detach() 对齐：断开输出后不得保留旧几何，否则暂停期
        // 窗口改尺寸、回前台尚未重挂的那段时间点击仍按旧视口换算。
        viewport = null;
        if (outputWindow != null) { outputWindow.release(); outputWindow = null; }
        return true;
    }

    private void detach() {
        try { detachSurface(); }
        catch (RuntimeException failure) { Log.w(TAG, "Display detach failed", failure); }
    }

    private boolean makeCurrent(EGLSurface surface) {
        return surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, eglContext);
    }

    private void setupDraw(int width, int height) {
        GLES30.glBindVertexArray(vao);
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_STENCIL_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glColorMask(true, true, true, true);
        GLES30.glViewport(0, 0, width, height);
    }

    private static void textureParameters(int target) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    private static int shader(int kind, String source) {
        int shader = GLES30.glCreateShader(kind);
        GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader);
        int[] ok = new int[1]; GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader); GLES30.glDeleteShader(shader);
            throw new IllegalStateException("Proxy shader: " + log);
        }
        return shader;
    }

    private static int program(String fragment) {
        int vertex = shader(GLES30.GL_VERTEX_SHADER, VERTEX), pixel = 0, program = 0;
        try {
            pixel = shader(GLES30.GL_FRAGMENT_SHADER, fragment);
            program = GLES30.glCreateProgram(); GLES30.glAttachShader(program, vertex);
            GLES30.glAttachShader(program, pixel); GLES30.glLinkProgram(program);
            int[] ok = new int[1]; GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("Proxy link: " + GLES30.glGetProgramInfoLog(program));
            return program;
        } catch (RuntimeException failure) {
            if (program != 0) GLES30.glDeleteProgram(program); throw failure;
        } finally {
            GLES30.glDeleteShader(vertex); if (pixel != 0) GLES30.glDeleteShader(pixel);
        }
    }

    /** Clear only inherited errors at an owned upload/present boundary. */
    private static void beginGlOperations() {
        int err = GlErrors.beginGlOperations(GLES30::glGetError);
        if (err == GlErrors.GL_CONTEXT_LOST_KHR) {
            throw new GlErrors.ContextLostException("Proxy observed GL_CONTEXT_LOST_KHR");
        }
        if (err != 0) {
            throw new IllegalStateException("Proxy GL error state did not clear");
        }
    }

    private static void checkGl(String operation) {
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) throw new IllegalStateException(operation + ": GL " + error);
    }

    private void fail(RuntimeException failure) {
        if (closed || terminalFailure) return;
        terminalFailure = true; status = GraphicsStatus.NATIVE_FAILED;
        Log.e(TAG, "Proxy failed; the host falls back to the classic bitmap presentation", failure);
        main.post(() -> {
            Listener callback = listener;
            if (!closed && callback != null) callback.onFailure(FramebufferGraphicsProxy.this, failure);
        });
    }

    private void finishRelease() {
        boolean lastAttempt = ++releaseAttempts >= MAX_RELEASE_ATTEMPTS;
        boolean disposed = false;
        try { disposed = dispose(); }
        catch (RuntimeException failure) {
            if (releaseAttempts == 1) Log.w(TAG, "EGL cleanup failed", failure);
        }
        if (disposed) { resourceSlot.release(); thread.quitSafely(); return; }
        if (lastAttempt) {
            // A failed EGL unbind must not transfer the native window to another
            // thread or force-release it; retain until process exit.
            synchronized (RETAINED_HANDLES) { RETAINED_HANDLES.add(new RetainedHandles(this)); }
            worker.removeCallbacksAndMessages(null);
            Log.w(TAG, "EGL cleanup exhausted; retaining driver handles and resource slot until process exit");
            thread.quitSafely();
        } else {
            if (releaseAttempts == 1) Log.w(TAG, "EGL cleanup deferred; retrying with bounded backoff");
            worker.postDelayed(this::finishRelease, EglTeardown.retryDelayMs(RELEASE_RETRY_MS, releaseAttempts));
        }
    }

    private void contextDestroyed() {
        if (pipeline != null) { pipeline.abandonContext(); pipeline = null; }
        copyProgram = inputTexture = vao = 0;
        textureWidth = textureHeight = 0;
    }

    private boolean dispose() {
        outputAttached = false; // Session teardown; no further frames can present.
        if (!initialized) { viewport = null; return true; }
        boolean current = makeCurrent(parking);
        if (current) {
            if (pipeline != null) { pipeline.release(); pipeline = null; }
            int[] scratch = new int[1];
            if (inputTexture != 0) { scratch[0] = inputTexture; GLES30.glDeleteTextures(1, scratch, 0); }
            if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram);
            if (vao != 0) { scratch[0] = vao; GLES30.glDeleteVertexArrays(1, scratch, 0); }
            copyProgram = inputTexture = vao = 0;
            textureWidth = textureHeight = 0;
        }
        if (!EglTeardown.unbind(display)) return false;
        output = EglTeardown.destroySurface(display, output);
        parking = EglTeardown.destroySurface(display, parking);
        eglContext = EglTeardown.destroyContext(display, eglContext, this::contextDestroyed);
        boolean handlesGone = output == EGL14.EGL_NO_SURFACE && parking == EGL14.EGL_NO_SURFACE
                && eglContext == EGL14.EGL_NO_CONTEXT;
        if (!handlesGone) return false;
        if (outputWindow != null) { outputWindow.release(); outputWindow = null; }
        // The default EGLDisplay can have other initialize references. A
        // successful terminate does not prove our failed destroys completed.
        if (!EglTeardown.terminate(display, initialized)) return false;
        display = EGL14.EGL_NO_DISPLAY;
        initialized = false;
        viewport = null;
        stats.reset();
        return true;
    }

    @Override public ArtemisGraphicsStats stats() { return stats; }
    // Analyzer semantics follow the other hosts: a live, non-failed proxy is
    // analyzable; pre-first-frame gaps surface as WAITING, not as a hidden panel.
    private boolean isEnabled() { return !terminalFailure && !closed; }
    @Override public boolean isAnalyzing() { return analyzerRequested && isEnabled(); }
    @Override public int status() { return status; }
}
