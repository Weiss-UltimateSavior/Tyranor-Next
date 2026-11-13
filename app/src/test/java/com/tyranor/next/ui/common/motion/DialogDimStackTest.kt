package com.tyranor.next.ui.common.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogDimStackTest {
    private class Window {
        var visible = true
        var applied = -1f
        var scrim = 0f
        var nativeEnabled = false
        var panelDim = 0f
    }

    private fun stack() = DialogDimStack<Window>(
        isVisible = { it.visible },
        applyPanelDim = { window, alpha -> window.panelDim = alpha },
    ) { window, dim, scrim, nativeEnabled ->
        window.applied = dim
        window.scrim = scrim
        window.nativeEnabled = nativeEnabled
    }

    @Test fun openingAndClosingChildNeverReplacesTheParentWithZeroDim() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val child = Window()
        stack.register(owner, parent, 0.5f, true)
        stack.register(owner, child, 0f, true)
        assertEquals(0.5f, child.applied, 0.0001f)
        for (dim in listOf(0.2f, 0.8f, 0.4f, 0.1f, 0f)) {
            stack.update(child, dim, true)
            assertEquals(0.5f, child.applied, 0.0001f)
        }
        stack.remove(child)
        assertEquals(0.5f, parent.applied, 0.0001f)
        stack.update(parent, 0f, true)
        assertEquals(0f, parent.applied, 0.0001f)
    }

    @Test fun removingOrHidingLowerWindowsDoesNotRetainAnInheritedFloor() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val middle = Window()
        val top = Window()
        stack.register(owner, parent, 0.6f, true)
        stack.register(owner, middle, 0.3f, true)
        stack.register(owner, top, 0f, true)
        assertEquals(0.6f, top.applied, 0.0001f)
        stack.remove(parent)
        assertEquals(0.3f, top.applied, 0.0001f)
        middle.visible = false
        stack.refresh()
        assertEquals(0f, top.applied, 0.0001f)
        middle.visible = true
        stack.refresh()
        assertEquals(0.3f, top.applied, 0.0001f)
        stack.update(middle, 0f, true)
        assertEquals(0f, top.applied, 0.0001f)
    }

    @Test fun anotherActivityAndWindowsWithoutNativeDimDoNotContribute() {
        val stack = stack()
        val oldPage = Window()
        val newPage = Window()
        val customScrim = Window()
        val owner = Any()
        stack.register(Any(), oldPage, 0.6f, true)
        stack.register(owner, customScrim, 0.8f, false)
        stack.register(owner, newPage, 0.1f, true)
        assertEquals(0f, customScrim.applied, 0.0001f)
        assertEquals(0.1f, newPage.applied, 0.0001f)
    }

    @Test fun attachingAndRemovingWindowsUsesTheirActualStackOrder() {
        val stack = stack()
        val owner = Any()
        val pending = Window().apply { visible = false }
        val visible = Window()
        stack.register(owner, pending, 0.7f, true)
        stack.register(owner, visible, 0.2f, true)
        assertEquals(0.2f, visible.applied, 0.0001f)
        pending.visible = true
        stack.attached(pending)
        assertEquals(0.2f, pending.applied, 0.0001f)
        assertEquals(0.2f, visible.applied, 0.0001f)
        stack.remove(pending)
        stack.remove(pending) // A late detach after disposal is harmless.
        val replacement = Window()
        stack.register(owner, replacement, 0f, true)
        assertEquals(0.2f, replacement.applied, 0.0001f)
    }

    @Test fun nestedNativeAndDrawnMasksNeverDeepenAndHandOffWhenTheLowerMaskFades() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val child = Window()
        stack.register(owner, parent, 0.4f, true, scrim = 0.5f)
        stack.register(owner, child, 0.7f, true, scrim = 0.5f)
        assertEquals(0.4f, child.applied, 0.0001f)
        assertEquals(0f, child.scrim, 0.0001f)
        for (progress in listOf(1f, 0.8f, 0.4f, 0f)) {
            stack.update(parent, 0.4f * progress, true, baseDim = 0.4f,
                scrim = 0.5f * progress, baseScrim = 0.5f)
            val combinedScrim = 1f - (1f - parent.scrim) * (1f - child.scrim)
            val combinedMask = 1f - (1f - child.applied) * (1f - combinedScrim)
            assertEquals(0.7f, combinedMask, 0.0001f)
        }
        stack.remove(parent)
        assertEquals(0.7f, child.applied, 0.0001f)
        assertEquals(0.5f, child.scrim, 0.0001f)
    }

    @Test fun searchMaskDoesNotAddADrawnLayerOverAnEnhancedDialog() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val search = Window()
        stack.register(owner, parent, 0.5f, true)
        stack.register(owner, search, 0.32f, true, scrim = 0.5f)
        assertEquals(0.5f, search.applied, 0.0001f)
        assertEquals(0f, search.scrim, 0.0001f)
        for (progress in listOf(0.8f, 0.4f, 0f)) {
            stack.update(parent, 0.5f * progress, true, baseDim = 0.5f)
            assertEquals(0.5f, search.applied, 0.0001f)
            assertEquals(0f, search.scrim, 0.0001f)
        }
        stack.remove(parent)
        assertEquals(0.32f, search.applied, 0.0001f)
        assertEquals(0.5f, search.scrim, 0.0001f)
    }

    @Test fun aSelfDrawnSheetContributesWithoutAddingANativeDimRequest() {
        val stack = stack()
        val owner = Any()
        val sheet = Window()
        val child = Window()
        stack.register(owner, sheet, 0f, false, scrim = 0.5f)
        stack.register(owner, child, 0.5f, true)
        assertEquals(0f, sheet.applied, 0.0001f)
        assertEquals(0f, child.applied, 0.0001f)
        for (progress in listOf(1f, 0.8f, 0.4f, 0f)) {
            stack.update(sheet, 0f, false, scrim = 0.5f * progress, baseScrim = 0.5f)
            assertEquals(0f, sheet.applied, 0.0001f)
            val total = 1f - (1f - child.applied) * (1f - sheet.scrim)
            assertEquals(0.5f, total, 0.0001f)
        }
        stack.remove(sheet)
        assertEquals(0.5f, child.applied, 0.0001f)
    }

    @Test fun aShallowerCropMaskHoldsTheInheritedMaskWhileItsParentFades() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val crop = Window()
        stack.register(owner, parent, 0.5f, true)
        stack.register(owner, crop, 0.32f, true)
        for (progress in listOf(1f, 0.8f, 0.4f, 0f)) {
            stack.update(parent, 0.5f * progress, true, baseDim = 0.5f)
            assertEquals(0.5f, crop.applied, 0.0001f)
        }
        stack.remove(parent)
        assertEquals(0.32f, crop.applied, 0.0001f)
    }

    @Test fun nestedExitImmediatelyReturnsDimOwnershipToTheLowerWindow() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val child = Window()
        stack.register(owner, parent, 0.5f, true)
        stack.register(owner, child, 0.32f, true, scrim = 0.5f)
        assertTrue(child.nativeEnabled)
        assertEquals(0.5f, child.applied, 0.0001f)

        assertTrue(stack.beginExit(child))
        assertTrue(stack.beginExit(child))
        assertEquals(0f, child.applied, 0.0001f)
        assertEquals(0f, child.scrim, 0.0001f)
        assertFalse(child.nativeEnabled)
        assertEquals(0.5f, parent.applied, 0.0001f)
        assertTrue(parent.nativeEnabled)

        // Animations and ordinary recomposition keep publishing raw progress during exit.
        stack.update(child, 0.16f, true, baseDim = 0.32f, scrim = 0.25f, baseScrim = 0.5f)
        assertEquals(0f, child.applied, 0.0001f)
        assertEquals(0f, child.scrim, 0.0001f)
        assertFalse(child.nativeEnabled)
        stack.remove(parent)
        assertEquals(0f, child.applied, 0.0001f)
        assertFalse(child.nativeEnabled)
        stack.restore(child)
        assertEquals(0.16f, child.applied, 0.0001f)
        assertEquals(0.25f, child.scrim, 0.0001f)
        assertTrue(child.nativeEnabled)
    }

    @Test fun aSingleWindowKeepsItsOwnFadeWhenExitBegins() {
        val stack = stack()
        val window = Window()
        stack.register(Any(), window, 0.5f, true, scrim = 0.4f)
        assertFalse(stack.beginExit(window))
        stack.update(window, 0.25f, true, baseDim = 0.5f, scrim = 0.2f, baseScrim = 0.4f)
        assertEquals(0.25f, window.applied, 0.0001f)
        assertEquals(0.2f, window.scrim, 0.0001f)
        assertTrue(window.nativeEnabled)
    }

    @Test fun explicitReleaseLatchesEvenASingleWindowAndRestoreUsesTheLatestRawRequest() {
        val stack = stack()
        val window = Window()
        stack.register(Any(), window, 0.5f, true, scrim = 0.4f)
        stack.release(window)
        stack.release(window)
        stack.update(window, 0.3f, true, baseDim = 0.6f, scrim = 0.2f, baseScrim = 0.4f)
        assertEquals(0f, window.applied, 0.0001f)
        assertEquals(0f, window.scrim, 0.0001f)
        assertFalse(window.nativeEnabled)
        stack.restore(window)
        stack.restore(window)
        assertEquals(0.3f, window.applied, 0.0001f)
        assertEquals(0.2f, window.scrim, 0.0001f)
        assertTrue(window.nativeEnabled)
    }

    @Test fun otherOwnersHiddenAndReleasedWindowsCannotTurnASingleExitIntoANestedExit() {
        val stack = stack()
        val owner = Any()
        val otherPage = Window()
        val hidden = Window().apply { visible = false }
        val released = Window()
        val noMask = Window()
        val current = Window()
        stack.register(Any(), otherPage, 0.8f, true)
        stack.register(owner, hidden, 0.6f, true)
        stack.register(owner, released, 0.5f, true)
        stack.release(released)
        stack.register(owner, noMask, 0f, false)
        stack.register(owner, current, 0.3f, true)

        assertFalse(stack.beginExit(current))
        stack.update(current, 0.15f, true, baseDim = 0.3f)
        assertEquals(0.15f, current.applied, 0.0001f)
        assertTrue(current.nativeEnabled)
    }

    @Test fun aSelfDrawnLowerMaskQualifiesForImmediateNestedExit() {
        val stack = stack()
        val owner = Any()
        val sheet = Window()
        val child = Window()
        stack.register(owner, sheet, 0f, false, scrim = 0.5f)
        stack.register(owner, child, 0.32f, true)
        assertTrue(stack.beginExit(child))
        assertEquals(0f, child.applied, 0.0001f)
        assertEquals(0f, child.scrim, 0.0001f)
        assertFalse(child.nativeEnabled)
        assertEquals(0.5f, sheet.scrim, 0.0001f)
        assertFalse(sheet.nativeEnabled)

        stack.update(child, 0.16f, true, baseDim = 0.32f)
        stack.restore(child)
        assertEquals(0f, child.applied, 0.0001f)
        assertTrue(child.nativeEnabled)
        stack.remove(sheet)
        assertEquals(0.16f, child.applied, 0.0001f)
    }

    @Test fun releasedWindowCannotContributeAFloorAfterAttachmentReordersIt() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val exiting = Window()
        val replacement = Window()
        stack.register(owner, parent, 0.5f, true)
        stack.register(owner, exiting, 0.4f, true)
        stack.beginExit(exiting)
        stack.register(owner, replacement, 0.2f, true)
        stack.attached(exiting)
        assertFalse(exiting.nativeEnabled)
        assertEquals(0f, exiting.applied, 0.0001f)
        stack.remove(parent)
        assertEquals(0.2f, replacement.applied, 0.0001f)
        assertFalse(exiting.nativeEnabled)

        stack.restore(exiting)
        assertTrue(exiting.nativeEnabled)
        assertEquals(0.2f, exiting.applied, 0.0001f)
    }

    @Test fun reattachingAReleasedWindowStartsWithoutTakingNativeDimOwnership() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val child = Window()
        stack.register(owner, parent, 0.5f, true)
        stack.register(owner, child, 0.32f, true)
        assertTrue(stack.beginExit(child))
        stack.remove(child)

        // Binding retains the release latch and latest raw request while its View is detached.
        stack.register(owner, child, 0.16f, true, baseDim = 0.32f, released = true)
        stack.attached(child)
        assertEquals(0f, child.applied, 0.0001f)
        assertFalse(child.nativeEnabled)
        assertTrue(stack.beginExit(child))
        stack.update(child, 0.24f, true, baseDim = 0.32f)
        assertFalse(child.nativeEnabled)

        stack.remove(parent)
        assertFalse(child.nativeEnabled)
        stack.restore(child)
        assertEquals(0.24f, child.applied, 0.0001f)
        assertTrue(child.nativeEnabled)
        assertFalse(stack.beginExit(Window()))
    }

    @Test fun sheetPanelGetsTheMissingMaskWithoutMovingItsRootScrim() {
        val stack = stack()
        val owner = Any()
        val sheet = Window()
        val child = Window()
        stack.register(owner, sheet, 0f, false, scrim = 0.32f)
        val baseline = rootMask(sheet)
        stack.register(owner, child, 0.5f, true)
        assertEquals(0.32f, sheet.scrim, 0f)
        assertEquals(0f, child.applied, 0.0001f)
        assertTrue(child.nativeEnabled)
        assertEquals(baseline, rootMask(sheet, child), 0.0001f)
        assertEquals(0.32f, sheet.panelDim, 0.0001f)
        assertEquals(0.32f, panelMask(sheet, sheet, child), 0.0001f)
        assertEquals(0f, child.panelDim, 0f)

        assertTrue(stack.beginExit(child))
        assertEquals(0.32f, sheet.scrim, 0f)
        assertEquals(0f, sheet.panelDim, 0f)
        assertEquals(baseline, rootMask(sheet, child), 0.0001f)
        stack.update(child, 0.25f, true, baseDim = 0.5f)
        assertEquals(0f, sheet.panelDim, 0f)
        stack.restore(child)
        assertEquals(0.32f, sheet.panelDim, 0.0001f)
        assertEquals(baseline, rootMask(sheet, child), 0.0001f)
        stack.remove(child)
        assertEquals(0f, sheet.panelDim, 0f)
    }

    @Test fun ordinaryPanelComplementsTheUpperNativeMaskWithoutDeepeningTheRoot() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val child = Window()
        stack.register(owner, parent, 0.6f, true, scrim = 0.5f)
        val baseline = rootMask(parent)
        assertEquals(0.8f, baseline, 0.0001f)
        stack.register(owner, child, 0.32f, true, scrim = 0.5f)
        // Keep the established root allocation byte-for-byte: native .6 plus lower drawn .5.
        assertEquals(0.6f, child.applied, 0.0001f)
        assertEquals(0.5f, parent.scrim, 0f)
        assertEquals(0f, child.scrim, 0f)
        assertEquals(0.5f, parent.panelDim, 0.0001f)
        assertEquals(baseline, rootMask(parent, child), 0.0001f)
        assertEquals(baseline, panelMask(parent, parent, child), 0.0001f)
        assertTrue(stack.beginExit(child))
        assertEquals(0f, parent.panelDim, 0f)
        assertEquals(baseline, rootMask(parent, child), 0.0001f)
        stack.restore(child)
        assertEquals(baseline, panelMask(parent, parent, child), 0.0001f)
        stack.remove(parent)
        assertEquals(0.32f, child.applied, 0.0001f)
        assertEquals(0.5f, child.scrim, 0f)
        assertEquals(0f, child.panelDim, 0f)
    }

    @Test fun anUpperSelfOnlyMaskSupplementsOnlyTheLowerPanel() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val sheet = Window()
        stack.register(owner, parent, 0.4f, true, scrim = 0.2f)
        val baseline = rootMask(parent)
        stack.register(owner, sheet, 0f, false, scrim = 0.32f)
        assertTrue(parent.nativeEnabled)
        assertEquals(0.4f, parent.applied, 0f)
        assertEquals(0.2f, parent.scrim, 0f)
        assertEquals(0f, sheet.scrim, 0.0001f)
        // A parent's own native mask is behind its panel and cannot count as external dim.
        assertEquals(baseline, parent.panelDim, 0.0001f)
        assertEquals(baseline, panelMask(parent, parent, sheet), 0.0001f)
        assertEquals(baseline, rootMask(parent, sheet), 0.0001f)
        assertTrue(stack.beginExit(sheet))
        assertEquals(0f, parent.panelDim, 0f)
        assertEquals(baseline, rootMask(parent, sheet), 0.0001f)
        stack.restore(sheet)
        stack.remove(parent)
        assertEquals(0.32f, sheet.scrim, 0f)
        assertEquals(0f, sheet.panelDim, 0f)
    }

    @Test fun mixedThreeLayerPanelsUseTheHighestNativeAndAllUpperDrawnMasks() {
        val stack = stack()
        val owner = Any()
        val parent = Window()
        val middle = Window()
        val top = Window()
        stack.register(owner, parent, 0.4f, true, scrim = 0.2f)
        stack.register(owner, middle, 0f, false, scrim = 0.3f)
        stack.register(owner, top, 0.32f, true, scrim = 0.5f)
        assertEquals(0.52f, rootMask(parent, middle, top), 0.0001f)
        assertEquals(0.52f, panelMask(parent, parent, middle, top), 0.0001f)
        assertEquals(0.52f, panelMask(middle, parent, middle, top), 0.0001f)
        assertEquals(0.2f, parent.panelDim, 0.0001f)
        assertEquals(0.2f, middle.panelDim, 0.0001f)
        assertTrue(stack.beginExit(top))
        assertEquals(0.52f, parent.panelDim, 0.0001f)
        assertEquals(0f, middle.panelDim, 0f)
        assertEquals(0.52f, rootMask(parent, middle, top), 0.0001f)
        assertTrue(stack.beginExit(middle))
        assertEquals(0f, parent.panelDim, 0f)
        assertEquals(0.52f, rootMask(parent, middle, top), 0.0001f)

        // With the higher native released, an upper drawn supplement still affects the parent.
        stack.remove(top)
        stack.remove(middle)
        stack.update(parent, 0.2f, true, baseDim = 0.4f, scrim = 0.1f, baseScrim = 0.2f)
        stack.register(owner, middle, 0f, false, scrim = 0.3f)
        assertTrue(middle.scrim > 0f)
        assertEquals(0.52f, rootMask(parent, middle), 0.0001f)
        assertEquals(0.52f, panelMask(parent, parent, middle), 0.0001f)
    }

    @Test fun pendingReleasedHiddenAndOtherOwnersCannotLeavePanelSupplementsBehind() {
        data class Owner(val name: String)
        val stack = stack()
        val owner = Owner("same")
        val otherOwner = Owner("same")
        val sheet = Window()
        val child = Window().apply { visible = false }
        val other = Window()
        stack.register(owner, sheet, 0f, false, scrim = 0.32f)
        stack.register(otherOwner, other, 0.6f, true, scrim = 0.5f)
        stack.register(owner, child, 0f, true, baseDim = 0.5f)
        assertEquals(0f, sheet.panelDim, 0f)
        assertEquals(0f, other.panelDim, 0f)
        child.visible = true
        stack.attached(child)
        assertEquals(0.32f, sheet.panelDim, 0.0001f)
        assertEquals(0.32f, sheet.scrim, 0f)
        assertEquals(0f, child.applied, 0f)
        assertEquals(0f, other.panelDim, 0f)
        assertEquals(0.5f, other.scrim, 0f)

        child.visible = false
        stack.refresh()
        assertEquals(0f, sheet.panelDim, 0f)
        child.visible = true
        stack.attached(child)
        assertTrue(stack.beginExit(child))
        stack.remove(child)
        stack.register(owner, child, 0.25f, true, baseDim = 0.5f, released = true)
        stack.attached(child)
        assertEquals(0f, sheet.panelDim, 0f)
        stack.update(sheet, 0f, false, scrim = 0.4f, baseScrim = 0.5f)
        stack.update(child, 0.2f, true, baseDim = 0.5f)
        assertEquals(0f, sheet.panelDim, 0f)
        stack.restore(child)
        assertEquals(0.4f, panelMask(sheet, sheet, child), 0.0001f)
        assertEquals(0.4f, rootMask(sheet, child), 0.0001f)
        stack.remove(child)
        assertEquals(0f, sheet.panelDim, 0f)
    }

    @Test fun aReleasedMiddlePanelStillReceivesTheMaskOfTheVisibleWindowAboveIt() {
        val stack = stack()
        val owner = Any()
        val bottom = Window()
        val middle = Window()
        val top = Window()
        stack.register(owner, bottom, 0f, false, scrim = 0.32f)
        stack.register(owner, middle, 0f, false, scrim = 0.32f)
        stack.register(owner, top, 0.5f, true)
        assertEquals(0f, top.applied, 0.0001f)
        assertEquals(0.32f, rootMask(bottom, middle, top), 0.0001f)
        assertEquals(0.32f, panelMask(middle, bottom, middle, top), 0.0001f)

        assertTrue(stack.beginExit(middle))
        assertEquals(0f, middle.scrim, 0f)
        assertFalse(middle.nativeEnabled)
        assertEquals(0.32f, middle.panelDim, 0.0001f)
        assertEquals(0.32f, panelMask(middle, bottom, middle, top), 0.0001f)
        assertEquals(0.32f, panelMask(bottom, bottom, middle, top), 0.0001f)
        assertEquals(0.32f, rootMask(bottom, middle, top), 0.0001f)
        stack.update(middle, 0f, false, scrim = 0.1f, baseScrim = 0.32f)
        assertEquals(0f, middle.scrim, 0f)
        assertEquals(0.32f, middle.panelDim, 0.0001f)

        middle.visible = false
        stack.refresh()
        assertEquals(0f, middle.panelDim, 0f)
        middle.visible = true
        stack.attached(middle)
        // A released surface reattached above top no longer has any upper source.
        assertEquals(0f, middle.panelDim, 0f)
        assertEquals(0.32f, bottom.panelDim, 0.0001f)
        assertTrue(stack.beginExit(top))
        assertEquals(0f, top.panelDim, 0f)
        assertEquals(0f, bottom.panelDim, 0f)
        assertEquals(0.32f, rootMask(bottom, middle, top), 0.0001f)
    }

    @Test fun nativeOnlyAndSingleWindowsNeedNoPanelSupplementAndOpaqueMasksStayFinite() {
        val stack = stack()
        for ((dim, scrim) in listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f, 0.32f to 0.5f)) {
            val owner = Any()
            val parent = Window()
            val child = Window()
            stack.register(owner, parent, dim, true, scrim = scrim)
            assertEquals(dim, parent.applied, 0f)
            assertEquals(scrim, parent.scrim, 0f)
            assertEquals(0f, parent.panelDim, 0f)
            val baseline = rootMask(parent)
            stack.register(owner, child, 0f, true, baseDim = 0.5f)
            assertEquals(baseline, rootMask(parent, child), 0.0001f)
            assertEquals(baseline, panelMask(parent, parent, child), 0.0001f)
            assertTrue(parent.panelDim.isFinite())
            assertEquals(0f, child.panelDim, 0f)
            if (scrim == 0f) assertEquals(0f, parent.panelDim, 0f)
            assertTrue(stack.beginExit(child))
            assertEquals(0f, parent.panelDim, 0f)
        }
        // Source compatibility: applyDim remains the trailing lambda when panel drawing is absent.
        val legacy = DialogDimStack<Window>({ it.visible }) { window, dim, _, _ -> window.applied = dim }
        val window = Window()
        legacy.register(Any(), window, 0.3f, true)
        assertEquals(0.3f, window.applied, 0f)
    }

    /** WMS uses the highest eligible native layer; panel-local drawing never changes the root. */
    private fun rootMask(vararg windows: Window): Float {
        var alpha = windows.lastOrNull { it.visible && it.nativeEnabled }?.applied ?: 0f
        for (window in windows) if (window.visible) alpha = over(alpha, window.scrim)
        return alpha
    }

    /** A panel sees its own supplement, plus only native/drawn masks from higher Windows. */
    private fun panelMask(panel: Window, vararg windows: Window): Float {
        val panelIndex = windows.indexOfFirst { it === panel }
        val nativeIndex = windows.indexOfLast { it.visible && it.nativeEnabled }
        var alpha = if (nativeIndex > panelIndex) windows[nativeIndex].applied else 0f
        for (index in panelIndex + 1 until windows.size) {
            if (windows[index].visible) alpha = over(alpha, windows[index].scrim)
        }
        return over(alpha, panel.panelDim)
    }

    private fun over(first: Float, second: Float): Float = 1f - (1f - first) * (1f - second)
}
