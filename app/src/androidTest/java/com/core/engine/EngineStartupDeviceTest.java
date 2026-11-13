package com.core.engine;

import android.graphics.Canvas;
import android.graphics.Color;
import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real Surface/PixelCopy tests. Synthetic buffers do not establish game compatibility. */
@RunWith(AndroidJUnit4.class)
public final class EngineStartupDeviceTest {
    private void requireIsolatedTarget() {
        assumeTrue("Dedicated startup acceptance package required",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()
                        .equals("com.tyranor.next.startuptest"));
        assumeTrue("Explicit device-test opt-in required",
                "true".equals(InstrumentationRegistry.getArguments().getString("startup_test")));
    }

    @Test public void noQueuedBufferWaitsAndBlackBufferRevealsThroughOpaqueOverlay() throws Exception {
        requireIsolatedTarget();
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            Fixture fixture = new Fixture();
            scenario.onActivity(fixture::attach);
            assertTrue("Surface was not created", fixture.surfaceCreated.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> fixture.startProbe(activity));
            assertFalse("An unqueued Surface is not ready", fixture.ready.await(400, TimeUnit.MILLISECONDS));
            saveScreenshot("loading.png");
            scenario.onActivity(activity -> fixture.queueBlackFrame());
            assertTrue("A legitimate black buffer must count as available", fixture.ready.await(8, TimeUnit.SECONDS));
            assertNull("PixelCopy failed", fixture.failure.get());
            assertEquals(1, fixture.readyCount.get());
            scenario.onActivity(activity -> {
                assertFalse(fixture.overlay.isLoading());
                fixture.dispose();
            });
        }
    }

    @Test public void stoppedProbeCannotRevealUntilResumed() throws Exception {
        requireIsolatedTarget();
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            Fixture fixture = new Fixture();
            scenario.onActivity(fixture::attach);
            assertTrue(fixture.surfaceCreated.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                fixture.startProbe(activity);
                fixture.probe.stop();
                fixture.queueBlackFrame();
            });
            assertFalse("Stopped probe delivered a ready callback", fixture.ready.await(400, TimeUnit.MILLISECONDS));
            scenario.onActivity(activity -> fixture.probe.start());
            assertTrue(fixture.ready.await(8, TimeUnit.SECONDS));
            assertNull(fixture.failure.get());
            assertEquals(1, fixture.readyCount.get());
            scenario.onActivity(activity -> fixture.dispose());
        }
    }

    @Test public void loadingConsumesTouchesAndFailureCannotBeRevealedByLateSuccess() {
        requireIsolatedTarget();
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<FrameLayout> root = new AtomicReference<>();
            AtomicReference<EngineStartupOverlay> overlay = new AtomicReference<>();
            AtomicInteger underlyingTouches = new AtomicInteger();
            scenario.onActivity(activity -> {
                FrameLayout content = new FrameLayout(activity);
                FrameLayout underlying = new FrameLayout(activity);
                underlying.setOnTouchListener((view, event) -> { underlyingTouches.incrementAndGet(); return true; });
                content.addView(underlying, new FrameLayout.LayoutParams(-1, -1));
                activity.setContentView(content);
                EngineStartupOverlay loading = new EngineStartupOverlay(activity);
                loading.attachTo(content);
                root.set(content);
                overlay.set(loading);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                dispatchTap(root.get());
                assertEquals("Loading touch reached game content", 0, underlyingTouches.get());
                overlay.get().fail("fixture failure");
                overlay.get().reveal();
            });
            // A late success must not start a fade/remove after failure.
            SystemClock.sleep(250);
            scenario.onActivity(activity -> {
                assertSame(root.get(), overlay.get().getParent());
                dispatchTap(root.get());
                assertEquals(0, underlyingTouches.get());
                overlay.get().dispose();
                overlay.get().reveal();
                assertNull(overlay.get().getParent());
                dispatchTap(root.get());
                assertTrue("Disposed overlay still blocks content", underlyingTouches.get() > 0);
            });
        }
    }

    @Test public void applicationPanelPreservesHostFocusAndDoesNotBlockHostDialog() throws Exception {
        requireIsolatedTarget();
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<EngineStartupOverlay> overlay = new AtomicReference<>();
            AtomicReference<AlertDialog> dialog = new AtomicReference<>();
            CountDownLatch confirmed = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                activity.setContentView(new FrameLayout(activity));
                EngineStartupOverlay loading = new EngineStartupOverlay(activity);
                overlay.set(loading);
                loading.attachToWindow();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertTrue("Application panel was not attached", overlay.get().isAttachedToWindow());
                assertTrue("Loading panel stole the render host's focus", activity.hasWindowFocus());
                dialog.set(new AlertDialog.Builder(activity).setTitle("Startup fixture")
                        .setPositiveButton(android.R.string.ok, (value, which) -> confirmed.countDown()).show());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            int[] center = new int[2];
            scenario.onActivity(activity -> {
                android.widget.Button button = dialog.get().getButton(AlertDialog.BUTTON_POSITIVE);
                button.getLocationOnScreen(center);
                center[0] += button.getWidth() / 2;
                center[1] += button.getHeight() / 2;
            });
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, center[0], center[1], 0);
            MotionEvent up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, center[0], center[1], 0);
            try {
                InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
                InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
            } finally { down.recycle(); up.recycle(); }
            assertTrue("Loading panel blocked the host dialog", confirmed.await(3, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                dialog.get().dismiss();
                overlay.get().dispose();
                assertFalse("Application panel leaked its window", overlay.get().isAttachedToWindow());
            });
        }
    }

    @Test public void framebufferDrawNotificationArrivesWhileStartupMaskIsOpaque() throws Exception {
        requireIsolatedTarget();
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            CountDownLatch drawn = new CountDownLatch(1);
            AtomicReference<EngineStartupOverlay> overlay = new AtomicReference<>();
            AtomicReference<com.core.games.FramebufferView> view = new AtomicReference<>();
            android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(32, 32,
                    android.graphics.Bitmap.Config.ARGB_8888);
            frame.eraseColor(Color.BLACK);
            scenario.onActivity(activity -> {
                FrameLayout root = new FrameLayout(activity);
                com.core.games.FramebufferView framebuffer = new com.core.games.FramebufferView(activity);
                root.addView(framebuffer, new FrameLayout.LayoutParams(-1, -1));
                activity.setContentView(root);
                EngineStartupOverlay loading = new EngineStartupOverlay(activity);
                loading.attachTo(root);
                overlay.set(loading);
                view.set(framebuffer);
                framebuffer.setFirstFrameDrawnListener(() -> { loading.reveal(); drawn.countDown(); });
                framebuffer.setFrame(frame);
            });
            assertTrue("Opaque loading mask prevented the framebuffer draw callback", drawn.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                assertFalse(overlay.get().isLoading());
                view.get().setFirstFrameDrawnListener(null);
                view.get().setFrame(null);
                overlay.get().dispose();
            });
            frame.recycle();
        }
    }

    private static void dispatchTap(FrameLayout root) {
        assertTrue("Test content is not laid out", root.getWidth() > 0 && root.getHeight() > 0);
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 8, 8, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 8, 8, 0);
        try { root.dispatchTouchEvent(down); root.dispatchTouchEvent(up); }
        finally { down.recycle(); up.recycle(); }
    }

    private static void saveScreenshot(String name) throws Exception {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getFilesDir(), "startup-acceptance");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull("Screenshot unavailable", screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }

    private static final class Fixture implements EngineSurfaceReadyProbe.SurfaceProvider {
        final CountDownLatch surfaceCreated = new CountDownLatch(1);
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicInteger readyCount = new AtomicInteger();
        final AtomicReference<EngineSurfaceReadyProbe.Failure> failure = new AtomicReference<>();
        SurfaceView surfaceView;
        EngineStartupOverlay overlay;
        EngineSurfaceReadyProbe probe;
        long generation;

        void attach(ComponentActivity activity) {
            FrameLayout content = new FrameLayout(activity);
            surfaceView = new SurfaceView(activity);
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(@NonNull SurfaceHolder holder) { generation++; surfaceCreated.countDown(); }
                @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) { }
                @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) { generation++; }
            });
            content.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
            activity.setContentView(content);
            overlay = new EngineStartupOverlay(activity);
            overlay.attachTo(content);
        }

        void startProbe(ComponentActivity activity) {
            probe = new EngineSurfaceReadyProbe(activity, this, new EngineSurfaceReadyProbe.Listener() {
                @Override public void onReady(long readyGeneration) {
                    assertEquals(generation, readyGeneration);
                    readyCount.incrementAndGet(); overlay.reveal(); ready.countDown();
                }
                @Override public void onFailure(EngineSurfaceReadyProbe.Failure value) { failure.set(value); ready.countDown(); }
            });
            probe.start();
        }

        void queueBlackFrame() {
            Canvas canvas = surfaceView.getHolder().lockCanvas();
            assertNotNull(canvas);
            try { canvas.drawColor(Color.BLACK); }
            finally { surfaceView.getHolder().unlockCanvasAndPost(canvas); }
        }

        @Override public Surface currentSurface() { return surfaceView.getHolder().getSurface(); }
        @Override public long currentGeneration() { return generation; }
        void dispose() { if (probe != null) probe.dispose(); overlay.dispose(); }
    }
}
