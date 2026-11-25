package com.core.gl;

/**
 * Reference-counted ownership of app-held display handles.
 *
 * <p>Hosts hand a TextureView's {@code SurfaceTexture} to a GLES proxy that may keep rendering
 * into it for a while after the UI's destroy callback fired. The UI drops its reference
 * immediately, the proxy drops its own when EGL detaches, and the <em>last</em> release frees
 * the native handles exactly once.</p>
 *
 * <p>Pure Java (no Android types) so the contract — "last release frees each handle once, and a
 * released lease never retains again" — is verified on the JVM instead of through
 * {@code return !owned} reasoning. Hosts pass whatever cleanup they own (e.g. releasing a
 * {@code Surface} and a {@code SurfaceTexture}) into the constructor.</p>
 */
public final class HandleLease {
    private final Runnable onLastRelease;
    private int references = 1;
    private boolean released;

    public HandleLease(Runnable onLastRelease) {
        if (onLastRelease == null) throw new IllegalArgumentException("release action must not be null");
        this.onLastRelease = onLastRelease;
    }

    /** @return {@code false} once the lease was fully released (caller must not use the handles). */
    public synchronized boolean retain() {
        if (released || references == 0) return false;
        references++;
        return true;
    }

    /**
     * 最后一次 release 在锁外执行清理动作：清理会调用 JNI（{@code Surface.release()} /
     * {@code SurfaceTexture.release()}），持本对象锁期间阻塞在驱动上会拖住其它线程的
     * {@link #retain()} / {@link #isReleased()}。
     */
    public void release() {
        synchronized (this) {
            if (released || references == 0) return;
            if (--references != 0) return;
            released = true;
        }
        onLastRelease.run();
    }

    public synchronized int references() { return references; }

    public synchronized boolean isReleased() { return released; }
}
