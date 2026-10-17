package com.core.gl;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import com.core.engine.LaunchContract;

/** KR2 filter-chain runtime. Historical class name and EXTRA_MODE preserve external single-mode launch compatibility. */
public final class Anime4kRuntime {
    public static final String EXTRA_MODE = "tyranor_anime4k";
    private static volatile boolean enabled, requested, analyzing;
    private static volatile GraphicsFilterChain chain = GraphicsFilterChain.parse("v1:");
    private static volatile int status = GraphicsStatus.WAITING;
    private static final ArtemisGraphicsStats STATS = new ArtemisGraphicsStats();
    private Anime4kRuntime() {}

    public static void configure(Context context, String mode) {
        apply(context, GraphicsFilterChain.fromLegacy(mode), true);
    }

    public static void configureFromIntent(Context context, Intent intent) {
        if (intent != null && intent.hasExtra(LaunchContract.GRAPHICS_FILTER_CHAIN)) {
            apply(context, GraphicsFilterChain.parse(intent.getStringExtra(LaunchContract.GRAPHICS_FILTER_CHAIN)),
                    intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ENABLED, false));
            analyzing = enabled && intent.getBooleanExtra(LaunchContract.GRAPHICS_FILTER_ANALYZER, false);
        } else {
            configure(context, intent == null ? null : intent.getStringExtra(EXTRA_MODE));
        }
    }

    private static void apply(Context context, GraphicsFilterChain configured, boolean master) {
        boolean invalid = configured == null;
        chain = invalid ? GraphicsFilterChain.parse("v1:") : configured;
        requested = !invalid && master && chain.hasActive();
        analyzing = false;
        enabled = requested && supportsGles3(context);
        status = invalid ? GraphicsStatus.FILTER_FAILED
                : requested && !enabled ? GraphicsStatus.GLES3_UNAVAILABLE : GraphicsStatus.WAITING;
        STATS.reset();
    }

    private static boolean supportsGles3(Context context) {
        if (context == null) return false;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return manager != null && manager.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }
    public static ArtemisPerformanceOverlay.Source source() {
        return new ArtemisPerformanceOverlay.Source() {
            public ArtemisGraphicsStats stats() { return Anime4kRuntime.stats(); }
            public boolean isAnalyzing() { return Anime4kRuntime.isAnalyzing(); }
            public int status() { return Anime4kRuntime.status(); }
        };
    }

    public static boolean isEnabled() { return enabled; }
    public static boolean useGles3Context() { return enabled; }
    public static boolean isAnalyzing() { return analyzing; }
    public static GraphicsFilterChain chain() { return chain; }
    public static int status() { return status; }
    public static ArtemisGraphicsStats stats() { return STATS; }
    public static void onContextCreated() {
        STATS.reset();
        if (enabled) status = GraphicsStatus.WAITING;
    }
    static void onRawUnavailable(int width, int height, int unavailableStatus) {
        if (enabled) status = unavailableStatus;
        if (analyzing) {
            STATS.surface(width, height);
            STATS.frame(0, 0, 0, 0, 0, 0);
            STATS.invalidateNodes();
            STATS.gpu(-1);
            if (width > 0 && height > 0) STATS.presented();
        }
    }
    static void onRawFrame(int sw,int sh,int width,int height,GraphicsFilterPipeline processor) {
        status = sw <= 0 || sh <= 0 ? GraphicsStatus.SIZE_UNAVAILABLE
                : processor != null && processor.isFailed() ? GraphicsStatus.FILTER_FAILED
                : processor != null && processor.bypassReason() != Anime4kPostProcessor.BYPASS_NONE
                ? GraphicsStatus.SIZE_UNAVAILABLE : GraphicsStatus.READY;
        if (analyzing) {
            GraphicsOutputSize.Size fitted = GraphicsOutputSize.fit(sw,sh,width,height);
            STATS.surface(width,height);
            STATS.frame(sw,sh,processor == null ? sw : processor.outputWidth(),
                    processor == null ? sh : processor.outputHeight(),
                    fitted == null ? 0 : fitted.width,fitted == null ? 0 : fitted.height);
            STATS.gpu(-1); STATS.presented();
        }
    }
    public static void reset() {
        enabled = requested = analyzing = false;
        chain = GraphicsFilterChain.parse("v1:");
        status = GraphicsStatus.WAITING;
        STATS.reset();
    }
}
