package com.core.gl;

import android.opengl.GLES30;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.function.IntSupplier;

import static org.junit.Assert.*;

/** Error-boundary contract of the shared Level-0 {@link GlErrors} discipline. */
public class GlErrorsBoundaryTest {
    @Test public void allocationPressureDoesNotLeakIntoHostRestoration() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY,
                GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        assertThrows(TargetUnavailableException.class,
                () -> GlErrors.requireGlSuccess("render target allocation", true, errors));
        assertTrue(errors.pending.isEmpty());

        // The bypass can release partial targets and restore the host without
        // promoting the original allocation rejection into a permanent failure.
        GlErrors.requireGlSuccess("render target cleanup", false, errors);
        GlErrors.requireGlSuccess("filter state restoration", false, errors);
        // A later allocation (for changed geometry) starts with a clean boundary.
        GlErrors.requireGlSuccess("render target allocation", true, errors);
    }

    @Test public void genuineRestorationFailureIsStillPermanent() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY);
        assertThrows(TargetUnavailableException.class,
                () -> GlErrors.requireGlSuccess("render target allocation", true, errors));
        errors.pending.add(GLES30.GL_INVALID_OPERATION);
        assertThrows(IllegalStateException.class,
                () -> GlErrors.requireGlSuccess("filter state restoration", false, errors));
        assertTrue(errors.pending.isEmpty());
    }

    @Test public void invalidAllocationApiUseIsNotMaskedByMemoryPressure() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY, GLES30.GL_INVALID_ENUM,
                GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        assertThrows(IllegalStateException.class,
                () -> GlErrors.requireGlSuccess("render target allocation", true, errors));
        assertTrue(errors.pending.isEmpty());
    }

    @Test public void drawingErrorsDrainAllFlagsAndRemainPermanent() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY,
                GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        assertThrows(IllegalStateException.class,
                () -> GlErrors.requireGlSuccess("filter node drawing", false, errors));
        assertTrue(errors.pending.isEmpty());
        GlErrors.requireGlSuccess("filter state restoration", false, errors);
    }

    @Test public void unclearedContextIsBoundedAndNeverRetryable() {
        int[] reads = {0};
        assertThrows(IllegalStateException.class,
                () -> GlErrors.requireGlSuccess("render target allocation", true,
                        () -> { reads[0]++; return GLES30.GL_OUT_OF_MEMORY; }));
        assertTrue(reads[0] > 0 && reads[0] <= 16);
    }

    @Test public void zeroReadFramebufferConsumesPressureBeforeRestorationAndRetry() {
        for (String operation : new String[]{"borrowed read framebuffer", "tile read framebuffer"}) {
            ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY,
                    GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
            assertThrows(TargetUnavailableException.class,
                    () -> GlErrors.requireTargetName(0, operation, errors));
            assertTrue(errors.pending.isEmpty());
            GlErrors.requireGlSuccess("filter state restoration", false, errors);
            // A later allocation after geometry reduction is not blocked by old errors.
            GlErrors.requireTargetName(7, operation, errors);
        }
    }

    @Test public void zeroReadFramebufferWithoutAnErrorRemainsATargetRejection() {
        ErrorQueue errors = new ErrorQueue();
        assertThrows(TargetUnavailableException.class,
                () -> GlErrors.requireTargetName(0, "read framebuffer", errors));
        GlErrors.requireTargetName(1, "read framebuffer", errors);
    }

    @Test public void readFramebufferApiErrorsAreStillPermanentAndFullyConsumed() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY, GLES30.GL_INVALID_OPERATION);
        assertThrows(IllegalStateException.class,
                () -> GlErrors.requireTargetName(0, "read framebuffer", errors));
        assertTrue(errors.pending.isEmpty());
    }

    @Test public void issuedReadFramebufferNameDoesNotMaskAllocationErrors() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY);
        assertThrows(TargetUnavailableException.class,
                () -> GlErrors.requireTargetName(7, "read framebuffer", errors));
        assertTrue(errors.pending.isEmpty());
    }

    @Test public void pressureAndAttachmentRejectionsPermitDelayedRetry() {
        TargetUnavailableException pressure = assertThrows(
                TargetUnavailableException.class,
                () -> GlErrors.requireGlSuccess("render target allocation", true,
                        new ErrorQueue(GLES30.GL_OUT_OF_MEMORY)));
        assertTrue(pressure.retryAfterDelay);
        TargetUnavailableException zeroName = assertThrows(
                TargetUnavailableException.class,
                () -> GlErrors.requireTargetName(0, "read framebuffer", new ErrorQueue()));
        assertTrue(zeroName.retryAfterDelay);
        TargetUnavailableException attachment = assertThrows(
                TargetUnavailableException.class,
                () -> GlErrors.requireGlSuccess("borrowed texture attachment", true,
                        new ErrorQueue(GLES30.GL_INVALID_FRAMEBUFFER_OPERATION)));
        assertTrue(attachment.retryAfterDelay);
    }

    @Test public void compileShaderDeletesNonZeroShaderWhenNameCheckFails() {
        int[] deleted = {0};
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY);
        GlOps fake = new StrictStubGlOps() {
            @Override public int glGetError() { return errors.getAsInt(); }
            @Override public int glCreateShader(int type) { return 42; }
            @Override public void glShaderSource(int shader, String string) {}
            @Override public void glCompileShader(int shader) {}
            @Override public void glGetShaderiv(int shader, int pname, int[] params, int offset) {}
            @Override public void glDeleteShader(int shader) { deleted[0] += shader; }
        };
        assertThrows(TargetUnavailableException.class,
                () -> GlOps.compileShader(fake, GLES30.GL_FRAGMENT_SHADER, "void main(){}", new int[1]));
        assertEquals("Created shader must be deleted once on requireTargetName failure", 42, deleted[0]);
    }

    @Test public void contextLostStopsImmediatelyAndIsNeverRetryable() {
        ErrorQueue errors = new ErrorQueue(GLES30.GL_OUT_OF_MEMORY, GlErrors.GL_CONTEXT_LOST_KHR,
                GLES30.GL_INVALID_FRAMEBUFFER_OPERATION);
        assertThrows(GlErrors.ContextLostException.class,
                () -> GlErrors.requireGlSuccess("render target allocation", true, errors));
        // Stopped early on GL_CONTEXT_LOST_KHR without draining trailing entries blindly.
        assertEquals(1, errors.pending.size());

        assertEquals(GlErrors.GL_CONTEXT_LOST_KHR, GlErrors.beginGlOperations(
                new ErrorQueue(GLES30.GL_INVALID_ENUM, GlErrors.GL_CONTEXT_LOST_KHR)));
        assertEquals(-1, GlErrors.beginGlOperations(() -> GLES30.GL_INVALID_OPERATION));

        assertFalse(GlErrors.drainAndDetectContextLost(GlErrors.GL_NO_ERROR, () -> {
            throw new AssertionError("Should not poll when initial error is GL_NO_ERROR");
        }));
        assertTrue(GlErrors.drainAndDetectContextLost(GLES30.GL_INVALID_ENUM,
                new ErrorQueue(GlErrors.GL_CONTEXT_LOST_KHR)));
    }

    private static final class ErrorQueue implements IntSupplier {
        final ArrayDeque<Integer> pending = new ArrayDeque<>();
        ErrorQueue(int... errors) { for (int error : errors) pending.add(error); }
        @Override public int getAsInt() {
            return pending.isEmpty() ? GLES30.GL_NO_ERROR : pending.removeFirst();
        }
    }
}
