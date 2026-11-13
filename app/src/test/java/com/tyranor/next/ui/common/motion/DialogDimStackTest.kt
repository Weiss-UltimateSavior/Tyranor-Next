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
    }

    private fun stack() = DialogDimStack<Window>({ it.visible }) { window, dim, scrim, nativeEnabled ->
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
}
