package com.core.gl;

import android.content.Context;
import android.opengl.GLES30;

/** Ordered, atomic filter execution. On failure, presents the borrowed original, never half a chain. */
public final class GraphicsFilterPipeline {
    private final GraphicsFilterChain chain;
    private final long reservedBytes;
    private final CnnPipeline[] nodes;
    private final CnnPipeline surfaceAdapter;
    private final GraphicsTargetRetry targetRetry = new GraphicsTargetRetry();
    private final int[] inputWidths, inputHeights, outputWidths, outputHeights;
    private final boolean[] execute;
    private boolean geometryKnown, planValid, temporaryFailed, failed, hasExecution;
    private boolean cropTemporaryFailed;
    private float failedCropU0, failedCropV0, failedCropU1, failedCropV1;
    private boolean inputTopLeft;
    private int sourceWidth, sourceHeight, boundWidth, boundHeight;
    private int actualWidth, actualHeight, bypassReason;
    private int lastCachedTexture, lastCachedOutput;
    private int lastCachedSourceWidth, lastCachedSourceHeight, lastCachedBoundWidth, lastCachedBoundHeight;
    private int lastOutputWidth, lastOutputHeight;
    private float lastU0, lastV0, lastU1, lastV1;
    private boolean lastCachedTopLeft, lastCachedCropped;
    private long targetBytes, extraOwnedBytes;
    private ArtemisGraphicsStats stats;
    private boolean gpuProfiling, profilingStarted;
    private GraphicsGpuTimer timer;

    /** Enabled only when an overlay is requested; all GL operations stay on the render thread. */
    public void setProfiler(ArtemisGraphicsStats stats, boolean gpu) { this.stats = stats; gpuProfiling = gpu; }
    private void startProfiling() {
        if (stats == null || profilingStarted) return;
        stats.configureNodes(chain, GLES30.glGetString(GLES30.GL_RENDERER));
        profilingStarted = true;
    }
    private void invalidateMeasurements() {
        if (timer != null) timer.invalidate();
        if (stats != null) stats.invalidateNodes();
    }

    public GraphicsFilterPipeline(Context context, GraphicsFilterChain chain) {
        this(context, chain, 0);
    }

    /**
     * Include adapter-owned input/buffer storage in the whole-chain budget.
     *
     * <p>{@code reservedBytes} must cover exactly the GL byte storage the adapter itself owns
     * beyond pipeline-owned targets that {@link #renderCroppedTexture} accounts for itself:
     * borrowed game textures and pipeline-internal targets are excluded. Hosts derive the value
     * from their own ownership model — e.g. the Artemis surface proxy reserves its RGBA8 input
     * target plus producer-side SurfaceTexture buffers, krkrsdl3 reserves its complete
     * RGBA8+D24S8 display canvas, and raw-frame bridges that borrow the game texture both ways
     * reserve nothing.</p>
     */
    public GraphicsFilterPipeline(Context context, GraphicsFilterChain chain, long reservedBytes) {
        if (context == null || chain == null || reservedBytes < 0)
            throw new IllegalArgumentException("invalid graphics pipeline");
        this.chain = chain;
        this.reservedBytes = reservedBytes;
        int count = chain.steps().size();
        nodes = new CnnPipeline[count];
        inputWidths = new int[count]; inputHeights = new int[count];
        outputWidths = new int[count]; outputHeights = new int[count]; execute = new boolean[count];
        for (int i = 0; i < count; i++) {
            GraphicsFilterChain.Step step = chain.steps().get(i);
            if (!step.enabled) continue;
            nodes[i] = CnnPipeline.forFilter(context, step);
        }
        surfaceAdapter = CnnPipeline.forLanczos(context);
    }

    public int renderTexture(int texture, int sw, int sh, int screenWidth, int screenHeight) {
        return renderTexture(texture, sw, sh, screenWidth, screenHeight, true);
    }

    /**
     * Renders an uncropped full-frame texture through active filter nodes.
     * When {@code frameDirty} is false and geometry and input texture are unchanged,
     * returns the previously rendered output texture without issuing CNN or resampling draws
     * (may still poll attached GPU profiling timers).
     */
    public int renderTexture(int texture, int sw, int sh, int screenWidth, int screenHeight, boolean frameDirty) {
        inputTopLeft = false;
        actualWidth = sw; actualHeight = sh;
        if (texture == 0 || !prepareGeometry(sw, sh, screenWidth, screenHeight)) {
            clearOutputCache();
            return texture;
        }
        if (!hasExecution) {
            clearOutputCache();
            bypassReason = CnnPipeline.BYPASS_NONE;
            targetRetry.reset();
            recordIdentity();
            return texture;
        }
        if (!frameDirty && canReuseOutput(texture, sw, sh, 0f, 0f, 1f, 1f, screenWidth, screenHeight, false, false)) {
            return reuseCachedOutput();
        }
        clearOutputCache();
        int result = run(texture, sw, sh);
        if (bypassReason == CnnPipeline.BYPASS_NONE && result != 0) {
            recordOutputCache(texture, sw, sh, 0f, 0f, 1f, 1f, screenWidth, screenHeight, false, false, result);
        }
        return result;
    }

    private int run(int original, int sw, int sh) {
        if (temporaryFailed || failed) { invalidateMeasurements(); return original; }
        startProfiling();
        if (gpuProfiling && hasExecution && timer == null) timer = new GraphicsGpuTimer(nodes.length);
        if (timer != null && stats != null) timer.poll(stats);
        int current = original, width = sw, height = sh;
        for (int i = 0; i < nodes.length; i++) {
            if (!execute[i]) continue;
            CnnPipeline node = nodes[i];
            int output;
            node.setInputTopLeft(inputTopLeft);
            // Measures actual current-frame GPU work; temporal cache reuse judges the frame
            // dirty before this loop, so cached frames skip node execution entirely.
            int planVersionBefore = node.getPlanVersion();
            if (timer != null) timer.begin(i);
            boolean unexpected = false;
            try { output = node.renderTexture(current, inputWidths[i], inputHeights[i], outputWidths[i], outputHeights[i]); }
            catch (RuntimeException unexpectedFailure) { output = 0; unexpected = true; }
            finally {
                if (timer != null) timer.end();
            }
            boolean planChanged = node.getPlanVersion() != planVersionBefore;
            if (unexpected) {
                failed = true; bypassReason = CnnPipeline.BYPASS_PIPELINE_FAILED;
                actualWidth = sw; actualHeight = sh; invalidateMeasurements(); return original;
            }
            if (output == 0 || node.isFailed()) {
                failed = node.isFailed();
                temporaryFailed = !failed;
                cropTemporaryFailed = false;
                bypassReason = node.bypassReason();
                if (bypassReason == CnnPipeline.BYPASS_NONE) {
                    bypassReason = CnnPipeline.BYPASS_PIPELINE_FAILED;
                    failed = true;
                }
                actualWidth = sw; actualHeight = sh;
                if (failed) invalidateMeasurements();
                else {
                    boolean retryAfterDelay = node.retriesTargetsAfterDelay();
                    discardTargets();
                    if (planChanged && !retryAfterDelay) {
                        // Force geometry re-admission next frame under the unfused pass plan
                        // (which may still fit within 256 MiB via a smaller tile size).
                        geometryKnown = false;
                        targetRetry.reset();
                    } else {
                        targetRetry.failed(retryAfterDelay);
                    }
                }
                return original;
            }
            if (planChanged) {
                if (!recheckChainBudgetAfterFallback(node)) {
                    temporaryFailed = true;
                    cropTemporaryFailed = false;
                    bypassReason = CnnPipeline.BYPASS_TARGET_UNAVAILABLE;
                    actualWidth = sw; actualHeight = sh;
                    discardTargets();
                    geometryKnown = false;
                    targetRetry.reset();
                    return original;
                }
            }
            current = output;
            width = node.outputWidth(); height = node.outputHeight();
        }
        actualWidth = width; actualHeight = height;
        bypassReason = CnnPipeline.BYPASS_NONE;
        targetRetry.reset(); // Only a complete chain resets the resource-pressure backoff.
        if (stats != null) stats.nodeFrame(inputWidths, inputHeights, outputWidths, outputHeights, execute);
        return current;
    }

    public int renderCroppedTexture(int texture, int sw, int sh, float u0, float v0,
                                    float u1, float v1, int bw, int bh) {
        return renderCroppedTexture(texture, sw, sh, u0, v0, u1, v1, bw, bh, false, true);
    }

    /** Input and output retain the engine's physical row order; shader coordinates are adapted per node. */
    public int renderCroppedTexture(int texture, int sw, int sh, float u0, float v0,
                                    float u1, float v1, int bw, int bh, boolean topLeft) {
        return renderCroppedTexture(texture, sw, sh, u0, v0, u1, v1, bw, bh, topLeft, true);
    }

    /**
     * Renders or crops the engine texture through active filter nodes.
     * When {@code frameDirty} is false and geometry, crop, and input texture are unchanged,
     * returns the previously rendered output texture without issuing CNN or resampling draws
     * (may still poll attached GPU profiling timers).
     */
    public int renderCroppedTexture(int texture, int sw, int sh, float u0, float v0,
                                    float u1, float v1, int bw, int bh, boolean topLeft, boolean frameDirty) {
        inputTopLeft = topLeft;
        actualWidth = sw; actualHeight = sh;
        boolean crop = u0 != 0 || v0 != 0 || u1 != 1 || v1 != 1;
        long extra = crop ? LanczosScale.ownedSurfaceBytes(sw, sh) : 0;
        if (temporaryFailed && cropTemporaryFailed
                && crop && (u0 != failedCropU0 || v0 != failedCropV0 || u1 != failedCropU1 || v1 != failedCropV1)) {
            temporaryFailed = false;
            cropTemporaryFailed = false;
            targetRetry.reset();
        }
        if (texture == 0 || !prepareGeometry(sw, sh, bw, bh, extra)) {
            clearOutputCache();
            return 0;
        }
        if (!hasExecution) {
            clearOutputCache();
            recordIdentity();
            return 0;
        }
        if (!frameDirty && canReuseOutput(texture, sw, sh, u0, v0, u1, v1, bw, bh, topLeft, true)) {
            return reuseCachedOutput();
        }
        clearOutputCache();
        int input = texture;
        boolean normalizingCrop = false;
        try {
            if (crop) {
                normalizingCrop = true;
                input = surfaceAdapter.normalizeBorrowedTexture(texture, sw, sh, u0, v0, u1, v1);
                normalizingCrop = false;
            }
            if (input == 0) {
                failed = surfaceAdapter.isFailed();
                temporaryFailed = !failed;
                cropTemporaryFailed = crop && temporaryFailed;
                if (cropTemporaryFailed) {
                    failedCropU0 = u0; failedCropV0 = v0; failedCropU1 = u1; failedCropV1 = v1;
                }
                bypassReason = surfaceAdapter.bypassReason();
                if (bypassReason == CnnPipeline.BYPASS_NONE)
                    bypassReason = CnnPipeline.BYPASS_TARGET_UNAVAILABLE;
                if (failed) invalidateMeasurements();
                else {
                    targetRetry.failed(surfaceAdapter.retriesTargetsAfterDelay());
                    discardTargets();
                }
                return 0;
            }
            int result = run(input, sw, sh);
            if (bypassReason == CnnPipeline.BYPASS_NONE && result != 0) {
                cropTemporaryFailed = false;
                recordOutputCache(texture, sw, sh, u0, v0, u1, v1, bw, bh, topLeft, true, result);
                return result;
            }
            return 0;
        } catch (TargetUnavailableException unavailable) {
            // Resource-pressure/attachment-class escapes keep the bounded retry semantics
            // used everywhere else in the pipeline (mirrors run()'s temporaryFailed path):
            // the chain retries at the target-retry rate instead of being permanently disabled.
            clearOutputCache();
            failed = false;
            temporaryFailed = true;
            cropTemporaryFailed = normalizingCrop;
            if (cropTemporaryFailed) {
                failedCropU0 = u0; failedCropV0 = v0; failedCropU1 = u1; failedCropV1 = v1;
            }
            bypassReason = CnnPipeline.BYPASS_TARGET_UNAVAILABLE;
            targetRetry.failed(surfaceAdapter.retriesTargetsAfterDelay());
            // discardTargets() already clears the output cache and invalidates measurements;
            // the RuntimeException branch below calls invalidateMeasurements() directly
            // because it does not go through discardTargets(). Defensive-dead-branch caveat
            // (kept deliberately): if a future path lets TargetUnavailableException escape
            // to this catch, discardTargets() -> GlState.capture can itself throw TUE out of
            // renderCroppedTexture; it is then caught by the JNI ExceptionCheck boundary
            // (single-frame direct output) rather than this class's failure classification.
            discardTargets();
            return 0;
        } catch (RuntimeException failure) {
            // Non-retryable escapes (broken GL discipline, invalid state) stay permanently
            // failed for this context, matching run()'s unexpected-failure handling.
            clearOutputCache();
            failed = true; bypassReason = CnnPipeline.BYPASS_PIPELINE_FAILED;
            invalidateMeasurements();
            return 0;
        }
    }

    /**
     * Host dirty-frame static reuse key invariant: the cached output is reproducible from this key alone.
     * The key must therefore cover every parameter that can change the rendered result —
     * input texture name, source geometry, crop window, bound size, and row order — while
     * frame content changes are guarded separately by the host-supplied {@code frameDirty}
     * flag ({@code frameDirty} judges content; this key judges geometry). A key hit with a matching
     * {@code frameDirty=false} input returns {@link #reuseCachedOutput()}, skipping CNN and
     * resampling draws (may still poll attached GPU profiling timers).
     */
    private boolean canReuseOutput(int texture, int sw, int sh, float u0, float v0,
                                   float u1, float v1, int bw, int bh, boolean topLeft, boolean cropped) {
        return lastCachedOutput != 0
                && lastCachedTexture == texture
                && lastCachedSourceWidth == sw
                && lastCachedSourceHeight == sh
                && lastCachedBoundWidth == bw
                && lastCachedBoundHeight == bh
                && lastU0 == u0 && lastV0 == v0 && lastU1 == u1 && lastV1 == v1
                && lastCachedTopLeft == topLeft
                && lastCachedCropped == cropped;
    }

    private void recordOutputCache(int texture, int sw, int sh, float u0, float v0,
                                   float u1, float v1, int bw, int bh, boolean topLeft,
                                   boolean cropped, int output) {
        lastCachedTexture = texture;
        lastCachedOutput = output;
        lastCachedSourceWidth = sw;
        lastCachedSourceHeight = sh;
        lastCachedBoundWidth = bw;
        lastCachedBoundHeight = bh;
        lastOutputWidth = actualWidth;
        lastOutputHeight = actualHeight;
        lastU0 = u0;
        lastV0 = v0;
        lastU1 = u1;
        lastV1 = v1;
        lastCachedTopLeft = topLeft;
        lastCachedCropped = cropped;
    }

    private void clearOutputCache() {
        lastCachedTexture = 0;
        lastCachedOutput = 0;
        lastCachedSourceWidth = 0;
        lastCachedSourceHeight = 0;
        lastCachedBoundWidth = 0;
        lastCachedBoundHeight = 0;
        lastOutputWidth = 0;
        lastOutputHeight = 0;
        lastU0 = 0f;
        lastV0 = 0f;
        lastU1 = 0f;
        lastV1 = 0f;
        lastCachedTopLeft = false;
        lastCachedCropped = false;
    }

    private int reuseCachedOutput() {
        actualWidth = lastOutputWidth;
        actualHeight = lastOutputHeight;
        bypassReason = CnnPipeline.BYPASS_NONE;
        if (timer != null && stats != null) timer.poll(stats);
        recordIdentity();
        return lastCachedOutput;
    }

    private void recordIdentity() {
        if (stats == null) return;
        stats.nodeFrame(inputWidths, inputHeights, outputWidths, outputHeights, execute);
    }
    private boolean prepareGeometry(int sw, int sh, int bw, int bh) {
        return prepareGeometry(sw, sh, bw, bh, 0);
    }
    private boolean prepareGeometry(int sw, int sh, int bw, int bh, long extra) {
        startProfiling();
        if (failed) { bypassReason = CnnPipeline.BYPASS_PIPELINE_FAILED; return false; }
        boolean sameGeometry = geometryKnown && sw == sourceWidth && sh == sourceHeight
                && bw == boundWidth && bh == boundHeight && extra == extraOwnedBytes;
        if (sameGeometry) {
            if (!temporaryFailed) return planValid;
            // Deterministic budget/geometry rejections stay cached. Resource pressure
            // retries the complete plan at a bounded rate, without exposing partial output.
            if (!planValid || !targetRetry.beginAttempt()) return false;
        } else targetRetry.reset();
        discardTargets(); // Avoid retaining old-size nodes while allocating the new whole-chain budget.
        geometryKnown = true; sourceWidth = sw; sourceHeight = sh; boundWidth = bw; boundHeight = bh;
        extraOwnedBytes = extra; temporaryFailed = false; cropTemporaryFailed = false; planValid = false; hasExecution = false;
        GraphicsFilterPlan plan = GraphicsFilterPlan.create(chain, sw, sh, bw, bh);
        if (plan == null) { bypassReason = CnnPipeline.BYPASS_INVALID_SIZE; return false; }
        hasExecution = plan.hasExecution;
        long bytes;
        try {
            for (int i = 0; i < nodes.length; i++) {
                execute[i] = plan.execute[i];
                inputWidths[i] = plan.inputWidths[i]; inputHeights[i] = plan.inputHeights[i];
                outputWidths[i] = plan.outputWidths[i]; outputHeights[i] = plan.outputHeights[i];
            }
            bytes = estimateTargets(extra, 0);
            // Keep full-frame processing when it fits; otherwise select the largest safe CNN
            // tile. Output-sized halo chains never tile (see tileDowngradeAllowed): tiling
            // would trip the node's fixed native-scale geometry invariant and latch a
            // permanent failure, so an oversized chain keeps its full-frame estimate and
            // takes the graceful target-unavailable bypass below.
            if (bytes > CnnPipeline.TARGET_MEMORY_BUDGET_BYTES && tileDowngradeAllowed(chain)) {
                for (int tile : new int[]{1024, 768, 512, 256}) {
                    if (bytes <= CnnPipeline.TARGET_MEMORY_BUDGET_BYTES) break;
                    bytes = estimateTargets(extra, tile);
                }
            }
        } catch (RuntimeException invalidFilter) {
            failed = true; bypassReason = CnnPipeline.BYPASS_PIPELINE_FAILED; return false;
        }
        if (bytes > CnnPipeline.TARGET_MEMORY_BUDGET_BYTES) {
            temporaryFailed = true;
            targetRetry.failed(false);
            bypassReason = CnnPipeline.BYPASS_TARGET_UNAVAILABLE;
            return false;
        }
        targetBytes = bytes; planValid = true; bypassReason = CnnPipeline.BYPASS_NONE;
        applyChainNodeBudgets();
        return true;
    }

    private void applyChainNodeBudgets() {
        long nonNodeBytes = LanczosScale.addBytes(extraOwnedBytes, reservedBytes);
        long totalNodesBytes = 0;
        long[] nodeBytes = new long[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            if (!execute[i]) continue;
            nodeBytes[i] = nodes[i].estimateTargetBytes(
                    inputWidths[i], inputHeights[i], outputWidths[i], outputHeights[i]);
            totalNodesBytes = LanczosScale.addBytes(totalNodesBytes, nodeBytes[i]);
        }
        for (int i = 0; i < nodes.length; i++) {
            if (!execute[i]) continue;
            long otherBytes = LanczosScale.addBytes(nonNodeBytes, Math.max(0L, totalNodesBytes - nodeBytes[i]));
            nodes[i].setChainBudgetBytes(Math.max(0L, CnnPipeline.TARGET_MEMORY_BUDGET_BYTES - otherBytes));
        }
    }

    private boolean recheckChainBudgetAfterFallback(CnnPipeline changedNode) {
        long recomputed = LanczosScale.addBytes(extraOwnedBytes, reservedBytes);
        for (int i = 0; i < nodes.length; i++) {
            if (!execute[i]) continue;
            recomputed = LanczosScale.addBytes(recomputed, nodes[i].estimateTargetBytes(
                    inputWidths[i], inputHeights[i], outputWidths[i], outputHeights[i]));
        }
        if (recomputed > CnnPipeline.TARGET_MEMORY_BUDGET_BYTES) {
            return false;
        }
        targetBytes = recomputed;
        applyChainNodeBudgets();
        return true;
    }

    private long estimateTargets(long extra, int tileCoreSize) {
        long bytes = LanczosScale.addBytes(extra, reservedBytes);
        for (int i = 0; i < nodes.length; i++) {
            if (!execute[i]) continue;
            nodes[i].setTileCoreSize(tileCoreSize);
            bytes = LanczosScale.addBytes(bytes, nodes[i].estimateTargetBytes(
                    inputWidths[i], inputHeights[i], outputWidths[i], outputHeights[i]));
        }
        return bytes;
    }

    /**
     * Tile tiling pins each node's restore target to fixed native-scale geometry, which an
     * output-sized definition cannot guarantee (its internal output follows the request).
     * Bundled descriptors never carry {@code haloPerPass>0} together with
     * {@code outputSized:true} — build-time verification rejects such definitions — and this
     * plan-time guard refuses the tile downgrade for any active node that does, so an
     * oversized chain degrades through the retryable target-unavailable bypass instead of
     * latching a permanent render-time failure.
     */
    static boolean tileDowngradeAllowed(GraphicsFilterChain chain) {
        for (GraphicsFilterChain.Step step : chain.steps()) {
            if (!step.enabled) continue;
            if (blocksTileDowngrade(GraphicsFilterDefinitions.find(step.id))) return false;
        }
        return true;
    }

    /** Single rule shared by the plan guard, the node assertion, and the build-time check. */
    static boolean blocksTileDowngrade(GraphicsFilterDefinition definition) {
        return definition != null && definition.haloPerPass > 0 && definition.outputSized;
    }

    private void discardTargets() {
        clearOutputCache();
        invalidateMeasurements();
        for (int i = 0; i < nodes.length; i++) if (nodes[i] != null) nodes[i].discardTargets();
        surfaceAdapter.discardTargets();
        targetBytes = 0;
    }
    public int outputWidth() { return actualWidth; }
    public int outputHeight() { return actualHeight; }
    public int bypassReason() { return bypassReason; }
    public boolean isFailed() { return failed; }
    public long targetBytes() { return targetBytes; }
    public void abandonContext() {
        clearOutputCache();
        if (timer != null) timer.release(false);
        timer = null; profilingStarted = false;
        invalidateMeasurements();
        for (int i = 0; i < nodes.length; i++) if (nodes[i] != null) nodes[i].abandonContext();
        surfaceAdapter.abandonContext();
        geometryKnown = planValid = temporaryFailed = failed = false;
        targetRetry.reset();
        actualWidth = actualHeight = 0; targetBytes = 0;
    }
    public void release() {
        clearOutputCache();
        if (timer != null) timer.release(true);
        timer = null; profilingStarted = false;
        invalidateMeasurements();
        for (int i = 0; i < nodes.length; i++) if (nodes[i] != null) nodes[i].release();
        surfaceAdapter.release();
        geometryKnown = planValid = temporaryFailed = failed = false;
        targetRetry.reset();
        actualWidth = actualHeight = 0; targetBytes = 0;
    }
}
