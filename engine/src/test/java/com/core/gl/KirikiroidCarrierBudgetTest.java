package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class KirikiroidCarrierBudgetTest {
    @Test public void includesNpotPaddingAndBothPossibleColorCopies() {
        assertEquals(8L, KirikiroidRawFrameBridge.carrierBytes126(1, 1));
        assertEquals(8L * 2048 * 1024, KirikiroidRawFrameBridge.carrierBytes126(1280, 720));
        assertEquals(8L * 1024 * 1024, KirikiroidRawFrameBridge.carrierBytes126(1024, 1024));
        assertEquals(8L * 16384 * 16384, KirikiroidRawFrameBridge.carrierBytes126(16384, 16384));
    }

    @Test public void rejectsUnrepresentableCarrierGeometry() {
        assertEquals(Long.MAX_VALUE, KirikiroidRawFrameBridge.carrierBytes126(0, 720));
        assertEquals(Long.MAX_VALUE, KirikiroidRawFrameBridge.carrierBytes126(-1, 720));
        assertEquals(Long.MAX_VALUE, KirikiroidRawFrameBridge.carrierBytes126(1280, 16385));
    }
}
