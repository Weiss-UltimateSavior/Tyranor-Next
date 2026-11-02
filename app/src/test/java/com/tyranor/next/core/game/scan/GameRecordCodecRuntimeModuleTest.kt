package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * prefs 行式镜像的运行时模块字段传播：新行追加字段 12，旧 12 字段行保持可解析。
 */
class GameRecordCodecRuntimeModuleTest {

    private val game = ScanGame(
        title = "Runtime Game",
        uri = "/storage/emulated/0/games/runtime",
        engine = EngineType.RUNTIME_MODULE,
        launchTarget = EngineScanner.LAUNCH_TARGET_GAME_DIR,
        runtimeModulePackage = "com.tyranor.runtime.example",
    )

    @Test
    fun roundTripKeepsRuntimeModulePackage() {
        val line = GameRecordCodec.serializeGame(game)
        val parsed = requireNotNull(GameRecordCodec.parseGame(line))

        assertEquals(game, parsed)
        assertEquals("com.tyranor.runtime.example", parsed.runtimeModulePackage)
    }

    @Test
    fun listRoundTripKeepsRuntimeModulePackage() {
        assertEquals(listOf(game), GameRecordCodec.parse(GameRecordCodec.serialize(listOf(game))))
    }

    @Test
    fun legacyTwelveFieldLineParsesWithNullRuntimeModule() {
        val legacy = listOf(
            "Legacy Game",
            "/storage/emulated/0/games/legacy",
            EngineType.KIRIKIRI.name,
            "data.xp3",
            "file:///legacy/cover.jpg",
            "v7",
            "Legacy Metadata",
            "data.xp3",
            "1725000000000",
            "local",
            "",
            "",
        ).joinToString("\u0001")
        assertEquals(12, legacy.split("\u0001").size)

        val parsed = requireNotNull(GameRecordCodec.parseGame(legacy))
        assertEquals(EngineType.KIRIKIRI, parsed.engine)
        assertEquals("Legacy Game", parsed.title)
        assertNull(parsed.runtimeModulePackage)
    }

    @Test
    fun blankRuntimeModulePackageNormalizesToNull() {
        val spaced = game.copy(runtimeModulePackage = "   ")
        val empty = game.copy(runtimeModulePackage = "")

        assertNull(GameRecordCodec.parseGame(GameRecordCodec.serializeGame(spaced))?.runtimeModulePackage)
        assertNull(GameRecordCodec.parseGame(GameRecordCodec.serializeGame(empty))?.runtimeModulePackage)
    }
}
