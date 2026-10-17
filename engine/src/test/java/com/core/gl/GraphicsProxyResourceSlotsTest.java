package com.core.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class GraphicsProxyResourceSlotsTest {
    @Test
    public void pendingCleanupOccupiesCapacityUntilCompleteDisposal() {
        GraphicsProxyResourceSlots resources = new GraphicsProxyResourceSlots(2);
        GraphicsProxyResourceSlots.Slot active = resources.acquire();
        GraphicsProxyResourceSlots.Slot closing = resources.acquire();
        assertNotNull(active);
        assertNotNull(closing);
        assertNull(resources.acquire());

        // An owner whose cleanup is pending must not be evicted to admit a new proxy.
        active.release();
        GraphicsProxyResourceSlots.Slot replacement = resources.acquire();
        assertNotNull(replacement);
        assertNull(resources.acquire());
        closing.release();
        assertNotNull(resources.acquire());
    }

    @Test
    public void permanentlyRetainedOwnerCannotBeReplacedAcrossRepeatedLaunches() {
        GraphicsProxyResourceSlots resources = new GraphicsProxyResourceSlots(2);
        GraphicsProxyResourceSlots.Slot retained = resources.acquire();
        assertNotNull(retained);
        for (int launch = 0; launch < 32; launch++) {
            GraphicsProxyResourceSlots.Slot active = resources.acquire();
            assertNotNull(active);
            assertNull(resources.acquire());
            active.release();
        }
        GraphicsProxyResourceSlots.Slot secondRetained = resources.acquire();
        assertNotNull(secondRetained);
        assertNull(resources.acquire());
        assertNull(resources.acquire());
    }

    @Test
    public void duplicateCompletionCannotReturnAnotherOwnersCapacity() {
        GraphicsProxyResourceSlots resources = new GraphicsProxyResourceSlots(2);
        GraphicsProxyResourceSlots.Slot disposed = resources.acquire();
        GraphicsProxyResourceSlots.Slot retained = resources.acquire();
        assertNotNull(disposed);
        assertNotNull(retained);
        disposed.release();
        GraphicsProxyResourceSlots.Slot replacement = resources.acquire();
        assertNotNull(replacement);
        disposed.release();
        assertNull(resources.acquire());
    }

    @Test
    public void constructionFailureBeforeEglWorkReturnsItsReservation() {
        GraphicsProxyResourceSlots resources = new GraphicsProxyResourceSlots(1);
        GraphicsProxyResourceSlots.Slot reserved = resources.acquire();
        assertNotNull(reserved);
        assertNull(resources.acquire());
        try {
            throw new IllegalStateException("Worker did not start");
        } catch (IllegalStateException expected) {
            // The constructor has not exposed a worker that could allocate EGL resources.
        } finally {
            reserved.release();
        }
        assertNotNull(resources.acquire());
    }

    @Test
    public void concurrentAdmissionCannotOverbookTheProcessBudget() throws Exception {
        GraphicsProxyResourceSlots resources = new GraphicsProxyResourceSlots(2);
        GraphicsProxyResourceSlots.Slot[] admitted = new GraphicsProxyResourceSlots.Slot[8];
        Thread[] workers = new Thread[admitted.length];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(workers.length);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < workers.length; index++) {
            final int workerIndex = index;
            workers[index] = new Thread(() -> {
                try {
                    start.await();
                    admitted[workerIndex] = resources.acquire();
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                } finally {
                    acquired.countDown();
                }
            });
            workers[index].start();
        }
        try {
            start.countDown();
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            int owners = 0;
            for (GraphicsProxyResourceSlots.Slot slot : admitted) if (slot != null) owners++;
            assertEquals(2, owners);
            assertNull(resources.acquire());
        } finally {
            start.countDown();
            for (Thread worker : workers) {
                worker.join(2000);
                if (worker.isAlive()) worker.interrupt();
                assertFalse(worker.isAlive());
            }
        }
    }
}
