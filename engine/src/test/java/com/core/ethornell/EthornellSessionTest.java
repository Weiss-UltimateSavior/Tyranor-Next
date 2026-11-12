package com.core.ethornell;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class EthornellSessionTest {
    private static class Fake implements EthornellSession.Backend {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<String> answers = Collections.synchronizedList(new ArrayList<>());
        final Queue<EthornellSession.Event> events = new ConcurrentLinkedQueue<>();
        final Set<Long> retried = Collections.synchronizedSet(new HashSet<>());
        final CountDownLatch ready = new CountDownLatch(1), closed = new CountDownLatch(1);
        final CountDownLatch requested = new CountDownLatch(1);
        final AtomicInteger payloadReads = new AtomicInteger();
        volatile CountDownLatch blockStep, enteredStep;
        volatile String payload = "{}";
        volatile long failingPayloadId;
        volatile RuntimeException payloadFailure;
        volatile long retryOnAnswer;
        volatile long followUpAfter;
        volatile EthornellSession.Event followUp;
        @Override public long create() { calls.add("create:" + Thread.currentThread().getName()); ready.countDown(); return 7; }
        @Override public void attach(long h, Object s, int w, int height, long g) { calls.add("attach:" + g); }
        @Override public void detach(long h, long g) { calls.add("detach:" + g); }
        @Override public void pause(long h, int mask) { calls.add("pause:" + mask); }
        @Override public void input(long h, EthornellSession.Input i) { calls.add("input:" + i.kind + ":" + i.code); }
        @Override public void step(long h, long now) {
            calls.add("step:" + now);
            if (enteredStep != null) enteredStep.countDown();
            if (blockStep != null) await(blockStep);
        }
        @Override public EthornellSession.Event poll(long h) { return events.poll(); }
        @Override public String requestPayload(long h, long id) {
            calls.add("payload:" + id + ":" + Thread.currentThread().getName());
            payloadReads.incrementAndGet();
            if (payloadFailure != null && id == failingPayloadId) throw payloadFailure;
            return payload;
        }
        @Override public void respond(long h, long id, int result, String text) {
            calls.add("response:" + id + ":" + result);
            answers.add(id + ":" + result + ":" + text);
            if (retryOnAnswer == id && retried.add(id)) events.add(new EthornellSession.Event(2, id, 0, "retry"));
            if (followUp != null && followUpAfter == id) { events.add(followUp); followUp = null; }
        }
        @Override public void requestClose(long h) { calls.add("requestClose"); requested.countDown(); }
        @Override public void destroy(long h) { calls.add("destroy"); closed.countDown(); }
    }
    private static final class Recorder implements EthornellSession.Listener {
        final List<EthornellSession.Event> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> details = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failures = new AtomicInteger(), fatals = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public void event(long token, EthornellSession.Event event) { events.add(event); }
        @Override public void failed(long token) { failures.incrementAndGet(); }
        @Override public void failedDetail(long token, String safeDetail) { details.add(safeDetail); }
        @Override public void closed(long token) { closed.countDown(); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue("timed out", latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    private static void awaitTrue(String message, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(2); } catch (InterruptedException e) { throw new AssertionError(e); }
        }
        fail(message);
    }
    /** FIFO commands mean the final requestClose has already observed every earlier poll. */
    private static void drainPolls(EthornellSession session, Fake backend) {
        session.requestClose(); await(backend.requested);
    }
    private EthornellSession start(Fake backend, AtomicInteger fatals) {
        return new EthornellSession(42, backend, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failed(long token) { fail("unexpected runtime failure"); }
            @Override public void closed(long token) {}
        }, fatals::incrementAndGet);
    }
    private EthornellSession start(Fake backend, Recorder listener) {
        return new EthornellSession(42, backend, listener, listener.fatals::incrementAndGet);
    }
    private void resume(EthornellSession session) {
        session.pause(1, false); session.pause(2, false); session.pause(4, false);
    }
    @Test public void clockDoesNotSpeedUpOn120HzOrReplayDelayedFrames() {
        EthornellSession.Clock clock = new EthornellSession.Clock();
        int passes = 0;
        for (int i = 0; i < 120; i++) if (clock.due(1_000_000_000L + i * 8_333_333L)) passes++;
        assertEquals(60, passes);
        assertTrue(clock.due(5_000_000_000L));
        assertFalse(clock.due(5_000_000_001L));
        clock.reset(); assertTrue(clock.due(5_000_000_002L));
    }
    @Test public void fifoEdgesAndIndependentPauseReasons() {
        Fake backend = new Fake(); AtomicInteger fatals = new AtomicInteger();
        EthornellSession session = start(backend, fatals); await(backend.ready);
        session.attach(new Object(), 800, 600, 1); resume(session);
        session.input(new EthornellSession.Input(4, 17, 0, 0, 0));
        session.input(new EthornellSession.Input(5, 17, 0, 0, 0));
        session.pause(4, true); session.pause(1, true); session.pause(1, false);
        session.requestClose(); await(backend.requested);
        session.detachAndWait(1);
        assertTrue(backend.calls.indexOf("input:4:17") < backend.calls.indexOf("input:5:17"));
        assertTrue(backend.calls.contains("pause:5"));
        assertEquals("pause:4", backend.calls.stream().filter(s -> s.startsWith("pause:")).reduce((a,b) -> b).orElse(""));
        assertTrue(backend.calls.contains("input:7:0"));
        session.close(); await(backend.closed); assertEquals(0, fatals.get());
    }
    @Test public void staleGenerationNeverReattachesAndDestroyIsIdempotent() {
        Fake backend = new Fake(); AtomicInteger fatals = new AtomicInteger();
        EthornellSession session = start(backend, fatals); await(backend.ready);
        session.attach(new Object(), 800, 600, 1); session.detachAndWait(1);
        int before = backend.calls.size();
        session.frame(1_000_000_000L, 1);
        session.attach(new Object(), 800, 600, 2); session.detachAndWait(2);
        assertFalse(backend.calls.subList(before, backend.calls.size()).stream().anyMatch(s -> s.startsWith("step:")));
        session.close(); session.close(); await(backend.closed);
        session.detachAndWait(2);
        assertEquals(1, Collections.frequency(backend.calls, "destroy")); assertEquals(0, fatals.get());
    }
    @Test public void slowFrameHasOneLatestTickAndDetachBarrierStopsSurfaceUse() {
        Fake backend = new Fake(); AtomicInteger fatals = new AtomicInteger();
        EthornellSession session = start(backend, fatals); await(backend.ready);
        session.attach(new Object(), 800, 600, 1); resume(session);
        backend.enteredStep = new CountDownLatch(1); backend.blockStep = new CountDownLatch(1);
        session.frame(1_000_000_000L, 1); await(backend.enteredStep);
        for (int i = 1; i <= 100; i++) session.frame(1_000_000_000L + i * 20_000_000L, 1);
        CountDownLatch detached = new CountDownLatch(1);
        Thread ui = new Thread(() -> { session.detachAndWait(1); detached.countDown(); }); ui.start();
        backend.blockStep.countDown(); await(detached);
        long steps = backend.calls.stream().filter(s -> s.startsWith("step:")).count();
        assertTrue(steps == 1 || steps == 2);
        if (steps == 2) assertTrue(backend.calls.contains("step:3000000000"));
        assertTrue(backend.calls.contains("detach:1"));
        session.close(); await(backend.closed); assertEquals(0, fatals.get());
    }
    @Test public void hostResponsesAreBoundToSessionAndSettledOnce() {
        Fake backend = new Fake(); AtomicInteger fatals = new AtomicInteger();
        backend.events.add(new EthornellSession.Event(2, 99, 0, "confirm"));
        EthornellSession session = start(backend, fatals); await(backend.ready);
        session.respond(43, 99, 1); session.respond(42, 99, 1); session.respond(42, 99, 0);
        session.requestClose(); await(backend.requested);
        session.attach(new Object(), 800, 600, 1); session.detachAndWait(1);
        assertEquals(1, Collections.frequency(backend.calls, "response:99:1"));
        assertFalse(backend.calls.contains("response:99:0"));
        session.close(); await(backend.closed); assertEquals(0, fatals.get());
    }
    @Test public void slowInitializationCannotDefeatIndependentDetachSupervisor() {
        CountDownLatch inCreate = new CountDownLatch(1), unblockCreate = new CountDownLatch(1);
        CountDownLatch supervised = new CountDownLatch(1);
        Fake backend = new Fake() {
            @Override public long create() {
                inCreate.countDown(); await(unblockCreate); return super.create();
            }
        };
        EthornellSession session = new EthornellSession(55, backend, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failed(long token) { fail(); }
            @Override public void closed(long token) {}
        }, () -> { supervised.countDown(); unblockCreate.countDown(); }, 2000, 50);
        await(inCreate);
        session.attach(new Object(), 800, 600, 1);
        session.detachAndWait(1);
        await(supervised);
        assertTrue(backend.calls.contains("detach:1"));
        assertFalse(backend.calls.contains("attach:1"));
        session.close(); await(backend.closed);
    }
    @Test public void slowNativeDetachCannotReturnBeforeNativeAcknowledges() {
        CountDownLatch enterDetach = new CountDownLatch(1), unblockDetach = new CountDownLatch(1);
        AtomicInteger supervised = new AtomicInteger();
        Fake backend = new Fake() {
            @Override public void detach(long h, long g) {
                enterDetach.countDown(); await(unblockDetach); super.detach(h, g);
            }
        };
        EthornellSession session = new EthornellSession(56, backend, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failed(long token) { fail(); }
            @Override public void closed(long token) {}
        }, () -> { supervised.incrementAndGet(); unblockDetach.countDown(); }, 2000, 50);
        await(backend.ready); session.attach(new Object(), 800, 600, 1);
        session.detachAndWait(1); await(enterDetach);
        assertEquals(1, supervised.get()); assertTrue(backend.calls.contains("detach:1"));
        session.close(); await(backend.closed);
    }
    @Test public void closeRequestWithoutClosedEventKeepsGameAliveForWmCloseHandler() {
        Fake backend = new Fake(); AtomicInteger fatals = new AtomicInteger();
        CountDownLatch continued = new CountDownLatch(1);
        backend.enteredStep = continued;
        EthornellSession session = start(backend, fatals);
        await(backend.ready);
        session.attach(new Object(), 800, 600, 1); resume(session);
        session.requestClose(); await(backend.requested);
        session.frame(1_000_000_000L, 1); await(continued);
        assertEquals(EthornellSession.State.RUNNING, session.state());
        assertFalse(backend.calls.contains("destroy"));
        assertEquals(1, backend.closed.getCount());
        assertTrue(backend.calls.indexOf("requestClose") < backend.calls.indexOf("step:1000000000"));
        session.close(); await(backend.closed); assertEquals(0, fatals.get());
    }
    @Test public void runtimeClosedEventPrecedesDestroyAndHostClosedCallback() {
        Fake backend = new Fake() {
            @Override public void requestClose(long h) {
                events.add(new EthornellSession.Event(3, 0, 0, "")); super.requestClose(h);
            }
        };
        AtomicInteger fatals = new AtomicInteger();
        CountDownLatch hostClosed = new CountDownLatch(1);
        EthornellSession session = new EthornellSession(57, backend, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {
                if (event.kind == 3) backend.calls.add("closedEvent");
            }
            @Override public void failed(long token) { fail(); }
            @Override public void closed(long token) { backend.calls.add("hostClosed"); hostClosed.countDown(); }
        }, fatals::incrementAndGet);
        await(backend.ready); session.requestClose(); await(hostClosed);
        assertEquals(EthornellSession.State.CLOSED, session.state());
        assertTrue(backend.calls.indexOf("requestClose") < backend.calls.indexOf("closedEvent"));
        assertTrue(backend.calls.indexOf("closedEvent") < backend.calls.indexOf("destroy"));
        assertTrue(backend.calls.indexOf("destroy") < backend.calls.indexOf("hostClosed"));
        assertEquals(0, fatals.get());
    }
    @Test public void actualFailedOperationReportsSafeStatusBeforeCleanup() {
        Fake backend = new Fake() {
            @Override public void step(long h, long now) {
                throw new IllegalStateException("Ethornell status -10: unsupported host/game capability");
            }
        };
        List<String> details = new ArrayList<>(); CountDownLatch finished = new CountDownLatch(1);
        EthornellSession session = new EthornellSession(58, backend, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failedDetail(long token, String detail) { details.add(detail); }
            @Override public void failed(long token) { assertEquals(1, details.size()); }
            @Override public void closed(long token) { finished.countDown(); }
        }, () -> fail("unexpected timeout"));
        await(backend.ready); session.attach(new Object(), 800, 600, 1); resume(session);
        session.frame(1_000_000_000L, 1); await(finished);
        assertEquals(Collections.singletonList("operation=step pause_mask=0 attached=true status=-10 detail=unsupported host/game capability"), details);
        assertTrue(backend.calls.contains("destroy"));
    }
    @Test public void independentWatchdogReportsActualBlockedBoundaryAndBudget() {
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1), supervised = new CountDownLatch(1);
        List<String> reasons = new ArrayList<>();
        Fake backend = new Fake() {
            @Override public long create() { entered.countDown(); await(unblock); return super.create(); }
        };
        EthornellSession session = new EthornellSession(59, backend, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failed(long token) { fail(); }
            @Override public void forcedExit(long token, String reason) { reasons.add(reason); }
            @Override public void closed(long token) {}
        }, () -> { supervised.countDown(); unblock.countDown(); }, 50, 50);
        await(entered); await(supervised); await(backend.ready);
        assertEquals(Collections.singletonList("reason=command_timeout operation=create budget_ms=50"), reasons);
        session.close(); await(backend.closed);
    }
    @Test public void twentySurfacePauseCyclesKeepOneOwnerAndCancelPendingRequestsOnClose() {
        Queue<CountDownLatch> drains = new ConcurrentLinkedQueue<>();
        CountDownLatch requestPolled = new CountDownLatch(1);
        Fake backend = new Fake() {
            @Override public void requestClose(long h) { super.requestClose(h); drains.remove().countDown(); }
            @Override public EthornellSession.Event poll(long h) {
                EthornellSession.Event event = super.poll(h);
                if (event != null && event.requestId == 700) requestPolled.countDown();
                return event;
            }
        };
        AtomicInteger fatals = new AtomicInteger();
        EthornellSession session = start(backend, fatals);
        await(backend.ready); resume(session);
        for (int generation = 1; generation <= 20; generation++) {
            session.attach(new Object(), 800, 600, generation);
            session.pause(1, true); session.pause(4, true);
            session.pause(1, false); session.pause(4, false);
            CountDownLatch drained = new CountDownLatch(1); drains.add(drained);
            session.requestClose(); await(drained);
            session.detachAndWait(generation);
            assertEquals(EthornellSession.State.READY_NO_SURFACE, session.state());
            assertEquals(1, Collections.frequency(backend.calls, "attach:" + generation));
            assertEquals(1, Collections.frequency(backend.calls, "detach:" + generation));
            session.frame(1_000_000_000L + generation * 20_000_000L, generation); // stale surface
        }
        backend.events.add(new EthornellSession.Event(2, 700, 0, "pending"));
        CountDownLatch pendingObserved = new CountDownLatch(1); drains.add(pendingObserved);
        session.requestClose(); await(pendingObserved); await(requestPolled);
        // No response may reach native after close, including a late UI answer to this pending ID.
        session.close(); session.respond(session.token(), 700, 1);
        await(backend.closed);
        assertFalse(backend.calls.stream().anyMatch(s -> s.startsWith("response:")));
        assertEquals(1, Collections.frequency(backend.calls, "destroy"));
        assertEquals(0, fatals.get());
    }
    @Test public void nonfiniteInputsAreRejectedBeforeBackendDelivery() {
        Fake backend = new Fake(); AtomicInteger fatals = new AtomicInteger();
        EthornellSession session = start(backend, fatals); await(backend.ready);
        session.attach(new Object(), 800, 600, 1); resume(session);
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            session.input(new EthornellSession.Input(2, 1, invalid, 0, 0));
            session.input(new EthornellSession.Input(4, 17, 0, invalid, 0));
            session.input(new EthornellSession.Input(6, 0, 0, 0, invalid));
        }
        session.input(new EthornellSession.Input(2, 1, 40, 50, 0));
        session.input(new EthornellSession.Input(3, 1, 40, 50, 0));
        session.requestClose(); await(backend.requested);
        assertEquals(1, Collections.frequency(backend.calls, "input:2:1"));
        assertEquals(1, Collections.frequency(backend.calls, "input:3:1"));
        assertFalse(backend.calls.contains("input:4:17")); assertFalse(backend.calls.contains("input:6:0"));
        session.close(); await(backend.closed); assertEquals(0, fatals.get());
    }
    @Test public void urgentDetachOvertakesQueuedInputButNotTheCurrentlyRunningStep() {
        Fake backend = new Fake(); AtomicInteger fatals = new AtomicInteger();
        EthornellSession session = start(backend, fatals); await(backend.ready);
        session.attach(new Object(), 800, 600, 1); resume(session);
        backend.enteredStep = new CountDownLatch(1); backend.blockStep = new CountDownLatch(1);
        session.frame(1_000_000_000L, 1); await(backend.enteredStep);
        session.input(new EthornellSession.Input(4, 17, 0, 0, 0));
        session.input(new EthornellSession.Input(5, 17, 0, 0, 0));
        Thread ui = new Thread(() -> session.detachAndWait(1)); ui.start(); awaitBarrierWait(ui);
        backend.blockStep.countDown(); join(ui);
        session.requestClose(); await(backend.requested);
        assertTrue(backend.calls.indexOf("step:1000000000") < backend.calls.indexOf("detach:1"));
        assertFalse(backend.calls.contains("input:4:17")); // paused by absent surface
        assertTrue(backend.calls.indexOf("detach:1") < backend.calls.indexOf("input:5:17"));
        session.close(); await(backend.closed); assertEquals(0, fatals.get());
    }
    @Test public void closeDuringBootstrapCancelsQueuedAttachAndRespondWithoutLosingDetachBarrier() {
        CountDownLatch inCreate = new CountDownLatch(1), releaseCreate = new CountDownLatch(1);
        Fake backend = new Fake() {
            @Override public long create() { inCreate.countDown(); await(releaseCreate); return super.create(); }
        };
        backend.events.add(new EthornellSession.Event(2, 99, 0, "pending"));
        AtomicInteger fatals = new AtomicInteger(); EthornellSession session = start(backend, fatals);
        await(inCreate);
        session.attach(new Object(), 800, 600, 1); session.respond(session.token(), 99, 1);
        Thread ui = new Thread(() -> session.detachAndWait(1)); ui.start(); awaitBarrierWait(ui);
        session.close(); // clears queued work, but destroy ACK must release the registered barrier
        releaseCreate.countDown(); join(ui); await(backend.closed);
        assertFalse(backend.calls.contains("attach:1"));
        assertFalse(backend.calls.stream().anyMatch(s -> s.startsWith("response:")));
        assertEquals(1, Collections.frequency(backend.calls, "destroy")); assertEquals(0, fatals.get());
    }
    @Test public void detachDuringDestroyWaitsForDestroyAckWithoutCallingFreedHandle() {
        CountDownLatch inDestroy = new CountDownLatch(1), releaseDestroy = new CountDownLatch(1);
        Fake backend = new Fake() {
            @Override public void destroy(long h) { inDestroy.countDown(); await(releaseDestroy); super.destroy(h); }
        };
        AtomicInteger fatals = new AtomicInteger(); EthornellSession session = start(backend, fatals);
        await(backend.ready); session.close(); await(inDestroy);
        CountDownLatch detached = new CountDownLatch(1);
        Thread ui = new Thread(() -> { session.detachAndWait(1); detached.countDown(); });
        ui.start(); awaitBarrierWait(ui); assertEquals(1, detached.getCount());
        releaseDestroy.countDown(); join(ui); await(detached);
        session.detachAndWait(1); // safe after supervisor shutdown too
        assertFalse(backend.calls.contains("detach:1"));
        assertEquals(1, Collections.frequency(backend.calls, "destroy")); assertEquals(0, fatals.get());
    }
    @Test public void watchdogStillDeliversOriginalIdentityAndTokenAfterCloseWasQueued() {
        CountDownLatch inCreate = new CountDownLatch(1), releaseCreate = new CountDownLatch(1), supervised = new CountDownLatch(1);
        List<String> evidence = Collections.synchronizedList(new ArrayList<>());
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        EthornellLifecycle lifecycle = new EthornellLifecycle(digest, 987, evidence::add);
        Fake backend = new Fake() {
            @Override public long create() { inCreate.countDown(); await(releaseCreate); return super.create(); }
        };
        EthornellSession session = new EthornellSession(987, backend, new EthornellSession.Listener() {
            @Override public void event(long token, EthornellSession.Event event) {}
            @Override public void failed(long token) { fail(); }
            @Override public void forcedExit(long token, String reason) {
                evidence.add("callback_token=" + token); lifecycle.forcedExit(reason);
            }
            @Override public void closed(long token) {}
        }, () -> { supervised.countDown(); releaseCreate.countDown(); }, 200, 200);
        await(inCreate); session.close(); await(supervised); await(backend.closed);
        assertEquals(987, session.token());
        assertEquals("callback_token=987", evidence.get(0));
        assertEquals("forced_exit identity_sha256=" + digest + " token=987 reason=command_timeout operation=create budget_ms=200", evidence.get(1));
    }
    @Test public void kindFivePayloadIsFetchedOnceOnOwnerWorkerAndAnswerCarriesJson() {
        Fake backend = new Fake();
        backend.payload = "{\"type\":\"input\",\"title\":\"Name\",\"fields\":[]}";
        backend.events.add(new EthornellSession.Event(5, 501, 0, "input"));
        Recorder listener = new Recorder();
        EthornellSession session = start(backend, listener);
        awaitTrue("kind5 delivery", () -> listener.events.size() == 1);
        assertEquals(1, backend.payloadReads.get());
        assertTrue(backend.calls.contains("payload:501:Ethornell-engine"));
        EthornellSession.Event first = listener.events.get(0);
        assertEquals("{\"type\":\"input\",\"title\":\"Name\",\"fields\":[]}", first.payload);
        assertNull(first.payloadError);
        // Repeating an unanswered id must neither re-fetch nor re-show the dialog.
        backend.events.add(new EthornellSession.Event(5, 501, 0, "input"));
        session.pause(EthornellSession.PAUSE_DIALOG, true);
        session.pause(EthornellSession.PAUSE_DIALOG, false);
        drainPolls(session, backend);
        assertEquals(1, listener.events.size());
        assertEquals(1, backend.payloadReads.get());
        session.respond(session.token(), 501, 0, "{\"accepted\":false}");
        awaitTrue("json answer", () -> backend.answers.size() == 1);
        assertEquals("501:0:{\"accepted\":false}", backend.answers.get(0));
        session.close(); await(listener.closed);
        assertEquals(0, listener.failures.get()); assertEquals(0, listener.fatals.get());
    }
    @Test public void payloadUnavailableOrFailingBecomesPayloadErrorWithoutSessionFailure() {
        Fake backend = new Fake();
        backend.payload = null;
        backend.failingPayloadId = 602;
        backend.payloadFailure = new IllegalStateException("Ethornell payload limit");
        backend.events.add(new EthornellSession.Event(5, 601, 0, "input"));
        backend.events.add(new EthornellSession.Event(5, 602, 0, "select"));
        Recorder listener = new Recorder();
        EthornellSession session = start(backend, listener);
        awaitTrue("kind5 deliveries", () -> listener.events.size() == 2);
        assertNull(listener.events.get(0).payload);
        assertNotNull(listener.events.get(0).payloadError);
        assertNull(listener.events.get(1).payload);
        assertNotNull(listener.events.get(1).payloadError);
        assertEquals(0, listener.failures.get());
        assertNotEquals(EthornellSession.State.FAILED, session.state());
        // Both requests are still settleable by an explicit cancel.
        session.respond(session.token(), 601, 0, "{\"accepted\":false}");
        session.respond(session.token(), 602, 0, "{\"accepted\":false}");
        awaitTrue("cancels", () -> backend.answers.size() == 2);
        session.respond(session.token(), 601, 0, "{\"accepted\":false}"); // stale duplicate
        drainPolls(session, backend);
        assertEquals(2, backend.answers.size());
        session.close(); await(listener.closed);
        assertEquals(0, listener.failures.get()); assertEquals(0, listener.fatals.get());
    }
    @Test public void emptyAnswerUsesIntegerPathWhileDialogAnswerCarriesJson() {
        Fake backend = new Fake();
        backend.events.add(new EthornellSession.Event(2, 701, 0, "confirm"));
        backend.events.add(new EthornellSession.Event(5, 702, 0, "input"));
        Recorder listener = new Recorder();
        EthornellSession session = start(backend, listener);
        awaitTrue("two deliveries", () -> listener.events.size() == 2);
        session.respond(session.token(), 701, 1);
        session.respond(session.token(), 702, 0, "{\"accepted\":true,\"texts\":[\"a\"]}");
        awaitTrue("two answers", () -> backend.answers.size() == 2);
        assertEquals("701:1:", backend.answers.get(0));
        assertEquals("702:0:{\"accepted\":true,\"texts\":[\"a\"]}", backend.answers.get(1));
        session.respond(session.token(), 702, 0, "{\"accepted\":false}"); // duplicate answer: no native call
        drainPolls(session, backend);
        assertEquals(2, backend.answers.size());
        session.close(); await(listener.closed);
        assertEquals(0, listener.failures.get()); assertEquals(0, listener.fatals.get());
    }
    @Test public void retryReusesRequestIdAndIsRedisplayedOnce() {
        Fake backend = new Fake();
        backend.retryOnAnswer = 801;
        backend.events.add(new EthornellSession.Event(2, 801, 0, "Sys80:3C retry"));
        Recorder listener = new Recorder();
        EthornellSession session = start(backend, listener);
        awaitTrue("first request", () -> listener.events.size() == 1);
        session.respond(session.token(), 801, 1);
        awaitTrue("retry request", () -> listener.events.size() == 2);
        assertEquals(801, listener.events.get(1).requestId);
        assertEquals(2, listener.events.get(1).kind);
        session.respond(session.token(), 801, 0);
        awaitTrue("second answer", () -> backend.answers.size() == 2);
        // Completing a retry must not leave a permanent id history.
        backend.events.add(new EthornellSession.Event(2, 802, 0, "next"));
        session.pause(EthornellSession.PAUSE_DIALOG, true);
        session.pause(EthornellSession.PAUSE_DIALOG, false);
        awaitTrue("next request", () -> listener.events.size() == 3);
        assertEquals(802, listener.events.get(2).requestId);
        session.close(); await(listener.closed);
        assertEquals(0, listener.failures.get()); assertEquals(0, listener.fatals.get());
    }
    @Test public void twentyOrdinaryConfirmationsDoNotAccumulateHistory() {
        Fake backend = new Fake();
        Recorder listener = new Recorder();
        EthornellSession session = start(backend, listener);
        for (int i = 0; i < 20; i++) {
            long id = 900 + i;
            final int expected = i + 1;
            backend.events.add(new EthornellSession.Event(2, id, 0, "confirm"));
            session.pause(EthornellSession.PAUSE_DIALOG, (i & 1) == 0);
            awaitTrue("event " + i, () -> listener.events.size() == expected);
            session.respond(session.token(), id, 1);
            awaitTrue("answer " + i, () -> backend.answers.size() == expected);
        }
        for (int i = 0; i < 15; i++) backend.events.add(new EthornellSession.Event(2, 950 + i, 0, "later"));
        session.pause(EthornellSession.PAUSE_DIALOG, true);
        session.pause(EthornellSession.PAUSE_DIALOG, false);
        awaitTrue("later requests admitted", () -> listener.events.size() == 35);
        assertEquals(35, listener.events.size());
        assertEquals(0, listener.failures.get()); assertEquals(0, listener.fatals.get());
        session.close(); await(listener.closed);
    }
    @Test public void budgetCountsPendingPlusRetryCandidateBeforeAdmittingNewRequests() {
        Fake backend = new Fake();
        Recorder listener = new Recorder();
        for (int i = 0; i < 16; i++) backend.events.add(new EthornellSession.Event(2, 1000 + i, 0, "confirm"));
        EthornellSession session = start(backend, listener);
        awaitTrue("sixteen pending", () -> listener.events.size() == 16);
        assertEquals(0, listener.failures.get());
        // Answering one of sixteen leaves fifteen pending plus one retry candidate.
        backend.followUpAfter = 1015;
        backend.followUp = new EthornellSession.Event(2, 2000, 0, "seventeenth");
        session.respond(session.token(), 1015, 1);
        awaitTrue("host budget diagnosed", () -> listener.failures.get() == 1);
        await(listener.closed);
        assertTrue(listener.details.stream().anyMatch(d ->
                d.contains("host_request_budget=16 pending=15 retry_candidate=1")));
        assertEquals(1, Collections.frequency(backend.calls, "destroy"));
        assertEquals(0, backend.answers.stream().filter(a -> a.startsWith("2000:")).count());
        assertEquals(EthornellSession.State.CLOSED, session.state());
    }
    @Test public void closeClearsPendingRequestsAndSuppressesLateSettlement() {
        Fake backend = new Fake();
        Recorder listener = new Recorder();
        backend.events.add(new EthornellSession.Event(2, 3000, 0, "confirm"));
        backend.events.add(new EthornellSession.Event(5, 3001, 0, "input"));
        EthornellSession session = start(backend, listener);
        awaitTrue("two pending", () -> listener.events.size() == 2);
        session.respond(session.token(), 3000, 1);
        awaitTrue("one answer", () -> backend.answers.size() == 1);
        session.close();
        await(listener.closed);
        backend.events.add(new EthornellSession.Event(2, 3000, 0, "late retry"));
        session.respond(session.token(), 3001, 0, "{\"accepted\":false}");
        session.pause(EthornellSession.PAUSE_DIALOG, true);
        session.requestClose();
        assertEquals(2, listener.events.size());
        assertEquals(1, backend.answers.size());
        assertEquals(1, Collections.frequency(backend.calls, "destroy"));
        assertEquals(EthornellSession.State.CLOSED, session.state());
        assertEquals(0, listener.failures.get()); assertEquals(0, listener.fatals.get());
    }
    private static void awaitBarrierWait(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
        assertEquals("detach must have registered its barrier before releasing native", Thread.State.WAITING, thread.getState());
    }
    private static void join(Thread thread) {
        try { thread.join(3000); assertFalse("detach thread remained blocked", thread.isAlive()); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
