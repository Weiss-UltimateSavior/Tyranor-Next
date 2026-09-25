package com.core.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 软件渲染引擎（RealLive / AVG32 / UK2）的画面视图：
 * 把每帧 RGBA 位图按 aspect-fit（尽量整数倍、保持像素清晰）居中绘制。
 */
public final class FramebufferView extends View {

    private final Paint paint = new Paint();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    @Nullable
    private Bitmap frame;

    public FramebufferView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
    }

    public void setFrame(@Nullable Bitmap bitmap) {
        frame = bitmap;
        invalidate();
    }

    /** 当前画面在屏幕上的矩形（游戏触摸坐标换算用）。 */
    public Rect frameRect() {
        layoutFrame();
        return dst;
    }

    private void layoutFrame() {
        int vw = getWidth();
        int vh = getHeight();
        if (frame == null || vw <= 0 || vh <= 0) {
            dst.set(0, 0, vw, vh);
            return;
        }
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        src.set(0, 0, fw, fh);
        float scale = Math.min(vw / (float) fw, vh / (float) fh);
        // 接近整数倍时取整数倍，避免低分辨率像素画被插值。
        float integer = (float) Math.floor(scale);
        if (integer >= 1f && integer / scale > 0.92f) {
            scale = integer;
        }
        int w = Math.round(fw * scale);
        int h = Math.round(fh * scale);
        int x = (vw - w) / 2;
        int y = (vh - h) / 2;
        dst.set(x, y, x + w, y + h);
    }

    /** 把视图坐标换算为帧像素坐标，并裁剪到帧范围。 */
    public int[] toFrame(float x, float y) {
        if (frame == null) {
            return new int[] {0, 0};
        }
        layoutFrame();
        float fx = (x - dst.left) * frame.getWidth() / (float) Math.max(1, dst.width());
        float fy = (y - dst.top) * frame.getHeight() / (float) Math.max(1, dst.height());
        int ix = Math.max(0, Math.min(frame.getWidth() - 1, (int) fx));
        int iy = Math.max(0, Math.min(frame.getHeight() - 1, (int) fy));
        return new int[] {ix, iy};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF000000);
        if (frame == null) {
            return;
        }
        layoutFrame();
        canvas.drawBitmap(frame, src, dst, paint);
    }
}
