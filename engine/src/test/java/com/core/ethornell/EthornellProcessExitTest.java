package com.core.ethornell;

import org.junit.Test;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class EthornellProcessExitTest {
    @Test public void noExitBeforeAcknowledgementOrOutsideOwnEngineProcess() {
        EthornellProcessExit policy = new EthornellProcessExit();
        AtomicInteger finishes = new AtomicInteger(), kills = new AtomicInteger();
        policy.afterCleanup(true, false, finishes::incrementAndGet, kills::incrementAndGet);
        policy.afterCleanup(false, true, finishes::incrementAndGet, kills::incrementAndGet);
        assertEquals(0, finishes.get()); assertEquals(0, kills.get());
        policy.afterCleanup(true, true, finishes::incrementAndGet, kills::incrementAndGet);
        policy.afterCleanup(true, true, finishes::incrementAndGet, kills::incrementAndGet);
        assertEquals(1, finishes.get()); assertEquals(1, kills.get());
    }
    @Test public void closedAndDestroyAckRemoveCachedProcessBusyConditionAllowingReopen() throws Exception {
        Queue<EthornellSession.Event> events = new ConcurrentLinkedQueue<>();
        AtomicBoolean processAlive = new AtomicBoolean(true), destroyed = new AtomicBoolean();
        CountDownLatch requested = new CountDownLatch(1), exited = new CountDownLatch(1);
        EthornellProcessExit exit = new EthornellProcessExit();
        EthornellSession session = new EthornellSession(1, backend(events, false, requested, destroyed),
                listener(exit, processAlive, destroyed, exited), () -> fail("unexpected fault termination"));
        session.requestClose(); assertTrue(requested.await(3, TimeUnit.SECONDS));
        assertTrue("WM_CLOSE without Closed must leave game alive", processAlive.get());
        assertFalse(destroyed.get());
        events.add(new EthornellSession.Event(3, 0, 0, ""));
        session.pause(EthornellSession.PAUSE_FOCUS, true); // poll event without advancing narrative
        assertTrue(exited.await(3, TimeUnit.SECONDS));
        assertTrue(destroyed.get());
        assertFalse("cleared identity plus cached live host must not remain BUSY", processAlive.get());
        // With no live host the launcher's conservative guard allows creating a new process/session.
        assertEquals(EthornellSession.State.CLOSED, session.state());
        AtomicBoolean newDestroyed = new AtomicBoolean();
        CountDownLatch reopened = new CountDownLatch(1);
        EthornellSession second = new EthornellSession(2, backend(new ConcurrentLinkedQueue<>(), false,
                reopened, newDestroyed), listener(new EthornellProcessExit(), new AtomicBoolean(true),
                newDestroyed, new CountDownLatch(1)), () -> fail("unexpected fault termination"));
        second.requestClose(); assertTrue(reopened.await(3, TimeUnit.SECONDS));
        second.close();
    }
    @Test public void failedInitializationCleanupAlsoRemovesCachedBusyHost() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true), destroyed = new AtomicBoolean();
        CountDownLatch exited = new CountDownLatch(1);
        EthornellSession session = new EthornellSession(3, backend(new ConcurrentLinkedQueue<>(), true,
                new CountDownLatch(1), destroyed), listener(new EthornellProcessExit(), processAlive,
                destroyed, exited), () -> fail("unexpected fault termination"));
        assertTrue(exited.await(3, TimeUnit.SECONDS));
        assertFalse(processAlive.get()); assertEquals(EthornellSession.State.CLOSED, session.state());
    }
    private EthornellSession.Listener listener(EthornellProcessExit exit, AtomicBoolean processAlive,
                                               AtomicBoolean destroyed, CountDownLatch exited) {
        return new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failed(long token) {}
            @Override public void closed(long token) {
                exit.afterCleanup(true, true, () -> {}, () -> processAlive.set(false)); exited.countDown();
            }
        };
    }
    private EthornellSession.Backend backend(Queue<EthornellSession.Event> events, boolean failCreate,
                                             CountDownLatch requested, AtomicBoolean destroyed) {
        return new EthornellSession.Backend() {
            @Override public long create() { if (failCreate) throw new IllegalStateException("init failure"); return 1; }
            @Override public void attach(long h, Object s, int w, int height, long generation) {}
            @Override public void detach(long h, long generation) {}
            @Override public void pause(long h, int reasons) {}
            @Override public void input(long h, EthornellSession.Input input) {}
            @Override public void step(long h, long time) {}
            @Override public EthornellSession.Event poll(long h) { return events.poll(); }
            @Override public void respond(long h, long requestId, int answer) {}
            @Override public void requestClose(long h) { requested.countDown(); }
            @Override public void destroy(long h) { destroyed.set(true); }
        };
    }
}
