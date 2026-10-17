package com.core.gl;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.Surface;
import com.core.engine.LaunchContract;

/** Optional host-owned producer for fixed official libraries. No clean-engine callbacks. */
public final class ArtemisOfficialBridge implements ArtemisPerformanceOverlay.Source {
    private static final ArtemisOfficialBridge INSTANCE = new ArtemisOfficialBridge();
    private static Context context;
    private static GraphicsFilterChain chain;
    private static volatile boolean requested, analyzer;
    private static volatile int state = GraphicsStatus.WAITING;
    private static volatile ArtemisOfficialSurfaceProxy proxy;
    private final ArtemisGraphicsStats empty = new ArtemisGraphicsStats();
    private ArtemisOfficialBridge() {}

    /** Called before NativeActivity starts its worker. JNI then owns connection changes. */
    public static void prepare(Activity activity) {
        Intent intent = activity.getIntent();
        String library = intent == null ? null : intent.getStringExtra(LaunchContract.ENGINE_LIB_NAME);
        context = activity.getApplicationContext();
        chain = intent == null ? null : GraphicsFilterChain.parse(intent.getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN));
        requested = library != null && ArtemisLibraryIdentity.supports("lib" + library + ".so")
                && chain != null && chain.hasActive()
                && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false);
        analyzer = requested && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false);
        state = GraphicsStatus.WAITING;
        INSTANCE.empty.reset();
    }
    public static ArtemisPerformanceOverlay.Source source() { return INSTANCE; }
    /** The bootstrap supplies the Build ID from the actually loaded, readable ELF notes. */
    public static boolean verifyLibrary(String path, String buildId) {
        if (!requested) return false;
        boolean verified = ArtemisLibraryIdentity.matches(path, buildId);
        if (!verified) state = GraphicsStatus.NATIVE_FAILED;
        return verified;
    }
    /** Render-thread call; GLES preparation never needs an Activity-thread callback. */
    public static synchronized Surface connect(Surface screen, int width, int height, int producerWidth, int producerHeight) {
        if (!requested || screen == null) return null;
        boolean handedToProxy = false;
        try {
            if (proxy == null) proxy = new ArtemisOfficialSurfaceProxy(context, chain, analyzer);
            handedToProxy = true;
            return proxy.connect(screen, width, height, producerWidth, producerHeight);
        } catch (RuntimeException failure) {
            // Construction can reject a new consumer before connect owns this reference.
            // Once handed over, claim/cancel/release remains entirely the proxy's job.
            if (!handedToProxy) screen.release();
            state = GraphicsStatus.NATIVE_FAILED;
            android.util.Log.w("ArtemisOfficial", "Surface proxy unavailable; keeping direct output", failure);
            // Keep the instance until native confirms its output has disconnected.
            // A timed-out connect job may still be running on the GLES thread.
            return null;
        }
    }
    /** Set the consumer's actual default buffer size before the official resize/reset. */
    public static boolean resizeProducer(int width, int height) {
        ArtemisOfficialSurfaceProxy current = proxy;
        return current != null && current.resizeProducer(width, height);
    }
    public static void submitted(long timestamp, int width, int height, boolean original) {
        ArtemisOfficialSurfaceProxy current = proxy;
        if (current != null) current.submitted(timestamp, width, height, original);
    }
    public static boolean healthy() { ArtemisOfficialSurfaceProxy current = proxy; return current != null && !current.failed(); }
    /** No monitor or worker wait: input/render hooks may already own a native surface lock. */
    public static long outputSize() {
        ArtemisOfficialSurfaceProxy current = proxy;
        return current == null ? 0L : current.outputSize();
    }
    /** Native detaches its EGL producer before dropping either Surface reference. */
    public static synchronized int disconnect() {
        ArtemisOfficialSurfaceProxy current = proxy;
        return current == null ? ArtemisOfficialDisconnect.DETACHED : current.disconnect();
    }
    public static synchronized void nativeStatus(int status) { state = GraphicsStatus.preserveFailure(state, status); }
    /** Called only after native successfully binds official direct output. */
    public static synchronized void directRestored() {
        ArtemisOfficialSurfaceProxy current = proxy;
        if (current != null) current.directRestored();
        state = GraphicsStatus.preserveFailure(state, GraphicsStatus.WAITING);
    }
    /** Called after NativeActivity.onDestroy joined its worker. */
    public static synchronized void close() {
        if (proxy != null) proxy.release();
        proxy = null; requested = analyzer = false;
    }
    @Override public ArtemisGraphicsStats stats() { ArtemisOfficialSurfaceProxy current = proxy; return current == null ? empty : current.stats(); }
    @Override public boolean isAnalyzing() { return requested && analyzer; }
    @Override public int status() {
        ArtemisOfficialSurfaceProxy current = proxy;
        // Native direct-output/registration failures take precedence over a
        // consumer's last successfully filtered frame.
        return state != GraphicsStatus.WAITING || current == null ? state : current.status();
    }
}
