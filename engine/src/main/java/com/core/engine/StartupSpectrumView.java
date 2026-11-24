package com.core.engine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Decorative spectrum for the startup overlay. It needs no samples from the game audio. */
@SuppressLint("ViewConstructor") // Constructed by the overlay with its launch palette and animation clock.
final class StartupSpectrumView extends View {
    private static final long CYCLE_DURATION_MS = 3_000;
    private static final int BAR_COUNT = 64;
    private static final long FRAME_INTERVAL_MS = 32;
    private static final float TWO_PI = (float) (2 * Math.PI);

    private final Paint paint = new Paint();
    private final float[] envelope = new float[BAR_COUNT];
    private final Runnable onFirstDraw;
    private boolean running;
    private long startedAt = -1;
    private float phase;

    StartupSpectrumView(Context context, int color, boolean animated, Runnable onFirstDraw) {
        super(context);
        running = animated;
        this.onFirstDraw = onFirstDraw;
        paint.setColor(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int index = 0; index < BAR_COUNT; index++) {
            float position = index / (float) (BAR_COUNT - 1);
            envelope[index] = 0.06f + 0.41f * peak(position, 0.16f, 0.029f)
                    + 0.87f * peak(position, 0.25f, 0.028f) + 0.24f * peak(position, 0.34f, 0.025f)
                    + 0.47f * peak(position, 0.49f, 0.031f) + 0.34f * peak(position, 0.60f, 0.068f)
                    + 0.24f * peak(position, 0.73f, 0.040f);
        }
    }

    long firstCycleEndsAt() {
        return startedAt < 0 ? -1 : startedAt + CYCLE_DURATION_MS;
    }

    void stop() {
        running = false;
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (running && visibility == VISIBLE) invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean update = running && isShown() && getWindowVisibility() == VISIBLE;
        if (update) {
            long now = SystemClock.uptimeMillis();
            if (startedAt < 0) {
                startedAt = now;
                onFirstDraw.run();
            }
            phase = TWO_PI * ((now - startedAt) % CYCLE_DURATION_MS) / CYCLE_DURATION_MS;
        }

        float spacing = getWidth() / (float) BAR_COUNT;
        float bottom = getHeight();
        for (int index = 0; index < BAR_COUNT; index++) {
            // Integer frequencies keep bar heights continuous across the loop boundary.
            float variation = 0.83f + 0.22f * (float) Math.sin(phase * 8f + index * 0.79f)
                    + 0.17f * (float) Math.sin(phase * 5f - index * 0.34f);
            float height = Math.max(2f, bottom * Math.min(1f, envelope[index] * variation));
            float left = spacing * index;
            canvas.drawRect(left, bottom - height, left + spacing * 0.76f, bottom, paint);
        }
        if (update) postInvalidateDelayed(FRAME_INTERVAL_MS);
    }

    private static float peak(float position, float center, float width) {
        float offset = (position - center) / width;
        return (float) Math.exp(-offset * offset / 2f);
    }
}
