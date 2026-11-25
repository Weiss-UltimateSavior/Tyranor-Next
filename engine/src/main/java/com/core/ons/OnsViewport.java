package com.core.ons;

import com.core.gl.GraphicsOutputSize;

/** One integer viewport shared by presentation and touch. No Android state. */
public final class OnsViewport {
    public final int sourceWidth, sourceHeight, surfaceWidth, surfaceHeight;
    public final int left, top, width, height;

    private OnsViewport(int sw, int sh, int dw, int dh, int w, int h) {
        sourceWidth = sw;
        sourceHeight = sh;
        surfaceWidth = dw;
        surfaceHeight = dh;
        width = w;
        height = h;
        left = (dw - w) / 2;
        top = (dh - h) / 2;
    }

    public static OnsViewport fit(int sw, int sh, int dw, int dh) {
        GraphicsOutputSize.Size size = GraphicsOutputSize.fit(sw, sh, dw, dh);
        return size == null ? null : new OnsViewport(sw, sh, dw, dh, size.width, size.height);
    }

    public boolean contains(double x, double y) {
        return x >= left && y >= top && x < left + width && y < top + height;
    }

    /**
     * 视口坐标 → 舞台浮点坐标，钳制到 {@code [0, sourceWidth]} 的独占上界。
     *
     * <p>与 {@code FramebufferViewport.toSource} 的「整数末像素含入」口径不同，这是有意为之：
     * 本值直接喂给 {@code SDLActivity.onNativeMouse}（浮点像素坐标，引擎侧再自行钳制），
     * 且必须与 {@link #screenX} 构成往返恒等式。若改成 {@code sourceWidth - 1}，
     * 右边界附近的 {@code sourceX(screenX(x)) != x}，触控会整体右移一格。</p>
     *
     * <p>非有限输入（NaN / Infinity）一律归零，与 {@code OnsInput.normalizeX/Y} 同口径：
     * {@code Math.max/min} 会把 NaN 原样透传，native 鼠标状态机会因此错乱。</p>
     */
    public double sourceX(double x) {
        return clampSource((x - left) * sourceWidth / width, sourceWidth);
    }

    /** 同 {@link #sourceX}：独占上界的浮点映射，保持往返恒等。 */
    public double sourceY(double y) {
        return clampSource((y - top) * sourceHeight / height, sourceHeight);
    }

    private static double clampSource(double value, int upper) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(upper, value));
    }

    public double screenX(double x) {
        return left + x * width / sourceWidth;
    }

    public double screenY(double y) {
        return top + y * height / sourceHeight;
    }
}
