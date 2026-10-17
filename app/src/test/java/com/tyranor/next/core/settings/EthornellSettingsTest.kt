package com.tyranor.next.core.settings

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.EthornellPaths
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.GameRecordCodec
import com.tyranor.next.core.game.storage.GameEntity
import com.tyranor.next.core.game.storage.GameOverridePartitions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class EthornellSettingsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun onlyRealSupportedEncodingsAreExposed() {
        assertEquals(setOf("sjis", "gbk"), EngineSettingsStore.ETHORNELL_NLS_VALUES)
        assertEquals("gbk", EngineSettingsStore.normalizeEthornellNls(" GBK "))
        for (invalid in listOf(null, "utf8", "auto", "big5", "")) {
            assertEquals("sjis", EngineSettingsStore.normalizeEthornellNls(invalid))
        }
        assertEquals("gbk", EffectiveEngineSettings.resolveAllowed("utf8", "gbk", EngineSettingsStore.ETHORNELL_NLS_VALUES, "sjis"))
        assertEquals("sjis", EffectiveEngineSettings.resolveAllowed("sjis", "gbk", EngineSettingsStore.ETHORNELL_NLS_VALUES, "sjis"))
        assertEquals("", EffectiveEngineSettings.resolve("", "custom.ttf"))
        assertEquals("gbk", EffectiveEngineSettings.resolveEthornellNls(" GBK ", "sjis"))
        assertEquals("gbk", EffectiveEngineSettings.resolveEthornellNls("utf8", "GBK"))
        assertEquals("sjis", EffectiveEngineSettings.resolveEthornellNls(null, "unsupported"))
    }

    @Test fun overridesRoundTripThroughExplicitPartition() {
        assertEquals(PerGameSettingsStore.F_ETHORNELL_NLS, GameOverridePartitions.KEY_ETHORNELL_NLS)
        assertEquals(PerGameSettingsStore.F_ETHORNELL_FONT, GameOverridePartitions.KEY_ETHORNELL_FONT)
        val blob = JSONObject().put("ethornell_nls", "gbk").put("ethornell_font", "")
        val row = GameOverridePartitions.split("file:///game", blob, 1)
        val restored = GameOverridePartitions.assemble(row)
        assertTrue(GameOverridePartitions.TYRANO_KEYS.containsAll(listOf("ethornell_nls", "ethornell_font")))
        assertEquals("gbk", restored.getString("ethornell_nls"))
        assertEquals("", restored.getString("ethornell_font"))
    }

    @Test fun enumNamePersistsInRoomAndLegacyCodecWithoutOrdinalMigration() {
        val game = ScanGame("BGI", "file:///game", EngineType.ETHORNELL, "__game_dir__")
        assertEquals("ETHORNELL", GameEntity.from(game, 1, 1).engine)
        assertEquals(game, GameEntity.from(game, 1, 1).toModel())
        assertEquals(game, GameRecordCodec.parse(GameRecordCodec.serialize(listOf(game))).single())
        assertEquals("BGI", EngineType.ETHORNELL.abbr)
    }

    @Test fun userRootsAreStableAndUseFullDigestNotBasenameOrJavaHash() {
        val base = File("/private/app/files")
        val first = EthornellPaths.userDataDir(base, "Aa")
        assertEquals(first, EthornellPaths.userDataDir(base, "Aa"))
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(first, EthornellPaths.userDataDir(base, "BB"))
        assertTrue(first.name.matches(Regex("[0-9a-f]{64}")))
        assertEquals(File(base, "ethornell/userdata"), first.parentFile)
        assertEquals(com.core.ethornell.EthornellPaths.userDataDir(base, "Aa"), first)
        assertEquals(first, EthornellPaths.validateUserDataDir(base, "Aa", first.absolutePath))
    }

    @Test fun customFontPathsMustBelongToThePrivateImportDirectory() {
        val files = temporary.newFolder()
        val fonts = File(files, "fonts").apply { mkdir() }
        val valid = File(fonts, "字体 日本語.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
        assertEquals(valid.canonicalPath, EthornellPaths.validatedFont(files, valid.absolutePath))
        assertEquals("", EthornellPaths.validatedFont(files, ""))
        assertNull(EthornellPaths.validatedFont(files, File(fonts, "missing.ttf").absolutePath))
        val outside = File(files, "outside.ttf").apply { writeText("not imported") }
        assertNull(EthornellPaths.validatedFont(files, outside.absolutePath))
        assertNull(EthornellPaths.validatedFont(files, File(fonts, "../outside.ttf").absolutePath))
        assertNull(EthornellPaths.validatedFont(files, "content://provider/font.ttf"))
        val wrong = File(fonts, "font.exe").apply { writeText("no") }
        assertNull(EthornellPaths.validatedFont(files, wrong.absolutePath))
    }
}
