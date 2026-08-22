package com.tyranor.next.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import com.akira.tyranoemu.remote.ArtemisActivityV1
import com.akira.tyranoemu.remote.ArtemisActivityV2
import com.akira.tyranoemu.remote.ArtemisActivityV3
import com.akira.tyranoemu.remote.Kirikiroid126
import com.akira.tyranoemu.remote.Kirikiroid134
import com.akira.tyranoemu.remote.Kirikiroid139
import com.core.krkrsdl3.Krkrsdl3Activity
import com.core.tyrano.TyranoActivity
import com.tyranor.next.settings.EngineSettingsStore
import com.tyranor.next.settings.PerGameSettingsStore
import com.yuri.onscripter.ONScripter
import java.io.File

/**
 * 游戏引擎启动器：根据 [EngineType] 把扫描到的游戏目录交给对应引擎宿主 Activity。
 * 直接集成（非模块化）。引擎均使用 AndroidManifest 中的内部 Activity，
 * intent 契约与 RinneMobile 保持一致。
 */
object EngineLauncher {

    /** 支持的引擎列表（用于引擎页展示）。 */
    val supportedEngines: List<EngineType> = listOf(
        EngineType.KIRIKIRI,
        EngineType.ONS,
        EngineType.TYRANO,
        EngineType.ARTEMIS,
    )

    /** 尝试启动游戏。返回错误信息；null 表示成功发起。 */
    fun launch(context: Context, game: ScanGame): String? {
        val path = resolveGameDirectory(context, game)
        if (path == null) {
            return "无法解析游戏目录（仅支持本地文件路径）"
        }
        requestAllFilesAccessIfNeeded(context, path)?.let { return it }
        EnginePluginBootstrap.ensureForLaunch(context, game.engine)?.let { return it }
        return try {
            val intent = buildIntent(context, game.engine, path, game)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            EngineScanner.recordRecentGame(context, game)
            null
        } catch (e: Exception) {
            e.message ?: "启动失败"
        }
    }

    /**
     * Native engines receive a real /storage path, so SAF tree grants are not enough on Android 11+.
     * Match RinneMobile's requirement: ask the user to enable "Manage all files" before launching.
     */
    private fun requestAllFilesAccessIfNeeded(context: Context, path: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (Environment.isExternalStorageManager()) return null
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
            normalized.startsWith("/storage/emulated/0/")
    }

    /** 构建引擎 Intent；path 为真实文件路径。 */
    private fun buildIntent(context: Context, engine: EngineType, path: String, game: ScanGame): Intent {
        return when (engine) {
            EngineType.KIRIKIRI ->
                buildKirikiriIntent(context, path, game)

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

            EngineType.TYRANO -> buildTyranoIntent(context, path, game)

            EngineType.ARTEMIS -> buildArtemisIntent(context, path, game)

            EngineType.UNKNOWN -> Intent(context, TyranoActivity::class.java).apply {
                putExtra("path", path)
                putExtra("gamePath", path)
                putExtra("rootUri", game.uri)
                putExtra("launchTarget", game.launchTarget)
                putExtra("type", "Tyrano")
            }
        }
    }

    /**
     * KRKR 启动：按设置页选择的内核（krkrsdl3 / 吉里吉里2）与引擎版本（auto/1.3.9/1.3.4/1.2.6）
     * 路由到对应引擎宿主，并注入字体、独立存档与渲染/内存偏好。
     */
    private fun buildKirikiriIntent(context: Context, path: String, game: ScanGame): Intent {
        val gid = game.uri
        fun <T> or(override: T?, global: T): T = override ?: global
        val kernel = or(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ENGINE_KERNEL), EngineSettingsStore.getKrKernel(context))
        val launchEntry = pickKrActivateEntry(path, game)
        if (kernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
            // krkrsdl3 内核：gameargs 首项为启动文件绝对路径
            return Intent(context, Krkrsdl3Activity::class.java).apply {
                putStringArrayListExtra("gameargs", arrayListOf(launchEntry))
                putExtra("path", launchEntry)
                putExtra("rootUri", game.uri)
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
        val scoped = or(PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR), EngineSettingsStore.isKrScopedSaveDir(context))
        val defaultFont = PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_DEFAULT_FONT)
            ?: EngineSettingsStore.getKrDefaultFont(context)
        val forceFont = or(PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT), EngineSettingsStore.isKrForceDefaultFont(context))
        return Intent(context, activity).apply {
            // KR2 引擎把 path 视为“启动条目”，gamedir = path 的父目录。
            putExtra("path", launchEntry)
            putExtra("gamePath", launchEntry)
            putExtra("projectRoot", path)
            putExtra("gamedir", path)
            putExtra("rootUri", game.uri)
            putExtra("launchTarget", game.launchTarget)
            putExtra("launchMode", "internal.kirikiroid2")
            putExtra("orientation", 6)
            putExtra("scopedSaveDir", scoped)
            // 独立存档：把 scopedSaveRoot 指向与 GameSaveManager 一致的镜像目录，
            // 否则 KR2 引擎会回退到游戏目录内 savedata，存档管理对着空镜像。
            if (scoped) {
                context.filesDir?.let { internal ->
                    putExtra(
                        "scopedSaveRoot",
                        File(File(File(internal, "krkr_mirror"), EngineScanner.safeSaveName(path)), "savedata").absolutePath,
                    )
                }
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

    /**
     * Artemis 启动：按设置页选择的引擎版本路由到 V1/V2/V3，并应用画面反转与补丁策略。
     */
    private fun buildArtemisIntent(context: Context, path: String, game: ScanGame): Intent {
        val gid = game.uri
        fun <T> or(override: T?, global: T): T = override ?: global
        var version = or(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_VERSION), EngineSettingsStore.getArtEngineVersion(context))
        val rotate = or(PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_ART_ROTATE), EngineSettingsStore.isArtRotateScreen(context))
        val autoPatch = or(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_PATCH), EngineSettingsStore.getArtAutoPatch(context))
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

    private fun buildTyranoIntent(context: Context, path: String, game: ScanGame): Intent {
        val scoped = PerGameSettingsStore.getBool(context, game.uri, "ty_scoped")
            ?: EngineSettingsStore.isTyranoScopedSaveDir(context)
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
            putExtra("type", "Tyrano")
            putExtra("launchMode", "internal.tyrano")
            putExtra("orientation", 6)
            putExtra("scopedSaveDir", scoped)
            scopedSaveRoot?.let { putExtra("scopedSaveRoot", it) }
        }
    }

    /**
     * RinneMobile 的 Artemis 启动链路会在启动前补齐部分 PFS 打包游戏所需的基础文件。
     * TyranorNext 目前没有确认弹窗，所以“启动时询问”按幂等自动补丁处理；“关闭”仍跳过。
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
            val f = java.io.File(path, manual)
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
            val f = java.io.File(path, target)
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
