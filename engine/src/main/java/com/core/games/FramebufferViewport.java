package com.core.games;

import com.core.gl.GraphicsOutputSize;

/**
 * One integer viewport shared by presentation and touch mapping. No Android state.
 * The filter path fits proportionally only; the classic view's near-integer scale
 * preference is intentionally not applied here (the chain output is already
 * high-resolution, and both presentation and touch must agree on one rect).
 */
final class FramebufferViewport {
    final int sourceWidth, sourceHeight, surfaceWidth, surfaceHeight;
    final int left, top, width, height;

    private FramebufferViewport(int sw, int sh, int dw, int dh, int w, int h) {
        sourceWidth = sw; sourceHeight = sh; surfaceWidth = dw; surfaceHeight = dh;
        width = w; height = h; left = (dw - w) / 2; top = (dh - h) / 2;
    }

    static FramebufferViewport fit(int sw, int sh, int dw, int dh) {
        GraphicsOutputSize.Size size = GraphicsOutputSize.fit(sw, sh, dw, dh);
        return size == null ? null : new FramebufferViewport(sw, sh, dw, dh, size.width, size.height);
    }

    /** Map view coordinates to engine frame pixels, clamped into the frame exactly
     * like the classic view (last pixel inclusive), in double precision. */
    int[] toSource(float x, float y) {
        double sx = Math.max(0, Math.min(sourceWidth - 1, (x - left) * sourceWidth / (double) width));
        double sy = Math.max(0, Math.min(sourceHeight - 1, (y - top) * sourceHeight / (double) height));
        return new int[] {(int) sx, (int) sy};
    }
}
