package com.core.rpgmaker

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusedArchiveIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bothReadersRecoverLanguagesAfterGlobalArchiveNodeLimit() {
        val file = temporary.newFile("oversized.asar")
        // One flat key exercises the node budget without creating 250,000 JSON objects.
        writeTestAsar(file, packed = mapOf(
            "www/img/" + "d/".repeat(250_000) + "leaf.png" to "",
            "www/Locales/en/messages.json" to "{}",
            "www/data/System.json" to "{}",
            "www/locales-other/unrelated.json" to "{}",
        ), emptyDirectories = setOf("www/Locales/empty"))
        AsarArchive(file).use { modern ->
            com.core.tyrano.AsarArchive(file).use { legacy ->
                for (builder in listOf(
                    RpgMakerFileIndex(temporary.newFolder(), modern, "www/"),
                    RpgMakerFileIndex(temporary.newFolder(), asarPrefix = "www/", legacyArchive = legacy),
                )) {
                    try { builder.build(); fail("global archive node budget must fail") }
                    catch (error: RpgMakerFileIndex.LimitExceeded) { assertEquals("archive node", error.limit) }
                    val index = JSONObject(String(builder.build(setOf("locales", "data"))))
                    assertEquals(setOf("Locales", "data"), index.keys().asSequence().toSet())
                    assertTrue(index.getJSONObject("Locales").getJSONObject("en").has("messages.json"))
                    assertEquals("directory", index.getJSONObject("Locales").getJSONObject("empty")
                        .getJSONObject("__stats__").getString("type"))
                    assertTrue(index.getJSONObject("data").has("System.json"))
                }
            }
        }
    }

    @Test fun focusedArchiveStillHonorsEntryBudget() {
        val file = temporary.newFile("focused-budget.asar")
        writeTestAsar(file, packed = mapOf("locales/a.json" to "{}", "locales/b.json" to "{}"))
        AsarArchive(file).use { archive ->
            try {
                RpgMakerFileIndex(temporary.newFolder(), archive, maxEntries = 3).build(setOf("locales"))
                fail("focused scans must retain entry limits")
            } catch (error: RpgMakerFileIndex.LimitExceeded) { assertEquals("entry", error.limit) }
        }
    }

    @Test fun genericChildIoFailureDoesNotHideReadableLanguages() {
        val root = temporary.newFolder()
        File(root, "denied").mkdir()
        File(root, "locales").mkdir()
        File(root, "locales/en.json").writeText("{}")
        val bytes = RpgMakerFileIndex(root, directoryReader = { directory ->
            if (directory.name == "denied") throw IOException("temporary child read failure")
            Files.newDirectoryStream(directory.toPath())
        }).build()
        assertTrue(JSONObject(String(bytes)).getJSONObject("locales").has("en.json"))
    }

    @Test(expected = InterruptedIOException::class)
    fun childReadCancellationStillPropagates() {
        val root = temporary.newFolder()
        File(root, "cancelled").mkdir()
        RpgMakerFileIndex(root, directoryReader = { directory ->
            if (directory.name == "cancelled") throw InterruptedIOException("cancelled")
            Files.newDirectoryStream(directory.toPath())
        }).build()
    }
}
