package com.tyranor.next.core.game.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import bridge.KrSafMirror
import com.akira.tyranoemu.remote.ArtemisActivityV1
import com.akira.tyranoemu.remote.ArtemisActivityV2
import com.akira.tyranoemu.remote.ArtemisActivityV3
import com.akira.tyranoemu.remote.ArtemisActivityV4
import com.akira.tyranoemu.remote.ArtemisActivityV5
import com.akira.tyranoemu.remote.Kirikiroid126
import com.akira.tyranoemu.remote.Kirikiroid134
import com.akira.tyranoemu.remote.Kirikiroid139
import com.core.engine.KrkrStartupDialogPolicy
import com.core.krkrsdl3.Krkrsdl3Activity
import com.core.tyrano.TyranoActivity
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.ExternalEngineLaunchRequest
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.game.scan.GameDirFingerprint
import com.tyranor.next.core.game.storage.EngineDetectionRepository
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.unpack.ArtemisPfsUnpacker
import com.tyranor.next.theme.AppThemeColors
import com.yuri.onscripter.ONScripter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/**
 * 游戏引擎启动器：根据 [EngineType] 把扫描到的游戏目录交给对应引擎宿主 Activity。
 * 直接集成（非模块化）。引擎均使用 AndroidManifest 中的内部 Activity，
 * intent 契约与 RinneMobile 保持一致。
 */
object EngineLauncher {
    private const val TAG = "EngineLauncher"
    private const val LEGACY_GAME_DIR_TARGET = "\u005B\u6E38\u620F\u76EE\u5F55\u005D"
    private const val KR_LEGACY_PATCH_MARKER = "// TYRANOR_NEXT_KRKR_LEGACY_PATCH_V1"
    private const val KR_FBF_STEAM_STUB_MARKER = "// TYRANOR_NEXT_FBF_STEAM_STUB_V1"
    private const val EXTRA_ARTEMIS_CURRENT_VERSION = "artemisCurrentVersion"
    private const val EXTRA_ARTEMIS_FALLBACK_VERSIONS = "artemisFallbackVersions"
    private const val EXTRA_ARTEMIS_FALLBACK_INDEX = "artemisFallbackIndex"
    private const val EXTRA_ARTEMIS_AUTO_PLAN_REASON = "artemisAutoPlanReason"
    private val ARTEMIS_DEFAULT_FALLBACK_CHAIN = listOf(
        EngineSettingsStore.ART_ENGINE_V2,
        EngineSettingsStore.ART_ENGINE_V1,
        EngineSettingsStore.ART_ENGINE_V3,
        EngineSettingsStore.ART_ENGINE_V4,
        EngineSettingsStore.ART_ENGINE_V5,
    )

    /** 支持的引擎列表（用于引擎页展示）。按名称长度从大到小排列。 */
    val supportedEngines: List<EngineType> = listOf(
        EngineType.KIRIKIRI,
        EngineType.ONS,
        EngineType.TYRANO,
        EngineType.RPGMAKER,
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
     *  [patchChoice] 为 Artemis 补丁确认弹窗（见 [needsArtemisPatchConfirm]）的选择结果。
     *  全链路（SAF 查询/文件扫描/patch 与 Steam overlay 写盘/PFS 解包）均为重 IO，
     *  统一切到 IO 线程执行，避免大游戏目录/慢存储上阻塞调用方主线程导致 ANR。 */
    suspend fun launch(context: Context, game: ScanGame, patchChoice: ArtemisPatchChoice? = null): String? =
        withContext(Dispatchers.IO) { launchInternal(context, game, patchChoice) }

    private suspend fun launchInternal(context: Context, game: ScanGame, patchChoice: ArtemisPatchChoice?): String? {
        val path = resolveGameDirectory(context, game)
        ExternalEngineModuleRegistry.moduleForEngine(game.engine)?.let { defaultModule ->
            val module = ExternalEngineModuleRegistry.resolveModule(
                game.engine,
                if (game.engine == EngineType.RENPY) {
                    PerGameSettingsStore.getStr(context, game.uri, PerGameSettingsStore.F_RENPY_VERSION)
                        ?: EngineSettingsStore.getRenpyVersion(context)
                } else {
                    null
                },
                detectedRenpyVersion = game.detectedRenpyVersion,
            ) ?: defaultModule
            if (module.requiresGameDirectoryPath && path == null) {
                return text(context, R.string.launch_resolve_local_dir_failed)
            }
            // 需要目录解析的外置引擎同样要「所有文件访问」权限才能读取游戏目录（SAF 授权对外置 APK 无效）
            if (module.requiresGameDirectoryPath && path != null) {
                requestAllFilesAccessIfNeeded(context, game, path)?.let { return it }
            }
            // 与内置引擎路径一致：真正拉起外置引擎前确认未取消，避免取消后仍执行启动副作用
            currentCoroutineContext().ensureActive()
            val result = ExternalEngineLauncher.launch(
                context,
                module,
                ExternalEngineLaunchRequest(
                    game = game,
                    gameDirectoryPath = path.orEmpty(),
                    launchTarget = game.launchTarget,
                ),
            )
            if (result.success) {
                currentCoroutineContext().ensureActive()
                EngineScanner.recordRecentGame(context, game)
                return null
            }
            return result.message ?: text(context, R.string.launch_external_module_failed, module.displayName(AppLocaleController.wrap(context)))
        }
        if (path == null) {
            return text(context, R.string.launch_resolve_local_dir_failed)
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
            } catch (ce: CancellationException) {
                throw ce // 取消不是启动失败，原样传播给调用方
            } catch (t: Throwable) {
                Log.e(TAG, "prepare KRKR SAF mirror failed uri=${game.uri}", t)
                return t.message ?: text(context, R.string.launch_prepare_krkr_sd_mirror_failed)
            }
        } else {
            null
        }
        if (game.engine == EngineType.KIRIKIRI) {
            if (krSafMirror != null) {
                val saveDir = File(krSafMirror.mirrorRoot, "savedata")
                if (!saveDir.isDirectory && !saveDir.mkdirs()) return text(context, R.string.launch_create_krkr_mirror_save_failed)
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
        // 阻塞准备（镜像/overlay/PFS）完成后统一检查取消：已取消则不执行任何启动副作用
        currentCoroutineContext().ensureActive()
        return try {
            val intent = buildIntent(context, game.engine, path, game, patchChoice, krSafMirror)
            // Intent 组装后、真正拉起引擎前最后一次确认，取消后不 startActivity
            currentCoroutineContext().ensureActive()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            EngineScanner.recordRecentGame(context, game)
            null
        } catch (ce: CancellationException) {
            throw ce // 取消不是启动失败，原样传播给调用方
        } catch (e: Exception) {
            e.message ?: text(context, R.string.launch_failed)
        }
    }

    /**
     * Artemis 补丁确认弹窗的触发条件：补丁策略为“启动时询问”（单游戏覆盖 > 全局）
     * 且该游戏确实需要 PFS 基础补丁（缺 system.ini 且存在 .pfs）。
     * UI 层据此弹窗，用户选择经 [launch] 的 [patchChoice] 传入。
     * 含 runBlocking 的设置读取与目录枚举，切 IO 执行。
     */
    suspend fun needsArtemisPatchConfirm(context: Context, game: ScanGame): Boolean =
        withContext(Dispatchers.IO) {
            if (game.engine != EngineType.ARTEMIS) return@withContext false
            // 单游戏覆盖值走白名单校验：损坏/历史遗留的非法值回退全局，防止静默改变补丁行为
            val override = PerGameSettingsStore.getStr(context, game.uri, PerGameSettingsStore.F_ART_PATCH)
            val strategy = override?.trim()?.takeIf { it in EngineSettingsStore.ART_PATCHES }
                ?: EngineSettingsStore.getArtAutoPatch(context)
            if (strategy != EngineSettingsStore.AUTO_PATCH_ASK) return@withContext false
            val path = resolveGameDirectory(context, game) ?: return@withContext false
            ArtemisPfsUnpacker.needsBasePatch(path)
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
            text(context, R.string.launch_all_files_access_request)
        } else {
            text(context, R.string.launch_all_files_access_missing)
        }
    }

    /** 判断路径是否位于共享存储（原生引擎无法仅凭 SAF 授权读取），供启动前与手动添加目录共用。 */
    internal fun needsAllFilesAccess(path: String): Boolean {
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
                    val external = context.getExternalFilesDir(null) ?: context.filesDir
                    File(File(external, "save"), File(path).name)
                } else {
                    File(path, "save")
                }
                val saveDirReady = saveDir.isDirectory ||
                    saveDir.mkdirs() ||
                    (!ons.scopedSaveDir && createSafDirectoryForStoragePath(context, saveDir.absolutePath))
                if (!saveDirReady) {
                    Log.w(TAG, "ONS save dir not created before launch, still passing --save-dir=${saveDir.absolutePath}")
                }
                args.add("--save-dir")
                args.add(saveDir.absolutePath)
                Log.i(TAG, "ONS launch scopedSaveDir=${ons.scopedSaveDir} saveDir=${saveDir.absolutePath} ready=$saveDirReady")
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

            EngineType.RPGMAKER,
            EngineType.RENPY -> error("${engine.displayName} is handled by external engine launcher")

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
        val skipStartupDialogs = PerGameSettingsStore.getBool(
            context,
            gid,
            PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS,
        ) ?: EngineSettingsStore.isKrSkipStartupDialogs(context)
        val engineRoot = safMirror?.mirrorRoot?.absolutePath ?: path
        val pickedLaunchEntry = pickKrActivateEntry(engineRoot, game)
        if (kernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
            val args = buildKrkrsdl3Args(context, gid, path, pickedLaunchEntry)
            Log.i(TAG, "krkrsdl3 launch root=$path entry=$pickedLaunchEntry args=$args")
            // krkrsdl3 内核：gameargs 首项为启动文件绝对路径，后续为 TVP 命令行参数
            return Intent(context, Krkrsdl3Activity::class.java).apply {
                putStringArrayListExtra("gameargs", args)
                putExtra("path", path)
                putExtra("gamePath", pickedLaunchEntry)
                putExtra("projectRoot", path)
                putExtra("gamedir", path)
                putExtra("rootUri", game.uri)
                putExtra("launchTarget", game.launchTarget)
                putExtra("launchMode", "internal.krkrsdl3")
                putExtra(KrkrStartupDialogPolicy.EXTRA_ENABLED, skipStartupDialogs)
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
        val launchEntry = pickedLaunchEntry
        val scoped = effectiveKrScopedSaveDir(context, gid)
        val actualSaveRoot = safMirror?.let { File(it.mirrorRoot, "savedata") }
            ?: resolveKrSaveDir(context, path, kernel, scoped)
        val effectiveScoped = scoped || safMirror != null
        val defaultFont = PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_DEFAULT_FONT)
            ?: EngineSettingsStore.getKrDefaultFont(context)
        val forceFont = or(PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT), EngineSettingsStore.isKrForceDefaultFont(context))
        val patchOverlay = prepareKrPatchOverlay(context, gid, engineRoot)
        val steamConfigOverlay = prepareKrSteamConfigOverlay(context, gid, engineRoot)
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
            putExtra(KrkrStartupDialogPolicy.EXTRA_ENABLED, skipStartupDialogs)
            putExtra("safFileFallback", needsSafFallback)
            patchOverlay?.let {
                putExtra("krPatchOverlayTarget", it.targetPatch.absolutePath)
                putExtra("krPatchOverlayPath", it.overlayPatch.absolutePath)
                putExtra("krPatchOverlayMode", it.mode)
            }
            steamConfigOverlay?.let {
                putExtra("krSteamConfigOverlayTarget", it.targetConfig.absolutePath)
                putExtra("krSteamConfigOverlayPath", it.overlayConfig.absolutePath)
            }
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
                check(EngineSettingsStore.KR_RENDER_PREF_KEYS.size == PerGameSettingsStore.KR_FIELDS.size) {
                    "KR pref keys / fields size mismatch"
                }
                val renderKeyMap = EngineSettingsStore.KR_RENDER_PREF_KEYS
                    .zip(PerGameSettingsStore.KR_FIELDS).toMap()
                putExtra("krkr_engine_prefs", EngineSettingsStore.buildKrEnginePrefsJson(context) { globalKey ->
                    renderKeyMap[globalKey]?.let { PerGameSettingsStore.getStr(context, gid, it)?.trim() }
                })
            }.onFailure { android.util.Log.w("EngineLauncher", "build krkr_engine_prefs failed", it) }
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
        if (saveDir.exists()) return text(context, R.string.launch_krkr_save_path_not_dir, saveDir.absolutePath)
        if (saveDir.mkdirs() || saveDir.isDirectory) return null
        if (!scoped && ensureKrGameSaveDirViaSaf(context, game, path)) return null
        return if (scoped) {
            text(context, R.string.launch_create_krkr_scoped_save_failed, saveDir.absolutePath)
        } else {
            text(context, R.string.launch_create_krkr_save_failed, saveDir.absolutePath)
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
     * Artemis 启动：手动版本直达；自动版本按历史成功记录与目录/PFS 指纹生成候选链，
     * 再由 ArtemisLauncherBaseActivity 在早退时跨进程尝试下一候选版本。
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
        fun artString(override: String?, global: String, allowed: Set<String>): String {
            val value = override?.trim()?.takeIf { it in allowed } ?: global.trim()
            return value.takeIf { it in allowed } ?: ""
        }
        // 版本/补丁策略的覆盖值同样走白名单（artString），非法持久化值回退全局
        var version = artString(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_VERSION), EngineSettingsStore.getArtEngineVersion(context), EngineSettingsStore.ART_VERSIONS)
        val rotate = or(PerGameSettingsStore.getBool(context, gid, PerGameSettingsStore.F_ART_ROTATE), EngineSettingsStore.isArtRotateScreen(context))
        var autoPatch = artString(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_PATCH), EngineSettingsStore.getArtAutoPatch(context), EngineSettingsStore.ART_PATCHES)
        val androidSettings = ArtemisPfsUnpacker.AndroidSettings(
            resolution = artString(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_RESOLUTION), EngineSettingsStore.getArtResolution(context), EngineSettingsStore.ART_RESOLUTIONS),
            sideCut = artString(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_SIDE_CUT), EngineSettingsStore.getArtSideCut(context), EngineSettingsStore.ART_TOGGLES),
            surfaceCacheSize = artString(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_SURFACE_CACHE_SIZE), EngineSettingsStore.getArtSurfaceCacheSize(context), EngineSettingsStore.ART_SURFACE_CACHES),
            fontCacheSize = artString(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_FONT_CACHE_SIZE), EngineSettingsStore.getArtFontCacheSize(context), EngineSettingsStore.ART_FONT_CACHES),
            powerSaving = artString(PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_ART_POWER_SAVING), EngineSettingsStore.getArtPowerSaving(context), EngineSettingsStore.ART_TOGGLES),
        )
        when (patchChoice) {
            ArtemisPatchChoice.ONCE, ArtemisPatchChoice.ALWAYS -> autoPatch = EngineSettingsStore.AUTO_PATCH_AUTO
            ArtemisPatchChoice.NEVER -> autoPatch = EngineSettingsStore.AUTO_PATCH_OFF
            null -> Unit
        }
        applyArtemisBasePatchIfNeeded(path, autoPatch)
        ArtemisPfsUnpacker.applyAndroidSettings(path, androidSettings)
        // 自动补丁=off 时禁用自动回退；否则 auto 版本启用兼容回退
        val auto = version == EngineSettingsStore.ART_ENGINE_AUTO &&
            autoPatch != EngineSettingsStore.AUTO_PATCH_OFF
        var fallbackVersions = listOf(version)
        var planReason = "manual"
        var stage = 0
        if (auto) {
            // 自动识别缓存（迁移方案阶段 5）：优先命中 DB 记忆（含引擎子进程经 prefs 写回的
            // 成功版本，consume-and-clear 归一）；指纹变化即失效，重走特征识别。
            val pathHash = Integer.toHexString(path.hashCode())
            val fingerprint = GameDirFingerprint.compute(path)
            val remembered = EngineDetectionRepository.lookupArtemisBlocking(context, game.uri, pathHash, fingerprint)
            if (remembered != null) {
                version = remembered
                fallbackVersions = fallbackChainStartingWith(remembered)
                planReason = "history"
                Log.i(TAG, "Artemis auto history hit path=$path version=$version chain=${fallbackVersions.joinToString(",")} fingerprint=$fingerprint")
            } else {
                val plan = ArtemisEngineFingerprintDetector.buildAutoPlan(path)
                version = plan.initialVersion
                fallbackVersions = plan.fallbackVersions
                planReason = plan.reason
                Log.i(TAG, "Artemis auto fingerprint selected path=$path version=$version chain=${fallbackVersions.joinToString(",")} reason=$planReason")
            }
            stage = artemisFallbackStage(version)
        } else {
            stage = artemisFallbackStage(version)
        }
        val (activity, libName) = artemisActivityAndLib(version)
        val fallbackIndex = fallbackVersions.indexOf(version).coerceAtLeast(0)
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
            putExtra(EXTRA_ARTEMIS_CURRENT_VERSION, version)
            putExtra(EXTRA_ARTEMIS_FALLBACK_VERSIONS, fallbackVersions.joinToString(","))
            putExtra(EXTRA_ARTEMIS_FALLBACK_INDEX, fallbackIndex)
            putExtra(EXTRA_ARTEMIS_AUTO_PLAN_REASON, planReason)
        }
    }

    private fun fallbackChainStartingWith(version: String): List<String> =
        (listOf(version) + ARTEMIS_DEFAULT_FALLBACK_CHAIN.filterNot { it == version }).distinct()

    private fun artemisFallbackStage(version: String): Int =
        when (version) {
            EngineSettingsStore.ART_ENGINE_V2 -> 1
            EngineSettingsStore.ART_ENGINE_V3 -> 2
            EngineSettingsStore.ART_ENGINE_V4 -> 3
            EngineSettingsStore.ART_ENGINE_V5 -> 4
            else -> 0
        }

    private fun artemisActivityAndLib(version: String): Pair<Class<*>, String> =
        when (version) {
            EngineSettingsStore.ART_ENGINE_V2 -> ArtemisActivityV2::class.java to "artemis-compatible"
            EngineSettingsStore.ART_ENGINE_V3 -> ArtemisActivityV3::class.java to "artemis-compatible-v2"
            EngineSettingsStore.ART_ENGINE_V4 -> ArtemisActivityV4::class.java to "artemis-v4"
            EngineSettingsStore.ART_ENGINE_V5 -> ArtemisActivityV5::class.java to "artemis-v5"
            else -> ArtemisActivityV1::class.java to "artemis"
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
            .takeIf {
                !it.isNullOrBlank() &&
                    it != LEGACY_GAME_DIR_TARGET &&
                    !it.equals(EngineScanner.LAUNCH_TARGET_GAME_DIR, ignoreCase = true)
            }
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

    private data class KrPatchCleanupResult(
        val patchFile: File,
        val bytes: ByteArray,
        val hadUserContent: Boolean,
        val cleanedManagedBlock: Boolean,
        val hadManagedMarker: Boolean,
    )

    private data class KrPatchOverlay(
        val targetPatch: File,
        val overlayPatch: File,
        val mode: String,
    )

    private data class KrSteamConfigOverlay(
        val targetConfig: File,
        val overlayConfig: File,
    )

    /**
     * 生成 KRKR 虚拟 patch.tjs overlay。
     *
     * 不再直接向用户游戏目录写入兼容脚本；只在 app 私有目录生成合成 patch.tjs，
     * 再由 KRKR 文件 hook 在读取游戏 patch.tjs 时做只读重定向。
     */
    private fun prepareKrPatchOverlay(context: Context, gid: String, engineRoot: String): KrPatchOverlay? {
        val root = File(engineRoot)
        if (!root.isDirectory || engineRoot.startsWith("content://")) return null
        val mode = EngineSettingsStore.normalizeKrPatchOverlayMode(
            PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_PATCH_OVERLAY_MODE)
                ?: EngineSettingsStore.getKrPatchOverlayMode(context),
        )
        val cleanup = cleanupTyranorManagedKrPatchScript(root)
        if (mode == EngineSettingsStore.KR_PATCH_OVERLAY_OFF) return null

        val hasSteamPlugin = rootContainsFbfSteamPlugin(root)
        val force = mode == EngineSettingsStore.KR_PATCH_OVERLAY_FORCE
        val includeBasicPatch = force || cleanup?.hadUserContent != true
        val includeSteamStub = hasSteamPlugin && (force || mode == EngineSettingsStore.KR_PATCH_OVERLAY_AUTO)
        if (!includeBasicPatch && !includeSteamStub) {
            Log.i(TAG, "KRKR patch overlay skipped root=$engineRoot mode=$mode userPatch=true steam=false")
            return null
        }

        val additionsText = buildString {
            if (includeBasicPatch) append(krBasicPatchOverlayScript())
            if (includeSteamStub) append(krFbfSteamStubScript())
        }
        val baseBytes = if (force || includeSteamStub) cleanup?.bytes ?: ByteArray(0) else ByteArray(0)
        val patchCharset = detectKrPatchCharset(baseBytes)
        val additions = additionsText.toByteArray(patchCharset)
        if (additions.isEmpty()) return null

        val output = ByteArrayOutputStream(baseBytes.size + additions.size + 4).apply {
            if (baseBytes.isNotEmpty()) {
                write(baseBytes)
                write("\n".toByteArray(patchCharset))
            }
            write(additions)
        }.toByteArray()

        return runCatching {
            val overlayDir = File(File(context.filesDir, "krkr_patch_overlay"), EngineScanner.safeSaveName(engineRoot))
            if (!overlayDir.isDirectory && !overlayDir.mkdirs()) {
                Log.w(TAG, "KRKR patch overlay directory unavailable root=$engineRoot dir=${overlayDir.absolutePath}")
                return null
            }
            val overlay = File(overlayDir, "patch.tjs")
            overlay.writeBytes(output)
            val target = File(root, "patch.tjs")
            Log.i(
                TAG,
                "KRKR patch overlay prepared root=$engineRoot mode=$mode basic=$includeBasicPatch steam=$includeSteamStub userPatch=${cleanup?.hadUserContent == true} bytes=${output.size}",
            )
            KrPatchOverlay(target, overlay, mode)
        }.onFailure { error ->
            Log.w(TAG, "KRKR patch overlay prepare failed root=$engineRoot", error)
        }.getOrNull()
    }

    /**
     * 部分 Windows Steam 版 KRKR 移植包会带 ds.ini，并用 Language=xxx 决定 UI 语言。
     * 不直接修改用户游戏目录，只在 app 私有目录生成 schinese 版本并由 NativeBridge 只读映射。
     */
    private fun prepareKrSteamConfigOverlay(context: Context, gid: String, engineRoot: String): KrSteamConfigOverlay? {
        val root = File(engineRoot)
        if (!root.isDirectory || engineRoot.startsWith("content://") || !rootContainsFbfSteamPlugin(root)) return null
        val mode = EngineSettingsStore.normalizeKrPatchOverlayMode(
            PerGameSettingsStore.getStr(context, gid, PerGameSettingsStore.F_PATCH_OVERLAY_MODE)
                ?: EngineSettingsStore.getKrPatchOverlayMode(context),
        )
        if (mode == EngineSettingsStore.KR_PATCH_OVERLAY_OFF) return null

        val config = File(root, "ds.ini")
        if (!config.isFile) return null
        return runCatching {
            val bytes = config.readBytes()
            val charset = detectKrPatchCharset(bytes)
            val text = bytes.toString(charset)
            val languageRegex = Regex("(?im)^(\\s*Language\\s*=\\s*)[^\\r\\n]*")
            val patched = if (languageRegex.containsMatchIn(text)) {
                languageRegex.replace(text) { match ->
                    "${match.groupValues[1]}schinese"
                }
            } else {
                text.trimEnd() + "\nLanguage=schinese\n"
            }
            if (patched == text) return null
            val overlayDir = File(File(context.filesDir, "krkr_config_overlay"), EngineScanner.safeSaveName(engineRoot))
            if (!overlayDir.isDirectory && !overlayDir.mkdirs()) {
                Log.w(TAG, "KRKR Steam config overlay directory unavailable root=$engineRoot dir=${overlayDir.absolutePath}")
                return null
            }
            val overlay = File(overlayDir, "ds.ini")
            overlay.writeText(patched, charset)
            Log.i(
                TAG,
                "KRKR Steam config overlay prepared root=$engineRoot target=${config.absolutePath} overlay=${overlay.absolutePath}",
            )
            KrSteamConfigOverlay(config, overlay)
        }.onFailure { error ->
            Log.w(TAG, "KRKR Steam config overlay prepare failed root=${root.absolutePath}", error)
        }.getOrNull()
    }

    /**
     * PR 44b6be2 曾在启动时向游戏目录 patch.tjs 自动追加 Tyranor Next 兼容脚本。
     * 这里只清理历史版本写入的精确托管块，并返回清理后的原始 patch 内容供 overlay 合成。
     */
    private fun cleanupTyranorManagedKrPatchScript(root: File): KrPatchCleanupResult? {
        val patch = File(root, "patch.tjs")
        if (!patch.isFile) return null
        return runCatching {
            val original = patch.readBytes()
            val blocks = listOf(
                krLegacyPatchScript(fontScale = 1.0f),
                krFbfSteamStubScript(),
            )
            var cleaned = original
            blocks.forEach { block ->
                cleaned = removeAllByteSequences(cleaned, block.toByteArray(Charsets.UTF_8))
                cleaned = removeAllByteSequences(cleaned, block.toByteArray(Charsets.UTF_16LE))
            }
            if (!cleaned.contentEquals(original)) {
                patch.writeBytes(cleaned)
                Log.i(TAG, "KRKR managed patch.tjs block cleaned root=${root.absolutePath} bytes=${original.size - cleaned.size}")
            } else if (
                original.containsByteSequence(KR_LEGACY_PATCH_MARKER.toByteArray(Charsets.UTF_8)) ||
                original.containsByteSequence(KR_FBF_STEAM_STUB_MARKER.toByteArray(Charsets.UTF_8)) ||
                original.containsByteSequence(KR_LEGACY_PATCH_MARKER.toByteArray(Charsets.UTF_16LE)) ||
                original.containsByteSequence(KR_FBF_STEAM_STUB_MARKER.toByteArray(Charsets.UTF_16LE))
            ) {
                Log.w(TAG, "KRKR managed patch.tjs marker found but exact block did not match; left untouched root=${root.absolutePath}")
            }
            KrPatchCleanupResult(
                patchFile = patch,
                bytes = cleaned,
                hadUserContent = hasMeaningfulPatchContent(cleaned),
                cleanedManagedBlock = !cleaned.contentEquals(original),
                hadManagedMarker = original.containsByteSequence(KR_LEGACY_PATCH_MARKER.toByteArray(Charsets.UTF_8)) ||
                    original.containsByteSequence(KR_FBF_STEAM_STUB_MARKER.toByteArray(Charsets.UTF_8)) ||
                    original.containsByteSequence(KR_LEGACY_PATCH_MARKER.toByteArray(Charsets.UTF_16LE)) ||
                    original.containsByteSequence(KR_FBF_STEAM_STUB_MARKER.toByteArray(Charsets.UTF_16LE)),
            )
        }.onFailure { error ->
            Log.w(TAG, "KRKR managed patch.tjs cleanup failed root=${root.absolutePath}", error)
        }.getOrNull()
    }

    private fun rootContainsFbfSteamPlugin(root: File): Boolean {
        val files = root.listFiles() ?: return false
        return files.any { file ->
            file.isFile && file.name.equals("FBFSteamPlugin.dll", ignoreCase = true)
        }
    }

    private fun krBasicPatchOverlayScript(): String = """
        |
        |
        |// TYRANOR_NEXT_KRKR_PATCH_OVERLAY_V1
        |System.setArgument("-debugwin","no");
        |Plugins.link("kirikiroid2.dll");
        |
    """.trimMargin()

    private fun krLegacyPatchScript(fontScale: Float): String = """
        |
        |
        |$KR_LEGACY_PATCH_MARKER
        |System.setArgument("-debugwin","no");
        |Plugins.link("kirikiroid2.dll");
        |with(Font) {
        |global._origFontHeightProp = &.height;
        |property hook_font_height {
        |setter(v) { global._origFontHeightProp = v * $fontScale; }
        |getter { return global._origFontHeightProp; }
        |}
        |&.height = &(hook_font_height incontextof null);
        |}
        |
    """.trimMargin()

    private fun hasMeaningfulPatchContent(bytes: ByteArray): Boolean =
        bytes.any { byte ->
            val value = byte.toInt() and 0xFF
            value > 0x20 && value != 0xFE && value != 0xFF
        }

    private fun detectKrPatchCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return Charsets.UTF_16LE
            if (b0 == 0xFE && b1 == 0xFF) return Charsets.UTF_16BE
        }
        val sampleSize = bytes.size.coerceAtMost(512)
        if (sampleSize >= 16) {
            var evenZeros = 0
            var oddZeros = 0
            for (i in 0 until sampleSize) {
                if (bytes[i].toInt() == 0) {
                    if (i % 2 == 0) evenZeros++ else oddZeros++
                }
            }
            if (oddZeros > sampleSize / 4 && oddZeros > evenZeros * 2) return Charsets.UTF_16LE
            if (evenZeros > sampleSize / 4 && evenZeros > oddZeros * 2) return Charsets.UTF_16BE
        }
        return Charsets.UTF_8
    }

    private fun krFbfSteamStubScript(): String = """
        |
        |
        |$KR_FBF_STEAM_STUB_MARKER
        |class CFBFSteam {
        |function CFBFSteam() {}
        |function finalize() {}
        |function Init() { return true; }
        |function Shutdown() { return true; }
        |function RestartAppIfNecessary(appId) { return false; }
        |function IsSteamRunning() { return true; }
        |function IsSubscribed() { return true; }
        |function IsSubscribedApp(appId) { return true; }
        |function IsDLCInstalled(appId) { return true; }
        |function GetUserLanguage() { return "schinese"; }
        |function GetCurrentGameLanguage() { return "schinese"; }
        |function GetAvailableGameLanguages() { return "schinese,english,japanese"; }
        |function GetPersonaName() { return "Tyranor"; }
        |function GetAppID() { return 0; }
        |function GetSteamID() { return "0"; }
        |function SetAchievement(name) { return true; }
        |function ClearAchievement(name) { return true; }
        |function GetAchievement(name) { return false; }
        |function IndicateAchievementProgress(name, current, max) { return true; }
        |function StoreStats() { return true; }
        |function ResetAllStats(achievementsToo) { return true; }
        |function SetStat(name, value) { return true; }
        |function GetStat(name) { return 0; }
        |function IsOverlayEnabled() { return false; }
        |function ActivateGameOverlay(dialog) { return false; }
        |function ActivateGameOverlayToWebPage(url) { return false; }
        |}
        |global.FBFSteam = new CFBFSteam();
        |
    """.trimMargin()

    private fun removeAllByteSequences(source: ByteArray, needle: ByteArray): ByteArray {
        if (source.isEmpty() || needle.isEmpty()) return source
        var index = source.indexOfByteSequence(needle, startIndex = 0)
        if (index < 0) return source
        val out = ByteArrayOutputStream(source.size)
        var cursor = 0
        while (index >= 0) {
            out.write(source, cursor, index - cursor)
            cursor = index + needle.size
            index = source.indexOfByteSequence(needle, startIndex = cursor)
        }
        out.write(source, cursor, source.size - cursor)
        return out.toByteArray()
    }

    private fun ByteArray.containsByteSequence(needle: ByteArray): Boolean =
        indexOfByteSequence(needle, startIndex = 0) >= 0

    private fun ByteArray.indexOfByteSequence(needle: ByteArray, startIndex: Int): Int {
        if (needle.isEmpty()) return startIndex.coerceIn(0, size)
        val max = size - needle.size
        var i = startIndex.coerceAtLeast(0)
        while (i <= max) {
            var j = 0
            while (j < needle.size && this[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
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

    private fun text(context: Context, @StringRes id: Int, vararg args: Any): String =
        AppLocaleController.wrap(context).getString(id, *args)
}

internal fun effectiveRpgMakerModEnabled(
    engine: EngineType,
    perGameOverride: Boolean?,
    globalDefault: Boolean,
): Boolean = engine in setOf(EngineType.RPG_MV, EngineType.RPG_MZ) &&
    (perGameOverride ?: globalDefault)
