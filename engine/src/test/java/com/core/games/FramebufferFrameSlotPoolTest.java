package com.core.games;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** 帧池在暂态 OOM 闩锁与回收复用下的行为回归。 */
public final class FramebufferFrameSlotPoolTest {

    @Test
    public void transientOomLatchesNewAllocationsWhileReusingIdleSameSizeSlots() {
        AtomicInteger allocations = new AtomicInteger();
        FramebufferGraphicsProxy.FrameSlotPool pool = new FramebufferGraphicsProxy.FrameSlotPool(size -> {
            int call = allocations.incrementAndGet();
            if (call == 2) throw new OutOfMemoryError("simulated transient OOM");
            return ByteBuffer.allocateDirect(size);
        });

        FramebufferGraphicsProxy.Slot first = pool.acquire(320, 240);
        assertNotNull(first);
        assertEquals(1, allocations.get());

        // 第二槽分配触发 OOM 并建立 320x240 闩锁；第一槽仍在途时同尺寸获取返回 null。
        assertNull(pool.acquire(320, 240));
        assertEquals(2, allocations.get());
        assertNull(pool.acquire(320, 240));
        assertEquals(2, allocations.get());

        // 归还第一槽后，即使 320x240 仍处于 OOM 闩锁下，也必须优先复用已分配同尺寸空闲槽位且不触发新分配。
        pool.recycle(0);
        FramebufferGraphicsProxy.Slot reused = pool.acquire(320, 240);
        assertNotNull(reused);
        assertSame(first.buffer, reused.buffer);
        assertEquals(2, allocations.get());
    }

    @Test
    public void oomLatchResetsOnSizeChangeAndExplicitReset() {
        AtomicInteger allocations = new AtomicInteger();
        FramebufferGraphicsProxy.FrameSlotPool pool = new FramebufferGraphicsProxy.FrameSlotPool(size -> {
            int call = allocations.incrementAndGet();
            if (call == 1) throw new OutOfMemoryError("simulated OOM");
            return ByteBuffer.allocateDirect(size);
        });

        assertNull(pool.acquire(640, 480));
        assertEquals(1, allocations.get());
        assertNull(pool.acquire(640, 480));
        assertEquals(1, allocations.get());

        // 尺寸变化放行新分配。
        FramebufferGraphicsProxy.Slot resized = pool.acquire(800, 600);
        assertNotNull(resized);
        assertEquals(2, allocations.get());
        pool.recycle(0);

        // 重置闩锁后允许重试同一尺寸，且会替换旧尺寸缓冲区。
        pool.resetOomLatch();
        FramebufferGraphicsProxy.Slot retried = pool.acquire(640, 480);
        assertNotNull(retried);
        assertNotSame(resized.buffer, retried.buffer);
        assertEquals(3, allocations.get());
    }

    @Test
    public void invalidOrOverBudgetSizesAreRejectedWithoutAllocating() {
        AtomicInteger allocations = new AtomicInteger();
        FramebufferGraphicsProxy.FrameSlotPool pool = new FramebufferGraphicsProxy.FrameSlotPool(size -> {
            allocations.incrementAndGet();
            return ByteBuffer.allocateDirect(size);
        });

        assertNull(pool.acquire(0, 480));
        assertNull(pool.acquire(640, -1));
        assertNull(pool.acquire(8192, 8192));
        assertEquals(0, allocations.get());
    }
}
