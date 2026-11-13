package com.core.engine;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.app.UiAutomation;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.core.rpgmaker.RpgMakerActivity;
import com.core.tyrano.TyranoActivity;
import com.tyranor.next.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Starts both private Web hosts with original local fixtures; run only in the isolated test APK. */
@RunWith(AndroidJUnit4.class)
public final class EngineStartupWebDeviceTest {
    private static final String TEST_APPLICATION_ID = "com.tyranor.next.startuptest";
    private static final long WAIT_MILLIS = 20_000L;

    @Test public void tyranoFirstPageAndReloadDoNotShowStartupFailureOrMaskAgain() throws Exception {
        Context context = requireIsolatedTarget();
        String token = "TYRANO_STARTUP_" + UUID.randomUUID().toString().replace("-", "");
        File fixture = createFixture(context, "tyrano", token, false, Scenario.NORMAL);
        runWebHost(context, fixture, token, TyranoActivity.class, "tyrano", Scenario.NORMAL);
    }

    @Test public void rpgMakerFirstPageAndReloadDoNotShowStartupFailureOrMaskAgain() throws Exception {
        Context context = requireIsolatedTarget();
        String token = "RPG_STARTUP_" + UUID.randomUUID().toString().replace("-", "");
        File fixture = createFixture(context, "rpgmaker", token, true, Scenario.NORMAL);
        runWebHost(context, fixture, token, RpgMakerActivity.class, "rpgmaker", Scenario.NORMAL);
    }

    @Test public void emptyStartupRevealsAndConsumedNavigationOrHttpFailureStaysFailed() throws Exception {
        Context context = requireIsolatedTarget();
        String token = "EMPTY_TYRANO_" + UUID.randomUUID().toString().replace("-", "");
        File fixture = createFixture(context, "tyrano", token, false, Scenario.EMPTY);
        runWebHost(context, fixture, token, TyranoActivity.class, "tyrano", Scenario.EMPTY);

        token = "CONSUMED_RPG_" + UUID.randomUUID().toString().replace("-", "");
        fixture = createFixture(context, "rpgmaker", token, true, Scenario.CONSUMED_SCHEME);
        runWebHost(context, fixture, token, RpgMakerActivity.class, "rpgmaker", Scenario.CONSUMED_SCHEME);

        token = "ERROR_RPG_" + UUID.randomUUID().toString().replace("-", "");
        fixture = createFixture(context, "rpgmaker", token, true, Scenario.MAIN_FRAME_ERROR);
        runWebHost(context, fixture, token, RpgMakerActivity.class, "rpgmaker", Scenario.MAIN_FRAME_ERROR);
    }

    private Context requireIsolatedTarget() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Dedicated startup acceptance package required",
                TEST_APPLICATION_ID.equals(context.getPackageName()));
        assumeTrue("Explicit startup device-test opt-in required",
                "true".equals(InstrumentationRegistry.getArguments().getString("startup_test")));
        return context;
    }

    private File createFixture(
            Context context, String name, String token, boolean rpgMaker, Scenario scenario
    ) throws Exception {
        File directory = new File(context.getCacheDir(), "engine_startup_" + name + "_" + UUID.randomUUID());
        assertTrue("Could not create isolated fixture directory", directory.mkdirs());
        if (scenario == Scenario.EMPTY) {
            write(new File(directory, "index.html"), "");
            return directory;
        }
        String counter = "<script>(function(){var k='" + token + "_reload';"
                + "var n=+(sessionStorage.getItem(k)||0)+1;sessionStorage.setItem(k,String(n));"
                + "document.getElementById('counter').textContent='" + token + "_COUNT_'+n;"
                + "document.getElementById('reloadBadge').style.backgroundColor=n===1?'#e53935':'#43a047';"
                + "if(n===1)setTimeout(function(){location.reload();},4000);})();</script>";
        String mvScripts = rpgMaker
                ? "<script src='js/libs/pixi.js'></script>"
                        + "<script src='js/libs/pixi-tilemap.js'></script>"
                        + "<script src='js/libs/pixi-picture.js'></script>"
                        + "<script src='js/libs/iphone-inline-video.browser.js'></script>"
                        + "<script src='js/rpg_core.js'></script>"
                        + "<script src='js/rpg_managers.js'></script>"
                        + "<script src='js/rpg_objects.js'></script>"
                        + "<script src='js/rpg_scenes.js'></script>"
                        + "<script src='js/rpg_sprites.js'></script>"
                        + "<script src='js/rpg_windows.js'></script>"
                : "";
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + (scenario == Scenario.CONSUMED_SCHEME
                        ? "<script>window.location.replace('startup-acceptance://consumed');</script>"
                        : scenario == Scenario.MAIN_FRAME_ERROR
                                ? "<script>window.location.replace('/missing-startup-main-frame.html');</script>"
                                : "")
                + (rpgMaker ? "<title>Original MV startup fixture</title>" + mvScripts : "")
                + "</head><body style='margin:0;background:#fff;color:#111;font:24px sans-serif'>"
                + (rpgMaker ? "<div id='gameScreen' style='position:relative;width:100vw;height:100vh'>"
                        + "<canvas id='gameCanvas' width='816' height='624'></canvas></div>"
                        + "<main id='startupFixture' style='position:fixed;z-index:2147483647;left:8px;top:8px;"
                        + "background:white;color:#111;pointer-events:none'>"
                        + "<p id='fixture'>" + token + "</p><p id='counter'>COUNT_PENDING</p></main>"
                        : "<main id='startupFixture'><p id='fixture'>" + token + "</p>"
                                + "<p id='counter'>COUNT_PENDING</p></main>")
                + "<img src='missing-startup-child.png' alt='child-resource-probe'>"
                + "<div id='reloadBadge' style='position:fixed;z-index:2147483647;left:5vw;top:15vh;"
                + "width:40vw;height:50vh;background:#e53935;color:white;pointer-events:none'></div>"
                + counter + "</body></html>";
        write(new File(directory, "index.html"), html);
        return directory;
    }

    private void runWebHost(
            Context context,
            File fixture,
            String token,
            Class<?> hostClass,
            String processSuffix,
            Scenario scenario
    ) throws Exception {
        String gameUri = android.net.Uri.fromFile(fixture).toString();
        Intent host = new Intent(context, hostClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LaunchContract.PATH, fixture.getAbsolutePath())
                .putExtra(LaunchContract.GAME_PATH, fixture.getAbsolutePath())
                .putExtra(LaunchContract.PROJECT_ROOT, fixture.getAbsolutePath())
                .putExtra(LaunchContract.GAME_DIR, fixture.getAbsolutePath())
                .putExtra(LaunchContract.ROOT_URI, gameUri)
                .putExtra(LaunchContract.TYPE, hostClass == RpgMakerActivity.class ? "RPG" : "WebOther")
                .putExtra(LaunchContract.UI_LANGUAGE_TAG, "en")
                .putExtra(LaunchContract.RPG_MAKER_MOD_ENABLED, false)
                .putExtra(LaunchContract.RPG_MAKER_MOD_GAME_ID, gameUri);
        if (hostClass == RpgMakerActivity.class) {
            // This is the production-selected standalone MV v1 host path. The fixture exercises
            // host injection and WebView lifecycle; it is not a bootable commercial game.
            host.putExtra(LaunchContract.RPG_MAKER_VERSION, "v1");
        }

        try {
            context.startActivity(host);
            waitFor("host process foreground", () -> isProcessForeground(context, processSuffix));
            if (scenario == Scenario.NORMAL) {
                waitForText(token);
                waitForText(token + "_COUNT_1");
                waitForColorBadge(true);
                waitForTextAbsentStable("Starting game", 500L);
                assertTextAbsent("Failed to start");

                waitForColorBadge(false);
                waitForTextAbsentStable("Starting game", 500L);
                assertTextAbsent("Failed to start");
            } else if (scenario == Scenario.MAIN_FRAME_ERROR) {
                waitForText("Failed to start");
                waitForTextAbsentStable("Starting game", 500L);
                SystemClock.sleep(1200L);
                waitForText("Failed to start");
                waitForTextAbsentStable("Starting game", 500L);
            } else if (scenario == Scenario.CONSUMED_SCHEME) {
                waitForText("Failed to start");
                waitForTextAbsentStable("Starting game", 500L);
                SystemClock.sleep(1200L);
                waitForText("Failed to start");
                waitForTextAbsentStable("Starting game", 500L);
            } else {
                waitForTextAbsentStable("Starting game", 1500L);
                assertTextAbsent("Failed to start");
            }
            saveUiEvidence(context, token, null);
        } catch (Throwable failure) {
            saveUiEvidence(context, token, failure);
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new AssertionError(failure);
        } finally {
            showMainApp(context);
            removeOnlyIsolatedHostTaskAndProcess(context, processSuffix, hostClass.getName());
            deleteRecursively(fixture);
        }
    }

    /** Retain the final UI evidence in the isolated target app before the host task is cleaned up. */
    private void saveUiEvidence(Context context, String token, Throwable failure) {
        File directory = new File(context.getFilesDir(), "startup-acceptance");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e("StartupAcceptance", "Could not create diagnostics directory: " + directory);
            return;
        }
        String stem = token.replaceAll("[^A-Za-z0-9_-]", "_");
        try {
            File textFile = new File(directory, stem + ".txt");
            StringBuilder dump = new StringBuilder()
                    .append(failure == null ? "result=passed" : "failure=" + failure).append('\n');
            if (failure != null) {
                for (StackTraceElement element : failure.getStackTrace()) {
                    dump.append("  at ").append(element).append('\n');
                }
            }
            AccessibilityNodeInfo root = freshRoot();
            dump.append("\nactiveWindowTree:\n");
            if (root == null) {
                dump.append("<no active accessibility root>\n");
            } else {
                appendNode(dump, root, 0);
            }
            write(textFile, dump.toString());

            Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (FileOutputStream output = new FileOutputStream(new File(directory, stem + ".png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
            } else {
                Log.e("StartupAcceptance", "UiAutomation returned no screenshot for " + token);
            }
        } catch (Throwable diagnosticFailure) {
            Log.e("StartupAcceptance", "Could not save failure diagnostics for " + token, diagnosticFailure);
        }
    }

    private static void appendNode(StringBuilder out, AccessibilityNodeInfo node, int depth) {
        if (node == null || !node.refresh()) return;
        for (int i = 0; i < depth; i++) out.append("  ");
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        out.append(node.getClassName())
                .append(" text=").append(node.getText())
                .append(" desc=").append(node.getContentDescription())
                .append(" viewId=").append(node.getViewIdResourceName())
                .append(" bounds=").append(bounds)
                .append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) appendNode(out, child, depth + 1);
        }
    }

    private static boolean nodeContainsText(AccessibilityNodeInfo node, String expected) {
        if (node == null || !node.refresh()) return false;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if ((text != null && text.toString().contains(expected))
                || (description != null && description.toString().contains(expected))) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (nodeContainsText(node.getChild(i), expected)) return true;
        }
        return false;
    }

    private void assertTextAbsent(String text) {
        AccessibilityNodeInfo root = freshRoot();
        assertNotNull("No active host window while checking startup overlay", root);
        assertTrue("Unexpected visible text: " + text, !nodeContainsText(root, text));
    }

    private void waitForText(String text) throws Exception {
        waitFor("visible text: " + text, () -> {
            AccessibilityNodeInfo root = freshRoot();
            return nodeContainsText(root, text);
        });
    }

    private void waitForTextAbsentStable(String text, long stableMillis) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS;
        long absentSince = -1L;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = freshRoot();
            if (root != null && !nodeContainsText(root, text)) {
                if (absentSince < 0L) absentSince = SystemClock.elapsedRealtime();
                if (SystemClock.elapsedRealtime() - absentSince >= stableMillis) return;
            } else {
                absentSince = -1L;
            }
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Text remained visible or window unavailable: " + text);
    }

    private void waitForColorBadge(boolean red) throws Exception {
        String state = red ? "red first-load" : "green post-reload";
        long deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try {
                    if (hasColorBadge(screenshot, red)) return;
                } finally {
                    screenshot.recycle();
                }
            }
            SystemClock.sleep(400L);
        }
        throw new AssertionError("Timed out waiting for visible " + state + " badge");
    }

    private static boolean hasColorBadge(Bitmap screenshot, boolean red) {
        int width = screenshot.getWidth();
        int height = screenshot.getHeight();
        int matching = 0;
        int total = 0;
        for (int row = 0; row < 5; row++) {
            int y = (int) (height * (0.25 + row * 0.06));
            for (int column = 0; column < 5; column++) {
                int x = (int) (width * (0.10 + column * 0.06));
                int pixel = screenshot.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                if (red ? r > 150 && r > g * 1.35 && r > b * 1.20
                        : g > 100 && g > r * 1.20 && g > b * 1.10) {
                    matching++;
                }
                total++;
            }
        }
        return matching >= total * 0.80;
    }

    private AccessibilityNodeInfo freshRoot() {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        if (Build.VERSION.SDK_INT >= 34) {
            automation.clearCache();
        } else {
            AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
            if (serviceInfo != null) automation.setServiceInfo(serviceInfo);
        }
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null) root.refresh();
        return root;
    }

    private void showMainApp(Context context) {
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        SystemClock.sleep(300L);
    }

    private void removeOnlyIsolatedHostTaskAndProcess(Context context, String suffix, String componentName) {
        if (!TEST_APPLICATION_ID.equals(context.getPackageName())) return;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return;
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo info = task.getTaskInfo();
            if (info != null && info.baseIntent != null && info.baseIntent.getComponent() != null
                    && componentName.equals(info.baseIntent.getComponent().getClassName())) {
                task.finishAndRemoveTask();
            }
        }
        String processName = TEST_APPLICATION_ID + ":" + suffix;
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return;
        processes.stream()
                .filter(info -> processName.equals(info.processName) && info.pid > 0 && info.pid != Process.myPid())
                .findFirst()
                .ifPresent(info -> Process.killProcess(info.pid));
    }

    private boolean isProcessForeground(Context context, String suffix) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null || manager.getRunningAppProcesses() == null) return false;
        String processName = context.getPackageName() + ":" + suffix;
        return manager.getRunningAppProcesses().stream().anyMatch(info -> processName.equals(info.processName)
                && info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
    }

    private void waitFor(String description, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.isTrue()) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static void write(File target, String contents) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        assertTrue("Could not remove isolated fixture path: " + target, target.delete());
    }

    private interface Condition { boolean isTrue() throws Exception; }

    private enum Scenario { NORMAL, EMPTY, CONSUMED_SCHEME, MAIN_FRAME_ERROR }
}
