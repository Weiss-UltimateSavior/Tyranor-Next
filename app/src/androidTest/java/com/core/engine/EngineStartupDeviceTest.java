package com.core.engine;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

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

    @Test public void fastStartupShowsTheGameCoverAndCompletesTheSpectrum() throws Exception {
        requireIsolatedTarget();
        assumeTrue("The emulator must enable animations", ValueAnimator.areAnimatorsEnabled());
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        File coverFile = File.createTempFile("startup-cover", ".png", instrumentation.getTargetContext().getCacheDir());
        Bitmap fixtureCover = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas art = new Canvas(fixtureCover);
        art.drawColor(0xFFAD5D91);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFF5AAC4);
        art.drawCircle(70, 25, 18, paint);
        paint.setColor(0xFF382547);
        art.drawRect(0, 55, 96, 96, paint);
        try (FileOutputStream output = new FileOutputStream(coverFile)) {
            assertTrue(fixtureCover.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            fixtureCover.recycle();
        }

        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<EngineStartupOverlay> overlay = new AtomicReference<>();
            long beforeAttach = SystemClock.uptimeMillis();
            scenario.onActivity(activity -> {
                activity.getIntent()
                        .putExtra(LaunchContract.UI_LANGUAGE_TAG, "zh-CN")
                        .putExtra(LaunchContract.GAMES_TITLE, "星光庭院")
                        .putExtra(LaunchContract.GAME_COVER_URI, Uri.fromFile(coverFile).toString())
                        .putExtra(LaunchContract.THEME_COLOR_PRIMARY, 0xFF77C2CD);
                FrameLayout content = new FrameLayout(activity);
                activity.setContentView(content);
                EngineStartupOverlay loading = new EngineStartupOverlay(activity);
                loading.attachTo(content);
                loading.reveal();
                overlay.set(loading);
            });

            long coverDeadline = SystemClock.uptimeMillis() + 1_800;
            AtomicBoolean decoded = new AtomicBoolean();
            while (SystemClock.uptimeMillis() < coverDeadline) {
                scenario.onActivity(activity -> decoded.set(hasFixtureCover(overlay.get())));
                if (decoded.get()) break;
                SystemClock.sleep(30);
            }
            scenario.onActivity(activity -> {
                assertTrue("Cover was not decoded from the launch URI", hasFixtureCover(overlay.get()));
                String[] quotes = EngineUiText.localizedContext(activity).getResources()
                        .getStringArray(R.array.engine_startup_quotes);
                String displayed = findChild(overlay.get(), TextView.class).getText().toString();
                assertTrue("The loading title should be one of the localized quotes",
                        Arrays.asList(quotes).contains(displayed));
                assertEquals("Fast engine readiness should keep the startup graphic visible",
                        View.VISIBLE, overlay.get().getVisibility());
            });
            saveScreenshot("startup-spectrum.png");

            long revealDeadline = SystemClock.uptimeMillis() + 5_000;
            AtomicInteger visibility = new AtomicInteger();
            do {
                scenario.onActivity(activity -> visibility.set(overlay.get().getVisibility()));
                if (visibility.get() == View.GONE) break;
                SystemClock.sleep(30);
            } while (SystemClock.uptimeMillis() < revealDeadline);
            assertEquals("Ready content never became visible", View.GONE, visibility.get());
            assertTrue("Ready content appeared before the three-second spectrum",
                    SystemClock.uptimeMillis() - beforeAttach >= 3_000);
            scenario.onActivity(activity -> overlay.get().dispose());
        } finally {
            assertTrue(coverFile.delete());
        }
    }

    private static boolean hasFixtureCover(EngineStartupOverlay overlay) {
        Drawable drawable = findChild(overlay, ImageView.class).getDrawable();
        return drawable instanceof BitmapDrawable
                && ((BitmapDrawable) drawable).getBitmap().getPixel(0, 0) == 0xFFAD5D91;
    }

    @Test public void missingCoverUsesAppIconAndPageTurnRevealsTheHostWindow() throws Exception {
        requireIsolatedTarget();
        assumeTrue("The emulator must enable animations", ValueAnimator.areAnimatorsEnabled());
        int icon = com.tyranor.next.R.mipmap.ic_launcher_classic;
        int hostColor = 0xFF345CB4;
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<EngineStartupOverlay> overlay = new AtomicReference<>();
            AtomicReference<ValueAnimator> paused = new AtomicReference<>();
            CountDownLatch halfway = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                activity.getIntent().putExtra(LaunchContract.STARTUP_APP_ICON, icon);
                FrameLayout content = new FrameLayout(activity);
                content.setBackgroundColor(hostColor);
                activity.setContentView(content);
                EngineStartupOverlay loading = new EngineStartupOverlay(activity);
                ImageView cover = findChild(loading, ImageView.class);
                assertNotNull(cover);
                assertNotNull(cover.getDrawable());
                Bitmap expected = renderIcon(activity.getDrawable(icon));
                Bitmap actual = renderIcon(cover.getDrawable());
                try {
                    assertTrue("The fallback should display the selected app icon", expected.sameAs(actual));
                } finally {
                    expected.recycle();
                    actual.recycle();
                }
                View page = loading.getChildAt(0);
                page.animate().setUpdateListener(animation -> {
                    if (page.getRotationY() >= 55f && paused.compareAndSet(null, animation)) {
                        animation.pause();
                        halfway.countDown();
                    }
                });
                loading.attachToWindow();
                overlay.set(loading);
                loading.reveal();
            });

            assertTrue("The loading page did not turn", halfway.await(8, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Rect left = new Rect();
            Rect right = new Rect();
            scenario.onActivity(activity -> {
                EngineStartupOverlay loading = overlay.get();
                int[] location = new int[2];
                loading.getLocationOnScreen(location);
                int y = location[1] + loading.getHeight() / 2;
                int leftX = location[0] + loading.getWidth() / 12;
                int rightX = location[0] + loading.getWidth() * 11 / 12;
                left.set(leftX, y, leftX + 1, y + 1);
                right.set(rightX, y, rightX + 1, y + 1);
            });
            // Let the window compositor present the paused frame before sampling it.
            SystemClock.sleep(120);
            saveScreenshot("startup-page-turn.png");
            assertEquals("The folded page still covers the hinge", Color.BLACK, capturePixels(left)[0]);
            assertEquals("The host is visible through the turned page", hostColor, capturePixels(right)[0]);
            scenario.onActivity(activity -> paused.get().resume());

            AtomicInteger visibility = new AtomicInteger(View.VISIBLE);
            long deadline = SystemClock.uptimeMillis() + 2_000;
            do {
                scenario.onActivity(activity -> visibility.set(overlay.get().getVisibility()));
                if (visibility.get() == View.GONE) break;
                SystemClock.sleep(30);
            } while (SystemClock.uptimeMillis() < deadline);
            assertEquals("The turned page finishes hiding", View.GONE, visibility.get());
            scenario.onActivity(activity -> overlay.get().dispose());
        }
    }

    private static Bitmap renderIcon(Drawable icon) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Rect bounds = icon.copyBounds();
        icon.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        icon.draw(new Canvas(bitmap));
        icon.setBounds(bounds);
        return bitmap;
    }

    @Test public void slowStartupContinuesDrawingAfterTheFirstCycle() {
        requireIsolatedTarget();
        assumeTrue("The emulator must enable animations", ValueAnimator.areAnimatorsEnabled());
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<EngineStartupOverlay> overlay = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FrameLayout content = new FrameLayout(activity);
                activity.setContentView(content);
                EngineStartupOverlay loading = new EngineStartupOverlay(activity);
                loading.attachTo(content);
                overlay.set(loading);
            });
            AtomicLong firstCycleEnd = new AtomicLong(-1);
            long drawDeadline = SystemClock.uptimeMillis() + 5_000;
            do {
                scenario.onActivity(activity -> {
                    StartupSpectrumView spectrum = findChild(overlay.get(), StartupSpectrumView.class);
                    firstCycleEnd.set(spectrum.firstCycleEndsAt());
                });
                if (firstCycleEnd.get() > 0) break;
                SystemClock.sleep(30);
            } while (SystemClock.uptimeMillis() < drawDeadline);
            assertTrue("The startup spectrum never drew", firstCycleEnd.get() > 0);
            SystemClock.sleep(Math.max(0, firstCycleEnd.get() + 100 - SystemClock.uptimeMillis()));

            Rect bounds = new Rect();
            scenario.onActivity(activity -> {
                StartupSpectrumView spectrum = findChild(overlay.get(), StartupSpectrumView.class);
                int[] location = new int[2];
                spectrum.getLocationOnScreen(location);
                bounds.set(location[0], location[1], location[0] + spectrum.getWidth(),
                        location[1] + spectrum.getHeight());
                assertTrue(overlay.get().isLoading());
            });
            int[] firstFrame = capturePixels(bounds);
            SystemClock.sleep(200);
            assertFalse("The spectrum stopped drawing after the first cycle",
                    Arrays.equals(firstFrame, capturePixels(bounds)));

            scenario.onActivity(activity -> overlay.get().reveal());
            AtomicInteger visibility = new AtomicInteger(View.VISIBLE);
            long revealDeadline = SystemClock.uptimeMillis() + 2_000;
            do {
                scenario.onActivity(activity -> visibility.set(overlay.get().getVisibility()));
                if (visibility.get() == View.GONE) break;
                SystemClock.sleep(30);
            } while (SystemClock.uptimeMillis() < revealDeadline);
            assertEquals("Ready content should reveal during a later cycle", View.GONE, visibility.get());
            scenario.onActivity(activity -> overlay.get().dispose());
        }
    }

    private static int[] capturePixels(Rect bounds) {
        Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Screenshot unavailable", screen);
        try {
            int[] pixels = new int[bounds.width() * bounds.height()];
            screen.getPixels(pixels, 0, bounds.width(), bounds.left, bounds.top, bounds.width(), bounds.height());
            return pixels;
        } finally {
            screen.recycle();
        }
    }

    private static <T extends View> T findChild(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int index = 0; index < parent.getChildCount(); index++) {
                T found = findChild(parent.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
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
