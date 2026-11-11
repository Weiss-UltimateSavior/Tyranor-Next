package com.core.impacto;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real JVM threads and an independent process exercise shutdown without Android/JNI mocks. */
public class ImpactoShutdownPolicyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final class Writer implements AutoCloseable {
        final CountDownLatch stop = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final Thread thread = new Thread(() -> {
            started.countDown();
            try { stop.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "SyntheticImpactoWriter");
        Writer() throws Exception {
            thread.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
        }
        @Override public void close() throws Exception {
            stop.countDown();
            thread.join(5000);
            assertFalse("Test writer must stop", thread.isAlive());
        }
    }

    private static class Actions implements ImpactoShutdownPolicy.Actions {
        int releases;
        int terminations;
        boolean forced;
        @Override public void releaseUnstartedSession() { releases++; }
        @Override public void terminateProcess(boolean forced) { terminations++; this.forced = forced; }
    }

    @Test public void cancelledBeforeLibraryLoadReleasesOnlyUnstartedSession() {
        Actions actions = new Actions();
        assertEquals(ImpactoShutdownPolicy.Outcome.UNSTARTED, ImpactoShutdownPolicy.finish(false, null, 10, actions));
        assertEquals(1, actions.releases);
        assertEquals(0, actions.terminations);
    }

    @Test public void loadedLibraryWithNoWriterTerminatesWithoutReleasingLeaseFirst() {
        Actions actions = new Actions();
        assertEquals(ImpactoShutdownPolicy.Outcome.FINISHED, ImpactoShutdownPolicy.finish(true, null, 10, actions));
        assertEquals(0, actions.releases);
        assertEquals(1, actions.terminations);
        assertFalse(actions.forced);
    }

    @Test public void nativeQuitRemainsForbiddenUntilTheActualWriterStops() throws Exception {
        AtomicInteger nativeQuitCalls = new AtomicInteger();
        try (Writer writer = new Writer()) {
            if (ImpactoShutdownPolicy.mayQuitNative(writer.thread)) nativeQuitCalls.incrementAndGet();
            assertEquals("A live writer must retain its pending SDL DESTROY event", 0, nativeQuitCalls.get());
            writer.stop.countDown(); writer.thread.join(5000);
            if (ImpactoShutdownPolicy.mayQuitNative(writer.thread)) nativeQuitCalls.incrementAndGet();
            assertEquals(1, nativeQuitCalls.get());
        }
    }

    @Test public void normalWriterCompletionIsObservedBeforeProcessTermination() throws Exception {
        try (Writer writer = new Writer()) {
            Actions actions = new Actions() {
                @Override public void terminateProcess(boolean forced) {
                    assertFalse(writer.thread.isAlive());
                    super.terminateProcess(forced);
                }
            };
            Thread completion = new Thread(() -> {
                try { Thread.sleep(30); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                writer.stop.countDown();
            });
            completion.start();
            assertEquals(ImpactoShutdownPolicy.Outcome.FINISHED, ImpactoShutdownPolicy.finish(true, writer.thread, 5000, actions));
            completion.join(5000);
            assertEquals(0, actions.releases);
            assertEquals(1, actions.terminations);
            assertFalse(actions.forced);
        }
    }

    private static int probe(File root) throws Exception {
        Class<?> probe = ImpactoSessionTest.Probe.class;
        String classes = new File(probe.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", classes, probe.getName(), root.getPath()).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        return process.exitValue();
    }

    @Test public void ignoredQuitIsBoundedAndForcedExitKeepsTheOsLease() throws Exception {
        File root = temp.newFolder();
        try (Writer writer = new Writer(); ImpactoSession session = ImpactoSession.acquire(root)) {
            Actions actions = new Actions() {
                @Override public void terminateProcess(boolean forced) {
                    assertTrue("Timeout cannot be called graceful completion", forced);
                    assertTrue(writer.thread.isAlive());
                    assertThrows(IOException.class, () -> ImpactoSession.acquire(root));
                    try { assertEquals("Another process must remain excluded until process death", 7, probe(root)); }
                    catch (Exception error) { throw new AssertionError(error); }
                    super.terminateProcess(forced);
                }
            };
            long start = System.nanoTime();
            assertEquals(ImpactoShutdownPolicy.Outcome.FORCED, ImpactoShutdownPolicy.finish(true, writer.thread, 30, actions));
            assertTrue("A blocked writer must not produce an unbounded reaper", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5);
            assertEquals(0, actions.releases);
            assertEquals(1, actions.terminations);
        }
        assertEquals(0, probe(root));
    }

    @Test public void interruptedReaperStillReachesDeadlineAndRestoresInterrupt() throws Exception {
        try (Writer writer = new Writer()) {
            Actions actions = new Actions();
            Thread.currentThread().interrupt();
            try {
                assertEquals(ImpactoShutdownPolicy.Outcome.FORCED, ImpactoShutdownPolicy.finish(true, writer.thread, 20, actions));
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, actions.releases);
                assertEquals(1, actions.terminations);
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void zeroGraceForLiveWriterDoesNotCallUnboundedJoin() throws Exception {
        try (Writer writer = new Writer()) {
            Actions actions = new Actions();
            assertEquals(ImpactoShutdownPolicy.Outcome.FORCED, ImpactoShutdownPolicy.finish(true, writer.thread, 0, actions));
            assertEquals(0, actions.releases);
            assertTrue(actions.forced);
        }
    }

    @Test public void processTerminationFailureNeverFallsBackToUnsafeLeaseRelease() throws Exception {
        try (Writer writer = new Writer()) {
            Actions actions = new Actions() {
                @Override public void terminateProcess(boolean forced) { throw new SecurityException("Simulated process termination denial"); }
            };
            assertThrows(SecurityException.class, () -> ImpactoShutdownPolicy.finish(true, writer.thread, 0, actions));
            assertEquals(0, actions.releases);
        }
    }

    @Test public void existingWriterIsNotTreatedAsAnUnstartedSession() throws Exception {
        try (Writer writer = new Writer()) {
            Actions actions = new Actions();
            assertEquals(ImpactoShutdownPolicy.Outcome.FORCED, ImpactoShutdownPolicy.finish(false, writer.thread, 0, actions));
            assertEquals(0, actions.releases);
            assertEquals(1, actions.terminations);
        }
    }
}
