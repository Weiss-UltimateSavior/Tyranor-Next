package com.core.gl;

/** Fits a source aspect ratio inside real display bounds, including reductions. */
public final class GraphicsOutputSize {
    public static final class Size {
        public final int width, height;
        private Size(int width, int height) { this.width = width; this.height = height; }
        public double scaleX(int sourceWidth) { return sourceWidth > 0 ? (double) width / sourceWidth : Double.NaN; }
        public double scaleY(int sourceHeight) { return sourceHeight > 0 ? (double) height / sourceHeight : Double.NaN; }
    }
    private GraphicsOutputSize() {}
    public static Size fit(int sourceWidth, int sourceHeight, int screenWidth, int screenHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null;
        if ((long) screenWidth * sourceHeight <= (long) screenHeight * sourceWidth) {
            return new Size(screenWidth, Math.max(1, (int) ((long) sourceHeight * screenWidth / sourceWidth)));
        }
        return new Size(Math.max(1, (int) ((long) sourceWidth * screenHeight / sourceHeight)), screenHeight);
    }
}
