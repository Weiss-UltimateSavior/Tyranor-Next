package com.tyranor.next.ui.common.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GridReflowSnapshotTest {
    @Test fun firstLayoutAndUnchangedLayoutDoNotOpenAGeneration() {
        val state = GridReflowSnapshot()
        assertFalse(state.update(4, false))
        assertFalse(state.update(4, false))
        assertEquals(0, state.layoutGeneration)
    }

    @Test fun orientationChangesStillCountWhenColumnCountsMatch() {
        val state = GridReflowSnapshot()
        state.update(4, false)
        assertTrue(state.update(4, true))
        assertTrue(state.update(5, true))
        assertEquals(2, state.layoutGeneration)
    }

    @Test fun onlyRealColumnOrOrientationChangesOpenExactlyOneGeneration() {
        val state = GridReflowSnapshot()
        state.update(4, false)
        assertFalse(state.update(4, false))
        assertEquals(0, state.layoutGeneration)
        assertTrue(state.update(6, true))
        assertEquals(1, state.layoutGeneration)
        assertFalse(state.update(6, true))
        assertEquals(1, state.layoutGeneration)
        assertTrue(state.update(4, false))
        assertEquals(2, state.layoutGeneration)
    }

    @Test fun visibleKeysAreOrderedOnlyOnceForTheSameVisibleItemsList() {
        data class Item(val index: Int, val key: String)
        var items = listOf(Item(12, "c"), Item(10, "a"), Item(11, "b"))
        var mappedKeys = 0
        val cache = GridReflowVisibleKeys({ items }, Item::index) { item ->
            mappedKeys++
            item.key
        }

        val initial = cache()
        assertEquals(listOf("a", "b", "c"), initial.toList())
        repeat(8) { assertSame(initial, cache()) }
        assertEquals(3, mappedKeys)

        items = listOf(Item(13, "d"), Item(11, "b"))
        val next = cache()
        assertNotSame(initial, next)
        assertEquals(listOf("b", "d"), next.toList())
        repeat(8) { assertSame(next, cache()) }
        assertEquals(5, mappedKeys)
    }

    @Test fun repeatedQueriesDoNotScanAnAlreadyScheduledSnapshot() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val visible = object : Set<Any> by linkedSetOf<Any>("a", "b") {
            var iterationsAllowed = true
            override fun iterator(): Iterator<Any> {
                check(iterationsAllowed) { "Repeated placement scanned visible keys" }
                return listOf<Any>("a", "b").iterator()
            }
        }
        val slot = requireNotNull(schedule.slotFor("b", visible))
        visible.iterationsAllowed = false
        repeat(8) {
            assertSame(slot, schedule.slotFor("b", visible))
            assertNull(schedule.slotFor("outside", visible))
        }
    }

    @Test fun initialVisibleCardsGetNoSlotsUntilARealLayoutChange() {
        val state = GridReflowSnapshot()
        val schedule = GridReflowEntranceSchedule()
        val visible = linkedSetOf<Any>("old-a", "old-b", "new")
        fun update(columns: Int, landscape: Boolean) {
            if (state.update(columns, landscape)) schedule.reset()
        }

        visible.forEach { assertNull(schedule.slotFor(it, visible)) }
        update(4, false)
        update(4, false)
        visible.forEach { assertNull(schedule.slotFor(it, visible)) }
        update(6, true)
        assertEquals(visible.size, visible.map { requireNotNull(schedule.slotFor(it, visible)).delayMillis }.distinct().size)
    }

    @Test fun firstCallbackAssignsAllOldAndNewVisibleCardsInListOrder() {
        val schedule = GridReflowEntranceSchedule()
        val oldKeys = (0 until 3).map { "old-$it" }
        val newKeys = (0 until 8).map { "new-$it" }
        val expected = oldKeys + newKeys
        val visible = expected.toCollection(LinkedHashSet())
        schedule.reset()
        val first = schedule.slotFor(newKeys.last(), visible)

        expected.forEachIndexed { index, key ->
            val slot = requireNotNull(schedule.slotFor(key, visible))
            assertEquals(index, slot.order)
            assertEquals(index * GridReflowEntranceSchedule.DEFAULT_STEP_MILLIS, slot.delayMillis)
        }
        assertSame(first, schedule.slotFor(newKeys.last(), visible))
        assertTrue(oldKeys.all { schedule.slotFor(it, visible) != null })
        assertEquals(visible.size, visible.map { requireNotNull(schedule.slotFor(it, visible)).delayMillis }.distinct().size)
        assertNull(schedule.slotFor("outside", visible))
    }

    @Test fun repeatedPlacementAndVisibleProviderReorderingKeepTheExactSameSlots() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val visible = (0 until 20).map { "card-$it" }.toCollection(LinkedHashSet())
        val original = visible.associateWith { requireNotNull(schedule.slotFor(it, visible)) }
        val reversed = visible.toList().asReversed().toCollection(LinkedHashSet())

        repeat(5) {
            reversed.forEach { key -> assertSame(original[key], schedule.slotFor(key, reversed)) }
        }
        assertEquals((0 until visible.size).toList(), original.values.map { it.order })
        assertEquals(visible.size, original.values.map { it.delayMillis }.distinct().size)
    }

    @Test fun reverseRegistrationStillFollowsTheOrderedVisibleProvider() {
        val expected = (0 until 20).map { "card-$it" }
        val visible = expected.toCollection(LinkedHashSet())
        val forward = GridReflowEntranceSchedule().apply { reset() }
        val reverse = GridReflowEntranceSchedule().apply { reset() }
        expected.forEach { forward.slotFor(it, visible) }
        expected.reversed().forEach { reverse.slotFor(it, visible) }

        expected.forEachIndexed { index, key ->
            val slot = requireNotNull(reverse.slotFor(key, visible))
            assertEquals(index, slot.order)
            assertEquals(forward.slotFor(key, visible), slot)
        }
    }

    @Test fun laterVisibleCardsAppendInProviderOrderWithoutRetimingEarlierCards() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val initial = (0 until 3).map { "old-$it" }.toCollection(LinkedHashSet())
        val original = initial.associateWith { requireNotNull(schedule.slotFor(it, initial)) }
        val additions = (0 until 7).map { "late-$it" }
        // New keys can precede old keys in list order, but existing slots remain fixed.
        val expanded = (additions.take(3) + initial.toList().asReversed() + additions.drop(3)).toCollection(LinkedHashSet())
        schedule.slotFor(additions.last(), expanded)
        val appended = additions.map { requireNotNull(schedule.slotFor(it, expanded)) }

        initial.forEach { assertSame(original[it], schedule.slotFor(it, expanded)) }
        assertEquals(additions, expanded.sortedBy { requireNotNull(schedule.slotFor(it, expanded)).order }.drop(initial.size))
        assertEquals((3 until 10).toList(), appended.map { it.order })
        assertTrue(appended.all { it.delayMillis > original.values.maxOf { slot -> slot.delayMillis } })
        assertEquals(10, (original.values + appended).map { it.delayMillis }.distinct().size)
    }

    @Test fun temporarilyInvisibleCardsKeepTheirSlotsWhenTheyReturnInTheSameGeneration() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val visible = linkedSetOf<Any>("a", "b", "c")
        val original = visible.associateWith { requireNotNull(schedule.slotFor(it, visible)) }
        assertNull(schedule.slotFor("b", visible - "b"))
        assertSame(original["a"], schedule.slotFor("a", visible - "b"))
        assertSame(original["b"], schedule.slotFor("b", visible))
        assertSame(original["c"], schedule.slotFor("c", visible))
    }

    @Test fun aLargeBatchUsesUniqueDelaysBeyondTheOldTenItemCapWithinBudget() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val visible = (0 until 40).map { "card-$it" }.toCollection(LinkedHashSet())
        val slots = visible.map { requireNotNull(schedule.slotFor(it, visible)) }
        val delays = slots.sortedBy { it.order }.map { it.delayMillis }

        assertEquals((0 until 40).toList(), slots.map { it.order })
        assertEquals(40, delays.distinct().size)
        assertEquals(0L, delays.first())
        assertTrue(delays.last() <= GridReflowEntranceSchedule.STAGGER_BUDGET_MILLIS)
        assertTrue(delays.zipWithNext().all { (a, b) -> b - a >= GridReflowEntranceSchedule.MIN_STEP_MILLIS })
        assertTrue(delays.last() > 10 * 30L)
    }

    @Test fun aDenseBatchKeepsAtLeastOneFrameBetweenCardsInsteadOfMergingTheTail() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val visible = (0 until 60).map { "card-$it" }.toCollection(LinkedHashSet())
        val delays = visible.map { requireNotNull(schedule.slotFor(it, visible)).delayMillis }

        assertEquals(60, delays.distinct().size)
        assertTrue(delays.zipWithNext().all { (a, b) -> b - a >= GridReflowEntranceSchedule.MIN_STEP_MILLIS })
        assertEquals(59 * GridReflowEntranceSchedule.MIN_STEP_MILLIS, delays.last())
    }

    @Test fun lateCardsAfterTheNormalBudgetStillReceiveDistinctIncreasingDelays() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val initial = (0 until 43).map { "first-$it" }.toCollection(LinkedHashSet())
        val original = initial.associateWith { requireNotNull(schedule.slotFor(it, initial)) }
        val expanded = initial + (0 until 10).map { "late-$it" }
        val slots = expanded.map { requireNotNull(schedule.slotFor(it, expanded)) }
        val delays = slots.sortedBy { it.order }.map { it.delayMillis }

        initial.forEach { assertSame(original[it], schedule.slotFor(it, expanded)) }
        assertEquals(53, delays.distinct().size)
        assertTrue(delays.zipWithNext().all { (a, b) -> b - a >= GridReflowEntranceSchedule.MIN_STEP_MILLIS })
    }

    @Test fun aNewLayoutAssignsFreshSlotsUsingTheLatestProviderOrder() {
        val schedule = GridReflowEntranceSchedule().apply { reset() }
        val initial = linkedSetOf<Any>("a", "b", "c")
        val original = initial.associateWith { requireNotNull(schedule.slotFor(it, initial)) }
        schedule.reset()
        val next = linkedSetOf<Any>("d", "b", "a")
        schedule.slotFor("a", next)
        val slots = next.associateWith { requireNotNull(schedule.slotFor(it, next)) }

        assertNotSame(original["a"], slots["a"])
        assertNotSame(original["b"], slots["b"])
        assertEquals(listOf(0, 1, 2), slots.values.map { it.order })
        assertEquals(listOf(0L, 60L, 120L), slots.values.map { it.delayMillis })
        assertNull(schedule.slotFor("c", next))
    }
}
