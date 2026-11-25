package com.core.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * 显示句柄租约的契约回归：最后一次 release 恰好执行一次清理、释放后不再 retain，
 * 清理动作在锁外执行，且清理抛异常后租约仍保持已释放态且不重放清理。
 */
public final class HandleLeaseTest {

    @Test
    public void lastReleaseRunsCleanupExactlyOnce() {
        AtomicInteger released = new AtomicInteger();
        HandleLease lease = new HandleLease(released::incrementAndGet);

        assertTrue(lease.retain());
        assertEquals(2, lease.references());

        lease.release();
        assertEquals(0, released.get());
        assertFalse(lease.isReleased());

        lease.release();
        assertEquals(1, released.get());
        assertTrue(lease.isReleased());

        // 释放后再 release 是幂等的，清理动作不会重放。
        lease.release();
        assertEquals(1, released.get());
    }

    @Test
    public void releasedLeaseRefusesFurtherRetention() {
        HandleLease lease = new HandleLease(() -> {});
        lease.release();
        assertTrue(lease.isReleased());
        assertFalse(lease.retain());
        assertEquals(0, lease.references());
    }

    @Test
    public void nullCleanupActionIsRejected() {
        try {
            new HandleLease(null);
            throw new AssertionError("null cleanup action must be rejected");
        } catch (IllegalArgumentException expected) {
            // Contract: a lease without an owner cleanup would silently leak native handles.
        }
    }

    @Test
    public void cleanupExceptionStillMarksLeaseReleasedAndRunsOnce() {
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicInteger secondaryReleased = new AtomicInteger();
        HandleLease lease = new HandleLease(() -> {
            cleanupCalls.incrementAndGet();
            try {
                throw new IllegalStateException("simulated surface release failure");
            } finally {
                secondaryReleased.incrementAndGet();
            }
        });

        try {
            lease.release();
            throw new AssertionError("cleanup exception should propagate to caller");
        } catch (IllegalStateException expected) {
            // 清理异常必须暴露给调用方，同时租约自身已收口。
        }
        assertEquals(1, cleanupCalls.get());
        assertEquals(1, secondaryReleased.get());
        assertTrue(lease.isReleased());
        assertEquals(0, lease.references());
        assertFalse(lease.retain());

        // 再次 release 保持幂等，不再重放已失败的清理动作。
        lease.release();
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    public void concurrentRetainAndReleaseNeverDoubleFrees() throws Exception {
        for (int round = 0; round < 20; round++) {
            AtomicInteger released = new AtomicInteger();
            HandleLease lease = new HandleLease(released::incrementAndGet);
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads * 2);
            for (int i = 0; i < threads; i++) {
                Thread retainer = new Thread(() -> {
                    try {
                        start.await();
                        // retain 之后必然再 release 一次，保持引用计数守恒。
                        if (lease.retain()) lease.release();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                retainer.start();
            }
            for (int i = 0; i < threads; i++) {
                Thread releaser = new Thread(() -> {
                    try {
                        start.await();
                        lease.release();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                releaser.start();
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            // 8 个 releaser 必然把初始引用计数归零，且清理动作必须恰好执行一次。
            assertEquals(1, released.get());
            assertTrue(lease.isReleased());
        }
    }

    @Test
    public void cleanupRunsOutsideTheMonitor() throws Exception {
        // 清理动作在锁外执行：清理里再读 references() 不会死锁（synchronized 可重入，
        // 死锁不会发生；这里验证的是「锁已释放」这一语义，用另一线程拿锁判定）。
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        HandleLease lease = new HandleLease(() -> {
            cleanupEntered.countDown();
            try {
                allowCleanup.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Thread releaser = new Thread(lease::release, "lease-releaser");
        releaser.start();
        assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));

        // 清理还在阻塞时，其它线程必须仍能观察租约状态（说明锁没有被清理动作占住）。
        AtomicInteger references = new AtomicInteger(-1);
        Thread observer = new Thread(() -> references.set(lease.references()), "lease-observer");
        observer.start();
        observer.join(1000);
        assertFalse("observer must read the lease while cleanup runs", observer.isAlive());
        assertEquals(0, references.get());

        allowCleanup.countDown();
        releaser.join(2000);
        assertFalse(releaser.isAlive());
    }
}
