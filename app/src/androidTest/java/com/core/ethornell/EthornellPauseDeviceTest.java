package com.core.ethornell;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real JNI/VM/GPU pause checks, with original generated guest bytecode and fresh private roots.
 * No UiAutomation, fake backend, commercial game, production endpoint or silent-mode override.
 * Successful create exercises the real Kira/CPAL backend initialization; API ACKs are NOT an
 * audio-thread packet ACK, audible BGM, focus callback, Bluetooth route or Activity lifecycle proof.
 */
@RunWith(AndroidJUnit4.class)
public final class EthornellPauseDeviceTest {
    private static final String TAG = "EthornellPauseTest";
    // C ABI complete-mask bits, not Android audio-focus constants or incremental operations.
    private static final int ACTIVITY = 1, FOCUS = 2;
    private static final long TICK = 16_000_000L, FRACTIONAL_TICK = 16_666_667L;
    private static final int KEY_A = 0x41, MOUSE_LEFT = 1;
    private static final int[][] PROBES = {{576, 296}, {704, 296}, {576, 424}, {704, 424}, {128, 128}};
    private static final int[][] COLORS = {{255, 0, 0, 255}, {0, 255, 0, 255}, {0, 0, 255, 255},
            {255, 255, 255, 255}, {0, 0, 0, 255}};

    @Test public void noSurfaceAndIndependentPauseReasonsFreezeVmAndGpu() throws Exception {
        bounded(() -> {
            try (Fixture fixture = new Fixture(new int[]{16, 32, 48}, new int[]{0, 0, 0})) {
                long handle = create(fixture);
                GpuSurfaceConsumer gpu = null;
                try {
                    Observations observed = new Observations();
                    assertRealAudioInitialization(handle, observed);
                    NativeEthornell.setPaused(handle, 0);
                    long now = 1_000_000_000L;
                    // Clearing every explicit reason must NOT run a VM without a renderer.
                    for (int i = 0; i < 8; i++) {
                        NativeEthornell.step(handle, now += TICK);
                        observed.drain(handle);
                    }
                    assertEquals("VM ran before first Surface", 0, observed.stages);
                    gpu = new GpuSurfaceConsumer(1280, 720, true);
                    NativeEthornell.setSurface(handle, gpu.surface, gpu.width, gpu.height, 1);
                    NativeEthornell.step(handle, now); // first active call establishes a fresh baseline
                    observed.drain(handle);
                    assertEquals(0, observed.stages);
                    advance(handle, gpu, observed, now += TICK, 1);

                    NativeEthornell.setPaused(handle, ACTIVITY | FOCUS);
                    int frozen = settle(gpu);
                    now = assertFrozen(handle, gpu, observed, now, frozen, "Activity + focus");
                    NativeEthornell.setPaused(handle, FOCUS); // clear Activity only
                    now = assertFrozen(handle, gpu, observed, now, frozen, "focus remains");
                    NativeEthornell.setPaused(handle, ACTIVITY | FOCUS);
                    NativeEthornell.setPaused(handle, ACTIVITY); // clear focus only
                    now = assertFrozen(handle, gpu, observed, now, frozen, "Activity remains");

                    NativeEthornell.setPaused(handle, 0);
                    NativeEthornell.step(handle, now); // excluded paused wall time, no catch-up
                    observed.drain(handle);
                    assertEquals("Resume baseline executed guest", 1, observed.stages);
                    NativeEthornell.step(handle, now + 8_000_000L);
                    observed.drain(handle);
                    SystemClock.sleep(80);
                    assertEquals("Sub-tick resume presented", frozen, gpu.frames.get());
                    assertEquals("Sub-tick resume executed guest", 1, observed.stages);
                    advance(handle, gpu, observed, now += TICK, 2);
                    advance(handle, gpu, observed, now + TICK, 3);
                    fixture.assertUnchanged();
                    Log.i(TAG, "PASS noSurface + complete pause-mask independence + same-Surface pixels; "
                            + "VM ticks=16,32,48; no audio audibility/packet-ACK claim");
                } finally {
                    destroyAcknowledged(handle); // producer borrow ends before consumer release
                    if (gpu != null) gpu.close();
                }
            }
        });
    }

    @Test public void fractionalMonotonicCarryAndCancelAreGuestObserved() throws Exception {
        bounded(() -> {
            // 3 * 16,666,667ns => 50ms, NOT 48ms from independently truncating each frame.
            // Pause resets the remaining 2/3ms after tick 116, hence the next tick is 132.
            int[] ticks = {16, 33, 50, 66, 83, 100, 116, 132, 149, 166, 182};
            int[] held = {0, 0, 0, 1, 0, 1, 1, 0, 0, 1, 0};
            try (Fixture fixture = new Fixture(ticks, held)) {
                long handle = create(fixture);
                GpuSurfaceConsumer gpu = null;
                try {
                    Observations observed = new Observations();
                    assertRealAudioInitialization(handle, observed);
                    gpu = new GpuSurfaceConsumer(1280, 720, true);
                    NativeEthornell.setSurface(handle, gpu.surface, gpu.width, gpu.height, 1);
                    NativeEthornell.setPaused(handle, 0);
                    long now = 10_000_000_000L;
                    NativeEthornell.step(handle, now);
                    for (int stage = 1; stage <= 3; stage++)
                        advance(handle, gpu, observed, now += FRACTIONAL_TICK, stage);
                    down(handle);
                    advance(handle, gpu, observed, now += FRACTIONAL_TICK, 4);
                    cancel(handle); // no UP: only real CANCEL can clear the observed held state
                    advance(handle, gpu, observed, now += FRACTIONAL_TICK, 5);
                    down(handle);
                    advance(handle, gpu, observed, now += FRACTIONAL_TICK, 6);
                    advance(handle, gpu, observed, now += FRACTIONAL_TICK, 7); // genuinely held across ticks

                    NativeEthornell.setPaused(handle, ACTIVITY | FOCUS);
                    down(handle); // paused presses must not be delivered after resume; no later CANCEL/UP
                    int frozen = settle(gpu);
                    now = assertFrozen(handle, gpu, observed, now, frozen, "fractional carry pause");
                    NativeEthornell.setPaused(handle, 0);
                    NativeEthornell.step(handle, now);
                    advance(handle, gpu, observed, now += FRACTIONAL_TICK, 8);
                    down(handle);
                    cancel(handle); // clear queued DOWNs before runtime_frontend drains them
                    advance(handle, gpu, observed, now += FRACTIONAL_TICK, 9);
                    down(handle);
                    advance(handle, gpu, observed, now += FRACTIONAL_TICK, 10);
                    NativeEthornell.input(handle, 3, MOUSE_LEFT, 640, 360, 0);
                    NativeEthornell.input(handle, 5, KEY_A, 0, 0, 0);
                    advance(handle, gpu, observed, now + FRACTIONAL_TICK, 11);
                    fixture.assertUnchanged();
                    Log.i(TAG, "PASS guest Sys80:04 carry ticks=" + Arrays.toString(ticks)
                            + "; Sys80:11 mouse-left + VK_A held/CANCEL/queued-cancel/UP verified");
                } finally {
                    destroyAcknowledged(handle);
                    if (gpu != null) gpu.close();
                }
            }
        });
    }

    @Test public void detachWhilePausedNewSurfaceRestoresPixelsAndClearsHeldInput() throws Exception {
        bounded(() -> {
            try (Fixture fixture = new Fixture(new int[]{16, 32, 48, 64, 80}, new int[]{0, 1, 0, 1, 0})) {
                long handle = create(fixture);
                GpuSurfaceConsumer first = null, second = null;
                try {
                    Observations observed = new Observations();
                    assertRealAudioInitialization(handle, observed);
                    first = new GpuSurfaceConsumer(1280, 720, true);
                    NativeEthornell.setSurface(handle, first.surface, first.width, first.height, 1);
                    NativeEthornell.setPaused(handle, 0);
                    long now = 20_000_000_000L;
                    NativeEthornell.step(handle, now);
                    advance(handle, first, observed, now += TICK, 1);
                    down(handle);
                    advance(handle, first, observed, now += TICK, 2);
                    NativeEthornell.setPaused(handle, ACTIVITY | FOCUS);
                    NativeEthornell.detachSurface(handle, 1); // actual renderer/window detach ACK
                    int frozen = settle(first);
                    NativeEthornell.setPaused(handle, 0);
                    now = assertFrozen(handle, first, observed, now, frozen, "detached, no explicit reasons");
                    down(handle); // no-Surface input must not replay on the replacement renderer
                    second = new GpuSurfaceConsumer(1280, 720, true);
                    NativeEthornell.setSurface(handle, second.surface, second.width, second.height, 2);
                    NativeEthornell.step(handle, now);
                    observed.drain(handle);
                    assertEquals("Reattach baseline executed guest", 2, observed.stages);
                    advance(handle, second, observed, now += TICK, 3);
                    assertEquals("Old Surface presented after replacement", frozen, first.frames.get());
                    down(handle);
                    advance(handle, second, observed, now += TICK, 4);
                    NativeEthornell.detachSurface(handle, 2); // independently prove detach clears held state
                    frozen = settle(second);
                    now = assertFrozen(handle, second, observed, now, frozen, "second detach, no pause");
                    NativeEthornell.setSurface(handle, second.surface, second.width, second.height, 3);
                    NativeEthornell.step(handle, now);
                    advance(handle, second, observed, now + TICK, 5);
                    fixture.assertUnchanged();
                    Log.i(TAG, "PASS real JNI detach/new-Surface reattach and same-Surface generation=3; "
                            + "guest held-state cleared + pixel probes restored (not Activity background proof)");
                } finally {
                    destroyAcknowledged(handle);
                    try { if (second != null) second.close(); }
                    finally { if (first != null) first.close(); }
                }
            }
        });
    }

    private static Context app() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    private static long create(Fixture fixture) {
        NativeEthornell.load();
        // Production JNI defaults flags=0: real backend required, never explicit silent mode.
        long handle = NativeEthornell.create(app(), fixture.resource.getPath(), fixture.user.getPath(), "", 0);
        assertNotEquals("Real JNI/runtime/audio create failed", 0, handle);
        return handle;
    }

    private static void assertRealAudioInitialization(long handle, Observations observed) {
        observed.drain(handle);
        assertTrue("Missing production backend initialization diagnostic", observed.initialized);
        assertEquals("VM must not execute during create", 0, observed.stages);
    }

    private static void down(long handle) {
        NativeEthornell.input(handle, 2, MOUSE_LEFT, 640, 360, 0);
        NativeEthornell.input(handle, 4, KEY_A, 0, 0, 0);
    }

    private static void cancel(long handle) { NativeEthornell.input(handle, 7, 0, 0, 0, 0); }

    /** No additional VM steps while waiting: each title must belong to precisely this input/time. */
    private static void advance(long handle, GpuSurfaceConsumer gpu, Observations observed,
                                long now, int expectedStages) {
        int frames = gpu.frames.get();
        NativeEthornell.step(handle, now);
        observed.drain(handle);
        assertEquals("Missing/excess guest stage: " + observed.values, expectedStages, observed.stages);
        long deadline = SystemClock.elapsedRealtime() + 6000;
        while ((gpu.frames.get() <= frames || !pixelsMatch(gpu)) && SystemClock.elapsedRealtime() < deadline) {
            gpu.assertHealthy();
            SystemClock.sleep(10);
        }
        gpu.assertHealthy();
        assertTrue("No NEW GPU buffer for stage " + expectedStages, gpu.frames.get() > frames);
        assertTrue("Guest render lost after stage " + expectedStages + ": " + gpu.probeDescription(PROBES), pixelsMatch(gpu));
    }

    private static boolean pixelsMatch(GpuSurfaceConsumer gpu) {
        byte[] image = gpu.snapshot();
        if (image == null) return false;
        for (int p = 0; p < PROBES.length; p++)
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int[] actual = gpu.pixel(image, PROBES[p][0] + dx, PROBES[p][1] + dy);
                for (int c = 0; c < 4; c++) if (Math.abs(actual[c] - COLORS[p][c]) > 3) return false;
            }
        return true;
    }

    /** Allow previously submitted GPU buffers to drain before taking a frozen baseline. */
    private static int settle(GpuSurfaceConsumer gpu) {
        int previous = gpu.frames.get();
        long stableSince = SystemClock.elapsedRealtime(), deadline = stableSince + 3000;
        while (SystemClock.elapsedRealtime() < deadline) {
            gpu.assertHealthy();
            SystemClock.sleep(20);
            int current = gpu.frames.get();
            if (current != previous) { previous = current; stableSince = SystemClock.elapsedRealtime(); }
            if (SystemClock.elapsedRealtime() - stableSince >= 200) return current;
        }
        throw new AssertionError("Consumer did not quiesce after suspend/detach");
    }

    private static long assertFrozen(long handle, GpuSurfaceConsumer gpu, Observations observed,
                                     long now, int frames, String phase) {
        int stages = observed.stages;
        byte[] pixels = gpu.snapshot();
        for (int i = 0; i < 8; i++) {
            NativeEthornell.step(handle, now += 250_000_000L);
            observed.drain(handle);
            gpu.assertHealthy();
            SystemClock.sleep(20);
            assertEquals(phase + " advanced VM marker", stages, observed.stages);
            assertEquals(phase + " submitted GPU buffer", frames, gpu.frames.get());
        }
        assertArrayEquals(phase + " changed frozen readback", pixels, gpu.snapshot());
        return now;
    }

    private static final class Observations {
        int stages;
        boolean initialized;
        final List<String> values = new ArrayList<>();
        void drain(long handle) {
            for (int i = 0; i < 128; i++) {
                EthornellSession.Event event = NativeEthornell.pollEvent(handle);
                if (event == null) return;
                String value = "kind=" + event.kind + " code=" + event.code + " text=" + event.text;
                values.add(value);
                Log.i(TAG, value);
                if (event.kind == 1) {
                    assertTrue("Fatal native diagnostic: " + values, event.code >= 0);
                    assertFalse("Silent fallback invalidates backend-path test", event.text.contains("silent"));
                    if (event.code == 0 && event.text.contains("MPEG is video-only")) initialized = true;
                } else if (event.kind == 4) {
                    assertEquals("Guest clock/input assertion failed or skipped stage: " + values,
                            "ETHORNELL_PAUSE_STAGE_" + stages, event.text);
                    assertEquals(0, event.code);
                    stages++;
                } else {
                    fail("Unexpected confirmation/Closed; never auto-answer a guest failure: " + values);
                }
            }
            fail("Event queue exceeded bounded budget");
        }
    }

    /** Original BP assembler; all comparisons run INSIDE the real VM, not in a Java fake. */
    private static final class Program {
        final ByteArrayOutputStream code = new ByteArrayOutputStream();
        final List<Integer> branchOffsets = new ArrayList<>();
        final List<Integer> stringOffsets = new ArrayList<>();
        final List<String> strings = new ArrayList<>();

        void bytes(int... values) { for (int value : values) code.write(value); }
        void integer(int value) { bytes(2, value, value >>> 8, value >>> 16, value >>> 24); }
        void pointer() { bytes(4, 4, 0); } // 4-byte frame, sprite at frame_base - 4
        void sprite() { pointer(); bytes(8, 2); }
        void call(int group, int selector, int... values) {
            for (int value : values) integer(value);
            bytes(group, selector);
        }
        void string(String value) {
            stringOffsets.add(code.size()); strings.add(value); bytes(5, 0, 0);
        }
        void title(String value) { string(value); call(0x80, 0x66); }
        void require(int expected) {
            integer(expected); bytes(0x30); // eq -> condition
            branchOffsets.add(code.size()); integer(0); bytes(0x15, 1); // jump if false
        }
        byte[] assemble(int[] ticks, int[] held) {
            assertEquals(ticks.length, held.length);
            // Source contracts: tools/ethornell/generate_fixture.py::_make_render_module;
            // real vm/lib.rs: 80:04 uses VmTime, 80:11 calls query_key_held, 80:5F yields.
            // parse_bp_program: string displacement relative to OPCODE; absolute branches
            // include 16-byte BP header; move_arg pops pointer then value (DWORD width=2).
            bytes(0x10, 0, 4, 0x20, 0x11); // frame base += 4
            call(0x90, 3, 1048576);
            call(0x90, 1, 1);
            call(0x90, 8, 1);
            call(0x90, 7, 1000);
            integer(1); string("system.arc"); string("checker.png"); call(0x90, 0x10);
            call(0x90, 0x50); pointer(); bytes(0x0a, 2);
            sprite(); call(0x90, 0x56, 512, 232, 1, 1, 0, 2176);
            sprite(); call(0x90, 0x54, 1);
            call(0x90, 0, 1);
            for (int stage = 0; stage < ticks.length; stage++) {
                call(0x80, 4); require(ticks[stage]);
                call(0x80, 0x11, MOUSE_LEFT); require(held[stage]);
                call(0x80, 0x11, KEY_A); require(held[stage]);
                title("ETHORNELL_PAUSE_STAGE_" + stage);
                call(0x80, 0x5f); // end precisely this cooperative scheduler pass
            }
            int done = code.size();
            call(0x80, 0x5f); integer(16 + done); bytes(0x14); // stay alive, no extra title
            int failure = code.size();
            title("ETHORNELL_PAUSE_GUEST_CLOCK_OR_HELD_MISMATCH");
            int failedLoop = code.size();
            call(0x80, 0x5f); integer(16 + failedLoop); bytes(0x14);
            bytes(0x17); // parser code/data boundary; the live paths yield/jump, never ret
            byte[] instructions = code.toByteArray();
            for (int offset : branchOffsets)
                ByteBuffer.wrap(instructions).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 1, 16 + failure);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            result.write(instructions, 0, instructions.length);
            for (int i = 0; i < strings.size(); i++) {
                int displacement = result.size() - stringOffsets.get(i);
                assertTrue("Original BP string exceeds relative-i16 range", displacement <= Short.MAX_VALUE);
                instructions[stringOffsets.get(i) + 1] = (byte) displacement;
                instructions[stringOffsets.get(i) + 2] = (byte) (displacement >>> 8);
                byte[] text = strings.get(i).getBytes(StandardCharsets.US_ASCII);
                result.write(text, 0, text.length); result.write(0);
            }
            byte[] all = result.toByteArray();
            System.arraycopy(instructions, 0, all, 0, instructions.length);
            return ByteBuffer.allocate(16 + all.length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(16).putInt(all.length).putInt(0).putInt(0).put(all).array();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File base, resource, user, archive;
        final byte[] expected;
        Fixture(int[] ticks, int[] held) throws Exception {
            base = new File(app().getFilesDir().getCanonicalFile(), "ethornell-pause-" + UUID.randomUUID());
            resource = new File(base, "resource"); user = new File(base, "user");
            assertTrue(resource.mkdirs()); assertTrue(user.mkdir());
            assertFalse(resource.getCanonicalFile().toPath().startsWith(user.getCanonicalFile().toPath()));
            assertFalse(user.getCanonicalFile().toPath().startsWith(resource.getCanonicalFile().toPath()));
            assertArrayEquals("Not a fresh test user root", new String[0], user.list());
            byte[] bp = new Program().assemble(ticks, held);
            byte[] png = originalChecker();
            int indexSize = 16 + 2 * 32;
            ByteBuffer pack = ByteBuffer.allocate(indexSize + bp.length + png.length).order(ByteOrder.LITTLE_ENDIAN);
            pack.put("PackFile    ".getBytes(StandardCharsets.US_ASCII)).putInt(2);
            entry(pack, "ipl._bp", 0, bp.length); entry(pack, "checker.png", bp.length, png.length);
            pack.put(bp).put(png); expected = pack.array();
            archive = new File(resource, "system.arc");
            Files.write(archive.toPath(), expected);
        }
        static void entry(ByteBuffer pack, String name, int offset, int size) {
            byte[] field = new byte[16], text = name.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, field, 0, text.length);
            pack.put(field).putInt(offset).putInt(size).putInt(0).putInt(0);
        }
        static byte[] originalChecker() throws Exception {
            byte[] asset;
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("ethornell-render/system.arc");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    assertTrue("Original render asset exceeds budget", output.size() + count <= 65536);
                    output.write(buffer, 0, count);
                }
                asset = output.toByteArray();
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(asset))
                hash.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            assertEquals("Only reuse the pinned ORIGINAL synthetic render asset",
                    "f0d1d96ef30135c95bc722414a9d9572ec3f34952fb57ad9a1bfcf6adb8c06d8", hash.toString());
            ByteBuffer pack = ByteBuffer.wrap(asset).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(2, pack.getInt(12));
            int start = 16 + 32; // deterministic generator's second entry is checker.png
            assertEquals("checker.png", new String(asset, start, 11, StandardCharsets.US_ASCII));
            int offset = 80 + pack.getInt(start + 16), length = pack.getInt(start + 20);
            assertTrue(offset >= 80 && length > 0 && offset + length == asset.length);
            return Arrays.copyOfRange(asset, offset, offset + length);
        }
        void assertUnchanged() throws Exception {
            assertArrayEquals("Resource bytes modified", expected, Files.readAllBytes(archive.toPath()));
            assertArrayEquals("Writes leaked into resource root", new String[]{"system.arc"}, resource.list());
        }
        @Override public void close() { Log.i(TAG, "Original tiny test data retained at " + base); }
    }

    private interface Checked { void run() throws Exception; }

    private static void bounded(Checked action) throws Exception {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Ethornell-pause-test-deadline"); thread.setDaemon(true); return thread;
        });
        ScheduledFuture<?> deadline = watchdog.schedule(() -> {
            Log.e(TAG, "Native/GPU deadline exceeded; terminate only instrumentation pid=" + Process.myPid());
            Process.killProcess(Process.myPid());
        }, 70, TimeUnit.SECONDS);
        try { action.run(); }
        finally { deadline.cancel(false); watchdog.shutdownNow(); }
    }

    private static void destroyAcknowledged(long handle) {
        try { NativeEthornell.destroy(handle); }
        catch (RuntimeException | LinkageError failure) {
            // A timed-out native owner may still borrow the window. Never release its consumer.
            Log.e(TAG, "Missing destroy ACK; terminate instrumentation before Surface release", failure);
            Process.killProcess(Process.myPid());
            throw failure;
        }
    }
}
