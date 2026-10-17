package com.core.gl;

import java.util.Arrays;

/** Shared graphics counters. Historical Artemis name is retained for the bridge API. */
public final class ArtemisGraphicsStats {
    private static final double MAX_GPU_SAMPLE_MS = 1000;
    private int sourceWidth, sourceHeight, outputWidth, outputHeight;
    private int viewportWidth, viewportHeight, surfaceWidth, surfaceHeight;
    private long presented, sampledPresented, sampleTimeNanos;
    private double gpuTotalMs;
    private int gpuSamples;
    private boolean gpuAvailable;
    private String renderer = "";
    private String[] nodeIds = new String[0];
    private double[] nodeGpu = new double[0], nodeScale = new double[0], nodeScaleMin = new double[0], nodeScaleMax = new double[0];
    private int[] nodeGpuSamples = new int[0];
    private boolean[] nodeExecuted = new boolean[0];

    public synchronized void configureNodes(GraphicsFilterChain chain, String renderer) {
        nodeIds = new String[chain.steps().size()];
        for (int i = 0; i < nodeIds.length; i++) nodeIds[i] = chain.steps().get(i).enabled ? chain.steps().get(i).id : null;
        nodeGpu = new double[nodeIds.length]; nodeScale = new double[nodeIds.length];
        nodeScaleMin = new double[nodeIds.length]; nodeScaleMax = new double[nodeIds.length];
        nodeGpuSamples = new int[nodeIds.length];
        nodeExecuted = new boolean[nodeIds.length];
        this.renderer = renderer == null ? "" : renderer;
    }
    public synchronized void nodeFrame(int[] widths, int[] heights, int[] outputs, int[] outputHeights, boolean[] execute) {
        if (widths.length != nodeIds.length) return;
        for (int i = 0; i < widths.length; i++) {
            if (nodeIds[i] == null) continue;
            nodeExecuted[i] = execute[i];
            double a = widths[i] > 0 ? (double) outputs[i] / widths[i] : Double.NaN;
            double b = heights[i] > 0 ? (double) outputHeights[i] / heights[i] : Double.NaN;
            nodeScaleMin[i] = Math.min(a, b); nodeScaleMax[i] = Math.max(a, b);
            nodeScale[i] = Math.sqrt(a * b);
            // A bypassed frame has no new timing, but cannot invalidate valid samples
            // already collected in this refresh interval (queries may finish later).
        }
    }
    public synchronized void nodeGpu(int index, double ms) {
        if (index < 0 || index >= nodeIds.length || nodeIds[index] == null) return;
        // Skip timing outliers without discarding valid samples in this interval.
        if (ms > MAX_GPU_SAMPLE_MS) return;
        if (Double.isFinite(ms) && ms >= 0) { nodeGpu[index] += ms; nodeGpuSamples[index]++; }
        else { nodeGpu[index] = 0; nodeGpuSamples[index] = 0; }
    }
    public synchronized void invalidateNodes() {
        Arrays.fill(nodeGpu, 0); Arrays.fill(nodeScale, Double.NaN);
        Arrays.fill(nodeScaleMin, Double.NaN); Arrays.fill(nodeScaleMax, Double.NaN);
        Arrays.fill(nodeGpuSamples, 0);
        Arrays.fill(nodeExecuted, false);
    }

    public synchronized void reset() {
        sourceWidth = sourceHeight = outputWidth = outputHeight = 0;
        viewportWidth = viewportHeight = surfaceWidth = surfaceHeight = 0;
        presented = sampledPresented = sampleTimeNanos = 0;
        gpuTotalMs = 0;
        gpuSamples = 0;
        gpuAvailable = false;
        renderer = ""; nodeIds = new String[0];
        nodeGpu = nodeScale = nodeScaleMin = nodeScaleMax = new double[0];
        nodeGpuSamples = new int[0];
        nodeExecuted = new boolean[0];
    }

    public synchronized void frame(int sw, int sh, int ow, int oh, int vw, int vh) {
        sourceWidth = sw; sourceHeight = sh;
        outputWidth = ow; outputHeight = oh;
        viewportWidth = vw; viewportHeight = vh;
    }

    public synchronized void surface(int width, int height) {
        surfaceWidth = width; surfaceHeight = height;
    }

    public synchronized void presented() { presented++; }

    public synchronized void gpu(double ms) {
        if (ms > MAX_GPU_SAMPLE_MS) return;
        gpuAvailable = Double.isFinite(ms) && ms >= 0;
        if (gpuAvailable) { gpuTotalMs += ms; gpuSamples++; }
        else { gpuTotalMs = 0; gpuSamples = 0; }
    }

    public synchronized void resetInterval(long nowNanos) {
        sampledPresented = presented;
        sampleTimeNanos = nowNanos;
        gpuSamples = 0;
        gpuTotalMs = 0;
        Arrays.fill(nodeGpu, 0);
        Arrays.fill(nodeGpuSamples, 0);
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        double seconds = (nowNanos - sampleTimeNanos) / 1_000_000_000.0;
        double fps = sampleTimeNanos > 0 && seconds > 0 ? (presented - sampledPresented) / seconds : 0;
        Node[] nodes = new Node[nodeIds.length];
        for (int i = 0; i < nodes.length; i++) nodes[i] = new Node(nodeIds[i], nodeExecuted[i], nodeScale[i], nodeScaleMin[i], nodeScaleMax[i],
                nodeExecuted[i] && nodeGpuSamples[i] > 0 ? nodeGpu[i] / nodeGpuSamples[i] : Double.NaN);
        double measuredGpu = gpuAvailable && gpuSamples > 0 ? gpuTotalMs / gpuSamples : Double.NaN;
        if (!Double.isFinite(measuredGpu) && nodes.length > 0) {
            double sum = 0; boolean complete = false;
            for (Node node : nodes) if (node.id != null && node.executed) {
                if (!Double.isFinite(node.gpuMs)) { sum = Double.NaN; break; }
                complete = true; sum += node.gpuMs;
            }
            if (complete && Double.isFinite(sum)) measuredGpu = sum;
        }
        if (measuredGpu > MAX_GPU_SAMPLE_MS) measuredGpu = Double.NaN;
        Snapshot result = new Snapshot(sourceWidth, sourceHeight, outputWidth, outputHeight,
                viewportWidth, viewportHeight, surfaceWidth, surfaceHeight, fps,
                measuredGpu, renderer, nodes);
        resetInterval(nowNanos);
        return result;
    }

    public static final class Node {
        public final String id;
        public final boolean executed;
        public final double scale, scaleMin, scaleMax, gpuMs;
        Node(String id, boolean executed, double scale, double scaleMin, double scaleMax, double gpuMs) {
            this.id = id; this.executed = executed; this.scale = scale; this.scaleMin = scaleMin; this.scaleMax = scaleMax;
            this.gpuMs = gpuMs;
        }
    }
    public static final class Snapshot {
        public final int sourceWidth, sourceHeight, outputWidth, outputHeight;
        public final int viewportWidth, viewportHeight, surfaceWidth, surfaceHeight;
        public final double fps, gpuMs;
        public final String renderer;
        public final Node[] nodes;
        Snapshot(int sw, int sh, int ow, int oh, int vw, int vh, int dw, int dh,
                 double fps, double gpuMs, String renderer, Node[] nodes) {
            sourceWidth = sw; sourceHeight = sh; outputWidth = ow; outputHeight = oh;
            viewportWidth = vw; viewportHeight = vh; surfaceWidth = dw; surfaceHeight = dh;
            this.fps = fps; this.gpuMs = gpuMs;
            this.renderer = renderer; this.nodes = nodes;
        }
    }
}
