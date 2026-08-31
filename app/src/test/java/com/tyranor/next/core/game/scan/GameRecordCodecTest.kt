package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 旧 game_scanner 行式格式编解码回归：导入器与 prefs 镜像写回共用同一实现。 */
class GameRecordCodecTest {

    private val game = ScanGame(
        title = "Fate/stay night",
        uri = "/storage/emulated/0/games/fsn",
        engine = EngineType.KIRIKIRI,
        launchTarget = "foo.xp3",
        coverUri = "file:///storage/emulated/0/games/fsn/cover.jpg",
        coverSource = "local",
        vndbId = "v7",
        metadataTitle = "Fate/stay night [Realta Nua]",
        launchFile = "data.xp3",
        openTime = 1725000000000L,
        externalModuleAlias = null,
        detectedRenpyVersion = null,
    )

    @Test
    fun roundTripKeepsAllFields() {
        val text = GameRecordCodec.serialize(listOf(game))
        val parsed = GameRecordCodec.parse(text)
        assertEquals(listOf(game), parsed)
    }

    @Test
    fun titleWithNewlineOrSeparatorIsSanitizedOnSerialize() {
        val dirty = game.copy(metadataTitle = "bad\nmetadata", title = "bad\u0001title")
        val text = GameRecordCodec.serialize(listOf(dirty))
        // 单行序列化：清洗后不应产生第二个解析行或字段错位
        assertEquals(1, text.split("\n").size)
        val parsed = GameRecordCodec.parse(text).single()
        assertEquals("bad title", parsed.title)
        assertEquals("bad metadata", parsed.metadataTitle)
    }

    @Test
    fun legacyFormatToleranceForShortLinesAndUnknownEngine() {
        // 旧版本字段可能不足 12 个：缺失字段按空处理，3 字段以下整行丢弃
        val legacy = listOf(
            "Title\u0001/uri\u0001ONS",
            "T2\u0001/uri2\u0001NOT_AN_ENGINE\u0001target",
            "broken-line",
        ).joinToString("\n")
        val parsed = GameRecordCodec.parse(legacy)
        assertEquals(2, parsed.size)
        assertEquals(EngineType.ONS, parsed[0].engine)
        assertEquals(EngineType.UNKNOWN, parsed[1].engine)
        assertEquals("target", parsed[1].launchTarget)
        assertNull(parsed[0].coverUri)
        assertEquals(0L, parsed[0].openTime)
    }

    @Test
    fun emptyTextParsesToEmptyList() {
        assertEquals(emptyList<ScanGame>(), GameRecordCodec.parse(""))
    }
}
