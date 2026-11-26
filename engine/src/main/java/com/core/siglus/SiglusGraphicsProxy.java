package com.core.siglus;

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
import com.core.gl.GraphicsStatus;
import com.core.gl.ArtemisGraphicsStats;
import com.core.gl.ArtemisPerformanceOverlay;
import com.core.gl.GraphicsFilterChain;
import com.core.gl.GraphicsFilterPipeline;
import com.core.gl.GraphicsProxyResourceSlots;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Consumes a logical-sized Siglus producer on its own GLES thread. The engine
 * and every handle JNI call remain on one dedicated engine thread. Screen recreation
 * changes only the output EGLSurface, preserving the producer and game VM.
 */
final class SiglusGraphicsProxy implements ArtemisPerformanceOverlay.Source {
    interface Listener {
        void onPrepared(SiglusGraphicsProxy proxy, Surface producer);
        void onPreparationTimeout(SiglusGraphicsProxy proxy);
        void onOutputReady(SiglusGraphicsProxy proxy, SiglusWindowLease window);
        void onFirstFrame(SiglusGraphicsProxy proxy);
        void onFailure(SiglusGraphicsProxy proxy, RuntimeException failure);
    }

    private static final String TAG = "SiglusGraphics";
    private static final long PREPARATION_TIMEOUT_MS = 10000L;
    private static final long RELEASE_RETRY_MS = 1000L;
    private static final int MAX_RELEASE_ATTEMPTS = 5;
    // Permanent driver failures retain only resources until process exit, never
    // the proxy's Listener (which can own an Activity) or its worker thread.
    // Admission slots bound active, closing and retained owners together; never evict one,
    // so the retained list is inherently bounded by GraphicsProxyResourceSlots' capacity.
    // Extra retention caps would force dropping a live handle to free a slot — exactly the
    // cross-thread destruction this retention exists to prevent.
    private static final List<HandleOwner> RETAINED_HANDLES = new ArrayList<>();
    private static final class HandleOwner {
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface parking, output;
        final GraphicsFilterPipeline pipeline;
        final Surface producer;
        final SurfaceTexture texture;
        final SiglusWindowLease window;
        final int[] glNames;
        final boolean initialized;
        final GraphicsProxyResourceSlots.Slot resourceSlot;
        HandleOwner(SiglusGraphicsProxy proxy) {
            display = proxy.display; context = proxy.eglContext; initialized = proxy.initialized;
            resourceSlot = proxy.resourceSlot;
            parking = proxy.parking; output = proxy.output; pipeline = proxy.pipeline;
            producer = proxy.producer; texture = proxy.texture; window = proxy.outputLease;
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
    private final int stageWidth, stageHeight;
    private final boolean analyzerRequested;
    private volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Siglus-GLES");
    private final Handler worker;
    private final GraphicsProxyResourceSlots.Slot resourceSlot;
    private final ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
    private final SiglusPreparationGate preparation = new SiglusPreparationGate();
    private final float[] transform = new float[16];
    private final int[] scratch = new int[1];
    private volatile boolean closed, terminalFailure, stageConfigured;
    private final SiglusOutputState outputState = new SiglusOutputState();
    private volatile SiglusViewport viewport;
    private volatile int status = GraphicsStatus.WAITING;

    private final Runnable preparationTimeout = () -> {
        if (closed || terminalFailure || !preparation.cancel()) return;
        Listener callback = listener;
        // No producer has reached Rust and initialize() cannot attach the display.
        // Close before notification so even a queued late onPrepared cannot transfer it.
        release();
        if (callback != null) callback.onPreparationTimeout(this);
    };

    // GLES-thread state only.
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface parking = EGL14.EGL_NO_SURFACE, output = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private SurfaceTexture texture;
    private Surface producer;
    private GraphicsFilterPipeline pipeline;
    private int oes, inputTexture, inputFbo, oesProgram, copyProgram, vao;
    private int transformLocation;
    private boolean hasImage, frameQueued;
    private volatile long attachedGeneration;
    private boolean initialized;
    private long firstPresentedGeneration;
    private int releaseAttempts;
    private SiglusWindowLease outputLease;
    private final SiglusLatestControl<OutputCommand> outputs = new SiglusLatestControl<>();
    SiglusGraphicsProxy(Context context, GraphicsFilterChain chain, int width, int height,
                        boolean analyzer, Listener listener) {
        this.context = context.getApplicationContext();
        this.chain = chain;
        stageWidth = width; stageHeight = height;
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

    void prepare() {
        main.postDelayed(preparationTimeout, PREPARATION_TIMEOUT_MS);
        worker.post(() -> {
            if (closed) return;
            try {
                initialize();
                // Rust never sees an Android display window. The Activity creates
                // or connects it to this private producer before presentation.
                main.post(() -> {
                    if (!closed && !terminalFailure && listener != null && preparation.deliver()) {
                        main.removeCallbacks(preparationTimeout);
                        listener.onPrepared(this, producer);
                    }
                });
            } catch (RuntimeException failure) { fail(failure); }
        });
    }

    void activate() { stageConfigured = true; stats.reset(); }
    SiglusViewport viewport() { return viewport; }
    boolean hasFailed() { return terminalFailure; }

    void attachOutput(SiglusWindowLease window, int width, int height) {
        if (window == null || !window.retain()) return;
        submitOutput(new OutputCommand(window, width, height, outputState.attach(), null));
    }

    /** Return immediately; the lease, not the UI callback, owns the native window. */
    void detachOutput() { detachOutput(null); }
    void detachOutput(Consumer<Boolean> completion) {
        long generation = outputState.detach(completion != null);
        if (generation != 0L) submitOutput(new OutputCommand(null, 0, 0, generation, completion));
    }

    private void submitOutput(OutputCommand command) {
        if (closed) { command.discard(); return; }
        if (outputs.offer(command) && !worker.post(this::drainOutput)) outputs.close();
    }

    private void drainOutput() {
        OutputCommand command = outputs.take();
        try { if (command != null) command.run(); }
        finally { if (outputs.complete() && !worker.post(this::drainOutput)) outputs.close(); }
    }

    private final class OutputCommand implements SiglusLatestControl.Command {
        private final SiglusWindowLease window;
        private final int width, height;
        private final long generation;
        private final Consumer<Boolean> completion;
        OutputCommand(SiglusWindowLease window, int width, int height, long generation,
                      Consumer<Boolean> completion) {
            this.window = window; this.width = width; this.height = height;
            this.generation = generation; this.completion = completion;
        }
        @Override public void run() {
            boolean success = false;
            try {
                if (closed || generation != outputState.generation) return;
                if (window == null) success = detach();
                else if (!terminalFailure) {
                    success = attach(window, width, height, generation);
                    if (success) {
                        main.post(() -> {
                            if (!closed && !terminalFailure && outputState.wanted
                                    && outputState.generation == generation && listener != null)
                                listener.onOutputReady(SiglusGraphicsProxy.this, window);
                        });
                        if (hasImage) draw();
                    }
                }
            } catch (RuntimeException failure) { fail(failure); }
            finally {
                // The command and EGL attachment own separate references.
                // Failed attachment cleanup cannot consume the UI's reference.
                if (window != null) window.release();
                complete(success);
            }
        }
        @Override public void discard() {
            if (window != null) window.release();
            complete(false);
        }
        private void complete(boolean success) {
            if (completion != null) main.post(() -> completion.accept(success));
        }
    }

    private void initialize() {
        if (stageWidth <= 0 || stageHeight <= 0) throw new IllegalArgumentException("Invalid stage size");
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] versions = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            display = EGL14.EGL_NO_DISPLAY;
            throw new IllegalStateException("EGL display unavailable");
        }
        initialized = true;
        int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
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
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, scratch, 0);
        if (stageWidth > scratch[0] || stageHeight > scratch[0])
            throw new IllegalStateException("Stage exceeds GL texture limit");
        // Reserve the RGBA input plus three producer buffers. This is a texture
        // budget, not a measurement of total driver/engine memory.
        long reserved = Math.multiplyExact(Math.multiplyExact((long) stageWidth, stageHeight), 16L);
        if (reserved >= CnnPipeline.TARGET_MEMORY_BUDGET_BYTES)
            throw new IllegalStateException("Stage exceeds proxy texture budget");
        String extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        if (extensions == null || !extensions.contains("GL_OES_EGL_image_external_essl3"))
            throw new IllegalStateException("External GLES3 textures unavailable");
        oesProgram = program(OES_FRAGMENT); copyProgram = program(COPY_FRAGMENT);
        transformLocation = GLES30.glGetUniformLocation(oesProgram, "transform");
        GLES30.glGenVertexArrays(1, scratch, 0); vao = scratch[0];
        GLES30.glGenTextures(1, scratch, 0); oes = scratch[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        texture = new SurfaceTexture(oes);
        // SurfaceTexture exposes no Java buffer-size query. These dimensions
        // come from decrypted engine configuration and the existing set_surface
        // request; do not claim an independent runtime geometry measurement.
        texture.setDefaultBufferSize(stageWidth, stageHeight);
        producer = new Surface(texture);
        texture.setOnFrameAvailableListener(ignored -> {
            if (closed || frameQueued || texture == null) return;
            frameQueued = true;
            worker.post(() -> {
                frameQueued = false;
                if (closed || texture == null) return;
                try {
                    // Keep draining after an output/filter failure: the UI may
                    // still be inside a producer step and must be able to return
                    // before it can replace the Rust Surface.
                    if (!makeCurrent(parking)) throw new IllegalStateException("Consumer context lost");
                    texture.updateTexImage();
                } catch (RuntimeException failure) {
                    // Keep the queue alive until Rust replaces its Surface.
                    // A blocked producer remains on its engine owner thread;
                    // UI recovery never concurrently destroys the live handle.
                    fail(failure);
                    return;
                }
                if (terminalFailure) return;
                try {
                    texture.getTransformMatrix(transform); hasImage = true;
                    normalize(); draw();
                } catch (RuntimeException failure) { fail(failure); }
            });
        }, worker);
        GLES30.glGenTextures(1, scratch, 0); inputTexture = scratch[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture);
        textureParameters(GLES30.GL_TEXTURE_2D);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, stageWidth, stageHeight,
                0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glGenFramebuffers(1, scratch, 0); inputFbo = scratch[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inputFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, inputTexture, 0);
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Original frame target unavailable");
        pipeline = new GraphicsFilterPipeline(context, chain, reserved);
        if (analyzerRequested) pipeline.setProfiler(stats, analyzerRequested);
        checkGl("initialize");
        Log.i(TAG, "Producer " + stageWidth + "x" + stageHeight + " | "
                + GLES30.glGetString(GLES30.GL_RENDERER));
    }

    private boolean attach(SiglusWindowLease window, int width, int height, long generation) {
        Surface screen = window.surface;
        if (!outputState.wanted || generation != outputState.generation || closed
                || screen == null || !screen.isValid() || width <= 0 || height <= 0) return false;
        SiglusViewport nextViewport = SiglusViewport.fit(stageWidth, stageHeight, width, height);
        if (nextViewport == null) return false;
        if (!detach()) throw new IllegalStateException("Previous display could not disconnect");
        // This command owns a lease across creation, swap and cleanup. A
        // TextureView destruction callback cannot revoke its retained window.
        if (!outputState.wanted || generation != outputState.generation || !screen.isValid()) return false;
        if (!window.retain()) return false;
        outputLease = window;
        output = EGL14.eglCreateWindowSurface(display, config, screen, new int[]{EGL14.EGL_NONE}, 0);
        if (output == EGL14.EGL_NO_SURFACE || !makeCurrent(output)) {
            int error = EGL14.eglGetError();
            if (!detach()) throw new IllegalStateException("Failed display could not disconnect");
            if (error == EGL14.EGL_BAD_NATIVE_WINDOW || error == EGL14.EGL_BAD_SURFACE
                    || (!screen.isValid() && error != EGL14.EGL_CONTEXT_LOST)) return false;
            throw new IllegalStateException("Display EGLSurface unavailable: " + error);
        }
        EGL14.eglSwapInterval(display, 0);
        viewport = nextViewport;
        attachedGeneration = generation;
        stats.surface(width, height);
        return true;
    }

    private boolean detach() {
        if (display == EGL14.EGL_NO_DISPLAY) return true;
        // detach may follow a partial dispose: it only touches display/output,
        // never released producer textures or pipeline objects.
        // Destroying a current EGLSurface defers disconnection. Confirm that
        // it is no longer current before allowing Rust to connect this queue.
        if (!makeCurrent(parking) && !EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) return false;
        if (output != EGL14.EGL_NO_SURFACE && !EGL14.eglDestroySurface(display, output)) return false;
        output = EGL14.EGL_NO_SURFACE;
        if (outputLease != null) { outputLease.release(); outputLease = null; }
        return true;
    }

    private boolean makeCurrent(EGLSurface surface) {
        return surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, eglContext);
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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(oesProgram, "tex"), 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        checkGl("OES normalization");
    }

    private void draw() {
        SiglusViewport v = viewport;
        if (!hasImage || !stageConfigured || !outputState.wanted || closed || terminalFailure
                || attachedGeneration != outputState.generation || v == null || output == EGL14.EGL_NO_SURFACE) return;
        if (!makeCurrent(output)) throw new IllegalStateException("Display context lost");
        beginGlOperations();
        int result = chain.hasActive() ? pipeline.renderTexture(inputTexture, stageWidth, stageHeight,
                v.surfaceWidth, v.surfaceHeight) : inputTexture;
        // Do not clear errors produced by the chain/state restoration before presenting.
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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "tex"), 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        checkGl("presentation");
        if (!outputState.wanted || attachedGeneration != outputState.generation) return;
        if (!EGL14.eglSwapBuffers(display, output)) throw new IllegalStateException("Display swap failed");
        if (firstPresentedGeneration != attachedGeneration) {
            final long presentedGeneration = attachedGeneration;
            firstPresentedGeneration = presentedGeneration;
            main.post(() -> {
                if (!closed && !terminalFailure && outputState.current(presentedGeneration)
                        && listener != null) listener.onFirstFrame(this);
            });
        }
        if (analyzerRequested) {
            stats.frame(stageWidth, stageHeight, outWidth, outHeight, v.width, v.height);
            stats.gpu(-1); // No blocking readback or CPU duration mislabeled as GPU time.
            stats.presented();
        }
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

    /** Clear only inherited errors at an owned normalize/present boundary.
     * End-of-operation checkGl calls retain all errors generated inside it. */
    private static void beginGlOperations() {
        int err = com.core.gl.GlErrors.beginGlOperations(GLES30::glGetError);
        if (err == com.core.gl.GlErrors.GL_CONTEXT_LOST_KHR) {
            throw new com.core.gl.GlErrors.ContextLostException("Proxy observed GL_CONTEXT_LOST_KHR");
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
        Log.e(TAG, "Proxy failed; pausing presentation while retaining native producer ownership", failure);
        main.post(() -> { if (!closed && listener != null) listener.onFailure(this, failure); });
    }

    /** Stop retaining a departing Activity while its native owner finishes. */
    void stopObserving() {
        listener = null;
        preparation.cancel();
        main.removeCallbacks(preparationTimeout);
    }

    /** Call only after Rust has stopped using the producer Surface. */
    void release() {
        if (closed) return;
        closed = true; stageConfigured = false; outputState.detach(true);
        stopObserving();
        outputs.close();
        worker.post(this::finishRelease);
    }

    private void finishRelease() {
        boolean lastAttempt = ++releaseAttempts >= MAX_RELEASE_ATTEMPTS;
        boolean disposed = false;
        try { disposed = dispose(); }
        catch (RuntimeException failure) {
            if (releaseAttempts == 1) Log.w(TAG, "EGL cleanup failed", failure);
        }
        if (disposed) { resourceSlot.release();thread.quitSafely();return; }
        if (lastAttempt) {
            // The worker may exit, but a failed EGL unbind must not transfer
            // native-window ownership to another thread or force-release it.
            synchronized (RETAINED_HANDLES) { RETAINED_HANDLES.add(new HandleOwner(this)); }
            worker.removeCallbacksAndMessages(null);
            Log.w(TAG, "EGL cleanup exhausted; retaining driver handles and resource slot until process exit");
            thread.quitSafely();
        } else {
            if (releaseAttempts == 1) Log.w(TAG, "EGL cleanup deferred; retrying with bounded backoff");
            worker.postDelayed(this::finishRelease, EglTeardown.retryDelayMs(RELEASE_RETRY_MS, releaseAttempts));
        }
    }

    private void deleteGlResources() {
        if (pipeline != null) { pipeline.release();pipeline = null; }
        if (oesProgram != 0) GLES30.glDeleteProgram(oesProgram);
        if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram);
        scratch[0] = inputFbo; GLES30.glDeleteFramebuffers(1, scratch, 0);
        scratch[0] = inputTexture; GLES30.glDeleteTextures(1, scratch, 0);
        scratch[0] = oes; GLES30.glDeleteTextures(1, scratch, 0);
        scratch[0] = vao; GLES30.glDeleteVertexArrays(1, scratch, 0);
        oesProgram = copyProgram = inputFbo = inputTexture = oes = vao = 0;
    }

    private void contextDestroyed() {
        // If makeCurrent fails these names/pipeline stay pending for a retry.
        // Confirmed context destruction is the fallback that reclaims all of them.
        if (pipeline != null) { pipeline.abandonContext();pipeline = null; }
        oesProgram = copyProgram = inputFbo = inputTexture = oes = vao = 0;
    }

    private boolean dispose() {
        if (texture != null) texture.setOnFrameAvailableListener(null);
        if (producer != null) { producer.release();producer = null; }
        if (texture != null) { texture.release();texture = null; }
        boolean current = display != EGL14.EGL_NO_DISPLAY && makeCurrent(parking);
        if (current) deleteGlResources();
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (!EglTeardown.unbind(display)) return false;
            // The display lease outlives the TextureView callback until EGL
            // proves that its native window reference has disconnected.
            output = EglTeardown.destroySurface(display, output);
            parking = EglTeardown.destroySurface(display, parking);
            eglContext = EglTeardown.destroyContext(display, eglContext, this::contextDestroyed);
            boolean handlesGone = output == EGL14.EGL_NO_SURFACE && parking == EGL14.EGL_NO_SURFACE
                    && eglContext == EGL14.EGL_NO_CONTEXT;
            if (!handlesGone) return false;
            if (outputLease != null) { outputLease.release(); outputLease = null; }
            // The default EGLDisplay can have other initialize references. A
            // successful terminate does not prove our failed destroys completed.
            if (!EglTeardown.terminate(display, initialized)) return false;
            initialized = false;
            display = EGL14.EGL_NO_DISPLAY;
        } else contextDestroyed();
        stats.reset();
        return true;
    }

    @Override public ArtemisGraphicsStats stats() { return stats; }
    public boolean isEnabled() { return chain.hasActive() && stageConfigured && !terminalFailure && !closed; }
    @Override public boolean isAnalyzing() { return analyzerRequested && isEnabled(); }
    @Override public int status() { return status; }
}
