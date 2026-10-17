package com.core.gl;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.util.Log;

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
import java.util.function.IntSupplier;

/**
 * Shared executor for contributed MAIN hooks, with separable Lanczos sizing.
 * All GL methods run on the owning GL thread; GLES3 and renderable RGBA16F are required.
 * External MAIN textures are borrowed, never modified or deleted. A successful
 * renderTexture result is an owned RGBA8 texture, valid until resize/release or
 * context loss. KR2 retains its default-framebuffer copy and display entry point.
 */
public final class Anime4kPostProcessor {
    private static final String TAG = "Anime4kPostProcessor";
    public static final int BYPASS_NONE = 0, BYPASS_INVALID_SIZE = 1,
            BYPASS_GL_LIMIT = 2, BYPASS_PIPELINE_FAILED = 3, BYPASS_TARGET_UNAVAILABLE = 4;
    /** Texture storage only; leaves room for the borrowed stage and the engine itself. */
    public static final long TARGET_MEMORY_BUDGET_BYTES = 256L * 1024L * 1024L;
    // Contributed CNN models can use eight simultaneous inputs. ES3 guarantees 16.
    private static final int MAX_BINDINGS = 8;
    private static final int MAX_GL_ERRORS = 16;
    private static final IntSupplier GL_ERRORS = GLES30::glGetError;

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec2 aPos;\n" +
            "out vec2 vPos;\n" +
            "void main() {\n" +
            "    vPos = aPos * 0.5 + 0.5;\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    static final class TargetUnavailableException extends RuntimeException {
        final boolean retryAfterDelay;
        TargetUnavailableException(String detail) { this(detail, true); }
        TargetUnavailableException(String detail, boolean retryAfterDelay) {
            super(detail);
            this.retryAfterDelay = retryAfterDelay;
        }
    }

    private static final class TexFbo {
        final int[] texture = new int[1];
        final int[] fbo = new int[1];
        final int width;
        final int height;

        TexFbo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class PassProgram {
        final MpvHookShader.Pass pass;
        final int program;
        final int[] samplerLoc;
        final int[] sizeLoc;
        final int outputSizeLoc, sharpnessLoc, outputSizedLoc;

        PassProgram(MpvHookShader.Pass pass, int program, int[] samplerLoc, int[] sizeLoc, int outputSizeLoc) {
            this.pass = pass;
            this.program = program;
            this.samplerLoc = samplerLoc;
            this.sizeLoc = sizeLoc;
            this.outputSizeLoc = outputSizeLoc;
            sharpnessLoc = GLES30.glGetUniformLocation(program, "u_SHARPNESS");
            outputSizedLoc = GLES30.glGetUniformLocation(program, "u_OUTPUT_SIZED");
        }
    }

    /** Reused scratch storage; capture/restore and the steady frame path allocate no arrays. */
    private static final class GlState {
        final int[] query = new int[1];
        final int[] viewport = new int[4];
        final int[] colorMask = new int[4];
        final int[] textures = new int[MAX_BINDINGS];
        final int[] samplers = new int[MAX_BINDINGS];
        int textureCount;
        int program;
        int vao;
        int arrayBuffer;
        int unpackBuffer;
        int activeTexture;
        int readFramebuffer;
        int drawFramebuffer;
        boolean blend;
        boolean depth;
        boolean stencil;
        boolean scissor;
        boolean cull;
        boolean rasterizerDiscard;
        boolean alphaToCoverage;
        boolean sampleCoverage;
        boolean dither;

        int integer(int name) {
            GLES30.glGetIntegerv(name, query, 0);
            return query[0];
        }

        void capture(int count) {
            textureCount = count;
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
            GLES30.glGetIntegerv(GLES30.GL_COLOR_WRITEMASK, colorMask, 0);
            program = integer(GLES30.GL_CURRENT_PROGRAM);
            vao = integer(GLES30.GL_VERTEX_ARRAY_BINDING);
            arrayBuffer = integer(GLES30.GL_ARRAY_BUFFER_BINDING);
            unpackBuffer = integer(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING);
            activeTexture = integer(GLES30.GL_ACTIVE_TEXTURE);
            readFramebuffer = integer(GLES30.GL_READ_FRAMEBUFFER_BINDING);
            drawFramebuffer = integer(GLES30.GL_DRAW_FRAMEBUFFER_BINDING);
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
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES30.glColorMask(colorMask[0] != 0, colorMask[1] != 0,
                    colorMask[2] != 0, colorMask[3] != 0);
            GLES30.glUseProgram(program);
            enable(GLES30.GL_BLEND, blend);
            enable(GLES30.GL_DEPTH_TEST, depth);
            enable(GLES30.GL_STENCIL_TEST, stencil);
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

    private final List<PassProgram> mPrograms = new ArrayList<>();
    private final List<TexFbo> mOwnedTargets = new ArrayList<>();
    private final Map<String, TexFbo> mTextures = new HashMap<>();
    private TexFbo[] mIntermediateTargets;
    private GraphicsShaderTargets mTargetPlan;
    private TexFbo mTileInput, mTileRestore;
    private int mTileCoreSize;
    private final GlState mState = new GlState();
    private final int[] mQuadBuffer = new int[1];
    private final int[] mQuadVao = new int[1];
    private final int[] mSampler = new int[1];
    private final int[] mQuery = new int[1];
    private final int[] mDimensionQuery = new int[2];
    private AssetManager mAssets;
    private boolean mScaleOnly, mOutputSizedShader;
    private int mNativeScale = 1, mMaxOutputScale = 1, mHaloPerPass;
    private List<MpvHookShader.Pass> mParsedPasses;
    private int mPresentProgram, mPresentSamplerLoc;
    private String mShaderAsset;
    private TexFbo mRestore;
    private TexFbo mScaleHorizontal;
    private TexFbo mOutput;
    private TexFbo mBorrowedCopy;
    private final int[] mBorrowedReadFbo = new int[1];
    private int mVertexShader;
    private int mScaleProgram, mScaleSamplerLoc, mScaleSizeLoc, mScaleAxisLoc, mScaleOutputSizeLoc;
    private int mLimitWidth, mLimitHeight, mBypassReason;
    private boolean mTargetFailed;
    private final GraphicsTargetRetry mTargetRetry = new GraphicsTargetRetry();
    private int mFailedSourceWidth, mFailedSourceHeight, mFailedOutputWidth, mFailedOutputHeight;
    private long mTargetBytes;
    private int mMaxBindings = MAX_BINDINGS;
    private int mWidth;
    private int mHeight;
    private boolean mGlReady;
    private boolean mHalfFloatChecked, mHalfFloatRenderable;
    private boolean mFailed;

    private Anime4kPostProcessor() {}

    public Anime4kPostProcessor(Context context, String shaderAsset) {
        configure(context, shaderAsset);
    }

    /** Scale nodes do not load or execute an implicit Anime4K Restore shader. */
    static Anime4kPostProcessor forLanczos(Context context) {
        if (context == null) throw new IllegalArgumentException("missing graphics context");
        Anime4kPostProcessor processor = new Anime4kPostProcessor();
        processor.mAssets = context.getAssets();
        processor.mScaleOnly = true;
        processor.mMaxBindings = 1;
        return processor;
    }

    private double mSharpness = 0.4;
    static Anime4kPostProcessor forFilter(Context context, GraphicsFilterChain.Step step) {
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(step.id);
        if (definition.shaderAsset == null) return forLanczos(context);
        Anime4kPostProcessor processor = new Anime4kPostProcessor(context, definition.shaderAsset);
        processor.mNativeScale = definition.nativeScale;
        processor.mMaxOutputScale = definition.maxOutputScale;
        processor.mHaloPerPass = definition.haloPerPass;
        processor.mOutputSizedShader = definition.outputSized && !"none".equals(step.scaleMode);
        processor.mSharpness = step.sharpness;
        return processor;
    }

    /** Configure only before GL initialization, or after release/abandonContext. */
    public void configure(Context context, String shaderAsset) {
        if (context == null || !GraphicsFilterDefinitions.isValidAssetPath(shaderAsset)) {
            throw new IllegalArgumentException("invalid Anime4K asset configuration");
        }
        AssetManager assets = context.getAssets();
        if (mAssets == assets && shaderAsset.equals(mShaderAsset)) return;
        if (!mPrograms.isEmpty() || !mOwnedTargets.isEmpty()) {
            throw new IllegalStateException("release or abandon the previous pipeline before configuring");
        }
        mAssets = assets;
        mShaderAsset = shaderAsset;
        mParsedPasses = null;
        mTargetPlan = null;
        mTileCoreSize = 0;
        mFailed = false;
    }

    /** Context loss: old names must not be deleted in a different GL context. */
    public void abandonContext() {
        mPrograms.clear();
        mOwnedTargets.clear();
        mTextures.clear();
        mIntermediateTargets = null;
        mTileInput = mTileRestore = null;
        mOutput = mRestore = mScaleHorizontal = mBorrowedCopy = null;
        mQuadBuffer[0] = mQuadVao[0] = mSampler[0] = mVertexShader = 0;
        mWidth = mHeight = 0;
        mScaleProgram = mScaleSamplerLoc = mScaleSizeLoc = mScaleAxisLoc = mScaleOutputSizeLoc = 0;
        mLimitWidth = mLimitHeight = mBypassReason = 0;
        mTargetPlan = null;
        mParsedPasses = null;
        mPresentProgram = mPresentSamplerLoc = 0;
        mBorrowedReadFbo[0] = 0;
        mTargetFailed = false;
        mTargetRetry.reset();
        mTargetBytes = 0;
        mMaxBindings = mScaleOnly ? 1 : MAX_BINDINGS;
        mGlReady = false;
        mHalfFloatChecked = mHalfFloatRenderable = false;
        mFailed = false;
    }

    public boolean isFailed() { return mFailed; }
    public int bypassReason() { return mBypassReason; }
    public long targetBytes() { return mGlReady ? mTargetBytes : 0; }
    boolean retriesTargetsAfterDelay() {
        return mTargetFailed && mTargetRetry.isRetryableFailure();
    }
    public int outputWidth() { return mGlReady ? mOutput.width : 0; }
    public int outputHeight() { return mGlReady ? mOutput.height : 0; }

    /**
     * Execute the node at its planned size, then resample only when requested by
     * that node. Native reconstruction retains its trained scale; the host handles display fitting.
     * Invalid/GL-limit requests return zero without permanently failing the
     * pipeline. Target allocation pressure retries with bounded backoff; deterministic
     * budget/geometry rejections retry after geometry or context changes.
     */
    public int renderTexture(int textureId, int width, int height, int outWidth, int outHeight) {
        if (mFailed) { mBypassReason = BYPASS_PIPELINE_FAILED; return 0; }
        if (textureId == 0 || !LanczosScale.isPositive(width, height, outWidth, outHeight)) {
            mBypassReason = BYPASS_INVALID_SIZE;
            return 0;
        }
        if (targetsBlocked(width, height, outWidth, outHeight)) return 0;
        if (!beginOwnedGlOperations()) return 0;
        mState.capture(mMaxBindings);
        try {
            if (!ensureTargets(width, height, outWidth, outHeight)) return 0;
            prepareDrawState();
            if (mTileCoreSize > 0) runTiledPasses(textureId, width, height);
            else if (!mScaleOnly) runPasses(textureId, width, height);
            runScale(mScaleOnly ? textureId : mRestore.texture[0],
                    mScaleOnly ? width : mRestore.width, mScaleOnly ? height : mRestore.height);
            // Report this node's drawing errors before the next node opens its own boundary.
            requireGlSuccess("filter node drawing", false);
            mBypassReason = BYPASS_NONE;
            mTargetRetry.reset();
            return mOutput.texture[0];
        } catch (TargetUnavailableException unavailable) {
            targetUnavailable(width, height, outWidth, outHeight, unavailable);
            return 0;
        } catch (RuntimeException failure) {
            fail(failure);
            return 0;
        } finally {
            mState.restore();
            // Restoration belongs to this node, before the next entry drains inherited errors.
            requireGlSuccess("filter state restoration", false);
        }
    }

    /** Delete owned resources only while the owning context is current. */
    public void release() {
        mTargetRetry.reset();
        mHalfFloatChecked = mHalfFloatRenderable = false;
        if (mPrograms.isEmpty() && mOwnedTargets.isEmpty() && mQuadBuffer[0] == 0
                && mQuadVao[0] == 0 && mSampler[0] == 0 && mVertexShader == 0 && mScaleProgram == 0 && mPresentProgram == 0 && mBorrowedReadFbo[0] == 0) {
            mFailed = false;
            mBypassReason = BYPASS_NONE;
            mTargetFailed = false;
            return;
        }
        mState.capture(mMaxBindings);
        try {
            releaseTargets();
            for (int i = 0; i < mPrograms.size(); i++) {
                int program = mPrograms.get(i).program;
                if (mState.program == program) mState.program = 0;
                GLES30.glDeleteProgram(program);
            }
            mPrograms.clear();
            if (mVertexShader != 0) GLES30.glDeleteShader(mVertexShader);
            if (mScaleProgram != 0) {
                if (mState.program == mScaleProgram) mState.program = 0;
                GLES30.glDeleteProgram(mScaleProgram);
            }
            if (mBorrowedReadFbo[0] != 0) {
                GLES30.glDeleteFramebuffers(1, mBorrowedReadFbo, 0);
                mBorrowedReadFbo[0] = 0;
            }
            if (mPresentProgram != 0) {
                if (mState.program == mPresentProgram) mState.program = 0;
                GLES30.glDeleteProgram(mPresentProgram);
            }
            mPresentProgram = mPresentSamplerLoc = 0;
            mParsedPasses = null;
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
            mMaxBindings = mScaleOnly ? 1 : MAX_BINDINGS;
            mTargetPlan = null;
            mParsedPasses = null;
            mTargetFailed = false;
        } finally {
            mState.restore();
        }
    }

    private void fail(RuntimeException failure) {
        mFailed = true;
        mBypassReason = BYPASS_PIPELINE_FAILED;
        // Native MAIN is never written; external callers can always fall back safely.
        releaseTargets();
        Log.e(TAG, "Anime4K disabled for this GL context", failure);
    }

    private void releaseTargets() {
        for (int i = 0; i < mOwnedTargets.size(); i++) deleteTexFbo(mOwnedTargets.get(i));
        mOwnedTargets.clear();
        mTextures.clear();
        mIntermediateTargets = null;
        mTileInput = mTileRestore = null;
        mOutput = mRestore = mScaleHorizontal = mBorrowedCopy = null;
        mGlReady = false;
        mTargetBytes = 0;
    }

    private void deleteTexFbo(TexFbo target) {
        // The borrowed result expires on resize/release. A host may still have it
        // bound from its previous presentation; never rebind a deleted GL name.
        for (int unit = 0; unit < mState.textureCount; unit++) {
            if (mState.textures[unit] == target.texture[0]) mState.textures[unit] = 0;
        }
        if (mState.readFramebuffer == target.fbo[0]) mState.readFramebuffer = 0;
        if (mState.drawFramebuffer == target.fbo[0]) mState.drawFramebuffer = 0;
        if (target.fbo[0] != 0) GLES30.glDeleteFramebuffers(1, target.fbo, 0);
        if (target.texture[0] != 0) GLES30.glDeleteTextures(1, target.texture, 0);
        target.fbo[0] = target.texture[0] = 0;
    }

    private boolean ensureTargets(int width, int height, int outWidth, int outHeight) {
        if (mFailed) return false;
        if (!LanczosScale.isPositive(width, height, outWidth, outHeight)) {
            mBypassReason = BYPASS_INVALID_SIZE;
            return false;
        }
        if (mLimitWidth == 0 || mLimitHeight == 0) {
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, mQuery, 0);
            int textureLimit = mQuery[0];
            GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, mDimensionQuery, 0);
            mLimitWidth = Math.min(textureLimit, mDimensionQuery[0]);
            mLimitHeight = Math.min(textureLimit, mDimensionQuery[1]);
        }
        GraphicsShaderGeometry geometry = new GraphicsShaderGeometry(width, height, outWidth, outHeight,
                mNativeScale, mOutputSizedShader, mMaxOutputScale);
        if (!LanczosScale.fitsLimits(width, height, outWidth, outHeight, mLimitWidth, mLimitHeight)
                || geometry.width > mLimitWidth || geometry.height > mLimitHeight) {
            if (mBypassReason != BYPASS_GL_LIMIT) Log.w(TAG, "requested Anime4K/Lanczos size exceeds GL limits: "
                    + width + "x" + height + " -> " + outWidth + "x" + outHeight
                    + "; limit " + mLimitWidth + "x" + mLimitHeight);
            mBypassReason = BYPASS_GL_LIMIT;
            return false;
        }
        if (mGlReady && mWidth == width && mHeight == height
                && mOutput.width == outWidth && mOutput.height == outHeight) return true;
        if (mAssets == null) throw new IllegalStateException("Anime4K assets not configured");
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
        // Shader format/compilation errors remain permanent; only target failures are retryable.
        if (!mScaleOnly && mPrograms.isEmpty()) compileChain();
        if (mScaleOnly && mVertexShader == 0) mVertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
        long bytes = estimateTargetBytes(width, height, outWidth, outHeight);
        if (bytes > TARGET_MEMORY_BUDGET_BYTES) {
            return targetUnavailable(width, height, outWidth, outHeight,
                    new TargetUnavailableException("texture budget exceeded: " + bytes + " > "
                            + TARGET_MEMORY_BUDGET_BYTES, false));
        }
        LanczosScale.Plan scale = LanczosScale.resample(geometry.width, geometry.height, outWidth, outHeight);
        if ((scale.horizontal || scale.vertical) && mScaleProgram == 0) buildScaleProgram();
        if (mQuadVao[0] == 0) createQuad();
        releaseTargets();
        mWidth = width;
        mHeight = height;
        try {
            mRestore = mScaleOnly ? null : createTexFbo(geometry.width, geometry.height, false);
            mOutput = mRestore;
            if (scale.horizontal || scale.vertical || mScaleOnly) mOutput = createTexFbo(outWidth, outHeight, false);
            if (scale.horizontal && scale.vertical) {
                // Pure Lanczos needs only ES3 RGBA8 targets. Its horizontal result
                // deliberately accepts 8-bit quantization/negative-lobe clipping;
                // CNN intermediates and their later resample retain RGBA16F.
                mScaleHorizontal = createTexFbo(scale.horizontalWidth, scale.horizontalHeight, !mScaleOnly);
            }
            if (!mScaleOnly) {
                int scratchWidth = scratchDimension(width), scratchHeight = scratchDimension(height);
                mIntermediateTargets = new TexFbo[mTargetPlan.count];
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
        Log.i(TAG, "pipeline ready: " + mPrograms.size() + " restore passes, "
                + width + "x" + height + " -> " + outWidth + "x" + outHeight
                + "; texture bytes " + bytes);
        return true;
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
        releaseTargets(); // Also release targets created before the failing allocation.
        requireGlSuccess("target bypass cleanup", false);
        mTargetFailed = true;
        mTargetRetry.failed(unavailable.retryAfterDelay);
        mFailedSourceWidth = sw; mFailedSourceHeight = sh;
        mFailedOutputWidth = ow; mFailedOutputHeight = oh;
        mBypassReason = BYPASS_TARGET_UNAVAILABLE;
        Log.w(TAG, "Anime4K/Lanczos target bypassed: " + sw + "x" + sh + " -> "
                + ow + "x" + oh + ": " + unavailable.getMessage());
        return false;
    }

    private void createQuad() {
        FloatBuffer quad = ByteBuffer.allocateDirect(8 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}).position(0);
        GLES30.glGenBuffers(1, mQuadBuffer, 0);
        requireName(mQuadBuffer[0], "quad buffer");
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mQuadBuffer[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * Float.BYTES, quad, GLES30.GL_STATIC_DRAW);
        GLES30.glGenVertexArrays(1, mQuadVao, 0);
        requireName(mQuadVao[0], "quad VAO");
        GLES30.glBindVertexArray(mQuadVao[0]);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glGenSamplers(1, mSampler, 0);
        requireName(mSampler[0], "sampler");
        GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glSamplerParameteri(mSampler[0], GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
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

    /** Establish the error boundary once, before capturing/changing the host state.
     * Owned-operation checks below must retain errors generated after this point. */
    private boolean beginOwnedGlOperations() {
        if (mFailed) { mBypassReason = BYPASS_PIPELINE_FAILED; return false; }
        for (int i = 0; i < MAX_GL_ERRORS; i++) {
            if (GLES30.glGetError() == GLES30.GL_NO_ERROR) return true;
        }
        // A broken/lost context must not loop forever or masquerade as a size failure.
        // Retain resources for release/context abandonment; submit no more GL work here.
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
        requireGlSuccess(operation, targetFailure, GL_ERRORS);
    }

    /** Consume every flag before leaving an operation boundary. Allocation errors
     * must not be mistaken for errors caused by restoring the host state. */
    static void requireGlSuccess(String operation, boolean targetFailure, IntSupplier errors) {
        StringBuilder detail = null;
        boolean retryable = targetFailure;
        for (int i = 0; i < MAX_GL_ERRORS; i++) {
            int error = errors.getAsInt();
            if (error == GLES30.GL_NO_ERROR) {
                if (detail == null) return;
                if (retryable) throw new TargetUnavailableException(detail.toString());
                throw new IllegalStateException(detail.toString());
            }
            if (detail == null) detail = new StringBuilder(operation).append(": GL errors");
            detail.append(" 0x").append(Integer.toHexString(error));
            // Resource pressure and unusable attachments can recover after backoff;
            // invalid API use or a lost context cannot be repaired by retrying.
            retryable &= error == GLES30.GL_OUT_OF_MEMORY
                    || error == GLES30.GL_INVALID_FRAMEBUFFER_OPERATION;
        }
        // A lost context must not loop forever or be cached as a geometry rejection.
        throw new IllegalStateException(operation + ": GL error state did not clear");
    }

    private TexFbo createTexFbo(int width, int height, boolean halfFloat) {
        if (halfFloat && !halfFloatRenderable())
            throw new IllegalStateException("RGBA16F color rendering unavailable");
        TexFbo target = new TexFbo(width, height);
        try {
            GLES30.glGenTextures(1, target.texture, 0);
            if (target.texture[0] == 0) {
                requireGlSuccess("texture name allocation");
                throw new TargetUnavailableException("cannot allocate texture");
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.texture[0]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0,
                    halfFloat ? GLES30.GL_RGBA16F : GLES30.GL_RGBA8, width, height, 0,
                    GLES30.GL_RGBA, halfFloat ? GLES30.GL_HALF_FLOAT : GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glGenFramebuffers(1, target.fbo, 0);
            if (target.fbo[0] == 0) {
                requireGlSuccess("framebuffer name allocation");
                throw new TargetUnavailableException("cannot allocate framebuffer");
            }
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, target.fbo[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, target.texture[0], 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_DRAW_FRAMEBUFFER);
            // Check even an incomplete FBO: glTexImage2D may have left OOM flags.
            requireGlSuccess("render target allocation");
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new TargetUnavailableException("FBO incomplete: 0x" + Integer.toHexString(status)
                        + (halfFloat ? " (RGBA16F)" : " (RGBA8)"));
            }
            mOwnedTargets.add(target);
            return target;
        } catch (RuntimeException failure) {
            deleteTexFbo(target);
            requireGlSuccess("render target cleanup", false);
            throw failure;
        }
    }

    /** CPU estimate used before allocating any node in a complete chain. */
    long estimateTargetBytes(int sw, int sh, int ow, int oh) {
        if (!mScaleOnly) loadPasses();
        if (mScaleOnly) return LanczosScale.estimatedOwnedBytes(sw, sh, ow, oh, 0, 0, false, false);
        GraphicsShaderGeometry geometry = new GraphicsShaderGeometry(sw, sh, ow, oh, mNativeScale, mOutputSizedShader, mMaxOutputScale);
        if (mTileCoreSize == 0) return geometry.estimate(sw, sh, ow, oh, mTargetPlan.count, 0, false);
        long outputBytes = geometry.estimate(sw, sh, ow, oh, 0, 0, false);
        long scratchPixels = (long) scratchDimension(sw) * scratchDimension(sh);
        // Source RGBA8, native-scale RGBA8 tile output, and RGBA16F slots; full output stays owned.
        return GraphicsFilterPipeline.addBytes(outputBytes,
                scratchPixels * (8L * mTargetPlan.count + 4L + 4L * mNativeScale * mNativeScale));
    }

    /** Only the fixed, translation-invariant 3x3 CNN assets have a known bounded halo. */
    void setTileCoreSize(int size) {
        mTileCoreSize = size > 0 && mHaloPerPass > 0 ? size : 0;
    }

    private int scratchDimension(int source) {
        return mTileCoreSize == 0 ? source : Math.min(source, mTileCoreSize + 2 * mHaloPerPass * mParsedPasses.size());
    }

    private List<MpvHookShader.Pass> loadPasses() {
        if (mParsedPasses != null) return mParsedPasses;
        String asset = mShaderAsset;
        StringBuilder source = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                mAssets.open(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) source.append(line).append('\n');
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read graphics filter asset: " + asset, failure);
        }
        mParsedPasses = MpvHookShader.parse(source.toString());
        mTargetPlan = new GraphicsShaderTargets(mParsedPasses);
        return mParsedPasses;
    }

    private void compileChain() {
        List<MpvHookShader.Pass> passes = loadPasses();
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_IMAGE_UNITS, mQuery, 0);
        int availableUnits = Math.min(MAX_BINDINGS, mQuery[0]);
        mMaxBindings = 1;
        for (MpvHookShader.Pass pass : passes) {
            int count = pass.binds.size();
            if (count > availableUnits) throw new IllegalStateException("too many Anime4K texture inputs");
            mMaxBindings = Math.max(mMaxBindings, count);
        }
        mVertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
        try {
            for (int i = 0; i < passes.size(); i++) mPrograms.add(buildProgram(passes.get(i)));
        } catch (RuntimeException failure) {
            for (int i = 0; i < mPrograms.size(); i++) GLES30.glDeleteProgram(mPrograms.get(i).program);
            mPrograms.clear();
            GLES30.glDeleteShader(mVertexShader);
            mVertexShader = 0;
            throw failure;
        }
    }

    private PassProgram buildProgram(MpvHookShader.Pass pass) {
        int fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, MpvHookShader.fragmentSource(pass));
        int program = GLES30.glCreateProgram();
        try {
            requireName(program, "program");
            GLES30.glAttachShader(program, mVertexShader);
            GLES30.glAttachShader(program, fragmentShader);
            GLES30.glLinkProgram(program);
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, mQuery, 0);
            if (mQuery[0] == 0) throw new IllegalStateException("link failed for " + pass.desc
                    + ": " + GLES30.glGetProgramInfoLog(program));
            int[] samplerLoc = new int[pass.binds.size()];
            int[] sizeLoc = new int[pass.binds.size()];
            for (int i = 0; i < pass.binds.size(); i++) {
                String bind = pass.binds.get(i);
                samplerLoc[i] = GLES30.glGetUniformLocation(program, "u_" + bind);
                sizeLoc[i] = GLES30.glGetUniformLocation(program, "u_" + bind + "_size");
            }
            return new PassProgram(pass, program, samplerLoc, sizeLoc,
                    GLES30.glGetUniformLocation(program, "u_OUTPUT_size"));
        } catch (RuntimeException failure) {
            GLES30.glDeleteProgram(program);
            throw failure;
        } finally {
            GLES30.glDeleteShader(fragmentShader);
        }
    }

    private int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        requireName(shader, "shader");
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, mQuery, 0);
        if (mQuery[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return shader;
    }

    private static void requireName(int name, String resource) {
        if (name == 0) throw new IllegalStateException("cannot allocate Anime4K " + resource);
    }

    private void buildScaleProgram() {
        int fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, LanczosScale.RESAMPLE_FRAGMENT_SHADER);
        int program = GLES30.glCreateProgram();
        try {
            requireName(program, "Lanczos program");
            GLES30.glAttachShader(program, mVertexShader);
            GLES30.glAttachShader(program, fragmentShader);
            GLES30.glLinkProgram(program);
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, mQuery, 0);
            if (mQuery[0] == 0) throw new IllegalStateException("Lanczos link failed: "
                    + GLES30.glGetProgramInfoLog(program));
            mScaleSamplerLoc = GLES30.glGetUniformLocation(program, "uSource");
            mScaleSizeLoc = GLES30.glGetUniformLocation(program, "uSourceSize");
            mScaleAxisLoc = GLES30.glGetUniformLocation(program, "uAxis");
            mScaleOutputSizeLoc = GLES30.glGetUniformLocation(program, "uOutputSize");
            mScaleProgram = program;
        } catch (RuntimeException failure) {
            GLES30.glDeleteProgram(program);
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
        } else {
            drawScale(inputTexture, width, height, mOutput, horizontal ? 0 : 1);
        }
    }

    private void drawScale(int texture, int width, int height, TexFbo target, int axis) {
        if (target == null || texture == target.texture[0]) throw new IllegalStateException("invalid Lanczos target");
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

    /** Discard targets before a whole-chain geometry change/retry; programs remain reusable. */
    void discardTargets() {
        if (!mOwnedTargets.isEmpty()) {
            mState.capture(mMaxBindings);
            try { releaseTargets(); } finally { mState.restore(); }
        }
        mTargetFailed = false;
        mTargetRetry.reset();
    }

    private void ensureBorrowedReadFramebuffer(String operation) {
        if (mBorrowedReadFbo[0] != 0) return;
        GLES30.glGenFramebuffers(1, mBorrowedReadFbo, 0);
        requireTargetName(mBorrowedReadFbo[0], operation, GL_ERRORS);
    }

    /** Read-FBO allocation uses the same bounded retry contract as texture targets.
     * Drain allocation errors before rejecting a zero name or restoring host state. */
    static void requireTargetName(int name, String operation, IntSupplier errors) {
        requireGlSuccess(operation, true, errors);
        if (name == 0) throw new TargetUnavailableException("cannot allocate " + operation);
    }

    /** Copy only the integer-aligned game texture rectangle, never the display framebuffer.
     * Borrowed attachment is detached before returning; its texture remains engine-owned. */
    int normalizeBorrowedTexture(int texture, int width, int height,
                                 float u0, float v0, float u1, float v1) {
        if (texture == 0 || width <= 0 || height <= 0 || u1 <= u0 || v1 <= v0) return 0;
        if (targetsBlocked(width, height, width, height)) return 0;
        if (!beginOwnedGlOperations()) return 0;
        mState.capture(1);
        try {
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
            int allocationWidth = Math.round(width / (u1-u0));
            int allocationHeight = Math.round(height / (v1-v0));
            int left = Math.round(u0*allocationWidth), bottom = Math.round(v0*allocationHeight);
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, mBorrowedCopy.fbo[0]);
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
            requireGlSuccess("borrowed texture copy setup");
            // The crop and native UV remap share [v0,v1]. Both blit rectangles
            // increase in framebuffer Y; (v-v0)/(v1-v0) retains vertex ordering.
            GLES30.glBlitFramebuffer(left, bottom, left+width, bottom+height,
                    0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
            requireGlSuccess("borrowed texture copy");
            mTargetRetry.reset();
            return mBorrowedCopy.texture[0];
        } catch (TargetUnavailableException unavailable) {
            targetUnavailable(width, height, width, height, unavailable);
            return 0;
        } finally {
            if (mBorrowedReadFbo[0] != 0) {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mBorrowedReadFbo[0]);
                GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                        GLES30.GL_TEXTURE_2D, 0, 0);
            }
            mState.restore();
        }
    }

    void presentTexture(int texture, int width, int height) {
        if (!beginOwnedGlOperations()) throw new IllegalStateException("Presentation GL context unavailable");
        mState.capture(mMaxBindings);
        try {
            if (mVertexShader == 0) mVertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
            if (mQuadVao[0] == 0) createQuad();
            if (mPresentProgram == 0) {
                int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER,
                        "#version 300 es\nprecision highp float;\nprecision highp sampler2D;\n"
                        + "in vec2 vPos; out vec4 fragColor; uniform sampler2D uSource;\n"
                        + "void main(){fragColor=texture(uSource,vPos);fragColor.a=1.0;}\n");
                int program = GLES30.glCreateProgram();
                try {
                    requireName(program, "presentation program");
                    GLES30.glAttachShader(program, mVertexShader); GLES30.glAttachShader(program, fragment);
                    GLES30.glLinkProgram(program); GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, mQuery, 0);
                    if (mQuery[0] == 0) throw new IllegalStateException("presentation link failed");
                    mPresentProgram = program;
                    mPresentSamplerLoc = GLES30.glGetUniformLocation(program, "uSource");
                } catch (RuntimeException failure) { GLES30.glDeleteProgram(program); throw failure; }
                finally { GLES30.glDeleteShader(fragment); }
            }
            prepareDrawState();
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, width, height);
            GLES30.glUseProgram(mPresentProgram);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glBindSampler(0, mSampler[0]); GLES30.glUniform1i(mPresentSamplerLoc, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            requireGlSuccess("filter presentation", false);
        } finally {
            mState.restore();
            requireGlSuccess("presentation state restoration", false);
        }
    }
    private void prepareDrawState() {
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_STENCIL_TEST);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_RASTERIZER_DISCARD);
        GLES30.glDisable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE);
        GLES30.glDisable(GLES30.GL_SAMPLE_COVERAGE);
        GLES30.glDisable(GLES30.GL_DITHER);
        GLES30.glColorMask(true, true, true, true);
        GLES30.glBindVertexArray(mQuadVao[0]);
    }

    /** Run unchanged CNN passes with a conservative one-pixel halo per pass, then copy only the core. */
    private void runTiledPasses(int mainTexture, int width, int height) {
        ensureBorrowedReadFramebuffer("tile read framebuffer");
        int halo = mHaloPerPass * mParsedPasses.size();
        int scratchWidth = mTileInput.width, scratchHeight = mTileInput.height;
        try {
            for (int top = 0; top < height; top += mTileCoreSize) {
                int y = Math.max(0, Math.min(top - halo, height - scratchHeight));
                int coreHeight = Math.min(mTileCoreSize, height - top);
                for (int left = 0; left < width; left += mTileCoreSize) {
                    int x = Math.max(0, Math.min(left - halo, width - scratchWidth));
                    int coreWidth = Math.min(mTileCoreSize, width - left);
                    // Fixed-size crops stay inside the image. At real edges, each pass retains normal clamp semantics.
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mBorrowedReadFbo[0]);
                    GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                            GLES30.GL_TEXTURE_2D, mainTexture, 0);
                    int status = GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER);
                    requireGlSuccess("tile input attachment");
                    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE)
                        throw new TargetUnavailableException("tile input framebuffer incomplete");
                    GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
                    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, mTileInput.fbo[0]);
                    requireGlSuccess("tile input copy setup");
                    GLES30.glBlitFramebuffer(x, y, x + scratchWidth, y + scratchHeight,
                            0, 0, scratchWidth, scratchHeight, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
                    requireGlSuccess("tile input copy");
                    runPasses(mTileInput.texture[0], scratchWidth, scratchHeight);
                    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mTileRestore.fbo[0]);
                    GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
                    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, mRestore.fbo[0]);
                    requireGlSuccess("tile output copy setup");
                    // Native reconstruction keeps its phase: crop offsets scale with the trained output factor.
                    GLES30.glBlitFramebuffer((left - x) * mNativeScale, (top - y) * mNativeScale,
                            (left - x + coreWidth) * mNativeScale, (top - y + coreHeight) * mNativeScale,
                            left * mNativeScale, top * mNativeScale,
                            (left + coreWidth) * mNativeScale, (top + coreHeight) * mNativeScale,
                            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
                    requireGlSuccess("tile output copy");
                }
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mBorrowedReadFbo[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, 0, 0);
        }
    }

    private void runPasses(int mainTexture, int width, int height) {
        mTextures.clear();
        for (int p = 0; p < mPrograms.size(); p++) {
            PassProgram pp = mPrograms.get(p);
            boolean finalPass = pp.pass.isFinalToScreen();
            TexFbo target = finalPass ? (mTileCoreSize > 0 ? mTileRestore : mRestore)
                    : mIntermediateTargets[mTargetPlan.slots[p]];
            if (target == null) throw new IllegalStateException("missing Anime4K render target");
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, target.fbo[0]);
            GLES30.glViewport(0, 0, target.width, target.height);
            GLES30.glUseProgram(pp.program);
            if (pp.outputSizeLoc >= 0) GLES30.glUniform2f(pp.outputSizeLoc, target.width, target.height);
            if (pp.sharpnessLoc >= 0) GLES30.glUniform1f(pp.sharpnessLoc, (float) mSharpness);
            if (pp.outputSizedLoc >= 0) GLES30.glUniform1i(pp.outputSizedLoc, mOutputSizedShader ? 1 : 0);
            for (int i = 0; i < pp.pass.binds.size(); i++) {
                String name = pp.pass.binds.get(i);
                boolean main = "MAIN".equals(name);
                TexFbo input = main ? null : mTextures.get(name);
                if (!main && input == null) throw new IllegalStateException("unresolved bind: " + name);
                int texture = main ? mainTexture : input.texture[0];
                if (texture == target.texture[0]) {
                    throw new IllegalStateException("Anime4K texture feedback loop");
                }
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
                GLES30.glBindSampler(i, mSampler[0]);
                if (pp.samplerLoc[i] >= 0) GLES30.glUniform1i(pp.samplerLoc[i], i);
                if (pp.sizeLoc[i] >= 0) GLES30.glUniform2f(pp.sizeLoc[i],
                        main ? width : input.width, main ? height : input.height);
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            // Publish after drawing, including self-saves; the slot plan keeps this pass's inputs alive.
            if (!finalPass) mTextures.put(pp.pass.save, target);
        }
    }
}
