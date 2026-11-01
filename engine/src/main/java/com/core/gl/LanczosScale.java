package com.core.gl;

/** Geometry and GLES3 shaders for the optional separable resampling stage. */
final class LanczosScale {
    // Lanczos2: four reads per changed axis, versus six for Lanczos3. The shorter
    // support reduces mobile bandwidth and ringing around high-contrast VN text.
    static final ResampleKernel DEFAULT_KERNEL = ResampleKernel.LANCZOS2;

    /** Resampling kernels behind the scale-only filter presets; descriptor strings map fail-closed. */
    enum ResampleKernel {
        BILINEAR(1), BICUBIC(2), LANCZOS2(2);

        final int radius;

        ResampleKernel(int radius) { this.radius = radius; }
    }

    /** Descriptor string to kernel; unknown names fail closed instead of falling back. */
    static ResampleKernel kernelOf(String resampleKernel) {
        if (resampleKernel == null) return DEFAULT_KERNEL;
        switch (resampleKernel) {
            case "bilinear": return ResampleKernel.BILINEAR;
            case "bicubic": return ResampleKernel.BICUBIC;
            case "lanczos2": return ResampleKernel.LANCZOS2;
            default: throw new IllegalArgumentException("unknown resample kernel: " + resampleKernel);
        }
    }

    private static final String TEMPLATE =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "precision highp sampler2D;\n" +
            "in vec2 vPos;\n" +
            "out vec4 fragColor;\n" +
            "uniform sampler2D uSource;\n" +
            "uniform ivec2 uSourceSize;\n" +
            "uniform int uAxis;\n" +
            "@@EXTRA_UNIFORMS@@" +
            "@@WEIGHT_HELPERS@@" +
            "void main() {\n" +
            "    vec2 position = vPos * vec2(uSourceSize) - vec2(0.5);\n" +
            "    float center = uAxis == 0 ? position.x : position.y;\n" +
            "    int base = int(floor(center));\n" +
            "    ivec2 nearest = ivec2(floor(position + vec2(0.5)));\n" +
            "    vec4 sum = vec4(0.0); float total = 0.0;\n" +
            "@@AR_DECLARE@@" +
            "@@SUPPORT@@" +
            "    for (int tap = @@TAP_START@@; tap <= @@TAP_END@@; ++tap) {\n" +
            "        int coordinate = base + tap;\n" +
            "        float w = weight(@@WEIGHT_ARG@@);\n" +
            "@@LOOP_GUARD@@" +
            "        ivec2 samplePos = nearest;\n" +
            "        if (uAxis == 0) samplePos.x = coordinate; else samplePos.y = coordinate;\n" +
            "        samplePos = clamp(samplePos, ivec2(0), uSourceSize - ivec2(1));\n" +
            "        vec4 tapColor = texelFetch(uSource, samplePos, 0);\n" +
            "        sum += tapColor * w; total += w;\n" +
            "@@AR_ACCUMULATE@@" +
            "    }\n" +
            "    fragColor = sum / total;\n" +
            "@@AR_APPLY@@" +
            "    fragColor.a = 1.0;\n" +
            "}\n";

    /** Baseline Mitchell B=C=0.33 at the default sharpness 0.4; higher sharpness => sharper. */
    private static double bicubicConstant(double sharpness) {
        return Math.round((0.53 - 0.5 * sharpness) * 10000.0) / 10000.0;
    }

    static String fragmentShader(ResampleKernel kernel, double sharpness) {
        return shader(kernel, sharpness, false);
    }

    /** Reductions widen the reconstruction support in proportion to the actual ratio. */
    static String resampleFragmentShader(ResampleKernel kernel, double sharpness) {
        return shader(kernel, sharpness, true);
    }

    private static String shader(ResampleKernel kernel, double sharpness, boolean resample) {
        String radius = Integer.toString(kernel.radius);
        // Anti-ringing: clamp the normalized result to the nearest-2 texel extremes; the
        // strength is baked per node instance (sharpness is fixed when the program builds).
        boolean antiring = kernel == ResampleKernel.LANCZOS2 && sharpness > 0.0;
        String shader = TEMPLATE
                .replace("@@EXTRA_UNIFORMS@@", resample ? "uniform ivec2 uOutputSize;\n" : "")
                .replace("@@WEIGHT_HELPERS@@", weightHelpers(kernel, sharpness))
                .replace("@@TAP_START@@", resample ? "1 - support" : Integer.toString(1 - kernel.radius))
                .replace("@@TAP_END@@", resample ? "support" : radius)
                .replace("@@SUPPORT@@", resample
                        ? "    float scale = max(float(uAxis == 0 ? uSourceSize.x : uSourceSize.y) / "
                        + "float(uAxis == 0 ? uOutputSize.x : uOutputSize.y), 1.0);\n"
                        + "    int support = int(ceil(" + radius + ".0 * scale));\n" : "")
                .replace("@@WEIGHT_ARG@@", resample ? "(center - float(coordinate)) / scale"
                        : "center - float(coordinate)")
                .replace("@@LOOP_GUARD@@", resample ? "        if (w == 0.0) continue;\n" : "")
                .replace("@@AR_DECLARE@@", antiring
                        ? "    vec3 ringLo = vec3(1e30); vec3 ringHi = vec3(-1e30);\n" : "")
                .replace("@@AR_ACCUMULATE@@", antiring
                        ? "        if (coordinate == base || coordinate == base + 1) "
                        + "{ ringLo = min(ringLo, tapColor.rgb); ringHi = max(ringHi, tapColor.rgb); }\n" : "")
                .replace("@@AR_APPLY@@", antiring
                        ? "    fragColor.rgb = mix(fragColor.rgb, clamp(fragColor.rgb, ringLo, ringHi), "
                        + baked(sharpness) + ");\n" : "");
        if (shader.contains("@@")) {
            throw new AssertionError("unconsumed resample shader template marker");
        }
        return shader;
    }

    private static String weightHelpers(ResampleKernel kernel, double sharpness) {
        StringBuilder helpers = new StringBuilder();
        if (kernel == ResampleKernel.LANCZOS2) {
            helpers.append("const float PI = 3.14159265358979323846;\n")
                    .append("float sinc(float x) {\n")
                    .append("    if (abs(x) < 0.00001) return 1.0;\n")
                    .append("    float p = PI * x; return sin(p) / p;\n")
                    .append("}\n");
        }
        switch (kernel) {
            case LANCZOS2:
                helpers.append("float weight(float x) {\n")
                        .append("    if (abs(x) >= 2.0) return 0.0;\n")
                        .append("    return sinc(x) * sinc(x / 2.0);\n")
                        .append("}\n");
                break;
            case BILINEAR:
                helpers.append("float weight(float x) {\n")
                        .append("    return max(0.0, 1.0 - abs(x));\n")
                        .append("}\n");
                break;
            case BICUBIC:
                // Mitchell-Netravali with baked B=C; the default sharpness 0.4 lands on the
                // B=C=0.33 Mitchell baseline, lower sharpness smooths, higher sharpens.
                String bc = baked(bicubicConstant(sharpness));
                helpers.append("float weight(float x) {\n")
                        .append("    x = abs(x);\n")
                        .append("    float x2 = x * x;\n")
                        .append("    float x3 = x2 * x;\n")
                        .append("    if (x < 1.0) return ((12.0 - 9.0 * ").append(bc).append(" - 6.0 * ").append(bc)
                        .append(") * x3 + (-18.0 + 12.0 * ").append(bc).append(" + 6.0 * ").append(bc)
                        .append(") * x2 + (6.0 - 2.0 * ").append(bc).append(")) / 6.0;\n")
                        .append("    if (x < 2.0) return ((-").append(bc).append(" - 6.0 * ").append(bc)
                        .append(") * x3 + (6.0 * ").append(bc).append(" + 30.0 * ").append(bc)
                        .append(") * x2 + (-12.0 * ").append(bc).append(" - 48.0 * ").append(bc)
                        .append(") * x + 8.0 * ").append(bc).append(" + 24.0 * ").append(bc).append(") / 6.0;\n")
                        .append("    return 0.0;\n")
                        .append("}\n");
                break;
            default:
                throw new AssertionError("unmapped resample kernel: " + kernel);
        }
        return helpers.toString();
    }

    private static String baked(double value) {
        return Double.toString(Math.round(value * 10000.0) / 10000.0);
    }

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
