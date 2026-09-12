package com.tyranor.next.core.game.launch

import android.app.ActivityManager
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
import com.akira.tyranoemu.remote.ArtemisActivityV4
import com.akira.tyranoemu.remote.ArtemisActivityV5
import com.akira.tyranoemu.remote.Kirikiroid126
import com.akira.tyranoemu.remote.Kirikiroid134
import com.akira.tyranoemu.remote.Kirikiroid139
import com.core.engine.EngineSessionRegistry
import com.core.engine.KrkrStartupDialogPolicy
import com.core.engine.LaunchContract
import com.core.krkrsdl3.Krkrsdl3Activity
import com.core.rpgmaker.RpgMakerActivity
import com.core.tyrano.TyranoActivity
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.ExternalEngineLaunchRequest
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.engine.plugin.EnginePluginBootstrap
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.RpgSaveFormatConverter
import com.tyranor.next.core.game.save.RpgSavePendingStore
import com.tyranor.next.core.game.save.RpgSaveSync
import com.tyranor.next.core.game.save.RpgSaveSyncState
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.EngineDetectionRepository
import com.tyranor.next.core.game.scan.GameDirFingerprint
import com.tyranor.next.core.settings.EffectiveEngineSettings
import com.tyranor.next.core.settings.EngineSettingsResolver
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.settings.ResolvedEngineSettings
import com.tyranor.next.core.theme.ThemeColorPayload
import com.tyranor.next.core.theme.ThemeColorPayloadStore
import com.tyranor.next.core.unpack.ArtemisPfsUnpacker
import com.yuri.onscripter.ONScripter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

    // 前台兜底回写：引擎 finish 后约 500ms 才杀进程，轮询等待其退出的间隔与上限
    private const val SESSION_EXIT_POLL_MS = 400L
    private const val SESSION_EXIT_MAX_ATTEMPTS = 8
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

    /** 尝试启动游戏。返回类型化结果（P0-6）；[LaunchResult.Success] 表示成功发起。
     *  [patchChoice] 为 Artemis 补丁确认弹窗（见 [needsArtemisPatchConfirm]）的选择结果。
     *  全链路（SAF 查询/文件扫描/patch 与 Steam overlay 写盘/PFS 解包）均为重 IO，
     *  统一切到 IO 线程执行，避免大游戏目录/慢存储上阻塞调用方主线程导致 ANR。 */
    suspend fun launch(context: Context, game: ScanGame, patchChoice: ArtemisPatchChoice? = null): LaunchResult =
        withContext(Dispatchers.IO) { launchInternal(context, game, patchChoice) }

    private suspend fun launchInternal(context: Context, game: ScanGame, patchChoice: ArtemisPatchChoice?): LaunchResult {
        val path = resolveGameDirectory(context, game)
        // 三级设置（应用级 + 单游戏覆盖）一次性解析，后续 Intent 组装只消费生效值（P0-3）
        val settings = EngineSettingsResolver.resolve(context, game, path)
        ExternalEngineModuleRegistry.moduleForEngine(game.engine)?.let { defaultModule ->
            val module = ExternalEngineModuleRegistry.resolveModule(
                game.engine,
                if (game.engine == EngineType.RENPY) settings.renpyVersion else null,
                detectedRenpyVersion = game.detectedRenpyVersion,
            ) ?: defaultModule
            if (module.requiresGameDirectoryPath && path == null) {
                return LaunchResult.Failure.GameDirUnresolved
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
                    resolvedSettings = settings,
                ),
            )
            if (result.success) {
                currentCoroutineContext().ensureActive()
                GameLibraryFacade.recordRecentGame(context, game)
                return LaunchResult.Success
            }
            return LaunchResult.Failure.ExternalModuleFailed(result)
        }
        if (path == null) {
            return LaunchResult.Failure.GameDirUnresolved
        }
        requestAllFilesAccessIfNeeded(context, game, path)?.let { return it }
        EnginePluginBootstrap.ensureForLaunch(context, game.engine)?.let {
            return LaunchResult.Failure.PluginBootstrapFailed(it)
        }
        // MV/MZ 存档互通开启时：登记待回写，并在同一游戏的引擎会话未运行时先同步一次
        // （补上次回写 + 本次导入）。登记与同步结果无关——即使本次同步失败/推迟，退出后仍要回写。
        if (isRpgSaveInteropEnabled(context, game)) {
            recordPendingSaveSync(context, game.uri)
            try {
                // 同一游戏的旧会话仍在写入 savedata 时绝不复制（进程内锁约束不了独立引擎进程），
                // 半写入文件会被当成有效存档。此时保留 pending，交给前台兜底在会话退出后同步。
                if (isEngineSessionRunning(context, game, path)) {
                    Log.i(TAG, "rpg save interop sync deferred (session running) uri=${game.uri}")
                } else {
                    val result = syncRpgSavesForDirectory(context, game.uri, File(path), game.engine)
                    Log.i(
                        TAG,
                        "rpg save interop sync uri=${game.uri} imported=${result.imported} exported=${result.exported} " +
                            "toTyranor=${result.toTyranor} toStandard=${result.toStandard} deleted=${result.movedToDeleted} failed=${result.failed}",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "rpg save interop sync failed uri=${game.uri}", t)
            }
        }
        val krSafMirror = if (
            game.engine == EngineType.KIRIKIRI && GamePathUtils.isRemovableStoragePath(path)
        ) {
            try {
                withContext(Dispatchers.IO) {
                    KrSafMirror.prepare(context.applicationContext, game.uri, path, game.title)
                }
            } catch (ce: CancellationException) {
                throw ce // 取消不是启动失败，原样传播给调用方
            } catch (t: Throwable) {
                Log.e(TAG, "prepare KRKR SAF mirror failed uri=${game.uri}", t)
                return LaunchResult.Failure.KrkrMirrorPrepareFailed(t.message)
            }
        } else {
            null
        }
        if (game.engine == EngineType.KIRIKIRI) {
            if (krSafMirror != null) {
                val saveDir = File(krSafMirror.mirrorRoot, "savedata")
                if (!saveDir.isDirectory && !saveDir.mkdirs()) {
                    return LaunchResult.Failure.KrkrMirrorSaveDirFailed(saveDir.absolutePath)
                }
            } else {
                ensureKrSaveDir(context, game, path, settings)?.let { return it }
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
            val intent = buildIntent(context, game.engine, path, game, patchChoice, krSafMirror, settings)
            // Intent 组装后、真正拉起引擎前最后一次确认，取消后不 startActivity
            currentCoroutineContext().ensureActive()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            GameLibraryFacade.recordRecentGame(context, game)
            LaunchResult.Success
        } catch (ce: CancellationException) {
            throw ce // 取消不是启动失败，原样传播给调用方
        } catch (e: Exception) {
            LaunchResult.Failure.StartFailed(e.message)
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
            val strategy = EngineSettingsResolver.resolve(context, game, null).artAutoPatch
            if (strategy != EngineSettingsStore.AUTO_PATCH_ASK) return@withContext false
            val path = resolveGameDirectory(context, game) ?: return@withContext false
            ArtemisPfsUnpacker.needsBasePatch(path)
        }

    /**
     * RPG Maker MV/MZ 存档格式确认弹窗的触发条件：存档目录（`<游戏根>/savedata`）内存在
     * 标准格式存档（`global.rpgsave` / `fileN.rpgsave` / MZ `.rmmzsave` 及 MV 的 `.bak`）时，
     * 返回检测结果供弹窗展示；无待转化项返回 null。UI 层据此弹窗，用户选择经
     * [convertRpgSaveFormat] 执行。含目录枚举的 IO，切 IO 执行。
     */
    suspend fun rpgSaveFormatPending(context: Context, game: ScanGame): RpgSaveFormat.Detection? =
        withContext(Dispatchers.IO) {
            val gameDir = effectiveRpgGameDirectory(context, game) ?: return@withContext null
            RpgSaveFormat.detect(gameDir, game.engine).takeIf { it.convertibleCount > 0 }
        }

    /**
     * 执行 MV/MZ 存档格式转化（标准 → Tyranor），输出到引擎存档目录。返回转化计数供 UI 提示；
     * 存档目录不可用等异常向上抛出由调用方处理。
     */
    suspend fun convertRpgSaveFormat(context: Context, game: ScanGame): RpgSaveFormat.ConvertResult =
        withContext(Dispatchers.IO) {
            val gameDir = effectiveRpgGameDirectory(context, game)
                ?: throw java.io.IOException("cannot resolve game directory for ${game.uri}")
            RpgSaveFormatConverter.convert(gameDir, game.engine)
        }

    /** MV/MZ 的游戏本地目录；非 RPG 引擎或无法解析时返回 null。 */
    private fun effectiveRpgGameDirectory(context: Context, game: ScanGame): File? {
        if (!RpgSaveFormat.isRpgWebEngine(game.engine)) return null
        return resolveGameDirectory(context, game)?.let(::File)
    }

    /**
     * MV/MZ 存档互通生效值：单游戏覆盖优先，否则全局；非 RPG 引擎恒为 false。
     * 单游戏覆盖读取会命中 DB（[PerGameSettingsStore.getBool] → loadRowBlocking 内含
     * runBlocking，首次还要回灌 SharedPreferences），故本函数挂起并在 IO 线程读取，
     * 避免 UI 主线程协程被阻塞。
     */
    suspend fun isRpgSaveInteropEnabled(context: Context, game: ScanGame): Boolean =
        isRpgSaveInteropEnabled(context, game.uri, game.engine)

    /** 互通开关生效值（按 gameId/engine，不依赖 ScanGame），供前台兜底复用。 */
    private suspend fun isRpgSaveInteropEnabled(context: Context, gameUri: String, engine: EngineType): Boolean {
        if (!RpgSaveFormat.isRpgWebEngine(engine)) return false
        return withContext(Dispatchers.IO) {
            PerGameSettingsStore.getBool(context, gameUri, PerGameSettingsStore.F_RPG_SAVE_INTEROP)
                ?: EngineSettingsStore.isRpgSaveInterop(context)
        }
    }

    /**
     * 执行一次 MV/MZ 存档互通双向同步（标准侧 `<内容根>/save` ⇄ Tyranor 侧有效存档目录）。
     * 返回同步结果供 UI 提示；非 RPG 引擎返回空结果。含文件 IO，切 IO 执行。
     */
    suspend fun syncRpgSaves(context: Context, game: ScanGame): RpgSaveSync.Result =
        withContext(Dispatchers.IO) {
            if (!RpgSaveFormat.isRpgWebEngine(game.engine)) return@withContext RpgSaveSync.Result()
            val gameDir = resolveGameDirectory(context, game)?.let(::File)
                ?: throw java.io.IOException("cannot resolve game directory for ${game.uri}")
            syncRpgSavesForDirectory(context, game.uri, gameDir, game.engine)
        }

    /** 同步核心（无 Context 依赖游戏解析）：供启动/前台兜底/手动入口共用。 */
    private fun syncRpgSavesForDirectory(
        context: Context,
        gameUri: String,
        gameDir: File,
        engine: EngineType,
    ): RpgSaveSync.Result {
        // 标准侧兼容 save / Save 两种拼写；首选写入目录为已存在的小写 save（默认）
        val standardDirs = RpgSaveFormat.standardSaveDirectories(gameDir)
        val saveManager = GameSaveManager(context)
        val scoped = saveManager.isTyranoFamilyScoped(gameUri)
        val tyranorDir = saveManager.effectiveTyranoFamilySaveDirectory(gameUri, gameDir.absolutePath, scoped)
            ?: gameDir.resolve("savedata")
        return RpgSaveSync.sync(
            standardDirs = standardDirs,
            tyranorDir = tyranorDir,
            engine = engine,
            stateStore = RpgSaveSyncState.forContext(context),
            gameKey = gameUri,
        )
    }

    /** 记录待回写的游戏（应用回到前台时对已退出会话补一次同步）。 */
    fun recordPendingSaveSync(context: Context, gameUri: String) {
        if (gameUri.isBlank()) return
        RpgSavePendingStore.add(context, gameUri)
    }

    /**
     * 应用回到前台时调用：对每个待回写游戏，等待其引擎会话进程退出后补一次同步并清除记录。
     *
     * 引擎 finish 后约 500ms 才 killProcess（且强杀无回调），回到前台时进程往往仍在，
     * 因此这里按 [SESSION_EXIT_POLL_MS] 轮询等待其退出（上限 [SESSION_EXIT_MAX_ATTEMPTS] 次）；
     * 超时仍存活视为「后台会话」本次不回写，避免与运行中的引擎并发读写。
     *
     * 只有「确认游戏已从库中删除」「不再是 RPG 引擎」「互通开关已关闭」或「本次同步成功」
     * 才移除 pending；临时性失败（游戏库读取异常、SAF 权限失效、存储未挂载导致目录解析失败、
     * 同步抛错）一律保留记录，留待下次前台重试。
     */
    suspend fun flushPendingSaveSync(context: Context): Int = withContext(Dispatchers.IO) {
        val pending = RpgSavePendingStore.all(context)
        if (pending.isEmpty()) return@withContext 0
        var synced = 0
        pending.forEach { gameUri ->
            // 读取游戏库失败（IO/DB 异常）不改变 pending：无法区分「已删除」与「暂时读不到」
            val games = try {
                GameLibraryFacade.loadGames(context)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "rpg save interop flush: loadGames failed, keep pending uri=$gameUri", t)
                return@forEach
            }
            val game = games.firstOrNull { it.uri == gameUri }
            if (game == null || !RpgSaveFormat.isRpgWebEngine(game.engine)) {
                // 已确认游戏不存在或非 RPG 引擎：记录无意义，移除
                RpgSavePendingStore.remove(context, gameUri)
                return@forEach
            }
            // 开关关闭后不再回写（也可能是本次登记后用户关掉了互通）
            if (!isRpgSaveInteropEnabled(context, game.uri, game.engine)) {
                RpgSavePendingStore.remove(context, gameUri)
                return@forEach
            }
            // 目录暂时解析不到（SAF 权限失效/存储未挂载/IO 异常）：保留记录待下次重试
            val gameDir = try {
                resolveGameDirectory(context, game)?.let(::File)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "rpg save interop flush: resolve dir failed, keep pending uri=$gameUri", t)
                return@forEach
            }
            if (gameDir == null) {
                Log.i(TAG, "rpg save interop flush: game dir unavailable, keep pending uri=$gameUri")
                return@forEach
            }
            val gameDirPath = gameDir.absolutePath
            var attempts = 0
            while (isEngineSessionRunning(context, game, gameDirPath) && attempts < SESSION_EXIT_MAX_ATTEMPTS) {
                delay(SESSION_EXIT_POLL_MS)
                attempts++
            }
            if (isEngineSessionRunning(context, game, gameDirPath)) return@forEach
            try {
                syncRpgSavesForDirectory(context, gameUri, gameDir, game.engine)
                synced++
                RpgSavePendingStore.remove(context, gameUri)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 同步失败保留 pending，下次前台重试
                Log.w(TAG, "rpg save interop flush failed, keep pending uri=$gameUri", t)
            }
        }
        synced
    }

    /**
     * 对应 host 进程是否正运行**同一游戏**的会话。仅按进程后缀判断会把「任意后台
     * Tyrano 游戏」误判为本游戏在跑，导致本游戏的回写被永久跳过；故先看
     * [EngineSessionRegistry] 登记的当前游戏目录是否与本游戏一致，再用进程存活兜底
     * （登记在宿主被强杀时可能残留，进程不在了就不算运行）。
     */
    private fun isEngineSessionRunning(context: Context, game: ScanGame, gameDirPath: String?): Boolean {
        if (gameDirPath.isNullOrBlank()) return false
        val hosts = if (game.engine == EngineType.RPG_MV || game.engine == EngineType.RPG_MZ) {
            // v1/v2 跑在 :rpgmaker，v0 跑在 :tyrano；两者任一在跑同一游戏都视为运行中
            listOf(EngineSessionRegistry.HOST_RPGMAKER to ":rpgmaker", EngineSessionRegistry.HOST_TYRANO to ":tyrano")
        } else {
            listOf(EngineSessionRegistry.HOST_TYRANO to ":tyrano")
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val running = am.runningAppProcesses.orEmpty()
        return hosts.any { (host, suffix) ->
            if (running.none { proc -> proc.processName.endsWith(suffix) }) return@any false
            val registered = EngineSessionRegistry.currentGame(context, host) ?: return@any false
            samePath(registered, gameDirPath)
        }
    }

    /** 目录等价判定：优先 canonicalPath，失败退回字符串比较（与 stopOppositeRpgHost 同策略）。 */
    private fun samePath(a: String, b: String): Boolean = try {
        File(a).canonicalPath == File(b).canonicalPath
    } catch (_: Throwable) {
        a == b
    }

    /**
     * Native engines receive a real /storage path, so SAF tree grants are not enough on Android 11+.
     * Match RinneMobile's requirement: ask the user to enable "Manage all files" before launching.
     */
    private fun requestAllFilesAccessIfNeeded(context: Context, game: ScanGame, path: String): LaunchResult.Failure? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (Environment.isExternalStorageManager()) return null
        if (game.engine == EngineType.KIRIKIRI && GamePathUtils.isRemovableStoragePath(path)) return null
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
            LaunchResult.Failure.AllFilesAccessRequested
        } else {
            LaunchResult.Failure.AllFilesAccessMissing
        }
    }

    /** 判断路径是否位于共享存储（原生引擎无法仅凭 SAF 授权读取），供启动前与手动添加目录共用。 */
    internal fun needsAllFilesAccess(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == "/sdcard" ||
            normalized.startsWith("/sdcard/") ||
            normalized == "/storage/emulated/0" ||
            normalized.startsWith("/storage/emulated/0/") ||
            GamePathUtils.isRemovableStoragePath(normalized)
    }

    /** 构建引擎 Intent；path 为真实文件路径。
     *  字体偏好契约：`default_font`（空串=引擎按所有权标记清理残留）与
     *  `force_default_font`（false 写 "0"）**必须无条件注入**——引擎侧
     *  applyFontPreferences 仅在 hasExtra 时执行写入/清理，缺省会让
     *  引擎配置里的旧值永久残留（issue #74）。 */
    private fun buildIntent(
        context: Context,
        engine: EngineType,
        path: String,
        game: ScanGame,
        patchChoice: ArtemisPatchChoice? = null,
        krSafMirror: KrSafMirror.Prepared? = null,
        settings: ResolvedEngineSettings,
    ): Intent {
        val intent = when (engine) {
            EngineType.KIRIKIRI ->
                buildKirikiriIntent(context, path, game, krSafMirror, settings)

            EngineType.ONS -> {
                val ons = settings.ons
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
                    putStringArrayListExtra(LaunchContract.GAME_ARGS, args)
                    putExtra(LaunchContract.GAME_URI, Uri.fromFile(java.io.File(path)).toString())
                    putExtra(LaunchContract.PATH, path)
                    putExtra(LaunchContract.GAME_PATH, path)
                    putExtra(LaunchContract.ROOT_URI, game.uri)
                    putExtra(LaunchContract.LAUNCH_TARGET, game.launchTarget)
                    putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_ONS)
                    putExtra(LaunchContract.IGNORE_CUTOUT, ons.ignoreCutout)
                }
            }

            EngineType.TYRANO,
            EngineType.RPG_MV,
            EngineType.RPG_MZ,
            EngineType.VN,
            EngineType.WEB_OTHER -> buildWebIntent(context, path, game, settings)

            EngineType.ARTEMIS -> buildArtemisIntent(context, path, game, patchChoice, settings)

            EngineType.RPGMAKER,
            EngineType.RENPY -> error("${engine.displayName} is handled by external engine launcher")

            EngineType.UNKNOWN -> Intent(context, TyranoActivity::class.java).apply {
                putExtra(LaunchContract.PATH, path)
                putExtra(LaunchContract.GAME_PATH, path)
                putExtra(LaunchContract.ROOT_URI, game.uri)
                putExtra(LaunchContract.LAUNCH_TARGET, game.launchTarget)
                putExtra(LaunchContract.TYPE, "Tyrano")
            }
        }
        // 注入 App 统一主题色与深浅色：引擎壳自绘 UI（确认/输入弹窗按钮等）经
        // EngineThemeColors.fromIntent / KrDialogStyle 读取，缺失时回落默认绿。
        // 主题色来自 ui 层写入的纯数据快照（ThemeColorPayloadStore），core 不反向依赖 theme。
        val theme = ThemeColorPayloadStore.current ?: ThemeColorPayload.DEFAULT
        intent.putExtra(LaunchContract.DARK_MODE, theme.darkMode)
        intent.putExtra(LaunchContract.PRIMARY_COLOR, theme.primaryArgb)
        intent.putExtra(LaunchContract.THEME_COLOR_PRIMARY, theme.primaryArgb)
        intent.putExtra(LaunchContract.THEME_COLOR_ON_PRIMARY, theme.onPrimaryArgb)
        intent.putExtra(LaunchContract.THEME_COLOR_CARD, theme.cardArgb)
        intent.putExtra(LaunchContract.THEME_COLOR_TEXT, theme.textArgb)
        intent.putExtra(LaunchContract.THEME_COLOR_TEXT_MUTED, theme.mutedArgb)
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
        settings: ResolvedEngineSettings,
    ): Intent {
        val gid = game.uri
        val needsSafFallback = GamePathUtils.isRemovableStoragePath(path)
        val kernel = settings.krKernel
        val skipStartupDialogs = settings.krSkipStartupDialogs
        val engineRoot = safMirror?.mirrorRoot?.absolutePath ?: path
        val pickedLaunchEntry = pickKrActivateEntry(engineRoot, game)
        if (kernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
            val args = buildKrkrsdl3Args(context, path, pickedLaunchEntry, settings)
            Log.i(TAG, "krkrsdl3 launch root=$path entry=$pickedLaunchEntry args=$args")
            // krkrsdl3 内核：gameargs 首项为启动文件绝对路径，后续为 TVP 命令行参数
            return Intent(context, Krkrsdl3Activity::class.java).apply {
                putStringArrayListExtra(LaunchContract.GAME_ARGS, args)
                putExtra(LaunchContract.PATH, path)
                putExtra(LaunchContract.GAME_PATH, pickedLaunchEntry)
                putExtra(LaunchContract.PROJECT_ROOT, path)
                putExtra(LaunchContract.GAME_DIR, path)
                putExtra(LaunchContract.ROOT_URI, game.uri)
                putExtra(LaunchContract.LAUNCH_TARGET, game.launchTarget)
                putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_KRKRSDL3)
                putExtra(KrkrStartupDialogPolicy.EXTRA_ENABLED, skipStartupDialogs)
                putExtra(LaunchContract.ORIENTATION, 6)
                putExtra(LaunchContract.FOCUS, "true")
            }
        }
        val version = settings.krEngineVersion
        val activity = when (version) {
            EngineSettingsStore.KR_134 -> Kirikiroid134::class.java
            EngineSettingsStore.KR_126 -> Kirikiroid126::class.java
            else -> Kirikiroid139::class.java
        }
        val launchEntry = pickedLaunchEntry
        val scoped = settings.krScopedSaveDir
        val actualSaveRoot = safMirror?.let { File(it.mirrorRoot, "savedata") }
            ?: resolveKrSaveDir(context, path, kernel, scoped)
        val effectiveScoped = scoped || safMirror != null
        val defaultFont = settings.krDefaultFont
        val forceFont = settings.krForceDefaultFont
        val patchOverlay = prepareKrPatchOverlay(context, engineRoot, settings.krPatchOverlayMode)
        val steamConfigOverlay = prepareKrSteamConfigOverlay(context, engineRoot, settings.krPatchOverlayMode)
        return Intent(context, activity).apply {
            // KR2 引擎把 path 视为“启动条目”，gamedir = path 的父目录。
            putExtra(LaunchContract.PATH, launchEntry)
            putExtra(LaunchContract.GAME_PATH, launchEntry)
            putExtra(LaunchContract.PROJECT_ROOT, engineRoot)
            putExtra(LaunchContract.GAME_DIR, engineRoot)
            putExtra(LaunchContract.ORIGINAL_PROJECT_ROOT, path)
            putExtra(LaunchContract.GAME_SAVE_ROOT, actualSaveRoot.absolutePath)
            putExtra(LaunchContract.ROOT_URI, game.uri)
            putExtra(LaunchContract.LAUNCH_TARGET, game.launchTarget)
            putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_KIRIKIROID2)
            putExtra(KrkrStartupDialogPolicy.EXTRA_ENABLED, skipStartupDialogs)
            putExtra(LaunchContract.SAF_FILE_FALLBACK, needsSafFallback)
            patchOverlay?.let {
                putExtra(LaunchContract.KR_PATCH_OVERLAY_TARGET, it.targetPatch.absolutePath)
                putExtra(LaunchContract.KR_PATCH_OVERLAY_PATH, it.overlayPatch.absolutePath)
                putExtra(LaunchContract.KR_PATCH_OVERLAY_MODE, it.mode)
            }
            steamConfigOverlay?.let {
                putExtra(LaunchContract.KR_STEAM_CONFIG_OVERLAY_TARGET, it.targetConfig.absolutePath)
                putExtra(LaunchContract.KR_STEAM_CONFIG_OVERLAY_PATH, it.overlayConfig.absolutePath)
            }
            safMirror?.let {
                putExtra(LaunchContract.BASE_DOC, game.uri)
                putExtra(LaunchContract.SAF_MIRROR_ROOT, it.mirrorRoot.absolutePath)
                putExtra(LaunchContract.SAF_MIRROR_INDEX, it.indexFile.absolutePath)
                putExtra(LaunchContract.SAF_MIRROR_FILES, it.fileCount)
            }
            putExtra(LaunchContract.ORIENTATION, 6)
            putExtra(LaunchContract.SCOPED_SAVE_DIR, effectiveScoped)
            if (effectiveScoped) {
                putExtra(LaunchContract.SCOPED_SAVE_ROOT, actualSaveRoot.absolutePath)
            }
            putExtra(LaunchContract.FOCUS, "true")
            // 引擎版本
            putExtra(LaunchContract.KR_ENGINE_VERSION, when (version) {
                EngineSettingsStore.KR_134 -> "1.3.4"
                EngineSettingsStore.KR_126 -> "1.2.6"
                else -> "1.3.9"
            })
            // 字体偏好：两个 extra 必须无条件注入。引擎侧 applyFontPreferences 仅在
            // hasExtra 时执行写入/按所有权标记清理——若仅在非空/为真时注入，用户关闭
            // 强制或清空字体后引擎 XML 里的旧值会永久残留（issue #74「换字体无法生效」
            // 的根因：残留的 force_default_font=1 一直压住新设置的字体）。
            putExtra(LaunchContract.DEFAULT_FONT, defaultFont)
            putExtra(LaunchContract.FORCE_DEFAULT_FONT, forceFont)
            // Anime4K 画面超分（单游戏覆盖 > 全局；仅 kirikiri2 内核路径支持）
            putExtra(com.core.gl.Anime4kRuntime.EXTRA_MODE, settings.krAnime4kMode)
            // 渲染/内存偏好 JSON：单游戏覆盖 与 全局 逐键合并；字段映射由 KrRenderPrefs 单一来源提供
            runCatching {
                putExtra(LaunchContract.KR_ENGINE_PREFS, EngineSettingsStore.buildKrEnginePrefsJson(context) { pref ->
                    PerGameSettingsStore.getStr(context, gid, pref.overrideField)?.trim()
                })
            }.onFailure { android.util.Log.w("EngineLauncher", "build krkr_engine_prefs failed", it) }
        }
    }

    private fun buildKrkrsdl3Args(
        context: Context,
        path: String,
        launchEntry: String,
        settings: ResolvedEngineSettings,
    ): ArrayList<String> {
        val args = arrayListOf(launchEntry)
        args.add("-render=${normalizeKrkrsdl3Renderer(settings.krRenderer)}")
        val saveDir = resolveKrSaveDir(context, path, EngineSettingsStore.KERNEL_KRKRSDL3, settings.krScopedSaveDir)
        if (saveDir.exists() || saveDir.mkdirs()) {
            args.add("-savedir=${saveDir.absolutePath}")
        }
        return args
    }

    private fun resolveKrSaveDir(context: Context, path: String, kernel: String, scoped: Boolean): File {
        if (!scoped) return File(path, "savedata")
        return if (kernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            File(File(baseDir, "save"), GamePathUtils.safeSaveName(path))
        } else {
            File(File(File(context.filesDir, "krkr_mirror"), GamePathUtils.safeSaveName(path)), "savedata")
        }
    }

    private fun ensureKrSaveDir(
        context: Context,
        game: ScanGame,
        path: String,
        settings: ResolvedEngineSettings,
    ): LaunchResult.Failure? {
        val scoped = settings.krScopedSaveDir
        val kernel = settings.krKernel
        val saveDir = resolveKrSaveDir(context, path, kernel, scoped)
        if (saveDir.isDirectory) return null
        if (saveDir.exists()) return LaunchResult.Failure.KrkrSavePathNotDirectory(saveDir.absolutePath)
        if (saveDir.mkdirs() || saveDir.isDirectory) return null
        if (!scoped && ensureKrGameSaveDirViaSaf(context, game, path)) return null
        return LaunchResult.Failure.KrkrSaveDirUnavailable(saveDir.absolutePath, scoped)
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
        settings: ResolvedEngineSettings,
    ): Intent {
        // 版本/补丁策略的覆盖值已在解析器内走白名单，非法持久化值回退全局（P0-3）
        var version = settings.artVersion
        val rotate = settings.artRotate
        var autoPatch = settings.artAutoPatch
        val androidSettings = ArtemisPfsUnpacker.AndroidSettings(
            resolution = settings.artResolution,
            sideCut = settings.artSideCut,
            surfaceCacheSize = settings.artSurfaceCacheSize,
            fontCacheSize = settings.artFontCacheSize,
            powerSaving = settings.artPowerSaving,
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
            putExtra(LaunchContract.PATH, path)
            putExtra(LaunchContract.GAME_PATH, path)
            putExtra(LaunchContract.ROOT_URI, game.uri)
            putExtra(LaunchContract.LAUNCH_TARGET, game.launchTarget)
            putExtra(LaunchContract.LAUNCH_MODE, LaunchContract.LAUNCH_MODE_ARTEMIS)
            putExtra(LaunchContract.ORIENTATION, if (rotate) 8 else 6)
            putExtra(LaunchContract.SCOPED_SAVE_DIR, false)
            // artemis_loader 按 "lib<engineLibName>.so" 拼路径，需传库名（不带 lib 前缀）
            putExtra(LaunchContract.ENGINE_LIB_NAME, libName)
            putExtra(LaunchContract.ARTEMIS_AUTO_FALLBACK, auto)
            putExtra(LaunchContract.ARTEMIS_FALLBACK_STAGE, stage)
            putExtra(LaunchContract.ARTEMIS_CURRENT_VERSION, version)
            putExtra(LaunchContract.ARTEMIS_FALLBACK_VERSIONS, fallbackVersions.joinToString(","))
            putExtra(LaunchContract.ARTEMIS_FALLBACK_INDEX, fallbackIndex)
            putExtra(LaunchContract.ARTEMIS_AUTO_PLAN_REASON, planReason)
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

    private fun buildWebIntent(
        context: Context,
        path: String,
        game: ScanGame,
        settings: ResolvedEngineSettings,
    ): Intent {
        // Tyrano 与 RPG Maker Web 共用同一组 WebView 宿主设置。
        val scoped = settings.webScopedSaveDir
        val rpgMakerModEnabled = settings.rpgMakerModEnabled
        val scopedSaveRoot = if (scoped) {
            context.getExternalFilesDir(null)?.let { external ->
                File(File(File(external, "save"), "tyrano"), GamePathUtils.safeSaveName(path)).absolutePath
            }
        } else {
            null
        }
        // v1/v2 由独立 rpgmaker 运行时（:rpgmaker 进程）承载；v0 与 MZ v1（占位版本）
        // 沿用原 tyrano 宿主的 v0 链路，不传版本 extras，行为与历史版本完全一致。
        // 版本/legacy 读取仅在 RPG 会话进行，Tyrano/VN/WebOther 启动路径零新增开销。
        val rpgSession = game.engine == EngineType.RPG_MV || game.engine == EngineType.RPG_MZ
        val rpgMakerVersion = when (game.engine) {
            EngineType.RPG_MV -> settings.rpgMvVersion
            EngineType.RPG_MZ -> settings.rpgMzVersion
            else -> null
        }
        val rpgLegacyRenderer = if (rpgSession) settings.rpgLegacyRenderer else false
        val useRpgMakerRuntime = when (game.engine) {
            EngineType.RPG_MV -> rpgMakerVersion == EngineSettingsStore.RPG_MV_V1 ||
                rpgMakerVersion == EngineSettingsStore.RPG_MV_V2
            EngineType.RPG_MZ -> rpgMakerVersion == EngineSettingsStore.RPG_MZ_V2
            else -> false
        }
        if (rpgSession) {
            // 与宿主侧 resolveGameDir 同型归一：目录（游戏路径为文件时取其父目录）
            val sessionGameDir = File(path).let { f -> (if (f.isFile) f.parentFile else f)?.absolutePath }
            stopOppositeRpgHost(context, useRpgMakerRuntime, sessionGameDir)
        }
        val target = if (useRpgMakerRuntime) RpgMakerActivity::class.java else TyranoActivity::class.java
        return Intent(context, target).apply {
            putExtra(LaunchContract.PATH, path)
            putExtra(LaunchContract.GAME_PATH, path)
            putExtra(LaunchContract.PROJECT_ROOT, path)
            putExtra(LaunchContract.GAME_DIR, path)
            putExtra(LaunchContract.ROOT_URI, game.uri)
            putExtra(LaunchContract.LAUNCH_TARGET, game.launchTarget)
            val webType = when (game.engine) {
                EngineType.RPG_MV -> "RPG"
                EngineType.RPG_MZ -> "RMMZ"
                EngineType.VN -> "VN"
                EngineType.WEB_OTHER -> "WebOther"
                else -> "Tyrano"
            }
            putExtra(LaunchContract.TYPE, webType)
            putExtra(LaunchContract.LAUNCH_MODE, "internal.${webType.lowercase()}")
            putExtra(LaunchContract.ORIENTATION, 6)
            putExtra(LaunchContract.SCOPED_SAVE_DIR, scoped)
            scopedSaveRoot?.let { putExtra(LaunchContract.SCOPED_SAVE_ROOT, it) }
            putExtra(LaunchContract.RPG_MAKER_MOD_ENABLED, rpgMakerModEnabled)
            putExtra(LaunchContract.RPG_MAKER_MOD_GAME_ID, game.uri)
            if (useRpgMakerRuntime) {
                rpgMakerVersion?.let { putExtra(LaunchContract.RPG_MAKER_VERSION, it) }
                putExtra(LaunchContract.RPG_LEGACY_RENDERER, rpgLegacyRenderer)
            }
        }
    }

    /**
     * 仅当会话登记表显示另一宿主正运行【同一游戏】时才回收对方进程（PR review 意见：
     * 按进程后缀终止会误杀后台无关的 Tyrano/RPG 会话并可能丢失未存档进度）。
     * 会话登记由各宿主 onCreate/onDestroy 经 EngineSessionRegistry 维护（跨进程文件，
     * 主进程读取始终为最新值）；getRunningAppProcesses 仅返回本应用（同 uid）进程。
     */
    private fun stopOppositeRpgHost(context: Context, targetIsRpgMaker: Boolean, sessionGameDir: String?) {
        if (sessionGameDir.isNullOrBlank()) return
        val oppositeHost = if (targetIsRpgMaker) EngineSessionRegistry.HOST_TYRANO else EngineSessionRegistry.HOST_RPGMAKER
        val registered = EngineSessionRegistry.currentGame(context, oppositeHost) ?: return
        val matches = try {
            File(registered).canonicalPath == File(sessionGameDir).canonicalPath
        } catch (_: Throwable) {
            registered == sessionGameDir
        }
        if (!matches) return
        val oppositeSuffix = if (targetIsRpgMaker) ":tyrano" else ":rpgmaker"
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses
                ?.filter { it.processName.endsWith(oppositeSuffix) }
                ?.forEach { processInfo ->
                    runCatching { android.os.Process.killProcess(processInfo.pid) }
                }
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
    private fun prepareKrPatchOverlay(context: Context, engineRoot: String, mode: String): KrPatchOverlay? {
        val root = File(engineRoot)
        if (!root.isDirectory || engineRoot.startsWith("content://")) return null
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
            val overlayDir = File(File(context.filesDir, "krkr_patch_overlay"), GamePathUtils.safeSaveName(engineRoot))
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
    private fun prepareKrSteamConfigOverlay(context: Context, engineRoot: String, mode: String): KrSteamConfigOverlay? {
        val root = File(engineRoot)
        if (!root.isDirectory || engineRoot.startsWith("content://") || !rootContainsFbfSteamPlugin(root)) return null
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
            val overlayDir = File(File(context.filesDir, "krkr_config_overlay"), GamePathUtils.safeSaveName(engineRoot))
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
        GamePathUtils.safUriToPath(uriText)?.let { mapped ->
            val f = java.io.File(mapped)
            if (f.isDirectory) return f.absolutePath
            if (game.engine == EngineType.KIRIKIRI && GamePathUtils.isRemovableStoragePath(mapped)) {
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
): Boolean = EffectiveEngineSettings.resolveRpgMakerModEnabled(engine, perGameOverride, globalDefault)
