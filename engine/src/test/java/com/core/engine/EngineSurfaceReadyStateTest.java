package com.core.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class EngineSurfaceReadyStateTest {
    @Test
    public void onlyOneCopyCanBeInFlight() {
        EngineSurfaceReadyState state = new EngineSurfaceReadyState();
        state.start(4L);

        EngineSurfaceReadyState.Request first = state.beginRequest();

        assertNotNull(first);
        assertNull(state.beginRequest());
        assertEquals(EngineSurfaceReadyState.Outcome.RETRY,
                state.complete(first, 4L, EngineSurfaceReadyState.CopyResult.NO_DATA));
        assertFalse(state.hasInFlightRequest());
        assertNotNull(state.beginRequest());
    }

    @Test
    public void pauseInvalidatesOutstandingCopyAndResumeUsesNewEpoch() {
        EngineSurfaceReadyState state = new EngineSurfaceReadyState();
        state.start(1L);
        EngineSurfaceReadyState.Request beforePause = state.beginRequest();
        state.stop();
        state.start(1L);

        assertEquals(EngineSurfaceReadyState.Outcome.STALE,
                state.complete(beforePause, 1L, EngineSurfaceReadyState.CopyResult.SUCCESS));
        assertFalse(state.isReady());
        assertTrue(state.isActive());
        assertNotNull(state.beginRequest());
    }

    @Test
    public void changedSurfaceGenerationRejectsOldSuccess() {
        EngineSurfaceReadyState state = new EngineSurfaceReadyState();
        state.start(8L);
        EngineSurfaceReadyState.Request old = state.beginRequest();
        state.updateGeneration(9L);

        assertEquals(EngineSurfaceReadyState.Outcome.STALE,
                state.complete(old, 9L, EngineSurfaceReadyState.CopyResult.SUCCESS));
        assertFalse(state.isReady());
        EngineSurfaceReadyState.Request current = state.beginRequest();
        assertNotNull(current);
        assertEquals(9L, current.generation);
        assertEquals(EngineSurfaceReadyState.Outcome.READY,
                state.complete(current, 9L, EngineSurfaceReadyState.CopyResult.SUCCESS));
        assertTrue(state.isReady());
    }

    @Test
    public void replacedSurfaceWithSameGenerationRejectsOldSuccessAndRetries() {
        EngineSurfaceReadyState state = new EngineSurfaceReadyState();
        state.start(11L);
        EngineSurfaceReadyState.Request old = state.beginRequest();

        assertTrue(state.invalidateCurrentSource());
        assertEquals(EngineSurfaceReadyState.Outcome.STALE,
                state.complete(old, 11L, EngineSurfaceReadyState.CopyResult.SUCCESS));
        assertFalse(state.isReady());
        assertTrue(state.isActive());

        EngineSurfaceReadyState.Request current = state.beginRequest();
        assertNotNull(current);
        assertEquals(11L, current.generation);
        assertEquals(EngineSurfaceReadyState.Outcome.READY,
                state.complete(current, 11L, EngineSurfaceReadyState.CopyResult.SUCCESS));
        assertTrue(state.isReady());
    }

    @Test
    public void disposedProbeIgnoresLateSuccess() {
        EngineSurfaceReadyState state = new EngineSurfaceReadyState();
        state.start(2L);
        EngineSurfaceReadyState.Request request = state.beginRequest();
        state.dispose();

        assertEquals(EngineSurfaceReadyState.Outcome.STALE,
                state.complete(request, 2L, EngineSurfaceReadyState.CopyResult.SUCCESS));
        assertFalse(state.isReady());
        assertTrue(state.isDisposed());
        assertNull(state.beginRequest());
    }

    @Test
    public void failureIsTerminalAndLateSuccessCannotReveal() {
        EngineSurfaceReadyState state = new EngineSurfaceReadyState();
        state.start(5L);
        EngineSurfaceReadyState.Request request = state.beginRequest();

        assertTrue(state.fail());
        assertEquals(EngineSurfaceReadyState.Outcome.STALE,
                state.complete(request, 5L, EngineSurfaceReadyState.CopyResult.SUCCESS));
        assertFalse(state.start(5L));
        assertFalse(state.isReady());
        assertTrue(state.isFailed());
    }

    @Test
    public void permanentCopyErrorFailsButNoDataCanRetry() {
        EngineSurfaceReadyState state = new EngineSurfaceReadyState();
        state.start(3L);
        EngineSurfaceReadyState.Request empty = state.beginRequest();
        assertEquals(EngineSurfaceReadyState.Outcome.RETRY,
                state.complete(empty, 3L, EngineSurfaceReadyState.CopyResult.NO_DATA));
        EngineSurfaceReadyState.Request invalid = state.beginRequest();
        assertEquals(EngineSurfaceReadyState.Outcome.FAILURE,
                state.complete(invalid, 3L, EngineSurfaceReadyState.CopyResult.SOURCE_INVALID));
        assertFalse(state.isReady());
    }
}
