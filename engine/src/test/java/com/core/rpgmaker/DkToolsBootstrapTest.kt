package com.core.rpgmaker

import com.core.web.DkToolsCompat
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DkToolsBootstrapTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bootstrapPrecedesHeadScriptsAndPreservesDoctype() {
        val html = "<!DOCTYPE html><html><head><script>window.onload = boot;</script></head><body></body></html>"
        val injected = DkToolsCompat.injectBootstrap(html)
        assertTrue(injected.startsWith("<!DOCTYPE html><html><head>"))
        val bootstrapIndex = injected.indexOf(DkToolsCompat.BOOTSTRAP_SCRIPT)
        assertTrue(bootstrapIndex >= 0)
        assertTrue(bootstrapIndex < injected.indexOf("window.onload = boot"))
        assertTrue(injected.endsWith("</body></html>"))
    }

    @Test fun headlessDocumentsAndFragmentsInstallBeforeBodyOnload() {
        for (html in listOf("<!doctype html><body onload='boot()'></body>", "<body onload='boot()'></body>")) {
            val injected = DkToolsCompat.injectBootstrap(html)
            val bootstrapIndex = injected.indexOf(DkToolsCompat.BOOTSTRAP_SCRIPT)
            assertTrue(bootstrapIndex >= 0)
            assertTrue(bootstrapIndex < injected.indexOf("<body"))
            if (html.startsWith("<!doctype")) assertTrue(injected.startsWith("<!doctype html>"))
        }
    }

    @Test fun bootstrapAlsoPrecedesAnExistingHostHookBeforeHead() {
        val injected = DkToolsCompat.injectBootstrap("<script>hostHook()</script><html><head></head></html>")
        val bootstrapIndex = injected.indexOf(DkToolsCompat.BOOTSTRAP_SCRIPT)
        assertTrue(bootstrapIndex >= 0)
        assertTrue(bootstrapIndex < injected.indexOf("hostHook()"))
    }

    @Test fun exactOriginalStampChecksDiskThenPrefixedAndRootArchiveCandidates() {
        val root = temporary.newFolder()
        val checked = mutableListOf<String>()
        assertTrue(DkToolsCompat.hasExactOriginalStamp(root, "www/") { checked.add(it); it == "data/Stamp.json" })
        assertEquals(listOf("www/data/Stamp.json", "data/Stamp.json"), checked)
        File(root, "data").mkdir()
        val stamp = File(root, "data/Stamp.json").apply { writeText("{}") }
        assertTrue(DkToolsCompat.hasExactOriginalStamp(root, "www/") { error("Disk original must win") })
        stamp.delete()
        stamp.mkdir()
        assertFalse(DkToolsCompat.hasExactOriginalStamp(root, "www/") { error("Disk directory shadows archive file") })
        stamp.delete()
        File(root, "data").delete()
        File(root, "data").writeText("file shadows archive directory")
        assertFalse(DkToolsCompat.hasExactOriginalStamp(root, "www/") { error("Disk file shadows archive directory") })
    }
}
