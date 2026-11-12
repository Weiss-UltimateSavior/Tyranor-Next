package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EffectiveEngineSettings
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.RuffleOverride
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuffleExternalEngineModuleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun buildsFlashGameJsonWithFolderAndExecFile() {
        val folder = temporaryFolder.newFolder("Flash Game")
        folder.resolve("movie.swf").writeText("swf")
        val request = request(folder, "movie.swf")

        val json = JSONObject(RuffleExternalEngineModule.buildGameJson(request))

        assertEquals("测试 Flash", json.getString("title"))
        assertEquals(folder.absolutePath, json.getString("folder"))
        assertEquals("movie.swf", json.getString("execFile"))
        assertEquals("flash", json.getString("type"))
        assertTrue(json.getString("id").isNotBlank())
    }

    @Test
    fun trimsTrailingSlashAndResolvesDirectoryFromFile() {
        val folder = temporaryFolder.newFolder("Trailing")
        val swf = folder.resolve("game.swf")
        swf.writeText("swf")

        val trailing = request(folder.absolutePath + "/", "game.swf")
        assertEquals(folder.absolutePath, JSONObject(RuffleExternalEngineModule.buildGameJson(trailing)).getString("folder"))

        val fileEntry = request(swf.absolutePath, "game.swf")
        assertEquals(folder.absolutePath, JSONObject(RuffleExternalEngineModule.buildGameJson(fileEntry)).getString("folder"))
    }

    @Test
    fun usesDistinctStableGameIdsForTwoSwfsInOneFolder() {
        val folder = temporaryFolder.newFolder("Two Swfs")
        folder.resolve("a.swf").writeText("a")
        folder.resolve("b.swf").writeText("b")

        val idA = JSONObject(RuffleExternalEngineModule.buildGameJson(request(folder, "a.swf"))).getString("id")
        val idB = JSONObject(RuffleExternalEngineModule.buildGameJson(request(folder, "b.swf"))).getString("id")

        assertNotEquals(idA, idB)
        assertTrue(idA.matches(Regex("[0-9a-f]{24}")))
        assertTrue(idB.matches(Regex("[0-9a-f]{24}")))
        assertEquals(
            idA,
            JSONObject(RuffleExternalEngineModule.buildGameJson(request(folder, "a.swf"))).getString("id"),
        )
    }

    @Test
    fun settingsJsonUsesTypedWrappersAndDefaults() {
        val json = JSONObject(RuffleExternalEngineModule.buildSettingsJson())
        val app = json.getJSONObject("app")
        val ruffle = json.getJSONObject("ruffle")

        assertEquals(0, app.getJSONObject("screenTimeout").getInt("int"))
        assertEquals("opengl", ruffle.getJSONObject("backend").getString("string"))
        assertEquals("high", ruffle.getJSONObject("quality").getString("string"))
        assertEquals("showall", ruffle.getJSONObject("scaleMode").getString("string"))
        assertEquals("on", ruffle.getJSONObject("letterbox").getString("string"))
        assertEquals("streaming", ruffle.getJSONObject("loadBehavior").getString("string"))
        assertEquals(5, ruffle.length())
    }

    @Test
    fun settingsJsonPassesResolvedValues() {
        val settings = EngineSettingsStore.Ruffle(
            screenTimeout = 30,
            backend = "vulkan",
            quality = "best",
            scaleMode = "noborder",
            letterbox = "off",
            loadBehavior = "blocking",
        )

        val json = JSONObject(RuffleExternalEngineModule.buildSettingsJson(settings))

        assertEquals(30, json.getJSONObject("app").getJSONObject("screenTimeout").getInt("int"))
        val ruffle = json.getJSONObject("ruffle")
        assertEquals("vulkan", ruffle.getJSONObject("backend").getString("string"))
        assertEquals("best", ruffle.getJSONObject("quality").getString("string"))
        assertEquals("noborder", ruffle.getJSONObject("scaleMode").getString("string"))
        assertEquals("off", ruffle.getJSONObject("letterbox").getString("string"))
        assertEquals("blocking", ruffle.getJSONObject("loadBehavior").getString("string"))
    }

    @Test
    fun mergedRuffleSettingsProduceResolvedPayload() {
        val merged = EffectiveEngineSettings.mergeRuffle(
            EngineSettingsStore.Ruffle(
                screenTimeout = 15,
                backend = "opengl",
                quality = "high",
                scaleMode = "showall",
                letterbox = "on",
                loadBehavior = "streaming",
            ),
            RuffleOverride(
                screenTimeout = "45",
                backend = "vulkan",
                quality = "best",
            ),
        )

        val json = JSONObject(RuffleExternalEngineModule.buildSettingsJson(merged))

        assertEquals(45, json.getJSONObject("app").getJSONObject("screenTimeout").getInt("int"))
        val ruffle = json.getJSONObject("ruffle")
        assertEquals("vulkan", ruffle.getJSONObject("backend").getString("string"))
        assertEquals("best", ruffle.getJSONObject("quality").getString("string"))
        // 未覆盖字段沿用全局
        assertEquals("showall", ruffle.getJSONObject("scaleMode").getString("string"))
        assertEquals("on", ruffle.getJSONObject("letterbox").getString("string"))
        assertEquals("streaming", ruffle.getJSONObject("loadBehavior").getString("string"))
    }

    @Test
    fun resolvesFolderFromSwfPathBeforeFileIsReadable() {
        // 未授予「所有文件访问」时 File.isFile 为 false：仍按 .swf 入口取父目录，让启动链先走权限门禁
        val missing = File(temporaryFolder.root, "No Permission/entry.SWF")
        val request = request(missing.absolutePath, "entry.SWF")

        assertEquals(missing.parentFile!!.absolutePath, RuffleExternalEngineModule.resolveGameFolder(request))
        // 入口文件尚未可读时 resolveExecFile 仍须保持严格校验，不猜测文件名
        assertNull(RuffleExternalEngineModule.resolveExecFile(request))
    }

    @Test
    fun keepsMissingNonSwfPathAsDirectory() {
        val missingDir = File(temporaryFolder.root, "Missing Folder").absolutePath + "/"
        val request = request(missingDir, "")

        assertEquals(
            missingDir.trimEnd('/'),
            RuffleExternalEngineModule.resolveGameFolder(request),
        )
    }

    @Test
    fun prefersLaunchFileOverLaunchTargetThenSingleSwf() {
        val folder = temporaryFolder.newFolder("Resolve")
        folder.resolve("first.swf").writeText("1")
        folder.resolve("second.swf").writeText("2")

        val targeted = request(folder, "second.swf")
        assertEquals("second.swf", RuffleExternalEngineModule.resolveExecFile(targeted))

        val viaLaunchFile = request(folder, "").copy(game = request(folder, "").game.copy(launchFile = "first.swf"))
        assertEquals("first.swf", RuffleExternalEngineModule.resolveExecFile(viaLaunchFile))

        // 手动覆盖（launchFile）优先于扫描写入的 launchTarget
        val both = request(folder, "second.swf")
            .copy(game = request(folder, "second.swf").game.copy(launchFile = "first.swf"))
        assertEquals("first.swf", RuffleExternalEngineModule.resolveExecFile(both))

        // 无任何显式入口且目录内多个 SWF：不猜测
        val ambiguous = request(folder, "")
        assertEquals(null, RuffleExternalEngineModule.resolveExecFile(ambiguous))

        val single = temporaryFolder.newFolder("Single Swf")
        single.resolve("only.swf").writeText("swf")
        assertEquals("only.swf", RuffleExternalEngineModule.resolveExecFile(request(single, "")))
    }

    @Test
    fun explicitMissingSwfDoesNotFallBackToSibling() {
        val folder = temporaryFolder.newFolder("Missing Entry")
        folder.resolve("present.swf").writeText("swf")

        assertNull(RuffleExternalEngineModule.resolveExecFile(request(folder, "missing.swf")))
        assertNull(
            RuffleExternalEngineModule.resolveExecFile(
                request(folder, "").copy(game = request(folder, "").game.copy(launchFile = "missing.swf")),
            ),
        )
    }

    @Test
    fun directoryNamedLikeSwfStaysADirectory() {
        val folder = temporaryFolder.newFolder("x.swf")
        folder.resolve("inside.swf").writeText("swf")

        val request = request(folder.absolutePath, "")
        assertEquals(folder.absolutePath, RuffleExternalEngineModule.resolveGameFolder(request))
        assertEquals("inside.swf", RuffleExternalEngineModule.resolveExecFile(request))
    }

    @Test
    fun blankFolderNameFallsBackToFlashGameTitle() {
        val request = request("/", "").copy(game = request("/", "").game.copy(title = ""))

        assertEquals("Flash Game", JSONObject(RuffleExternalEngineModule.buildGameJson(request)).getString("title"))
    }

    @Test
    fun exposesVerifiedModuleProtocol() {
        assertEquals("ruffle", RuffleExternalEngineModule.id)
        assertEquals(EngineType.RUFFLE, RuffleExternalEngineModule.engine)
        assertEquals("cyou.joiplay.runtime.ruffle", RuffleExternalEngineModule.packageName)
        assertEquals("cyou.joiplay.runtime.flash.run", RuffleExternalEngineModule.action)
        assertEquals("internal.flash", RuffleExternalEngineModule.defaultAlias)
        assertEquals(
            "https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RufflePlugin-1.02.00.apk",
            RuffleExternalEngineModule.installUrl,
        )
        assertTrue(RuffleExternalEngineModule.requiresGameDirectoryPath)
        assertTrue(RuffleExternalEngineModule.launchEntryIsFile)
    }

    private fun request(folder: File, launchTarget: String): ExternalEngineLaunchRequest =
        request(folder.absolutePath, launchTarget)

    private fun request(folderPath: String, launchTarget: String): ExternalEngineLaunchRequest =
        ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "测试 Flash",
                uri = "file://$folderPath",
                engine = EngineType.RUFFLE,
                launchTarget = launchTarget,
            ),
            gameDirectoryPath = folderPath,
        )
}
