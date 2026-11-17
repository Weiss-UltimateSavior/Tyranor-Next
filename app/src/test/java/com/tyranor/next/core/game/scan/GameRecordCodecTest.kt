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
        bangumiId = "4154",
        hikarinagiId = "hkn-77",
        nextmoeId = "207379",
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
    fun bindingIdsWithSeparatorOrNewlineAreSanitizedOnSerialize() {
        // BK-58：绑定 id 来自服务端候选、正常不含分隔符；污染输入（含 \u0001 / \n）与标题
        // 同款过 clean() 作行式格式错位防御——不得把整条行式记录拆成多行/多字段错位
        val dirty = game.copy(
            vndbId = "v1\u00017",
            bangumiId = "41\n54",
            hikarinagiId = "hkn\u0001-77",
        )
        val text = GameRecordCodec.serialize(listOf(game, dirty))
        // 两行游戏序列化仍为两行：污染 id 不产生第二个解析行
        assertEquals(2, text.split("\n").size)
        val parsed = GameRecordCodec.parse(text)
        assertEquals(2, parsed.size)
        // 两条记录字段不错位：污染 id 收敛为字段内空格，其余字段原值保留
        assertEquals("v1 7", parsed[1].vndbId)
        assertEquals("41 54", parsed[1].bangumiId)
        assertEquals("hkn -77", parsed[1].hikarinagiId)
        assertEquals(game.uri, parsed[1].uri)
        assertEquals(game.metadataTitle, parsed[1].metadataTitle)
        assertEquals(listOf(game), parsed.subList(0, 1))
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
    fun legacyTwelveFieldLineParsesWithoutBangumiBinding() {
        // 12 字段行（槽位 0–11，止于 detectedRenpyVersion）早于库 schema v3——v3 基线已
        // 13 字段（新增槽位 12 runtimeModulePackage），v4 起才有 bangumiId（槽位 13）、
        // 其后为 hikarinagiId（槽位 14）。尾部缺失的槽位一律按 null 处理，不得错位读取
        val legacy = listOf(
            "T", "/u", "ONS", "t", "", "", "", "", "0", "", "", "",
        ).joinToString("\u0001")
        val parsed = GameRecordCodec.parse(legacy).single()
        assertNull(parsed.runtimeModulePackage)
        assertNull(parsed.bangumiId)
        assertNull(parsed.hikarinagiId)
        assertNull(parsed.metadataTitle)
    }

    @Test
    fun legacyFourteenFieldLineParsesWithoutHikarinagiBinding() {
        // v4 镜像行恰为 14 个字段（槽位 0–13，止于 bangumiId）：
        // 新增槽位 14（hikarinagiId）缺失时按 null 处理，不得错位读取
        val legacy = listOf(
            "T", "/u", "ONS", "t", "", "", "", "", "0", "", "", "", "", "4154",
        ).joinToString("\u0001")
        val parsed = GameRecordCodec.parse(legacy).single()
        assertEquals("4154", parsed.bangumiId)
        assertNull(parsed.hikarinagiId)
    }

    @Test
    fun legacyFifteenFieldLineParsesWithoutNextMoeBinding() {
        val legacy = listOf(
            "T", "/u", "ONS", "t", "", "", "", "", "0", "", "", "", "", "4154", "hkn-77",
        ).joinToString("\u0001")
        val parsed = GameRecordCodec.parse(legacy).single()
        assertEquals("4154", parsed.bangumiId)
        assertEquals("hkn-77", parsed.hikarinagiId)
        assertNull(parsed.nextmoeId)
    }

    @Test
    fun emptyTextParsesToEmptyList() {
        assertEquals(emptyList<ScanGame>(), GameRecordCodec.parse(""))
    }
}
