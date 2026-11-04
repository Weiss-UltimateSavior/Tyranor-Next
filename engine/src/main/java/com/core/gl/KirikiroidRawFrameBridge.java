package com.core.gl;

import android.content.Context;
import androidx.annotation.Keep;
import java.io.File;
import com.core.nativeplugin.NativePluginConstants;

/** Leases a processed game-window texture to Cocos for one nativeRender call. */
@Keep
public final class KirikiroidRawFrameBridge {
    /** Host installation state; native failure stages retain their numeric protocol. */
    public enum InstallStatus {
        DISABLED, UNCONFIGURED, UNSUPPORTED_LIBRARY, LIBRARY_PATH_FAILED,
        WAITING_FOR_CONTEXT, INSTALLED, NATIVE_UNAVAILABLE, NATIVE_ARGUMENT,
        NATIVE_LIBRARY_LOOKUP, NATIVE_IMAGE_LOOKUP, NATIVE_BUILD_ID, NATIVE_SYMBOLS,
        NATIVE_VTABLE, NATIVE_RELOCATION, NATIVE_CALLBACK, NATIVE_PROTECTION,
        NATIVE_ROLLBACK, NATIVE_UNKNOWN, FILTER_UNAVAILABLE
    }
    private static volatile InstallStatus installStatus = InstallStatus.UNCONFIGURED;
    private static String configuredLibrary;
    private static boolean bitmap126;
    private static Context pipelineContext;
    private static long pipelineReservedBytes;
    private static boolean installed, frameSeen;
    private static GraphicsFilterPipeline pipeline;
    private KirikiroidRawFrameBridge() {}

    /** Runs during library loading, before the GL renderer starts. */
    public static void configureLibrary(String library) {
        configuredLibrary = null;
        bitmap126 = false;
        installStatus = InstallStatus.UNCONFIGURED;
        if (!Anime4kRuntime.isEnabled()) {
            installStatus = InstallStatus.DISABLED;
            return;
        }
        if (library == null || library.isEmpty()) return;
        try {
            // Match linker path aliases; nativeInstall verifies the already loaded
            // image's Build ID and all hook entry points before making changes.
            File canonicalLibrary = new File(library).getCanonicalFile();
            String name = canonicalLibrary.getName();
            if (!KirikiroidLibraryIdentity.supports(name)) {
                installStatus = InstallStatus.UNSUPPORTED_LIBRARY;
                return;
            }
            bitmap126 = NativePluginConstants.LIB_GAME_126.equals(name);
            configuredLibrary = canonicalLibrary.getPath();
            installStatus = InstallStatus.WAITING_FOR_CONTEXT;
        } catch (Exception unavailable) {
            configuredLibrary = null;
            installStatus = InstallStatus.LIBRARY_PATH_FAILED;
        }
    }
    public static InstallStatus installStatus() { return installStatus; }
    private static InstallStatus statusForStage(int stage) {
        switch (stage) {
            case 0: return InstallStatus.INSTALLED;
            case 1: return InstallStatus.NATIVE_ARGUMENT;
            case 2: return InstallStatus.NATIVE_LIBRARY_LOOKUP;
            case 3: return InstallStatus.NATIVE_IMAGE_LOOKUP;
            case 4: return InstallStatus.NATIVE_BUILD_ID;
            case 5: return InstallStatus.NATIVE_SYMBOLS;
            case 6: return InstallStatus.NATIVE_VTABLE;
            case 7: return InstallStatus.NATIVE_RELOCATION;
            case 8: return InstallStatus.NATIVE_CALLBACK;
            case 9: return InstallStatus.NATIVE_PROTECTION;
            case 10: return InstallStatus.NATIVE_ROLLBACK;
            default: return InstallStatus.NATIVE_UNKNOWN;
        }
    }
    public static void onContextCreated(Context context) {
        if (pipeline != null) pipeline.abandonContext();
        pipeline = null;
        pipelineContext = context;
        pipelineReservedBytes = 0;
        installed = false;
        if (!Anime4kRuntime.isEnabled()) installStatus = InstallStatus.DISABLED;
        else if (context == null) installStatus = InstallStatus.FILTER_UNAVAILABLE;
        else if (configuredLibrary != null) {
            try {
                installStatus = statusForStage(nativeInstall(configuredLibrary));
                installed = installStatus == InstallStatus.INSTALLED;
            } catch (UnsatisfiedLinkError unavailable) {
                installStatus = InstallStatus.NATIVE_UNAVAILABLE;
            }
        }
        if (installed) {
            try {
                nativeResetContext(false);
                if (!bitmap126) ensurePipeline(0);
            } catch (UnsatisfiedLinkError unavailable) {
                installed = false;
                installStatus = InstallStatus.NATIVE_UNAVAILABLE;
            } catch (RuntimeException unavailable) {
                installed = false;
                installStatus = InstallStatus.FILTER_UNAVAILABLE;
            }
        }
        if (!installed) onUnavailableFrame(0, 0);
    }

    /** Missing/unverified entries do not have source geometry, even when the screen is known. */
    public static void onUnavailableFrame(int width, int height) {
        Anime4kRuntime.onRawUnavailable(width, height, graphicsStatus(installStatus()));
    }

    static int graphicsStatus(InstallStatus status) {
        switch (status) {
            case INSTALLED: return GraphicsStatus.READY;
            case DISABLED:
            case UNCONFIGURED:
            case WAITING_FOR_CONTEXT: return GraphicsStatus.WAITING;
            case UNSUPPORTED_LIBRARY:
            case NATIVE_UNAVAILABLE: return GraphicsStatus.MISSING_ABI;
            case FILTER_UNAVAILABLE: return GraphicsStatus.FILTER_FAILED;
            default: return GraphicsStatus.NATIVE_FAILED;
        }
    }
    public static boolean isInstalled() { return installed; }
    public static void beginFrame(int width, int height) {
        frameSeen = false;
        if (installed && Anime4kRuntime.isEnabled()) nativeBeginFrame(width, height);
    }
    public static void endFrame(int width, int height) {
        if (!installed) return;
        nativeEndFrame();
        if (!frameSeen) Anime4kRuntime.onRawFrame(0,0,width,height,null);
    }
    @Keep
    private static int process(int texture,int sourceWidth,int sourceHeight,float u0,float v0,
                               float u1,float v1,int width,int height,boolean topLeft) {
        if (pipeline == null || !Anime4kRuntime.isEnabled()) return 0;
        int output = pipeline.renderCroppedTexture(texture,sourceWidth,sourceHeight,u0,v0,u1,v1,width,height,topLeft);
        frameSeen = true;
        Anime4kRuntime.onRawFrame(sourceWidth,sourceHeight,width,height,pipeline);
        return output;
    }
    /** Includes both possible RenderTexture color copies and NPOT padding. */
    static long carrierBytes126(int width, int height) {
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return Long.MAX_VALUE;
        int paddedWidth = Integer.highestOneBit(width - 1) << 1;
        int paddedHeight = Integer.highestOneBit(height - 1) << 1;
        if (width == 1) paddedWidth = 1;
        if (height == 1) paddedHeight = 1;
        return 8L * paddedWidth * paddedHeight;
    }
    private static void ensurePipeline(long reservedBytes) {
        if (pipeline != null && pipelineReservedBytes == reservedBytes) return;
        if (pipeline != null) pipeline.release();
        pipeline = new GraphicsFilterPipeline(pipelineContext, Anime4kRuntime.chain(), reservedBytes);
        pipelineReservedBytes = reservedBytes;
        if (Anime4kRuntime.isAnalyzing()) pipeline.setProfiler(Anime4kRuntime.stats(), true);
    }
    /** Low 32 bits: texture name. Remaining 16-bit fields: output width and height. */
    @Keep
    private static long process126(int texture,int sourceWidth,int sourceHeight,float u0,float v0,
                                   float u1,float v1,int width,int height) {
        if (pipelineContext == null || !Anime4kRuntime.isEnabled()) return 0;
        GraphicsFilterPlan plan = GraphicsFilterPlan.create(Anime4kRuntime.chain(), sourceWidth, sourceHeight, width, height);
        if (plan == null) return 0;
        int last = plan.outputWidths.length - 1;
        int plannedWidth = last < 0 ? sourceWidth : plan.outputWidths[last];
        int plannedHeight = last < 0 ? sourceHeight : plan.outputHeights[last];
        long reserved = carrierBytes126(plannedWidth, plannedHeight);
        if (reserved == Long.MAX_VALUE) return 0;
        // Release old-size native storage before releasing/rebuilding the
        // pipeline. The outer native guard can then forget deleted GL names.
        boolean prepared = nativePrepareCarrier(plannedWidth, plannedHeight);
        if (plan.hasExecution && !prepared) {
            frameSeen = true;
            Anime4kRuntime.onRawUnavailable(width,height,GraphicsStatus.FILTER_FAILED);
            return 0;
        }
        ensurePipeline(reserved);
        // The 1.2.6 PaintBox bitmap and its unchanged carrier UVs use top-left rows.
        boolean topLeft = true;
        int output = pipeline.renderCroppedTexture(texture,sourceWidth,sourceHeight,u0,v0,u1,v1,width,height,topLeft);
        frameSeen = true;
        if (output != 0 && !nativeUploadCarrier(output,pipeline.outputWidth(),pipeline.outputHeight())) {
            Anime4kRuntime.onRawUnavailable(width,height,GraphicsStatus.FILTER_FAILED);
            return 0;
        }
        Anime4kRuntime.onRawFrame(sourceWidth,sourceHeight,width,height,pipeline);
        int outputWidth = pipeline.outputWidth(), outputHeight = pipeline.outputHeight();
        if (output == 0 || outputWidth <= 0 || outputHeight <= 0 || outputWidth > 16384 || outputHeight > 16384) return 0;
        return (output & 0xffffffffL) | ((long) outputWidth << 32) | ((long) outputHeight << 48);
    }
    public static void release() {
        if (installed) nativeResetContext(true);
        // The host preserves its EGL context on pause. Keep this reusable
        // pipeline object so resume can allocate fresh targets on demand.
        if (pipeline != null) pipeline.release();
    }
    private static native int nativeInstall(String library);
    private static native void nativeBeginFrame(int width,int height);
    private static native void nativeEndFrame();
    private static native boolean nativePrepareCarrier(int width,int height);
    private static native boolean nativeUploadCarrier(int texture,int width,int height);
    private static native void nativeResetContext(boolean release);
}
