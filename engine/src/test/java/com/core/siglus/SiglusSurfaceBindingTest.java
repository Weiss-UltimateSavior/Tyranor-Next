package com.core.siglus;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SiglusSurfaceBindingTest {
    private static final class Window implements SiglusSurfaceBinding.Connection {
        boolean occupied;
        int connects;
        int resizes;

        @Override public boolean connect() {
            connects++;
            if (occupied) return false; // Vulkan rejects a duplicate window connection.
            occupied = true;
            return true;
        }
        @Override public void resize() { resizes++; }
    }

    @Test public void failedPreparationKeepsTheAlreadyConnectedWindow() {
        SiglusSurfaceBinding binding = new SiglusSurfaceBinding();
        binding.surfaceCreated();
        binding.directConnected();
        Window window = new Window();
        window.occupied = true;
        assertTrue(binding.restoreDirect(false, window));
        assertEquals(0, window.connects);
        assertEquals(1, window.resizes);
    }

    @Test public void recreatedHolderMustReconnectEvenBeforeProxyTakeover() {
        SiglusSurfaceBinding binding = new SiglusSurfaceBinding();
        binding.surfaceCreated();
        binding.directConnected();
        binding.surfaceDestroyed();
        binding.surfaceCreated();
        Window window = new Window();
        assertTrue(binding.restoreDirect(false, window));
        assertEquals(1, window.connects);
        assertEquals(0, window.resizes);
        assertTrue(binding.restoreDirect(false, window));
        assertEquals(1, window.connects);
        assertEquals(1, window.resizes);
    }

    @Test public void producerTakeoverRequiresANewDirectConnection() {
        SiglusSurfaceBinding binding = new SiglusSurfaceBinding();
        binding.surfaceCreated();
        binding.directConnected();
        Window window = new Window();
        assertTrue(binding.restoreDirect(true, window));
        assertEquals(1, window.connects);
        assertEquals(0, window.resizes);
    }

    @Test public void preparationTimeoutCanResumeOnlyTheCurrentDirectGeneration() {
        SiglusSurfaceBinding binding = new SiglusSurfaceBinding();
        assertFalse(binding.isDirectCurrent());
        binding.surfaceCreated();
        binding.directConnected();
        assertTrue(binding.isDirectCurrent());
        binding.surfaceDestroyed();
        assertFalse(binding.isDirectCurrent());
        binding.surfaceCreated();
        assertFalse(binding.isDirectCurrent());
        binding.directConnected();
        assertTrue(binding.isDirectCurrent());
    }

    @Test public void failedReconnectDoesNotClaimOwnershipForTheRetry() {
        SiglusSurfaceBinding binding = new SiglusSurfaceBinding();
        binding.surfaceCreated();
        Window window = new Window();
        window.occupied = true;
        assertFalse(binding.restoreDirect(false, window));
        window.occupied = false; // The previous consumer finishes disconnecting.
        assertTrue(binding.restoreDirect(false, window));
        assertEquals(2, window.connects);
        assertEquals(0, window.resizes);
    }
}
