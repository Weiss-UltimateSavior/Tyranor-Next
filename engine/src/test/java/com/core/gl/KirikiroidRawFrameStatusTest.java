package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class KirikiroidRawFrameStatusTest {
    @Test public void reportsMissingEntryInsteadOfPretendingScreenFramesAreRaw() {
        assertEquals(GraphicsStatus.MISSING_ABI, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.UNSUPPORTED_LIBRARY));
        assertEquals(GraphicsStatus.MISSING_ABI, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.NATIVE_UNAVAILABLE));
        assertEquals(GraphicsStatus.NATIVE_FAILED, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.NATIVE_BUILD_ID));
        assertEquals(GraphicsStatus.NATIVE_FAILED, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.NATIVE_CALLBACK));
        assertEquals(GraphicsStatus.FILTER_FAILED, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.FILTER_UNAVAILABLE));
    }

    @Test public void preparationStatesRemainWaitingUntilEntryIsInstalled() {
        assertEquals(GraphicsStatus.WAITING, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.UNCONFIGURED));
        assertEquals(GraphicsStatus.WAITING, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.WAITING_FOR_CONTEXT));
        assertEquals(GraphicsStatus.WAITING, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.DISABLED));
        assertEquals(GraphicsStatus.READY, KirikiroidRawFrameBridge.graphicsStatus(
                KirikiroidRawFrameBridge.InstallStatus.INSTALLED));
    }
}
