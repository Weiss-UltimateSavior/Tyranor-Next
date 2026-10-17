package com.core.ethornell;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class EthornellLifecycleTest {
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SUFFIX = " identity_sha256=" + DIGEST + " token=42";

    @Test public void evidenceFollowsObservedClosedAndActualNativeDestroyReturn() {
        List<String> evidence = new ArrayList<>();
        EthornellLifecycle lifecycle = new EthornellLifecycle(DIGEST, 42, evidence::add);
        assertEquals("EthornellLifecycle", EthornellLifecycle.TAG);
        lifecycle.observe(new EthornellSession.Event(2, 7, 0, "private dialogue /private/user/game"));
        assertTrue(evidence.isEmpty()); // A request (including WM_CLOSE confirmation) is not Closed.
        lifecycle.observe(new EthornellSession.Event(3, 0, 0, "private event text"));
        lifecycle.destroy(() -> {
            assertEquals(Arrays.asList("closed_event" + SUFFIX), evidence);
            evidence.add("native_destroy_return");
        });
        assertEquals(Arrays.asList("closed_event" + SUFFIX, "native_destroy_return", "destroy_ack" + SUFFIX), evidence);
    }

    @Test public void failedNativeDestroyCannotProduceAcknowledgementOrFabricatedClosed() {
        List<String> evidence = new ArrayList<>();
        EthornellLifecycle lifecycle = new EthornellLifecycle(DIGEST, 42, evidence::add);
        try {
            lifecycle.destroy(() -> { throw new IllegalStateException("private path /private/user/game"); });
            fail("native failure must propagate");
        } catch (IllegalStateException expected) { /* The caller's existing failure path handles it. */ }
        assertTrue(evidence.isEmpty());
        lifecycle.failed();
        assertEquals(Arrays.asList("failed" + SUFFIX), evidence);
    }

    @Test public void cleanupWithoutRuntimeClosedDoesNotInventClosedEvent() {
        List<String> evidence = new ArrayList<>();
        EthornellLifecycle lifecycle = new EthornellLifecycle(DIGEST, 42, evidence::add);
        lifecycle.failed(); lifecycle.destroy(() -> {});
        assertEquals(Arrays.asList("failed" + SUFFIX, "destroy_ack" + SUFFIX), evidence);
    }

    @Test public void invalidDigestCannotInjectPathsOrAdditionalLogLines() {
        for (String invalid : new String[]{"/private/user/game", DIGEST + "\nclosed_event", "", DIGEST.toUpperCase(java.util.Locale.ROOT)}) {
            try { new EthornellLifecycle(invalid, 42, message -> fail("must not log")); fail("must reject"); }
            catch (IllegalArgumentException expected) { /* Only the canonical lowercase digest is accepted. */ }
        }
    }
    @Test public void diagnosticSchemaDistinguishesUpdatedHostFromStaleInstalledApk() {
        List<String> evidence = new ArrayList<>();
        EthornellLifecycle lifecycle = new EthornellLifecycle(DIGEST, 42, evidence::add);
        lifecycle.failedDetail("operation=step status=-9 detail_state=absent");
        assertEquals(Arrays.asList("failed_detail" + SUFFIX + " diagnostic_schema=2 operation=step status=-9 detail_state=absent"), evidence);
    }
}
