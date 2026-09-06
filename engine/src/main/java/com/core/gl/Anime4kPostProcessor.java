package com.core.gl;

import android.content.Context;
import android.opengl.GLES30;
import android.util.Log;

import org.cocos2dx.lib.Cocos2dxHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anime4K 后处理器：在引擎帧绘制完成后、eglSwapBuffers 前执行 mpv hook 着色器链。
 *
 * 管线（每个 pass 均为全屏同尺寸）：
 * <pre>
 * 默认帧缓冲 --glCopyTexSubImage2D--> MAIN 纹理(RGBA8)
 *   → pass1: MAIN → conv2d_tf   (RGBA16F FBO)
 *   → pass2: conv2d_tf → conv2d_1_tf
 *   → ...（中间纹理可为负值，必须 fp16 存储）
 *   → 最终pass: MAIN+末级中间纹理 → 默认帧缓冲（覆盖引擎原始帧）
 * </pre>
 *
 * 生命周期契约：所有方法仅允许 GL 线程调用（由 Cocos2dxRenderer 回调驱动）。
 * EGL 上下文销毁重建时 {@link #onSurfaceCreated} 使句柄失效，随后
 * {@link #onSurfaceChanged} 重建。着色器编译或 fp16 能力失败时置 mFailed，
 * 静默禁用不崩溃（调用方仍可安全每帧调用 {@link #drawFrame}）。
 */
public final class Anime4kPostProcessor {

    private static final String TAG = "Anime4kPostProcessor";
    private static final Anime4kPostProcessor sInstance = new Anime4kPostProcessor();

    public static Anime4kPostProcessor getInstance() { return sInstance; }

    /** 命名纹理 + 附件 FBO（MAIN 为 RGBA8 拷贝目标，中间纹理为 RGBA16F） */
    private static final class TexFbo {
        final int[] texture = new int[1];
        final int[] fbo = new int[1];
        final int width;
        final int height;
        final boolean halfFloat;

        TexFbo(int width, int height, boolean halfFloat) {
            this.width = width;
            this.height = height;
            this.halfFloat = halfFloat;
        }
    }

    /** 编译后的 pass 程序与 uniform 缓存 */
    private static final class PassProgram {
        final MpvHookShader.Pass pass;
        final int program;
        final int aPosLoc;
        final int[] samplerLoc;
        final int[] sizeLoc;
        PassProgram(MpvHookShader.Pass pass, int program, int aPosLoc, int[] samplerLoc, int[] sizeLoc) {
            this.pass = pass;
            this.program = program;
            this.aPosLoc = aPosLoc;
            this.samplerLoc = samplerLoc;
            this.sizeLoc = sizeLoc;
        }
    }

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "in vec2 aPos;\n" +
            "out vec2 vPos;\n" +
            "void main() {\n" +
            "    vPos = aPos * 0.5 + 0.5;\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private final List<PassProgram> mPrograms = new ArrayList<>();
    private final Map<String, TexFbo> mTextures = new HashMap<>();
    /** SAVE 目标同时被 BIND 的纹理（如 Deblur 的 MMKERNEL）需要双缓冲交替 */
    private final List<String> mSwapNames = new ArrayList<>();
    private final Map<String, TexFbo> mSwapBackBuffers = new HashMap<>();
    private final Map<String, Boolean> mSwapFlipped = new HashMap<>();

    private FloatBuffer mQuad;
    private int mQuadBuf;
    private int mVertexShader;
    private int mWidth;
    private int mHeight;
    private boolean mGlReady;
    private boolean mFailed;

    private Anime4kPostProcessor() {}

    /** EGL 上下文重建：旧句柄随旧上下文销毁，直接丢弃（不可跨上下文 glDelete） */
    public void onSurfaceCreated() {
        mPrograms.clear();
        mTextures.clear();
        mSwapBackBuffers.clear();
        mSwapFlipped.clear();
        mSwapNames.clear();
        mQuadBuf = 0;
        mVertexShader = 0;
        mGlReady = false;
        // mFailed 不重置：着色器编译失败与上下文无关，进程内持续禁用
    }

    /** 尺寸变化 / 上下文重建后重建 GL 资源（惰性：首帧 drawFrame 时才编译着色器） */
    public void onSurfaceChanged(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (mGlReady && width == mWidth && height == mHeight) return;
        mWidth = width;
        mHeight = height;
        releaseTargets();
        mGlReady = buildTargets();
    }

    /**
     * 每帧入口：拷贝默认帧缓冲 → 跑 pass 链 → 写回默认帧缓冲。
     * 仅 GL 线程调用；mFailed / 未就绪时为空操作。
     */
    public void drawFrame() {
        if (!mGlReady || mFailed || mPrograms.isEmpty()) return;
        try {
            int[] saved = saveGlState();
            try {
                runPasses();
            } finally {
                restoreGlState(saved);
            }
        } catch (Throwable t) {
            mFailed = true;
            Log.e(TAG, "post-processing failed, disabling for this session", t);
        }
    }

    // ===== 资源构建 =====

    private void releaseTargets() {
        for (TexFbo t : mTextures.values()) deleteTexFbo(t);
        for (TexFbo t : mSwapBackBuffers.values()) deleteTexFbo(t);
        mTextures.clear();
        mSwapBackBuffers.clear();
        mSwapFlipped.clear();
        if (mQuadBuf != 0) {
            GLES30.glDeleteBuffers(1, new int[]{mQuadBuf}, 0);
            mQuadBuf = 0;
        }
        // 程序/着色器与尺寸无关，保留复用（上下文重建路径已由 onSurfaceCreated 清空）
    }

    private void deleteTexFbo(TexFbo t) {
        if (t.fbo[0] != 0) GLES30.glDeleteFramebuffers(1, t.fbo, 0);
        if (t.texture[0] != 0) GLES30.glDeleteTextures(1, t.texture, 0);
    }

    private boolean buildTargets() {
        try {
            if (mPrograms.isEmpty() && !compileChain()) return false;

            // 全屏四边形
            float[] quad = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
            mQuad = ByteBuffer.allocateDirect(quad.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mQuad.put(quad).position(0);
            int[] buf = new int[1];
            GLES30.glGenBuffers(1, buf, 0);
            mQuadBuf = buf[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mQuadBuf);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, mQuad, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

            // MAIN 拷贝目标（RGBA8）与各中间纹理（RGBA16F）
            mTextures.put("MAIN", createTexFbo(mWidth, mHeight, false));
            for (PassProgram pp : mPrograms) {
                if (pp.pass.isFinalToScreen()) continue;
                if (!mTextures.containsKey(pp.pass.save)) {
                    mTextures.put(pp.pass.save, createTexFbo(mWidth, mHeight, true));
                }
                // SAVE 目标同时被 BIND → 需要双缓冲防读写冲突
                if (pp.pass.binds.contains(pp.pass.save) && !mSwapNames.contains(pp.pass.save)) {
                    mSwapNames.add(pp.pass.save);
                    mSwapBackBuffers.put(pp.pass.save, createTexFbo(mWidth, mHeight, true));
                    mSwapFlipped.put(pp.pass.save, false);
                }
            }
            Log.i(TAG, "pipeline ready: " + mPrograms.size() + " passes, "
                    + (mTextures.size() - 1) + " intermediates, " + mWidth + "x" + mHeight);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "buildTargets failed", t);
            return false;
        }
    }

    private TexFbo createTexFbo(int w, int h, boolean halfFloat) {
        TexFbo t = new TexFbo(w, h, halfFloat);
        GLES30.glGenTextures(1, t.texture, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.texture[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        if (halfFloat) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null);
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        }
        GLES30.glGenFramebuffers(1, t.fbo, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, t.texture[0], 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("FBO incomplete (0x" + Integer.toHexString(status)
                    + (halfFloat ? ", RGBA16F unsupported" : "") + ")");
        }
        return t;
    }

    private boolean compileChain() {
        try {
            Context ctx = Cocos2dxHelper.getContext();
            String asset = "anime4k/" + Anime4kRuntime.shaderAsset() + ".glsl";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open(asset), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            List<MpvHookShader.Pass> passes = MpvHookShader.parse(sb.toString());

            mVertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
            for (MpvHookShader.Pass pass : passes) {
                mPrograms.add(buildProgram(pass));
            }
            Log.i(TAG, "compiled " + mPrograms.size() + " passes from " + asset);
            return !mPrograms.isEmpty();
        } catch (Throwable t) {
            Log.e(TAG, "shader chain compile failed, disabling Anime4K", t);
            mFailed = true;
            return false;
        }
    }

    private PassProgram buildProgram(MpvHookShader.Pass pass) {
        StringBuilder fs = new StringBuilder();
        fs.append("#version 300 es\n");
        fs.append("precision highp float;\n");
        fs.append("in vec2 vPos;\n");
        fs.append("out vec4 fragColor;\n");
        for (String bind : pass.binds) {
            fs.append("uniform sampler2D u_").append(bind).append(";\n");
            fs.append("uniform vec2 u_").append(bind).append("_size;\n");
        }
        for (String bind : pass.binds) {
            // mpv hook 语义：NAME_tex(pos) 直读、NAME_texOff(off) 邻域偏移读、
            // NAME_pos 当前片元坐标（全屏同尺寸链上与输出坐标一致）、
            // NAME_pt 单纹素步长（由 size 推导，避免逐 bind 两份 uniform）
            fs.append("vec4 ").append(bind).append("_tex(vec2 pos) { return texture(u_")
              .append(bind).append(", pos); }\n");
            fs.append("vec4 ").append(bind).append("_texOff(vec2 off) { return ")
              .append(bind).append("_tex(vPos + off * (1.0 / u_").append(bind).append("_size)); }\n");
            fs.append("#define ").append(bind).append("_pos vPos\n");
            fs.append("#define ").append(bind).append("_size u_").append(bind).append("_size\n");
            fs.append("#define ").append(bind).append("_pt (1.0 / u_").append(bind).append("_size)\n");
        }
        fs.append(pass.body).append('\n');
        fs.append("void main() {\n");
        fs.append("    fragColor = hook();\n");
        if (pass.isFinalToScreen()) {
            // 默认帧缓冲的 alpha 通道语义不保证（RGB 窗口表面拷贝后 alpha 未定义），
            // 游戏画面为全屏不透明内容，强制 alpha=1 避免个别合成路径异常
            fs.append("    fragColor.a = 1.0;\n");
        }
        fs.append("}\n");

        int fsId = compileShader(GLES30.GL_FRAGMENT_SHADER, fs.toString());
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, mVertexShader);
        GLES30.glAttachShader(program, fsId);
        GLES30.glLinkProgram(program);
        GLES30.glDeleteShader(fsId);
        int[] linked = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteProgram(program);
            throw new IllegalStateException("link failed for [" + pass.desc + "]: " + log);
        }

        int n = pass.binds.size();
        int[] samplerLoc = new int[n];
        int[] sizeLoc = new int[n];
        for (int i = 0; i < n; i++) {
            String bind = pass.binds.get(i);
            samplerLoc[i] = GLES30.glGetUniformLocation(program, "u_" + bind);
            sizeLoc[i] = GLES30.glGetUniformLocation(program, "u_" + bind + "_size");
        }
        int aPosLoc = GLES30.glGetAttribLocation(program, "aPos");
        return new PassProgram(pass, program, aPosLoc, samplerLoc, sizeLoc);
    }

    private int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return shader;
    }

    // ===== 每帧执行 =====

    private void runPasses() {
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mQuadBuf);

        // 1. 引擎帧 → MAIN 纹理（源为默认帧缓冲；显式回绑 0 防御引擎残留的非零绑定）
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        TexFbo main = mTextures.get("MAIN");
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, main.texture[0]);
        GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, mWidth, mHeight);

        // 2. pass 链
        for (PassProgram pp : mPrograms) {
            TexFbo target = pp.pass.isFinalToScreen() ? null : mTextures.get(pp.pass.save);
            if (target != null) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo[0]);
                GLES30.glViewport(0, 0, target.width, target.height);
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glViewport(0, 0, mWidth, mHeight);
            }

            GLES30.glUseProgram(pp.program);
            for (int i = 0; i < pp.pass.binds.size(); i++) {
                String bind = pp.pass.binds.get(i);
                TexFbo src = mTextures.get(bind);
                if (src == null) throw new IllegalStateException("unresolved bind: " + bind);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.texture[0]);
                if (pp.samplerLoc[i] >= 0) GLES30.glUniform1i(pp.samplerLoc[i], i);
                if (pp.sizeLoc[i] >= 0) {
                    GLES30.glUniform2f(pp.sizeLoc[i], src.width, src.height);
                }
            }
            GLES30.glEnableVertexAttribArray(pp.aPosLoc);
            GLES30.glVertexAttribPointer(pp.aPosLoc, 2, GLES30.GL_FLOAT, false, 0, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

            // 3. 自引用 SAVE（读写同名纹理）→ 交换双缓冲，后续 pass 读到新结果
            if (target != null && mSwapNames.contains(pp.pass.save)) {
                String name = pp.pass.save;
                TexFbo back = mSwapBackBuffers.get(name);
                TexFbo front = mTextures.get(name);
                mSwapBackBuffers.put(name, front);
                mTextures.put(name, back);
            }
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    // ===== GL 状态保存/恢复（引擎每帧自设状态，此处保守恢复以防万一） =====

    private int[] saveGlState() {
        int[] saved = new int[12];
        // GL_VIEWPORT 为 4 分量 (x,y,w,h)，取 w/h 供恢复
        java.nio.IntBuffer vp = java.nio.IntBuffer.allocate(4);
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vp);
        saved[10] = vp.get(2);
        saved[11] = vp.get(3);
        saved[1] = queryInt(GLES30.GL_CURRENT_PROGRAM);
        saved[2] = queryInt(GLES30.GL_ARRAY_BUFFER_BINDING);
        saved[3] = queryInt(GLES30.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        saved[4] = queryInt(GLES30.GL_ACTIVE_TEXTURE) - GLES30.GL_TEXTURE0;
        saved[5] = queryInt(GLES30.GL_TEXTURE_BINDING_2D);
        saved[6] = queryInt(GLES30.GL_FRAMEBUFFER_BINDING);
        saved[7] = queryInt(GLES30.GL_BLEND);
        saved[8] = queryInt(GLES30.GL_DEPTH_TEST);
        saved[9] = queryInt(GLES30.GL_SCISSOR_TEST);
        return saved;
    }

    private static int queryInt(int pname) {
        java.nio.IntBuffer tmp = java.nio.IntBuffer.allocate(1);
        GLES30.glGetIntegerv(pname, tmp);
        return tmp.get(0);
    }

    private void restoreGlState(int[] s) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, s[6]);
        GLES30.glViewport(0, 0, Math.max(s[10], 1), Math.max(s[11], 1));
        GLES30.glUseProgram(s[1]);
        if (s[7] != 0) GLES30.glEnable(GLES30.GL_BLEND); else GLES30.glDisable(GLES30.GL_BLEND);
        if (s[8] != 0) GLES30.glEnable(GLES30.GL_DEPTH_TEST); else GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        if (s[9] != 0) GLES30.glEnable(GLES30.GL_SCISSOR_TEST); else GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + s[4]);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s[5]);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s[2]);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, s[3]);
    }
}
