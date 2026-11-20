package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.external.ImpactoHostContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host's profiles.json (asset tyn/profiles.json) as the app reads it; any malformed part rejects the whole file. */
class ImpactoHostProfilesTest {
    private fun json(profiles: String, layouts: String? = null) =
        """{"version":1,"profiles":[$profiles]${layouts?.let { ""","unsupportedLayouts":[$it]""" } ?: ""}}"""

    private val sg = """{"id":"sg-pc","source":"tyn-patch","name":"STEINS;GATE (PC, Steam)","language":null,
        "detect":{"layout":"pc","allOf":["Game.exe","USRDIR/script.mpk","USRDIR/system.mpk"]}}"""
    private val zhs = """{"id":"mo6tw-pc-zhs","source":"tyn-patch","name":"Memories Off 6","language":"zh-Hans",
        "detect":{"layout":"pc","allOf":["game.exe","data/script.cpk","data/mes00.cpk"],"textArchive":"data/mes02.cpk"},
        "variantOf":"mo6tw-pc"}"""
    private val noDetect = """{"id":"chlcc","source":"upstream","name":"CHAOS;HEAD","language":null,"detect":null}"""

    @Test
    fun readsProfilesWithTheirRulesAndSkipsProfilesWithoutDetection() {
        val rules = ImpactoHostProfiles.parse(json("$sg,$zhs,$noDetect"))!!

        assertEquals(listOf("sg-pc", "mo6tw-pc-zhs"), rules.games.map { it.profileId })
        val sgGame = rules.games[0]
        assertEquals(setOf("Game.exe", "USRDIR/script.mpk", "USRDIR/system.mpk"), sgGame.requiredEntries)
        assertEquals("STEINS;GATE (PC, Steam)", sgGame.title)
        assertNull(sgGame.family)
        assertNull(sgGame.textLanguage)
        assertEquals(0, sgGame.minHostVersionCode)
    }

    @Test
    fun theTextArchiveOfALanguageVariantIsRequiredToo() {
        val game = ImpactoHostProfiles.parse(json(zhs))!!.games.single()

        assertTrue("data/mes02.cpk" in game.requiredEntries)
        assertEquals("mo6tw-pc", game.family)
        assertEquals("zh-Hans", game.textLanguage)
    }

    @Test
    fun readsUnsupportedLayoutsThatPointAtAKnownProfile() {
        val rules = ImpactoHostProfiles.parse(
            json(sg, """{"id":"pc-usrdir-mpk","allOf":["USRDIR/script.mpk","USRDIR/system.mpk"],"tryProfile":"sg-pc"}"""),
        )!!

        val layout = rules.unsupportedLayouts.single()
        assertEquals("pc-usrdir-mpk", layout.id)
        assertEquals(setOf("USRDIR/script.mpk", "USRDIR/system.mpk"), layout.requiredEntries)
        assertEquals("sg-pc", layout.tryProfile)
    }

    @Test
    fun rejectsTheWholeFileOnAnyMalformedPart() {
        val cases = mapOf(
            "not json" to "{",
            "no profiles" to """{"version":1}""",
            "only profiles without detection" to json(noDetect),
            "duplicate id" to json("$sg,$sg"),
            "empty allOf" to json("""{"id":"x","name":"X","language":null,"detect":{"allOf":[]}}"""),
            "absolute entry" to json("""{"id":"x","name":"X","language":null,"detect":{"allOf":["/etc/x"]}}"""),
            "parent entry" to json("""{"id":"x","name":"X","language":null,"detect":{"allOf":["../x"]}}"""),
            "two levels deep" to json("""{"id":"x","name":"X","language":null,"detect":{"allOf":["a/b/c"]}}"""),
            "empty segment" to json("""{"id":"x","name":"X","language":null,"detect":{"allOf":["a//"]}}"""),
            "blank name" to json("""{"id":"x","name":"","language":null,"detect":{"allOf":["a"]}}"""),
            "unknown tryProfile" to
                json(sg, """{"id":"l","allOf":["USRDIR/script.mpk","USRDIR/system.mpk"],"tryProfile":"nope"}"""),
            "layout without entries" to json(sg, """{"id":"l","allOf":[],"tryProfile":"sg-pc"}"""),
            "layout with one entry" to json(sg, """{"id":"l","allOf":["system.cpk"],"tryProfile":"sg-pc"}"""),
            "layout with one entry twice" to
                json(sg, """{"id":"l","allOf":["system.cpk","system.cpk"],"tryProfile":"sg-pc"}"""),
            "number entry" to json("""{"id":"x","name":"X","language":null,"detect":{"allOf":[1]}}"""),
            "null entry" to json("""{"id":"x","name":"X","language":null,"detect":{"allOf":[null]}}"""),
            "number id" to json("""{"id":7,"name":"X","language":null,"detect":{"allOf":["a"]}}"""),
            "number language" to json("""{"id":"x","name":"X","language":3,"detect":{"allOf":["a"]}}"""),
            "detect not an object" to json("""{"id":"x","name":"X","language":null,"detect":"a"}"""),
            "layouts not an array" to """{"profiles":[$sg],"unsupportedLayouts":{}}""",
            "deeply nested" to "{\"profiles\":" + "[".repeat(100_000),
        )
        for ((name, text) in cases) assertNull(name, ImpactoHostProfiles.parse(text))
    }

    @Test
    fun theHostPackageMatchesTheLaunchContract() {
        // The scan layer keeps its own copy to avoid depending on engine/external; both must name the same app.
        assertEquals(ImpactoHostContract.PACKAGE, ImpactoHostProfiles.HOST_PACKAGE)
    }

    @Test
    fun entriesAreRelativeAndAtMostOneDirectoryDeep() {
        assertTrue(ImpactoHostProfiles.isValidEntry("Game.exe"))
        assertTrue(ImpactoHostProfiles.isValidEntry("USRDIR/script.mpk"))
        assertFalse(ImpactoHostProfiles.isValidEntry(""))
        assertFalse(ImpactoHostProfiles.isValidEntry("."))
        assertFalse(ImpactoHostProfiles.isValidEntry("a/../b"))
        assertFalse(ImpactoHostProfiles.isValidEntry("a/b/c"))
    }
}
