package com.core.ethornell;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real BufferQueue consumer. Never use SurfaceTexture(0) without a current GL context. */
final class GpuSurfaceConsumer implements AutoCloseable {
    static final int WIDTH = 320, HEIGHT = 240;
    private final HandlerThread thread = new HandlerThread("Ethornell-test-GL-consumer");
    private final Handler handler;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicInteger frames = new AtomicInteger();
    volatile long timestamp;
    Surface surface;
    private SurfaceTexture texture;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private int textureId;
    final int width, height;
    private final boolean readPixels;
    private int program, framebuffer, colorTexture;
    private FloatBuffer quad;
    private ByteBuffer pixels;
    private volatile byte[] rgbaBottomUp;

    GpuSurfaceConsumer() throws Exception { this(WIDTH, HEIGHT, false); }

    GpuSurfaceConsumer(int width, int height, boolean readPixels) throws Exception {
        this.width = width; this.height = height; this.readPixels = readPixels;
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            onGl(() -> {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                int[] version = new int[2];
                check(EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize");
                EGLConfig[] configs = new EGLConfig[1];
                int[] count = new int[1];
                check(EGL14.eglChooseConfig(display, new int[]{
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0)
                        && count[0] > 0, "eglChooseConfig");
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
                check(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
                pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                        new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
                check(pbuffer != EGL14.EGL_NO_SURFACE, "eglCreatePbufferSurface");
                check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context), "eglMakeCurrent");
                int[] ids = new int[1];
                GLES20.glGenTextures(1, ids, 0); textureId = ids[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                texture = new SurfaceTexture(textureId);
                texture.setDefaultBufferSize(width, height);
                if (readPixels) initReadback();
                texture.setOnFrameAvailableListener(value -> {
                    try {
                        value.updateTexImage(); // Drain on owning current EGL context, not the VM thread.
                        timestamp = value.getTimestamp();
                        if (readPixels) readback(value);
                        frames.incrementAndGet();
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                }, handler);
                surface = new Surface(texture);
                check(surface.isValid(), "consumer Surface");
            });
        } catch (Exception | Error error) {
            try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private void initReadback() {
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 position; varying vec2 uv; uniform mat4 transform;"
                + "void main(){ gl_Position=vec4(position,0.,1.);"
                + "uv=(transform*vec4((position+1.)*.5,0.,1.)).xy; }");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\n"
                + "precision highp float; varying vec2 uv; uniform samplerExternalOES image;"
                + "void main(){ gl_FragColor=texture2D(image,uv); }");
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException("Readback shader link: " + GLES20.glGetProgramInfoLog(program));
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0); colorTexture = id[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenFramebuffers(1, id, 0); framebuffer = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, colorTexture, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete RGBA readback framebuffer");
        pixels = ByteBuffer.allocateDirect(width * height * 4);
        glHealthy("init readback");
    }

    private static int shader(int kind, String source) {
        int id = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(id, source); GLES20.glCompileShader(id);
        int[] ok = new int[1]; GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("Readback shader: " + GLES20.glGetShaderInfoLog(id));
        return id;
    }

    private void readback(SurfaceTexture source) {
        float[] transform = new float[16]; source.getTransformMatrix(transform);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_DITHER);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0);
        int position = GLES20.glGetAttribLocation(program, "position");
        GLES20.glEnableVertexAttribArray(position);
        quad.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
        pixels.clear();
        // GL_RGBA readback normalizes producer BGRA formats; never swap channels to fit expectations.
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        glHealthy("draw/readPixels");
        byte[] copy = new byte[pixels.capacity()]; pixels.position(0); pixels.get(copy);
        rgbaBottomUp = copy;
    }

    private static void glHealthy(String phase) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException(phase + " GL error=" + error);
    }

    /** Top-left physical pixels, after SurfaceTexture transform and explicit GL row conversion. */
    int[] pixel(byte[] snapshot, int x, int y) {
        int offset = ((height - 1 - y) * width + x) * 4;
        return new int[]{snapshot[offset] & 255, snapshot[offset + 1] & 255,
                snapshot[offset + 2] & 255, snapshot[offset + 3] & 255};
    }

    byte[] snapshot() { return rgbaBottomUp; } // immutable publication from GL owner

    String probeDescription(int[][] locations) {
        byte[] image = snapshot();
        if (image == null) return "no readback";
        StringBuilder result = new StringBuilder();
        for (int[] xy : locations) result.append(Arrays.toString(xy)).append('=')
                .append(Arrays.toString(pixel(image, xy[0], xy[1]))).append(' ');
        return result.toString();
    }

    void assertHealthy() {
        Throwable error = failure.get();
        if (error != null) throw new AssertionError("GPU consumer failed", error);
    }

    private void onGl(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        if (!handler.post(task)) throw new IllegalStateException("GL consumer stopped");
        task.get(5, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException(name + " EGL error=" + EGL14.eglGetError());
    }

    @Override public void close() throws Exception {
        try {
            onGl(() -> {
                if (surface != null) { surface.release(); surface = null; }
                if (texture != null) { texture.setOnFrameAvailableListener(null); texture.release(); texture = null; }
                if (textureId != 0) GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                if (colorTexture != 0) GLES20.glDeleteTextures(1, new int[]{colorTexture}, 0);
                if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
                if (program != 0) GLES20.glDeleteProgram(program);
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                    EGL14.eglTerminate(display);
                    EGL14.eglReleaseThread();
                }
            });
        } finally {
            thread.quitSafely();
            thread.join(5000);
            if (thread.isAlive()) throw new AssertionError("GL consumer did not stop");
        }
    }
}
