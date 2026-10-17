package com.core.ethornell;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;

/** Private cross-process Activity is started by instrumented app UID, never by adb am start. */
@RunWith(AndroidJUnit4.class)
public final class EthornellPrivateActivityTest {
    private static final Object AUTOMATION_LOCK = new Object();
    private static UiAutomation automation;

    @BeforeClass public static void connectDiagnosticsOnce() throws Exception {
        diagnosticCall("UiAutomation initial connection", () -> {
            synchronized (AUTOMATION_LOCK) {
                if (automation == null) {
                // Diagnostics and screenshot do not need an accessibility service. In particular,
                // do not repeatedly getUiAutomation(): that can attempt to reconnect/register on
                // affected systems while the previous accessibility registration still exists.
                automation = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
                    assertNotNull("UiAutomation diagnostics connection unavailable", automation);
                }
            }
            return null;
        });
        // Runner owns this connection's lifetime; never disconnect or change its flags per command.
    }

    @Test public void suppliedGamePrivateActivityLaunch() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String path = arguments.getString("game_path");
        assumeTrue("Optional: supply game_path for real private Activity launch", path != null && !path.isEmpty());
        String encoding = arguments.getString("encoding", "sjis");
        assertTrue("encoding must be sjis or gbk", encoding.equals("sjis") || encoding.equals("gbk"));
        int seconds = Integer.parseInt(arguments.getString("observe_seconds", "8"));
        assertTrue("observe_seconds must be 3..45", seconds >= 3 && seconds <= 45);
        String keepArgument = arguments.getString("keep_host_for_manual", "false");
        assertTrue("keep_host_for_manual must be true or false", keepArgument.equals("true") || keepArgument.equals("false"));
        boolean keepForManual = keepArgument.equals("true");
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        String pkg = app.getPackageName();
        ComponentName component = new ComponentName(pkg, EthornellActivity.class.getName());
        ActivityInfo info = app.getPackageManager().getActivityInfo(component, 0);
        assertFalse("Never export the production host for testing", info.exported);
        assertEquals(pkg + ":ethornell", info.processName);
        assertNull("Refuse to disturb an existing engine session",
                EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
        assertEquals("Instrumentation must execute with app UID", app.getApplicationInfo().uid, Process.myUid());

        File game = new File(path).getCanonicalFile();
        assertTrue("game_path not readable (check appops/scoped storage)", game.isAbsolute() && game.isDirectory() && game.canRead());
        String identity = "ethornell-instrumentation://" + UUID.randomUUID();
        File user = expectedUserRoot(app, identity);
        assertTrue(user.mkdirs());
        String dispatch = issueDispatch(app, identity);
        Intent intent = new Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_ETHORNELL)
                .putExtra(LaunchContract.ROOT_URI, identity)
                .putExtra(LaunchContract.PATH, game.getPath())
                .putExtra(LaunchContract.ETHORNELL_USER_DATA_DIR, user.getPath())
                .putExtra(LaunchContract.ETHORNELL_DISPATCH_TOKEN, dispatch)
                .putExtra(LaunchContract.ETHORNELL_NLS, encoding);
        ScheduledExecutorService supervisor = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> hardDeadline = supervisor.schedule(() -> {
            abortHungHarness("Activity harness hard timeout");
        }, 90, TimeUnit.SECONDS); // Includes bounded failure evidence collection before teardown.
        int hostPid = 0;
        Throwable primaryFailure = null;
        boolean evidenceAttempted = false;
        boolean manualHandoff = false;
        try {
            // Application context belongs to the target UID, not the shell/test APK UID.
            app.startActivity(intent);
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (!identity.equals(EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL))
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertEquals("No production native-create ACK registry entry", identity,
                    EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
            hostPid = ownHostPid(app);
            assertTrue("Expected isolated process not found", hostPid > 0 && hostPid != Process.myPid());
            assertDispatch(app, dispatch, identity, hostPid, EthornellDispatchRegistry.Stage.CLAIMED);
            String activities = shell("dumpsys activity activities");
            assertTrue("Private Activity not present in ActivityManager", activities.contains(EthornellActivity.class.getName()));
            Log.i(EthornellDeviceTest.TAG, "ACTIVITY create ACK pid=" + hostPid + " private=true encoding=" + encoding);
            long observeUntil = SystemClock.elapsedRealtime() + seconds * 1000L;
            while (SystemClock.elapsedRealtime() < observeUntil) {
                assertEquals("Production session exited/crashed during observation", identity,
                        EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
                SystemClock.sleep(100);
            }
            evidenceAttempted = true;
            Evidence evidence = collectActivityEvidence(app, user, game, identity, dispatch, hostPid, "end");
            String logs = evidence.logs;
            assertTrue("End-of-observation evidence unavailable: " + evidence.failures, evidence.failures.isEmpty());
            assertFalse("Production process crash; see activity-logcat.txt", logs.contains("FATAL EXCEPTION") || logs.contains("Fatal signal"));
            Log.i(EthornellDeviceTest.TAG, "ACTIVITY observed " + seconds
                    + "s (create ACK/liveness only; screenshot requires human visual review)");
            assertFalse("Host GPU/VM error in log; see activity-logcat.txt",
                    logs.contains("GPU device lost") || logs.contains("VM stopped:") || logs.contains("panicked at"));
            if (keepForManual) {
                assertEquals("Host exited before manual handoff", identity,
                        EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
                assertDispatch(app, dispatch, identity, hostPid, EthornellDispatchRegistry.Stage.CLAIMED);
                String handoff = "MANUAL_HANDOFF host_pid=" + hostPid + " identity=" + identity
                        + " private_user_root=" + user + " dispatch=" + dispatch
                        + " survival_after_runner=NOT_GUARANTEED (default Android instrumentation finish force-stops target package)"
                        + " auto_acceptance=NONE (title/newgame/save/load/confirmation require manual review)";
                Files.write(new File(user, "manual-handoff.txt").toPath(), handoff.getBytes(StandardCharsets.UTF_8));
                runnerDiagnostic(handoff);
                manualHandoff = true;
            }
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            if (!evidenceAttempted) {
                Evidence evidence = collectActivityEvidence(app, user, game, identity, dispatch, hostPid, "failure");
                for (Throwable error : evidence.failures) failure.addSuppressed(error);
            }
            throw failure;
        } finally {
            // This is deterministic test teardown, NOT proof of graceful WM_CLOSE/save behavior.
            // Production has no instrumentation endpoint. Validate exact UID/process + this
            // run's registry identity before killing only the isolated child (never main app).
            try {
                // Opting out of OUR teardown cannot override ActivityManager's default
                // force-stop of the target package when instrumentation finishes.
                if (!manualHandoff) {
                    killOwnHost(app, identity);
                    retireFailedTestDispatch(app, dispatch, identity);
                }
            } catch (Exception | Error cleanup) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup);
                else throw cleanup;
            } finally {
                hardDeadline.cancel(false);
                supervisor.shutdownNow();
                Log.i(EthornellDeviceTest.TAG, "ACTIVITY test data retained at " + user + "; game_path never removed");
            }
        }
    }

    private static final class Evidence {
        String logs = "";
        final List<Throwable> failures = new ArrayList<>();
    }

    /** Best effort BEFORE teardown, including an already-dead host. Original failure wins. */
    private static Evidence collectActivityEvidence(Context app, File user, File game, String identity,
                                                    String dispatch, int knownPid, String phase) {
        Evidence evidence = new Evidence();
        int pid = knownPid;
        if (pid <= 0) {
            try {
                EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
                if (record != null && identity.equals(record.identity)) pid = record.pid;
            } catch (Exception | Error error) { evidence.failures.add(error); }
        }
        final int capturedPid = pid;
        try {
            assertTrue("No recorded host pid for log capture", capturedPid > 0 && capturedPid != Process.myPid());
            evidence.logs = shell("logcat -d -v threadtime --pid=" + capturedPid + " -t 2000", false);
            try (FileOutputStream out = new FileOutputStream(new File(user, "activity-logcat.txt"))) {
                out.write(evidence.logs.getBytes(StandardCharsets.UTF_8));
            }
            int emitted = 0;
            for (String line : evidence.logs.split("\n")) {
                if (!(line.contains(" E ") || line.contains(" W ") || line.contains("EthornellLifecycle"))) continue;
                if (emitted++ >= 100) { runnerDiagnostic("HOST further lines omitted; full private activity-logcat.txt retained"); break; }
                String redacted = line.replace(game.getPath(), "<game-root>").replace(user.getPath(), "<test-user-root>")
                        .replaceAll("(?:/[^\\s:;\\\"<>]+)+", "<path>");
                runnerDiagnostic("HOST " + redacted.substring(0, Math.min(1600, redacted.length())));
            }
        } catch (Exception | Error error) { evidence.failures.add(error); }
        try {
            diagnosticCall("capture " + phase + " screenshot", () -> {
                Bitmap screenshot;
                synchronized (AUTOMATION_LOCK) { screenshot = automation.takeScreenshot(); }
                assertNotNull("Screenshot unavailable", screenshot);
                try (FileOutputStream out = new FileOutputStream(new File(user, "activity.png"))) {
                    assertTrue("PNG encode failed", screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
                } finally { screenshot.recycle(); }
                return null;
            }, false);
        } catch (Exception | Error error) { evidence.failures.add(error); }
        try {
            String summary = "phase=" + phase + " host_pid=" + capturedPid + " capture_failures=" + evidence.failures;
            Files.write(new File(user, "activity-evidence.txt").toPath(), summary.getBytes(StandardCharsets.UTF_8));
            runnerDiagnostic("EVIDENCE phase=" + phase + " host_pid=" + capturedPid
                    + " capture_failures=" + evidence.failures.size() + " files=" + user);
        } catch (Exception | Error error) { evidence.failures.add(error); }
        return evidence;
    }

    private static void runnerDiagnostic(String text) {
        Log.w(EthornellDeviceTest.TAG, text);
        Bundle status = new Bundle(); status.putString("stream", "\n" + text + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    /** Genuine Closed/destroy ACK/process exit twice; no test kill between launches. */
    @Test public void closeFixtureNormallyAndReenterAfterAcknowledgedExit() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        assertNull("Refuse to disturb an existing game",
                EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
        String identity = "ethornell-close-reentry://" + UUID.randomUUID();
        String hash = identityHash(identity);
        File user = expectedUserRoot(app, identity);
        assertTrue(user.mkdirs());
        ComponentName component = new ComponentName(app.getPackageName(), EthornellActivity.class.getName());
        ActivityInfo info = app.getPackageManager().getActivityInfo(component, 0);
        assertFalse(info.exported);
        assertEquals(app.getPackageName() + ":ethornell", info.processName);
        assertEquals(app.getApplicationInfo().uid, Process.myUid());
        ScheduledExecutorService supervisor = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = supervisor.schedule(() -> {
            abortHungHarness("normal-close/reentry exceeded 65s");
        }, 65, TimeUnit.SECONDS);
        boolean passed = false;
        int firstPid = 0;
        String previousDispatch = null;
        String activeDispatch = null;
        try (EthornellDeviceTest.Fixture fixture = new EthornellDeviceTest.Fixture(true)) {
            for (int round = 1; round <= 2; round++) {
                activeDispatch = issueDispatch(app, identity);
                if (previousDispatch != null) assertNull("issue must prune the previous TERMINAL record",
                        EthornellDispatchRegistry.status(app.getFilesDir(), previousDispatch));
                Intent intent = new Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_ETHORNELL)
                        .putExtra(LaunchContract.ROOT_URI, identity)
                        .putExtra(LaunchContract.PATH, fixture.resource.getPath())
                        .putExtra(LaunchContract.ETHORNELL_USER_DATA_DIR, user.getPath())
                        .putExtra(LaunchContract.ETHORNELL_DISPATCH_TOKEN, activeDispatch)
                        .putExtra(LaunchContract.ETHORNELL_NLS, "sjis");
                app.startActivity(intent);
                int pid = 0;
                long token = 0;
                String logs = "";
                EthornellDispatchRegistry.Record claimed = null;
                long deadline = SystemClock.elapsedRealtime() + 20000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), activeDispatch);
                    assertNotNull("Current dispatch vanished before another issue", record);
                    assertEquals(identity, record.identity);
                    assertFalse("Normal dispatch was revoked", record.stage == EthornellDispatchRegistry.Stage.REVOKED);
                    if (record.stage == EthornellDispatchRegistry.Stage.CLAIMED) claimed = record;
                    // Normal host exit may finish before ActivityManager polling sees its PID.
                    // Retained real logcat records carry the pid even after process death.
                    logs = shell("logcat -d -v threadtime -s EthornellLifecycle:I '*:S'");
                    long[] completed = completedSession(logs, hash, round == 1 ? 0 : firstPid);
                    if (completed != null) { pid = (int) completed[0]; token = completed[1]; break; }
                    SystemClock.sleep(75);
                }
                File report = new File(user, "close-reentry-round-" + round + ".log");
                try (FileOutputStream out = new FileOutputStream(report)) {
                    out.write(logs.getBytes(StandardCharsets.UTF_8));
                }
                assertTrue("Missing ordered real Closed/destroy ACK diagnostics, round=" + round
                        + "; registry disappearance alone is insufficient; see " + report, token > 0);
                assertTrue("Missing isolated host pid", pid > 0 && pid != Process.myPid());
                EthornellDispatchRegistry.Record terminal = awaitTerminalDispatch(app, activeDispatch, identity, pid);
                if (claimed != null) {
                    assertEquals("Terminal owner differs from claimed owner", claimed.pid, terminal.pid);
                    assertEquals("Terminal starttime differs from claim", claimed.startTime, terminal.startTime);
                }
                if (round == 1) firstPid = pid;
                else assertNotEquals("Second launch reused exited process pid", firstPid, pid);
                long clearDeadline = SystemClock.elapsedRealtime() + 3000;
                while (EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL) != null
                        && SystemClock.elapsedRealtime() < clearDeadline) SystemClock.sleep(50);
                assertNull("Registry must clear only after destroy ACK",
                        EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
                long exitDeadline = SystemClock.elapsedRealtime() + 5000;
                while (ownHostPid(app) != 0 && SystemClock.elapsedRealtime() < exitDeadline) SystemClock.sleep(50);
                assertEquals("Host must terminate itself after acknowledged close", 0, ownHostPid(app));
                assertFalse("TERMINAL owner must have actually exited", EthornellDispatchRegistry.isOwnerAlive(app, terminal));
                fixture.assertResourceUnchanged();
                Log.i(EthornellDeviceTest.TAG, "PASS normal-close round=" + round + " pid=" + pid
                        + " token=" + token + " identity_sha256=" + hash + " log=" + report);
                previousDispatch = activeDispatch;
            }
            passed = true;
        } finally {
            // No test-initiated kill on success or between rounds: production must exit only
            // after Closed + destroy ACK. Failure/timeout may use emergency cleanup.
            if (!passed) {
                killOwnHost(app, identity);
                if (activeDispatch != null) retireFailedTestDispatch(app, activeDispatch, identity);
            }
            timeout.cancel(false);
            supervisor.shutdownNow();
        }
    }

    private static String issueDispatch(Context app, String identity) throws Exception {
        String dispatch = EthornellDispatchRegistry.issue(app.getFilesDir(), identity);
        EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
        assertNotNull("issue did not persist dispatch", record);
        assertEquals(identity, record.identity);
        assertEquals(EthornellDispatchRegistry.Stage.ISSUED, record.stage);
        assertEquals(0, record.pid);
        assertEquals("", record.startTime);
        return dispatch;
    }

    private static void assertDispatch(Context app, String dispatch, String identity, int pid,
                                       EthornellDispatchRegistry.Stage stage) throws Exception {
        EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
        assertNotNull("Dispatch record missing", record);
        assertEquals(identity, record.identity);
        assertEquals(stage, record.stage);
        assertEquals(pid, record.pid);
        assertTrue("Owner starttime missing", record.startTime.matches("[0-9]{1,24}"));
    }

    private static EthornellDispatchRegistry.Record awaitTerminalDispatch(Context app, String dispatch,
                                                                          String identity, int pid) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        EthornellDispatchRegistry.Record record;
        do {
            record = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
            assertNotNull("Dispatch disappeared before prune", record);
            if (record.stage == EthornellDispatchRegistry.Stage.TERMINAL) break;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertDispatch(app, dispatch, identity, pid, EthornellDispatchRegistry.Stage.TERMINAL);
        Log.i(EthornellDeviceTest.TAG, "DISPATCH TERMINAL owner_pid=" + record.pid + " starttime=" + record.startTime);
        return record;
    }

    private static void retireFailedTestDispatch(Context app, String dispatch, String identity) {
        try {
            EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
            if (record == null || !identity.equals(record.identity)) return;
            if (record.stage == EthornellDispatchRegistry.Stage.ISSUED) {
                EthornellDispatchRegistry.revokeUnclaimed(app.getFilesDir(), dispatch, identity);
            } else if (record.stage == EthornellDispatchRegistry.Stage.CLAIMED) {
                // Test emergency teardown only; production metadata still requires positive
                // owner-death evidence. Do not call finish to fabricate a destroy ACK.
                long deadline = SystemClock.elapsedRealtime() + 2000;
                while (EthornellDispatchRegistry.isOwnerAlive(app, record) && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(25);
                if (!EthornellDispatchRegistry.retireDeadClaim(app, dispatch))
                    Log.e(EthornellDeviceTest.TAG, "Dispatch still live after failed-test teardown; retained CLAIMED");
            }
        } catch (Exception error) {
            // Never hide the original test failure or fabricate terminal when evidence is unavailable.
            Log.e(EthornellDeviceTest.TAG, "Failed-test dispatch metadata retained", error);
        }
    }

    private static long[] completedSession(String logs, String hash, int previousPid) {
        String identity = "identity_sha256=" + hash + " token=";
        assertFalse("Production host reported failure, not a normal close: " + logs,
                logs.contains("failed " + identity));
        String prefix = "(?m)^\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+I\\s+EthornellLifecycle\\s*:\\s*";
        Pattern closed = Pattern.compile(prefix + "closed_event " + identity + "(\\d+)(?:\\s|$)");
        Matcher matcher = closed.matcher(logs);
        while (matcher.find()) {
            int pid = Integer.parseInt(matcher.group(1));
            long token = Long.parseLong(matcher.group(2));
            if (pid <= 0 || pid == previousPid || token <= 0) continue;
            String suffix = logs.substring(matcher.end());
            Matcher ack = Pattern.compile(prefix + "destroy_ack " + identity + token + "(?:\\s|$)").matcher(suffix);
            while (ack.find()) if (Integer.parseInt(ack.group(1)) == pid) return new long[]{pid, token};
        }
        return null;
    }

    private static String identityHash(String identity) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return hex.toString();
    }

    private static File expectedUserRoot(Context app, String identity) throws Exception {
        // Independently derive the real security contract; never ask the production host to
        // accept an arbitrary instrumentation root or relax its identity/path check.
        File expected = new File(new File(app.getFilesDir().getCanonicalFile(), "ethornell/userdata"), identityHash(identity));
        assertEquals("Shared path facade disagrees with independent SHA256 contract", expected,
                EthornellPaths.userDataDir(app.getFilesDir(), identity));
        return expected;
    }

    private static int ownHostPid(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.uid == Process.myUid() && process.processName.equals(context.getPackageName() + ":ethornell")) return process.pid;
        }
        return 0;
    }

    private static void killOwnHost(Context context, String identity) {
        String current = EngineSessionRegistry.currentLiveGame(context, EngineSessionRegistry.HOST_ETHORNELL);
        // A cached idle process may be reused, but never kill a different live game.
        if (current != null && !identity.equals(current)) return;
        int pid = ownHostPid(context);
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
    }

    private static String shell(String fixedCommand) throws Exception {
        return shell(fixedCommand, true);
    }

    private static String shell(String fixedCommand, boolean abortOnTimeout) throws Exception {
        // Only fixed diagnostics plus a validated numeric pid; never interpolate game paths.
        return diagnosticCall("shell " + fixedCommand, () -> {
            synchronized (AUTOMATION_LOCK) {
                assertNotNull("Diagnostics must initialize before launching host", automation);
                // AutoCloseInputStream owns the descriptor; do not close it a second time.
                ParcelFileDescriptor descriptor = automation.executeShellCommand(fixedCommand);
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192];
                    int length;
                    while ((length = input.read(bytes)) != -1) {
                        if (output.size() + length > 2 * 1024 * 1024)
                            throw new AssertionError("Diagnostic output exceeds 2MiB; refusing truncated lifecycle evidence");
                        output.write(bytes, 0, length);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            }
        }, abortOnTimeout);
    }

    private static <T> T diagnosticCall(String label, Callable<T> action) throws Exception {
        return diagnosticCall(label, action, true);
    }

    private static <T> T diagnosticCall(String label, Callable<T> action, boolean abortOnTimeout) throws Exception {
        Log.i(EthornellDeviceTest.TAG, "DIAGNOSTIC begin " + label);
        FutureTask<T> task = new FutureTask<>(action);
        Thread worker = new Thread(task, "Ethornell-diagnostic-" + label);
        worker.setDaemon(true);
        worker.start();
        try {
            T result = task.get(8, TimeUnit.SECONDS);
            Log.i(EthornellDeviceTest.TAG, "DIAGNOSTIC end " + label);
            return result;
        } catch (TimeoutException timeout) {
            // Binder/pipe IO cannot reliably be interrupted or closed from the blocked worker.
            // Do not wait on its lock, call another Binder service, or perform metadata cleanup.
            task.cancel(true);
            if (abortOnTimeout) abortHungHarness("diagnostic exceeded 8s: " + label);
            else Log.e(EthornellDeviceTest.TAG, "Evidence capture exceeded 8s; preserving original test failure: " + label);
            throw new AssertionError("Diagnostic timed out: " + label, timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError("Diagnostic failed: " + label, cause);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void abortHungHarness(String reason) {
        Log.e(EthornellDeviceTest.TAG, "FAIL " + reason + "; kill instrumentation pid=" + Process.myPid()
                + "; parent must inspect/clean any isolated child");
        // Best-effort stack collection must not delay the actual kill deadline.
        Thread dump = new Thread(() -> {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Log.e(EthornellDeviceTest.TAG, "THREAD " + entry.getKey().getName() + " " + entry.getKey().getState());
                for (StackTraceElement frame : entry.getValue()) Log.e(EthornellDeviceTest.TAG, "  at " + frame);
            }
        }, "Ethornell-timeout-stack-dump");
        dump.setDaemon(true); dump.start();
        Process.killProcess(Process.myPid());
    }
}
