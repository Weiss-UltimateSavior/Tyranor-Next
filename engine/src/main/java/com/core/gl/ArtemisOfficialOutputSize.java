package com.core.gl;

/** Last successfully displayed consumer size; the game stage has separate ownership. */
final class ArtemisOfficialOutputSize {
    private volatile long size;

    long get() { return size; }

    void clear() { size = 0L; }

    void presented(int width, int height) {
        if (width > 0 && height > 0)
            size = ((long) width << 32) | (height & 0xffffffffL);
    }
}
