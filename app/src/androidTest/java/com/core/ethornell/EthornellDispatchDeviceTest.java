package com.core.ethornell;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.core.engine.EngineSessionRegistry;
import com.core.engine.LaunchContract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real app-UID -> private production Activity -> durable cross-process protocol.
 * No fake claim, instrumentation Activity, exported endpoint, or capability promotion.
 * Run separately from a CLI accessibility server; one non-accessibility connection only.
 */
@RunWith(AndroidJUnit4.class)
public final class EthornellDispatchDeviceTest {
    private static final String TAG = "EthornellDispatchTest";
    private static UiAutomation automation;

    @BeforeClass public static void connectDiagnosticsOnce() throws Exception {
        automation = boundedDiagnostic(() -> InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY));
        assertNotNull("No diagnostics connection (stop CLI accessibility server first)", automation);
        // Runner owns connection lifetime. Never reconnect/change flags/disconnect per command.
    }

    @Test public void revokedBeforeDeliveryRejectsLateIntentWithoutCreatingRuntime() throws Exception {
        try (Run run = new Run("revoked")) {
            String token = run.issue();
            assertTrue(EthornellDispatchRegistry.revokeUnclaimed(run.app.getFilesDir(), token, run.identity));
            assertEquals(EthornellDispatchRegistry.Stage.REVOKED, run.record(token).stage);
            // Hold the actual production metadata.lock across delivery. This is an Android OS
            // file lock in two different processes, not the registry's JVM lock/unit-test probe.
            Owner owner = run.deliverBehindMetadataLock(token, run.resource);
            run.awaitExit(owner, true);
            EthornellDispatchRegistry.Record rejected = run.record(token);
            assertEquals(EthornellDispatchRegistry.Stage.REVOKED, rejected.stage);
            assertEquals(0, rejected.pid);
            assertEquals("", rejected.startTime);
            assertFalse("Revoked delivery created private user data", run.user.exists());
            assertNull("Revoked delivery must not publish a native-create registry entry",
                    EngineSessionRegistry.currentGame(run.app, EngineSessionRegistry.HOST_ETHORNELL));
            String logs = run.logs(owner);
            assertFalse("Rejected dispatch reached native session lifecycle", logs.contains("identity_sha256=" + run.hash));
            run.assertResourceUnchanged();
            run.evidence("REVOKED_LATE_INTENT", token, owner,
                    "REVOKED retained; user absent; registry absent; no native lifecycle; actual owner exited");
            run.passed = true;
        }
    }

    @Test public void normalCloseLeavesDurableTerminalEvenWhenParentMissesLifecycle() throws Exception {
        try (Run run = new Run("normal-close")) {
            String token = run.issue();
            Owner owner = run.deliverBehindMetadataLock(token, run.resource);
            // Intentionally do NOT subscribe to Activity lifecycle or poll CLAIMED/currentLiveGame.
            // The main process next reads the record only after /proc proves owner exit.
            run.awaitExit(owner, false);
            EthornellDispatchRegistry.Record terminal = run.assertTerminal(token, owner);
            String logs = run.logs(owner);
            assertNormalClosed(logs, run.hash, owner.pid);
            assertTrue("Successful native create must establish the identity-bound private root", run.user.isDirectory());
            assertNull("Native registry must clear after destroy ACK",
                    EngineSessionRegistry.currentGame(run.app, EngineSessionRegistry.HOST_ETHORNELL));
            assertFalse("TERMINAL alone is not process death", ownerAlive(run.app, owner));
            run.assertResourceUnchanged();
            run.evidence("DURABLE_TERMINAL_AFTER_MISSED_LIFECYCLE", token, owner,
                    "live UID/PID/starttime captured before unlock; TERMINAL preserves owner; Closed->destroy_ack; owner exited");
            // Re-enter through a NEW production dispatch, never by reusing a terminal token.
            String next = run.issue();
            assertNotEquals(token, next);
            assertNull("New issue should prune terminal metadata", EthornellDispatchRegistry.status(run.app.getFilesDir(), token));
            Owner second = run.deliverBehindMetadataLock(next, run.resource);
            run.awaitExit(second, false);
            run.assertTerminal(next, second);
            assertNormalClosed(run.logs(second), run.hash, second.pid);
            run.assertResourceUnchanged();
            run.evidence("NORMAL_REENTRY", next, second, "new token claimed by production owner; normal native close ACK");
            run.passed = true;
        }
    }

    @Test public void missingResourceClaimsThenTerminatesAndAllowsFreshDispatch() throws Exception {
        try (Run run = new Run("missing-resource")) {
            File missing = new File(run.base, "does-not-exist");
            assertFalse(missing.exists());
            String failed = run.issue();
            Owner owner = run.deliverBehindMetadataLock(failed, missing);
            run.awaitExit(owner, true);
            run.assertTerminal(failed, owner);
            assertFalse("Resource validation failure must precede user directory creation", run.user.exists());
            assertNull("No native-create ACK for nonexistent resource",
                    EngineSessionRegistry.currentGame(run.app, EngineSessionRegistry.HOST_ETHORNELL));
            String logs = run.logs(owner);
            assertTrue("Missing resource must report actual failed initialization", lifecycle(logs, "failed", run.hash, owner.pid).find());
            assertFalse("Failed pre-native init is not a native destroy ACK", lifecycle(logs, "destroy_ack", run.hash, owner.pid).find());
            assertFalse("Failed init is not script-generated Closed", lifecycle(logs, "closed_event", run.hash, owner.pid).find());
            run.evidence("CLAIM_THEN_FAILED_INIT_TERMINAL", failed, owner,
                    "TERMINAL has real host owner (thus claim occurred); failed init; no native destroy/Closed claimed");
            String next = run.issue();
            assertNotEquals(failed, next);
            assertNull(EthornellDispatchRegistry.status(run.app.getFilesDir(), failed));
            Owner recovered = run.deliverBehindMetadataLock(next, run.resource);
            run.awaitExit(recovered, false);
            run.assertTerminal(next, recovered);
            assertNormalClosed(run.logs(recovered), run.hash, recovered.pid);
            run.assertResourceUnchanged();
            run.evidence("FRESH_DISPATCH_AFTER_FAILED_INIT", next, recovered, "fresh token normally closed with native destroy ACK");
            run.passed = true;
        }
    }

    private static final class Owner {
        final int pid;
        final String start;
        boolean verified;
        String exitEvidence;
        String lastProbe;
        Owner(int pid, String start) { this.pid = pid; this.start = start; }
    }

    private static final class Run implements AutoCloseable {
        final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String identity, hash;
        final File base, resource, archive, user;
        final byte[] expected;
        final List<String> tokens = new ArrayList<>();
        final List<Owner> owners = new ArrayList<>();
        final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        final ScheduledFuture<?> hardDeadline;
        boolean passed;

        Run(String scenario) throws Exception {
            // Independent of Android/JUnit timeouts: no Binder call, metadata lock, or child
            // enumeration in this callback. Only this instrumentation PID is ever hard-killed.
            hardDeadline = watchdog.schedule(() -> abort("scenario exceeded independent 85s deadline"), 85, TimeUnit.SECONDS);
            try {
                assertEquals(app.getApplicationInfo().uid, Process.myUid());
                ComponentName component = new ComponentName(app.getPackageName(), "com.core.ethornell.EthornellActivity");
                ActivityInfo info = app.getPackageManager().getActivityInfo(component, 0);
                assertFalse("Production host must remain private", info.exported);
                assertEquals(app.getPackageName() + ":ethornell", info.processName);
                assertNull("Refuse existing/stale native registry; do not clean someone else's game",
                        EngineSessionRegistry.currentGame(app, EngineSessionRegistry.HOST_ETHORNELL));
                awaitNoHost(app);
                for (EthornellDispatchRegistry.Record record : EthornellDispatchRegistry.records(app.getFilesDir())) {
                    assertFalse("Another unretired dispatch exists; parent must reconcile it before testing",
                            record.stage == EthornellDispatchRegistry.Stage.ISSUED || record.stage == EthornellDispatchRegistry.Stage.CLAIMED);
                }
                identity = "ethornell-dispatch-device://" + scenario + "/" + UUID.randomUUID();
                hash = sha256(identity.getBytes(StandardCharsets.UTF_8));
                File files = app.getFilesDir().getCanonicalFile();
                base = new File(files, "ethornell-dispatch-fixture-" + UUID.randomUUID());
                resource = new File(base, "resource");
                assertTrue(resource.mkdirs());
                user = new File(new File(files, "ethornell/userdata"), hash);
                assertEquals("Independently derived SHA-256 identity contract", user, EthornellPaths.userDataDir(files, identity));
                assertFalse("Dedicated user root must begin absent", user.exists());
                assertFalse(user.toPath().startsWith(resource.toPath()));
                assertFalse(resource.toPath().startsWith(user.toPath()));
                archive = new File(resource, "system.arc");
                // Original minimal BP: push_byte(1), Sys80:68 native close mode,
                // Sys80:69 request close, ret. No game/font assets or game_path input.
                byte[] code = {0, 1, (byte) 0x80, 0x68, (byte) 0x80, 0x69, 0x17};
                ByteBuffer bp = ByteBuffer.allocate(16 + code.length).order(ByteOrder.LITTLE_ENDIAN);
                bp.putInt(16).putInt(code.length).putInt(0).putInt(0).put(code);
                ByteBuffer pack = ByteBuffer.allocate(48 + bp.capacity()).order(ByteOrder.LITTLE_ENDIAN);
                pack.put("PackFile    ".getBytes(StandardCharsets.US_ASCII)).putInt(1);
                byte[] name = new byte[16];
                System.arraycopy("ipl._bp".getBytes(StandardCharsets.US_ASCII), 0, name, 0, 7);
                pack.put(name).putInt(0).putInt(bp.capacity()).putInt(0).putInt(0).put(bp.array());
                expected = pack.array();
                Files.write(archive.toPath(), expected);
                report("FIXTURE identity_sha256=" + hash + " resource=" + resource + " private_user=" + user
                        + " original_archive_sha256=" + sha256(expected));
            } catch (Exception | Error failure) {
                hardDeadline.cancel(false); watchdog.shutdownNow(); throw failure;
            }
        }

        String issue() throws Exception {
            String token = EthornellDispatchRegistry.issue(app.getFilesDir(), identity);
            tokens.add(token);
            EthornellDispatchRegistry.Record issued = record(token);
            assertEquals(EthornellDispatchRegistry.Stage.ISSUED, issued.stage);
            assertEquals(0, issued.pid); assertEquals("", issued.startTime);
            return token;
        }

        EthornellDispatchRegistry.Record record(String token) throws Exception {
            EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), token);
            assertNotNull("Current token disappeared before next issue", record);
            assertEquals(token, record.token); assertEquals(identity, record.identity);
            return record;
        }

        Owner deliverBehindMetadataLock(String token, File path) throws Exception {
            awaitNoHost(app);
            File lock = new File(app.getFilesDir(), "ethornell_dispatch/metadata.lock");
            try (FileChannel channel = FileChannel.open(lock.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock held = channel.lock()) {
                Intent intent = new Intent().setComponent(new ComponentName(app.getPackageName(), "com.core.ethornell.EthornellActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_ETHORNELL)
                        .putExtra(LaunchContract.ROOT_URI, identity)
                        .putExtra(LaunchContract.PATH, path.getPath())
                        .putExtra(LaunchContract.ETHORNELL_USER_DATA_DIR, user.getPath())
                        .putExtra(LaunchContract.ETHORNELL_DISPATCH_TOKEN, token)
                        .putExtra(LaunchContract.ETHORNELL_NLS, "sjis");
                app.startActivity(intent);
                String lastProbe = "AM has not published a candidate";
                long deadline = SystemClock.elapsedRealtime() + 12000;
                do {
                    int pid = hostPid(app);
                    if (pid > 0) {
                        assertNotEquals("Instrumentation is not a cross-process host", Process.myPid(), pid);
                        try {
                            Owner candidate = new Owner(pid, processStart(pid));
                            OwnerState state = probeOwner(app, candidate);
                            lastProbe = "pid=" + pid + " start=" + candidate.start + " " + candidate.lastProbe;
                            if (state == OwnerState.LIVE) {
                                // Do not bind the test/cleanup to an old AM entry or a zygote
                                // candidate. Only the fully verified incarnation owns delivery.
                                owners.add(candidate);
                                assertNull("OS metadata lock must prevent native-create ACK before claim",
                                        EngineSessionRegistry.currentGame(app, EngineSessionRegistry.HOST_ETHORNELL));
                                report("DELIVERY_OWNER_VERIFIED identity_sha256=" + hash + " dispatch=" + token
                                        + " pid=" + candidate.pid + " start=" + candidate.start + " " + candidate.lastProbe
                                        + " metadata_lock=held");
                                return candidate;
                            }
                        } catch (NoSuchFileException gone) {
                            lastProbe = "AM candidate pid=" + pid + " disappeared before /proc capture";
                        }
                    }
                    SystemClock.sleep(20);
                } while (SystemClock.elapsedRealtime() < deadline);
                throw new AssertionError("No fully initialized production host within delivery deadline: " + lastProbe);
                // Release OS lock; only now may the production Activity claim/reject the record.
                // Never invoke registry APIs while holding this non-registry FileLock.
            }
        }

        void awaitExit(Owner owner, boolean assertNoRegistry) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 20000;
            do {
                if (assertNoRegistry) assertNull("Unexpected native-create registry during rejected/failed delivery",
                        EngineSessionRegistry.currentGame(app, EngineSessionRegistry.HOST_ETHORNELL));
                if (!ownerAlive(app, owner)) {
                    // AM may retain an exited process briefly. Death was already established
                    // from /proc; this bounded drain only makes the next-delivery preflight stable.
                    long drainDeadline = SystemClock.elapsedRealtime() + 3000;
                    while (hostPid(app) == owner.pid && SystemClock.elapsedRealtime() < drainDeadline)
                        SystemClock.sleep(20);
                    evidence("OWNER_EXIT_" + owner.pid, "see-durable-record", owner, owner.exitEvidence);
                    return;
                }
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            fail("Production host failed to exit on its own (including live cached process); pid="
                    + owner.pid + " start=" + owner.start);
        }

        EthornellDispatchRegistry.Record assertTerminal(String token, Owner owner) throws Exception {
            long deadline = SystemClock.elapsedRealtime() + 3000;
            EthornellDispatchRegistry.Record terminal;
            do {
                terminal = record(token);
                if (terminal.stage == EthornellDispatchRegistry.Stage.TERMINAL) break;
                assertNotEquals("Valid delivery was revoked", EthornellDispatchRegistry.Stage.REVOKED, terminal.stage);
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertEquals("Production must persist terminal without parent lifecycle ACK", EthornellDispatchRegistry.Stage.TERMINAL, terminal.stage);
            assertEquals("Terminal owner must be actual delivered host, not instrumentation", owner.pid, terminal.pid);
            assertNotEquals(Process.myPid(), terminal.pid);
            assertEquals(owner.start, terminal.startTime);
            // A TERMINAL owner is evidence of the prior production claim, not direct observation
            // of the transient CLAIMED stage and not by itself a native destroy/Closed proof.
            assertFalse(EthornellDispatchRegistry.revokeUnclaimed(app.getFilesDir(), token, identity));
            return terminal;
        }

        String logs(Owner owner) throws Exception {
            String text = boundedDiagnostic(() -> {
                ParcelFileDescriptor descriptor = automation.executeShellCommand(
                        "logcat -d -v threadtime --pid=" + owner.pid + " -t 2000");
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        assertTrue("Refuse truncated lifecycle evidence >2MiB", output.size() + count <= 2 * 1024 * 1024);
                        output.write(buffer, 0, count);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            });
            Files.write(new File(base, "host-" + owner.pid + ".log").toPath(), text.getBytes(StandardCharsets.UTF_8));
            assertFalse("Host crashed, not protocol completion; see retained host log",
                    text.contains("FATAL EXCEPTION") || text.contains("Fatal signal"));
            return text;
        }

        void assertResourceUnchanged() throws Exception {
            assertArrayEquals(expected, Files.readAllBytes(archive.toPath()));
            assertArrayEquals("Writes leaked to resource root", new String[]{"system.arc"}, resource.list());
        }

        void evidence(String phase, String token, Owner owner, String detail) throws Exception {
            String text = phase + " identity_sha256=" + hash + " dispatch=" + token + " owner_pid=" + owner.pid
                    + " owner_uid=" + Process.myUid() + " owner_start=" + owner.start + " " + detail;
            Files.write(new File(base, phase + ".txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
            report(text);
        }

        @Override public void close() throws Exception {
            List<Throwable> failures = new ArrayList<>();
            try {
                // Capture BEFORE any failure-only kill, including failures in awaitExit/owner
                // probing. Otherwise fast missing-resource/revoked evidence was lost entirely.
                for (Owner owner : owners) {
                    try { logs(owner); }
                    catch (Exception | Error failure) { failures.add(failure); }
                    try {
                        String snapshot;
                        try {
                            ProcStat stat = procStat(owner.pid);
                            snapshot = "observed_start=" + stat.start + " state=" + stat.state;
                        } catch (NoSuchFileException gone) { snapshot = "proc_absent=true"; }
                        Files.write(new File(base, "owner-" + owner.pid + "-before-cleanup.txt").toPath(),
                                ("captured_start=" + owner.start + " previously_verified=" + owner.verified
                                + " " + snapshot + " exit_evidence=" + owner.exitEvidence).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception | Error failure) { failures.add(failure); }
                }
                for (String token : tokens) {
                    try {
                        EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), token);
                        String snapshot = record == null ? "pruned" : "stage=" + record.stage + " identity=" + record.identity
                                + " pid=" + record.pid + " start=" + record.startTime;
                        Files.write(new File(base, token + "-before-cleanup.txt").toPath(),
                                (snapshot + " user_exists=" + user.exists() + " native_registry="
                                + EngineSessionRegistry.currentGame(app, EngineSessionRegistry.HOST_ETHORNELL)).getBytes(StandardCharsets.UTF_8));
                    } catch (Exception | Error failure) { failures.add(failure); }
                }
                // Never manufacture passing TERMINAL by calling finish/retireDeadClaim before
                // assertions. Failure-only kill requires this run's captured process incarnation,
                // matching durable identity/token, and no other identity in the native registry.
                if (!passed) for (Owner owner : owners) {
                    try {
                    String current = EngineSessionRegistry.currentGame(app, EngineSessionRegistry.HOST_ETHORNELL);
                    if (current != null && !identity.equals(current)) continue;
                    boolean ours = false;
                    for (String token : tokens) {
                        EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), token);
                        if (record == null || !identity.equals(record.identity)) continue;
                        if ((record.pid == owner.pid && record.startTime.equals(owner.start))
                                || (record.pid == 0 && (record.stage == EthornellDispatchRegistry.Stage.REVOKED
                                || record.stage == EthornellDispatchRegistry.Stage.ISSUED))) ours = true;
                    }
                    if (ours && owner.pid != Process.myPid() && ownerAlive(app, owner)) {
                        report("FAILURE_ONLY_TEARDOWN pid=" + owner.pid + " start=" + owner.start + "; never PASS evidence");
                        Process.killProcess(owner.pid);
                        awaitExit(owner, false);
                    }
                    } catch (Exception | Error failure) { failures.add(failure); }
                }
                for (String token : tokens) {
                    try {
                    EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), token);
                    if (record == null || !identity.equals(record.identity)) continue;
                    if (record.stage == EthornellDispatchRegistry.Stage.ISSUED)
                        assertTrue(EthornellDispatchRegistry.revokeUnclaimed(app.getFilesDir(), token, identity));
                    else if (!passed && record.stage == EthornellDispatchRegistry.Stage.CLAIMED) {
                        long deadline = SystemClock.elapsedRealtime() + 2000;
                        boolean retired = false;
                        Exception last = null;
                        do {
                            // Production probes fail closed on an exit-race empty cmdline. That
                            // is safe, but cleanup must allow a later definitive /proc sample.
                            try { retired = EthornellDispatchRegistry.retireDeadClaim(app, token); }
                            catch (java.io.IOException ambiguous) { last = ambiguous; }
                            if (retired) break;
                            SystemClock.sleep(20);
                        } while (SystemClock.elapsedRealtime() < deadline);
                        assertTrue("Retain CLAIMED if positive owner-death evidence is unavailable; last_probe=" + last,
                                retired);
                    }
                    } catch (Exception | Error failure) { failures.add(failure); }
                }
                // Terminal/revoked records and tiny private files are retained as evidence. The
                // next issue prunes metadata via production API; never delete global registry/files.
            } finally {
                try { report("DISPATCH fixture/evidence retained=" + base + " passed=" + passed); }
                finally { hardDeadline.cancel(false); watchdog.shutdownNow(); }
            }
            if (!failures.isEmpty()) {
                AssertionError cleanup = new AssertionError("Evidence/cleanup failures (original case failure is retained by try-with-resources)");
                for (Throwable failure : failures) cleanup.addSuppressed(failure);
                throw cleanup;
            }
        }
    }

    private static Matcher lifecycle(String logs, String event, String hash, int pid) {
        return Pattern.compile("(?m)^\\S+\\s+\\S+\\s+" + pid + "\\s+\\d+\\s+I\\s+EthornellLifecycle\\s*:\\s*"
                + event + " identity_sha256=" + hash + " token=(\\d+)(?:\\s|$)").matcher(logs);
    }

    private static void assertNormalClosed(String logs, String hash, int pid) {
        assertFalse("Failure is not normal close", lifecycle(logs, "failed", hash, pid).find());
        assertFalse("Forced teardown is not normal close", lifecycle(logs, "forced_exit", hash, pid).find());
        Matcher closed = lifecycle(logs, "closed_event", hash, pid);
        assertTrue("No real script-generated Closed; process/registry absence is insufficient", closed.find());
        String nativeToken = closed.group(1);
        assertTrue("Invalid native token", Long.parseLong(nativeToken) > 0);
        Matcher ack = lifecycle(logs.substring(closed.end()), "destroy_ack", hash, pid);
        assertTrue("No native destroy ACK after Closed", ack.find());
        assertEquals("Closed and destroy ACK belong to different sessions", nativeToken, ack.group(1));
        Matcher exit = lifecycle(logs.substring(closed.end() + ack.end()), "process_exit", hash, pid);
        assertTrue("No cleanup exit after native ACK", exit.find());
        assertEquals(nativeToken, exit.group(1));
    }

    private static int hostPid(Context app) {
        ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        assertNotNull(manager);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        assertNotNull("AM process evidence unavailable", processes);
        for (ActivityManager.RunningAppProcessInfo process : processes)
            if (process.uid == Process.myUid() && process.processName.equals(app.getPackageName() + ":ethornell")) {
                try {
                    ProcStat stat = procStat(process.pid);
                    // AM can retain a dead process entry. A running/cached S/R/D process is
                    // NEVER ignored; only actual /proc death evidence permits another delivery.
                    if (stat.terminated()) continue;
                } catch (NoSuchFileException gone) { continue; }
                catch (Exception error) { throw new AssertionError("Host /proc evidence unavailable", error); }
                return process.pid;
            }
        return 0;
    }

    private static void awaitNoHost(Context app) {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        int pid;
        do {
            pid = hostPid(app);
            if (pid == 0) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        // No kill, no ignore-cached shortcut. A genuinely live prior owner still blocks testing.
        fail("Existing host did not finish exiting within 3s; refuse to disturb pid=" + pid);
    }

    private static final class ProcStat {
        final String start, state;
        ProcStat(String start, String state) { this.start = start; this.state = state; }
        boolean terminated() { return state.equals("Z") || state.equals("X") || state.equals("x"); }
    }

    private static ProcStat procStat(int pid) throws Exception {
        String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
        int end = stat.lastIndexOf(") ");
        assertTrue("Invalid actual /proc stat", end >= 0);
        String[] fields = stat.substring(end + 2).trim().split("\\s+");
        assertTrue(fields.length > 19 && fields[19].matches("[0-9]{1,24}"));
        assertTrue("Invalid /proc state", fields[0].matches("[A-Za-z]"));
        return new ProcStat(fields[19], fields[0]);
    }

    private static String processStart(int pid) throws Exception {
        return procStat(pid).start;
    }

    private enum OwnerState { LIVE, EXITED, UNKNOWN }

    /** One read-only sample, never treats AM publication/zygote naming as initialized ownership. */
    private static OwnerState probeOwner(Context app, Owner owner) throws Exception {
        try {
            ProcStat before = procStat(owner.pid);
            if (!owner.start.equals(before.start)) {
                owner.exitEvidence = "PID incarnation changed from " + owner.start + " to " + before.start;
                owner.lastProbe = owner.exitEvidence;
                return OwnerState.EXITED;
            }
            if (before.terminated()) {
                owner.exitEvidence = "matching /proc starttime=" + before.start + " terminated state=" + before.state;
                owner.lastProbe = owner.exitEvidence;
                return OwnerState.EXITED;
            }
            String status = new String(Files.readAllBytes(new File("/proc/" + owner.pid + "/status").toPath()), StandardCharsets.UTF_8);
            Matcher uid = Pattern.compile("(?m)^Uid:\\s+(\\d+)").matcher(status);
            String command = new String(Files.readAllBytes(new File("/proc/" + owner.pid + "/cmdline").toPath()), StandardCharsets.UTF_8).split("\u0000", -1)[0];
            ProcStat after = procStat(owner.pid);
            if (!owner.start.equals(after.start)) {
                owner.exitEvidence = "PID incarnation changed during probe to " + after.start;
                owner.lastProbe = owner.exitEvidence;
                return OwnerState.EXITED;
            }
            if (after.terminated()) {
                owner.exitEvidence = "matching /proc starttime=" + after.start + " terminated state=" + after.state
                        + " cmdline_empty=" + command.isEmpty();
                owner.lastProbe = owner.exitEvidence;
                return OwnerState.EXITED;
            }
            String actualUid = uid.find() ? uid.group(1) : "unavailable";
            owner.lastProbe = "state=" + after.state + " uid=" + actualUid + " cmdline=" + command;
            if (command.isEmpty() || command.equals("<pre-initialized>")) {
                // Both zygote startup and kernel exit can expose incomplete cmdline. Neither
                // authenticates the host nor proves death, even if AM already calls it our host.
                return OwnerState.UNKNOWN;
            }
            if (!owner.verified && (!command.equals(app.getPackageName() + ":ethornell")
                    || !actualUid.equals(Integer.toString(Process.myUid())))) return OwnerState.UNKNOWN;
            assertEquals("No actual app UID evidence", Integer.toString(Process.myUid()), actualUid);
            assertEquals("Never accept instrumentation process as cross-process host", app.getPackageName() + ":ethornell", command);
            owner.verified = true;
            return OwnerState.LIVE;
        } catch (NoSuchFileException gone) {
            owner.exitEvidence = "actual /proc owner disappearance";
            owner.lastProbe = owner.exitEvidence;
            return OwnerState.EXITED; // AM-list absence alone never reaches here.
        }
    }

    private static boolean ownerAlive(Context app, Owner owner) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        do {
            OwnerState state = probeOwner(app, owner);
            if (state == OwnerState.LIVE) return true;
            if (state == OwnerState.EXITED) return false;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Ambiguous owner evidence exceeded 2s; pid=" + owner.pid
                + " start=" + owner.start + " last=" + owner.lastProbe);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder(64);
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            text.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return text.toString();
    }

    private static <T> T boundedDiagnostic(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Thread worker = new Thread(task, "Ethornell-dispatch-diagnostics");
        worker.setDaemon(true); worker.start();
        try { return task.get(8, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) {
            task.cancel(true); abort("diagnostics exceeded 8s; parent must inspect child");
            throw new AssertionError("Diagnostic timeout", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        } catch (InterruptedException interrupted) {
            task.cancel(true); Thread.currentThread().interrupt(); throw interrupted;
        }
    }

    private static void report(String text) {
        Log.i(TAG, text);
        Bundle status = new Bundle(); status.putString("stream", "\n" + text + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private static void abort(String reason) {
        Log.e(TAG, "FAIL " + reason + "; kill only instrumentation pid=" + Process.myPid());
        Process.killProcess(Process.myPid());
    }
}
