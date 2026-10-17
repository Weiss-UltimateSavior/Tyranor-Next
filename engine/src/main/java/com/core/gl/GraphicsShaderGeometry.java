package com.core.gl;

/** Native reconstruction geometry; the externally requested size always comes from the chain plan. */
final class GraphicsShaderGeometry {
    final int width, height;

    GraphicsShaderGeometry(int sw, int sh, int ow, int oh, int nativeScale, boolean outputSized, int maxOutputScale) {
        if (!LanczosScale.isPositive(sw, sh, ow, oh) || nativeScale < 1 || nativeScale > 2 || maxOutputScale < 1 || maxOutputScale > 2)
            throw new IllegalArgumentException("invalid shader geometry");
        if (outputSized) {
            // A contribution bounds its internal output; larger requests use a later resample.
            width = Math.min(Math.max(ow, sw), Math.multiplyExact(sw, maxOutputScale));
            height = Math.min(Math.max(oh, sh), Math.multiplyExact(sh, maxOutputScale));
        } else {
            width = Math.multiplyExact(sw, nativeScale);
            height = Math.multiplyExact(sh, nativeScale);
        }
    }

    long estimate(int sw, int sh, int ow, int oh, int intermediates, int selfSaves, boolean screenCopy) {
        if (intermediates < 0 || selfSaves < 0) return Long.MAX_VALUE;
        try {
            long source = Math.multiplyExact((long) sw, sh);
            long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
            bytes = Math.addExact(bytes, Math.multiplyExact(Math.multiplyExact(source, 8L),
                    Math.addExact((long) intermediates, selfSaves)));
            if (screenCopy) bytes = Math.addExact(bytes, Math.multiplyExact(source, 4L));
            if (width != ow || height != oh)
                bytes = Math.addExact(bytes, Math.multiplyExact(Math.multiplyExact((long) ow, oh), 4L));
            if (width != ow && height != oh)
                bytes = Math.addExact(bytes, Math.multiplyExact(Math.multiplyExact((long) ow, height), 8L));
            return bytes;
        } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
}
