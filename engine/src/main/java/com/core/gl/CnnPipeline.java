package com.core.gl;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.GLES31Ext;
import android.opengl.GLES32;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Unified, zero-allocation OpenGL ES 3.0 filter and resampling pipeline.
 * Owns shader asset loading, pass graph optimization, immutable config snapshots,
 * memory budget planning, tiled execution, separable resampling (Lanczos/Bilinear/Bicubic),
 * cropped borrowed-texture normalization, and host GL state isolation.
 *
 * <p>External MAIN textures are borrowed, never modified or deleted; a successful
 * {@link #renderTexture} result is an owned RGBA8 texture valid until resize, release,
 * or context loss.
 *
 * <p>Optimizations across CNN/convolution models without image quality loss:
 * 1. Multiple Render Target (MRT) Pass Fusion &amp; CReLU Tap Deduplication: Executes compatible multi-target
 *    convolution passes simultaneously in OpenGL ES 3.0 via layout(location = 0..k) out vec4 and glDrawBuffers
 *    (e.g. Anime4K_Restore_CNN_L 9 -&gt; 5 passes), while pre-sampling 3x3 / 1x1 CReLU positive/negative
 *    activations once per pass to cut single-pixel texture taps by up to 69.7%.
 * 2. Static Dependency Graph: Pre-computes slot indices for all input bindings and target FBOs at initialization,
 *    completely eliminating runtime String hashing and HashMap lookups in the render loop.
 * 3. Mobile TBDR Bandwidth (Tile Load Elimination): Calls glInvalidateFramebuffer on off-screen intermediate
 *    and restore FBOs before drawing, preventing redundant DRAM-to-GMEM tile loads on mobile GPUs.
 * 4. Pass-Specific FBO Binding: Configures dedicated FBOs and draw buffers at resize/setup time, eliminating
 *    glFramebufferTexture2D calls inside the per-frame render loop.
 * 5. Link-time Sampler Uniform Assignment &amp; Pre-bound Samplers: Assigns u_&lt;bind&gt; units once on link and
 *    binds clamp-to-edge linear samplers once per execution.
 * 6. Texture Unit Binding Cache &amp; Uniform Size Caching: Tracks bound textures across passes and uploads size/pt/state
 *    uniforms only when dimensions, global state, or intermediate targets change.
 * 7. Division Elimination &amp; Spatial Coordinate High Precision: Precomputes u_&lt;bind&gt;_pt = vec2(1.0/w, 1.0/h) on CPU
 *    while keeping IEEE highp float for all spatial coordinates and texel-step vectors.
 * 8. P1 Tiered FP16 ALU Acceleration: Retains highp coordinates/sampling headers while switching convolution bodies
 *    (mat4/mat3x4 outside comments) to mediump float (GL_RGBA16F intermediates, &lt;= 1 LSB max error).
 */
public final class CnnPipeline {
    private static final String TAG = "CnnPipeline";
    public static final int BYPASS_NONE = 0, BYPASS_INVALID_SIZE = 1,
            BYPASS_GL_LIMIT = 2, BYPASS_PIPELINE_FAILED = 3, BYPASS_TARGET_UNAVAILABLE = 4;
    /** Texture storage only; leaves room for the borrowed stage and the engine itself. */
    public static final long TARGET_MEMORY_BUDGET_BYTES = 256L * 1024L * 1024L;

    private static final int[] COLOR_ATTACHMENT_0 = new int[]{GLES30.GL_COLOR_ATTACHMENT0};
    private static final int MAX_BINDINGS = 8;
    private static final IntSupplier GL_ERRORS = GLES30::glGetError;

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec2 aPos;\n" +
            "out vec2 vPos;\n" +
            "void main() {\n" +
            "    vPos = aPos * 0.5 + 0.5;\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final Object CONFIG_LOCK = new Object();

    private static volatile boolean sTieredPrecisionEnabled = true;
    private static volatile boolean sRuntimeMrtFusionEnabled = true;

    public static boolean isTieredPrecisionEnabled() {
        return sTieredPrecisionEnabled;
    }

    public static void setTieredPrecisionEnabled(boolean enabled) {
        synchronized (CONFIG_LOCK) {
            sTieredPrecisionEnabled = enabled;
        }
    }

    public static boolean isRuntimeMrtFusionEnabled() {
        return sRuntimeMrtFusionEnabled;
    }

    public static void setRuntimeMrtFusionEnabled(boolean enabled) {
        synchronized (CONFIG_LOCK) {
            sRuntimeMrtFusionEnabled = enabled;
        }
    }

    static GraphicsShaderTransforms.Config snapshotConfig() {
        synchronized (CONFIG_LOCK) {
            return new GraphicsShaderTransforms.Config(
                    sTieredPrecisionEnabled,
                    sRuntimeMrtFusionEnabled);
        }
    }

    interface AssetOpener {
        InputStream open(String asset) throws IOException;
    }

    /** Reused scratch storage; capture/restore and the steady frame path allocate no arrays. */
    static final class GlState {
        static final int MAX_INDEXED_DRAW_BUFFERS = 16;

        interface IndexedDrawBufferOps {
            int getDrawBufferCount();
            void getColorMaski(int index, int[] out, int offset);
            boolean isBlendEnabledi(int index);
            void colorMaski(int index, boolean red, boolean green, boolean blue, boolean alpha);
            void setBlendEnabledi(int index, boolean enabled);
            default void reset() {}
        }

        private static final class DefaultIndexedDrawBufferOps implements IndexedDrawBufferOps {
            private boolean useExt;

            private static boolean hasExtension(String target) {
                int[] count = new int[1];
                GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0);
                for (int i = 0; i < count[0]; i++) {
                    if (target.equals(GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i))) {
                        return true;
                    }
                }
                return false;
            }

            @Override public void reset() {
                useExt = false;
            }

            @Override public int getDrawBufferCount() {
                useExt = false;
                int[] ver = new int[2];
                GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, ver, 0);
                GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, ver, 1);
                boolean isEs32 = ver[0] > 3 || (ver[0] == 3 && ver[1] >= 2);
                int[] maxBufs = new int[1];
                GLES30.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, maxBufs, 0);
                int drawBufferCount = Math.min(MAX_INDEXED_DRAW_BUFFERS, Math.max(0, maxBufs[0]));
                if (!isEs32) {
                    if (!hasExtension("GL_EXT_draw_buffers_indexed")) {
                        return 0;
                    }
                    if (drawBufferCount == 0) {
                        return 0;
                    }
                    useExt = true;
                    try {
                        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}
                        int[] probe = new int[4];
                        GLES32.glGetIntegeri_v(GLES30.GL_COLOR_WRITEMASK, 0, probe, 0);
                        boolean blend0 = GLES31Ext.glIsEnablediEXT(GLES30.GL_BLEND, 0);
                        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                            useExt = false;
                            return -1;
                        }
                        // Probe all EXT write entry points in idempotent noop->toggle->noop order
                        // before touching host state so missing restore symbols never fail mid-restore.
                        GLES31Ext.glColorMaskiEXT(0, probe[0] != 0, probe[1] != 0, probe[2] != 0, probe[3] != 0);
                        if (blend0) {
                            GLES31Ext.glEnableiEXT(GLES30.GL_BLEND, 0);
                            GLES31Ext.glDisableiEXT(GLES30.GL_BLEND, 0);
                            GLES31Ext.glEnableiEXT(GLES30.GL_BLEND, 0);
                        } else {
                            GLES31Ext.glDisableiEXT(GLES30.GL_BLEND, 0);
                            GLES31Ext.glEnableiEXT(GLES30.GL_BLEND, 0);
                            GLES31Ext.glDisableiEXT(GLES30.GL_BLEND, 0);
                        }
                        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                            useExt = false;
                            return -1;
                        }
                    } catch (RuntimeException | UnsatisfiedLinkError unavailable) {
                        useExt = false;
                        return -1;
                    }
                }
                return drawBufferCount;
            }

            @Override public void getColorMaski(int index, int[] out, int offset) {
                GLES32.glGetIntegeri_v(GLES30.GL_COLOR_WRITEMASK, index, out, offset);
            }

            @Override public boolean isBlendEnabledi(int index) {
                return useExt
                        ? GLES31Ext.glIsEnablediEXT(GLES30.GL_BLEND, index)
                        : GLES32.glIsEnabledi(GLES30.GL_BLEND, index);
            }

            @Override public void colorMaski(int index, boolean red, boolean green, boolean blue, boolean alpha) {
                if (useExt) GLES31Ext.glColorMaskiEXT(index, red, green, blue, alpha);
                else GLES32.glColorMaski(index, red, green, blue, alpha);
            }

            @Override public void setBlendEnabledi(int index, boolean enabled) {
                if (useExt) {
                    if (enabled) GLES31Ext.glEnableiEXT(GLES30.GL_BLEND, index);
                    else GLES31Ext.glDisableiEXT(GLES30.GL_BLEND, index);
                } else {
                    if (enabled) GLES32.glEnablei(GLES30.GL_BLEND, index);
                    else GLES32.glDisablei(GLES30.GL_BLEND, index);
                }
            }
        }

        final int[] query = new int[1];
        final int[] viewport = new int[4];
        final int[] colorMask = new int[4];
        final int[][] indexedColorMask = new int[MAX_INDEXED_DRAW_BUFFERS][4];
        final boolean[] indexedBlend = new boolean[MAX_INDEXED_DRAW_BUFFERS];
        final int[] scissorBox = new int[4];
        final int[] textures = new int[MAX_BINDINGS];
        final int[] samplers = new int[MAX_BINDINGS];
        IndexedDrawBufferOps indexedOps = new DefaultIndexedDrawBufferOps();
        boolean indexedSupportChecked;
        boolean indexedExtUnavailable;
        int indexedDrawBufferCount;
        int textureCount;
        int program;
        int vao;
        int arrayBuffer;
        int unpackBuffer;
        int activeTexture;
        int readFramebuffer;
        int drawFramebuffer;
        int readBuffer;
        boolean blend;
        boolean depth;
        boolean stencil;
        boolean scissor;
        boolean cull;
        boolean rasterizerDiscard;
        boolean alphaToCoverage;
        boolean sampleCoverage;
        boolean dither;

        void resetIndexedSupport() {
            indexedSupportChecked = false;
            indexedExtUnavailable = false;
            indexedDrawBufferCount = 0;
            indexedOps.reset();
        }

        int integer(int name) {
            GLES30.glGetIntegerv(name, query, 0);
            return query[0];
        }

        void captureForRender(int count) {
            capture(count, true);
        }

        void capture(int count) {
            capture(count, false);
        }

        private void capture(int count, boolean bypassOnBrokenExt) {
            if (!indexedSupportChecked) {
                int probed = indexedOps.getDrawBufferCount();
                indexedExtUnavailable = probed < 0;
                indexedDrawBufferCount = indexedExtUnavailable ? 0 : Math.min(MAX_INDEXED_DRAW_BUFFERS, probed);
                indexedSupportChecked = true;
            }
            if (indexedExtUnavailable && bypassOnBrokenExt) {
                throw new TargetUnavailableException("GL_EXT_draw_buffers_indexed entry points unavailable", false);
            }
            textureCount = count;
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
            GLES30.glGetIntegerv(GLES30.GL_COLOR_WRITEMASK, colorMask, 0);
            for (int i = 0; i < indexedDrawBufferCount; i++) {
                indexedOps.getColorMaski(i, indexedColorMask[i], 0);
                indexedBlend[i] = indexedOps.isBlendEnabledi(i);
            }
            program = integer(GLES30.GL_CURRENT_PROGRAM);
            vao = integer(GLES30.GL_VERTEX_ARRAY_BINDING);
            arrayBuffer = integer(GLES30.GL_ARRAY_BUFFER_BINDING);
            unpackBuffer = integer(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING);
            activeTexture = integer(GLES30.GL_ACTIVE_TEXTURE);
            readFramebuffer = integer(GLES30.GL_READ_FRAMEBUFFER_BINDING);
            drawFramebuffer = integer(GLES30.GL_DRAW_FRAMEBUFFER_BINDING);
            GLES30.glGetIntegerv(GLES30.GL_SCISSOR_BOX, scissorBox, 0);
            readBuffer = integer(GLES30.GL_READ_BUFFER);
            blend = GLES30.glIsEnabled(GLES30.GL_BLEND);
            depth = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST);
            stencil = GLES30.glIsEnabled(GLES30.GL_STENCIL_TEST);
            scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST);
            cull = GLES30.glIsEnabled(GLES30.GL_CULL_FACE);
            rasterizerDiscard = GLES30.glIsEnabled(GLES30.GL_RASTERIZER_DISCARD);
            alphaToCoverage = GLES30.glIsEnabled(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE);
            sampleCoverage = GLES30.glIsEnabled(GLES30.GL_SAMPLE_COVERAGE);
            dither = GLES30.glIsEnabled(GLES30.GL_DITHER);
            for (int i = 0; i < count; i++) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
                textures[i] = integer(GLES30.GL_TEXTURE_BINDING_2D);
                samplers[i] = integer(GLES30.GL_SAMPLER_BINDING);
            }
            GLES30.glActiveTexture(activeTexture);
        }

        void restore() {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            // glReadBuffer selects on the read-bound framebuffer, so restore after the bind above.
            GLES30.glReadBuffer(readBuffer);
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (indexedDrawBufferCount > 0) {
                for (int i = 0; i < indexedDrawBufferCount; i++) {
                    int[] m = indexedColorMask[i];
                    indexedOps.colorMaski(i, m[0] != 0, m[1] != 0, m[2] != 0, m[3] != 0);
                    indexedOps.setBlendEnabledi(i, indexedBlend[i]);
                }
            } else {
                GLES30.glColorMask(colorMask[0] != 0, colorMask[1] != 0,
                        colorMask[2] != 0, colorMask[3] != 0);
                enable(GLES30.GL_BLEND, blend);
            }
            GLES30.glUseProgram(program);
            enable(GLES30.GL_DEPTH_TEST, depth);
            enable(GLES30.GL_STENCIL_TEST, stencil);
            GLES30.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            enable(GLES30.GL_SCISSOR_TEST, scissor);
            enable(GLES30.GL_CULL_FACE, cull);
            enable(GLES30.GL_RASTERIZER_DISCARD, rasterizerDiscard);
            enable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE, alphaToCoverage);
            enable(GLES30.GL_SAMPLE_COVERAGE, sampleCoverage);
            enable(GLES30.GL_DITHER, dither);
            for (int i = 0; i < textureCount; i++) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i]);
                GLES30.glBindSampler(i, samplers[i]);
            }
            GLES30.glActiveTexture(activeTexture);
            // Attrib pointers, enables, divisors and element binding belong to the VAO.
            // Only our private VAO is changed, so the host's client/VBO attrib state survives.
            GLES30.glBindVertexArray(vao);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, arrayBuffer);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        }

        private static void enable(int capability, boolean enabled) {
            if (enabled) GLES30.glEnable(capability); else GLES30.glDisable(capability);
        }
    }

    /**
     * Optional value uniforms a pass shader may declare; the executor drives each one when the
     * linked program resolves it. Alias lists preserve historical casing variants (bundled CAS
     * assets declare the uppercase form). Extend this table instead of adding executor fields.
     */
    private enum OptionalUniform {
        SHARPNESS("u_sharpness", "u_SHARPNESS"),
        OUTPUT_SIZED("u_output_sized", "u_OUTPUT_SIZED");

        static final OptionalUniform[] ALL = values();
        final String[] aliases;

        OptionalUniform(String... aliases) {
            this.aliases = aliases;
        }
    }

    static final class PassPlan {
        final MpvHookShader.Pass pass;
        final int program;
        final int[] targetSlots; // empty for final target, intermediate slot indices otherwise
        final int[] inputSlots; // -1 for MAIN, >= 0 for intermediate slot
        final int[] samplerLoc;
        final int[] sizeLoc;
        final int[] ptLoc;
        final int outputSizeLoc;
        final int flipYLoc;
        // Resolved locations of OptionalUniform.ALL.length entries; -1 when the pass shader
        // does not declare the uniform. Keeps executor value-drivers decoupled from shader names.
        final int[] optionalUniformLocs;
        int fbo; // pass-specific FBO for intermediate passes
        final int[] drawBuffers; // GL_COLOR_ATTACHMENT0 .. GL_COLOR_ATTACHMENT(K-1)

        PassPlan(MpvHookShader.Pass pass, int program, int[] targetSlots, int[] inputSlots,
                 int[] samplerLoc, int[] sizeLoc, int[] ptLoc, int outputSizeLoc,
                 int flipYLoc, int[] optionalUniformLocs, int fbo, int[] drawBuffers) {
            this.pass = pass;
            this.program = program;
            this.targetSlots = targetSlots;
            this.inputSlots = inputSlots;
            this.samplerLoc = samplerLoc;
            this.sizeLoc = sizeLoc;
            this.ptLoc = ptLoc;
            this.outputSizeLoc = outputSizeLoc;
            this.flipYLoc = flipYLoc;
            this.optionalUniformLocs = optionalUniformLocs;
            this.fbo = fbo;
            this.drawBuffers = drawBuffers;
        }
    }

    private final AssetOpener mAssetOpener;
    private final String mShaderAsset;
    private final boolean mScaleOnly;
    private final boolean mTopLeftCoordinates;
    private final int mNativeScale;
    private final int mMaxOutputScale;
    private final int mHaloPerPass;
    private final boolean mOutputSizedShader;
    private final double mSharpness;
    // GL dispatch channel split: pass execution and owned target FBO/texture allocation
    // (buildPrograms / runPasses / runTiledPasses / releaseTargets / releasePrograms /
    // createTexFbo / deleteTexFbo / compileShader) go through mGl so JVM test fakes can
    // record and drive the pipeline end-to-end; host state capture/restore, VAO/VBO quad
    // setup, and separable resample program paths keep direct GLES30 calls.
    private final GlOps mGl;

    private List<MpvHookShader.Pass> mPasses;
    private GraphicsShaderTargets mTargetPlan;
    private GraphicsShaderTransforms.Config mConfig;
    private int mPlanVersion;
    private long mMaxBudgetBytes = TARGET_MEMORY_BUDGET_BYTES;
    private int mMaxBindings;
    private boolean mIsCnnChain;

    private int mTileCoreSize;
    private GlRenderTarget[] mIntermediateTargets;
    private GlRenderTarget mRestore;
    private GlRenderTarget mTileInput;
    private GlRenderTarget mTileRestore;

    private PassPlan[] mPlans;
    private int mMaxRequiredIntermediateSlot = -1;
    private final int[] mBoundTextures = new int[MAX_BINDINGS];
    private final int[] mQuery = new int[1];
    private final int[] mDimensionQuery = new int[2];

    // Uniform cache to eliminate steady-state per-frame uniform updates
    private int mLastWidth = -1;
    private int mLastHeight = -1;
    private int mLastTargetWidth = -1;
    private int mLastTargetHeight = -1;
    private int mLastFlipY = -1;
    private double mLastSharpness = -1.0;
    private int mLastOutputSized = -1;
    private Object mLastIntermediateTargetsRef;

    // Host GL state, owned targets, crop adapter, and separable resampler state
    private final List<GlRenderTarget> mOwnedTargets = new ArrayList<>();
    private final GlState mState = new GlState();
    private final int[] mQuadBuffer = new int[1];
    private final int[] mQuadVao = new int[1];
    private final int[] mSampler = new int[1];
    private final int[] mBorrowedReadFbo = new int[1];
    private GlRenderTarget mScaleHorizontal;
    private GlRenderTarget mOutput;
    private GlRenderTarget mBorrowedCopy;
    private int mVertexShader;
    private int mScaleProgram, mScaleSamplerLoc, mScaleSizeLoc, mScaleAxisLoc, mScaleOutputSizeLoc;
    private double mBicubicB = 0.33;
    private double mBicubicC = 0.33;
    private double mLanczosAntiRingingStrength = 0;
    private LanczosScale.ResampleKernel mScaleKernel = LanczosScale.DEFAULT_KERNEL;
    private boolean mInputTopLeft;
    private int mTextureLimit, mLimitWidth, mLimitHeight, mBypassReason;
    private boolean mTargetFailed;
    private final GraphicsTargetRetry mTargetRetry = new GraphicsTargetRetry();
    private int mFailedSourceWidth, mFailedSourceHeight, mFailedOutputWidth, mFailedOutputHeight;
    private long mTargetBytes;
    private int mWidth, mHeight;
    private boolean mGlReady;
    private boolean mHalfFloatChecked, mHalfFloatRenderable;
    private boolean mFailed;
    private List<MpvHookShader.Pass> mRawPasses;
    private boolean mRuntimeFusionFused;
    private boolean mRuntimeFusionFallback;
    private boolean mLastTargetRejectionIsMrt;

    private CnnPipeline(AssetOpener opener, String shaderAsset, boolean scaleOnly,
                        boolean topLeftCoordinates, int nativeScale, int maxOutputScale,
                        int haloPerPass, boolean outputSizedShader, double sharpness, int tileCoreSize) {
        this(opener, shaderAsset, scaleOnly, topLeftCoordinates, nativeScale, maxOutputScale,
                haloPerPass, outputSizedShader, sharpness, tileCoreSize, GlOps.GLES30_OPS);
    }

    private CnnPipeline(AssetOpener opener, String shaderAsset, boolean scaleOnly,
                        boolean topLeftCoordinates, int nativeScale, int maxOutputScale,
                        int haloPerPass, boolean outputSizedShader, double sharpness, int tileCoreSize,
                        GlOps gl) {
        this.mAssetOpener = opener;
        this.mShaderAsset = shaderAsset;
        this.mScaleOnly = scaleOnly;
        this.mTopLeftCoordinates = topLeftCoordinates;
        this.mNativeScale = nativeScale;
        this.mMaxOutputScale = maxOutputScale;
        this.mHaloPerPass = haloPerPass;
        this.mOutputSizedShader = outputSizedShader;
        this.mSharpness = sharpness;
        this.mTileCoreSize = tileCoreSize > 0 && haloPerPass > 0 ? tileCoreSize : 0;
        this.mMaxBindings = scaleOnly ? 1 : MAX_BINDINGS;
        this.mGl = gl != null ? gl : GlOps.GLES30_OPS;
    }

    @VisibleForTesting
    CnnPipeline(List<MpvHookShader.Pass> passes, GraphicsShaderTargets targetPlan,
                boolean topLeftCoordinates, int nativeScale, int maxBindings,
                GraphicsShaderTransforms.Config config) {
        this(passes, targetPlan, topLeftCoordinates, nativeScale, maxBindings, config, null);
    }

    @VisibleForTesting
    CnnPipeline(List<MpvHookShader.Pass> passes, GraphicsShaderTargets targetPlan,
                boolean topLeftCoordinates, int nativeScale, int maxBindings,
                GraphicsShaderTransforms.Config config, GlOps gl) {
        if (passes == null || passes.isEmpty() || targetPlan == null || nativeScale < 1) {
            throw new IllegalArgumentException("invalid CNN pipeline specification");
        }
        this.mAssetOpener = null;
        this.mShaderAsset = null;
        this.mScaleOnly = false;
        this.mTopLeftCoordinates = topLeftCoordinates;
        this.mNativeScale = nativeScale;
        this.mMaxOutputScale = nativeScale;
        this.mHaloPerPass = 0;
        this.mOutputSizedShader = false;
        this.mSharpness = 0.4;
        this.mGl = gl != null ? gl : GlOps.GLES30_OPS;
        this.mPasses = passes;
        this.mTargetPlan = targetPlan;
        this.mConfig = config != null ? config : GraphicsShaderTransforms.Config.DEFAULT;
        this.mMaxBindings = Math.min(MAX_BINDINGS, Math.max(1, maxBindings));
        this.mIsCnnChain = MpvHookShader.isCnnConvolutionChain(passes);
    }

    /** Scale/crop-only node without shader passes. */
    static CnnPipeline forLanczos(Context context) {
        if (context == null) throw new IllegalArgumentException("missing graphics context");
        return new CnnPipeline(null, null, true, false, 1, 1, 0, false, 0.4, 0);
    }

    static CnnPipeline forFilter(Context context, GraphicsFilterChain.Step step) {
        if (context == null || step == null) {
            throw new IllegalArgumentException("invalid filter configuration");
        }
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(step.id);
        if (definition == null) {
            throw new IllegalArgumentException("unknown graphics filter: " + step.id);
        }
        if (definition.shaderAsset == null) {
            CnnPipeline node = forLanczos(context);
            node.mScaleKernel = LanczosScale.kernelOf(definition.resampleKernel);
            node.mBicubicB = step.bicubicB;
            node.mBicubicC = step.bicubicC;
            node.mLanczosAntiRingingStrength = step.lanczosAntiRingingStrength;
            return node;
        }
        if (!GraphicsFilterDefinitions.isValidAssetPath(definition.shaderAsset)) {
            throw new IllegalArgumentException("invalid filter asset configuration");
        }
        AssetManager assets = context.getAssets();
        return new CnnPipeline(
                assets != null ? assets::open : null,
                definition.shaderAsset,
                false,
                definition.topLeftCoordinates,
                definition.nativeScale,
                definition.maxOutputScale,
                definition.haloPerPass,
                definition.outputSized && !"none".equals(step.scaleMode),
                step.casSharpness,
                0);
    }

    @VisibleForTesting
    static CnnPipeline createForTesting(AssetOpener opener, String shaderAsset,
                                        boolean topLeftCoordinates, int nativeScale,
                                        int haloPerPass, int tileCoreSize) {
        return createForTesting(opener, shaderAsset, topLeftCoordinates, nativeScale,
                haloPerPass, tileCoreSize, GlOps.GLES30_OPS);
    }

    @VisibleForTesting
    static CnnPipeline createForTesting(AssetOpener opener, String shaderAsset,
                                        boolean topLeftCoordinates, int nativeScale,
                                        int haloPerPass, int tileCoreSize, GlOps gl) {
        if (opener == null || !GraphicsFilterDefinitions.isValidAssetPath(shaderAsset)) {
            throw new IllegalArgumentException("invalid filter test asset configuration");
        }
        return new CnnPipeline(opener, shaderAsset, false, topLeftCoordinates,
                nativeScale, nativeScale, haloPerPass, false, 0.4, tileCoreSize, gl);
    }

    void setInputTopLeft(boolean value) { mInputTopLeft = value; }
    boolean isFailed() { return mFailed; }
    int bypassReason() { return mBypassReason; }
    boolean retriesTargetsAfterDelay() { return mTargetFailed && mTargetRetry.isRetryableFailure(); }
    int outputWidth() { return mGlReady ? mOutput.width : 0; }
    int outputHeight() { return mGlReady ? mOutput.height : 0; }

    /** Only fixed translation-invariant 3x3 CNN assets have a known bounded halo. */
    void setTileCoreSize(int size) {
        if (size > 0 && mHaloPerPass > 0 && mOutputSizedShader) {
            // Output-sized geometry pins the restore target to the request instead of
            // input*nativeScale, so tiling would trip the runTiledPasses invariant.
            // GraphicsFilterPipeline.tileDowngradeAllowed gates this at plan time; fail
            // fast for any direct caller rather than latching a render-time failure.
            throw new IllegalArgumentException("output-sized shader cannot run tiled: " + mShaderAsset);
        }
        mTileCoreSize = size > 0 && mHaloPerPass > 0 ? size : 0;
    }

    private static int computeMaxBindings(List<MpvHookShader.Pass> passes) {
        int max = 1;
        if (passes != null) {
            for (MpvHookShader.Pass pass : passes) {
                max = Math.max(max, pass.binds.size());
            }
        }
        return Math.min(MAX_BINDINGS, max);
    }

    List<MpvHookShader.Pass> loadPasses() {
        if (mScaleOnly || mPasses != null) return mPasses;
        if (mAssetOpener == null) throw new IllegalStateException("filter shader assets not configured");
        mConfig = snapshotConfig();
        StringBuilder source = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                mAssetOpener.open(mShaderAsset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) source.append(line).append('\n');
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read graphics filter asset: " + mShaderAsset, failure);
        }
        mRawPasses = MpvHookShader.parse(source.toString());
        boolean fuse = !mRuntimeFusionFallback && mConfig.runtimeMrtFusionEnabled;
        List<MpvHookShader.Pass> opt = GraphicsShaderTransforms.optimizePasses(mRawPasses, fuse);
        mRuntimeFusionFused = fuse && opt.size() < mRawPasses.size();
        applyPasses(opt);
        return mPasses;
    }

    private void applyPasses(List<MpvHookShader.Pass> passes) {
        mPasses = passes;
        mTargetPlan = new GraphicsShaderTargets(mPasses);
        mIsCnnChain = MpvHookShader.isCnnConvolutionChain(mPasses);
        mMaxBindings = computeMaxBindings(mPasses);
    }

    private static boolean isMrtTargetFailure(TargetUnavailableException failure) {
        if (failure == null || failure.getMessage() == null) return false;
        String msg = failure.getMessage();
        return msg.startsWith("device MRT capacity exceeded")
                || msg.startsWith("MRT FBO incomplete")
                || msg.startsWith("MRT program link failed")
                || msg.startsWith("MRT fragment shader compile failed");
    }

    private boolean canFallbackFromRuntimeFusion(boolean mrtFailure) {
        return !mScaleOnly && mrtFailure && mRuntimeFusionFused && !mRuntimeFusionFallback
                && mRawPasses != null;
    }

    private void fallbackToUnfusedPasses(String reason) {
        GraphicsShaderTransforms.Config preservedConfig = mConfig != null ? mConfig : snapshotConfig();
        mRuntimeFusionFallback = true;
        mRuntimeFusionFused = false;
        mPlanVersion++;
        releaseTargets();
        releasePrograms();
        mConfig = preservedConfig;
        mTargetFailed = false;
        mTargetRetry.reset();
        mBypassReason = BYPASS_NONE;
        applyPasses(GraphicsShaderTransforms.optimizePasses(mRawPasses, false));
        Log.w(TAG, "Runtime MRT fusion unavailable (" + reason + "); falling back to unfused passes");
    }

    int getPlanVersion() {
        return mPlanVersion;
    }

    void setChainBudgetBytes(long maxBudgetBytes) {
        mMaxBudgetBytes = Math.max(0L, Math.min(TARGET_MEMORY_BUDGET_BYTES, maxBudgetBytes));
    }

    private int scratchDimension(int source) {
        if (mTileCoreSize == 0) return source;
        long padded = (long) mTileCoreSize + 2L * mHaloPerPass * mPasses.size();
        return (int) Math.min((long) source, padded);
    }

    static long estimateTargetBytesForPlan(List<MpvHookShader.Pass> passes,
                                           GraphicsShaderTargets targetPlan,
                                           int sw, int sh, int ow, int oh,
                                           int nativeScale, int haloPerPass,
                                           boolean outputSizedShader, int maxOutputScale,
                                           int tileCoreSize) {
        // Tiled estimates are only valid for fixed native-scale geometry; output-sized
        // shaders never tile (setTileCoreSize rejects them), so their estimate stays full-frame.
        int effectiveTileCore = (tileCoreSize > 0 && haloPerPass > 0 && !outputSizedShader) ? tileCoreSize : 0;
        GraphicsShaderGeometry geometry = new GraphicsShaderGeometry(
                sw, sh, ow, oh, nativeScale, outputSizedShader, maxOutputScale);
        if (effectiveTileCore == 0) {
            return geometry.estimate(sw, sh, ow, oh, targetPlan.count, 0, false);
        }
        long outputBytes = geometry.estimate(sw, sh, ow, oh, 0, 0, false);
        int passCount = passes != null ? passes.size() : 0;
        long paddedTile = (long) effectiveTileCore + 2L * haloPerPass * passCount;
        int scratchW = (int) Math.min((long) sw, paddedTile);
        int scratchH = (int) Math.min((long) sh, paddedTile);
        long scratchPixels = (long) scratchW * scratchH;
        long scratchBytesPerPixel = 8L * targetPlan.count + 4L + 4L * nativeScale * nativeScale;
        // Source RGBA8, native-scale RGBA8 tile output, and RGBA16F intermediate slots; full output stays owned.
        return LanczosScale.addBytes(outputBytes, LanczosScale.multiplyBytes(scratchPixels, scratchBytesPerPixel));
    }

    /** CPU estimate used before allocating any node in a complete chain. */
    long estimateTargetBytes(int sw, int sh, int ow, int oh) {
        if (mScaleOnly) {
            if (sw == ow && sh == oh) {
                return LanczosScale.ownedSurfaceBytes(ow, oh);
            }
            return LanczosScale.estimatedOwnedBytes(sw, sh, ow, oh, 0, 0, false, false);
        }
        loadPasses();
        return estimateTargetBytesForPlan(mPasses, mTargetPlan, sw, sh, ow, oh,
                mNativeScale, mHaloPerPass, mOutputSizedShader, mMaxOutputScale, mTileCoreSize);
    }

    int getBuiltPassCount() {
        return mPlans != null ? mPlans.length : 0;
    }

    @VisibleForTesting
    PassPlan[] getPlans() {
        return mPlans != null ? mPlans.clone() : null;
    }

    @VisibleForTesting
    boolean isCnnChain() {
        return mIsCnnChain;
    }

    /**
     * Execute the node at its planned size, then resample only when requested by that node.
     * Invalid/GL-limit requests return zero without permanently failing the pipeline.
     */
    int renderTexture(int textureId, int width, int height, int outWidth, int outHeight) {
        if (mFailed) { mBypassReason = BYPASS_PIPELINE_FAILED; return 0; }
        if (textureId == 0 || !LanczosScale.isPositive(width, height, outWidth, outHeight)) {
            mBypassReason = BYPASS_INVALID_SIZE;
            return 0;
        }
        if (targetsBlocked(width, height, outWidth, outHeight)) return 0;
        if (!beginOwnedGlOperations()) return 0;
        boolean stateCaptured = false;
        int rendered = 0;
        boolean restoreStrictly = false;
        try {
            mState.captureForRender(mMaxBindings);
            stateCaptured = true;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (!ensureTargets(width, height, outWidth, outHeight)) {
                        if (attempt == 0 && canFallbackFromRuntimeFusion(mLastTargetRejectionIsMrt)) {
                            fallbackToUnfusedPasses("MRT capacity exceeded");
                            continue;
                        }
                        return 0;
                    }
                    prepareDrawState();
                    if (mTileCoreSize > 0) {
                        ensureBorrowedReadFramebuffer("tile read framebuffer");
                        runTiledPasses(textureId, width, height, mInputTopLeft, mSampler[0], mBorrowedReadFbo[0]);
                    } else if (!mScaleOnly) {
                        runPasses(textureId, width, height, mIntermediateTargets, mRestore,
                                mInputTopLeft, mSampler[0], mSharpness, mOutputSizedShader);
                    }
                    runScale(mScaleOnly ? textureId : mRestore.texture[0],
                            mScaleOnly ? width : mRestore.width, mScaleOnly ? height : mRestore.height);
                    // Report this node's drawing errors before the next node opens its own boundary.
                    requireGlSuccess("filter node drawing", false);
                    mBypassReason = BYPASS_NONE;
                    mTargetRetry.reset();
                    rendered = mOutput.texture[0];
                    restoreStrictly = true;
                    break;
                } catch (TargetUnavailableException unavailable) {
                    if (attempt == 0 && canFallbackFromRuntimeFusion(isMrtTargetFailure(unavailable))) {
                        fallbackToUnfusedPasses(unavailable.getMessage());
                        continue;
                    }
                    try {
                        targetUnavailable(width, height, outWidth, outHeight, unavailable);
                    } catch (RuntimeException cleanupFailure) {
                        fail(cleanupFailure);
                    }
                    break;
                }
            }
        } catch (TargetUnavailableException unavailable) {
            try {
                targetUnavailable(width, height, outWidth, outHeight, unavailable);
            } catch (RuntimeException cleanupFailure) {
                fail(cleanupFailure);
            }
        } catch (RuntimeException failure) {
            fail(failure);
        } finally {
            if (stateCaptured) {
                try {
                    mState.restore();
                    // Restoration belongs to this node, before the next entry drains inherited errors.
                    requireGlSuccess("filter state restoration", false);
                } catch (RuntimeException restoreFailure) {
                    // Keep node state consistent when state restoration fails (N3): fail(restoreFailure)
                    // sets mFailed=true, releases owned targets, and logs without throwing out of renderTexture
                    // or upgrading an earlier TargetUnavailable bypass unless restoration itself broke GL state.
                    if (restoreStrictly || !mFailed) {
                        fail(restoreFailure);
                        GlErrors.drainBestEffortNoThrow(GLES30.GL_NO_ERROR, GL_ERRORS);
                    }
                    rendered = 0;
                }
            }
        }
        return rendered;
    }

    /** Copy only the integer-aligned game texture rectangle, never the display framebuffer.
     * Borrowed attachment is detached before returning; its texture remains engine-owned. */
    int normalizeBorrowedTexture(int texture, int width, int height,
                                 float u0, float v0, float u1, float v1) {
        if (texture == 0 || width <= 0 || height <= 0
                || !Float.isFinite(u0) || !Float.isFinite(v0) || !Float.isFinite(u1) || !Float.isFinite(v1)
                || u0 < 0f || v0 < 0f || u1 > 1f || v1 > 1f || u1 <= u0 || v1 <= v0) {
            mBypassReason = BYPASS_INVALID_SIZE;
            return 0;
        }
        if (targetsBlocked(width, height, width, height)) return 0;
        if (!beginOwnedGlOperations()) return 0;
        boolean stateCaptured = false;
        int copied = 0;
        boolean restoreStrictly = false;
        try {
            mState.captureForRender(1);
            stateCaptured = true;
            if (mTextureLimit == 0 || mLimitWidth == 0 || mLimitHeight == 0) loadDeviceLimits();
            if (width > mLimitWidth || height > mLimitHeight) {
                mBypassReason = BYPASS_GL_LIMIT;
                return 0;
            }
            // Match Krkrsdl3Graphics.exactCrop integer reconstruction while checking physical
            // source dimensions against GL_MAX_TEXTURE_SIZE (not GL_MAX_VIEWPORT_DIMS, which can be
            // smaller than POT/atlas read textures).
            long roundedAllocW = Math.round(width / (double) (u1 - u0));
            long roundedAllocH = Math.round(height / (double) (v1 - v0));
            if (roundedAllocW < width || roundedAllocH < height
                    || roundedAllocW > mTextureLimit || roundedAllocH > mTextureLimit) {
                mBypassReason = BYPASS_GL_LIMIT;
                return 0;
            }
            int allocationWidth = (int) roundedAllocW;
            int allocationHeight = (int) roundedAllocH;
            int left = Math.round(u0 * allocationWidth), bottom = Math.round(v0 * allocationHeight);
            // u0/v0 in [0,1] and positive allocation sizes guarantee left/bottom >= 0; only
            // the crop rect fitting inside the allocation still needs an explicit check.
            if ((long) left + width > allocationWidth || (long) bottom + height > allocationHeight) {
                mBypassReason = BYPASS_INVALID_SIZE;
                return 0;
            }
            if (mBorrowedCopy == null || mBorrowedCopy.width != width || mBorrowedCopy.height != height) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
                releaseTargets();
                mBorrowedCopy = createTexFbo(width, height, false);
            }
            ensureBorrowedReadFramebuffer("borrowed read framebuffer");
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mBorrowedReadFbo[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, texture, 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER);
            requireGlSuccess("borrowed texture attachment");
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE)
                throw new TargetUnavailableException("borrowed input framebuffer incomplete");
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, mBorrowedCopy.fbo[0]);
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
            requireGlSuccess("borrowed texture copy setup");
            // The crop and native UV remap share [v0,v1]. Both blit rectangles
            // increase in framebuffer Y; (v-v0)/(v1-v0) retains vertex ordering.
            GLES30.glBlitFramebuffer(left, bottom, left + width, bottom + height,
                    0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
            requireGlSuccess("borrowed texture copy");
            mBypassReason = BYPASS_NONE;
            mTargetRetry.reset();
            copied = mBorrowedCopy.texture[0];
            restoreStrictly = true;
        } catch (TargetUnavailableException unavailable) {
            try {
                targetUnavailable(width, height, width, height, unavailable);
            } catch (RuntimeException cleanupFailure) {
                fail(cleanupFailure);
            }
        } catch (RuntimeException failure) {
            fail(failure);
        } finally {
            if (stateCaptured) {
                try {
                    if (mBorrowedReadFbo[0] != 0) {
                        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mBorrowedReadFbo[0]);
                        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                                GLES30.GL_TEXTURE_2D, 0, 0);
                    }
                    mState.restore();
                    requireGlSuccess("borrowed state restoration", false);
                } catch (RuntimeException restoreFailure) {
                    if (restoreStrictly || !mFailed) {
                        fail(restoreFailure);
                        GlErrors.drainBestEffortNoThrow(GLES30.GL_NO_ERROR, GL_ERRORS);
                    }
                    copied = 0;
                }
            }
        }
        return copied;
    }

    /** Discard targets before a whole-chain geometry change/retry; programs remain reusable. */
    void discardTargets() {
        if (!mOwnedTargets.isEmpty()) {
            mState.capture(mMaxBindings);
            try {
                releaseTargets();
            } finally {
                mState.restore();
                GlErrors.drainBestEffortNoThrow(GLES30.GL_NO_ERROR, GL_ERRORS);
            }
        }
        invalidateUniformCache();
        mTargetFailed = false;
        mTargetRetry.reset();
    }

    /** Context loss: old names must not be deleted in a different GL context. */
    void abandonContext() {
        mPlans = null;
        mRuntimeFusionFallback = false;
        resetPassRuntimeState(true);
        mOwnedTargets.clear();
        mOutput = mScaleHorizontal = mBorrowedCopy = null;
        mQuadBuffer[0] = mQuadVao[0] = mSampler[0] = mVertexShader = 0;
        mWidth = mHeight = 0;
        mScaleProgram = mScaleSamplerLoc = mScaleSizeLoc = mScaleAxisLoc = mScaleOutputSizeLoc = 0;
        mTextureLimit = mLimitWidth = mLimitHeight = mBypassReason = 0;
        mBorrowedReadFbo[0] = 0;
        mTargetFailed = false;
        mTargetRetry.reset();
        mTargetBytes = 0;
        mGlReady = false;
        mHalfFloatChecked = mHalfFloatRenderable = false;
        mState.resetIndexedSupport();
        mFailed = false;
    }

    /** Delete owned resources only while the owning context is current. */
    void release() {
        mTargetRetry.reset();
        mHalfFloatChecked = mHalfFloatRenderable = false;
        if (mPlans == null && mOwnedTargets.isEmpty() && mQuadBuffer[0] == 0
                && mQuadVao[0] == 0 && mSampler[0] == 0 && mVertexShader == 0
                && mScaleProgram == 0 && mBorrowedReadFbo[0] == 0) {
            mPlans = null;
            resetPassRuntimeState(true);
            mFailed = false;
            mBypassReason = BYPASS_NONE;
            mTargetFailed = false;
            return;
        }
        mState.capture(mMaxBindings);
        try {
            releaseTargets();
            releasePrograms(true);
            if (mVertexShader != 0) GLES30.glDeleteShader(mVertexShader);
            if (mScaleProgram != 0) {
                if (mState.program == mScaleProgram) mState.program = 0;
                GLES30.glDeleteProgram(mScaleProgram);
            }
            if (mBorrowedReadFbo[0] != 0) {
                GLES30.glDeleteFramebuffers(1, mBorrowedReadFbo, 0);
                mBorrowedReadFbo[0] = 0;
            }
            mScaleProgram = mScaleSamplerLoc = mScaleSizeLoc = mScaleAxisLoc = mScaleOutputSizeLoc = 0;
            mBypassReason = BYPASS_NONE;
            if (mState.arrayBuffer == mQuadBuffer[0]) mState.arrayBuffer = 0;
            if (mState.vao == mQuadVao[0]) mState.vao = 0;
            for (int unit = 0; unit < mState.textureCount; unit++) {
                if (mState.samplers[unit] == mSampler[0]) mState.samplers[unit] = 0;
            }
            if (mQuadBuffer[0] != 0) GLES30.glDeleteBuffers(1, mQuadBuffer, 0);
            if (mQuadVao[0] != 0) GLES30.glDeleteVertexArrays(1, mQuadVao, 0);
            if (mSampler[0] != 0) GLES30.glDeleteSamplers(1, mSampler, 0);
            mQuadBuffer[0] = mQuadVao[0] = mSampler[0] = mVertexShader = 0;
            mFailed = false;
            mTargetFailed = false;
        } finally {
            mState.restore();
            GlErrors.drainBestEffortNoThrow(GLES30.GL_NO_ERROR, GL_ERRORS);
        }
    }

    private void fail(RuntimeException failure) {
        mFailed = true;
        mBypassReason = BYPASS_PIPELINE_FAILED;
        // Native MAIN is never written; external callers can always fall back safely.
        releaseTargets();
        Log.e(TAG, "filter pipeline disabled for this GL context", failure);
    }

    private void releaseTargets() {
        // GLES keeps deleted textures attached to non-currently-bound pass FBOs until explicitly
        // detached; clear every intermediate pass FBO attachment before deleting owned targets.
        if (mPlans != null && mGl != null) {
            boolean resetStateDrawFbo = false;
            for (PassPlan plan : mPlans) {
                if (plan.fbo != 0 && plan.targetSlots.length > 0) {
                    if (mState.drawFramebuffer == plan.fbo) resetStateDrawFbo = true;
                    mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, plan.fbo);
                    for (int k = 0; k < plan.targetSlots.length; k++) {
                        mGl.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER,
                                GLES30.GL_COLOR_ATTACHMENT0 + k, GLES30.GL_TEXTURE_2D, 0, 0);
                    }
                }
            }
            mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,
                    resetStateDrawFbo ? 0 : mState.drawFramebuffer);
            if (resetStateDrawFbo) mState.drawFramebuffer = 0;
        }
        invalidateUniformCache();
        for (int i = 0; i < mOwnedTargets.size(); i++) deleteTexFbo(mOwnedTargets.get(i));
        mOwnedTargets.clear();
        mIntermediateTargets = null;
        mRestore = mTileInput = mTileRestore = null;
        mOutput = mScaleHorizontal = mBorrowedCopy = null;
        mGlReady = false;
        mTargetBytes = 0;
    }

    private void deleteTexFbo(GlRenderTarget target) {
        // The borrowed result expires on resize/release. A host may still have it
        // bound from its previous presentation; never rebind a deleted GL name.
        for (int unit = 0; unit < mState.textureCount; unit++) {
            if (mState.textures[unit] == target.texture[0]) mState.textures[unit] = 0;
        }
        if (mState.readFramebuffer == target.fbo[0]) mState.readFramebuffer = 0;
        if (mState.drawFramebuffer == target.fbo[0]) mState.drawFramebuffer = 0;
        if (target.fbo[0] != 0) mGl.glDeleteFramebuffers(1, target.fbo, 0);
        if (target.texture[0] != 0) mGl.glDeleteTextures(1, target.texture, 0);
        target.fbo[0] = target.texture[0] = 0;
    }

    private boolean ensureTargets(int width, int height, int outWidth, int outHeight) {
        mLastTargetRejectionIsMrt = false;
        if (mFailed) return false;
        if (!LanczosScale.isPositive(width, height, outWidth, outHeight)) {
            mBypassReason = BYPASS_INVALID_SIZE;
            return false;
        }
        if (mGlReady && mWidth == width && mHeight == height
                && mOutput.width == outWidth && mOutput.height == outHeight) return true;
        if (mLimitWidth == 0 || mLimitHeight == 0) loadDeviceLimits();
        GraphicsShaderGeometry geometry = new GraphicsShaderGeometry(width, height, outWidth, outHeight,
                mNativeScale, mOutputSizedShader, mMaxOutputScale);
        if (!LanczosScale.fitsLimits(width, height, outWidth, outHeight, mLimitWidth, mLimitHeight)
                || geometry.width > mLimitWidth || geometry.height > mLimitHeight) {
            if (mBypassReason != BYPASS_GL_LIMIT) Log.w(TAG, "requested filter/resampler size exceeds GL limits: "
                    + width + "x" + height + " -> " + outWidth + "x" + outHeight
                    + "; limit " + mLimitWidth + "x" + mLimitHeight);
            mBypassReason = BYPASS_GL_LIMIT;
            return false;
        }

        // Reject deterministic budget overruns BEFORE compiling shaders, linking programs, or allocating VBO/VAO/FBOs.
        long bytes = estimateTargetBytes(width, height, outWidth, outHeight);
        if (bytes > mMaxBudgetBytes) {
            return targetUnavailable(width, height, outWidth, outHeight,
                    new TargetUnavailableException("texture budget exceeded: " + bytes + " > "
                            + mMaxBudgetBytes, false));
        }

        mGl.glActiveTexture(GLES30.GL_TEXTURE0);
        mGl.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
        // Shader syntax/compilation errors remain permanent; resource/object allocations and
        // deterministic device capability limits (MRT count, RGBA16F renderability) bypass via TargetUnavailableException.
        try {
            if (!mScaleOnly && mPlans == null) {
                mGl.glGetIntegerv(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS, mQuery, 0);
                int availableUnits = Math.min(MAX_BINDINGS, mQuery[0]);
                if (mVertexShader == 0) mVertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
                try {
                    buildPrograms(mVertexShader, availableUnits);
                } catch (RuntimeException failure) {
                    releasePrograms();
                    mGl.glDeleteShader(mVertexShader);
                    mVertexShader = 0;
                    throw failure;
                }
            } else if (mScaleOnly && mVertexShader == 0) {
                mVertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
            }
            LanczosScale.Plan scale = LanczosScale.resample(geometry.width, geometry.height, outWidth, outHeight);
            // Identity passes are currently planned away for scale-only presets, but the program
            // must exist if runScale is ever reached with unchanged sizes (drawing and uploading
            // uniforms with program 0 would fail the node).
            if ((mScaleOnly || scale.horizontal || scale.vertical) && mScaleProgram == 0) buildScaleProgram();
            if (mQuadVao[0] == 0) createQuad();
            releaseTargets();
            mWidth = width;
            mHeight = height;
            mRestore = mScaleOnly ? null : createTexFbo(geometry.width, geometry.height, false);
            mOutput = (scale.horizontal || scale.vertical || mScaleOnly)
                    ? createTexFbo(outWidth, outHeight, false)
                    : mRestore;
            if (scale.horizontal && scale.vertical) {
                // Pure Lanczos needs only ES3 RGBA8 targets. Its horizontal result
                // deliberately accepts 8-bit quantization/negative-lobe clipping;
                // CNN intermediates and their later resample retain RGBA16F.
                mScaleHorizontal = createTexFbo(scale.horizontalWidth, scale.horizontalHeight, !mScaleOnly);
            }
            if (!mScaleOnly) {
                int scratchWidth = scratchDimension(width), scratchHeight = scratchDimension(height);
                mIntermediateTargets = new GlRenderTarget[mTargetPlan.count];
                for (int i = 0; i < mIntermediateTargets.length; i++) {
                    mIntermediateTargets[i] = createTexFbo(scratchWidth, scratchHeight, true);
                }
                if (mTileCoreSize > 0) {
                    mTileInput = createTexFbo(scratchWidth, scratchHeight, false);
                    mTileRestore = createTexFbo(scratchWidth * mNativeScale, scratchHeight * mNativeScale, false);
                }
            }
        } catch (TargetUnavailableException unavailable) {
            return targetUnavailable(width, height, outWidth, outHeight, unavailable);
        }
        mTargetFailed = false;
        mTargetBytes = bytes;
        mGlReady = true;
        int passCount = getBuiltPassCount();
        Log.i(TAG, "pipeline ready: " + passCount + " restore passes, "
                + width + "x" + height + " -> " + outWidth + "x" + outHeight
                + "; texture bytes " + bytes);
        return true;
    }

    /** Caches the device texture/viewport limits once per context; both size gates share this block. */
    private void loadDeviceLimits() {
        mQuery[0] = 0;
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, mQuery, 0);
        mTextureLimit = mQuery[0];
        mDimensionQuery[0] = mDimensionQuery[1] = 0;
        GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, mDimensionQuery, 0);
        mLimitWidth = Math.min(mTextureLimit, mDimensionQuery[0]);
        mLimitHeight = Math.min(mTextureLimit, mDimensionQuery[1]);
    }

    private boolean targetsBlocked(int sw, int sh, int ow, int oh) {
        if (!mTargetFailed) return false;
        boolean sameGeometry = sw == mFailedSourceWidth && sh == mFailedSourceHeight
                && ow == mFailedOutputWidth && oh == mFailedOutputHeight;
        if (sameGeometry) {
            if (!mTargetRetry.beginAttempt()) {
                mBypassReason = BYPASS_TARGET_UNAVAILABLE;
                return true;
            }
        } else mTargetRetry.reset();
        mTargetFailed = false;
        return false;
    }

    private boolean targetUnavailable(int sw, int sh, int ow, int oh,
                                      TargetUnavailableException unavailable) {
        mLastTargetRejectionIsMrt = isMrtTargetFailure(unavailable);
        releaseTargets(); // Also release targets created before the failing allocation.
        requireGlSuccess("target bypass cleanup", false);
        mTargetFailed = true;
        mTargetRetry.failed(unavailable.retryAfterDelay);
        mFailedSourceWidth = sw; mFailedSourceHeight = sh;
        mFailedOutputWidth = ow; mFailedOutputHeight = oh;
        mBypassReason = BYPASS_TARGET_UNAVAILABLE;
        Log.w(TAG, "filter/resampler target bypassed: " + sw + "x" + sh + " -> "
                + ow + "x" + oh + ": " + unavailable.getMessage());
        return false;
    }

    private void createQuad() {
        FloatBuffer quad = ByteBuffer.allocateDirect(8 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}).position(0);
        try {
            GLES30.glGenBuffers(1, mQuadBuffer, 0);
            requireTargetName(mQuadBuffer[0], "quad buffer");
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mQuadBuffer[0]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * Float.BYTES, quad, GLES30.GL_STATIC_DRAW);
            GLES30.glGenVertexArrays(1, mQuadVao, 0);
            requireTargetName(mQuadVao[0], "quad VAO");
            GLES30.glBindVertexArray(mQuadVao[0]);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
            GLES30.glGenSamplers(1, mSampler, 0);
            requireTargetName(mSampler[0], "sampler");
            GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            requireGlSuccess("quad setup", true);
        } catch (RuntimeException failure) {
            if (mState.arrayBuffer == mQuadBuffer[0]) mState.arrayBuffer = 0;
            if (mState.vao == mQuadVao[0]) mState.vao = 0;
            for (int unit = 0; unit < mState.textureCount; unit++) {
                if (mState.samplers[unit] == mSampler[0]) mState.samplers[unit] = 0;
            }
            if (mQuadBuffer[0] != 0) GLES30.glDeleteBuffers(1, mQuadBuffer, 0);
            if (mQuadVao[0] != 0) GLES30.glDeleteVertexArrays(1, mQuadVao, 0);
            if (mSampler[0] != 0) GLES30.glDeleteSamplers(1, mSampler, 0);
            mQuadBuffer[0] = mQuadVao[0] = mSampler[0] = 0;
            GlErrors.drainBestEffortNoThrow(GLES30.GL_NO_ERROR, GL_ERRORS);
            throw failure;
        }
    }

    /** ES 3.2 makes RGBA16F color-renderable; earlier contexts require an extension. */
    private boolean halfFloatRenderable() {
        if (mHalfFloatChecked) return mHalfFloatRenderable;
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, mDimensionQuery, 0);
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, mDimensionQuery, 1);
        int major = mDimensionQuery[0], minor = mDimensionQuery[1];
        mHalfFloatRenderable = major > 3 || major == 3 && minor >= 2;
        if (!mHalfFloatRenderable) {
            GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, mQuery, 0);
            int count = mQuery[0];
            for (int i = 0; i < count; i++) {
                String extension = GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i);
                if ("GL_EXT_color_buffer_float".equals(extension)
                        || "GL_EXT_color_buffer_half_float".equals(extension)) {
                    mHalfFloatRenderable = true;
                    break;
                }
            }
        }
        mHalfFloatChecked = true;
        return mHalfFloatRenderable;
    }

    /** Establish the error boundary once, before capturing/changing the host state. */
    private boolean beginOwnedGlOperations() {
        if (mFailed) { mBypassReason = BYPASS_PIPELINE_FAILED; return false; }
        for (int i = 0; i < GlErrors.MAX_GL_ERRORS; i++) {
            if (GLES30.glGetError() == GLES30.GL_NO_ERROR) return true;
        }
        mFailed = true;
        mGlReady = false;
        mBypassReason = BYPASS_PIPELINE_FAILED;
        Log.e(TAG, "GL error state did not clear before filter processing");
        return false;
    }

    private static void requireGlSuccess(String operation) {
        requireGlSuccess(operation, true);
    }

    private static void requireGlSuccess(String operation, boolean targetFailure) {
        GlErrors.requireGlSuccess(operation, targetFailure, GL_ERRORS);
    }

    private static void requireTargetName(int name, String operation) {
        GlErrors.requireTargetName(name, operation, GL_ERRORS);
    }

    private GlRenderTarget createTexFbo(int width, int height, boolean halfFloat) {
        if (halfFloat && !halfFloatRenderable()) {
            throw new TargetUnavailableException("RGBA16F color rendering unavailable", false);
        }
        GlRenderTarget target = new GlRenderTarget(width, height);
        try {
            mGl.glGenTextures(1, target.texture, 0);
            if (target.texture[0] == 0) {
                GlErrors.requireGlSuccess("texture name allocation", true, mGl::glGetError);
                throw new TargetUnavailableException("cannot allocate texture");
            }
            mGl.glBindTexture(GLES30.GL_TEXTURE_2D, target.texture[0]);
            mGl.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            mGl.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            mGl.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            mGl.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            mGl.glTexImage2D(GLES30.GL_TEXTURE_2D, 0,
                    halfFloat ? GLES30.GL_RGBA16F : GLES30.GL_RGBA8, width, height, 0,
                    GLES30.GL_RGBA, halfFloat ? GLES30.GL_HALF_FLOAT : GLES30.GL_UNSIGNED_BYTE, null);
            mGl.glGenFramebuffers(1, target.fbo, 0);
            if (target.fbo[0] == 0) {
                GlErrors.requireGlSuccess("framebuffer name allocation", true, mGl::glGetError);
                throw new TargetUnavailableException("cannot allocate framebuffer");
            }
            mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, target.fbo[0]);
            mGl.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, target.texture[0], 0);
            int status = mGl.glCheckFramebufferStatus(GLES30.GL_DRAW_FRAMEBUFFER);
            // Check even an incomplete FBO: glTexImage2D may have left OOM flags.
            GlErrors.requireGlSuccess("render target allocation", true, mGl::glGetError);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new TargetUnavailableException("FBO incomplete: 0x" + Integer.toHexString(status)
                        + (halfFloat ? " (RGBA16F)" : " (RGBA8)"));
            }
            mOwnedTargets.add(target);
            return target;
        } catch (RuntimeException failure) {
            deleteTexFbo(target);
            GlErrors.requireGlSuccess("render target cleanup", false, mGl::glGetError);
            throw failure;
        }
    }

    private void buildScaleProgram() {
        int fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER,
                LanczosScale.resampleFragmentShader(mScaleKernel, mBicubicB, mBicubicC, mLanczosAntiRingingStrength));
        int program = 0;
        try {
            program = GLES30.glCreateProgram();
            requireTargetName(program, "resampler program");
            GLES30.glAttachShader(program, mVertexShader);
            GLES30.glAttachShader(program, fragmentShader);
            GLES30.glLinkProgram(program);
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, mQuery, 0);
            if (mQuery[0] == 0) {
                String linkLog = GLES30.glGetProgramInfoLog(program);
                requireGlSuccess("resampler program link");
                throw new IllegalStateException("resampler link failed: " + linkLog);
            }
            mScaleSamplerLoc = GLES30.glGetUniformLocation(program, LanczosScale.UNIFORM_SOURCE);
            mScaleSizeLoc = GLES30.glGetUniformLocation(program, LanczosScale.UNIFORM_SOURCE_SIZE);
            mScaleAxisLoc = GLES30.glGetUniformLocation(program, LanczosScale.UNIFORM_AXIS);
            mScaleOutputSizeLoc = GLES30.glGetUniformLocation(program, LanczosScale.UNIFORM_OUTPUT_SIZE);
            if (mScaleSamplerLoc < 0 || mScaleSizeLoc < 0 || mScaleAxisLoc < 0 || mScaleOutputSizeLoc < 0) {
                throw new IllegalStateException("resampler shader missing required uniforms");
            }
            mScaleProgram = program;
            program = 0;
        } catch (RuntimeException failure) {
            if (program != 0) GLES30.glDeleteProgram(program);
            throw failure;
        } finally {
            GLES30.glDeleteShader(fragmentShader);
        }
    }

    private void runScale(int inputTexture, int width, int height) {
        if (!mScaleOnly && mOutput == mRestore) return;
        boolean horizontal = width != mOutput.width;
        boolean vertical = height != mOutput.height;
        if (horizontal && vertical) {
            drawScale(inputTexture, width, height, mScaleHorizontal, 0);
            drawScale(mScaleHorizontal.texture[0], mScaleHorizontal.width, mScaleHorizontal.height, mOutput, 1);
        } else if (horizontal || vertical) {
            drawScale(inputTexture, width, height, mOutput, horizontal ? 0 : 1);
        } else {
            // Defensive pass-through: a scale-only node reached with identical input/output sizes
            // fills its owned output target via 1:1 NEAREST blit.
            passThroughBlit(inputTexture, width, height);
        }
    }

    private void passThroughBlit(int inputTexture, int width, int height) {
        ensureBorrowedReadFramebuffer("scale pass-through read framebuffer");
        try {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mBorrowedReadFbo[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, inputTexture, 0);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, mOutput.fbo[0]);
            GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, mOutput.width, mOutput.height,
                    GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mBorrowedReadFbo[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, 0, 0);
        }
    }

    private void drawScale(int texture, int width, int height, GlRenderTarget target, int axis) {
        if (target == null || texture == target.texture[0]) throw new IllegalStateException("invalid resampler target");
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, target.fbo[0]);
        GLES30.glViewport(0, 0, target.width, target.height);
        GLES30.glUseProgram(mScaleProgram);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glBindSampler(0, mSampler[0]);
        GLES30.glUniform1i(mScaleSamplerLoc, 0);
        GLES30.glUniform2i(mScaleSizeLoc, width, height);
        GLES30.glUniform1i(mScaleAxisLoc, axis);
        if (mScaleOutputSizeLoc >= 0) GLES30.glUniform2i(mScaleOutputSizeLoc, target.width, target.height);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void ensureBorrowedReadFramebuffer(String operation) {
        if (mBorrowedReadFbo[0] != 0) return;
        GLES30.glGenFramebuffers(1, mBorrowedReadFbo, 0);
        requireTargetName(mBorrowedReadFbo[0], operation);
    }

    void prepareDrawState() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_STENCIL_TEST);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_RASTERIZER_DISCARD);
        GLES30.glDisable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE);
        GLES30.glDisable(GLES30.GL_SAMPLE_COVERAGE);
        GLES30.glDisable(GLES30.GL_DITHER);
        if (mState.indexedDrawBufferCount > 0) {
            for (int i = 0; i < mState.indexedDrawBufferCount; i++) {
                mState.indexedOps.setBlendEnabledi(i, false);
                mState.indexedOps.colorMaski(i, true, true, true, true);
            }
        } else {
            GLES30.glDisable(GLES30.GL_BLEND);
            GLES30.glColorMask(true, true, true, true);
        }
        GLES30.glBindVertexArray(mQuadVao[0]);
    }

    void buildPrograms(int vertexShader) {
        buildPrograms(vertexShader, MAX_BINDINGS);
    }

    /**
     * Compiles and links all pass programs for the CNN pipeline.
     * Pre-binds sampler uniforms (u_&lt;bind&gt; = texture unit i) once upon link time.
     */
    void buildPrograms(int vertexShader, int availableUnits) {
        if (mPlans != null) return;
        loadPasses();
        int clampedAvailable = Math.min(MAX_BINDINGS, Math.max(1, availableUnits));
        int maxRequiredBindings = 1;
        for (MpvHookShader.Pass pass : mPasses) {
            int count = pass.binds.size();
            if (count > clampedAvailable) {
                throw new IllegalStateException("too many shader texture inputs: "
                        + count + " > " + clampedAvailable + " in " + pass.desc);
            }
            maxRequiredBindings = Math.max(maxRequiredBindings, count);
        }
        mMaxBindings = maxRequiredBindings;
        int passCount = mPasses.size();
        if (mTargetPlan.passTargetSlots == null || mTargetPlan.passTargetSlots.length != passCount) {
            throw new IllegalStateException("target plan pass count mismatch");
        }
        int[][] staticInputSlots = GraphicsShaderTransforms.resolveStaticInputSlots(mPasses, mTargetPlan);
        int maxRequiredSlot = -1;

        mQuery[0] = 0;
        mGl.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, mQuery, 0);
        int savedProgram = mQuery[0];

        mQuery[0] = 0;
        mGl.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, mQuery, 0);
        int maxDrawBuffers = mQuery[0];
        mQuery[0] = 0;
        mGl.glGetIntegerv(GLES30.GL_MAX_COLOR_ATTACHMENTS, mQuery, 0);
        int maxColorAttachments = mQuery[0];

        try {
            mPlans = new PassPlan[passCount];
            for (int p = 0; p < passCount; p++) {
                MpvHookShader.Pass pass = mPasses.get(p);
                int bindCount = pass.binds.size();
                if (bindCount > mMaxBindings) {
                    throw new IllegalStateException("pass exceeds max texture bindings: "
                            + bindCount + " > " + mMaxBindings + " in " + pass.desc);
                }

                boolean finalPass = pass.isFinalToScreen();
                int[] targetSlots = finalPass ? new int[0] : mTargetPlan.passTargetSlots[p];
                for (int slot : targetSlots) {
                    if (slot > maxRequiredSlot) maxRequiredSlot = slot;
                }
                for (int slot : staticInputSlots[p]) {
                    if (slot > maxRequiredSlot) maxRequiredSlot = slot;
                }

                int[] drawBuffers;
                if (finalPass) {
                    drawBuffers = COLOR_ATTACHMENT_0;
                } else {
                    if (targetSlots.length > maxDrawBuffers || targetSlots.length > maxColorAttachments) {
                        throw new TargetUnavailableException(
                                "device MRT capacity exceeded for pass " + pass.desc + ": requires "
                                        + targetSlots.length + " targets (max draw buffers=" + maxDrawBuffers
                                        + ", max color attachments=" + maxColorAttachments + ")", false);
                    }
                    drawBuffers = new int[targetSlots.length];
                    for (int k = 0; k < targetSlots.length; k++) {
                        drawBuffers[k] = GLES30.GL_COLOR_ATTACHMENT0 + k;
                    }
                }

                int fbo = 0;
                int fragmentShader = 0;
                int program = 0;
                try {
                    if (!finalPass) {
                        mQuery[0] = 0;
                        mGl.glGenFramebuffers(1, mQuery, 0);
                        fbo = mQuery[0];
                        GlErrors.requireTargetName(fbo, "MRT FBO for CNN pass " + p, mGl::glGetError);
                    }

                    String fragmentSource = GraphicsShaderTransforms.generateFragmentShader(
                            pass, true, mConfig.tieredPrecisionEnabled);
                    try {
                        fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
                    } catch (IllegalStateException compileFailure) {
                        if (targetSlots.length > 1 && canFallbackFromRuntimeFusion(true)) {
                            throw new TargetUnavailableException("MRT fragment shader compile failed for "
                                    + pass.desc + ": " + compileFailure.getMessage(), false);
                        }
                        throw compileFailure;
                    }
                    program = mGl.glCreateProgram();
                    GlErrors.requireTargetName(program, "GL program for pass " + pass.desc, mGl::glGetError);
                    mGl.glAttachShader(program, vertexShader);
                    mGl.glAttachShader(program, fragmentShader);
                    mGl.glLinkProgram(program);
                    mQuery[0] = 0;
                    mGl.glGetProgramiv(program, GLES30.GL_LINK_STATUS, mQuery, 0);
                    if (mQuery[0] == 0) {
                        String linkLog = mGl.glGetProgramInfoLog(program);
                        GlErrors.requireGlSuccess("program link for " + pass.desc, true, mGl::glGetError);
                        if (targetSlots.length > 1 && canFallbackFromRuntimeFusion(true)) {
                            throw new TargetUnavailableException("MRT program link failed for "
                                    + pass.desc + ": " + linkLog, false);
                        }
                        throw new IllegalStateException("link failed for " + pass.desc + ": " + linkLog);
                    }

                    int[] samplerLoc = new int[bindCount];
                    int[] sizeLoc = new int[bindCount];
                    int[] ptLoc = new int[bindCount];

                    mGl.glUseProgram(program);
                    for (int b = 0; b < bindCount; b++) {
                        String bind = pass.binds.get(b);
                        samplerLoc[b] = mGl.glGetUniformLocation(program, "u_" + bind);
                        sizeLoc[b] = mGl.glGetUniformLocation(program, "u_" + bind + "_size");
                        ptLoc[b] = mGl.glGetUniformLocation(program, "u_" + bind + "_pt");

                        // Optimization: Assign sampler uniform once at link time; never touched again in the render loop.
                        if (samplerLoc[b] >= 0) {
                            mGl.glUniform1i(samplerLoc[b], b);
                        }
                    }

                    int outputSizeLoc = mGl.glGetUniformLocation(program, "u_OUTPUT_size");
                    int flipYLoc = mGl.glGetUniformLocation(program, "u_FLIP_Y");
                    int[] optionalUniformLocs = new int[OptionalUniform.ALL.length];
                    for (int u = 0; u < OptionalUniform.ALL.length; u++) {
                        int loc = -1;
                        for (String alias : OptionalUniform.ALL[u].aliases) {
                            loc = mGl.glGetUniformLocation(program, alias);
                            if (loc >= 0) break;
                        }
                        optionalUniformLocs[u] = loc;
                    }

                    mPlans[p] = new PassPlan(pass, program, targetSlots, staticInputSlots[p],
                            samplerLoc, sizeLoc, ptLoc, outputSizeLoc, flipYLoc, optionalUniformLocs,
                            fbo, drawBuffers);
                } catch (RuntimeException failure) {
                    if (fbo != 0) {
                        mQuery[0] = fbo;
                        mGl.glDeleteFramebuffers(1, mQuery, 0);
                    }
                    if (program != 0) {
                        if (savedProgram == program) savedProgram = 0;
                        mGl.glUseProgram(0);
                        mGl.glDeleteProgram(program);
                    }
                    throw failure;
                } finally {
                    if (fragmentShader != 0) {
                        mGl.glDeleteShader(fragmentShader);
                    }
                }
            }

            mMaxRequiredIntermediateSlot = maxRequiredSlot;
            GlErrors.requireGlSuccess("CNN program setup", true, mGl::glGetError);
        } catch (RuntimeException failure) {
            releasePrograms();
            throw failure;
        } finally {
            mGl.glUseProgram(savedProgram);
        }

        invalidateUniformCache();
    }

    /** Run unchanged filter passes with a conservative per-pass halo, then copy only the core. */
    void runTiledPasses(int mainTexture, int width, int height,
                        boolean inputTopLeft, int sampler, int readFbo) {
        if (mTileCoreSize <= 0 || mTileInput == null || mTileRestore == null || mRestore == null || mPasses == null) {
            throw new IllegalStateException("tile scratch targets not initialized");
        }
        int halo = (int) Math.min((long) height + width, (long) mHaloPerPass * mPasses.size());
        int scratchWidth = mTileInput.width, scratchHeight = mTileInput.height;
        if (scratchWidth <= 0 || scratchWidth > width || scratchHeight <= 0 || scratchHeight > height
                || mTileRestore.width != (long) scratchWidth * mNativeScale
                || mTileRestore.height != (long) scratchHeight * mNativeScale
                || mRestore.width != (long) width * mNativeScale
                || mRestore.height != (long) height * mNativeScale) {
            throw new IllegalStateException("tile geometry invariant violated");
        }
        try {
            for (int top = 0; top < height; top += mTileCoreSize) {
                int y = Math.max(0, Math.min(top - halo, height - scratchHeight));
                int coreHeight = Math.min(mTileCoreSize, height - top);
                for (int left = 0; left < width; left += mTileCoreSize) {
                    int x = Math.max(0, Math.min(left - halo, width - scratchWidth));
                    int coreWidth = Math.min(mTileCoreSize, width - left);
                    if (left < x || left - x + coreWidth > scratchWidth
                            || top < y || top - y + coreHeight > scratchHeight) {
                        throw new IllegalStateException("tile core crop exceeds scratch bounds");
                    }
                    // Fixed-size crops stay inside the image. At real edges, each pass retains normal clamp semantics.
                    mGl.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo);
                    mGl.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                            GLES30.GL_TEXTURE_2D, mainTexture, 0);
                    int status = mGl.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER);
                    GlErrors.requireGlSuccess("tile input attachment", true, mGl::glGetError);
                    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                        throw new TargetUnavailableException("tile input framebuffer incomplete");
                    }
                    mGl.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
                    mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, mTileInput.fbo[0]);
                    GlErrors.requireGlSuccess("tile input copy setup", true, mGl::glGetError);
                    mGl.glBlitFramebuffer(x, y, x + scratchWidth, y + scratchHeight,
                            0, 0, scratchWidth, scratchHeight, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
                    GlErrors.requireGlSuccess("tile input copy", true, mGl::glGetError);
                    runPasses(mTileInput.texture[0], scratchWidth, scratchHeight, mIntermediateTargets, mTileRestore,
                            inputTopLeft, sampler, mSharpness, mOutputSizedShader);
                    mGl.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mTileRestore.fbo[0]);
                    mGl.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
                    mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, mRestore.fbo[0]);
                    GlErrors.requireGlSuccess("tile output copy setup", true, mGl::glGetError);
                    // Native reconstruction keeps its phase: crop offsets scale with the trained output factor.
                    mGl.glBlitFramebuffer((left - x) * mNativeScale, (top - y) * mNativeScale,
                            (left - x + coreWidth) * mNativeScale, (top - y + coreHeight) * mNativeScale,
                            left * mNativeScale, top * mNativeScale,
                            (left + coreWidth) * mNativeScale, (top + coreHeight) * mNativeScale,
                            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
                    GlErrors.requireGlSuccess("tile output copy", true, mGl::glGetError);
                }
            }
        } finally {
            mGl.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, readFbo);
            mGl.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, 0, 0);
        }
    }

    /**
     * Executes all CNN passes with zero GC allocations and optimized state transitions.
     *
     * <p>Caller contract: {@code GL_SCISSOR_TEST}, {@code GL_CULL_FACE}, and
     * {@code GL_RASTERIZER_DISCARD} must be disabled and a full-screen quad VAO bound before entry,
     * and {@code intermediateTargets} elements must not be mutated in place across calls without
     * passing a new array or calling {@link #invalidateUniformCache()}.</p>
     */
    void runPasses(int mainTexture, int width, int height,
                   GlRenderTarget[] intermediateTargets,
                   GlRenderTarget restoreTarget,
                   boolean inputTopLeft, int sampler, double sharpness, boolean outputSized) {
        if (mPlans == null) {
            throw new IllegalStateException("CnnPipeline programs not built");
        }
        if (mainTexture == 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid CnnPipeline input: texture="
                    + mainTexture + ", size=" + width + "x" + height);
        }

        int flipY = GraphicsShaderTransforms.flipYUniform(mTopLeftCoordinates, inputTopLeft);
        int targetWidth = restoreTarget != null ? restoreTarget.width : Math.multiplyExact(width, mNativeScale);
        int targetHeight = restoreTarget != null ? restoreTarget.height : Math.multiplyExact(height, mNativeScale);
        if (targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("invalid CnnPipeline target dimensions: "
                    + targetWidth + "x" + targetHeight);
        }

        boolean sizeChanged = (width != mLastWidth || height != mLastHeight
                || targetWidth != mLastTargetWidth || targetHeight != mLastTargetHeight);
        boolean stateChanged = (flipY != mLastFlipY || sharpness != mLastSharpness
                || (outputSized ? 1 : 0) != mLastOutputSized);
        boolean targetsChanged = (intermediateTargets != mLastIntermediateTargetsRef);

        // Validate intermediate targets array when passes read/write intermediate slots
        if (mMaxRequiredIntermediateSlot >= 0) {
            if (intermediateTargets == null || intermediateTargets.length <= mMaxRequiredIntermediateSlot) {
                throw new IllegalStateException("insufficient intermediate targets: required index "
                        + mMaxRequiredIntermediateSlot);
            }
            for (int i = 0; i <= mMaxRequiredIntermediateSlot; i++) {
                GlRenderTarget slotTarget = intermediateTargets[i];
                if (slotTarget == null || slotTarget.texture[0] == 0) {
                    throw new TargetUnavailableException("missing intermediate target slot " + i);
                }
            }
        }

        // Pre-bind clamp-to-edge sampler to all active units once
        for (int i = 0; i < mMaxBindings; i++) {
            mGl.glBindSampler(i, sampler);
        }

        // Configure pass FBO attachments when targets or dimensions are updated.
        // Each intermediate plan.fbo permanently retains its own plan.drawBuffers state.
        if (sizeChanged || targetsChanged) {
            try {
                for (PassPlan plan : mPlans) {
                    if (plan.targetSlots.length > 0) {
                        mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, plan.fbo);
                        for (int k = 0; k < plan.targetSlots.length; k++) {
                            int slot = plan.targetSlots[k];
                            GlRenderTarget target = intermediateTargets[slot];
                            if (target == null || target.texture[0] == 0) {
                                throw new TargetUnavailableException("missing intermediate target slot " + slot);
                            }
                            mGl.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER,
                                    GLES30.GL_COLOR_ATTACHMENT0 + k, GLES30.GL_TEXTURE_2D, target.texture[0], 0);
                        }
                        mGl.glDrawBuffers(plan.drawBuffers.length, plan.drawBuffers, 0);
                        int status = mGl.glCheckFramebufferStatus(GLES30.GL_DRAW_FRAMEBUFFER);
                        GlErrors.requireGlSuccess("MRT FBO setup", true, mGl::glGetError);
                        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                            throw new TargetUnavailableException("MRT FBO incomplete for pass "
                                    + plan.pass.desc + ": 0x" + Integer.toHexString(status));
                        }
                    }
                }
            } catch (RuntimeException failure) {
                mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
                mLastIntermediateTargetsRef = null;
                throw failure;
            }
        }

        // Reset texture unit cache for this execution
        Arrays.fill(mBoundTextures, -1);
        boolean uniformsDirty = sizeChanged || stateChanged || targetsChanged;

        int passCount = mPlans.length;
        for (int p = 0; p < passCount; p++) {
            PassPlan plan = mPlans[p];
            boolean finalPass = (plan.targetSlots.length == 0);
            int drawFbo = finalPass ? (restoreTarget != null ? restoreTarget.fbo[0] : 0) : plan.fbo;
            int vpWidth = finalPass ? targetWidth : width;
            int vpHeight = finalPass ? targetHeight : height;

            // Validate input-target feedback loops BEFORE binding/invalidating drawFbo so an aliased
            // restoreTarget or intermediate target is never invalidated prior to throwing.
            for (int b = 0; b < plan.inputSlots.length; b++) {
                int inSlot = plan.inputSlots[b];
                int inTexture = (inSlot < 0) ? mainTexture : intermediateTargets[inSlot].texture[0];
                if (finalPass) {
                    if (restoreTarget != null && restoreTarget.texture[0] != 0
                            && inTexture == restoreTarget.texture[0]) {
                        mLastIntermediateTargetsRef = null;
                        throw new IllegalStateException("CnnPipeline feedback loop detected in final pass " + p);
                    }
                } else {
                    for (int ts : plan.targetSlots) {
                        if (inTexture == intermediateTargets[ts].texture[0]) {
                            mLastIntermediateTargetsRef = null;
                            throw new IllegalStateException("CnnPipeline feedback loop detected in pass " + p);
                        }
                    }
                }
            }

            mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, drawFbo);

            // Optimization: Eliminate redundant DRAM-to-GMEM tile loads on mobile TBDR GPUs.
            // Intermediate and restore FBO contents are completely overwritten by the full-screen quad
            // (caller disables GL_SCISSOR_TEST). Default framebuffer (drawFbo == 0) rejects GL_COLOR_ATTACHMENT0.
            if (drawFbo != 0 && plan.drawBuffers.length > 0) {
                mGl.glInvalidateFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, plan.drawBuffers.length, plan.drawBuffers, 0);
            }

            mGl.glViewport(0, 0, vpWidth, vpHeight);
            mGl.glUseProgram(plan.program);

            // Optimization: Update uniforms only when dimensions, global state, or target buffers change.
            if (uniformsDirty) {
                if (plan.flipYLoc >= 0) mGl.glUniform1i(plan.flipYLoc, flipY);
                if (plan.outputSizeLoc >= 0) mGl.glUniform2f(plan.outputSizeLoc, vpWidth, vpHeight);
                int sharpnessLoc = plan.optionalUniformLocs[OptionalUniform.SHARPNESS.ordinal()];
                if (sharpnessLoc >= 0) mGl.glUniform1f(sharpnessLoc, (float) sharpness);
                int outputSizedLoc = plan.optionalUniformLocs[OptionalUniform.OUTPUT_SIZED.ordinal()];
                if (outputSizedLoc >= 0) mGl.glUniform1i(outputSizedLoc, outputSized ? 1 : 0);

                for (int b = 0; b < plan.inputSlots.length; b++) {
                    int inSlot = plan.inputSlots[b];
                    int inW = (inSlot < 0) ? width : intermediateTargets[inSlot].width;
                    int inH = (inSlot < 0) ? height : intermediateTargets[inSlot].height;

                    if (plan.sizeLoc[b] >= 0) {
                        mGl.glUniform2f(plan.sizeLoc[b], inW, inH);
                    }
                    if (plan.ptLoc[b] >= 0) {
                        mGl.glUniform2f(plan.ptLoc[b], 1.0f / (float) inW, 1.0f / (float) inH);
                    }
                }
            }

            // Bind textures with unit cache (skips redundant glBindTexture when consecutive passes share inputs)
            for (int b = 0; b < plan.inputSlots.length; b++) {
                int inSlot = plan.inputSlots[b];
                int inTexture = (inSlot < 0) ? mainTexture : intermediateTargets[inSlot].texture[0];
                if (mBoundTextures[b] != inTexture) {
                    mGl.glActiveTexture(GLES30.GL_TEXTURE0 + b);
                    mGl.glBindTexture(GLES30.GL_TEXTURE_2D, inTexture);
                    mBoundTextures[b] = inTexture;
                }
            }

            mGl.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        int drawErr = mGl.glGetError();
        if (drawErr != GLES30.GL_NO_ERROR) {
            mLastIntermediateTargetsRef = null;
            GlErrors.drainBestEffortNoThrow(drawErr, mGl::glGetError);
            throw new IllegalStateException("filter node drawing: GL error 0x" + Integer.toHexString(drawErr));
        }

        mLastIntermediateTargetsRef = intermediateTargets;
        mLastWidth = width;
        mLastHeight = height;
        mLastTargetWidth = targetWidth;
        mLastTargetHeight = targetHeight;
        mLastFlipY = flipY;
        mLastSharpness = sharpness;
        mLastOutputSized = outputSized ? 1 : 0;
    }

    void invalidateUniformCache() {
        mLastWidth = -1;
        mLastHeight = -1;
        mLastTargetWidth = -1;
        mLastTargetHeight = -1;
        mLastFlipY = -1;
        mLastSharpness = -1.0;
        mLastOutputSized = -1;
        mLastIntermediateTargetsRef = null;
    }

    void releasePrograms() {
        releasePrograms(false);
    }

    private void releasePrograms(boolean clearPassConfig) {
        if (mPlans != null) {
            mGl.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, mQuery, 0);
            int boundDrawFbo = mQuery[0];
            mGl.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, mQuery, 0);
            int boundReadFbo = mQuery[0];
            mGl.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, mQuery, 0);
            int boundProgram = mQuery[0];

            for (PassPlan plan : mPlans) {
                if (plan != null) {
                    if (plan.fbo != 0) {
                        if (boundDrawFbo == plan.fbo) {
                            mGl.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
                            boundDrawFbo = 0;
                        }
                        if (boundReadFbo == plan.fbo) {
                            mGl.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
                            boundReadFbo = 0;
                        }
                        mQuery[0] = plan.fbo;
                        mGl.glDeleteFramebuffers(1, mQuery, 0);
                        plan.fbo = 0;
                    }
                    if (plan.program != 0) {
                        if (boundProgram == plan.program) {
                            mGl.glUseProgram(0);
                            boundProgram = 0;
                        }
                        mGl.glDeleteProgram(plan.program);
                    }
                }
            }
            mPlans = null;
        }
        resetPassRuntimeState(clearPassConfig);
    }

    private void resetPassRuntimeState(boolean clearPassConfig) {
        mMaxRequiredIntermediateSlot = -1;
        Arrays.fill(mBoundTextures, -1);
        mIntermediateTargets = null;
        mRestore = mTileInput = mTileRestore = null;
        if (clearPassConfig && mAssetOpener != null) {
            mPasses = null;
            mTargetPlan = null;
            mConfig = null;
            mMaxBindings = mScaleOnly ? 1 : MAX_BINDINGS;
            mIsCnnChain = false;
        }
        invalidateUniformCache();
    }

    private int compileShader(int type, String source) {
        return GlOps.compileShader(mGl, type, source, mQuery);
    }
}
