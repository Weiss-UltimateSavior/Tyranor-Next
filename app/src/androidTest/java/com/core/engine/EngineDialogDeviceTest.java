package com.core.engine;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ScrollView;
import androidx.activity.ComponentActivity;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.tvp.kirikiri2.KrDialogStyle;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class EngineDialogDeviceTest {
    @Test public void paletteAndActionsStayVisibleWithLongText() {
        int primary = 0xff7d35bb;
        int card = 0xff26223b;
        int onPrimary = 0xfffee8ff;
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<Dialog> opened = new AtomicReference<>();
            AtomicInteger selected = new AtomicInteger(-1);
            scenario.onActivity(activity -> {
                activity.getIntent()
                        .putExtra(LaunchContract.DARK_MODE, true)
                        .putExtra(LaunchContract.PRIMARY_COLOR, 0xff0055aa)
                        .putExtra(LaunchContract.THEME_COLOR_PRIMARY, primary)
                        .putExtra(LaunchContract.THEME_COLOR_CARD, card)
                        .putExtra(LaunchContract.THEME_COLOR_ON_PRIMARY, onPrimary);
                String message = String.join("\n", Collections.nCopies(100, "A long message"));
                Dialog dialog = KrDialogStyle.createDialog(activity, "Theme test", message, null,
                        new String[]{"Accept", "Cancel"}, true, true,
                        (d, button) -> selected.set(button));
                opened.set(dialog);
                dialog.show();
            });
            awaitLabel("Accept");
            scenario.onActivity(activity -> {
                Dialog dialog = opened.get();
                ViewGroup content = dialog.findViewById(android.R.id.content);
                Button button = find(content, Button.class);
                assertNotNull(button);
                assertEquals(primary, ((GradientDrawable) button.getBackground()).getColor().getDefaultColor());
                assertEquals(onPrimary, button.getCurrentTextColor());
                View cardView = (View) button.getParent().getParent();
                assertEquals(card, ((GradientDrawable) cardView.getBackground()).getColor().getDefaultColor());
                ScrollView body = find(content, ScrollView.class);
                assertNotNull(body);
                assertTrue(body.canScrollVertically(1));
                assertTrue(button.isShown());
                assertTrue(button.performClick());
                assertEquals(0, selected.get());
            });
        }
    }

    @Test public void sdlEscapeReturnsTheOriginalIdAndCloseReleasesTheNextRequest() throws Exception {
        ExecutorService nativeThread = Executors.newSingleThreadExecutor();
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<SdlMessageBox> messageBox = new AtomicReference<>();
            scenario.onActivity(activity -> messageBox.set(new SdlMessageBox(activity)));
            SdlMessageBox box = messageBox.get();
            Future<Integer> first = nativeThread.submit(() -> box.show("SDL request", "Choose an action",
                    new int[]{1, 2}, new int[]{41, 73}, new String[]{"Accept", "Abort"}, false, -1));
            awaitLabel("Abort");
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE);
            assertEquals(73, first.get(5, TimeUnit.SECONDS).intValue());

            Future<Integer> second = nativeThread.submit(() -> box.show("Next SDL request", null,
                    new int[]{0}, new int[]{97}, new String[]{"Wait"}, false, -1));
            awaitLabel("Wait");
            scenario.onActivity(activity -> box.close());
            assertEquals(-1, second.get(5, TimeUnit.SECONDS).intValue());
        } finally {
            nativeThread.shutdownNow();
        }
    }

    @Test public void webPromptReturnsItsInputToJavaScript() throws Exception {
        try (ActivityScenario<ComponentActivity> scenario = ActivityScenario.launch(ComponentActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            CountDownLatch answered = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                activity.getIntent().putExtra(LaunchContract.UI_LANGUAGE_TAG, "en");
                WebView view = new WebView(activity);
                view.getSettings().setJavaScriptEnabled(true);
                view.setWebChromeClient(new EngineWebChromeClient(activity) {
                    @Override public void onReceivedTitle(WebView view, String title) {
                        if ("return:example".equals(title)) answered.countDown();
                    }
                });
                web.set(view);
                activity.setContentView(view);
                view.loadDataWithBaseURL("https://dialog.test/",
                        "<script>document.title = 'return:' + prompt('Type a value', 'example')</script>",
                        "text/html", "UTF-8", null);
            });
            try {
                AccessibilityNodeInfo ok = awaitLabel("OK");
                assertTrue(ok.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                assertTrue("WebView did not receive the prompt value", answered.await(5, TimeUnit.SECONDS));
            } finally {
                scenario.onActivity(activity -> {
                    WebView view = web.get();
                    ((EngineWebChromeClient) view.getWebChromeClient()).dismissDialogs();
                    activity.setContentView(new View(activity));
                    view.destroy();
                });
            }
        }
    }

    private static AccessibilityNodeInfo awaitLabel(String text) {
        long deadline = SystemClock.uptimeMillis() + 5_000;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                    if (node.isClickable() && text.contentEquals(node.getText())) return node;
                }
            }
            SystemClock.sleep(40);
        }
        throw new AssertionError("Dialog action did not become visible: " + text);
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
