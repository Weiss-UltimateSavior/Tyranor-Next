package com.core.gl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 共享 EGL 清理退避计算的契约回归。 */
public final class EglTeardownTest {

    @Test
    public void retryDelayDoublesAcrossFourBackoffIntervals() {
        assertEquals(1000L, EglTeardown.retryDelayMs(1000L, 1));
        assertEquals(2000L, EglTeardown.retryDelayMs(1000L, 2));
        assertEquals(4000L, EglTeardown.retryDelayMs(1000L, 3));
        assertEquals(8000L, EglTeardown.retryDelayMs(1000L, 4));
    }

    @Test
    public void retryDelayClampsInvalidAndOverflowInputs() {
        assertEquals(0L, EglTeardown.retryDelayMs(0L, 1));
        assertEquals(0L, EglTeardown.retryDelayMs(-500L, 2));
        assertEquals(1000L, EglTeardown.retryDelayMs(1000L, 0));
        assertEquals(1000L, EglTeardown.retryDelayMs(1000L, -1));
        assertEquals(1000L, EglTeardown.retryDelayMs(1000L, Integer.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, EglTeardown.retryDelayMs(Long.MAX_VALUE, 5));
        assertEquals(1000L << 30, EglTeardown.retryDelayMs(1000L, 64));
    }
}
