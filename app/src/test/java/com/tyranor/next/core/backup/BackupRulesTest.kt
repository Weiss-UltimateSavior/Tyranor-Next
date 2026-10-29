package com.tyranor.next.core.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesTest {
    @Test
    fun defaultDomainsRemainEligibleInAllBackupSections() {
        sections().forEach { section ->
            assertTrue(eligible(section, "root", "games/game-key/savedata/slot.ksd"))
            assertTrue(eligible(section, "sharedpref", "app_settings.xml"))
            assertTrue(eligible(section, "sharedpref", "game_scanner.xml"))
            assertTrue(eligible(section, "sharedpref", "tyranor_game_overrides.xml"))
            assertTrue(eligible(section, "file", "krkr_mirror/game/savedata/slot.ksd"))
            assertTrue(eligible(section, "external", "save/tyrano/game/slot.sav"))
            assertTrue(eligible(section, "database", "other.db"))
        }
    }

    @Test
    fun credentialsAndRoomSnapshotsRemainExcludedInAllSections() {
        sections().forEach { section ->
            assertFalse(eligible(section, "sharedpref", "hikarinagi_auth.xml"))
            assertFalse(eligible(section, "sharedpref", "hikarinagi_auth_secure.xml"))
            listOf("game_library.db", "game_library.db-wal", "game_library.db-shm").forEach {
                assertFalse(eligible(section, "database", it))
            }
        }
    }

    private fun sections(): List<Element> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        fun root(name: String): Element {
            val file = File("src/main/res/xml", name)
            assertTrue("Missing actual backup resource: $file", file.isFile)
            return factory.newDocumentBuilder().parse(file).documentElement
        }
        val legacy = root("backup_rules.xml")
        val modern = root("data_extraction_rules.xml")
        return listOf(legacy, modern.getElementsByTagName("cloud-backup").item(0) as Element,
            modern.getElementsByTagName("device-transfer").item(0) as Element)
    }

    private fun eligible(section: Element, domain: String, path: String): Boolean {
        fun matches(tag: String): Boolean {
            val rules = section.getElementsByTagName(tag)
            return (0 until rules.length).any { index ->
                val rule = rules.item(index) as Element
                val prefix = rule.getAttribute("path").trimEnd('/')
                rule.getAttribute("domain") == domain &&
                    (prefix == "." || path == prefix || path.startsWith("$prefix/"))
            }
        }
        val included = section.getElementsByTagName("include").length == 0 || matches("include")
        return included && !matches("exclude")
    }
}
