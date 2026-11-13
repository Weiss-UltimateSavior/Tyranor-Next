package com.tyranor.next.ui.common.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class PagePopLevelsTest {
    private data class Owner(val name: String)

    @Test fun directlyOpenedPageCannotConsumeAnotherPagesDepthOrManufactureALevel() {
        val levels = PagePopLevels<Owner>()
        val calls = ArrayList<String>()
        levels.register { calls += "late callback without push" }
        val owner = Owner("pushed page")
        levels.bind(levels.push(), owner)
        levels.register { calls += "page" }
        levels.register { calls += "sheet" }

        levels.pop(Owner("direct page"))
        levels.pop(owner.copy()) // Value equality is not Activity identity.
        assertEquals(emptyList<String>(), calls)
        levels.pop(owner)
        levels.pop(owner) // Repeated finish is harmless even without the Activity guard.
        assertEquals(listOf("page", "sheet"), calls)
    }

    @Test fun nestedPagesOnlyUnfoldTheirImmediateLowerLevelInStackOrder() {
        val levels = PagePopLevels<Owner>()
        val calls = ArrayList<String>()
        val first = Owner("first")
        val second = Owner("second")
        levels.bind(levels.push(), first)
        levels.register { calls += "main" }
        levels.bind(levels.push(), second)
        levels.register { calls += "first" }

        levels.pop(first)
        assertEquals(emptyList<String>(), calls)
        levels.pop(second)
        assertEquals(listOf("first"), calls)
        levels.pop(first)
        assertEquals(listOf("first", "main"), calls)
    }

    @Test fun recreatedPageTakesOwnershipAndTheOldInstanceCannotPop() {
        val levels = PagePopLevels<Owner>()
        val token = levels.push()
        val old = Owner("same page")
        val recreated = old.copy()
        var unfolds = 0
        levels.bind(token, old)
        levels.register { unfolds++ }
        levels.bind(token, recreated)
        levels.pop(old)
        assertEquals(0, unfolds)
        levels.pop(recreated)
        assertEquals(1, unfolds)
    }

    @Test fun failedPushCancelsOnlyItsTokenAndDisposedCallbacksStayRemoved() {
        val levels = PagePopLevels<Owner>()
        val failed = levels.push()
        levels.register { error("A cancelled push must not run") }
        val owner = Owner("later push")
        val later = levels.push()
        levels.bind(later, owner)
        var calls = 0
        val disposed: () -> Unit = { error("A disposed callback must not run") }
        levels.register(disposed)
        levels.register { calls++ }
        levels.unregister(disposed)
        levels.cancel(failed)
        levels.pop(owner)
        assertEquals(1, calls)
        levels.bind(failed, owner)
        levels.pop(owner)
        assertEquals(1, calls)
    }
}
