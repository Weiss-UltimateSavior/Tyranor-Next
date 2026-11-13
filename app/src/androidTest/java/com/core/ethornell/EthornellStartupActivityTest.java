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
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.core.engine.EngineSessionRegistry;
import com.core.engine.LaunchContract;
import com.core.nativeplugin.NativeRuntimePack;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in isolated-package proof of the real Ethornell startup overlay and normal close ACK. */
@RunWith(AndroidJUnit4.class)
public final class EthornellStartupActivityTest {
    private static final long STARTUP_TIMEOUT_MS = 45_000;
    private static final long CLOSE_TIMEOUT_MS = 20_000;

    @Test public void privateHostRevealsOnPixelCopyOutputAndClosesWithAck() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context app = instrumentation.getTargetContext();
        assumeTrue("Dedicated startup acceptance package required",
                "com.tyranor.next.startuptest".equals(app.getPackageName()));
        assumeTrue("Explicit device-test opt-in required",
                "true".equals(InstrumentationRegistry.getArguments().getString("startup_test")));

        assertNull("Refuse to disturb an existing Ethornell game",
                EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
        ComponentName component = new ComponentName(app.getPackageName(), EthornellActivity.class.getName());
        ActivityInfo activityInfo = app.getPackageManager().getActivityInfo(component, 0);
        assertFalse("Production host must remain private", activityInfo.exported);
        assertEquals(app.getPackageName() + ":ethornell", activityInfo.processName);
        assertEquals("Harness must run under the isolated app UID", app.getApplicationInfo().uid, Process.myUid());

        UiAutomation automation = instrumentation.getUiAutomation();
        String identity = "ethornell-startup-render://" + UUID.randomUUID();
        File userRoot = expectedUserRoot(app, identity);
        assertTrue("Private identity-bound user root creation failed", userRoot.mkdirs());
        String dispatch = issueDispatch(app, identity);
        String runtimePath = runtimeLibrary(app);
        String runtimeHash = sha256(Files.readAllBytes(new File(runtimePath).toPath()));
        File evidence = new File(userRoot, "startup-runtimeidentity-report.txt");
        Files.write(evidence.toPath(), ("result=PENDING\npackage=" + app.getPackageName()
                + "\nidentity=" + identity + "\ndispatch=" + dispatch
                + "\nruntime_library=" + runtimePath + "\nruntime_library_sha256=" + runtimeHash
                + "\nabi=" + android.os.Build.SUPPORTED_ABIS[0] + " sdk=" + android.os.Build.VERSION.SDK_INT + "\n")
                .getBytes(StandardCharsets.UTF_8));
        String resourceHash;
        int hostPid = 0;
        EthornellDispatchRegistry.Record claimed = null;
        boolean normalExit = false;

        try (EthornellDeviceTest.Fixture fixture = new EthornellDeviceTest.Fixture("render-close")) {
            assertTrue("Render fixture resource must be private to the target app",
                    fixture.resource.getCanonicalFile().toPath().startsWith(app.getFilesDir().getCanonicalFile().toPath()));
            assertFalse("Resource and user roots must remain disjoint",
                    fixture.resource.getCanonicalFile().toPath().startsWith(userRoot.getCanonicalFile().toPath())
                            || userRoot.getCanonicalFile().toPath().startsWith(fixture.resource.getCanonicalFile().toPath()));
            resourceHash = sha256(Files.readAllBytes(new File(fixture.resource, "system.arc").toPath()));
            appendReport(evidence, "fixture_profile=render-close\n"
                    + "fixture_derived_from_profile=render\n"
                    + "fixture_derived_from_system_arc_sha256=f0d1d96ef30135c95bc722414a9d9572ec3f34952fb57ad9a1bfcf6adb8c06d8\n"
                    + "fixture_resource=" + fixture.resource.getCanonicalPath()
                    + "\nfixture_system_arc_sha256=" + resourceHash + "\n");

            Intent intent = new Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_ETHORNELL)
                    .putExtra(LaunchContract.ROOT_URI, identity)
                    .putExtra(LaunchContract.PATH, fixture.resource.getPath())
                    .putExtra(LaunchContract.ETHORNELL_USER_DATA_DIR, userRoot.getPath())
                    .putExtra(LaunchContract.ETHORNELL_DISPATCH_TOKEN, dispatch)
                    .putExtra(LaunchContract.ETHORNELL_NLS, "sjis");
            app.startActivity(intent);

            long claimDeadline = SystemClock.elapsedRealtime() + 15_000;
            while (SystemClock.elapsedRealtime() < claimDeadline) {
                claimed = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
                if (claimed != null && claimed.stage == EthornellDispatchRegistry.Stage.CLAIMED) break;
                SystemClock.sleep(50);
            }
            assertNotNull("Production host did not claim ETHORNELL_DISPATCH_TOKEN", claimed);
            assertEquals(identity, claimed.identity);
            assertTrue("Expected separate private host process", claimed.pid > 0 && claimed.pid != Process.myPid());
            hostPid = claimed.pid;
            appendReport(evidence, "host_process=" + activityInfo.processName + " pid=" + hostPid
                    + "\ndispatch_stage=" + claimed.stage + "\n");
            long startupDeadline = SystemClock.elapsedRealtime() + STARTUP_TIMEOUT_MS;
            String registeredIdentity = null;
            while (SystemClock.elapsedRealtime() < startupDeadline) {
                registeredIdentity = EngineSessionRegistry.currentLiveGame(
                        app, EngineSessionRegistry.HOST_ETHORNELL);
                if (identity.equals(registeredIdentity)) break;
                assertTrue("A different engine identity occupied the Ethornell registry",
                        registeredIdentity == null || identity.equals(registeredIdentity));

                assertTrue("Private host process exited before native session registration",
                        isHostAlive(app, hostPid));
                EthornellDispatchRegistry.Record current =
                        EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
                assertNotNull("Dispatch vanished while native session was being created", current);
                assertEquals("Dispatch identity changed during native session creation", identity, current.identity);
                assertEquals("Private host failed before native session registration",
                        EthornellDispatchRegistry.Stage.CLAIMED, current.stage);
                assertEquals("Dispatch owner changed during native session creation", hostPid, current.pid);
                SystemClock.sleep(50);
            }
            assertEquals("Native session was not registered before the startup deadline",
                    identity, registeredIdentity);
            assertTrue("Private host process disappeared after native registration", isHostAlive(app, hostPid));
            EthornellDispatchRegistry.Record registeredDispatch =
                    EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
            assertNotNull("Dispatch disappeared after native registration", registeredDispatch);
            assertEquals(EthornellDispatchRegistry.Stage.CLAIMED, registeredDispatch.stage);
            assertEquals(hostPid, registeredDispatch.pid);
            assertEquals("Private user root must be the identity-derived directory", userRoot.getCanonicalFile(),
                    EthornellPaths.userDataDir(app.getFilesDir(), identity).getCanonicalFile());
            assertTrue("Native create must establish private user root", userRoot.isDirectory());

            long surfaceDeadline = startupDeadline;
            Bitmap presented = null;
            while (SystemClock.elapsedRealtime() < surfaceDeadline) {
                assertEquals("Host left foreground/live session during startup", identity,
                        EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
                Bitmap screenshot = automation.takeScreenshot();
                if (screenshot != null && hasRenderFixturePixels(screenshot)) {
                    presented = screenshot;
                    break;
                }
                if (screenshot != null) screenshot.recycle();
                SystemClock.sleep(150);
            }
            assertNotNull("Render fixture's checkerboard never reached the foreground display", presented);
            try (FileOutputStream out = new FileOutputStream(new File(userRoot, "startup-presented.png"))) {
                assertTrue("Startup screenshot encoding failed", presented.compress(Bitmap.CompressFormat.PNG, 100, out));
            } finally {
                presented.recycle();
            }
            assertTrue("Native output was visible but startup title remained exposed",
                    awaitTextAbsent(automation, app.getString(com.core.engine.R.string.engine_starting_game), 2500));
            assertForegroundHost(automation, app, hostPid);
            fixture.assertResourceUnchanged();

            String report = "result=PIXELCOPY_REVEAL_WITH_NATIVE_RENDER_FIXTURE\n"
                    + "package=" + app.getPackageName() + "\n"
                    + "activity=" + EthornellActivity.class.getName() + "\n"
                    + "process=" + activityInfo.processName + " pid=" + hostPid + "\n"
                    + "dispatch=" + dispatch + " stage=" + claimed.stage + " identity=" + identity + "\n"
                    + "private_user_root=" + userRoot.getCanonicalPath() + "\n"
                    + "fixture_resource=" + fixture.resource.getCanonicalPath() + "\n"
                    + "fixture_system_arc_sha256=" + resourceHash + "\n"
                    + "runtime_library=" + runtimePath + "\n"
                    + "runtime_library_sha256=" + runtimeHash + "\n"
                    + "abi=" + android.os.Build.SUPPORTED_ABIS[0] + " sdk=" + android.os.Build.VERSION.SDK_INT + "\n"
                    + "evidence=Production overlay reveals only from EngineSurfaceReadyProbe PixelCopy SUCCESS; screenshot contains four expected fixture colors; foreground window package and CLAIMED owner verified.\n";
            Files.write(evidence.toPath(), report.getBytes(StandardCharsets.UTF_8));

            // Exit through the real host UI. TERMINAL is written only after Closed and destroy ACK.
            assertTrue("Could not reach Ethornell exit confirmation through the host UI",
                    requestNormalClose(instrumentation, automation, app, 5000));
            awaitNormalExit(app, dispatch, identity, hostPid, CLOSE_TIMEOUT_MS);
            normalExit = true;
            assertNull("Session registry must clear after Closed/destroy ACK",
                    EngineSessionRegistry.currentLiveGame(app, EngineSessionRegistry.HOST_ETHORNELL));
            assertFalse("Private process must exit after acknowledged close", isHostAlive(app, hostPid));
            EthornellDispatchRegistry.Record terminal = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
            assertNotNull("Normal dispatch record must be retained as terminal proof", terminal);
            assertEquals(EthornellDispatchRegistry.Stage.TERMINAL, terminal.stage);
            assertEquals(hostPid, terminal.pid);
            Files.write(new File(userRoot, "startup-exit-ack.txt").toPath(),
                    ("dispatch=" + dispatch + " stage=" + terminal.stage + " owner_pid=" + terminal.pid
                            + " owner_starttime=" + terminal.startTime + " host_process_exited=true\n")
                            .getBytes(StandardCharsets.UTF_8));
        } finally {
            if (!normalExit) {
                // Best-effort normal close before test teardown; preserve CLAIMED evidence if
                // the host cannot acknowledge instead of manufacturing a TERMINAL result.
                try {
                    if (isHostAlive(app, hostPid)) {
                        requestNormalClose(instrumentation, automation, app, 2500);
                        awaitNormalExit(app, dispatch, identity, hostPid, 5000);
                    }
                } catch (Exception ignored) {
                    // Failure report and durable dispatch state are more useful than fabricated cleanup.
                }
            }
            try {
                EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
                if (record != null && record.identity.equals(identity)) {
                    if (record.stage == EthornellDispatchRegistry.Stage.ISSUED) {
                        EthornellDispatchRegistry.revokeUnclaimed(app.getFilesDir(), dispatch, identity);
                    } else if (record.stage == EthornellDispatchRegistry.Stage.CLAIMED && !isHostAlive(app, record.pid)) {
                        EthornellDispatchRegistry.retireDeadClaim(app, dispatch);
                    }
                }
            } catch (Exception ignored) {
                // Leave durable dispatch evidence untouched when ownership cannot be proven.
            }
        }
    }

    private static String runtimeLibrary(Context app) {
        assertTrue("Ethornell runtime pack extraction failed",
                NativeRuntimePack.ensure(app, NativeRuntimePack.PACK_ETHORNELL));
        String path = NativeRuntimePack.libraryPath(app, NativeRuntimePack.PACK_ETHORNELL, "libethornell.so");
        assertNotNull("Ethornell runtime library is unavailable", path);
        assertTrue(new File(path).isFile());
        return path;
    }

    private static String issueDispatch(Context app, String identity) throws Exception {
        String token = EthornellDispatchRegistry.issue(app.getFilesDir(), identity);
        EthornellDispatchRegistry.Record issued = EthornellDispatchRegistry.status(app.getFilesDir(), token);
        assertNotNull(issued);
        assertEquals(identity, issued.identity);
        assertEquals(EthornellDispatchRegistry.Stage.ISSUED, issued.stage);
        return token;
    }

    private static File expectedUserRoot(Context app, String identity) throws Exception {
        File root = new File(new File(app.getFilesDir().getCanonicalFile(), "ethornell/userdata"),
                sha256(identity.getBytes(StandardCharsets.UTF_8)));
        assertEquals(root.getCanonicalFile(),
                EthornellPaths.userDataDir(app.getFilesDir(), identity).getCanonicalFile());
        return root;
    }

    private static void awaitNormalExit(Context app, String dispatch, String identity, int pid, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(app.getFilesDir(), dispatch);
            assertNotNull("Dispatch record disappeared before exit ACK", record);
            assertEquals(identity, record.identity);
            if (record.stage == EthornellDispatchRegistry.Stage.TERMINAL && !isHostAlive(app, pid)) {
                assertEquals(pid, record.pid);
                return;
            }
            SystemClock.sleep(50);
        }
        fail("Host did not complete normal Closed/destroy ACK and process exit; dispatch="
                + EthornellDispatchRegistry.status(app.getFilesDir(), dispatch).stage);
    }

    private static boolean isHostAlive(Context app, int pid) {
        if (pid <= 0) return false;
        ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if (process.uid == app.getApplicationInfo().uid && process.pid == pid
                    && process.processName.equals(app.getPackageName() + ":ethornell")) return true;
        return false;
    }

    private static void assertForegroundHost(UiAutomation automation, Context app, int expectedPid) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        assertNotNull("No active foreground window after render", root);
        try {
            assertEquals("The native host must own the foreground window", app.getPackageName(),
                    String.valueOf(root.getPackageName()));
        } finally { root.recycle(); }
        assertTrue("Dispatched private host process is no longer live", isHostAlive(app, expectedPid));
    }

    private static boolean awaitTextAbsent(UiAutomation automation, String text, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            boolean present = false;
            if (root != null) {
                try { present = containsText(root, text); }
                finally { root.recycle(); }
            }
            if (!present) return true;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static boolean clickText(UiAutomation automation, String text, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                try {
                    if (clickExactText(root, text)) return true;
                } finally { root.recycle(); }
            }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static boolean requestNormalClose(Instrumentation instrumentation, UiAutomation automation,
                                              Context app, long timeoutMs) {
        String exit = app.getString(com.core.engine.R.string.engine_exit_game);
        String confirm = app.getString(com.core.engine.R.string.engine_ok);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        boolean menuOpened = clickText(automation, exit, 250);
        if (!menuOpened) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            menuOpened = clickText(automation, exit,
                    Math.max(250, Math.min(2500, deadline - SystemClock.elapsedRealtime())));
        }
        if (!menuOpened) return false;
        return clickText(automation, confirm,
                Math.max(250, deadline - SystemClock.elapsedRealtime()));
    }

    private static boolean clickExactText(AccessibilityNodeInfo node, String text) {
        CharSequence label = node.getText();
        if (node.isClickable() && label != null && text.contentEquals(label))
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (clickExactText(child, text)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private static boolean containsText(AccessibilityNodeInfo node, String text) {
        CharSequence label = node.getText();
        if (label != null && text.contentEquals(label)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (containsText(child, text)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private static boolean hasRenderFixturePixels(Bitmap screenshot) {
        if (screenshot.getWidth() <= 0 || screenshot.getHeight() <= 0) return false;
        float scale = Math.min(screenshot.getWidth() / 1280f, screenshot.getHeight() / 720f);
        float offsetX = (screenshot.getWidth() - 1280f * scale) / 2f;
        float offsetY = (screenshot.getHeight() - 720f * scale) / 2f;
        int[][] probes = {{576, 296, 0xffff0000}, {704, 296, 0xff00ff00},
                {576, 424, 0xff0000ff}, {704, 424, 0xffffffff}};
        for (int[] probe : probes) {
            int x = Math.max(0, Math.min(screenshot.getWidth() - 1, Math.round(offsetX + probe[0] * scale)));
            int y = Math.max(0, Math.min(screenshot.getHeight() - 1, Math.round(offsetY + probe[1] * scale)));
            if (!nearColor(screenshot.getPixel(x, y), probe[2], 48)) return false;
        }
        return true;
    }

    private static boolean nearColor(int actual, int expected, int tolerance) {
        return Math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) <= tolerance
                && Math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected)) <= tolerance
                && Math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected)) <= tolerance;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return result.toString();
    }

    private static void appendReport(File report, String text) throws Exception {
        Files.write(report.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }
}
