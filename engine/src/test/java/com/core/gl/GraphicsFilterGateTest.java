package com.core.gl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Shared launch gate for every filter host. The same contract must hold for Siglus,
 * RealLive / AVG32 / UK2 and ONScripter, so the cases live next to the gate instead of
 * being duplicated per host.
 */
public final class GraphicsFilterGateTest {
    @Test
    public void disabledOrUnparsableRequestsKeepTheClassicPath() {
        assertNull(GraphicsFilterGate.requestedChain(false, "v1:s,lanczos"));
        assertNull(GraphicsFilterGate.requestedChain(false, "invalid"));
        assertNull(GraphicsFilterGate.requestedChain(false, null));
        assertNull(GraphicsFilterGate.requestedChain(true, null));
        assertNull(GraphicsFilterGate.requestedChain(true, ""));
        assertNull(GraphicsFilterGate.requestedChain(true, "v3:s"));
        // Unknown nodes must fail the whole parse instead of silently shortening the chain.
        assertNull(GraphicsFilterGate.requestedChain(true, "v1:unknown,s"));
    }

    @Test
    public void inactiveChainsKeepTheClassicPath() {
        assertNull(GraphicsFilterGate.requestedChain(true, "v1:"));
        assertNull(GraphicsFilterGate.requestedChain(true, "v1:!s,!lanczos"));
    }

    @Test
    public void activeChainsPreserveOrderAndPause() {
        GraphicsFilterChain enabled = GraphicsFilterGate.requestedChain(true, "v1:s,!lanczos,s");
        assertNotNull(enabled);
        assertEquals("v1:s,!lanczos,s", enabled.encode());
        assertTrue(GraphicsFilterGate.requestedChain(true, "v1:m,!deblur,lanczos").hasActive());
    }
}
