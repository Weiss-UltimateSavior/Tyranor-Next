package com.core.gl;
import org.junit.Test;
import static org.junit.Assert.*;

public class GraphicsGpuTimerTest {
    @Test public void pendingQueriesKeepExistingSamplesWithoutRepeatingThem() {
        ArtemisGraphicsStats stats = twoExecutedNodes();
        stats.nodeGpu(0, 3); stats.nodeGpu(0, 5); stats.nodeGpu(1, 6);
        GraphicsGpuTimer.recordSamples(stats, new double[]{-2,-2});
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertEquals(4, sample.nodes[0].gpuMs, 0);
        assertEquals(6, sample.nodes[1].gpuMs, 0);
        assertEquals(10, sample.gpuMs, 0);
        GraphicsGpuTimer.recordSamples(stats, new double[]{-2,-2});
        sample = stats.snapshot(2);
        assertTrue(Double.isNaN(sample.nodes[0].gpuMs));
        assertTrue(Double.isNaN(sample.nodes[1].gpuMs));
        assertTrue(Double.isNaN(sample.gpuMs));
    }

    @Test public void invalidQueryClearsSamplesAndZeroRemainsValid() {
        ArtemisGraphicsStats stats = twoExecutedNodes();
        stats.nodeGpu(0, 3); stats.nodeGpu(1, 5);
        GraphicsGpuTimer.recordSamples(stats, new double[]{-1,Double.NaN});
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertTrue(Double.isNaN(sample.nodes[0].gpuMs));
        assertTrue(Double.isNaN(sample.nodes[1].gpuMs));
        assertTrue(Double.isNaN(sample.gpuMs));
        GraphicsGpuTimer.recordSamples(stats, new double[]{0,0});
        sample = stats.snapshot(2);
        assertEquals(0, sample.nodes[0].gpuMs, 0);
        assertEquals(0, sample.nodes[1].gpuMs, 0);
        assertEquals(0, sample.gpuMs, 0);
    }

    @Test public void resultArraysRecordOnlyPresentAndConfiguredIndices() {
        ArtemisGraphicsStats stats = twoExecutedNodes();
        stats.nodeGpu(0, 3); stats.nodeGpu(1, 7);
        GraphicsGpuTimer.recordSamples(stats, new double[]{5});
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertEquals(4, sample.nodes[0].gpuMs, 0);
        assertEquals(7, sample.nodes[1].gpuMs, 0);
        assertEquals(11, sample.gpuMs, 0);
        GraphicsGpuTimer.recordSamples(stats, new double[]{2,4,100});
        sample = stats.snapshot(2);
        assertEquals(2, sample.nodes[0].gpuMs, 0);
        assertEquals(4, sample.nodes[1].gpuMs, 0);
        assertEquals(6, sample.gpuMs, 0);
        GraphicsGpuTimer.recordSamples(stats, new double[0]);
        sample = stats.snapshot(3);
        assertTrue(Double.isNaN(sample.nodes[0].gpuMs));
        assertTrue(Double.isNaN(sample.nodes[1].gpuMs));
        assertTrue(Double.isNaN(sample.gpuMs));
    }

    private static ArtemisGraphicsStats twoExecutedNodes() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos,lanczos"), "GPU");
        stats.nodeFrame(new int[]{100,200}, new int[]{100,200},
                new int[]{200,400}, new int[]{200,400}, new boolean[]{true,true});
        return stats;
    }
}
