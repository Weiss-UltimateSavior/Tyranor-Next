package com.core.gl;

import android.content.Context;
import android.opengl.GLES30;

/** Ordered, atomic filter execution. On failure, presents the borrowed original, never half a chain. */
public final class GraphicsFilterPipeline {
    private final GraphicsFilterChain chain;
    private final long reservedBytes;
    private final Anime4kPostProcessor[] nodes;
    private final Anime4kPostProcessor surfaceAdapter;
    private final GraphicsTargetRetry targetRetry = new GraphicsTargetRetry();
    private final int[] inputWidths, inputHeights, outputWidths, outputHeights;
    private final boolean[] execute;
    private boolean geometryKnown, planValid, temporaryFailed, failed, hasExecution;
    private boolean inputTopLeft;
    private int sourceWidth, sourceHeight, boundWidth, boundHeight;
    private int actualWidth, actualHeight, bypassReason;
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

    /** Include adapter-owned input/buffer storage in the whole-chain budget. */
    public GraphicsFilterPipeline(Context context, GraphicsFilterChain chain, long reservedBytes) {
        if (context == null || chain == null || reservedBytes < 0)
            throw new IllegalArgumentException("invalid graphics pipeline");
        this.chain = chain;
        this.reservedBytes = reservedBytes;
        int count = chain.steps().size();
        nodes = new Anime4kPostProcessor[count];
        inputWidths = new int[count]; inputHeights = new int[count];
        outputWidths = new int[count]; outputHeights = new int[count]; execute = new boolean[count];
        for (int i = 0; i < count; i++) {
            GraphicsFilterChain.Step step = chain.steps().get(i);
            if (!step.enabled) continue;
            nodes[i] = Anime4kPostProcessor.forFilter(context, step);
        }
        surfaceAdapter = Anime4kPostProcessor.forLanczos(context);
    }

    public int renderTexture(int texture, int sw, int sh, int screenWidth, int screenHeight) {
        inputTopLeft = false;
        actualWidth = sw; actualHeight = sh;
        if (texture == 0 || !prepareGeometry(sw, sh, screenWidth, screenHeight)) return texture;
        return run(texture, sw, sh);
    }

    private int run(int original, int sw, int sh) {
        if (temporaryFailed || failed) { invalidateMeasurements(); return original; }
        startProfiling();
        if (gpuProfiling && hasExecution && timer == null) timer = new GraphicsGpuTimer(nodes.length);
        if (timer != null) timer.poll(stats);
        int current = original, width = sw, height = sh;
        for (int i = 0; i < nodes.length; i++) {
            if (!execute[i]) continue;
            Anime4kPostProcessor node = nodes[i];
            int output;
            node.setInputTopLeft(inputTopLeft);
            if (timer != null) timer.begin(i);
            boolean unexpected = false;
            try { output = node.renderTexture(current, inputWidths[i], inputHeights[i], outputWidths[i], outputHeights[i]); }
            catch (RuntimeException unexpectedFailure) { output = 0; unexpected = true; }
            finally {
                if (timer != null) timer.end();
            }
            if (unexpected) {
                failed = true; bypassReason = Anime4kPostProcessor.BYPASS_PIPELINE_FAILED;
                actualWidth = sw; actualHeight = sh; invalidateMeasurements(); return original;
            }
            if (output == 0 || node.isFailed()) {
                failed = node.isFailed();
                temporaryFailed = !failed;
                bypassReason = node.bypassReason();
                if (bypassReason == Anime4kPostProcessor.BYPASS_NONE) {
                    bypassReason = Anime4kPostProcessor.BYPASS_PIPELINE_FAILED;
                    failed = true;
                }
                actualWidth = sw; actualHeight = sh;
                if (failed) invalidateMeasurements();
                else {
                    targetRetry.failed(node.retriesTargetsAfterDelay());
                    discardTargets();
                }
                return original;
            }
            current = output;
            width = node.outputWidth(); height = node.outputHeight();
        }
        actualWidth = width; actualHeight = height;
        bypassReason = Anime4kPostProcessor.BYPASS_NONE;
        targetRetry.reset(); // Only a complete chain resets the resource-pressure backoff.
        if (stats != null) stats.nodeFrame(inputWidths, inputHeights, outputWidths, outputHeights, execute);
        return current;
    }

    public int renderCroppedTexture(int texture, int sw, int sh, float u0, float v0,
                                    float u1, float v1, int bw, int bh) {
        return renderCroppedTexture(texture, sw, sh, u0, v0, u1, v1, bw, bh, false);
    }

    /** Input and output retain the engine's physical row order; shader coordinates are adapted per node. */
    public int renderCroppedTexture(int texture, int sw, int sh, float u0, float v0,
                                    float u1, float v1, int bw, int bh, boolean topLeft) {
        inputTopLeft = topLeft;
        actualWidth = sw; actualHeight = sh;
        boolean crop = u0 != 0 || v0 != 0 || u1 != 1 || v1 != 1;
        long extra = crop ? ownedSurfaceBytes(sw, sh) : 0;
        if (texture == 0 || !prepareGeometry(sw, sh, bw, bh, extra)) return 0;
        if (!hasExecution) { recordIdentity(); return 0; }
        int input = texture;
        try {
            if (crop) input = surfaceAdapter.normalizeBorrowedTexture(texture, sw, sh, u0, v0, u1, v1);
            if (input == 0) {
                failed = surfaceAdapter.isFailed();
                temporaryFailed = !failed;
                bypassReason = surfaceAdapter.bypassReason();
                if (bypassReason == Anime4kPostProcessor.BYPASS_NONE)
                    bypassReason = Anime4kPostProcessor.BYPASS_TARGET_UNAVAILABLE;
                if (failed) invalidateMeasurements();
                else {
                    targetRetry.failed(surfaceAdapter.retriesTargetsAfterDelay());
                    discardTargets();
                }
                return 0;
            }
            int result = run(input, sw, sh);
            return bypassReason == Anime4kPostProcessor.BYPASS_NONE ? result : 0;
        } catch (RuntimeException failure) {
            failed = true; bypassReason = Anime4kPostProcessor.BYPASS_PIPELINE_FAILED;
            invalidateMeasurements(); return 0;
        }
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
        if (failed) { bypassReason = Anime4kPostProcessor.BYPASS_PIPELINE_FAILED; return false; }
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
        extraOwnedBytes = extra; temporaryFailed = false; planValid = false; hasExecution = false;
        GraphicsFilterPlan plan = GraphicsFilterPlan.create(chain, sw, sh, bw, bh);
        if (plan == null) { bypassReason = Anime4kPostProcessor.BYPASS_INVALID_SIZE; return false; }
        hasExecution = plan.hasExecution;
        long bytes;
        try {
            for (int i = 0; i < nodes.length; i++) {
                execute[i] = plan.execute[i];
                inputWidths[i] = plan.inputWidths[i]; inputHeights[i] = plan.inputHeights[i];
                outputWidths[i] = plan.outputWidths[i]; outputHeights[i] = plan.outputHeights[i];
            }
            bytes = estimateTargets(extra, 0);
            // Keep full-frame processing when it fits; otherwise select the largest safe CNN tile.
            for (int tile : new int[]{1024, 768, 512, 256}) {
                if (bytes <= Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES) break;
                bytes = estimateTargets(extra, tile);
            }
        } catch (RuntimeException invalidFilter) {
            failed = true; bypassReason = Anime4kPostProcessor.BYPASS_PIPELINE_FAILED; return false;
        }
        if (bytes > Anime4kPostProcessor.TARGET_MEMORY_BUDGET_BYTES) {
            temporaryFailed = true;
            targetRetry.failed(false);
            bypassReason = Anime4kPostProcessor.BYPASS_TARGET_UNAVAILABLE;
            return false;
        }
        targetBytes = bytes; planValid = true; bypassReason = Anime4kPostProcessor.BYPASS_NONE;
        return true;
    }

    private long estimateTargets(long extra, int tileCoreSize) {
        long bytes = addBytes(extra, reservedBytes);
        for (int i = 0; i < nodes.length; i++) {
            if (!execute[i]) continue;
            nodes[i].setTileCoreSize(tileCoreSize);
            bytes = addBytes(bytes, nodes[i].estimateTargetBytes(
                    inputWidths[i], inputHeights[i], outputWidths[i], outputHeights[i]));
        }
        return bytes;
    }

    static long addBytes(long accumulated, long nodeBytes) {
        if (accumulated < 0 || nodeBytes < 0) return Long.MAX_VALUE;
        try { return Math.addExact(accumulated, nodeBytes); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    static long ownedSurfaceBytes(int width, int height) {
        if (width <= 0 || height <= 0) return Long.MAX_VALUE;
        try { return Math.multiplyExact(Math.multiplyExact((long) width, height), 4L); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private void discardTargets() {
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
