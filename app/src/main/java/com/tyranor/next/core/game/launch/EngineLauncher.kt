package com.tyranor.next.core.game.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import bridge.KrSafMirror
import com.akira.tyranoemu.remote.ArtemisActivityV1
import com.akira.tyranoemu.remote.ArtemisActivityV2
import com.akira.tyranoemu.remote.ArtemisActivityV3
import com.akira.tyranoemu.remote.Kirikiroid126
import com.akira.tyranoemu.remote.Kirikiroid134
import com.akira.tyranoemu.remote.Kirikiroid139
import com.core.krkrsdl3.Krkrsdl3Activity
import com.core.tyrano.TyranoActivity
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.ExternalEngineLaunchRequest
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.unpack.ArtemisPfsUnpacker
import com.tyranor.next.theme.AppThemeColors
import com.yuri.onscripter.ONScripter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 游戏引擎启动器：根据 [EngineType] 把扫描到的游戏目录交给对应引擎宿主 Activity。
 * 直接集成（非模块化）。引擎均使用 AndroidManifest 中的内部 Activity，
 * intent 契约与 RinneMobile 保持一致。
 */
object EngineLauncher {
    private const val TAG = "EngineLauncher"

    /** 支持的引擎列表（用于引擎页展示）。按名称长度从大到小排列。 */
    val supportedEngines: List<EngineType> = listOf(
        EngineType.KIRIKIRI,
        EngineType.ONS,
        EngineType.TYRANO,
        EngineType.RPG_MV,
        EngineType.RPG_MZ,
        EngineType.VN,
        EngineType.WEB_OTHER,
        EngineType.ARTEMIS,
        EngineType.RENPY,
    ).sortedByDescending { it.displayName.length }

    /** Artemis 补丁确认弹窗的用户选择：
     *  本次 = 仅当次应用；总是 = 记住为全局 auto；不再 = 记住为全局 off。 */
    enum class ArtemisPatchChoice { ONCE, ALWAYS, NEVER }

    /** 尝试启动游戏。返回错误信息；null 表示成功发起。
     *  [patchChoice] 为 Artemis 补丁确认弹窗（见 [needsArtemisPatchConfirm]）的选择结果。 */
    suspend fun launch(context: Context, game: ScanGame, patchChoice: ArtemisPatchChoice? = null): String? {
        val path = resolveGameDirectory(context, game)
        if (path == null) {
            return "无法解析游戏目录（仅支持本地文件路径）"
        }
        ExternalEngineModuleRegistry.moduleForEngine(game.engine)?.let { module ->
            val result = ExternalEngineLauncher.launch(
                context,
                ExternalEngineLaunchRequest(
                    game = game,
                    gameDirectoryPath = path,
                    launchTarget = game.launchTarget,
                ),
            )
            if (result.success) {
                EngineScanner.recordRecentGame(context, game)
                return null
            }
            return result.message ?: "启动 ${module.displayName} 失败"
        }
        requestAllFilesAccessIfNeeded(context, game, path)?.let { return it }
        EnginePluginBootstrap.ensureForLaunch(context, game.engine)?.let { return it }
        val krSafMirror = if (
            game.engine == EngineType.KIRIKIRI && EngineScanner.isRemovableStoragePath(path)
        ) {
            try {
                withContext(Dispatchers.IO) {
                    KrSafMirror.prepare(context.applicationContext, game.uri, path, game.title)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "prepare KRKR SAF mirror failed uri=${game.uri}", t)
                return t.message ?: "无法准备 KRKR SD 卡镜像"
            }
        } else {
            null
        }
        if (game.engine == EngineType.KIRIKIRI) {
            if (krSafMirror != null) {
                val saveDir = File(krSafMirror.mirrorRoot, "savedata")
                if (!saveDir.isDirectory && !saveDir.mkdirs()) return "无法创建 KRKR 镜像存档目录"
            } else {
                ensureKrSaveDir(context, game, path)?.let { return it }
            }
        }
        // “总是/不再”持久化为全局补丁策略；“本次”不落盘，仅本次按 auto 生效
        if (game.engine == EngineType.ARTEMIS) {
            when (patchChoice) {
                ArtemisPatchChoice.ALWAYS ->
                    EngineSettingsStore.setArtAutoPatch(context, EngineSettingsStore.AUTO_PATCH_AUTO)
                ArtemisPatchChoice.NEVER ->
                    EngineSettingsStore.setArtAutoPatch(context, EngineSettingsStore.AUTO_PATCH_OFF)
                else -> Unit
            }
        }
        return try {
            val intent = buildIntent(context, game.engine, path, game, patchChoice, krSafMirror)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            EngineScanner.recordRecentGame(context, game)
            null
        } catch (e: Exception) {
            e.message ?: "启动失败"
        }
    }

    /**
     * Artemis 补丁确认弹窗的触发条件：补丁策略为“启动时询问”（单游戏覆盖 > 全局）
     * 且该游戏确实需要 PFS 基础补丁（缺 system.ini 且存在 .pfs）。
     * UI 层据此弹窗，用户选择经 [launch] 的 [patchChoice] 传入。
     */
    fun needsArtemisPatchConfirm(context: Context, game: ScanGame): Boolean {
        if (game.engine != EngineType.ARTEMIS) return false
        val strategy = PerGameSettingsStore.getStr(context, game.uri, PerGameSettingsStore.F_ART_PATCH)
            ?: EngineSettingsStore.getArtAutoPatch(context)
        if (strategy != EngineSettingsStore.AUTO_PATCH_ASK) return false
        val path = resolveGameDirectory(context, game) ?: return false
        return ArtemisPfsUnpacker.needsBasePatch(path)
    }

    /**
     * Native engines receive a real /storage path, so SAF tree grants are not enough on Android 11+.
     * Match RinneMobile's requirement: ask the user to enable "Manage all files" before launching.
     */
    private fun requestAllFilesAccessIfNeeded(context: Context, game: ScanGame, path: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (Environment.isExternalStorageManager()) return null
        if (game.engine == EngineType.KIRIKIRI && EngineScanner.isRemovableStoragePath(path)) return null
        if (!needsAllFilesAccess(path)) return null

        val app = context.applicationContext
        val packageUri = Uri.parse("package:${app.packageName}")
        val opened = runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.recoverCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess

        return if (opened) {
            "请在系统页面允许“管理所有文件”，返回后再次启动游戏"
        } else {
            "缺少“管理所有文件”权限，无法让原生引擎读取游戏目录"
        }
    }

    private fun needsAllFilesAccess(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == "/sdcard" ||
            normalized.startsWith("/sdcard/") ||
            normalized == "/storage/emulated/0" ||
            normalized.startsWith("/storage/emulated/0/") ||
            EngineScanner.isRemovableStoragePath(normalized)
    }

    /** 构建引擎 Intent；path 为真实文件路径。 */
    private fun buildIntent(
        context: Context,
        engine: EngineType,
        path: String,
        game: ScanGame,
        patchChoice: ArtemisPatchChoice? = null,
        krSafMirror: KrSafMirror.Prepared? = null,
    ): Intent {
        val intent = when (engine) {
            EngineType.KIRIKIRI ->
                buildKirikiriIntent(context, path, game, krSafMirror)

            EngineType.ONS -> {
                var ons = EngineSettingsStore.loadOns(context)
                val o = PerGameSettingsStore.loadOnsOverride(context, game.uri)
                if (o != null) {
                    if (o.has("scopedsavedir")) ons = ons.copy(scopedSaveDir = o.optBoolean("scopedsavedir"))
                    if (o.has("strechfull")) ons = ons.copy(stretchFull = o.optBoolean("strechfull"))
                    if (o.has("ignorecutout")) ons = ons.copy(ignoreCutout = o.optBoolean("ignorecutout"))
                    if (o.has("disablevideo")) ons = ons.copy(disableVideo = o.optBoolean("disablevideo"))
                    if (o.has("sharpness")) ons = ons.copy(sharpness = o.optBoolean("sharpness"))
                    if (o.has("sharpness_value")) ons = ons.copy(sharpnessValue = o.optString("sharpness_value", "2"))
                    if (o.has("encoding")) ons = ons.copy(encoding = EngineSettingsStore.normalizeEncoding(o.optString("encoding")))
                }
                val args = ArrayList<String>()
                args.add("--root")
                args.add(path)
                args.add("--font")
                args.add(if (path.endsWith("/")) "${path}default.ttf" else "$path/default.ttf")
                args.add(if (ons.stretchFull) "--fullscreen2" else "--fullscreen")
                if (ons.disableVideo) args.add("--no-video")
                args.add("--enc:" + EngineSettingsStore.normalizeEncoding(ons.encoding))
                val saveDir = if (ons.scopedSaveDir) {
                    File(context.getExternalFilesDir(null), "save/${File(path).name}")
                } else {
                    File(path, "save")
                }
                if (saveDir.exists() || saveDir.mkdirs()) {
                    args.add("--save-dir")
                    args.add(saveDir.absolutePath)
                }
                if (ons.sharpness) {
                    args.add("--sharpness")
                    args.add(safeSharpnessValue(ons.sharpnessValue))
                }
                Intent(context, ONScripter::class.java).apply {
                    putStringArrayListExtra("gameargs", args)
                    putExtra("gameuri", Uri.fromFile(java.io.File(path)).toString())
                    putExtra("path", path)
                    putExtra("gamePath", path)
                    putExtra("rootUri", game.uri)
                    putExtra("launchTarget", game.launchTarget)
                    putExtra("launchMode", "internal.ons")
                    putExtra("ignorecutout", ons.ignoreCutout)
                }
            }

            EngineType.TYRANO,
            EngineType.RPG_MV,
            EngineType.RPG_MZ,
            EngineType.VN,
            EngineType.WEB_OTHER -> buildWebIntent(context, path, game)

            EngineType.ARTEMIS -> buildArtemisIntent(context, path, game, patchChoice)

            EngineType.RENPY -> error("Ren'Py is handled by external engine launcher")

            EngineType.UNKNOWN -> Intent(context, TyranoActivity::class.java).apply {
                putExtra("path", path)
                putExtra("gamePath", path)
                putExtra("rootUri", game.uri)
                putExtra("launchTarget", game.launchTarget)
                putExtra("type", "Tyrano")
            }
        }
        // 注入 App 统一主题色与深浅色：引擎壳自绘 UI（确认/输入弹窗按钮等）经
        // EngineThemeColors.fromIntent / KrDialogStyle 读取，缺失时回落默认绿，
        // 这里同时写 primaryColor（EngineThemeColors）与 themeColorPrimary（KrDialogStyle）两套 key。
        val dark = AppThemeColors.isDark
        intent.putExtra("darkMode", dark)
        intent.putExtra("primaryColor", AppThemeColors.primaryArgb)
        intent.putExtra("themeColorPrimary", AppThemeColors.primaryArgb)
        intent.putExtra("themeColorOnPrimary", 0xFFFFFFFF.toInt())
        intent.putExtra("themeColorCard", (if (dark) 0xFF1E1F1F else 0xFFFFFFFF).toInt())
        intent.putExtra("themeColorText", (if (dark) 0xFFF0F0F0 else 0xFF14221B).toInt())
        intent.putExtra("themeColorTextMuted", (if (dark) 0xFF9A9A9A else 0xFF82908A).toInt())
        return intent
    }

    /**
     * KRKR 启动：按设置页选择的内核（krkrsdl3 / 吉里吉里2）与引擎版本（auto/1.3.9/1.3.4/1.2.6）
     * 路由到对应引擎宿主，并注入字体、独立存档与渲染/内存偏好。
     */
    private fun buildKirikiriIntent(
        context: Context,
        path: String,
        game: ScanGame,
        safMirror: KrSafMirror.Prepared?,
    ): Intent {
        val gid = game.uri
        fun <T> or(override: T?, global: T): T = override ?: global
        val needsSafFallback = EngineScanner.isRemovableStoragePath(path)
        val kernel = effectiveKrKernel(context, gid, path)
        val engineRoot = safMirror?.mirrorRoot?.absolutePath ?: path
        val launchEntry = pickKrActivateEntry(engineRoot, game)
        if (kernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
            val args = buildKrkrsdl3Args(context, gid, path, launchEntry)
            Log.i(TAG, "krkrsdl3 launch root=$path entry=$launchEntry args=$args")
            // krkrsdl3 内核：gameargs 首项为启动文件绝对路径，后续为 TVP 命令行参数
            return Intent(context, Krkrsdl3Activity::class.java).apply {
                putStringArrayListExtra("gameargs", args)
                putExtra("path", path)
                putExtra("gamePath", launchEntry)
                putExtra("projectRoot", path)
                putExtra("gamedir", path)
                putExtra("rootUri", game.uri)
                putExtra("launchTarget", game.launchTarget)
                putExtra("launchMode", "internal.krkrsdl3")
                putExtra("orientation", 6)
                putExtra("focus", "true")
            }
        }
        val version = or(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ENGINE_VERSION), EngineSettingsStore.getKrEngineVersion(context))
        val activity = when (version) {
            EngineSettingsStore.KR_134 -> Kirikiroid134::class.java
            EngineSettingsStore.KR_126 -> Kirikiroid126::class.java
            else -> Kirikiroid139::class.java
        }
        val scoped = effectiveKrScopedSaveDir(context, gid)
        val actualSaveRoot = safMirror?.let { File(it.mirrorRoot, "savedata") }
            ?: resolveKrSaveDir(context, path, kernel, scoped)
        val effectiveScoped = scoped || safMirror != null
        val defaultFont = PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_DEFAULT_FONT)
            ?: EngineSettingsStore.getKrDefaultFont(context)
        val forceFont = or(PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT), EngineSettingsStore.isKrForceDefaultFont(context))
        return Intent(context, activity).apply {
            // KR2 引擎把 path 视为“启动条目”，gamedir = path 的父目录。
            putExtra("path", launchEntry)
            putExtra("gamePath", launchEntry)
            putExtra("projectRoot", engineRoot)
            putExtra("gamedir", engineRoot)
            putExtra("originalProjectRoot", path)
            putExtra("gameSaveRoot", actualSaveRoot.absolutePath)
            putExtra("rootUri", game.uri)
            putExtra("launchTarget", game.launchTarget)
            putExtra("launchMode", "internal.kirikiroid2")
            putExtra("safFileFallback", needsSafFallback)
            safMirror?.let {
                putExtra("baseDoc", game.uri)
                putExtra("safMirrorRoot", it.mirrorRoot.absolutePath)
                putExtra("safMirrorIndex", it.indexFile.absolutePath)
                putExtra("safMirrorFiles", it.fileCount)
            }
            putExtra("orientation", 6)
            putExtra("scopedSaveDir", effectiveScoped)
            if (effectiveScoped) {
                putExtra("scopedSaveRoot", actualSaveRoot.absolutePath)
            }
            putExtra("focus", "true")
            // 引擎版本
            putExtra("krEngineVersion", when (version) {
                EngineSettingsStore.KR_134 -> "1.3.4"
                EngineSettingsStore.KR_126 -> "1.2.6"
                else -> "1.3.9"
            })
            // 字体偏好
            if (defaultFont.isNotEmpty()) putExtra("default_font", defaultFont)
            if (forceFont) putExtra("force_default_font", true)
            // 渲染/内存偏好 JSON：单游戏覆盖 与 全局 逐键合并
            // 注意：buildKrEnginePrefsJson 遍历的是全局键（kr_renderer 等），
            // 而单游戏覆盖以 PerGameSettingsStore.KR_FIELDS（renderer 等）存储，需做键名映射。
            runCatching {
                val renderKeyMap = EngineSettingsStore.KR_RENDER_PREF_KEYS
                    .zip(PerGameSettingsStore.KR_FIELDS).toMap()
                putExtra("krkr_engine_prefs", EngineSettingsStore.buildKrEnginePrefsJson(context) { globalKey ->
                    renderKeyMap[globalKey]?.let { PerGameSettingsStore.getStr(context, gid, it) }
                })
            }
        }
    }

    private fun buildKrkrsdl3Args(
        context: Context,
        gid: String,
        path: String,
        launchEntry: String,
    ): ArrayList<String> {
        fun <T> or(override: T?, global: T): T = override ?: global
        val args = arrayListOf(launchEntry)
        val renderer = normalizeKrkrsdl3Renderer(
            or(
                PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_RENDERER),
                EngineSettingsStore.getKrRenderer(context),
            ),
        )
        args.add("-render=$renderer")

        val scoped = effectiveKrScopedSaveDir(context, gid)
        val saveDir = resolveKrSaveDir(context, path, EngineSettingsStore.KERNEL_KRKRSDL3, scoped)
        if (saveDir.exists() || saveDir.mkdirs()) {
            args.add("-savedir=${saveDir.absolutePath}")
        }
        return args
    }

    private fun effectiveKrScopedSaveDir(context: Context, gid: String): Boolean =
        PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR)
            ?: EngineSettingsStore.isKrScopedSaveDir(context)

    private fun effectiveKrKernel(context: Context, gid: String, path: String): String {
        val requested = PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ENGINE_KERNEL)
            ?: EngineSettingsStore.getKrKernel(context)
        return if (EngineScanner.isRemovableStoragePath(path) && requested == EngineSettingsStore.KERNEL_KRKRSDL3) {
            EngineSettingsStore.KERNEL_KIRIKIRI2
        } else {
            requested
        }
    }

    private fun resolveKrSaveDir(context: Context, path: String, kernel: String, scoped: Boolean): File {
        if (!scoped) return File(path, "savedata")
        return if (kernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            File(File(baseDir, "save"), EngineScanner.safeSaveName(path))
        } else {
            File(File(File(context.filesDir, "krkr_mirror"), EngineScanner.safeSaveName(path)), "savedata")
        }
    }

    private fun ensureKrSaveDir(context: Context, game: ScanGame, path: String): String? {
        val scoped = effectiveKrScopedSaveDir(context, game.uri)
        val kernel = effectiveKrKernel(context, game.uri, path)
        val saveDir = resolveKrSaveDir(context, path, kernel, scoped)
        if (saveDir.isDirectory) return null
        if (saveDir.exists()) return "KRKR 存档路径已存在但不是目录：${saveDir.absolutePath}"
        if (saveDir.mkdirs() || saveDir.isDirectory) return null
        if (!scoped && ensureKrGameSaveDirViaSaf(context, game, path)) return null
        return if (scoped) {
            "无法创建 KRKR 应用独立存档目录：${saveDir.absolutePath}"
        } else {
            "无法创建 KRKR 存档目录：${saveDir.absolutePath}"
        }
    }

    private fun ensureKrGameSaveDirViaSaf(context: Context, game: ScanGame, path: String): Boolean {
        return try {
            val saveDir = DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(game.uri))
                ?.takeIf { it.isDirectory }
                ?.findFile("savedata")
                ?: DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(game.uri))
                    ?.takeIf { it.isDirectory }
                    ?.createDirectory("savedata")
            if (saveDir?.isDirectory == true) return true
            createSafDirectoryForStoragePath(context, "$path/savedata")
        } catch (_: Throwable) {
            createSafDirectoryForStoragePath(context, "$path/savedata")
        }
    }

    private fun createSafDirectoryForStoragePath(context: Context, storagePath: String): Boolean {
        val normalized = storagePath.replace('\\', '/').trimEnd('/')
        val parsed = parseStoragePath(normalized) ?: return false
        val (volume, relative) = parsed
        val resolver = context.contentResolver
        for (perm in resolver.persistedUriPermissions) {
            val tree = perm.uri ?: continue
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val decodedTreeId = Uri.decode(treeId)
            if (!decodedTreeId.startsWith("$volume:", ignoreCase = true)) continue
            val treeRel = decodedTreeId.substringAfter(':', "")
            if (treeRel.isNotEmpty() && relative != treeRel && !relative.startsWith("$treeRel/")) continue
            var current = DocumentFile.fromTreeUri(context.applicationContext, tree) ?: continue
            val localRel = if (treeRel.isNotEmpty() && relative.startsWith("$treeRel/")) {
                relative.substring(treeRel.length + 1)
            } else {
                relative
            }
            var ok = true
            for (segment in localRel.split('/').filter { it.isNotBlank() }) {
                val next = current.findFile(segment)?.takeIf { it.isDirectory }
                    ?: current.createDirectory(segment)
                if (next == null || !next.isDirectory) {
                    ok = false
                    break
                }
                current = next
            }
            if (ok && current.name.equals("savedata", ignoreCase = true) && current.isDirectory) return true
        }
        return false
    }

    private fun parseStoragePath(path: String): Pair<String, String>? {
        return when {
            path == "/storage/emulated/0" -> "primary" to ""
            path.startsWith("/storage/emulated/0/") -> "primary" to path.substring("/storage/emulated/0/".length)
            path == "/sdcard" -> "primary" to ""
            path.startsWith("/sdcard/") -> "primary" to path.substring("/sdcard/".length)
            path.startsWith("/storage/") -> {
                val rest = path.substring("/storage/".length)
                val slash = rest.indexOf('/')
                if (slash <= 0) null else rest.substring(0, slash) to rest.substring(slash + 1)
            }
            else -> null
        }
    }

    private fun normalizeKrkrsdl3Renderer(value: String): String =
        when (value.trim().lowercase()) {
            EngineSettingsStore.RENDERER_OPENGL, "gl", "gpu" -> EngineSettingsStore.RENDERER_OPENGL
            EngineSettingsStore.RENDERER_SOFTWARE, "sw" -> EngineSettingsStore.RENDERER_SOFTWARE
            else -> EngineSettingsStore.RENDERER_OPENGL
        }

    /**
     * Artemis 启动：按设置页选择的引擎版本路由到 V1/V2/V3，并应用画面反转与补丁策略。
     * 策略为“启动时询问”时由 UI 层先弹窗确认（needsArtemisPatchConfirm）；
     * [patchChoice] 为弹窗选择，本次/总是按 auto、不再按 off 覆盖生效值（持久化在 launch() 完成）。
     */
    private fun buildArtemisIntent(
        context: Context,
        path: String,
        game: ScanGame,
        patchChoice: ArtemisPatchChoice? = null,
    ): Intent {
        val gid = game.uri
        fun <T> or(override: T?, global: T): T = override ?: global
        var version = or(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_VERSION), EngineSettingsStore.getArtEngineVersion(context))
        val rotate = or(PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_ART_ROTATE), EngineSettingsStore.isArtRotateScreen(context))
        var autoPatch = or(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_PATCH), EngineSettingsStore.getArtAutoPatch(context))
        when (patchChoice) {
            ArtemisPatchChoice.ONCE, ArtemisPatchChoice.ALWAYS -> autoPatch = EngineSettingsStore.AUTO_PATCH_AUTO
            ArtemisPatchChoice.NEVER -> autoPatch = EngineSettingsStore.AUTO_PATCH_OFF
            null -> Unit
        }
        applyArtemisBasePatchIfNeeded(path, autoPatch)
        // 自动补丁=off 时禁用自动回退；否则 auto 版本启用兼容回退
        val auto = version == EngineSettingsStore.ART_ENGINE_AUTO &&
            autoPatch != EngineSettingsStore.AUTO_PATCH_OFF
        var stage = 0
        if (auto) {
            // 读取引擎侧记忆的上次可用包名（artemis_engine.<路径hash>），从该版本起启动
            val remembered = context.getSharedPreferences("yukihub_prefs", Context.MODE_PRIVATE)
                .getString("artemis_engine." + Integer.toHexString(path.hashCode()), null)
            version = when (remembered) {
                EngineSettingsStore.ART_ENGINE_V3, "internal.artemis.compat.v2" -> {
                    stage = 2; EngineSettingsStore.ART_ENGINE_V3
                }
                EngineSettingsStore.ART_ENGINE_V2, "internal.artemis.compat" -> {
                    stage = 1; EngineSettingsStore.ART_ENGINE_V2
                }
                EngineSettingsStore.ART_ENGINE_V1, "internal.artemis" -> {
                    stage = 0; EngineSettingsStore.ART_ENGINE_V1
                }
                else -> { stage = 0; EngineSettingsStore.ART_ENGINE_AUTO }
            }
        } else {
            stage = when (version) {
                EngineSettingsStore.ART_ENGINE_V3 -> 2
                EngineSettingsStore.ART_ENGINE_V2 -> 1
                else -> 0
            }
        }
        val (activity, libName) = when (version) {
            EngineSettingsStore.ART_ENGINE_V2 -> ArtemisActivityV2::class.java to "artemis-compatible"
            EngineSettingsStore.ART_ENGINE_V3 -> ArtemisActivityV3::class.java to "artemis-compatible-v2"
            else -> ArtemisActivityV1::class.java to "artemis"
        }
        return Intent(context, activity).apply {
            putExtra("path", path)
            putExtra("gamePath", path)
            putExtra("rootUri", game.uri)
            putExtra("launchTarget", game.launchTarget)
            putExtra("launchMode", "internal.artemis")
            putExtra("orientation", if (rotate) 8 else 6)
            putExtra("scopedSaveDir", false)
            // artemis_loader 按 "lib<engineLibName>.so" 拼路径，需传库名（不带 lib 前缀）
            putExtra("engineLibName", libName)
            putExtra("artemisAutoFallback", auto)
            putExtra("artemisFallbackStage", stage)
        }
    }

    private fun buildWebIntent(context: Context, path: String, game: ScanGame): Intent {
        // Tyrano 与 RPG Maker Web 共用 TyranoActivity，因此沿用同一组 WebView 宿主设置。
        val scoped = PerGameSettingsStore.getBool(context, game.uri, "ty_scoped")
            ?: EngineSettingsStore.isTyranoScopedSaveDir(context)
        val rpgMakerModEnabled = effectiveRpgMakerModEnabled(
            game.engine,
            PerGameSettingsStore.getBool(context, game.uri, PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED),
            EngineSettingsStore.isRpgMakerModEnabled(context),
        )
        val scopedSaveRoot = if (scoped) {
            context.getExternalFilesDir(null)?.let { external ->
                File(File(File(external, "save"), "tyrano"), EngineScanner.safeSaveName(path)).absolutePath
            }
        } else {
            null
        }
        return Intent(context, TyranoActivity::class.java).apply {
            putExtra("path", path)
            putExtra("gamePath", path)
            putExtra("projectRoot", path)
            putExtra("gamedir", path)
            putExtra("rootUri", game.uri)
            putExtra("launchTarget", game.launchTarget)
            val webType = when (game.engine) {
                EngineType.RPG_MV -> "RPG"
                EngineType.RPG_MZ -> "RMMZ"
                EngineType.VN -> "VN"
                EngineType.WEB_OTHER -> "WebOther"
                else -> "Tyrano"
            }
            putExtra("type", webType)
            putExtra("launchMode", "internal.${webType.lowercase()}")
            putExtra("orientation", 6)
            putExtra("scopedSaveDir", scoped)
            scopedSaveRoot?.let { putExtra("scopedSaveRoot", it) }
            putExtra("rpgMakerModEnabled", rpgMakerModEnabled)
            putExtra("rpgMakerModGameId", game.uri)
        }
    }

    /**
     * RinneMobile 的 Artemis 启动链路会在启动前补齐部分 PFS 打包游戏所需的基础文件。
     * “启动时询问”策略已由 UI 层弹窗确认（needsArtemisPatchConfirm），到达这里时
     * ask 已按弹窗结果改写为 auto/off：auto（含 ask 遗留路径）幂等自动补丁，off 跳过。
     */
    private fun applyArtemisBasePatchIfNeeded(path: String, strategy: String) {
        if (strategy == EngineSettingsStore.AUTO_PATCH_OFF) return
        if (!ArtemisPfsUnpacker.needsBasePatch(path)) return
        ArtemisPfsUnpacker.applyBasePatch(path)
    }

    /**
     * 为 KR2 挑选“启动条目”路径（让 gamedir = path 的父目录 = 游戏目录）。优先：launchTarget
     * 指定的 xp3 → 目录内 data.xp3/startup.tjs 等常见启动条目 → 任意一个 xp3 → 目录本身。
     */
    private fun pickKrActivateEntry(path: String, game: ScanGame): String {
        val files = java.io.File(path).listFiles()
            ?.filter { it.isFile }
            .orEmpty()

        // 用户通过“启动文件”手动指定的入口优先（文件不存在时回退自动逻辑）
        game.launchFile?.takeIf { it.isNotBlank() }?.let { manual ->
            val exact = java.io.File(path, manual)
            val f = if (exact.isFile) exact else java.io.File(path, manual.lowercase(Locale.ROOT))
            if (f.isFile) return f.absolutePath
        }

        // 脚本/主启动归档优先（此类 xp3 内含 start.ks / FirstConductor 等启动脚本），
        // 避开 bgimage/bgm/video/voice 等纯素材档。
        val preferred = listOf(
            "data.xp3", "main.xp3", "scn.xp3", "patch.xp3", "scenario.xp3",
            "startup.tjs", "0.ebk",
        )
        preferred.forEach { name ->
            files.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.absolutePath }
        }

        // launchTarget 若存在且非素材档，作为候选用
        val target = game.launchTarget
            .takeIf { !it.isNullOrBlank() && it != "[游戏目录]" && it != "DIR" }
        if (target != null && !target.lowercase().startsWith("bg")) {
            val exact = java.io.File(path, target)
            val f = if (exact.isFile) exact else java.io.File(path, target.lowercase(Locale.ROOT))
            if (f.isFile) return f.absolutePath
        }

        // 兜底：任意非 bg* 的 xp3
        files.firstOrNull {
            it.name.lowercase().endsWith(".xp3") && !it.name.lowercase().startsWith("bg")
        }?.let { return it.absolutePath }

        return path
    }

    /**
     * 列出游戏目录内可作为启动入口的文件（xp3 与 exe），供“启动文件”选择弹窗展示。
     */
    internal fun listKrLaunchFiles(context: Context, game: ScanGame): List<String> {
        val path = resolveGameDirectory(context, game) ?: return emptyList()
        val files = java.io.File(path).listFiles()?.filter { it.isFile }.orEmpty()
        val xp3 = files.filter { it.name.lowercase().endsWith(".xp3") }.sortedBy { it.name.lowercase() }.map { it.name }
        val exe = files.filter { it.name.lowercase().endsWith(".exe") }.sortedBy { it.name.lowercase() }.map { it.name }
        return xp3 + exe
    }

    /**
     * 当前 KRKR 启动入口对应的文件名（仅当入口为目录内文件时返回；入口为目录本身时返回 null）。
     */
    internal fun currentKrLaunchFileName(context: Context, game: ScanGame): String? {
        val path = resolveGameDirectory(context, game) ?: return null
        val entry = pickKrActivateEntry(path, game)
        return java.io.File(entry).takeIf { it.isFile }?.name
    }

    /** 与 OnsSettings.safeSharpness 一致：只接受 0.1~10.0 的数字，否则回退 "2"。 */
    private fun safeSharpnessValue(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return "2"
        val parsed = v.toDoubleOrNull() ?: return "2"
        if (parsed.isNaN() || parsed.isInfinite()) return "2"
        if (parsed < 0.1 || parsed > 10.0) return "2"
        return v
    }

    /**
     * 将游戏 URI 解析为真实文件路径。优先按 SAF documentId 映射（主存储→/storage/emulated/0），
     * 映射失败再用 _data 查询兜底。引擎 native 需要真实文件路径。
     */
    private fun resolveGameDirectory(context: Context, game: ScanGame): String? {
        val uriText = game.uri

        // 1) 首选 SAF documentId → 文件路径映射（兼容 child 子目录 document uri）
        EngineScanner.safUriToPath(uriText)?.let { mapped ->
            val f = java.io.File(mapped)
            if (f.isDirectory) return f.absolutePath
            if (game.engine == EngineType.KIRIKIRI && EngineScanner.isRemovableStoragePath(mapped)) {
                val readableBySaf = runCatching {
                    DocumentFile.fromTreeUri(context.applicationContext, Uri.parse(uriText))?.isDirectory == true
                }.getOrDefault(false)
                if (readableBySaf) return f.absolutePath
            }
        }

        val uri = Uri.parse(uriText) ?: return null
        if (uri.scheme == "file") return uri.path

        // 2) 兜底：尝试 _data 直查
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc == null || !doc.exists()) return null
            val cursor = context.contentResolver.query(uri, arrayOf("_data"), null, null, null)
            if (cursor == null) {
                null
            } else {
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        val dataIdx = c.getColumnIndex("_data")
                        if (dataIdx >= 0) c.getString(dataIdx) else null
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

internal fun effectiveRpgMakerModEnabled(
    engine: EngineType,
    perGameOverride: Boolean?,
    globalDefault: Boolean,
): Boolean = engine in setOf(EngineType.RPG_MV, EngineType.RPG_MZ) &&
    (perGameOverride ?: globalDefault)
