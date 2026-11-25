package com.core.ons;

import android.content.Context;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * Custom SDLSurface for ONScripter that redirects getNativeSurface() to the
 * shared filter proxy when filterMode is active, and isolates logical canvas
 * resolution from physical window geometry.
 */
public class OnsSurface extends SDLSurface {
    private static final String TAG = "OnsSurface";

    public interface SurfaceProvider {
        boolean isFilterMode();
        Surface provideProxySurface(Surface fallback);
        int getLogicalWidth();
        int getLogicalHeight();
    }

    private final SurfaceProvider provider;
    /** 最近一次 surfaceChanged 收到的物理窗口尺寸；回退直出时用它写回归一化分母。 */
    private volatile int lastPhysicalWidth;
    private volatile int lastPhysicalHeight;

    public OnsSurface(Context context, SurfaceProvider provider) {
        super(context);
        this.provider = provider;
    }

    @Override
    public Surface getNativeSurface() {
        if (provider == null || !provider.isFilterMode()) {
            return super.getNativeSurface();
        }
        return provider.provideProxySurface(super.getNativeSurface());
    }

    /**
     * 回退直出时把触点归一化分母写回物理窗口尺寸。
     *
     * <p>滤镜路径把 {@code mWidth/mHeight} 设成逻辑舞台尺寸，供 native 侧把归一化触点
     * 乘回同一逻辑尺寸；回退后不再走本类的 {@code surfaceChanged}（父类唯一写物理值处），
     * 分母会一直停在逻辑值，触点按物理/逻辑比例整体偏移且无法自愈。</p>
     */
    private void setNormalizationSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        this.mWidth = width;
        this.mHeight = height;
    }

    /**
     * 统一查询真实屏幕指标与刷新率，并向 SDL 上报逻辑/窗口分辨率。
     *
     * <p>{@code surfaceChanged}、代理建窗与回退直出三处共享本入口，避免各自查询
     * {@code DisplayMetrics} 与刷新率导致设备尺寸口径漂移。</p>
     */
    public void applyScreenResolution(int surfaceW, int surfaceH, boolean triggerResize) {
        if (surfaceW <= 0 || surfaceH <= 0) return;
        setNormalizationSize(surfaceW, surfaceH);
        int[] deviceSize = readDeviceMetrics(surfaceW, surfaceH);
        float rate = readRefreshRate();
        Log.v(TAG, "Configuring SDL screen resolution: surface=" + surfaceW + "x" + surfaceH
                + " device=" + deviceSize[0] + "x" + deviceSize[1] + " rate=" + rate);
        dispatchScreenResolution(surfaceW, surfaceH, deviceSize[0], deviceSize[1], rate, triggerResize,
                "Failed to configure SDL screen resolution");
    }

    /**
     * 回退直出时将触点归一化分母与 SDL 逻辑分辨率同步恢复为物理窗口尺寸。
     *
     * <p>若 {@code surfaceChanged} 尚未记录物理尺寸（极端早退），以单次查询得到的
     * 真实屏幕尺寸兜底，确保不会残留脚本逻辑尺寸。</p>
     */
    public void restoreDirectGeometry() {
        int physW = lastPhysicalWidth;
        int physH = lastPhysicalHeight;
        int[] deviceSize = readDeviceMetrics(0, 0);
        if (physW <= 1 || physH <= 1) {
            physW = deviceSize[0];
            physH = deviceSize[1];
        }
        if (physW <= 1 || physH <= 1) return;
        setNormalizationSize(physW, physH);
        int deviceW = deviceSize[0] > 0 ? deviceSize[0] : physW;
        int deviceH = deviceSize[1] > 0 ? deviceSize[1] : physH;
        float rate = readRefreshRate();
        dispatchScreenResolution(physW, physH, deviceW, deviceH, rate, true,
                "Failed to restore native screen resolution after fallback");
    }

    private void dispatchScreenResolution(
            int surfaceW, int surfaceH, int deviceW, int deviceH,
            float rate, boolean triggerResize, String errorMessage) {
        try {
            SDLActivity.nativeSetScreenResolution(surfaceW, surfaceH, deviceW, deviceH, rate);
            if (triggerResize) {
                SDLActivity.onNativeResize();
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError t) {
            Log.w(TAG, errorMessage, t);
        }
    }

    private int[] readDeviceMetrics(int fallbackW, int fallbackH) {
        int deviceW = fallbackW;
        int deviceH = fallbackH;
        try {
            if (this.mDisplay != null) {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                this.mDisplay.getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    deviceW = metrics.widthPixels;
                    deviceH = metrics.heightPixels;
                }
            }
        } catch (RuntimeException ignored) {}
        return new int[]{deviceW, deviceH};
    }

    private float readRefreshRate() {
        float rate = 60.0f;
        try {
            if (this.mDisplay != null) {
                rate = this.mDisplay.getRefreshRate();
            }
        } catch (RuntimeException ignored) {}
        return rate;
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        // 先于滤镜/直出分支记录最新有效物理尺寸：若代理刚报失败、`isFilterMode()` 先于 UI
        // 回退变为 false，父类直出分支会跳过后续记录，导致 restoreDirectGeometry 用到旧尺寸。
        if (width > 0 && height > 0) {
            lastPhysicalWidth = width;
            lastPhysicalHeight = height;
        }
        if (provider == null || !provider.isFilterMode()) {
            super.surfaceChanged(surfaceHolder, format, width, height);
            return;
        }

        Log.v(TAG, "surfaceChanged() intercepted for filterMode; physical=" + width + "x" + height);
        if (SDLActivity.getContext() == null) {
            return;
        }

        int logicW = provider.getLogicalWidth();
        int logicH = provider.getLogicalHeight();
        if (logicW <= 0 || logicH <= 0) {
            logicW = OnsScriptResolution.DEFAULT_WIDTH;
            logicH = OnsScriptResolution.DEFAULT_HEIGHT;
        }

        // SDL 的 onNativeTouch 收归一化 [0,1]，native 侧乘回窗口逻辑尺寸；滤镜路径的
        // `OnsViewport` 也按同一逻辑舞台换算，两者必须同源，否则触点按物理/逻辑比例错位。
        this.mWidth = logicW;
        this.mHeight = logicH;

        synchronized (SDLActivity.getContext()) {
            SDLActivity.getContext().notifyAll();
        }

        applyScreenResolution(logicW, logicH, true);

        SDLActivity.onNativeSurfaceChanged();
        this.mIsSurfaceReady = true;
        SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
        SDLActivity.handleNativeState();
    }
}
