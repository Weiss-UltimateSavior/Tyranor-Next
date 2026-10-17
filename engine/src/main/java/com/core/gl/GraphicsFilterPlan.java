package com.core.gl;

/** Pure node geometry planning used by the real GL pipeline and protocol tests. */
final class GraphicsFilterPlan {
    final int[] inputWidths, inputHeights, outputWidths, outputHeights;
    final boolean[] execute;
    final GraphicsOutputSize.Size fitted;
    final boolean hasExecution;

    private GraphicsFilterPlan(GraphicsFilterChain chain, int sw, int sh, GraphicsOutputSize.Size fitted, int bw, int bh) {
        this.fitted = fitted;
        int count = chain.steps().size();
        inputWidths = new int[count]; inputHeights = new int[count];
        outputWidths = new int[count]; outputHeights = new int[count]; execute = new boolean[count];
        int width = sw, height = sh;
        boolean any = false;
        for (int i = 0; i < count; i++) {
            GraphicsFilterChain.Step step = chain.steps().get(i);
            inputWidths[i] = width; inputHeights[i] = height;
            boolean active = step.enabled;
            GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(step.id);
            if (active && definition.nativeScale > 1) {
                // Preserve the contribution's native scale; the host retains default display scaling.
                width = Math.multiplyExact(width, definition.nativeScale); height = Math.multiplyExact(height, definition.nativeScale);
            } else if (active && !"none".equals(step.scaleMode)) {
                GraphicsOutputSize.Size base = "fit".equals(step.scaleMode)
                        ? GraphicsOutputSize.fit(width, height, bw, bh) : null;
                int targetWidth = dimension((base == null ? width : base.width) * step.widthScale);
                int targetHeight = dimension((base == null ? height : base.height) * step.heightScale);
                active = width != targetWidth || height != targetHeight || definition.sharpnessAdjustable;
                if (active) { width = targetWidth; height = targetHeight; }
            }
            execute[i] = active;
            outputWidths[i] = width; outputHeights[i] = height;
            any |= active;
        }
        hasExecution = any;
    }
    private static int dimension(double value) {
        long rounded = Math.round(value);
        if (!Double.isFinite(value) || value <= 0 || rounded > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid scale dimension");
        return (int) Math.max(1, rounded);
    }
    static GraphicsFilterPlan create(GraphicsFilterChain chain, int sw, int sh, int bw, int bh) {
        GraphicsOutputSize.Size fitted = GraphicsOutputSize.fit(sw, sh, bw, bh);
        if (chain == null || fitted == null) return null;
        try { return new GraphicsFilterPlan(chain, sw, sh, fitted, bw, bh); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return null; }
    }
}
