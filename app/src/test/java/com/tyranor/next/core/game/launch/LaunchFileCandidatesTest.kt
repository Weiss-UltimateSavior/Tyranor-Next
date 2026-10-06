package com.tyranor.next.core.game.launch

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「启动文件」候选纯逻辑：KRKR 过滤/排序/当前项优先级，Windows 手动覆盖与自动首选项。
 * SAF 与真实路径两条枚举来源共用本逻辑，因此这里不依赖 Android 运行时。
 */
class LaunchFileCandidatesTest {

    private fun game(
        engine: EngineType = EngineType.KIRIKIRI,
        launchFile: String? = null,
        launchTarget: String = "",
    ): ScanGame = ScanGame(
        title = "Test Game",
        uri = "content://test",
        engine = engine,
        launchTarget = launchTarget,
        launchFile = launchFile,
    )

    private fun entries(vararg names: String): List<LaunchFileCandidates.Entry> =
        names.map { LaunchFileCandidates.Entry(it, 0L) }

    @Test
    fun krkrNamesKeepsXp3BeforeExeAndSkipsOtherFiles() {
        val names = LaunchFileCandidates.krkrNames(
            entries("start.exe", "readme.txt", "bgimage.xp3", "data.xp3"),
        )

        assertEquals(listOf("bgimage.xp3", "data.xp3", "start.exe"), names)
    }

    @Test
    fun krkrNamesSortsIgnoringCase() {
        val names = LaunchFileCandidates.krkrNames(entries("B.xp3", "a.xp3"))

        assertEquals(listOf("a.xp3", "B.xp3"), names)
    }

    @Test
    fun krkrCurrentNamePrefersManualCaseInsensitively() {
        val game = game(launchFile = "DATA.XP3")

        assertEquals(
            "data.xp3",
            LaunchFileCandidates.krkrCurrentName(entries("data.xp3", "start.exe"), game),
        )
    }

    @Test
    fun krkrCurrentNameKeepsSubdirectoryManualName() {
        val game = game(launchFile = "bin/nested.ebk")

        assertEquals(
            "bin/nested.ebk",
            LaunchFileCandidates.krkrCurrentName(entries("data.xp3", "start.exe"), game),
        )
    }

    @Test
    fun krkrCurrentNameDoesNotMapSubdirectoryManualToRootSameName() {
        // 手动指向 bin/game.xp3 时不得预选根目录 game.xp3（确认会改写启动项）
        val game = game(launchFile = "bin/game.xp3")
        val entries = entries("game.xp3", "data.xp3")

        assertEquals("bin/game.xp3", LaunchFileCandidates.krkrCurrentName(entries, game))
    }

    @Test
    fun krkrCurrentNameFollowsPreferredOrder() {
        val entries = entries("aaa.xp3", "data.xp3", "start.exe")

        assertEquals("data.xp3", LaunchFileCandidates.krkrCurrentName(entries, game()))
    }

    @Test
    fun krkrCurrentNameMatchesRawEnumerationOrderForFallback() {
        // 兜底与 pickKrActivateEntry 一致：按目录原始枚举序取首个非 bg xp3，而非展示排序序
        val entries = entries("voice.xp3", "alpha.xp3")

        assertEquals("voice.xp3", LaunchFileCandidates.krkrCurrentName(entries, game()))
    }

    @Test
    fun krkrCurrentNameHonorsPreferredEntriesOutsideDisplayList() {
        // startup.tjs 不在 xp3/exe 展示列表中，但仍是实际启动入口（弹窗据此不预选）
        val entries = entries("startup.tjs", "zzz.xp3")

        assertEquals("startup.tjs", LaunchFileCandidates.krkrCurrentName(entries, game()))
        assertEquals(listOf("zzz.xp3"), LaunchFileCandidates.krkrNames(entries))
    }

    @Test
    fun krkrCurrentNameUsesNonBgLaunchTarget() {
        val game = game(launchTarget = "game.xp3")
        val entries = entries("bgimage.xp3", "game.xp3")

        assertEquals("game.xp3", LaunchFileCandidates.krkrCurrentName(entries, game))
    }

    @Test
    fun krkrCurrentNameIgnoresDirectorySentinelTargets() {
        val legacy = game(launchTarget = LaunchFileCandidates.LEGACY_GAME_DIR_TARGET)
        val dir = game(launchTarget = EngineScanner.LAUNCH_TARGET_GAME_DIR)
        val entries = entries("bgimage.xp3", "zzz.xp3")

        assertEquals("zzz.xp3", LaunchFileCandidates.krkrCurrentName(entries, legacy))
        assertEquals("zzz.xp3", LaunchFileCandidates.krkrCurrentName(entries, dir))
    }

    @Test
    fun krkrCurrentNameRejectsBgLaunchTarget() {
        val game = game(launchTarget = "bgimage.xp3")
        val entries = entries("bgimage.xp3", "aaa.xp3")

        assertEquals("aaa.xp3", LaunchFileCandidates.krkrCurrentName(entries, game))
    }

    @Test
    fun krkrCurrentNameFallsBackToFirstNonBgXp3() {
        val entries = entries("bgimage.xp3", "voice.xp3", "start.exe")

        assertEquals("voice.xp3", LaunchFileCandidates.krkrCurrentName(entries, game()))
    }

    @Test
    fun krkrCurrentNameReturnsNullWithoutUsableEntry() {
        assertNull(LaunchFileCandidates.krkrCurrentName(emptyList(), game()))
        assertNull(LaunchFileCandidates.krkrCurrentName(entries("bgm.xp3", "start.exe"), game()))
    }

    @Test
    fun fileNameOfNormalizesSubdirectoryPaths() {
        assertEquals("nested.exe", LaunchFileCandidates.fileNameOf("bin/nested.exe"))
        assertEquals("nested.exe", LaunchFileCandidates.fileNameOf("bin\\nested.exe"))
        assertEquals("main.exe", LaunchFileCandidates.fileNameOf(" main.exe "))
    }

    @Test
    fun windowsCurrentNameKeepsManualEvenIfExcludedFromAutoCandidates() {
        val candidates = YurisLaunchFiles.candidatesOf(
            listOf(
                YurisLaunchFiles.ExeCandidate("settings.exe", 100),
                YurisLaunchFiles.ExeCandidate("game.exe", 5000),
            ),
            dirName = "Game",
        )

        assertEquals(
            "settings.exe",
            LaunchFileCandidates.windowsCurrentName(candidates, "settings.exe", allowBin = false),
        )
    }

    @Test
    fun windowsCurrentNameMatchesListNameIgnoringCase() {
        val candidates = listOf(YurisLaunchFiles.ExeCandidate("Game.exe", 5000))

        assertEquals(
            "Game.exe",
            LaunchFileCandidates.windowsCurrentName(candidates, "game.EXE", allowBin = false),
        )
    }

    @Test
    fun windowsCurrentNameKeepsSubdirectoryManualPath() {
        // 手动指向 bin/game.exe 时不得回显根目录 game.exe（确认会改写启动项）
        val candidates = listOf(YurisLaunchFiles.ExeCandidate("game.exe", 5000))

        assertEquals(
            "bin/game.exe",
            LaunchFileCandidates.windowsCurrentName(candidates, "bin\\game.exe", allowBin = false),
        )
    }

    @Test
    fun windowsCurrentNameFallsBackToFirstCandidate() {
        val candidates = listOf(
            YurisLaunchFiles.ExeCandidate("game.exe", 5000),
            YurisLaunchFiles.ExeCandidate("tool.exe", 100),
        )

        assertEquals("game.exe", LaunchFileCandidates.windowsCurrentName(candidates, null, allowBin = false))
        assertEquals(
            "game.exe",
            LaunchFileCandidates.windowsCurrentName(candidates, "readme.txt", allowBin = false),
        )
    }

    @Test
    fun windowsCurrentNameSupportsBinOnlyForCatSystem2() {
        val entries = listOf(YurisLaunchFiles.ExeCandidate("game.bin", 900))

        assertNull(
            LaunchFileCandidates.windowsCurrentName(
                YurisLaunchFiles.candidatesOf(entries, dirName = "Game", allowBin = false),
                "game.bin",
                allowBin = false,
            ),
        )
        assertEquals(
            "game.bin",
            LaunchFileCandidates.windowsCurrentName(
                YurisLaunchFiles.candidatesOf(entries, dirName = "Game", allowBin = true),
                "game.bin",
                allowBin = true,
            ),
        )
    }
}
