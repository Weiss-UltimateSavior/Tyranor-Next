package com.core.gl;

import android.graphics.SurfaceTexture;
import android.view.Surface;

/**
 * 应用自有的 TextureView 显示窗口。引用计数语义与 Siglus 显示租约一致：UI 的销毁回调
 * 立即归还自己的引用，而 GLES 代理会保持 native 窗口存活直到 EGL 断开；最后一次 release
 * 同时释放两个句柄。在途或已保留的 EGL 工作不会被撤走窗口。
 */
public final class TextureDisplayWindow {
    public final SurfaceTexture texture;
    public final Surface surface;
    private final HandleLease lease;

    public TextureDisplayWindow(SurfaceTexture texture) {
        this(texture, new Surface(texture));
    }

    private TextureDisplayWindow(SurfaceTexture texture, Surface surface) {
        this.texture = texture;
        this.surface = surface;
        this.lease = new HandleLease(this::releaseHandles);
    }

    /** 测试用入口：JVM 上没有 Surface/SurfaceTexture，用纯 Java 清理动作验证所有权契约。 */
    TextureDisplayWindow(Runnable handleRelease) {
        this.texture = null;
        this.surface = null;
        this.lease = new HandleLease(handleRelease);
    }

    public boolean retain() {
        return lease.retain();
    }

    public void release() {
        lease.release();
    }

    public int getReferences() {
        return lease.references();
    }

    public boolean isReleased() {
        return lease.isReleased();
    }

    /** 只在最后一次 release 时执行一次。surface 释放失败不得漏掉 texture。 */
    private void releaseHandles() {
        try {
            if (surface != null) surface.release();
        } finally {
            if (texture != null) texture.release();
        }
    }
}
