package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnsGraphicsSharpnessPolicyTest {

    private fun enabledOnsFilter() = GraphicsSettings(
        enabled = true,
        chain = listOf(GraphicsNode("s"), GraphicsNode("lanczos")),
    )

    private fun disabledOnsFilter() = GraphicsSettings(
        enabled = false,
        chain = listOf(GraphicsNode("s"), GraphicsNode("lanczos")),
    )

    @Test
    fun `global load normalizes legacy double-enabled config in favor of filter`() {
        assertFalse(OnsGraphicsSharpnessPolicy.normalizeGlobal(true, enabledOnsFilter()))
        assertTrue(OnsGraphicsSharpnessPolicy.normalizeGlobal(true, disabledOnsFilter()))
    }

    @Test
    fun `global enabling sharpness disables active filter while keeping chain`() {
        val custom = enabledOnsFilter()
        val res = OnsGraphicsSharpnessPolicy.onGlobalSharpnessChange(true, custom)

        assertTrue(res.sharpness)
        assertFalse(res.graphics.enabled)
        assertEquals(custom.chain, res.graphics.chain)
        assertEquals(OnsGraphicsSharpnessPolicy.AutoDisabledTarget.GRAPHICS_FILTER, res.autoDisabled)
    }

    @Test
    fun `global enabling filter disables engine sharpness`() {
        val res = OnsGraphicsSharpnessPolicy.onGlobalGraphicsChange(
            newGraphics = enabledOnsFilter(),
            currentSharpness = true,
        )

        assertFalse(res.sharpness)
        assertTrue(res.graphics.enabled)
        assertEquals(OnsGraphicsSharpnessPolicy.AutoDisabledTarget.ENGINE_SHARPNESS, res.autoDisabled)
    }

    @Test
    fun `global turning off options or inactive chain does not trigger mutex`() {
        val inactiveChain = GraphicsSettings(enabled = true, chain = emptyList())
        val keepSharpness = OnsGraphicsSharpnessPolicy.onGlobalGraphicsChange(inactiveChain, currentSharpness = true)
        assertTrue(keepSharpness.sharpness)
        assertEquals(OnsGraphicsSharpnessPolicy.AutoDisabledTarget.NONE, keepSharpness.autoDisabled)

        val turnOffSharpness = OnsGraphicsSharpnessPolicy.onGlobalSharpnessChange(false, enabledOnsFilter())
        assertFalse(turnOffSharpness.sharpness)
        assertTrue(turnOffSharpness.graphics.enabled)
        assertEquals(OnsGraphicsSharpnessPolicy.AutoDisabledTarget.NONE, turnOffSharpness.autoDisabled)
    }

    @Test
    fun `per-game enabling sharpness override turns off inherited active filter`() {
        val res = OnsGraphicsSharpnessPolicy.onPerGameSharpnessChange(
            globalSharpness = false,
            globalGraphics = enabledOnsFilter(),
            newSharpnessOverride = true,
            currentGraphicsOverride = GraphicsOverride(),
        )

        assertEquals(true, res.sharpnessOverride)
        assertEquals(false, res.graphicsOverride.enabled)
        assertEquals(OnsGraphicsSharpnessPolicy.AutoDisabledTarget.GRAPHICS_FILTER, res.autoDisabled)
    }

    @Test
    fun `per-game enabling filter override turns off inherited or explicit sharpness`() {
        val inheritedSharp = OnsGraphicsSharpnessPolicy.onPerGameGraphicsChange(
            globalSharpness = true,
            globalGraphics = disabledOnsFilter(),
            currentSharpnessOverride = null,
            newGraphicsOverride = GraphicsOverride(enabled = true),
        )
        assertEquals(false, inheritedSharp.sharpnessOverride)
        assertEquals(true, inheritedSharp.graphicsOverride.enabled)
        assertEquals(OnsGraphicsSharpnessPolicy.AutoDisabledTarget.ENGINE_SHARPNESS, inheritedSharp.autoDisabled)

        val explicitSharp = OnsGraphicsSharpnessPolicy.onPerGameGraphicsChange(
            globalSharpness = false,
            globalGraphics = enabledOnsFilter(),
            currentSharpnessOverride = true,
            newGraphicsOverride = GraphicsOverride(enabled = null),
        )
        assertEquals(false, explicitSharp.sharpnessOverride)
        assertNull(explicitSharp.graphicsOverride.enabled)
        assertEquals(OnsGraphicsSharpnessPolicy.AutoDisabledTarget.ENGINE_SHARPNESS, explicitSharp.autoDisabled)
    }

    @Test
    fun `per-game load normalizes inherited both-on state to false sharpness override`() {
        assertEquals(
            false,
            OnsGraphicsSharpnessPolicy.normalizePerGame(
                globalSharpness = true,
                globalGraphics = enabledOnsFilter(),
                sharpnessOverride = null,
                graphicsOverride = GraphicsOverride(),
            ),
        )
        assertNull(
            OnsGraphicsSharpnessPolicy.normalizePerGame(
                globalSharpness = false,
                globalGraphics = enabledOnsFilter(),
                sharpnessOverride = null,
                graphicsOverride = GraphicsOverride(),
            ),
        )
    }
}
