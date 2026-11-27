package com.core.web;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.core.rpgmaker.RpgMakerDirIndex;
import com.core.rpgmaker.RpgMakerLocalHttpServer;
import com.core.tyrano.TyranoLocalHttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android WebView + production HTTP hosts; synthetic scripts only, no game assets. */
@RunWith(AndroidJUnit4.class)
public final class DkToolsStartupDeviceTest {
    private static final String TAG = "DkToolsStartupDevice";

    @Test(timeout = 30000) public void modernHostKeepsStartupResponsive() throws Exception {
        runHost(false);
    }

    @Test(timeout = 30000) public void legacyHostKeepsStartupResponsive() throws Exception {
        runHost(true);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void runHost(boolean legacy) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assumeTrue("Use only the isolated instrumentation application ID",
                "com.tyranor.next.ethornelltest".equals(context.getPackageName()));
        assumeTrue("Explicit opt-in: -e rpgmaker_compat true",
                "true".equals(InstrumentationRegistry.getArguments().getString("rpgmaker_compat")));

        File root = new File(context.getCacheDir(), "dktools-startup-" + UUID.randomUUID());
        RpgMakerLocalHttpServer modern = null;
        TyranoLocalHttpServer old = null;
        CountDownLatch releaseScan = new CountDownLatch(1);
        AtomicReference<WebView> browser = new AtomicReference<>();
        try {
            assertTrue(root.mkdirs());
            File scripts = new File(root, "js/plugins");
            assertTrue(scripts.mkdirs());
            Files.write(new File(root, "index.html").toPath(), HTML.getBytes(StandardCharsets.UTF_8));
            Files.write(new File(scripts, "DKTools.js").toPath(), PLUGIN.getBytes(StandardCharsets.UTF_8));
            byte[] hook;
            try (InputStream input = context.getAssets().open(DkToolsCompat.ASSET_PATH);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                hook = output.toByteArray();
            }
            Map<String, byte[]> appends = Collections.singletonMap(DkToolsCompat.SCRIPT_PATH, hook);
            modern = legacy ? null : new RpgMakerLocalHttpServer(root,
                    (com.core.rpgmaker.AsarArchive) null, null, false, appends, "",
                    Collections.emptyMap(), null, true, false, 0, "", new RpgMakerDirIndex());
            old = legacy ? new TyranoLocalHttpServer(root,
                    (com.core.tyrano.AsarArchive) null, null, false, appends, "",
                    Collections.emptyMap(), 0) : null;
            Object server = legacy ? old : modern;
            CountDownLatch scanStarted = new CountDownLatch(1);
            AtomicReference<String> workerName = new AtomicReference<>();
            PluginFileIndexCache cache = new PluginFileIndexCache(focused -> {
                workerName.set(Thread.currentThread().getName());
                scanStarted.countDown();
                try {
                    if (!releaseScan.await(12, TimeUnit.SECONDS)) throw new AssertionError("Scan was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Synthetic slow scan interrupted", error);
                }
                return "{\"locales\":{\"en.json\":{\"__stats__\":{\"type\":\"file\",\"size\":2}}}}"
                        .getBytes(StandardCharsets.UTF_8);
            }, error -> false, System::nanoTime, 5_000_000_000L, 3, error -> Unit.INSTANCE);
            // Replace only the slow-I/O boundary, using the real cache, executor and HTTP protocol.
            Field cacheField = server.getClass().getDeclaredField("pluginFileIndex");
            cacheField.setAccessible(true);
            cacheField.set(server, cache);
            Bridge probe = new Bridge();
            if (legacy) old.start(); else modern.start();
            int port = legacy ? old.getPort() : modern.getPort();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WebView view = new WebView(context);
                browser.set(view);
                view.getSettings().setJavaScriptEnabled(true);
                view.addJavascriptInterface(probe, "DeviceProbe");
                view.setWebChromeClient(new WebChromeClient() {
                    @Override public boolean onConsoleMessage(ConsoleMessage message) {
                        Log.i(TAG, message.message());
                        return true;
                    }
                });
                view.setWebViewClient(new WebViewClient() {
                    @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                          WebResourceError error) {
                        if (request.isForMainFrame()) probe.failure("WebView: " + error.getDescription());
                    }
                });
                view.loadUrl("http://127.0.0.1:" + port + "/index.html");
            });
            assertTrue("Independent scan did not start: " + probe.error.get(), scanStarted.await(5, TimeUnit.SECONDS));
            assertTrue("JS heartbeat / HTTP 202 polling stopped: " + probe.error.get(),
                    probe.responsive.await(8, TimeUnit.SECONDS));
            assertEquals("TyranorPluginIndex", workerName.get());
            releaseScan.countDown();
            assertTrue("Startup did not resume: " + probe.error.get(), probe.done.await(10, TimeUnit.SECONDS));
            assertNull(probe.error.get(), probe.error.get());
            JSONObject result = new JSONObject(probe.result.get());
            assertTrue("Initialization must see the generated stamp", result.getBoolean("stampReady"));
            assertEquals(0, result.getInt("originalCalls"));
            assertEquals(1, result.getInt("initialized"));
            assertEquals(1, result.getInt("earlyOnload"));
            assertEquals(1, result.getInt("earlyListener"));
            assertEquals(1, result.getInt("lateOnload"));
            assertEquals(1, result.getInt("lateListener"));
            assertTrue(result.getInt("ticks") >= 2);
            assertTrue(result.getInt("pending") >= 1);
            Log.i(TAG, "PASS legacy=" + legacy + " result=" + result + " fixture=" + root);
        } finally {
            releaseScan.countDown();
            if (old != null) old.stop();
            if (modern != null) modern.stop();
            if (browser.get() != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    WebView view = browser.get();
                    if (view != null) {
                        view.stopLoading();
                        view.removeJavascriptInterface("DeviceProbe");
                        view.destroy();
                    }
                });
            }
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!target.delete()) {
            Log.w(TAG, "Failed to delete temporary fixture path: " + target);
        }
    }

    public static final class Bridge {
        final CountDownLatch responsive = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();

        @JavascriptInterface public void pulse(int ticks, int pending) {
            if (ticks >= 2 && pending >= 1) responsive.countDown();
        }
        @JavascriptInterface public void success(String json) { result.set(json); done.countDown(); }
        @JavascriptInterface public void failure(String message) { error.compareAndSet(null, message); done.countDown(); }
    }

    private static final String HTML = "<!doctype html><html><head><meta charset='utf-8'><script>"
            + "var state={originalCalls:0,initialized:0,stampReady:false,earlyOnload:0,earlyListener:0,"
            + "lateOnload:0,lateListener:0,ticks:0,pending:0};"
            + "window.addEventListener('error',function(e){DeviceProbe.failure(e.message||e.type);});"
            + "window.onload=function(){state.earlyOnload++;};"
            + "window.addEventListener('load',function(){state.earlyListener++;},true);"
            + "var send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){"
            + "this.addEventListener('load',function(){if(this.status===202)state.pending++;});"
            + "return send.apply(this,arguments);};"
            + "var heartbeat=setInterval(function(){state.ticks++;DeviceProbe.pulse(state.ticks,state.pending);},5);"
            + "</script><script src='js/plugins/DKTools.js'></script><script>"
            + "var alias=window.onload;window.onload=function(e){alias.call(this,e);state.lateOnload++;};"
            + "window.addEventListener('load',function(){state.lateListener++;setTimeout(function(){"
            + "clearInterval(heartbeat);DeviceProbe.success(JSON.stringify(state));},50);});"
            + "</script></head><body>DKTools startup fixture</body></html>";

    private static final String PLUGIN = "var Utils={isNwjs:function(){return false;}};"
            + "var DKToolsParam={get:function(){return 0;}};var DKTools={IO:{MODE_NWJS_STAMP:0,_stamp:{},"
            + "_loadStamp:function(){state.originalCalls++;},initialize:function(){this.mode=0;this._loadStamp();"
            + "state.initialized++;state.stampReady=!!(this._stamp.locales&&this._stamp.locales['en.json']);}}};"
            + "var previousOnload=window.onload;window.onload=function(e){if(previousOnload)previousOnload.call(this,e);"
            + "DKTools.IO.initialize();};";
}
