package com.core.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EngineUiTextTest {
    @Test public void missingHostContextReturnsEmptyText() {
        assertEquals("", EngineUiText.get(null, 0));
    }
}
