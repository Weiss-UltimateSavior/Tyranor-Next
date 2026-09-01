package com.tyranor.next.core.game.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameShortcutCropTest {
    @Test
    /** Verifies the default crop centers common aspect ratios. */
    fun initialCrop_centersLandscapePortraitAndSquareImages() {
        assertEquals(
            CropSourceRect(left = 350, top = 0, size = 900),
            initialRect(width = 1600f, height = 900f),
        )
        assertEquals(
            CropSourceRect(left = 0, top = 350, size = 900),
            initialRect(width = 900f, height = 1600f),
        )
        assertEquals(
            CropSourceRect(left = 0, top = 0, size = 1000),
            initialRect(viewport = 1000f, width = 1000f, height = 1000f),
        )
    }

    @Test
    /** Verifies panning and zooming are clamped to the decoded image bounds. */
    fun zoomAndPan_stayInsideSourceBounds() {
        val metrics = CropImageMetrics(viewportSize = 900f, imageWidth = 1600f, imageHeight = 900f)
        val zoomed = constrainCropTransform(
            CropTransform(scale = 2f, offsetX = Float.MAX_VALUE, offsetY = -Float.MAX_VALUE),
            metrics,
        )
        val rect = requireNotNull(cropSourceRect(zoomed, metrics))

        assertEquals(450, rect.size)
        assertTrue(rect.left >= 0)
        assertTrue(rect.top >= 0)
        assertTrue(rect.left + rect.size <= 1600)
        assertTrue(rect.top + rect.size <= 900)
    }

    @Test
    /** Verifies the rendered image always covers the square crop viewport. */
    fun renderRect_alwaysCoversTheCropViewport() {
        val metrics = CropImageMetrics(viewportSize = 900f, imageWidth = 1600f, imageHeight = 900f)
        val rect = requireNotNull(cropRenderRect(CropTransform(), metrics))

        assertTrue(rect.width >= metrics.viewportSize)
        assertTrue(rect.height >= metrics.viewportSize)
        assertEquals(0f, rect.top, 0.001f)
    }

    @Test
    /** Verifies malformed gesture values are sanitized before export. */
    fun invalidGestureValues_areSanitized() {
        val metrics = CropImageMetrics(viewportSize = 512f, imageWidth = 8192f, imageHeight = 1f)
        val result = applyCropGesture(
            transform = CropTransform(scale = Float.NaN, offsetX = Float.POSITIVE_INFINITY),
            metrics = metrics,
            centroidX = Float.NaN,
            centroidY = Float.NEGATIVE_INFINITY,
            panX = Float.NaN,
            panY = Float.POSITIVE_INFINITY,
            zoom = -1f,
        )
        val rect = requireNotNull(cropSourceRect(result, metrics))

        assertTrue(result.scale.isFinite())
        assertEquals(1, rect.size)
        assertTrue(rect.left + rect.size <= 8192)
        assertTrue(rect.top + rect.size <= 1)
    }

    /** Computes an exported crop rectangle for a test image size. */
    private fun initialRect(
        viewport: Float = 900f,
        width: Float,
        height: Float,
    ): CropSourceRect? {
        val metrics = CropImageMetrics(viewportSize = viewport, imageWidth = width, imageHeight = height)
        return cropSourceRect(initialCropTransform(metrics), metrics)
    }
}
