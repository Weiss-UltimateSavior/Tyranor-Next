package com.core.gl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ArtemisOfficialOutputSizeTest {
    @Test
    public void sameOutputCanPublishResizedDisplayWithoutChangingStage() {
        ArtemisOfficialOutputSize size = new ArtemisOfficialOutputSize();
        final int stageWidth = 1280, stageHeight = 720;
        size.presented(2560, 1600);
        assertEquals(((long) 2560 << 32) | 1600L, size.get());
        long original = size.get();
        GraphicsOutputSize.Size before = GraphicsOutputSize.fit(stageWidth, stageHeight,
                (int) (original >>> 32), (int) original);
        assertEquals(2560, before.width);
        assertEquals(1440, before.height);
        size.presented(1600, 2560);
        long resized = size.get();
        GraphicsOutputSize.Size after = GraphicsOutputSize.fit(stageWidth, stageHeight,
                (int) (resized >>> 32), (int) resized);
        assertEquals(1600, after.width);
        assertEquals(900, after.height);
    }

    @Test
    public void invalidQueryCannotReplaceLastSuccessfulDisplay() {
        ArtemisOfficialOutputSize size = new ArtemisOfficialOutputSize();
        size.presented(2133, 1200);
        long displayed = size.get();
        size.presented(0, 1200);
        size.presented(2133, -1);
        assertEquals(displayed, size.get());
    }

    @Test
    public void reconnectStartsWithoutPreviousOutputsDimensions() {
        ArtemisOfficialOutputSize size = new ArtemisOfficialOutputSize();
        assertEquals(0L, size.get());
        size.presented(2560, 1600);
        size.clear();
        assertEquals(0L, size.get());
        size.presented(2133, 1200);
        assertEquals(((long) 2133 << 32) | 1200L, size.get());
    }
}
