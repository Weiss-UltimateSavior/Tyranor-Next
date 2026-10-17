package com.core.gl;

/** Host-independent status values; native bridges retain the stable numeric protocol. */
public final class GraphicsStatus {
    public static final int READY = 0, MISSING_ABI = 1, GLES3_UNAVAILABLE = 2,
            NATIVE_FAILED = 3, FILTER_FAILED = 4, WAITING = 5, SIZE_UNAVAILABLE = 7;
    // Value 6 is retired; preserve the other native protocol values.
    static int preserveFailure(int previous, int incoming) {
        boolean failure = previous == MISSING_ABI || previous == GLES3_UNAVAILABLE
                || previous == NATIVE_FAILED || previous == FILTER_FAILED;
        return failure && (incoming == READY || incoming == WAITING) ? previous : incoming;
    }
    private GraphicsStatus() {}
}
