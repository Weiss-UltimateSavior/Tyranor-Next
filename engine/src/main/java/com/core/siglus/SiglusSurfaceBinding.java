package com.core.siglus;

/** Main-thread ownership of the direct SurfaceHolder window across proxy preparation. */
final class SiglusSurfaceBinding {
    interface Connection {
        boolean connect();
        void resize();
    }

    private long generation;
    private long directGeneration = -1;

    void surfaceCreated() {
        generation++;
    }

    void surfaceDestroyed() {
        clear();
    }

    void directConnected() {
        directGeneration = generation;
    }

    void clear() {
        directGeneration = -1;
    }

    boolean isDirectCurrent() {
        return directGeneration == generation;
    }

    boolean restoreDirect(boolean proxyAttached, Connection connection) {
        if (!proxyAttached && isDirectCurrent()) {
            // Rust still owns this window. setSurface would create a second Vulkan
            // surface before dropping the first and fail with NATIVE_WINDOW_IN_USE.
            connection.resize();
            return true;
        }
        if (!connection.connect()) return false;
        directConnected();
        return true;
    }
}
