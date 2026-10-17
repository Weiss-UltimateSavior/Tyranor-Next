package com.core.gl;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArtemisGraphicsStatsTest {
    @Test public void countsOnlyPresentedFramesAndKeepsFourDimensionsDistinct() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.resetInterval(1_000_000_000L);
        stats.surface(2400, 1080);
        stats.frame(1280, 720, 1280, 720, 1920, 1080);
        stats.frame(1280, 720, 1280, 720, 1920, 1080);
        stats.presented();
        ArtemisGraphicsStats.Snapshot frame = stats.snapshot(1_500_000_000L);
        assertEquals(2, frame.fps, 0.001);
        assertEquals(1280, frame.sourceWidth); assertEquals(1280, frame.outputWidth);
        assertEquals(1920, frame.viewportWidth); assertEquals(2400, frame.surfaceWidth);
        assertTrue(Double.isNaN(frame.gpuMs));
        ArtemisGraphicsStats.Snapshot idle = stats.snapshot(2_000_000_000L);
        assertEquals(0, idle.fps, 0);
    }
    @Test public void invalidGpuResultClearsSamplesInsteadOfSubstitutingCpuTime() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.resetInterval(1_000_000_000L);
        stats.gpu(3); stats.gpu(5);
        assertEquals(4, stats.snapshot(1_500_000_000L).gpuMs, 0);
        stats.gpu(9); stats.gpu(-1);
        assertTrue(Double.isNaN(stats.snapshot(2_000_000_000L).gpuMs));
        stats.reset();
        assertEquals(0, stats.snapshot(2_500_000_000L).sourceWidth);
    }
    @Test public void nodeGpuAveragesAndInvalidationClearsGeometry() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v2:lanczos~0.4~factor~2.0~2.0,!s,lanczos"), "test GPU");
        stats.resetInterval(1_000_000_000L);
        stats.nodeFrame(new int[]{1920,3840,3840}, new int[]{1080,2160,2160},
                new int[]{3840,3840,2133}, new int[]{2160,2160,1200}, new boolean[]{true,false,true});
        stats.nodeGpu(0, 8); stats.nodeGpu(2, 2);
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1_500_000_000L);
        assertEquals("test GPU", sample.renderer);
        assertEquals(2, sample.nodes[0].scale, 0);
        assertEquals(8, sample.nodes[0].gpuMs, 0);
        assertTrue(sample.nodes[0].executed);
        assertTrue(sample.nodes[2].executed);
        assertNull(sample.nodes[1].id);
        assertFalse(sample.nodes[1].executed);
        assertEquals(10, sample.gpuMs, 0);
        stats.nodeGpu(0, 7); stats.invalidateNodes();
        sample = stats.snapshot(2_000_000_000L);
        assertTrue(Double.isNaN(sample.nodes[0].gpuMs));
        assertTrue(Double.isNaN(sample.nodes[0].scale));
        assertFalse(sample.nodes[0].executed);
        assertFalse(sample.nodes[2].executed);
        assertTrue(Double.isNaN(sample.gpuMs));
    }

    @Test public void anisotropicScaleKeepsRangeWithoutInventingBypassTiming() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos"), "GPU");
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{50}, new int[]{200}, new boolean[]{false});
        ArtemisGraphicsStats.Node node = stats.snapshot(1).nodes[0];
        assertEquals(.5, node.scaleMin, 0); assertEquals(2, node.scaleMax, 0);
        assertFalse(node.executed);
        assertTrue(Double.isNaN(node.gpuMs));
    }


    @Test public void totalTimingOutliersDoNotPolluteOrClearValidSamples() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.gpu(3); stats.gpu(5);
        stats.gpu(1000.001); stats.gpu(Double.MAX_VALUE); stats.gpu(Double.POSITIVE_INFINITY);
        assertEquals(4, stats.snapshot(1).gpuMs, 0);
        stats.gpu(1001);
        assertTrue(Double.isNaN(stats.snapshot(2).gpuMs));
        stats.gpu(0); stats.gpu(1000);
        assertEquals(500, stats.snapshot(3).gpuMs, 0);
    }

    @Test public void nodeTimingOutliersDoNotPolluteOrClearValidSamples() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos"), "GPU");
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{200}, new int[]{200}, new boolean[]{true});
        stats.nodeGpu(0, 3); stats.nodeGpu(0, 5);
        stats.nodeGpu(0, 1000.001); stats.nodeGpu(0, Double.POSITIVE_INFINITY);
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertEquals(4, sample.nodes[0].gpuMs, 0);
        assertEquals(4, sample.gpuMs, 0);
        stats.nodeGpu(0, 1001);
        sample = stats.snapshot(2);
        assertTrue(Double.isNaN(sample.nodes[0].gpuMs));
        assertTrue(Double.isNaN(sample.gpuMs));
        stats.nodeGpu(0, 0); stats.nodeGpu(0, 1000);
        sample = stats.snapshot(3);
        assertEquals(500, sample.nodes[0].gpuMs, 0);
    }

    @Test public void invalidQueryStillClearsNodeSamples() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos"), "GPU");
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{200}, new int[]{200}, new boolean[]{true});
        stats.nodeGpu(0, 3); stats.nodeGpu(0, -1);
        assertTrue(Double.isNaN(stats.snapshot(1).nodes[0].gpuMs));
        stats.nodeGpu(0, 5); stats.nodeGpu(0, Double.NaN);
        assertTrue(Double.isNaN(stats.snapshot(2).gpuMs));
    }

    @Test public void fallbackTotalUsesTheSameTimingLimit() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos,lanczos"), "GPU");
        stats.nodeFrame(new int[]{100,200}, new int[]{100,200},
                new int[]{200,400}, new int[]{200,400}, new boolean[]{true,true});
        stats.nodeGpu(0, 600); stats.nodeGpu(1, 600);
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertEquals(600, sample.nodes[0].gpuMs, 0);
        assertEquals(600, sample.nodes[1].gpuMs, 0);
        assertTrue(Double.isNaN(sample.gpuMs));
        stats.nodeGpu(0, 500); stats.nodeGpu(1, 500);
        assertEquals(1000, stats.snapshot(2).gpuMs, 0);
    }

    @Test public void executedNodeWithoutTimingKeepsTotalUnavailable() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos,lanczos"), "GPU");
        stats.nodeFrame(new int[]{100,200}, new int[]{100,200},
                new int[]{200,400}, new int[]{200,400}, new boolean[]{true,true});
        stats.nodeGpu(0, 8);
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertTrue(sample.nodes[0].executed); assertTrue(sample.nodes[1].executed);
        assertEquals(8, sample.nodes[0].gpuMs, 0);
        assertTrue(Double.isNaN(sample.nodes[1].gpuMs));
        assertTrue(Double.isNaN(sample.gpuMs));
    }

    @Test public void bypassedNodeDoesNotHideExecutedTiming() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos,lanczos"), "GPU");
        stats.nodeFrame(new int[]{100,200}, new int[]{100,200},
                new int[]{200,200}, new int[]{200,200}, new boolean[]{true,false});
        stats.nodeGpu(0, 8);
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertTrue(sample.nodes[0].executed); assertFalse(sample.nodes[1].executed);
        assertEquals(8, sample.gpuMs, 0);
        assertTrue(Double.isNaN(sample.nodes[1].gpuMs));
        assertEquals(1, sample.nodes[1].scale, 0);
    }
    @Test public void bypassBetweenMeasuredFramesRetainsRefreshIntervalSamples() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos"), "GPU");
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{200}, new int[]{200}, new boolean[]{true});
        stats.nodeGpu(0, 3);
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{100}, new int[]{100}, new boolean[]{false});
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{200}, new int[]{200}, new boolean[]{true});
        stats.nodeGpu(0, 5);
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertTrue(sample.nodes[0].executed);
        assertEquals(4, sample.nodes[0].gpuMs, 0);
        assertEquals(4, sample.gpuMs, 0);
    }

    @Test public void latestBypassedFrameDoesNotExposeEarlierTimingAsCurrentExecution() {
        ArtemisGraphicsStats stats = new ArtemisGraphicsStats();
        stats.configureNodes(GraphicsFilterChain.parse("v1:lanczos"), "GPU");
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{200}, new int[]{200}, new boolean[]{true});
        stats.nodeGpu(0, 3);
        stats.nodeFrame(new int[]{100}, new int[]{100}, new int[]{100}, new int[]{100}, new boolean[]{false});
        ArtemisGraphicsStats.Snapshot sample = stats.snapshot(1);
        assertFalse(sample.nodes[0].executed);
        assertTrue(Double.isNaN(sample.nodes[0].gpuMs));
        assertTrue(Double.isNaN(sample.gpuMs));
    }

}
