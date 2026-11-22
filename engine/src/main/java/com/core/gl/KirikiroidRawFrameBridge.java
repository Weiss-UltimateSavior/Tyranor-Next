package com.core.gl;

import android.content.Context;
import androidx.annotation.Keep;
import java.io.File;

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
    private static boolean installed, frameSeen;
    private static GraphicsFilterPipeline pipeline;
    private KirikiroidRawFrameBridge() {}

    /** Runs during library loading, before the GL renderer starts. */
    public static void configureLibrary(String library) {
        configuredLibrary = null;
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
        Context appContext = context != null ? context.getApplicationContext() : null;
        Context resolvedContext = appContext != null ? appContext : context;
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
                nativeResetContext();
                ensurePipeline(resolvedContext);
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
                               float u1,float v1,int width,int height,boolean topLeft,boolean frameDirty) {
        // Native binds by exact signature "(IIIFFFFIIZZ)I" (11 params); the historical
        // 10-param frameDirty-less overload was dead code and has been removed.
        if (pipeline == null || !Anime4kRuntime.isEnabled()) return 0;
        int output = pipeline.renderCroppedTexture(
                texture,sourceWidth,sourceHeight,u0,v0,u1,v1,width,height,topLeft,frameDirty);
        frameSeen = true;
        Anime4kRuntime.onRawFrame(sourceWidth,sourceHeight,width,height,pipeline);
        return output;
    }
    private static void ensurePipeline(Context context) {
        if (pipeline != null) return;
        // Reserved bytes stay 0: the hooked game texture is borrowed from the host's renderer on
        // entry and the processed result is leased straight back. The former P0 temporal GPU
        // probe was removed together with the unified-pipeline rewrite; static-frame reuse now
        // relies solely on native dirty-frame static reuse tracking (frameDirty).
        pipeline = new GraphicsFilterPipeline(context, Anime4kRuntime.chain(), 0);
        if (Anime4kRuntime.isAnalyzing()) pipeline.setProfiler(Anime4kRuntime.stats(), true);
    }
    public static void release() {
        if (installed) nativeResetContext();
        // The host preserves its EGL context on pause. Keep this reusable
        // pipeline object so resume can allocate fresh targets on demand.
        if (pipeline != null) pipeline.release();
    }
    private static native int nativeInstall(String library);
    private static native void nativeBeginFrame(int width,int height);
    private static native void nativeEndFrame();
    private static native void nativeResetContext();
}
