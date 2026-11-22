package com.tyranor.next.core.engine.external

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEngineUpdateCheckerTest {
    private val source = ImpactoExternalEngineModule.updateSource

    @Test
    fun releaseTagMatchesTheHostVersionAndRequiresAnApk() {
        assertEquals("https://github.com/yuukana1/Tyranor-Next-MAGES/releases", source.releasesUrl)
        assertEquals("0.3.1", ExternalEngineUpdateChecker.versionFromRelease(source, release()))
        assertNull(ExternalEngineUpdateChecker.versionFromRelease(source, release(tag = "v0.3.1")))
        assertNull(ExternalEngineUpdateChecker.versionFromRelease(source, release(tag = "impacto-host-0.3.1-rc1")))
        assertNull(ExternalEngineUpdateChecker.versionFromRelease(source, release(asset = "source.zip")))
    }

    @Test
    fun draftAndPrereleaseAreNotOffered() {
        assertNull(ExternalEngineUpdateChecker.versionFromRelease(source, release().put("draft", true)))
        assertNull(ExternalEngineUpdateChecker.versionFromRelease(source, release().put("prerelease", true)))
    }

    @Test
    fun onlyStrictlyNewerNumericVersionsAreUpdates() {
        assertTrue(ExternalEngineUpdateChecker.isNewerVersion("0.3.10", "0.3.9"))
        assertTrue(ExternalEngineUpdateChecker.isNewerVersion("0.4", "0.3.10"))
        assertFalse(ExternalEngineUpdateChecker.isNewerVersion("0.3.1", "0.3.1"))
        assertFalse(ExternalEngineUpdateChecker.isNewerVersion("0.3.1.0", "0.3.1"))
        assertFalse(ExternalEngineUpdateChecker.isNewerVersion("0.3.1", "0.4.0"))
        assertFalse(ExternalEngineUpdateChecker.isNewerVersion("0.3.1", "0.2.0-dev"))
    }

    private fun release(tag: String = "impacto-host-0.3.1", asset: String = "Tyranor-Next-MAGES.apk"): JSONObject =
        JSONObject().put("tag_name", tag).put("assets", JSONArray().put(JSONObject().put("name", asset)))
}
