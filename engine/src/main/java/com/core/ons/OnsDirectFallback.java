package com.core.ons;

/**
 * ONScripter 从代理滤镜路径回退直出路径时的 SDL 窗口交接编排。
 *
 * <p>{@code onNativeSurfaceDestroyed} 会把 native 侧保存的 {@code ANativeWindow} 清空，
 * 而 {@code onNativeSurfaceChanged} 仅复用已有 window 重建 EGLSurface，不会自行重取窗口；
 * 因此必须先经 {@code onNativeSurfaceCreated} 让 SDL 重取直出 Surface，再恢复几何并通知 changed。</p>
 */
public final class OnsDirectFallback {
    public interface Actions {
        void onNativeSurfaceDestroyed();
        boolean hasValidDirectSurface();
        void onNativeSurfaceCreated();
        void restoreDirectGeometry();
        void onNativeSurfaceChanged();
        void releaseProxy();
    }

    private OnsDirectFallback() {}

    public static void execute(Actions actions) {
        if (actions == null) {
            return;
        }
        actions.onNativeSurfaceDestroyed();
        if (actions.hasValidDirectSurface()) {
            actions.onNativeSurfaceCreated();
            actions.restoreDirectGeometry();
            actions.onNativeSurfaceChanged();
        } else {
            actions.restoreDirectGeometry();
        }
        actions.releaseProxy();
    }
}
