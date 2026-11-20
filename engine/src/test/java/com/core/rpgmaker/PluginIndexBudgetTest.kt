package com.core.rpgmaker

import java.io.File
import java.io.InterruptedIOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginIndexBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun focusedIndexKeepsLanguagesWhenUnrelatedAssetsExceedBudget() {
        val root = temporary.newFolder()
        File(root, "locales").mkdir()
        File(root, "locales/en.json").writeText("{}")
        File(root, "img").mkdir()
        repeat(10) { File(root, "img/$it.png").writeText("") }
        val builder = RpgMakerFileIndex(root, maxEntries = 5)
        try { builder.build(); fail("full scan must exceed quota") }
        catch (error: RpgMakerFileIndex.LimitExceeded) { assertEquals("entry", error.limit) }
        val index = JSONObject(String(builder.build(setOf("locales", "data"))))
        assertTrue(index.getJSONObject("locales").has("en.json"))
        assertFalse(index.has("img"))
    }

    @Test(expected = InterruptedIOException::class) fun cancelledScanDoesNotReturnPartialMetadata() {
        RpgMakerFileIndex(temporary.newFolder(), isCancelled = { true }).build()
    }

    @Test fun timeBudgetProducesTypedFailure() {
        var tick = 0L
        val builder = RpgMakerFileIndex(temporary.newFolder(), nanoTime = { tick++ }, maxDurationNanos = 0)
        try { builder.build(); fail("time budget must be enforced") }
        catch (error: RpgMakerFileIndex.LimitExceeded) { assertEquals("time", error.limit) }
    }
}
