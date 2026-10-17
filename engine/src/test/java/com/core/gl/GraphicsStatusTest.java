package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GraphicsStatusTest {
    @Test public void restoredDirectOutputPreservesEnhancementFailureReason() {
        for (int reason : new int[]{GraphicsStatus.MISSING_ABI, GraphicsStatus.GLES3_UNAVAILABLE,
                GraphicsStatus.NATIVE_FAILED, GraphicsStatus.FILTER_FAILED}) {
            assertEquals(reason, GraphicsStatus.preserveFailure(reason, GraphicsStatus.WAITING));
            assertEquals(reason, GraphicsStatus.preserveFailure(reason, GraphicsStatus.READY));
        }
        assertEquals(GraphicsStatus.READY, GraphicsStatus.preserveFailure(GraphicsStatus.WAITING, GraphicsStatus.READY));
        assertEquals(GraphicsStatus.WAITING, GraphicsStatus.preserveFailure(GraphicsStatus.SIZE_UNAVAILABLE, GraphicsStatus.WAITING));
        assertEquals(GraphicsStatus.NATIVE_FAILED, GraphicsStatus.preserveFailure(GraphicsStatus.WAITING, GraphicsStatus.NATIVE_FAILED));
    }
}
