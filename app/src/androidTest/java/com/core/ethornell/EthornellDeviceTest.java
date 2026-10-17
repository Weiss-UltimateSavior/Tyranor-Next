package com.core.ethornell;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;

/** Device-only real JNI/VM/GPU checks; contains no fake Backend or production backdoor. */
@RunWith(AndroidJUnit4.class)
public final class EthornellDeviceTest {
    static final String TAG = "EthornellHarness";
    private static native String probe(String absoluteLibrary);
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext(); }
    private static Bundle args() { return InstrumentationRegistry.getArguments(); }
    private static int encoding() {
        String selected = args().getString("encoding", "sjis");
        assertTrue("encoding must be sjis or gbk", selected.equals("sjis") || selected.equals("gbk"));
        return selected.equals("gbk") ? 1 : 0;
    }

    @Test public void loadAbiBuildInfoAndRejectInvalidTokens() throws Exception {
        bounded(() -> {
            System.loadLibrary("ethornell_test_probe");
            String report = probe(new File(app().getApplicationInfo().nativeLibraryDir, "libethornell.so").getPath());
            Log.i(TAG, "LOAD " + report + " sdk=" + Build.VERSION.SDK_INT + " abi=" + Build.SUPPORTED_ABIS[0]
                    + " page_size=" + Os.sysconf(OsConstants._SC_PAGESIZE));
            assertTrue(report, report.startsWith("OK RTLD_NOW"));
            assertTrue(report, report.contains("build_info=ethornell"));
            NativeEthornell.load();
            for (long bad : new long[]{0, -1, Long.MAX_VALUE}) {
                rejected("invalid token step", () -> NativeEthornell.step(bad, System.nanoTime()));
                rejected("invalid token pause", () -> NativeEthornell.setPaused(bad, 0));
                rejected("invalid token poll", () -> NativeEthornell.pollEvent(bad));
                rejected("invalid token close", () -> NativeEthornell.requestClose(bad));
                NativeEthornell.destroy(bad); // production destroy explicitly permits unknown/idempotent tokens
            }
        });
    }

    @Test public void supplementaryUtf8PathsAndWrongThreadAreSafe() throws Exception {
        bounded(() -> {
            try (Fixture fixture = new Fixture(false)) {
                NativeEthornell.load();
                for (String bad : new String[]{"bad\0path", "bad\uD800", "bad\uDC00"}) {
                    rejected("invalid UTF-16 resource", () -> NativeEthornell.create(app(), bad, fixture.user.getPath(), "", encoding()));
                    rejected("invalid UTF-16 user", () -> NativeEthornell.create(app(), fixture.resource.getPath(), bad, "", encoding()));
                    rejected("invalid UTF-16 font", () -> NativeEthornell.create(app(), fixture.resource.getPath(), fixture.user.getPath(), bad, encoding()));
                }
                long handle = create(fixture);
                try {
                    AtomicReference<Throwable> wrong = new AtomicReference<>();
                    Thread thread = new Thread(() -> {
                        try { NativeEthornell.step(handle, System.nanoTime()); }
                        catch (Throwable error) { wrong.set(error); }
                    }, "Ethornell-intentionally-wrong-owner");
                    thread.start(); thread.join(3000);
                    assertFalse("wrong-thread call blocked", thread.isAlive());
                    assertTrue("wrong-thread must be IllegalStateException, not crash/no-op: " + wrong.get(),
                            wrong.get() instanceof IllegalStateException);
                    assertTrue(wrong.get().getMessage().contains("handle/thread"));
                    // A rejected caller must not poison the owner token.
                    NativeEthornell.setPaused(handle, 1);
                    NativeEthornell.setPaused(handle, 0);
                    poll(handle, new Events(), true);
                } finally { destroyAcknowledged(handle); }
                rejected("destroyed token", () -> NativeEthornell.step(handle, System.nanoTime()));
                NativeEthornell.destroy(handle);
                fixture.assertResourceUnchanged();
                Log.i(TAG, "PASS UTF8 non-BMP resource/user roots opened; malformed UTF16 and wrong-thread rejected");
            }
        });
    }

    @Test public void gpuPresentDetachReattachAndGeneration() throws Exception {
        bounded(() -> {
            try (Fixture fixture = new Fixture("render")) {
                long handle = create(fixture);
                // Close/destroy must ACK before releasing either borrowed producer Surface.
                GpuSurfaceConsumer first = null, second = null;
                try {
                    first = new GpuSurfaceConsumer(1280, 720, true);
                    second = new GpuSurfaceConsumer(1280, 720, true);
                    Events events = new Events();
                    NativeEthornell.setSurface(handle, first.surface, first.width, first.height, 1);
                    NativeEthornell.setPaused(handle, 0);
                    awaitFrames(handle, first, first.frames.get() + 2, events);
                    awaitRenderPixels(handle, first, events, "generation=1");
                    NativeEthornell.detachSurface(handle, 1);
                    // Drain a buffer queued immediately before detach, then freeze the baseline.
                    SystemClock.sleep(50);
                    int detachedFrames = first.frames.get();
                    for (int i = 0; i < 5; i++) { NativeEthornell.step(handle, System.nanoTime()); SystemClock.sleep(20); }
                    assertEquals("detached Surface still presenting", detachedFrames, first.frames.get());
                    Surface oldSurface = first.surface;
                    rejected("stale generation", () -> NativeEthornell.setSurface(handle, oldSurface, 1280, 720, 1));
                    NativeEthornell.setSurface(handle, second.surface, second.width, second.height, 2);
                    NativeEthornell.detachSurface(handle, 1); // stale detach must not detach generation 2
                    awaitFrames(handle, second, second.frames.get() + 2, events);
                    awaitRenderPixels(handle, second, events, "generation=2 after stale detach");
                    rejected("old generation after reattach", () -> NativeEthornell.setSurface(handle, oldSurface, 1280, 720, 1));
                    awaitFrames(handle, second, second.frames.get() + 2, events);
                    awaitRenderPixels(handle, second, events, "generation=2 after rejected old attach");
                    assertTrue("real producer timestamp missing", second.timestamp > 0);
                    NativeEthornell.detachSurface(handle, 2);
                    Log.i(TAG, "PASS GPU buffer consumed first=" + first.frames.get() + " reattached=" + second.frames.get()
                            + " timestamp=" + second.timestamp + " stale detach preserved generation=2");
                } finally {
                    destroyAcknowledged(handle);
                    try { if (second != null) second.close(); }
                    finally { if (first != null) first.close(); }
                }
                fixture.assertResourceUnchanged();
            }
        });
    }

    @Test public void scriptExecutesNativeCloseAndProducesClosed() throws Exception {
        bounded(() -> {
            try (Fixture fixture = new Fixture(true)) {
                long handle = create(fixture);
                GpuSurfaceConsumer consumer = null;
                try {
                    consumer = new GpuSurfaceConsumer();
                    Events events = new Events();
                    poll(handle, events, true);
                    assertFalse("Closed before any VM step", events.closed);
                    NativeEthornell.setSurface(handle, consumer.surface, GpuSurfaceConsumer.WIDTH, GpuSurfaceConsumer.HEIGHT, 1);
                    NativeEthornell.setPaused(handle, 0);
                    long deadline = SystemClock.elapsedRealtime() + 15000;
                    while (!events.closed && SystemClock.elapsedRealtime() < deadline) {
                        NativeEthornell.step(handle, System.nanoTime());
                        poll(handle, events, true);
                        consumer.assertHealthy();
                        if (!events.closed) SystemClock.sleep(20);
                    }
                    assertTrue("No script-generated Closed; not VM execution evidence: " + events.describe(), events.closed);
                    // No Java requestClose call here: only bytecode can cause this Closed event.
                    Log.i(TAG, "PASS VM Sys80:68(1)->Sys80:69->ret Closed=" + events.describe());
                } finally {
                    destroyAcknowledged(handle);
                    if (consumer != null) consumer.close();
                }
                fixture.assertResourceUnchanged();
            }
        });
    }

    /** Original VM serialization/reopen proof, never writes a real game's user root. */
    @Test public void vmGlobalUserDataPersistsAcrossNewSessionsAndRejectsEmptyRoot() throws Exception {
        bounded(() -> {
            try (Fixture writer = new Fixture("user-data-write"); Fixture reader = new Fixture("user-data-read")) {
                File save = new File(writer.user, "BGI.gdb");
                assertArrayEquals("Writer requires fresh dedicated user root", new String[0], writer.user.list());
                assertFalse(save.exists());
                long writerHandle = runUserDataPhase(writer.resource, writer.user, "ETHORNELL_USER_DATA_WRITE_OK");
                // runUserDataPhase returns only after production destroy ACK, releasing writer lock.
                assertTrue("VM did not create BGI.gdb in user root", save.isFile() && save.length() > 32);
                assertTrue("Serialized save exceeds test budget", save.length() <= 8 * 1024 * 1024);
                byte[] persisted = Files.readAllBytes(save.toPath());
                byte[] sdcMagic = "SDC FORMAT 1.00\0".getBytes(StandardCharsets.US_ASCII);
                assertArrayEquals("Not VM SDC serialization", sdcMagic, java.util.Arrays.copyOf(persisted, sdcMagic.length));
                writer.assertResourceUnchanged(); reader.assertResourceUnchanged();
                assertFalse(new File(writer.resource, "BGI.gdb").exists());
                assertFalse(new File(reader.resource, "BGI.gdb").exists());
                long readerHandle = runUserDataPhase(reader.resource, writer.user, "ETHORNELL_USER_DATA_READ_OK");
                assertNotEquals("Reader must use a new JNI/native session", writerHandle, readerHandle);
                assertArrayEquals("Read phase modified persisted save", persisted, Files.readAllBytes(save.toPath()));
                assertArrayEquals("Negative control must start empty", new String[0], reader.user.list());
                long negativeHandle = runUserDataPhase(reader.resource, reader.user, "ETHORNELL_USER_DATA_READ_LOAD_FAILED");
                assertNotEquals(readerHandle, negativeHandle);
                assertFalse("Reader must not generate/repair missing save", new File(reader.user, "BGI.gdb").exists());
                assertArrayEquals("Negative control altered positive save", persisted, Files.readAllBytes(save.toPath()));
                writer.assertResourceUnchanged(); reader.assertResourceUnchanged();
                Log.i(TAG, "PASS VM_USER_DATA writer/new-reader/empty-negative exact Title->Closed; bytes="
                        + persisted.length + " sha256=" + sha256(persisted)
                        + " (synthetic global user-data only; no real-game slot/PC compatibility claim)");
            }
        });
    }

    private static long runUserDataPhase(File resource, File user, String expectedTitle) throws Exception {
        File r = resource.getCanonicalFile(), u = user.getCanonicalFile();
        assertFalse("Resource/user overlap", r.toPath().startsWith(u.toPath()) || u.toPath().startsWith(r.toPath()));
        NativeEthornell.load();
        long handle = NativeEthornell.create(app(), r.getPath(), u.getPath(), "", encoding());
        assertNotEquals("User-data phase create failed", 0, handle);
        GpuSurfaceConsumer consumer = null;
        boolean title = false, closed = false;
        List<String> observed = new ArrayList<>();
        try {
            consumer = new GpuSurfaceConsumer();
            NativeEthornell.setSurface(handle, consumer.surface, consumer.width, consumer.height, 1);
            NativeEthornell.setPaused(handle, 0);
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (!closed && SystemClock.elapsedRealtime() < deadline) {
                NativeEthornell.step(handle, System.nanoTime());
                for (int i = 0; i < 128; i++) {
                    EthornellSession.Event event = NativeEthornell.pollEvent(handle);
                    if (event == null) break;
                    String text = "kind=" + event.kind + " code=" + event.code + " text=" + event.text;
                    observed.add(text); Log.i(TAG, "USER_DATA " + text);
                    assertFalse("Fatal user-data diagnostic: " + observed, event.kind == 1 && event.code < 0);
                    assertNotEquals("Unexpected confirmation; do not auto-accept serialization errors", 2, event.kind);
                    if (event.kind == 4) {
                        assertFalse("Repeated Title event", title);
                        assertEquals("Wrong user-data phase result: " + observed, expectedTitle, event.text);
                        assertEquals(0, event.code); title = true;
                    }
                    if (event.kind == 3) {
                        assertTrue("Closed without exact phase Title: " + observed, title);
                        assertEquals(0, event.code); closed = true;
                    }
                    if (i == 127) fail("User-data event queue exceeded budget");
                }
                consumer.assertHealthy();
                if (!closed) SystemClock.sleep(20);
            }
            assertTrue("Missing Title->Closed for " + expectedTitle + ": " + observed, title && closed);
            return handle;
        } finally {
            destroyAcknowledged(handle);
            if (consumer != null) consumer.close();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder text = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data))
            text.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    /** Optional legal game input. User-selected game is never copied, rewritten, or removed. */
    @Test public void suppliedGameDirectRuntime() throws Exception {
        String path = args().getString("game_path");
        assumeTrue("Optional: supply -e game_path /absolute/readable/game", path != null && !path.isEmpty());
        bounded(() -> {
            File root = new File(path).getCanonicalFile();
            assertTrue("game_path must be readable directory", root.isAbsolute() && root.isDirectory() && root.canRead());
            File user = new File(app().getFilesDir(), "ethornell-device-game-" + UUID.randomUUID());
            assertTrue(user.mkdirs());
            NativeEthornell.load();
            long handle = 0;
            GpuSurfaceConsumer consumer = null;
            try {
                handle = NativeEthornell.create(app(), root.getPath(), user.getPath(), "", encoding());
                assertNotEquals("create returned zero", 0, handle);
                consumer = new GpuSurfaceConsumer();
                Events events = new Events();
                NativeEthornell.setSurface(handle, consumer.surface, GpuSurfaceConsumer.WIDTH, GpuSurfaceConsumer.HEIGHT, 1);
                NativeEthornell.setPaused(handle, 0);
                awaitFrames(handle, consumer, 3, events);
                Log.i(TAG, "GAME real buffers=" + consumer.frames.get() + " events=" + events.describe()
                        + " encoding=" + args().getString("encoding", "sjis") + " (not playability/text/audio/save acceptance)");
                NativeEthornell.requestClose(handle);
                poll(handle, events, true); // WM_CLOSE may be intercepted; never pretend Closed.
                Log.i(TAG, "GAME host requestClose observed closed=" + events.closed);
            } finally {
                if (handle != 0) destroyAcknowledged(handle);
                if (consumer != null) consumer.close();
                // Keep test user data for investigation; do not remove game or user saves.
                Log.i(TAG, "GAME isolated user data retained at " + user);
            }
        });
    }

    private static long create(Fixture fixture) {
        NativeEthornell.load();
        try { fixture.assertRootsAccessibleAndDisjoint(); }
        catch (Exception error) { throw new AssertionError("Fixture root preflight failed", error); }
        long handle = NativeEthornell.create(app(), fixture.resource.getPath(), fixture.user.getPath(), "", encoding());
        assertNotEquals("real JNI create failed", 0, handle);
        return handle;
    }

    private static void awaitFrames(long handle, GpuSurfaceConsumer consumer, int minimum, Events events) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (consumer.frames.get() < minimum && SystemClock.elapsedRealtime() < deadline) {
            NativeEthornell.step(handle, System.nanoTime());
            poll(handle, events, true);
            consumer.assertHealthy();
            if (consumer.frames.get() < minimum) SystemClock.sleep(20);
        }
        assertTrue("No actual GPU buffers; events=" + events.describe(), consumer.frames.get() >= minimum);
    }

    private static final int[][] RENDER_PROBES = {{576, 296}, {704, 296}, {576, 424}, {704, 424}, {128, 128}};
    private static final int[][] RENDER_RGBA = {{255, 0, 0, 255}, {0, 255, 0, 255}, {0, 0, 255, 255},
            {255, 255, 255, 255}, {0, 0, 0, 255}};

    private static boolean renderPixelsMatch(GpuSurfaceConsumer consumer) {
        byte[] image = consumer.snapshot();
        if (image == null) return false;
        for (int probe = 0; probe < RENDER_PROBES.length; probe++) {
            // All nine pixels of each 3x3 patch must match, not only an average hiding corruption.
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int[] actual = consumer.pixel(image, RENDER_PROBES[probe][0] + dx, RENDER_PROBES[probe][1] + dy);
                for (int c = 0; c < 4; c++) if (Math.abs(actual[c] - RENDER_RGBA[probe][c]) > 3) return false;
            }
        }
        return true;
    }

    private static void awaitRenderPixels(long handle, GpuSurfaceConsumer consumer, Events events, String stage) {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (!renderPixelsMatch(consumer) && SystemClock.elapsedRealtime() < deadline) {
            NativeEthornell.step(handle, System.nanoTime());
            poll(handle, events, true);
            consumer.assertHealthy();
            if (!renderPixelsMatch(consumer)) SystemClock.sleep(20);
        }
        assertTrue("Real VM/render fixture pixel mismatch " + stage + ": "
                + consumer.probeDescription(RENDER_PROBES) + " events=" + events.describe(), renderPixelsMatch(consumer));
        Log.i(TAG, "PASS PIXELS " + stage + " tolerance=3 patch=3x3 " + consumer.probeDescription(RENDER_PROBES));
    }

    private static final class Events {
        boolean closed;
        final Set<Long> requests = new HashSet<>();
        final List<String> values = new ArrayList<>();
        String describe() { return values.toString(); }
    }

    private static void poll(long handle, Events events, boolean respond) {
        for (int i = 0; i < 128; i++) {
            EthornellSession.Event event = NativeEthornell.pollEvent(handle);
            if (event == null) return;
            String value = "kind=" + event.kind + " code=" + event.code + " id=" + event.requestId + " text=" + event.text;
            Log.i(TAG, "EVENT " + value);
            events.values.add(value);
            assertFalse("Runtime fatal diagnostic: " + value, event.kind == 1 && event.code < 0);
            if (event.kind == 3) events.closed = true;
            if (event.kind == 2 && respond) {
                assertTrue("invalid/replayed request: " + value, event.requestId != 0 && events.requests.add(event.requestId));
                NativeEthornell.completeRequest(handle, event.requestId, 1);
            }
        }
        fail("Event queue exceeded bounded poll budget");
    }

    private static void rejected(String label, Runnable call) {
        try { call.run(); fail(label + " was accepted"); }
        catch (IllegalStateException expected) { Log.i(TAG, "REJECT " + label + ": " + expected.getMessage()); }
    }

    private interface Checked { void run() throws Exception; }

    private static void destroyAcknowledged(long handle) {
        try { NativeEthornell.destroy(handle); }
        catch (RuntimeException | LinkageError failure) {
            // Retained ANativeWindow is still borrowed on missing ACK. Do not release the
            // consumer and carry a broken session into another test in this process.
            Log.e(TAG, "FAIL destroy did not ACK; terminating instrumentation process", failure);
            Process.killProcess(Process.myPid());
            throw failure;
        }
    }

    private static void bounded(Checked action) throws Exception {
        // JNI/GPU calls cannot be safely interrupted. A stuck native call must not outlive its
        // borrowed Surface; on hard deadline terminate ONLY this instrumentation process.
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Ethornell-test-hard-deadline"); t.setDaemon(true); return t;
        });
        ScheduledFuture<?> deadline = watchdog.schedule(() -> {
            Log.e(TAG, "FAIL native harness exceeded 70s; terminating instrumented pid=" + Process.myPid());
            Process.killProcess(Process.myPid());
        }, 70, TimeUnit.SECONDS);
        try { action.run(); }
        finally { deadline.cancel(false); watchdog.shutdownNow(); }
    }

    /** Original single-entry PackFile/BP bytes; no third-party script/font/image data. */
    static final class Fixture implements AutoCloseable {
        final File base, resource, user, archive;
        final byte[] expected;
        Fixture(boolean close) throws Exception { this(close ? "close" : "ret"); }
        Fixture(String profile) throws Exception {
            // /data/user/0 and /data/data can be legitimate aliases on Android images.
            // Bind this direct-JNI fixture to the canonical app root, not its framework spelling.
            base = new File(app().getFilesDir().getCanonicalFile(), "ethornell-fixture-" + UUID.randomUUID());
            resource = new File(base, "resource-\uD83D\uDE80");
            user = new File(base, "user-\uD834\uDD1E");
            assertTrue(resource.mkdirs()); assertTrue(user.mkdir());
            assertRootsAccessibleAndDisjoint();
            archive = new File(resource, "system.arc");
            if (profile.equals("render") || profile.equals("user-data-write") || profile.equals("user-data-read")) {
                Context tests = InstrumentationRegistry.getInstrumentation().getContext();
                String directory = profile.equals("render") ? "ethornell-render" : "ethornell-user-data/" + profile.substring("user-data-".length());
                String expectedHash = profile.equals("render") ? "f0d1d96ef30135c95bc722414a9d9572ec3f34952fb57ad9a1bfcf6adb8c06d8"
                        : profile.equals("user-data-write") ? "0a3455d3b5165d64d59a8f16d7f805983c626dc2af418633096d1f7d18d22a26"
                        : "75c558c5fe797fb522d6d0baadeff0e9471fd13f67bc612e674dc8b8831f4ab0";
                byte[] manifest;
                try (InputStream input = tests.getAssets().open(directory + "/fixture.json")) { manifest = readSmallAsset(input); }
                try (InputStream input = tests.getAssets().open(directory + "/system.arc")) { expected = readSmallAsset(input); }
                JSONObject metadata = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
                assertEquals(profile, metadata.getString("profile"));
                String digest = sha256(expected);
                assertEquals("Pinned original archive " + profile, expectedHash, digest);
                assertEquals(metadata.getJSONObject("files").getJSONObject("system.arc").getString("sha256"), digest);
                assertEquals(metadata.getJSONObject("files").getJSONObject("system.arc").getInt("size"), expected.length);
                Files.write(archive.toPath(), expected);
                return;
            }
            assertTrue("Unknown fixture profile", profile.equals("ret") || profile.equals("close"));
            // push_byte(1); Sys80:68 SetNativeCloseMode (1 -> 0);
            // Sys80:69 RequestWindowClose (0 -> 0); ret. See fixture README.
            byte[] code = profile.equals("close") ? new byte[]{0, 1, (byte) 0x80, 0x68, (byte) 0x80, 0x69, 0x17} : new byte[]{0x17};
            ByteBuffer bp = ByteBuffer.allocate(16 + code.length).order(ByteOrder.LITTLE_ENDIAN);
            bp.putInt(16).putInt(code.length).putInt(0).putInt(0).put(code);
            ByteBuffer pack = ByteBuffer.allocate(48 + bp.capacity()).order(ByteOrder.LITTLE_ENDIAN);
            pack.put("PackFile    ".getBytes(StandardCharsets.US_ASCII)).putInt(1);
            byte[] name = new byte[16];
            System.arraycopy("ipl._bp".getBytes(StandardCharsets.US_ASCII), 0, name, 0, 7);
            pack.put(name).putInt(0).putInt(bp.capacity()).putInt(0).putInt(0).put(bp.array());
            expected = pack.array();
            Files.write(archive.toPath(), expected);
            assertEquals(1, resource.list().length);
        }
        private static byte[] readSmallAsset(InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                assertTrue("Fixture asset exceeds 64KB", output.size() + length <= 65536);
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
        void assertRootsAccessibleAndDisjoint() throws Exception {
            File r = resource.getCanonicalFile(), u = user.getCanonicalFile();
            assertFalse("user is resource or descendant", u.toPath().startsWith(r.toPath()));
            assertFalse("resource is user or descendant", r.toPath().startsWith(u.toPath()));
            assertTrue("resource is not private", r.toPath().startsWith(app().getFilesDir().getCanonicalFile().toPath()));
            assertTrue("user is not private", u.toPath().startsWith(app().getFilesDir().getCanonicalFile().toPath()));
            assertTrue("resource unreadable", r.isDirectory() && r.canRead());
            assertTrue("user inaccessible", u.isDirectory() && u.canRead() && u.canWrite());
            // Verify the app can actually write here, without assuming canWrite proves the IO.
            File probe = File.createTempFile("fixture-user-preflight-", ".tmp", u);
            assertTrue("user write probe cleanup failed", probe.delete());
            Log.i(TAG, "ROOT_PREFLIGHT disjoint=true siblings=" + r.getParentFile().equals(u.getParentFile())
                    + " resource=" + r + " user=" + u + " (no root/ancestor permission requirement)");
        }
        void assertResourceUnchanged() throws Exception {
            assertArrayEquals("resource root was modified", expected, Files.readAllBytes(archive.toPath()));
            assertArrayEquals("writes leaked to resource root", new String[]{"system.arc"}, resource.list());
        }
        @Override public void close() throws Exception {
            // Deliberately retain tiny per-run data on failure/success. No recursive deletion,
            // no chance to traverse game_path or user-supplied symlinks during cleanup.
            Log.i(TAG, "FIXTURE retained at " + base);
        }
    }
}
