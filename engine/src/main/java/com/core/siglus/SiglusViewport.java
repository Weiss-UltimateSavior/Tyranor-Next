package com.core.siglus;

import com.core.gl.GraphicsOutputSize;

/** One integer viewport shared by presentation, touch and IME. No Android state. */
final class SiglusViewport {
    final int sourceWidth, sourceHeight, surfaceWidth, surfaceHeight;
    final int left, top, width, height;

    private SiglusViewport(int sw, int sh, int dw, int dh, int w, int h) {
        sourceWidth = sw; sourceHeight = sh; surfaceWidth = dw; surfaceHeight = dh;
        width = w; height = h; left = (dw - w) / 2; top = (dh - h) / 2;
    }

    static SiglusViewport fit(int sw, int sh, int dw, int dh) {
        GraphicsOutputSize.Size size = GraphicsOutputSize.fit(sw, sh, dw, dh);
        return size == null ? null : new SiglusViewport(sw, sh, dw, dh, size.width, size.height);
    }

    static boolean isOneToOne(int[] g) {
        return g != null && g.length >= 10 && g[0] > 0 && g[1] > 0
                && g[2] == g[0] && g[3] == g[1] && g[4] == 0 && g[5] == 0
                && g[6] == g[0] && g[7] == g[1];
    }

    static long renderSerial(int[] geometry) {
        return ((long) geometry[9] << 32) | (geometry[8] & 0xffffffffL);
    }

    boolean contains(double x, double y) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }

    double sourceX(double x) { return Math.max(0, Math.min(sourceWidth, (x - left) * sourceWidth / width)); }
    double sourceY(double y) { return Math.max(0, Math.min(sourceHeight, (y - top) * sourceHeight / height)); }
    double screenX(double x) { return left + x * width / sourceWidth; }
    double screenY(double y) { return top + y * height / sourceHeight; }
}
