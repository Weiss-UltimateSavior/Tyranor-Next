package com.tyranor.next.core.game.save.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveLaunchGenerationTest {
    @Test
    fun wrongGenerationIsNeutralizedWithoutDroppingRelatedUris() {
        val oldExternal = JSONObject()
            .put("token", "old-yuris")
            .put("returned", false)
            .put("enabledGeneration", 1L)
        val oldNative = JSONObject()
            .put("token", "old-native")
            .put("startedAt", 1_700_000_000_000L)
            .put("enabledGeneration", 1L)
        val currentNative = JSONObject()
            .put("token", "current-native")
            .put("startedAt", 0L)
            .put("returned", true)
            .put("enabledGeneration", 2L)
        val sessions = mapOf(
            "old-external" to oldExternal,
            "old-native" to oldNative,
            "current-native" to currentNative,
        )

        val evidence = generationFilteredSessionEvidence(sessions.keys, generation = 2L) {
            sessions.getValue(it)
        }

        assertEquals(sessions.keys, evidence.keys)
        assertEquals(0, evidence.getValue("old-external").length())
        assertEquals(0, evidence.getValue("old-native").length())
        assertEquals("current-native", evidence.getValue("current-native").getString("token"))
        assertFalse(evidence.getValue("old-external").has("token"))
        assertTrue(evidence.getValue("current-native").optBoolean("returned"))
    }

    @Test
    fun missingSessionStillProducesAProbeUriEntry() {
        val evidence = generationFilteredSessionEvidence(setOf("native-game"), generation = 3L) { JSONObject() }

        assertTrue(evidence.containsKey("native-game"))
        assertEquals(0, evidence.getValue("native-game").length())
    }

    @Test
    fun launchGraceRejectsRetiredGenerationsButRespectsNewerLaunches() {
        val grace = NativeLaunchGrace(generation = 2L, untilMillis = 15_000L, token = "new-launch")

        assertTrue(nativeLaunchGraceActive(grace, generation = 1L, nowMillis = 10_000L))
        assertFalse(nativeLaunchGraceActive(grace, generation = 3L, nowMillis = 10_000L))
        assertFalse(nativeLaunchGraceActive(grace, generation = 2L, nowMillis = 15_000L))
        assertTrue(nativeLaunchGraceActive(grace, generation = 2L, nowMillis = 14_999L))
        assertFalse(nativeLaunchGraceActive(null, generation = 2L, nowMillis = 0L))
    }

    @Test
    fun failedLaunchCanOnlyClearItsOwnGrace() {
        val grace = NativeLaunchGrace(generation = 2L, untilMillis = 15_000L, token = "replacement")

        assertFalse(nativeLaunchGraceOwnedBy(grace, generation = 1L, token = "old-launch"))
        assertFalse(nativeLaunchGraceOwnedBy(grace, generation = 2L, token = "old-launch"))
        assertTrue(nativeLaunchGraceOwnedBy(grace, generation = 2L, token = "replacement"))
        assertFalse(nativeLaunchGraceOwnedBy(null, generation = 2L, token = "replacement"))
    }

    @Test
    fun neutralizedSessionStillDefersToLiveAndUnknownNativeHostEvidence() {
        val gameUri = "content://saves/native-launch"
        val evidence = generationFilteredSessionEvidence(setOf(gameUri), generation = 2L) {
            JSONObject()
                .put("token", "old-native")
                .put("startedAt", 1_700_000_000_000L)
                .put("enabledGeneration", 1L)
        }
        val neutral = evidence.getValue(gameUri)
        assertEquals(0, neutral.length())
        assertFalse(sessionRequiresHostEvidence(neutral.has("token"), neutral.optLong("startedAt", -1L),
            neutral.optBoolean("returned")))

        val processName = "com.tyranor.next:kirikiroid"
        val liveProbe = object : SaveHostProbe {
            override fun visibleHosts() = listOf(SaveHostProcess(41, processName))
            override fun records() = emptyMap<String, JSONObject>()
            override fun identity(pid: Int) = SaveHostIdentity.Dead
        }
        assertTrue(hasRunningSaveHost(
            probe = liveProbe,
            gameUri = gameUri,
            hasSession = neutral.has("token"),
            inStartGrace = false,
            elapsedMillis = 10_000L,
            relatedUris = evidence.keys,
        ))

        val unknownProbe = object : SaveHostProbe {
            override fun visibleHosts() = emptyList<SaveHostProcess>()
            override fun records() = mapOf(
                SaveContentHash.text(processName) to JSONObject()
                    .put("pid", 42)
                    .put("activities", 1)
                    .put("uris", JSONArray().put(gameUri)),
            )
            override fun identity(pid: Int) = SaveHostIdentity.Unknown
        }
        assertTrue(hasRunningSaveHost(
            probe = unknownProbe,
            gameUri = gameUri,
            hasSession = neutral.has("token"),
            inStartGrace = false,
            elapsedMillis = 10_000L,
            relatedUris = evidence.keys,
        ))
    }
}
