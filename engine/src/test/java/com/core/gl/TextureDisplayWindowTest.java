package com.core.gl;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ownership contract behind the {@code return !owned} callback in
 * {@code onSurfaceTextureDestroyed}: the UI destroy callback drops its reference while the GLES
 * proxy keeps the native window alive until EGL detaches, and the last release frees the
 * {@code Surface} + {@code SurfaceTexture} handles exactly once.
 */
public final class TextureDisplayWindowTest {
    @Test
    public void refCountLifecycleAndRetention() {
        AtomicInteger handleReleases = new AtomicInteger();
        TextureDisplayWindow window = new TextureDisplayWindow(handleReleases::incrementAndGet);
        assertEquals(1, window.getReferences());
        assertFalse(window.isReleased());

        assertTrue(window.retain());
        assertEquals(2, window.getReferences());
        assertFalse(window.isReleased());

        window.release();
        assertEquals(1, window.getReferences());
        assertFalse(window.isReleased());
        // The proxy still holds one reference: handles must NOT be freed yet.
        assertEquals(0, handleReleases.get());

        window.release();
        assertEquals(0, window.getReferences());
        assertTrue(window.isReleased());
        assertEquals(1, handleReleases.get());

        // Further retain fails once released; redundant release stays safe and frees nothing more.
        assertFalse(window.retain());
        window.release();
        window.release();
        assertEquals(0, window.getReferences());
        assertTrue(window.isReleased());
        assertEquals(1, handleReleases.get());
    }

    @Test
    public void lastReleaseFreesHandlesExactlyOnceUnderMultipleRetains() {
        AtomicInteger handleReleases = new AtomicInteger();
        TextureDisplayWindow window = new TextureDisplayWindow(handleReleases::incrementAndGet);
        assertTrue(window.retain());
        assertTrue(window.retain());
        assertEquals(3, window.getReferences());

        window.release();
        window.release();
        assertEquals(1, window.getReferences());
        assertFalse(window.isReleased());
        assertEquals(0, handleReleases.get());

        window.release();
        assertTrue(window.isReleased());
        assertFalse(window.retain());
        assertEquals(1, handleReleases.get());
    }
}
