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
import com.core.nativeplugin.NativeRuntimePack;
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
    /** Schema-valid explicit cancel for kind-5 dialogs; tests never fake an accepted answer. */
    private static final String DIALOG_CANCEL = "{\"accepted\":false}";
    private static native String probe(String absoluteLibrary);
    private static native int[] probeWriterLocks(String path);
    private static Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext(); }
    private static Bundle args() { return InstrumentationRegistry.getArguments(); }
    private static int encoding() {
        String selected = args().getString("encoding", "sjis");
        assertTrue("encoding must be sjis or gbk", selected.equals("sjis") || selected.equals("gbk"));
        return selected.equals("gbk") ? 1 : 0;
    }

    /**
     * Extracts {@code assets/engine-runtime/ethornell.zip.xz} on demand and binds the absolute
     * library path into the production JNI bridge. Replaces the legacy APK lib/arm64-v8a lookup:
     * after a fresh install or {@code pm clear} that file never exists, and the bridge would
     * otherwise fall back to a missing plain SONAME.
     */
    private static String runtimeLibrary() {
        Context context = app();
        assertTrue("Ethornell runtime pack not ready: NativeRuntimePack.ensure(PACK_ETHORNELL) failed "
                        + "to extract assets/engine-runtime/ethornell.zip.xz",
                NativeRuntimePack.ensure(context, NativeRuntimePack.PACK_ETHORNELL));
        String path = NativeRuntimePack.libraryPath(context, NativeRuntimePack.PACK_ETHORNELL, "libethornell.so");
        assertNotNull("Ethornell runtime pack not ready: libethornell.so missing after extraction", path);
        File library = new File(path);
        assertTrue("Ethornell runtime pack not ready: " + path + " is not a readable file",
                library.isFile() && library.canRead());
        assertTrue("Ethornell JNI bridge rejected runtime library path " + path,
                NativeEthornell.prepareEngineLibrary(context));
        return path;
    }

    @Test public void loadAbiBuildInfoAndRejectInvalidTokens() throws Exception {
        bounded(() -> {
            // The engine library only exists after extracting the runtime pack, not in APK lib/.
            String library = runtimeLibrary();
            System.loadLibrary("ethornell_test_probe");
            String report = probe(library);
            Log.i(TAG, "LOAD " + report + " sdk=" + Build.VERSION.SDK_INT + " abi=" + Build.SUPPORTED_ABIS[0]
                    + " page_size=" + Os.sysconf(OsConstants._SC_PAGESIZE) + " pack=ethornell lib=" + library);
            assertTrue(report, report.startsWith("OK RTLD_NOW"));
            assertTrue(report, report.contains("symbols=15"));
            assertTrue(report, report.contains("build_info=ethornell"));
            for (long bad : new long[]{0, -1, Long.MAX_VALUE}) {
                rejected("invalid token step", () -> NativeEthornell.step(bad, System.nanoTime()));
                rejected("invalid token pause", () -> NativeEthornell.setPaused(bad, 0));
                rejected("invalid token poll", () -> NativeEthornell.pollEvent(bad));
                rejected("invalid token payload", () -> NativeEthornell.requestPayload(bad, 1));
                rejected("invalid token close", () -> NativeEthornell.requestClose(bad));
                NativeEthornell.destroy(bad); // production destroy explicitly permits unknown/idempotent tokens
            }
        });
    }

    @Test public void supplementaryUtf8PathsAndWrongThreadAreSafe() throws Exception {
        bounded(() -> {
            try (Fixture fixture = new Fixture(false)) {
                runtimeLibrary();
                for (String bad : new String[]{"bad\0path", "bad\uD800", "bad\uDC00"}) {
                    rejected("invalid UTF-16 resource", () -> NativeEthornell.create(app(), bad, fixture.user.getPath(), "", encoding()));
                    rejected("invalid UTF-16 user", () -> NativeEthornell.create(app(), fixture.resource.getPath(), bad, "", encoding()));
                    rejected("invalid UTF-16 font", () -> NativeEthornell.create(app(), fixture.resource.getPath(), fixture.user.getPath(), bad, encoding()));
                }
                long handle = create(fixture);
                try {
                    // Payload lookup contract: unknown/stale ids return null (non-fatal); id 0 is rejected.
                    assertNull("unknown dialog id must not be fatal",
                            NativeEthornell.requestPayload(handle, Long.MAX_VALUE));
                    rejected("zero dialog request id", () -> NativeEthornell.requestPayload(handle, 0));
                    NativeEthornell.completeRequest(handle, Long.MAX_VALUE, 0, DIALOG_CANCEL); // stale id: no-op
                    // Neither the unknown payload nor the stale response may poison the owner token.
                    NativeEthornell.setPaused(handle, 1);
                    NativeEthornell.setPaused(handle, 0);
                    poll(handle, new Events(), true);
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

    @Test public void publicSharedStorageSupportsExclusiveOpenFileDescriptionLocks() throws Exception {
        assumeTrue("Dedicated test package required", app().getPackageName().equals("com.tyranor.next.startuptest"));
        assumeTrue("Explicit opt-in required", "true".equals(args().getString("startup_test")));
        File root = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "bgi-writer-lock-" + UUID.randomUUID());
        assertTrue("Public shared-storage fixture is not writable", root.mkdir());
        File lock = new File(root, "writer.lock");
        try {
            System.loadLibrary("ethornell_test_probe");
            int[] results = probeWriterLocks(lock.getAbsolutePath());
            Log.i(TAG, "PUBLIC_LOCKS flock/ofd/conflict/released=" + java.util.Arrays.toString(results));
            assertEquals("OFD lock is unavailable", 0, results[1]);
            assertTrue("A second descriptor must be refused", results[2] == OsConstants.EAGAIN || results[2] == OsConstants.EACCES);
            assertEquals("Closing the owner must release the lock", 0, results[3]);
        } finally {
            if (lock.exists()) assertTrue(lock.delete());
            assertTrue(root.delete());
        }
    }

    @Test public void vmWritesAndReopensGameLocalSaveOnSharedStorage() throws Exception {
        File external = app().getExternalFilesDir(null);
        assertNotNull("Shared storage is unavailable", external);
        verifyGameLocalSave(external);
    }

    @Test public void vmWritesAndReopensGameLocalSaveOnPublicSharedStorage() throws Exception {
        verifyGameLocalSave(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS));
    }

    @Test public void vmSys80ResourceUserDataSlotWritesAndReopensThroughFileApis() throws Exception {
        assumeTrue("Dedicated test package required", app().getPackageName().equals("com.tyranor.next.startuptest"));
        assumeTrue("Explicit opt-in required", "true".equals(args().getString("startup_test")));
        bounded(() -> {
            File external = app().getExternalFilesDir(null);
            assertNotNull("App-specific shared storage is unavailable", external);
            File base = new File(external.getCanonicalFile(), "bgi-sys80-slot-" + UUID.randomUUID());
            File resource = new File(base, "game"), user = new File(resource, "savedata");
            assertTrue("Unable to create isolated game/savedata fixture", user.mkdirs());
            File resourceAlias = new File(resource, "bgi-resource");
            assertFalse("Virtual bgi-resource prefix must not be shadowed by a real directory",
                    resourceAlias.exists());
            File archive = new File(resource, "system.arc");
            File savedSlot = new File(new File(user, "UserData"), "7.sud");
            File resourceSlot = new File(new File(resourceAlias, "UserData"), "7.sud");
            assertFalse("Synthetic SUD slot must start absent", savedSlot.exists());
            try {
                byte[] writeArchive = Sys80Fixture.archive("write");
                Files.write(archive.toPath(), writeArchive);
                runSys80Phase(resource, user, "ETHORNELL_SYS80_SLOT_WRITE_OK");
                assertTrue("Sys80:32 did not write the slot under game/savedata/UserData",
                        savedSlot.isFile() && savedSlot.length() == Sys80Fixture.SLOT_BYTES.length);
                assertArrayEquals("Sys80:32 wrote the wrong slot bytes", Sys80Fixture.SLOT_BYTES,
                        Files.readAllBytes(savedSlot.toPath()));
                assertFalse("Resource-root slot was modified", resourceSlot.exists());
                assertFalse("The virtual resource prefix became a physical directory", resourceAlias.exists());

                // A new native session must prove Sys80:30/31/34/35 can reopen/query the
                // actual UserData slot through the default single and explicit double prefix.
                Files.write(archive.toPath(), Sys80Fixture.archive("read"));
                runSys80Phase(resource, user, "ETHORNELL_SYS80_SLOT_READ_OK");
                assertArrayEquals("Sys80 reads changed the persisted slot", Sys80Fixture.SLOT_BYTES,
                        Files.readAllBytes(savedSlot.toPath()));
                assertFalse("Read phase created the missing negative-control slot",
                        new File(new File(user, "UserData"), "Missing.sud").exists());
                assertArrayEquals("Read phase changed the resource archive",
                        Sys80Fixture.archive("read"), Files.readAllBytes(archive.toPath()));
                Log.i(TAG, "PASS real VM Sys80:32 write + new-session 30/31/34/35; single/double bgi-resource prefix, case folding, archive fallback and missing-slot controls");
            } finally {
                try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(base.toPath())) {
                    for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(
                            java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
                }
            }
        });
    }

    private static void runSys80Phase(File resource, File user, String expectedTitle) throws Exception {
        File r = resource.getCanonicalFile(), u = user.getCanonicalFile();
        assertTrue("Invalid game-local roots", u.equals(new File(r, "savedata").getCanonicalFile()));
        runtimeLibrary();
        long handle = NativeEthornell.create(app(), r.getPath(), u.getPath(), "", encoding());
        assertNotEquals("Sys80 phase create failed", 0, handle);
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
                    observed.add(text);
                    Log.i(TAG, "SYS80 " + text);
                    assertFalse("Fatal Sys80 file-I/O diagnostic: " + observed,
                            event.kind == 1 && event.code < 0);
                    assertNotEquals("Unexpected confirmation in Sys80 fixture", 2, event.kind);
                    if (event.kind == 5) {
                        String payload = NativeEthornell.requestPayload(handle, event.requestId);
                        NativeEthornell.completeRequest(handle, event.requestId, 0, DIALOG_CANCEL);
                        fail("Unexpected dialog in Sys80 fixture: " + text + " payload=" + payload);
                    }
                    if (event.kind == 4) {
                        assertFalse("Repeated Title event", title);
                        assertEquals("Wrong Sys80 result: " + observed, expectedTitle, event.text);
                        assertEquals(0, event.code);
                        title = true;
                    }
                    if (event.kind == 3) {
                        assertTrue("Closed without exact Sys80 Title: " + observed, title);
                        assertEquals(0, event.code);
                        closed = true;
                    }
                    if (i == 127) fail("Sys80 event queue exceeded budget");
                }
                consumer.assertHealthy();
                if (!closed) SystemClock.sleep(20);
            }
            assertTrue("Missing Title->Closed for " + expectedTitle + ": " + observed, title && closed);
        } finally {
            destroyAcknowledged(handle);
            if (consumer != null) consumer.close();
        }
    }

    private static void verifyGameLocalSave(File external) throws Exception {
        assumeTrue("Dedicated test package required", app().getPackageName().equals("com.tyranor.next.startuptest"));
        assumeTrue("Explicit opt-in required", "true".equals(args().getString("startup_test")));
        bounded(() -> {
            File base = new File(external.getCanonicalFile(), "bgi-local-save-" + UUID.randomUUID());
            File resource = new File(base, "game"), user = new File(resource, "savedata");
            assertTrue(user.mkdirs());
            Context tests = InstrumentationRegistry.getInstrumentation().getContext();
            File archive = new File(resource, "system.arc");
            try {
                try (InputStream input = tests.getAssets().open("ethornell-user-data/write/system.arc")) {
                    Files.copy(input, archive.toPath());
                }
                Log.i(TAG, "GAME_LOCAL_WRITE_BEGIN");
                runUserDataPhase(resource, user, "ETHORNELL_USER_DATA_WRITE_OK");
                Log.i(TAG, "GAME_LOCAL_WRITE_ACK");
                File save = new File(user, "BGI.gdb");
                assertTrue("Save was not written inside game/savedata", save.isFile() && save.length() > 32);
                byte[] persisted = Files.readAllBytes(save.toPath());
                assertFalse("Save leaked into the resource root", new File(resource, "BGI.gdb").exists());
                Files.delete(archive.toPath());
                try (InputStream input = tests.getAssets().open("ethornell-user-data/read/system.arc")) {
                    Files.copy(input, archive.toPath());
                }
                Log.i(TAG, "GAME_LOCAL_READ_BEGIN");
                runUserDataPhase(resource, user, "ETHORNELL_USER_DATA_READ_OK");
                Log.i(TAG, "GAME_LOCAL_READ_ACK");
                assertArrayEquals("Reopen changed the save", persisted, Files.readAllBytes(save.toPath()));
            } finally {
                // This random fixture is the only cleanup scope; do not follow links.
                try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(base.toPath())) {
                    for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(
                            java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
                }
            }
        });
    }

    private static long runUserDataPhase(File resource, File user, String expectedTitle) throws Exception {
        File r = resource.getCanonicalFile(), u = user.getCanonicalFile();
        assertTrue("Invalid overlapping roots", u.equals(new File(r, "savedata"))
                || (!r.toPath().startsWith(u.toPath()) && !u.toPath().startsWith(r.toPath())));
        runtimeLibrary();
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
                    if (event.kind == 5) {
                        // No dialog fixture belongs to this phase: fetch the payload, cancel it
                        // explicitly, then fail rather than silently accept or ignore it.
                        String payload = NativeEthornell.requestPayload(handle, event.requestId);
                        NativeEthornell.completeRequest(handle, event.requestId, 0, DIALOG_CANCEL);
                        fail("Unexpected dialog in user-data fixture: " + text + " payload=" + payload);
                    }
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
            Log.i(TAG, "USER_DATA_DESTROY_BEGIN handle=" + handle);
            destroyAcknowledged(handle);
            Log.i(TAG, "USER_DATA_DESTROY_ACK handle=" + handle);
            if (consumer != null) consumer.close();
            Log.i(TAG, "USER_DATA_CONSUMER_CLOSED handle=" + handle);
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
            runtimeLibrary();
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
        runtimeLibrary();
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
                NativeEthornell.completeRequest(handle, event.requestId, 1, "");
            }
            if (event.kind == 5 && respond) {
                // Never route a dialog through the kind-2 integer answer.
                assertTrue("invalid/replayed dialog request: " + value,
                        event.requestId != 0 && events.requests.add(event.requestId));
                // Payload is fetched on the owner thread; no real dialog fixture exists here,
                // so the only honest policy is an explicit schema-valid cancel (never accept).
                String payload = NativeEthornell.requestPayload(handle, event.requestId);
                assertNotNull("pending dialog payload missing: " + value, payload);
                Log.i(TAG, "DIALOG id=" + event.requestId + " payload=" + payload);
                NativeEthornell.completeRequest(handle, event.requestId, 0, DIALOG_CANCEL);
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

    /** Original in-test BP program exercising the VM's real Sys80 disk-file opcodes. */
    private static final class Sys80Fixture {
        static final byte[] SLOT_BYTES = new byte[]{'T', 'Y', 'R', 'A', 'N', 'O', 'R', '-', 'S', 'L', 'O', 'T', '-', 'V', '1', 0};
        private static final byte[] FALLBACK_BYTES = new byte[]{'R', 'E', 'S', 'O', 'U', 'R', 'C', 'E'};
        private static final int FRAME = 32;

        static byte[] archive(String phase) throws Exception {
            Program program = new Program();
            program.prologue();
            if ("write".equals(phase)) {
                // The restricted VM already reports bgi-resource\\ as its primary root.
                program.pointer(0);
                program.emit(0x0b, SLOT_BYTES.length);
                program.emit(SLOT_BYTES);
                program.pushString("UserData\\7.sud");
                program.pointer(0);
                program.pushInt(SLOT_BYTES.length);
                program.sys(0x32);
                program.requireInt(1);
                program.finishWith("ETHORNELL_SYS80_SLOT_WRITE_OK", "ETHORNELL_SYS80_SLOT_WRITE_FAILED");
            } else if ("read".equals(phase)) {
                // Generic Sys81 loads use a different RuntimeTraceApi callback than the
                // Sys80 loose-file operations below; check this first to catch alias drift.
                program.checkGenericSlot();
                // First exercise the default bgi-resource\\ root before any setter can
                // mask a resource-root routing failure on the unpatched host.
                program.checkSlot("UserData\\7.sud");
                program.checkSlot("userdata\\7.SUD");
                program.checkDoublePrefixSlot("bgi-resource\\UserData\\7.sud");
                program.checkArchiveFallback();
                program.checkMissingSlot();
                program.finishWith("ETHORNELL_SYS80_SLOT_READ_OK", "ETHORNELL_SYS80_SLOT_READ_FAILED");
            } else {
                throw new IllegalArgumentException("Unknown Sys80 fixture phase: " + phase);
            }
            return packEntries(
                    new String[]{"ipl._bp", "fallback.bin", "UserData\\7.sud"},
                    new byte[][]{program.finish(), FALLBACK_BYTES, FALLBACK_SLOT_BYTES});
        }

        private static final byte[] FALLBACK_SLOT_BYTES = new byte[]{
                'F', 'A', 'L', 'L', 'B', 'A', 'C', 'K', '-', 'S', 'L', 'O', 'T', '-', 'V', '1'
        };

        private static byte[] packEntries(String[] names, byte[][] payloads) {
            if (names.length != payloads.length) throw new IllegalArgumentException("Pack entry mismatch");
            int payloadOffset = 16 + names.length * 32;
            int payloadLength = 0;
            for (byte[] payload : payloads) payloadLength += payload.length;
            ByteBuffer pack = ByteBuffer.allocate(payloadOffset + payloadLength)
                    .order(ByteOrder.LITTLE_ENDIAN);
            pack.put("PackFile    ".getBytes(StandardCharsets.US_ASCII)).putInt(names.length);
            int offset = 0;
            for (int i = 0; i < names.length; i++) {
                putEntry(pack, names[i], offset, payloads[i].length);
                offset += payloads[i].length;
            }
            for (byte[] payload : payloads) pack.put(payload);
            return pack.array();
        }

        private static void putEntry(ByteBuffer pack, String name, int offset, int length) {
            byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
            if (encoded.length == 0 || encoded.length >= 16) throw new IllegalArgumentException("Bad pack name");
            byte[] padded = new byte[16];
            System.arraycopy(encoded, 0, padded, 0, encoded.length);
            pack.put(padded).putInt(offset).putInt(length).putInt(0).putInt(0);
        }

        private static final class Program {
            private final ByteArrayOutputStream code = new ByteArrayOutputStream();
            private final ByteArrayOutputStream strings = new ByteArrayOutputStream();
            private final List<Branch> branches = new ArrayList<>();
            private final List<StringFixup> stringFixups = new ArrayList<>();
            private final java.util.Map<String, Integer> labels = new java.util.HashMap<>();
            private boolean outcomeAdded;

            void prologue() { emit(0x10, 0x00, FRAME, 0x20, 0x11); }
            void emit(int... bytes) { for (int value : bytes) code.write(value & 0xff); }
            void emit(byte[] bytes) { code.write(bytes, 0, bytes.length); }
            void pushInt(int value) {
                if (value >= -128 && value <= 127) emit(0x00, value);
                else if (value >= -32768 && value <= 32767) emit(0x01, value, value >>> 8);
                else emit(0x02, value, value >>> 8, value >>> 16, value >>> 24);
            }
            void pointer(int offset) {
                int displacement = FRAME - offset;
                emit(0x04, displacement, displacement >>> 8);
            }
            void pushString(String value) {
                int opcode = code.size();
                emit(0x05, 0, 0);
                stringFixups.add(new StringFixup(opcode, value));
            }
            void sys(int selector) { emit(0x80, selector); }
            void sys81(int selector) { emit(0x81, selector); }
            void requireInt(int expected) {
                pushInt(expected);
                emit(0x30);
                branch("failed", 1); // predicate 1 branches when equality is false
            }
            void branch(String label, int zeroPredicate) {
                int operand = code.size() + 1;
                emit(0x02, 0, 0, 0, 0); // branch destination is a 32-bit code-region offset
                emit(0x15, zeroPredicate);
                branches.add(new Branch(operand, label));
            }
            void jump(String label) {
                int operand = code.size() + 1;
                emit(0x02, 0, 0, 0, 0, 0x14); // unconditional branch
                branches.add(new Branch(operand, label));
            }
            void load(int offset) {
                int displacement = FRAME - offset;
                emit(0x04, displacement, displacement >>> 8, 0x08, 0x02);
            }
            void checkGenericSlot() {
                String filename = "bgi-resource\\UserData\\7.sud";
                // Sys81:32 inputs are pushed in VM stack order: requested length,
                // destination, filename, then bytes-read output pointer.
                pushInt(SLOT_BYTES.length);
                pointer(0);
                pushString(filename);
                pointer(28);
                sys81(0x32);
                requireInt(0);
                load(28);
                requireInt(SLOT_BYTES.length);
                requireBytes(0, SLOT_BYTES, 0, SLOT_BYTES.length);

                // Sys81:35 takes context then path and returns the resolved size.
                pushInt(0);
                pushString(filename);
                sys81(0x35);
                requireInt(SLOT_BYTES.length);
            }
            void checkSlot(String filename) {
                pointer(0); pushString("system.arc"); pushString(filename); sys(0x30);
                requireInt(SLOT_BYTES.length);
                requireBytes(0, SLOT_BYTES, 0, SLOT_BYTES.length);

                pointer(20); pushString("system.arc"); pushString(filename); pushInt(4); pushInt(8); sys(0x31);
                requireInt(0);
                requireBytes(20, SLOT_BYTES, 4, 8);

                pushInt(0); pushString(filename); sys(0x34); requireInt(1);
                pushInt(0); pushString(filename); sys(0x35); requireInt(SLOT_BYTES.length);
            }
            void checkDoublePrefixSlot(String filename) {
                pointer(20); pushInt(0); pushString(filename); pushInt(4); pushInt(8); sys(0x31);
                requireInt(0);
                requireBytes(20, SLOT_BYTES, 4, 8);
                pushInt(0); pushString(filename); sys(0x34); requireInt(1);
                pushInt(0); pushString(filename); sys(0x35); requireInt(SLOT_BYTES.length);
            }
            void requireBytes(int destination, byte[] expected, int start, int length) {
                for (int offset = 0; offset < length; offset += 4) {
                    load(destination + offset);
                    int word = (expected[start + offset] & 0xff)
                            | ((expected[start + offset + 1] & 0xff) << 8)
                            | ((expected[start + offset + 2] & 0xff) << 16)
                            | (expected[start + offset + 3] << 24);
                    pushInt(word);
                    emit(0x30);
                    branch("failed", 1);
                }
            }
            void checkArchiveFallback() {
                pointer(0); pushString("system.arc"); pushString("fallback.bin"); sys(0x30);
                requireInt(FALLBACK_BYTES.length);
                requireBytes(0, FALLBACK_BYTES, 0, FALLBACK_BYTES.length);
                pointer(20); pushString("system.arc"); pushString("fallback.bin");
                pushInt(0); pushInt(FALLBACK_BYTES.length); sys(0x31);
                requireInt(0);
                requireBytes(20, FALLBACK_BYTES, 0, FALLBACK_BYTES.length);
                pushString("system.arc"); pushString("fallback.bin"); sys(0x34); requireInt(1);
                pushString("system.arc"); pushString("fallback.bin"); sys(0x35);
                requireInt(FALLBACK_BYTES.length);
            }
            void checkMissingSlot() {
                pointer(0); pushInt(0); pushString("UserData\\Missing.sud");
                pushInt(0); pushInt(4); sys(0x31); requireInt(1);
                pushInt(0); pushString("UserData\\Missing.sud"); sys(0x34); requireInt(0);
                pushInt(0); pushString("UserData\\Missing.sud"); sys(0x35); requireInt(0);
            }
            void finishWith(String success, String failure) {
                if (outcomeAdded) throw new IllegalStateException("Duplicate outcome");
                outcomeAdded = true;
                labels.put("success", code.size());
                pushString(success); sys(0x66); jump("finish");
                labels.put("failed", code.size());
                pushString(failure); sys(0x66); jump("finish");
                labels.put("finish", code.size());
                pushInt(1); sys(0x68); sys(0x69);
                emit(0x10, 0x00, FRAME, 0x21, 0x11, 0x17);
            }
            byte[] finish() {
                if (!outcomeAdded) throw new IllegalStateException("Missing outcome");
                byte[] instructions = code.toByteArray();
                for (Branch branch : branches) {
                    Integer target = labels.get(branch.label);
                    if (target == null) throw new IllegalStateException("Unknown branch " + branch.label);
                    ByteBuffer.wrap(instructions).order(ByteOrder.LITTLE_ENDIAN).putInt(branch.operandOffset, target);
                }
                for (StringFixup fixup : stringFixups) {
                    byte[] text = (fixup.value + "\0").getBytes(StandardCharsets.US_ASCII);
                    int delta = instructions.length + strings.size() - fixup.opcodeOffset;
                    if (delta < Short.MIN_VALUE || delta > Short.MAX_VALUE)
                        throw new IllegalStateException("String displacement overflow");
                    instructions[fixup.opcodeOffset + 1] = (byte) delta;
                    instructions[fixup.opcodeOffset + 2] = (byte) (delta >>> 8);
                    strings.write(text, 0, text.length);
                }
                byte[] payload = new byte[instructions.length + strings.size()];
                System.arraycopy(instructions, 0, payload, 0, instructions.length);
                System.arraycopy(strings.toByteArray(), 0, payload, instructions.length, strings.size());
                ByteBuffer bp = ByteBuffer.allocate(16 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
                bp.putInt(16).putInt(payload.length).putInt(0).putInt(0).put(payload);
                return bp.array();
            }
            private static final class Branch {
                final int operandOffset; final String label;
                Branch(int offset, String name) { operandOffset = offset; label = name; }
            }
            private static final class StringFixup {
                final int opcodeOffset; final String value;
                StringFixup(int offset, String text) { opcodeOffset = offset; value = text; }
            }
        }
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
            if (profile.startsWith("render") || profile.equals("user-data-write") || profile.equals("user-data-read")) {
                Context tests = InstrumentationRegistry.getInstrumentation().getContext();
                String directory = profile.startsWith("render") ? "ethornell-" + profile : "ethornell-user-data/" + profile.substring("user-data-".length());
                String expectedHash = profile.equals("render") ? "f0d1d96ef30135c95bc722414a9d9572ec3f34952fb57ad9a1bfcf6adb8c06d8"
                        : profile.equals("render-close") ? "233cd2ec37af3ce195322fb439e023e67034b2edab302e2cd222a433d3171d57"
                        : profile.equals("user-data-write") ? "b64f297f3854c19360406b51143cd5e30b1b44b3e711bc21917d99f88f0fd538"
                        : "a7a5494ca1e8a6e66f00e46c35a8d5a93920457d49a92ad75c82ad20c44e842c";
                byte[] manifest;
                try (InputStream input = tests.getAssets().open(directory + "/fixture.json")) { manifest = readSmallAsset(input); }
                try (InputStream input = tests.getAssets().open(directory + "/system.arc")) { expected = readSmallAsset(input); }
                JSONObject metadata = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
                assertEquals(profile, metadata.getString("profile"));
                String digest = sha256(expected);
                assertEquals("Pinned original archive " + profile, expectedHash, digest);
                if (profile.equals("render-close")) {
                    JSONObject derived = metadata.getJSONObject("derived_from");
                    assertEquals("render", derived.getString("profile"));
                    assertEquals("f0d1d96ef30135c95bc722414a9d9572ec3f34952fb57ad9a1bfcf6adb8c06d8",
                            derived.getString("system_arc_sha256"));
                    assertEquals("bfb81d854ecb0bf1f1d375631e9450bbe51a6316c2513736e0a93e1a23d5cf09",
                            metadata.getJSONObject("entries").getJSONObject("ipl._bp").getString("sha256"));
                    assertTrue(derived.getString("derivation").contains("Sys80:68 SetNativeCloseMode"));
                }
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
