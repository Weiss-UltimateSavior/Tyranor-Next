package com.core.ons;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.core.gl.CnnPipeline;
import com.core.gl.EglTeardown;
import com.core.gl.GlErrors;
import com.core.gl.ArtemisGraphicsStats;
import com.core.gl.ArtemisPerformanceOverlay;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.GraphicsFilterPipeline;
import com.core.gl.GraphicsProxyResourceSlots;
import com.core.gl.GraphicsStatus;
import com.core.gl.TextureDisplayWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Consumes logical-resolution ONScripter producer frames via vendored SDL2 glue
 * on a dedicated GLES thread and presents the shared filter chain output on a TextureView window.
 */
public final class OnsGraphicsProxy implements ArtemisPerformanceOverlay.Source {
    public interface Listener {
        void onOutputReady(OnsGraphicsProxy proxy);
        /** First successful presentation of a filtered frame: the only readiness signal the
         *  startup overlay may use (the engine's own SurfaceView no longer has a producer). */
        void onFirstFrame(OnsGraphicsProxy proxy);
        void onFailure(OnsGraphicsProxy proxy, RuntimeException failure);
    }

    private static final String TAG = "OnsGraphics";
    private static final long RELEASE_RETRY_MS = 1000L;
    private static final int MAX_RELEASE_ATTEMPTS = 5;
    // 永久驱动失败的句柄只保留到进程退出，绝不跨线程析构仍在使用的 ANativeWindow。
    // 保留表天然有界：每个保留项都占一个 GraphicsProxyResourceSlots 名额
    // （进程容量 2），名额耗尽后连新代理都创建不出来，列表不可能无界增长——
    // 不需要（也不允许）额外上限，否则只能靠丢弃句柄来归还名额，那正是跨线程析构。
    private static final List<RetainedHandles> RETAINED_HANDLES = new ArrayList<>();

    private static final class RetainedHandles {
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface parking, output;
        final GraphicsFilterPipeline pipeline;
        final Surface producer;
        final SurfaceTexture texture;
        final TextureDisplayWindow window;
        final int[] glNames;
        final boolean initialized;
        final GraphicsProxyResourceSlots.Slot resourceSlot;

        RetainedHandles(OnsGraphicsProxy proxy) {
            display = proxy.display;
            context = proxy.eglContext;
            initialized = proxy.initialized;
            resourceSlot = proxy.resourceSlot;
            parking = proxy.parking;
            output = proxy.output;
            pipeline = proxy.pipeline;
            producer = proxy.producer;
            texture = proxy.texture;
            window = proxy.outputWindow;
            glNames = new int[]{proxy.oesProgram, proxy.copyProgram, proxy.inputFbo,
                    proxy.inputTexture, proxy.oes, proxy.vao};
        }
    }

    private static final String VERTEX = "#version 300 es\n"
            + "out vec2 uv; void main(){ vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));"
            + "uv=p; gl_Position=vec4(p*2.0-1.0,0.0,1.0); }";
    private static final String OES_FRAGMENT = "#version 300 es\n"
            + "#extension GL_OES_EGL_image_external_essl3 : require\n"
            + "precision highp float; in vec2 uv; uniform samplerExternalOES tex;"
            + "uniform mat4 transform; out vec4 color;"
            + "void main(){color=vec4(texture(tex,(transform*vec4(uv,0.0,1.0)).xy).rgb,1.0);}";
    private static final String COPY_FRAGMENT = "#version 300 es\n"
            + "precision highp float; in vec2 uv; uniform sampler2D tex; out vec4 color;"
            + "void main(){color=vec4(texture(tex,uv).rgb,1.0);}";

    private final Context context;
    private final GraphicsFilterChain chain;
    private final boolean analyzerRequested;
    private volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("ONS-GLES");
    private final Handler worker;
    private final GraphicsProxyResourceSlots.Slot resourceSlot;
    private final ArtemisGraphicsStats stats = new ArtemisGraphicsStats();

    private final float[] transform = new float[16];
    private final int[] scratch = new int[1];

    private volatile boolean closed, terminalFailure, stageConfigured;
    private volatile boolean outputAttached, hasImage, firstPresented;
    private volatile OnsViewport viewport;
    private volatile int status = GraphicsStatus.WAITING;

    private volatile int stageWidth, stageHeight;
    private volatile int outputWidth, outputHeight;
    private int releaseAttempts;

    // GLES-thread state only.
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface parking = EGL14.EGL_NO_SURFACE, output = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private SurfaceTexture texture;
    private volatile Surface producer;
    private TextureDisplayWindow outputWindow;
    private int oes, vao, oesProgram, copyProgram, transformLocation;
    private int oesTexLocation, copyTexLocation;
    private int inputTexture, inputFbo;
    private GraphicsFilterPipeline pipeline;
    private boolean initialized;
    private boolean contextReady;

    public OnsGraphicsProxy(Context context, GraphicsFilterChain chain, boolean analyzerRequested, Listener listener) {
        this.context = context.getApplicationContext();
        this.chain = chain;
        this.analyzerRequested = analyzerRequested;
        this.listener = listener;

        resourceSlot = GraphicsProxyResourceSlots.acquireForProcess();
        if (resourceSlot == null) {
            throw new IllegalStateException("Proxy resource capacity exhausted");
        }
        try {
            thread.start();
            worker = new Handler(thread.getLooper());
        } catch (RuntimeException | Error failure) {
            try { thread.quitSafely(); }
            finally { resourceSlot.release(); }
            throw failure;
        }
    }

    public Surface getProducerSurface(int width, int height) {
        if (closed || terminalFailure) return null;
        Surface current = producer;
        if (stageConfigured && stageWidth == width && stageHeight == height && current != null && current.isValid()) {
            return current;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Surface> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        boolean posted = worker.post(() -> {
            try {
                configureStageInternal(width, height);
                ref.set(producer);
            } catch (Throwable t) {
                err.set(t);
                fail(asFailure(t));
            } finally {
                done.countDown();
            }
        });
        if (!posted) return null;
        try {
            if (!done.await(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "getProducerSurface timed out");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (err.get() != null) {
            Log.w(TAG, "getProducerSurface failed", err.get());
            return null;
        }
        return ref.get();
    }

    public void attachOutput(TextureDisplayWindow window, int width, int height) {
        if (closed || terminalFailure || window == null || !window.retain()) return;
        boolean posted = worker.post(() -> {
            try {
                attach(window, width, height);
            } catch (Throwable t) {
                fail(asFailure(t));
            } finally {
                window.release();
            }
        });
        if (!posted) window.release();
    }

    public void detachOutput() {
        // worker 已退出时不静默丢请求——记录一次，宿主仍有看门狗兜底。
        if (!worker.post(this::detach)) Log.w(TAG, "Detach ignored; graphics worker is gone");
    }

    public void release() {
        if (closed) return;
        closed = true;
        listener = null;
        if (!worker.post(this::finishRelease)) {
            Log.w(TAG, "Release ignored; graphics worker is gone, resource slot retained until process exit");
        }
    }

    public OnsViewport viewport() { return viewport; }
    public boolean hasFailed() { return terminalFailure; }
    public boolean isClosed() { return closed; }
    public boolean hasOutput() { return outputAttached; }
    public boolean isStageConfigured() { return stageConfigured; }
    /** 是否已至少成功呈现过一帧。首帧之前的健康判据用它，之后由「输出面还在」接管。 */
    public boolean hasPresented() { return firstPresented; }

    @Override
    public ArtemisGraphicsStats stats() {
        return stats;
    }

    @Override
    public boolean isAnalyzing() {
        return analyzerRequested && !closed && !terminalFailure;
    }

    @Override
    public int status() {
        return status;
    }

    // ---------- GLES Worker Internals ----------

    private void ensureContext() {
        if (contextReady) return;
        if (initialized) throw new IllegalStateException("Previous EGL initialization incomplete");
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] versions = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("EGL display unavailable");
        }
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            // eglInitialize 失败时 display 仍是有效句柄但初始化计数没有增加：必须立刻
            // 复位，否则 dispose() 会为它执行无配对的 eglTerminate（进程共享默认 display
            // 的计数被误扣），退避重试还会一直扣着资源名额。
            display = EGL14.EGL_NO_DISPLAY;
            throw new IllegalStateException("EGL display unavailable");
        }
        // eglInitialize 成功即置 initialized=true，半初始化失败统一由 dispose() 经 EglTeardown 清理。
        initialized = true;
        int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, scratch, 0) || scratch[0] < 1) {
            throw new IllegalStateException("GLES3 EGL configuration unavailable");
        }
        config = configs[0];
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        parking = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT || parking == EGL14.EGL_NO_SURFACE || !makeCurrent(parking)) {
            throw new IllegalStateException("GLES3 context unavailable");
        }

        String extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        if (extensions == null || !extensions.contains("GL_OES_EGL_image_external_essl3")) {
            throw new IllegalStateException("External textures unavailable");
        }

        oesProgram = program(OES_FRAGMENT);
        copyProgram = program(COPY_FRAGMENT);
        transformLocation = GLES30.glGetUniformLocation(oesProgram, "transform");
        oesTexLocation = GLES30.glGetUniformLocation(oesProgram, "tex");
        copyTexLocation = GLES30.glGetUniformLocation(copyProgram, "tex");

        GLES30.glGenVertexArrays(1, scratch, 0); vao = scratch[0];
        GLES30.glGenTextures(1, scratch, 0); oes = scratch[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);

        texture = new SurfaceTexture(oes);
        producer = new Surface(texture);
        texture.setOnFrameAvailableListener(ignored -> onFrameAvailable(), worker);

        checkGl("ensureContext");
        contextReady = true;
        Log.i(TAG, "ONS GLES3 consumer ready | " + GLES30.glGetString(GLES30.GL_RENDERER));
    }

    private void configureStageInternal(int width, int height) {
        if (closed || terminalFailure) return;
        ensureContext();
        // ensureContext 在 initialized == true 时直接返回；此时上下文可能已被 detach()
        // 回退到 EGL_NO_CONTEXT（或丢失）。GL 删除与 FBO 创建必须确认拿到当前上下文。
        if (!makeCurrent(parking)) throw new IllegalStateException("Consumer context lost");
        if (stageConfigured && stageWidth == width && stageHeight == height) return;
        validateFrameSize(width, height);
        long reserve = Math.multiplyExact(Math.multiplyExact((long) width, height), 16L);

        texture.setDefaultBufferSize(width, height);

        if (inputTexture != 0) {
            scratch[0] = inputTexture;
            GLES30.glDeleteTextures(1, scratch, 0);
            inputTexture = 0;
        }
        if (inputFbo != 0) {
            scratch[0] = inputFbo;
            GLES30.glDeleteFramebuffers(1, scratch, 0);
            inputFbo = 0;
        }
        if (pipeline != null) {
            pipeline.release();
            pipeline = null;
        }

        GLES30.glGenTextures(1, scratch, 0); inputTexture = scratch[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture);
        textureParameters(GLES30.GL_TEXTURE_2D);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);

        GLES30.glGenFramebuffers(1, scratch, 0); inputFbo = scratch[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inputFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, inputTexture, 0);

        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Input FBO incomplete");
        }

        pipeline = new GraphicsFilterPipeline(context, chain, reserve);
        if (analyzerRequested) pipeline.setProfiler(stats, true);

        stageWidth = width;
        stageHeight = height;
        stageConfigured = true;

        if (outputAttached && outputWidth > 0 && outputHeight > 0) {
            viewport = OnsViewport.fit(stageWidth, stageHeight, outputWidth, outputHeight);
        }

        checkGl("configureStage");
        Log.i(TAG, "ONS stage configured " + width + "x" + height);
    }

    private void attach(TextureDisplayWindow window, int width, int height) {
        if (closed || terminalFailure) return;
        ensureContext();
        Surface screen = window.surface;
        if (screen == null || !screen.isValid() || width <= 0 || height <= 0) return;
        if (!detach()) throw new IllegalStateException("Previous display could not disconnect");
        if (closed || terminalFailure || !screen.isValid()) return;
        if (!window.retain()) return;
        outputWindow = window;
        try {
            output = EGL14.eglCreateWindowSurface(display, config, screen, new int[]{EGL14.EGL_NONE}, 0);
            if (output == EGL14.EGL_NO_SURFACE || !makeCurrent(output)) {
                int error = EGL14.eglGetError();
                if (!detach()) throw new IllegalStateException("Failed display could not disconnect");
                if (error == EGL14.EGL_BAD_NATIVE_WINDOW || error == EGL14.EGL_BAD_SURFACE
                        || (!screen.isValid() && error != EGL14.EGL_CONTEXT_LOST)) return;
                throw new IllegalStateException("Display EGLSurface unavailable: " + error);
            }
        } catch (RuntimeException failure) {
            detach();
            throw failure;
        }
        EGL14.eglSwapInterval(display, 0);
        outputWidth = width;
        outputHeight = height;
        outputAttached = true;
        stats.surface(width, height);
        if (stageConfigured) {
            viewport = OnsViewport.fit(stageWidth, stageHeight, width, height);
            if (hasImage) {
                draw();
            }
        }
        if (listener != null) {
            main.post(() -> {
                if (!closed && !terminalFailure && listener != null) listener.onOutputReady(this);
            });
        }
    }

    private boolean detach() {
        outputAttached = false;
        // 断开输出后不得保留旧几何——resume 到首次 re-attach 之间（最长一个轮询周期）
        // 触点仍会用旋转/尺寸变化前的视口换算，整体偏移。与 Framebuffer 的 attach 清视口对齐。
        viewport = null;
        if (display == EGL14.EGL_NO_DISPLAY) {
            if (outputWindow != null) {
                outputWindow.release();
                outputWindow = null;
            }
            return true;
        }
        if (!makeCurrent(parking) && !EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) return false;
        if (output != EGL14.EGL_NO_SURFACE && !EGL14.eglDestroySurface(display, output)) return false;
        output = EGL14.EGL_NO_SURFACE;
        if (outputWindow != null) {
            outputWindow.release();
            outputWindow = null;
        }
        return true;
    }

    private void onFrameAvailable() {
        if (closed || terminalFailure || texture == null) return;
        try {
            if (!makeCurrent(parking)) throw new IllegalStateException("Consumer context lost");
            // updateTexImage 的 GL 错误必须归到本区间内，不能被下一次 checkGl
            // 误判成 happen 在别处；先清继承错误再调用。
            beginGlOperations();
            texture.updateTexImage();
            texture.getTransformMatrix(transform);
            checkGl("updateTexImage");
            if (stageConfigured) {
                normalize();
                hasImage = true;
            }
            if (stageConfigured && outputAttached && output != EGL14.EGL_NO_SURFACE) {
                draw();
            }
        } catch (Throwable failure) {
            fail(asFailure(failure));
        }
    }

    private void normalize() {
        beginGlOperations();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inputFbo);
        setupDraw(stageWidth, stageHeight);
        GLES30.glUseProgram(oesProgram);
        GLES30.glUniformMatrix4fv(transformLocation, 1, false, transform, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindSampler(0, 0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        GLES30.glUniform1i(oesTexLocation, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        checkGl("OES normalization");
    }

    private void draw() {
        OnsViewport v = viewport;
        if (!hasImage || !stageConfigured || !outputAttached || closed || terminalFailure
                || v == null || output == EGL14.EGL_NO_SURFACE) return;
        if (!makeCurrent(output)) throw new IllegalStateException("Display context lost");
        beginGlOperations();
        int result = chain.hasActive() ? pipeline.renderTexture(inputTexture, stageWidth, stageHeight,
                v.surfaceWidth, v.surfaceHeight) : inputTexture;
        checkGl("filter output");
        int outWidth = chain.hasActive() ? pipeline.outputWidth() : stageWidth;
        int outHeight = chain.hasActive() ? pipeline.outputHeight() : stageHeight;
        status = pipeline.isFailed() ? GraphicsStatus.FILTER_FAILED
                : pipeline.bypassReason() != CnnPipeline.BYPASS_NONE ? GraphicsStatus.SIZE_UNAVAILABLE
                : GraphicsStatus.READY;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        setupDraw(v.surfaceWidth, v.surfaceHeight);
        GLES30.glClearColor(0, 0, 0, 1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport(v.left, v.surfaceHeight - v.top - v.height, v.width, v.height);
        GLES30.glUseProgram(copyProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindSampler(0, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, result);
        GLES30.glUniform1i(copyTexLocation, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        checkGl("presentation");
        if (!outputAttached || output == EGL14.EGL_NO_SURFACE) return;
        if (!EGL14.eglSwapBuffers(display, output)) throw new IllegalStateException("Display swap failed");
        boolean firstFrame = !firstPresented;
        firstPresented = true;
        if (analyzerRequested) {
            stats.frame(stageWidth, stageHeight, outWidth, outHeight, v.width, v.height);
            stats.gpu(-1);
            stats.presented();
        }
        if (firstFrame) {
            // 滤镜模式唯一的就绪信号：真实 SurfaceView 已无 producer，探针不可用。
            Listener callback = listener;
            main.post(() -> {
                if (!closed && !terminalFailure && callback != null) callback.onFirstFrame(OnsGraphicsProxy.this);
            });
        }
    }

    private void setupDraw(int width, int height) {
        GLES30.glBindVertexArray(vao);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_STENCIL_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glColorMask(true, true, true, true);
        GLES30.glViewport(0, 0, width, height);
    }

    private boolean makeCurrent(EGLSurface target) {
        return target != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT
                && EGL14.eglMakeCurrent(display, target, target, eglContext);
    }

    private void validateFrameSize(int width, int height) {
        int[] maxTex = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0);
        if (width <= 0 || height <= 0 || width > maxTex[0] || height > maxTex[0]) {
            throw new IllegalStateException("Invalid stage dimensions: " + width + "x" + height);
        }
        long pixels;
        try {
            pixels = Math.multiplyExact((long) width, height);
            if (Math.multiplyExact(pixels, 16L) >= CnnPipeline.TARGET_MEMORY_BUDGET_BYTES) {
                throw new IllegalStateException("Stage exceeds proxy texture budget");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Stage dimensions overflow", overflow);
        }
    }

    private void fail(RuntimeException failure) {
        if (terminalFailure) return;
        terminalFailure = true;
        status = GraphicsStatus.NATIVE_FAILED;
        Log.w(TAG, "ONS graphics proxy terminal failure", failure);
        main.post(() -> {
            if (listener != null) listener.onFailure(this, failure);
        });
    }

    private static RuntimeException asFailure(Throwable failure) {
        if (failure instanceof RuntimeException) return (RuntimeException) failure;
        return new IllegalStateException("ONS graphics worker failure", failure);
    }

    private void finishRelease() {
        boolean lastAttempt = ++releaseAttempts >= MAX_RELEASE_ATTEMPTS;
        boolean disposed = false;
        try {
            disposed = dispose();
        } catch (RuntimeException failure) {
            if (releaseAttempts == 1) Log.w(TAG, "EGL cleanup failed", failure);
        }
        if (disposed) {
            resourceSlot.release();
            thread.quitSafely();
            return;
        }
        if (lastAttempt) {
            // 清理耗尽：保留驱动句柄与资源名额至进程退出。任何线程都不许代替驱动
            // 解绑这块窗口，也不释放名额（否则坏驱动能在同进程反复创建新代理并无界泄漏）。
            synchronized (RETAINED_HANDLES) {
                RETAINED_HANDLES.add(new RetainedHandles(this));
            }
            worker.removeCallbacksAndMessages(null);
            Log.w(TAG, "EGL cleanup exhausted; retaining driver handles and resource slot until process exit");
            thread.quitSafely();
            return;
        }
        // 与 Framebuffer / Siglus 对齐：有界指数退避，避免驱动持续失败时 5 次 1s 连打。
        if (releaseAttempts == 1) Log.w(TAG, "EGL cleanup deferred; retrying with bounded backoff");
        worker.postDelayed(this::finishRelease, EglTeardown.retryDelayMs(RELEASE_RETRY_MS, releaseAttempts));
    }

    private void contextDestroyed() {
        // makeCurrent 失败时保留管线与 GL 名字待重试；确认上下文已销毁后统一回收引用。
        if (pipeline != null) {
            pipeline.abandonContext();
            pipeline = null;
        }
        inputTexture = inputFbo = oes = vao = oesProgram = copyProgram = 0;
    }

    private boolean dispose() {
        // 与 Framebuffer / Siglus 共用 EglTeardown 不变式：无论 current 与否都逐项尝试
        // 解绑/释放，只在销毁成功时置空句柄；未 current 时等 eglDestroyContext 确认后再回收 GL 引用。
        outputAttached = false; // 会话收尾；此后不再有帧可呈现。
        // ① 从未初始化（代理刚创建即 release、60s 超时、未请求 producer）：没有 EGL 资源
        //    可回收，直接成功归还名额与输出窗口。
        if (!initialized && display == EGL14.EGL_NO_DISPLAY) {
            contextDestroyed();
            releaseWindowAndTexture();
            viewport = null;
            return true;
        }
        boolean current = display != EGL14.EGL_NO_DISPLAY && makeCurrent(parking);
        if (current) {
            if (pipeline != null) {
                pipeline.release();
                pipeline = null;
            }
            if (inputTexture != 0) {
                scratch[0] = inputTexture;
                GLES30.glDeleteTextures(1, scratch, 0);
                inputTexture = 0;
            }
            if (inputFbo != 0) {
                scratch[0] = inputFbo;
                GLES30.glDeleteFramebuffers(1, scratch, 0);
                inputFbo = 0;
            }
            if (oes != 0) {
                scratch[0] = oes;
                GLES30.glDeleteTextures(1, scratch, 0);
                oes = 0;
            }
            if (vao != 0) {
                scratch[0] = vao;
                GLES30.glDeleteVertexArrays(1, scratch, 0);
                vao = 0;
            }
            if (oesProgram != 0) {
                GLES30.glDeleteProgram(oesProgram);
                oesProgram = 0;
            }
            if (copyProgram != 0) {
                GLES30.glDeleteProgram(copyProgram);
                copyProgram = 0;
            }
        }
        if (display == EGL14.EGL_NO_DISPLAY) {
            // 从未拿到 display（eglInitialize 失败）：只剩窗口与纹理可放。
            contextDestroyed();
            releaseWindowAndTexture();
            viewport = null;
            return true;
        }
        if (!EglTeardown.unbind(display)) return false;
        output = EglTeardown.destroySurface(display, output);
        parking = EglTeardown.destroySurface(display, parking);
        eglContext = EglTeardown.destroyContext(display, eglContext, this::contextDestroyed);
        boolean handlesGone = output == EGL14.EGL_NO_SURFACE && parking == EGL14.EGL_NO_SURFACE
                && eglContext == EGL14.EGL_NO_CONTEXT;
        if (!handlesGone) return false;
        contextDestroyed();
        // 全部 EGL 句柄销毁后才释放 outputWindow 与 producer/texture —— 反过来会让
        // EGL 仍在引用已 release 的 ANativeWindow（下一次 swap 命中已回收的 BufferQueue）。
        releaseWindowAndTexture();
        // 默认 EGLDisplay 可能被其它初始化方共享：只平衡自己那一次 eglInitialize，
        // 未初始化（初始化中途失败）时不得无配对 eglTerminate。
        if (!EglTeardown.terminate(display, initialized)) return false;
        display = EGL14.EGL_NO_DISPLAY;
        initialized = false;
        contextReady = false;
        viewport = null;
        stats.reset();
        return true;
    }

    /** 释放输出窗口租约、producer 与 consumer 纹理；每项幂等、逐项异常隔离。 */
    private void releaseWindowAndTexture() {
        // 先摘成局部变量再置空：任一项抛异常都不能留下后续项漏放、更不能让重试
        // 反复撞同一处（此前 producer.release() 抛错会漏掉 texture 与 outputWindow）。
        SurfaceTexture nextTexture = texture;
        Surface nextProducer = producer;
        TextureDisplayWindow nextWindow = outputWindow;
        texture = null;
        producer = null;
        outputWindow = null;
        RuntimeException failure = null;
        if (nextTexture != null) {
            try { nextTexture.setOnFrameAvailableListener(null); }
            catch (RuntimeException t) { failure = t; }
        }
        if (nextProducer != null) {
            try { nextProducer.release(); }
            catch (RuntimeException t) { if (failure == null) failure = t; }
        }
        if (nextTexture != null) {
            try { nextTexture.release(); }
            catch (RuntimeException t) { if (failure == null) failure = t; }
        }
        if (nextWindow != null) {
            try { nextWindow.release(); }
            catch (RuntimeException t) { if (failure == null) failure = t; }
        }
        if (failure != null) throw failure;
    }

    private static void textureParameters(int target) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    private static int shader(int kind, String source) {
        int shader = GLES30.glCreateShader(kind);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("Proxy shader: " + log);
        }
        return shader;
    }

    private static int program(String fragment) {
        int vs = shader(GLES30.GL_VERTEX_SHADER, VERTEX);
        int fs = 0;
        int prog = 0;
        try {
            fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment);
            prog = GLES30.glCreateProgram();
            GLES30.glAttachShader(prog, vs);
            GLES30.glAttachShader(prog, fs);
            GLES30.glLinkProgram(prog);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                throw new IllegalStateException("Proxy program: " + GLES30.glGetProgramInfoLog(prog));
            }
            return prog;
        } catch (RuntimeException failure) {
            if (prog != 0) GLES30.glDeleteProgram(prog);
            throw failure;
        } finally {
            GLES30.glDeleteShader(vs);
            if (fs != 0) GLES30.glDeleteShader(fs);
        }
    }

    private static void beginGlOperations() {
        int err = GlErrors.beginGlOperations(GLES30::glGetError);
        if (err == GlErrors.GL_CONTEXT_LOST_KHR) {
            throw new GlErrors.ContextLostException("Proxy observed GL_CONTEXT_LOST_KHR");
        }
        if (err != 0) {
            throw new IllegalStateException("Proxy GL error state did not clear");
        }
    }

    private static void checkGl(String op) {
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            throw new IllegalStateException("GL error " + error + " during " + op);
        }
    }
}
