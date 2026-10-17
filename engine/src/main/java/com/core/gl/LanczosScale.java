package com.core.gl;

/** Geometry and GLES3 shader for the optional separable Lanczos resampling stage. */
final class LanczosScale {
    // Lanczos2: four reads per changed axis, versus six for Lanczos3. The shorter
    // support reduces mobile bandwidth and ringing around high-contrast VN text.
    static final int RADIUS = 2;

    static final String FRAGMENT_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "precision highp sampler2D;\n" +
            "in vec2 vPos;\n" +
            "out vec4 fragColor;\n" +
            "uniform sampler2D uSource;\n" +
            "uniform ivec2 uSourceSize;\n" +
            "uniform int uAxis;\n" +
            "const float PI = 3.14159265358979323846;\n" +
            "float sinc(float x) {\n" +
            "    if (abs(x) < 0.00001) return 1.0;\n" +
            "    float p = PI * x; return sin(p) / p;\n" +
            "}\n" +
            "float weight(float x) {\n" +
            "    if (abs(x) >= " + RADIUS + ".0) return 0.0;\n" +
            "    return sinc(x) * sinc(x / " + RADIUS + ".0);\n" +
            "}\n" +
            "void main() {\n" +
            "    vec2 position = vPos * vec2(uSourceSize) - vec2(0.5);\n" +
            "    float center = uAxis == 0 ? position.x : position.y;\n" +
            "    int base = int(floor(center));\n" +
            "    ivec2 nearest = ivec2(floor(position + vec2(0.5)));\n" +
            "    vec4 sum = vec4(0.0); float total = 0.0;\n" +
            "    for (int tap = " + (1 - RADIUS) + "; tap <= " + RADIUS + "; ++tap) {\n" +
            "        int coordinate = base + tap;\n" +
            "        float w = weight(center - float(coordinate));\n" +
            "        ivec2 samplePos = nearest;\n" +
            "        if (uAxis == 0) samplePos.x = coordinate; else samplePos.y = coordinate;\n" +
            "        samplePos = clamp(samplePos, ivec2(0), uSourceSize - ivec2(1));\n" +
            "        sum += texelFetch(uSource, samplePos, 0) * w; total += w;\n" +
            "    }\n" +
            "    fragColor = sum / total;\n" +
            "    fragColor.a = 1.0;\n" +
            "}\n";

    // Reductions widen the reconstruction support in proportion to the actual ratio.
    static final String RESAMPLE_FRAGMENT_SHADER = FRAGMENT_SHADER
            .replace("uniform int uAxis;", "uniform int uAxis;\nuniform ivec2 uOutputSize;")
            .replace("int base = int(floor(center));", "int base = int(floor(center));\n"
                    + "float scale = max(float(uAxis == 0 ? uSourceSize.x : uSourceSize.y) / "
                    + "float(uAxis == 0 ? uOutputSize.x : uOutputSize.y), 1.0);\nint support = int(ceil(2.0 * scale));")
            .replace("tap = -1; tap <= 2", "tap = 1 - support; tap <= support")
            .replace("weight(center - float(coordinate))", "weight((center - float(coordinate)) / scale)")
            .replace("ivec2 samplePos = nearest;", "if (w == 0.0) continue;\nivec2 samplePos = nearest;");

    static final class Plan {
        final int sourceWidth, sourceHeight, outputWidth, outputHeight;
        final boolean horizontal, vertical;
        // If both axes change, the X result is outputWidth x sourceHeight (not x2).
        final int horizontalWidth, horizontalHeight;

        Plan(int sw, int sh, int ow, int oh) {
            if (!isPositive(sw, sh, ow, oh)) {
                throw new IllegalArgumentException("Lanczos requires positive dimensions");
            }
            sourceWidth = sw; sourceHeight = sh;
            outputWidth = ow; outputHeight = oh;
            horizontal = ow != sw; vertical = oh != sh;
            horizontalWidth = ow; horizontalHeight = sh;
        }
    }

    static Plan resample(int sw, int sh, int ow, int oh) { return new Plan(sw, sh, ow, oh); }

    private LanczosScale() {}

    static boolean isPositive(int sw, int sh, int ow, int oh) {
        return sw > 0 && sh > 0 && ow > 0 && oh > 0;
    }

    static boolean fitsLimits(int sw, int sh, int ow, int oh, int maxWidth, int maxHeight) {
        return isPositive(sw, sh, ow, oh) && maxWidth > 0 && maxHeight > 0
                && sw <= maxWidth && ow <= maxWidth && sh <= maxHeight && oh <= maxHeight;
    }

    /** Saturating estimate of owned texture storage; borrowed source texture is excluded. */
    static long estimatedOwnedBytes(int sw, int sh, int ow, int oh,
                                   int intermediateCount, int selfSaveCount, boolean screenCopy) {
        return estimatedOwnedBytes(sw, sh, ow, oh, intermediateCount, selfSaveCount, screenCopy, true);
    }

    static long estimatedOwnedBytes(int sw, int sh, int ow, int oh,
                                   int intermediateCount, int selfSaveCount, boolean screenCopy, boolean restore) {
        if (!isPositive(sw, sh, ow, oh) || intermediateCount < 0 || selfSaveCount < 0) {
            return Long.MAX_VALUE;
        }
        try {
            long sourcePixels = Math.multiplyExact((long) sw, sh);
            long bytes = restore ? Math.multiplyExact(sourcePixels, 4L) : 0; // No hidden Restore in a scale node.
            if (screenCopy) bytes = Math.addExact(bytes, Math.multiplyExact(sourcePixels, 4L));
            long halfFloatCount = Math.addExact((long) intermediateCount, selfSaveCount);
            bytes = Math.addExact(bytes, Math.multiplyExact(
                    Math.multiplyExact(sourcePixels, 8L), halfFloatCount));
            if (ow != sw || oh != sh) {
                bytes = Math.addExact(bytes, Math.multiplyExact(Math.multiplyExact((long) ow, oh), 4L));
            }
            if (ow != sw && oh != sh) {
                // Pure scale nodes use RGBA8; reconstruction keeps the negative
                // lobes in RGBA16F until its final output.
                long horizontalBytesPerPixel = restore ? 8L : 4L;
                bytes = Math.addExact(bytes, Math.multiplyExact(
                        Math.multiplyExact((long) ow, sh), horizontalBytesPerPixel));
            }
            return bytes;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
