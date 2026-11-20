package com.core.gl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the singleMode latch contract of the Artemis single-context stage entry:
 * only requested, fully validated invocations (parameters and a current EGL context) may
 * latch {@code singleMode} and publish the logical output size, and the native teardown
 * entries (disconnect / contextDestroyed) reset it together with the construction-failure
 * budget (context recreation rebuilds from a fresh budget).
 *
 * <p>JVM boundary: renderStage cannot reach pipeline construction or GL work on the JVM (the
 * EGL context is unavailable), so these tests pin the latch/teardown state machine only. The
 * bounded construction-failure backoff lives behind the same EGL gate and is verified by source
 * review plus build/regression gates; it is not JVM-pinnable without an EGL fake.</p>
 */
public final class ArtemisOfficialSingleModeTest {
    private boolean savedRequested, savedAnalyzer, savedSingleMode;
    private int savedState, savedFailureCount;
    private Object savedProxy;

    @Before
    public void snapshotAndResetStatics() throws Exception {
        savedRequested = getBoolean("requested");
        savedAnalyzer = getBoolean("analyzer");
        savedSingleMode = getBoolean("singleMode");
        savedState = getInt("state");
        savedFailureCount = getInt("singlePipelineConstructionFailures");
        savedProxy = getObject("proxy");
        setBoolean("requested", false);
        setBoolean("analyzer", false);
        setBoolean("singleMode", false);
        setInt("state", GraphicsStatus.WAITING);
        setInt("singlePipelineConstructionFailures", 0);
        setObject("proxy", null);
        ((ArtemisOfficialOutputSize) getObject("singleOutputSize")).clear();
        ((ArtemisGraphicsStats) getObject("singleStats")).reset();
    }

    @After
    public void restoreStatics() throws Exception {
        setBoolean("requested", savedRequested);
        setBoolean("analyzer", savedAnalyzer);
        setBoolean("singleMode", savedSingleMode);
        setInt("state", savedState);
        setInt("singlePipelineConstructionFailures", savedFailureCount);
        setObject("proxy", savedProxy);
        setObject("singlePipeline", null);
        setObject("singlePipelineContext", android.opengl.EGL14.EGL_NO_CONTEXT);
        Field gen = ArtemisOfficialBridge.class.getDeclaredField("singlePipelineContextGeneration");
        gen.setAccessible(true);
        gen.setLong(null, -1L);
        setInt("singleStageWidth", 0);
        setInt("singleStageHeight", 0);
        ((ArtemisOfficialOutputSize) getObject("singleOutputSize")).clear();
        ((ArtemisGraphicsStats) getObject("singleStats")).reset();
    }

    @Test
    public void nonRequestedRenderStageMustNotLatchSingleMode() throws Exception {
        // Non-requested invocations must not latch a live single-context claim for a filter
        // that was never requested.
        assertEquals(0, ArtemisOfficialBridge.renderStage(0, 0, 0, 0, 0, false, false, 1L));
        assertFalse("Non-requested renderStage must not latch singleMode", getBoolean("singleMode"));
    }

    @Test
    public void requestedRenderStageWithMissingEglContextDoesNotLatchSingleMode() throws Exception {
        setBoolean("requested", true);
        // Valid parameters but no current EGL context (JVM): analytics/health must not claim
        // a live single-context path, and no logical output size may be published.
        Object outputSize = getObject("singleOutputSize");
        long before = ((ArtemisOfficialOutputSize) outputSize).get();
        assertEquals(0, ArtemisOfficialBridge.renderStage(7, 640, 360, 640, 360, true, true, 1L));
        assertFalse("Missing EGL context must not latch singleMode", getBoolean("singleMode"));
        assertEquals("Missing EGL context must not publish an output size",
                before, ((ArtemisOfficialOutputSize) getObject("singleOutputSize")).get());
    }

    @Test
    public void nativeTeardownEntriesResetTheSingleModeLatch() throws Exception {
        setBoolean("requested", true);
        setBoolean("singleMode", true);
        ArtemisOfficialBridge.contextDestroyed();
        assertFalse("contextDestroyed must reset singleMode", getBoolean("singleMode"));

        setBoolean("singleMode", true);
        assertEquals(ArtemisOfficialDisconnect.DETACHED, ArtemisOfficialBridge.disconnect());
        assertFalse("disconnect must reset singleMode", getBoolean("singleMode"));
    }

    @Test
    public void prepareLifecycleAndCloseClearTheLatch() throws Exception {
        setBoolean("requested", true);
        setBoolean("singleMode", true);
        ArtemisOfficialBridge.close();
        assertFalse("close must reset singleMode", getBoolean("singleMode"));
    }

    @Test
    public void closeResetsSinglePipelineConstructionFailureCount() throws Exception {
        setInt("singlePipelineConstructionFailures", 4);
        ArtemisOfficialBridge.close();
        assertEquals("close must reset construction failure count",
                0, getInt("singlePipelineConstructionFailures"));
    }

    @Test
    public void contextDestroyedResetsSinglePipelineConstructionFailureCount() throws Exception {
        // Context recreation rebuilds from a fresh budget: a fresh EGLContext must not
        // inherit the spent deterministic-failure circuit breaker of the torn-down one.
        setInt("singlePipelineConstructionFailures", 4);
        ArtemisOfficialBridge.contextDestroyed();
        assertEquals("contextDestroyed must reset construction failure count",
                0, getInt("singlePipelineConstructionFailures"));
    }

    private static boolean getBoolean(String name) throws Exception {
        Field f = ArtemisOfficialBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static int getInt(String name) throws Exception {
        Field f = ArtemisOfficialBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static Object getObject(String name) throws Exception {
        Field f = ArtemisOfficialBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setBoolean(String name, boolean value) throws Exception {
        Field f = ArtemisOfficialBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    private static void setInt(String name, int value) throws Exception {
        Field f = ArtemisOfficialBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(null, value);
    }

    private static void setObject(String name, Object value) throws Exception {
        Field f = ArtemisOfficialBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
