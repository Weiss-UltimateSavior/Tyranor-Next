package com.core.gl;

/** Owned OpenGL 2D texture paired with an FBO for off-screen rendering. */
final class GlRenderTarget {
    final int[] texture = new int[1];
    final int[] fbo = new int[1];
    final int width;
    final int height;

    GlRenderTarget(int width, int height) {
        this.width = width;
        this.height = height;
    }
}
