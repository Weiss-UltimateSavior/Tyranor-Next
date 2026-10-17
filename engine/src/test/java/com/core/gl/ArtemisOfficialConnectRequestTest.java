package com.core.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ArtemisOfficialConnectRequestTest {
    @Test
    public void cancelledFirstRequestCannotBeConnectedByALateWorker() throws Exception {
        Object screen = new Object();
        ArtemisOfficialConnectRequest<Object> request = new ArtemisOfficialConnectRequest<>(screen, true);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Object> workerScreen = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                resume.await();
                workerScreen.set(request.claim());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        try {
            assertSame(screen, request.cancel());
            assertTrue(request.canConfirmDetached());
            ArtemisOfficialDisconnect disconnect = new ArtemisOfficialDisconnect();
            disconnect.complete(true);
            assertEquals(ArtemisOfficialDisconnect.DETACHED, disconnect.result());
            resume.countDown();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertNull(workerScreen.get());
            assertNull(request.cancel());
            assertTrue(request.canConfirmDetached());
        } finally {
            resume.countDown();
            worker.join(2000);
            if (worker.isAlive()) worker.interrupt();
        }
    }

    @Test
    public void claimedFirstRequestStillRequiresWorkerDetachment() throws Exception {
        Object screen = new Object();
        ArtemisOfficialConnectRequest<Object> request = new ArtemisOfficialConnectRequest<>(screen, true);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Object> workerScreen = new AtomicReference<>();
        ArtemisOfficialDisconnect disconnect = new ArtemisOfficialDisconnect();
        Thread worker = new Thread(() -> {
            workerScreen.set(request.claim());
            claimed.countDown();
            try {
                resume.await();
                disconnect.complete(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                disconnect.complete(false);
            }
        });
        worker.start();
        try {
            assertTrue(claimed.await(2, TimeUnit.SECONDS));
            assertSame(screen, workerScreen.get());
            assertNull(request.cancel());
            assertFalse(request.canConfirmDetached());
            assertEquals(ArtemisOfficialDisconnect.PENDING, disconnect.result());
            resume.countDown();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertEquals(ArtemisOfficialDisconnect.DETACHED, disconnect.result());
        } finally {
            resume.countDown();
            worker.join(2000);
            if (worker.isAlive()) worker.interrupt();
        }
    }

    @Test
    public void cancelledReconnectCannotProveThePreviousOutputDetached() {
        Object screen = new Object();
        ArtemisOfficialConnectRequest<Object> request = new ArtemisOfficialConnectRequest<>(screen, false);
        assertSame(screen, request.cancel());
        assertNull(request.claim());
        assertFalse(request.canConfirmDetached());
        ArtemisOfficialDisconnect disconnect = new ArtemisOfficialDisconnect();
        assertEquals(ArtemisOfficialDisconnect.PENDING, disconnect.result());
    }
}
