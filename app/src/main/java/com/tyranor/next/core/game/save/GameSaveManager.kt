package com.tyranor.next.core.game.save

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.launch.EthornellOperationGate
import com.tyranor.next.core.game.manual.AndroidAppGames
import com.tyranor.next.core.game.model.EthornellPaths
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.Xs35Paths
import com.tyranor.next.core.game.model.Xs4Paths
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.EngineSettingsResolver
import com.tyranor.next.core.settings.EngineSettingsStore
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class GameSaveManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val localizedContext: Context
        get() = AppLocaleController.wrap(appContext)

    data class SaveLocation(
        val directory: File?,
        val description: String,
        val available: Boolean,
        /** 存档路径被 SD 镜像强制接管（与独立存档开关无关），UI 据此置灰开关并显示引导。 */
        val mirrorForced: Boolean = false,
    )

    fun resolveSaveLocation(game: ScanGame): SaveLocation {
        // 手动添加的安卓游戏无文件存档接口：先于目录解析返回（androidapp:// 无法解析为目录，
        // 否则会落到「无法解析游戏目录」的误导文案）
        if (AndroidAppGames.isAndroidApp(game)) {
            return SaveLocation(
                null,
                text(R.string.save_location_engine_no_file_interface, game.engine.displayName),
                false,
            )
        }
        // Ruffle 的 SWF 记录指向文件本身，且外置模块没有 App 可管理的文件存档接口；
        // 先于目录解析返回，避免 SWF 文件 URI 落到「无法解析游戏目录」的误导文案。
        if (game.engine == EngineType.RUFFLE) {
            return SaveLocation(
                null,
                text(R.string.save_location_engine_no_file_interface, game.engine.displayName),
                false,
            )
        }
        // Exact private identity root even when the resource volume is absent.
        // Admission remains disabled until the device save-IO fixture is accepted.
        if (game.engine == EngineType.ETHORNELL) {
            val directory = try {
                val expected = EthornellPaths.userDataDir(appContext.filesDir, game.uri)
                EthornellPaths.validateUserDataDir(appContext.filesDir, game.uri, expected.absolutePath)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            return SaveLocation(
                directory,
                text(R.string.save_location_engine_scoped, game.engine.displayName),
                directory != null && EthornellSaveCapability.available,
            )
        }
        val root = resolveGameDirectory(game)
            ?: return SaveLocation(null, text(R.string.save_error_resolve_game_dir), false)
        // 三级设置统一解析（应用级 + 单游戏覆盖），避免本类重复逐字段合并（P0-3）
        val settings = EngineSettingsResolver.resolve(appContext, game, root)

        return when (game.engine) {
            EngineType.KIRIKIRI -> {
                // 可移动存储上启动器强制走 KrSafMirror 镜像（与独立存档开关无关），
                // 引擎实际读写的是镜像目录内的 savedata，存档管理必须指向同一处。
                if (GamePathUtils.isRemovableStoragePath(root)) {
                    return SaveLocation(
                        File(bridge.KrSafMirror.mirrorRootFor(appContext, game.uri, root), "savedata"),
                        text(R.string.save_location_krkr_sd_mirror),
                        true,
                        mirrorForced = true,
                    )
                }
                if (settings.krScopedSaveDir) {
                    if (settings.krKernel == EngineSettingsStore.KERNEL_KRKRSDL3) {
                        val external = appContext.getExternalFilesDir(null)
                            ?: return SaveLocation(null, text(R.string.save_error_krkr_sdl3_external_unavailable), false)
                        SaveLocation(
                            File(File(external, "save"), GamePathUtils.safeSaveName(root)),
                            text(R.string.save_location_krkr_sdl3_scoped),
                            true,
                        )
                    } else {
                        val internal = appContext.filesDir
                            ?: return SaveLocation(null, text(R.string.save_error_app_internal_unavailable), false)
                        SaveLocation(
                            File(File(File(internal, "krkr_mirror"), GamePathUtils.safeSaveName(root)), "savedata"),
                            text(R.string.save_location_krkr_scoped),
                            true,
                        )
                    }
                } else {
                    SaveLocation(File(root, "savedata"), text(R.string.save_location_krkr_game_dir), true)
                }
            }
            EngineType.ONS -> {
                if (settings.ons.scopedSaveDir) {
                    val external = appContext.getExternalFilesDir(null)
                        ?: return SaveLocation(null, text(R.string.save_error_ons_external_unavailable), false)
                    SaveLocation(File(File(external, "save"), File(root).name), text(R.string.save_location_ons_scoped), true)
                } else {
                    SaveLocation(File(root, "save"), text(R.string.save_location_ons_game_dir), true)
                }
            }
            EngineType.TYRANO,
            EngineType.RPG_MV,
            EngineType.RPG_MZ -> {
                // Tyrano 与 RPG Maker Web 共用 TyranoActivity，存档目录开关保持同一套配置。
                if (settings.webScopedSaveDir) {
                    val external = appContext.getExternalFilesDir(null)
                        ?: return SaveLocation(null, text(R.string.save_error_tyrano_external_unavailable), false)
                    SaveLocation(
                        File(File(File(external, "save"), "tyrano"), GamePathUtils.safeSaveName(root)),
                        text(R.string.save_location_engine_scoped, game.engine.displayName),
                        true,
                    )
                } else {
                    SaveLocation(
                        File(root, "savedata"),
                        text(R.string.save_location_engine_game_dir, game.engine.displayName),
                        true,
                    )
                }
            }
            EngineType.VN, EngineType.WEB_OTHER, EngineType.RPGMAKER, EngineType.RENPY,
            // RUFFLE 在函数开头已提前返回（外置模块无 App 可管理的文件存档接口），此分支仅为 when 完备性
            EngineType.RUFFLE,
            // ETHORNELL returns its exact private user root above; exhaustive fallback only.
            EngineType.ETHORNELL,
            EngineType.PSP, EngineType.NINTENDO_SWITCH, EngineType.CATSYSTEM2, EngineType.PC,
            // ANDROID_APP 在函数开头已提前返回（无文件存档接口），此分支仅为 when 完备性
            EngineType.ANDROID_APP ->
                SaveLocation(null, text(R.string.save_location_engine_no_file_interface, game.engine.displayName), false)
            EngineType.ARTEMIS -> SaveLocation(File(root), text(R.string.save_location_artemis_game_dir), true)
            EngineType.SIGLUS -> SaveLocation(
                File(root, "savedata"),
                text(R.string.save_location_engine_game_dir, game.engine.displayName),
                true,
            )
            // RealLive：存档固定在游戏目录 savedata_rs/
            EngineType.REALLIVE -> SaveLocation(
                File(root, "savedata_rs"),
                text(R.string.save_location_engine_game_dir, game.engine.displayName),
                true,
            )
            // AVG32（SAVE.INI）/ UK2（FLAGnn.DAT）：存档是游戏目录内的单个/多个文件，
            // 以「白名单过滤 + 导入后恢复其余游戏文件」方式纳入统一管理（与 ARTEMIS 的
            // 「存档目录即游戏根」语义一致，见 excludeFor/restoresExcludedFromBackup）。
            EngineType.AVG32,
            EngineType.UK2 -> SaveLocation(
                File(root),
                text(R.string.save_location_engine_game_dir, game.engine.displayName),
                true,
            )
            // FVP（rfvp）：存档固定在游戏目录 save/（rfvp_s###.bin，读取兼容 s###.bin）
            EngineType.FVP -> SaveLocation(
                File(root, "save"),
                text(R.string.save_location_engine_game_dir, game.engine.displayName),
                true,
            )
            // System35（xsystem35）：独立存档开（默认）→ 应用外部存档目录；关闭 → `<生效游戏目录>/save`。
            // 目录推导与启动侧 ensureXs35SaveDir / XS35_SAVE_DIR 共用 Xs35Paths（单一实现，禁止两侧各自推导）。
            EngineType.XSYS35 -> {
                if (settings.xs35ScopedSaveDir) {
                    val external = appContext.getExternalFilesDir(null)
                        ?: return SaveLocation(null, text(R.string.save_error_xsys35_external_unavailable), false)
                    SaveLocation(
                        Xs35Paths.scopedSaveDir(external, root),
                        text(R.string.save_location_engine_scoped, game.engine.displayName),
                        true,
                    )
                } else {
                    SaveLocation(
                        Xs35Paths.gameDirSaveDir(Xs35Paths.effectiveGameDir(File(root))),
                        text(R.string.save_location_engine_game_dir, game.engine.displayName),
                        true,
                    )
                }
            }
            // System4（xsystem4）：独立存档开（默认）→ 应用外部存档目录；关闭 → `<游戏根>/<SaveFolder>`（经校验）。
            // 目录推导与启动侧 ensureXs4SaveDir / XS4_SAVE_DIR 共用 Xs4Paths（单一实现）。
            // 关闭分支读取 ini，本函数须在 IO 线程调用（与 XSYS35 同约束）。
            EngineType.XSYS4 -> {
                if (settings.xs4ScopedSaveDir) {
                    val external = appContext.getExternalFilesDir(null)
                        ?: return SaveLocation(null, text(R.string.save_error_xsys4_external_unavailable), false)
                    SaveLocation(
                        Xs4Paths.scopedSaveDir(external, root),
                        text(R.string.save_location_engine_scoped, game.engine.displayName),
                        true,
                    )
                } else {
                    SaveLocation(
                        Xs4Paths.gameDirSaveDir(File(root)),
                        text(R.string.save_location_engine_game_dir, game.engine.displayName),
                        true,
                    )
                }
            }
            // YU-RIS：存档固定在游戏目录 save/（经 Winlator 运行但落盘在游戏目录，可纳入统一存档管理）
            EngineType.YURIS -> SaveLocation(
                File(root, "save"),
                text(R.string.save_location_engine_game_dir, game.engine.displayName),
                true,
            )
            EngineType.UNKNOWN -> SaveLocation(null, text(R.string.save_location_unknown_unsupported), false)
        }
    }

    /**
     * Tyrano 家族（Tyrano/MV/MZ）的有效存档目录：独立存档开关开启时为外部私有目录
     * （需要外部存储可用，不可用时返回 null，调用方应跳过而非回退游戏根），否则
     * `<游戏根>/savedata`。与 engine 宿主 resolveSaveDirectory 语义一致；
     * 开关生效值统一经 [EngineSettingsResolver] 的 webScopedSaveDir 获取（单一事实源）。
     */
    fun effectiveTyranoFamilySaveDirectory(gameId: String, root: String, scoped: Boolean): File? {
        if (!scoped) return File(root, "savedata")
        val external = appContext.getExternalFilesDir(null) ?: return null
        return File(File(File(external, "save"), "tyrano"), GamePathUtils.safeSaveName(root))
    }

    suspend fun listSaveFiles(game: ScanGame): List<File> {
        if (game.engine == EngineType.ETHORNELL) {
            // Existing SaveUI refresh does not catch errors. Busy/disabled data
            // has no safe snapshot; action methods still throw typed failures.
            if (!EthornellSaveCapability.available) return emptyList()
            return EthornellOperationGate.exclusive {
                try {
                    if (EthornellOperationGate.isSaveBusy(appContext)) emptyList()
                    else ethornellStore(game).listFiles()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    emptyList()
                } catch (_: SecurityException) {
                    emptyList()
                }
            }
        }
        val location = resolveSaveLocation(game)
        val directory = location.directory ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        return buildList {
            collectFiles(directory, this, excludeFor(game.engine), location.mirrorForced)
        }
    }

    /** 存档导出格式：Tyranor 原生 / JoiPlay+PC 标准（仅 RPG Maker MV/MZ 可选，内容字节一致仅换名）。 */
    enum class ExportFormat { TYRANOR, STANDARD }

    @Throws(IOException::class)
    suspend fun exportToZip(
        game: ScanGame,
        destinationUri: Uri,
        format: ExportFormat = ExportFormat.TYRANOR,
    ): Int {
        if (game.engine == EngineType.ETHORNELL) return ethornellOperation(game) { store ->
            store.export {
                appContext.contentResolver.openOutputStream(destinationUri, "w")
                    ?: throw GameSaveException(SaveErrorCode.CREATE_EXPORT_ZIP)
            }
        }
        val location = resolveSaveLocation(game)
        val source = location.directory ?: throw GameSaveException(SaveErrorCode.RESOLVE_SAVE_DIR_FAILED)
        if (!source.isDirectory) throw GameSaveException(SaveErrorCode.NO_EXPORTABLE_FILES)
        val rename = exportRename(game.engine, format)
        val output = appContext.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw GameSaveException(SaveErrorCode.CREATE_EXPORT_ZIP)
        ZipOutputStream(output).use { zip ->
            val entries = mutableSetOf<String>()
            val count = writeZipContents(source, source, zip, entries, excludeFor(game.engine), rename, location.mirrorForced)
            if (count == 0) throw GameSaveException(SaveErrorCode.NO_EXPORTABLE_FILES)
            return count
        }
    }

    /**
     * 导出时的条目重命名规则：仅 RPG Maker MV/MZ 在「标准模式」下把 Tyranor 名换成标准名。
     * 同时反解引擎写入的哈希名（`key_<sha256>.bin`）；无法映射的文件保留原名。
     */
    private fun exportRename(engine: EngineType, format: ExportFormat): ((String) -> String)? {
        if (format != ExportFormat.STANDARD || !RpgSaveFormat.isRpgWebEngine(engine)) return null
        return { name ->
            RpgSaveFormat.tyranorToStandard(name, engine)
                ?: RpgSaveFormat.hashedToStandardName(name, engine)
                ?: name
        }
    }

    @Throws(IOException::class)
    suspend fun importFromZip(game: ScanGame, sourceUri: Uri): Int {
        if (game.engine == EngineType.ETHORNELL) return ethornellOperation(game) { store ->
            store.import {
                appContext.contentResolver.openInputStream(sourceUri)
                    ?: throw GameSaveException(SaveErrorCode.READ_IMPORT_ZIP)
            }
        }
        return synchronized(importLock) {
            // 锁序恒为 importLock → INTEROP_LOCK（sync 只取后者），无死锁环。
            synchronized(RpgSaveSyncState.INTEROP_LOCK) {
                importFromZipLocked(game, sourceUri)
            }
        }
    }

    @Throws(IOException::class)
    private fun importFromZipLocked(game: ScanGame, sourceUri: Uri): Int {
        val location = resolveSaveLocation(game)
        val destination = location.directory ?: throw GameSaveException(SaveErrorCode.RESOLVE_SAVE_DIR_FAILED)

        var temp: File? = null
        try {
            val restoreExcluded: ((File, File) -> Unit)? = if (restoresExcludedFromBackup(game.engine)) {
                { backup, target -> restoreExcludedFromBackup(backup, target, game.engine) }
            } else {
                null
            }
            return importSaveDirectory(destination, excludeFor(game.engine), restoreExcluded) {
                // Recovery completes before extraction creates a cache directory.
                val extractedDirectory = createTemporaryDirectory().also { temp = it }
                if (extractZip(sourceUri, extractedDirectory) == 0) {
                    throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
                }
                unwrapOuterDirs(extractedDirectory, game.engine)
            }
        } finally {
            temp?.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    suspend fun deleteSaves(game: ScanGame): Int {
        if (game.engine == EngineType.ETHORNELL) return ethornellOperation(game) { it.delete() }
        return synchronized(RpgSaveSyncState.INTEROP_LOCK) {
            // 与存档互通同步互斥（H3）：删除与并发同步交错会产生半删状态
            val directory = resolveSaveLocation(game).directory ?: throw GameSaveException(SaveErrorCode.RESOLVE_SAVE_DIR_FAILED)
            val restoreExcluded: ((File, File) -> Unit)? = if (restoresExcludedFromBackup(game.engine)) {
                { backup, target -> restoreExcludedContents(backup, target, excludeFor(game.engine), rejectExisting = true) }
            } else {
                null
            }
            deleteSaveDirectory(directory, excludeFor(game.engine), restoreExcluded)
        }
    }

    /**
     * 删除游戏时清理应用内数据（独立/镜像存档目录），
     * 仅触碰应用专属存储，绝不删除游戏目录内的任何文件。
     */
    fun cleanupAppData(game: ScanGame) {
        if (game.engine == EngineType.ETHORNELL) {
            // Retain on busy, disabled, damaged path, or incomplete recovery.
            // Library removal is not permission to delete an uncertain tree.
            if (!EthornellSaveCapability.available) return
            try {
                EthornellOperationGate.cleanupIfIdle(appContext) { ethornellStore(game).delete() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Best effort cleanup: never redirect or broaden deletion.
            } catch (_: SecurityException) {
                // Permission failure retains the private tree.
            }
            return
        }
        // 块体（审查跟进 #2）：表达式体 + 内部 return 会触发 KTLC-288 前向兼容告警，
        // Kotlin 2.5 起将变为错误
        synchronized(RpgSaveSyncState.INTEROP_LOCK) {
            // 与存档互通同步互斥（H3）：删除独立存档目录与并发同步交错会让同步复制进一个
            // 正被删除的目录。clear/remove 内部同锁，可重入。
            // 存档互通残留必须先清：同步清单（区分「新建」与「已删除」）与待回写登记都以 game.uri
            // 为键。若随游戏删除留下旧清单，同一 uri 的游戏被重新添加后，标准侧存档会被误判为
            // 「Tyranor 侧已删除」而移入 deleted/；待回写记录则会在下次前台时指向已删除的游戏。
            RpgSaveSyncState.forContext(appContext).clear(game.uri)
            RpgSavePendingStore.remove(appContext, game.uri)
            val root = resolveGameDirectory(game) ?: return
            val targets = when (game.engine) {
                EngineType.KIRIKIRI -> {
                    val internal = appContext.filesDir ?: return
                    val targetList = mutableListOf(
                        File(File(internal, "krkr_mirror"), GamePathUtils.safeSaveName(root)),
                    )
                    appContext.getExternalFilesDir(null)?.let { external ->
                        targetList += File(File(external, "save"), GamePathUtils.safeSaveName(root))
                    }
                    if (GamePathUtils.isRemovableStoragePath(root)) {
                        // 可移动存储走 KrSafMirror：清理镜像树与 SAF 索引，避免内部存储持续膨胀；
                        // 根定位失败（锁/IO）不得中断整个清理而遗留孤儿数据（审查第十轮 #4）；
                        // 取消不是失败，必须原样向上传播（本地审计 df6984c P3-3）
                        runCatching { bridge.KrSafMirror.mirrorRootFor(appContext, game.uri, root) }
                            .onSuccess { targetList += it }
                            .onFailure { error ->
                                if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                                android.util.Log.w("GameSaveManager", "mirror root locate failed during cleanup", error)
                            }
                        val mirrorKey = bridge.KrSafMirror.mirrorKey(game.uri, root)
                        targetList += File(
                            File(appContext.noBackupFilesDir, "krkr_saf_index"),
                            "$mirrorKey.idx",
                        )
                        // sticky root 选择标记必须随游戏一起删除，否则同名 key 的旧标记
                        // 会让重新添加的游戏绑定到已删除的目录名（校验失效后重选）
                        targetList += File(
                            File(appContext.noBackupFilesDir, "krkr_saf_index"),
                            "root-$mirrorKey.txt",
                        )
                    }
                    targetList
                }
                EngineType.ONS -> {
                    val external = appContext.getExternalFilesDir(null) ?: return
                    listOf(File(File(external, "save"), File(root).name))
                }
                // System35（xsystem35）：独立存档目录在应用外部存储；独立存档关闭时存档在
                // 游戏目录内，按规范不触碰（不清理游戏目录内文件）。
                EngineType.XSYS35 -> {
                    val external = appContext.getExternalFilesDir(null) ?: return
                    listOf(Xs35Paths.scopedSaveDir(external, root))
                }
                // System4：仅清理独立存档目录（游戏根 SaveFolder 位于游戏目录内，按规范不触碰）
                EngineType.XSYS4 -> {
                    val external = appContext.getExternalFilesDir(null) ?: return
                    listOf(Xs4Paths.scopedSaveDir(external, root))
                }
                EngineType.TYRANO,
                EngineType.RPG_MV,
                EngineType.RPG_MZ -> {
                    val external = appContext.getExternalFilesDir(null) ?: return
                    listOf(File(File(File(external, "save"), "tyrano"), GamePathUtils.safeSaveName(root)))
                }
                else -> return
            }
            val appInternal = appContext.filesDir.canonicalPath + File.separator
            // KrSafMirror 镜像根（games/）与 SAF 索引（no_backup/）位于 filesDir 的父目录（应用数据根）下
            val appDataRoot = appContext.filesDir.parentFile?.canonicalPath?.let { it + File.separator }
            val appExternal = appContext.getExternalFilesDir(null)?.canonicalPath
            targets.forEach { target ->
                val inAppStorage = target.canonicalPath.startsWith(appInternal) ||
                    (appDataRoot != null && target.canonicalPath.startsWith(appDataRoot)) ||
                    (appExternal != null && target.canonicalPath.startsWith(appExternal + File.separator))
                if (inAppStorage) {
                    target.deleteRecursively()
                    // 事务兄弟目录（staging/backup/retained）与目标同级：遗留的旧存档
                    // 不能在删除游戏后继续留在外部存储（审查第十轮 #13）
                    transactionSiblings(target).forEach { it.deleteRecursively() }
                }
            }
        }
    }

    private fun ethornellStore(game: ScanGame) = EthornellSaveStore(appContext.filesDir, game.uri)

    private suspend fun <T> ethornellOperation(game: ScanGame, block: (EthornellSaveStore) -> T): T =
        EthornellOperationGate.exclusive {
            if (!EthornellSaveCapability.available) {
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
            val coroutine = kotlinx.coroutines.currentCoroutineContext()
            try {
                EthornellOperationGate.requireIdle(appContext)
                block(EthornellSaveStore(appContext.filesDir, game.uri, checkCancelled = {
                    coroutine.ensureActive()
                }))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (typed: GameSaveException) {
                throw typed
            } catch (error: IOException) {
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = error)
            } catch (error: SecurityException) {
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = error)
            }
        }

    private fun resolveGameDirectory(game: ScanGame): String? {
        // 与 EngineLauncher.resolveGameDirectory 保持同源：镜像 key 由 (uri, path) 派生，
        // 两边解析出不同路径会让存档管理/清理指向错误镜像。
        GamePathUtils.safUriToPath(game.uri)?.let { path ->
            val f = File(path)
            if (f.isDirectory) return f.absolutePath
            // 可移动存储上的 KRKR 允许仅 SAF 可读（启动器将以镜像模式运行），需解析出同一路径
            if (game.engine == EngineType.KIRIKIRI && GamePathUtils.isRemovableStoragePath(path)) {
                val readableBySaf = runCatching {
                    DocumentFile.fromTreeUri(appContext, Uri.parse(game.uri))?.isDirectory == true
                }.getOrDefault(false)
                if (readableBySaf) return f.absolutePath
            }
        }
        val uri = runCatching { Uri.parse(game.uri) }.getOrNull()
        if (uri?.scheme == "file") return uri.path
        // 兜底：_data 直查（与 EngineLauncher 的第二步一致）
        return try {
            val doc = DocumentFile.fromTreeUri(appContext, uri ?: return null)
            if (doc == null || !doc.exists()) return null
            appContext.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val dataIdx = c.getColumnIndex("_data")
                    if (dataIdx >= 0) c.getString(dataIdx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    private fun extractZip(sourceUri: Uri, destination: File): Int {
        val input = appContext.contentResolver.openInputStream(sourceUri)
            ?: throw GameSaveException(SaveErrorCode.READ_IMPORT_ZIP)
        return extractZip(input, destination)
    }

    /**
     * 把备份目录中不参与导入的 Artemis 引擎资源（system/、*.pfs 等）逐个 rename 回目标目录；
     * 按相对路径递归遍历，嵌套资源如 foo/data.pfs 亦能恢复（copyDirectoryContents 每层均过滤）。
     * 任一失败先把已移回的资源搬回备份、保留备份再抛出，保证任何时刻备份都保有完整的引擎资源副本。
     * 备份本身的删除/停靠统一由 [importSaveDirectory] 决策（审查第十轮 #1/#11）：
     * 这里不再清理，避免在 backupConsumed 判定前销毁可保留的旧存档。
     */
    @Throws(IOException::class)
    private fun restoreExcludedFromBackup(backup: File, destination: File, engine: EngineType) {
        restoreExcludedContents(backup, destination, excludeFor(engine))
    }

    private fun excludeFor(engine: EngineType): (String) -> Boolean = { name ->
        val lower = name.lowercase(Locale.ROOT)
        // MV/MZ 存档目录内的 original/ 是格式转化/互通留底，不参与列表计数/导出/导入/删除
        (RpgSaveFormat.isRpgWebEngine(engine) && lower == RpgSaveFormat.ORIGINAL_DIR) ||
            isTransientTmpName(lower) ||
            (engine == EngineType.ARTEMIS && isArtemisResourceName(name)) ||
            // AVG32/UK2 存档目录即游戏根：白名单之外的文件（含游戏资源）一律排除，
            // 导入交换后由 restoresExcludedFromBackup 从备份移回。
            (engine == EngineType.AVG32 && lower != AVG32_SAVE_FILE_NAME) ||
            (engine == EngineType.UK2 && !UK2_SAVE_NAME_RE.matches(lower))
    }

    /** AVG32 默认存档文件名（`#SAVEFILE` 自定义名暂不识别）。 */
    private val AVG32_SAVE_FILE_NAME = "save.ini"

    /** UK2 存档文件名：引擎落盘为 `<根>/FLAGnn.DAT`（内部名 `flagNN.dat1` 经虚拟扩展名映射）。 */
    private val UK2_SAVE_NAME_RE = Regex("""^flag\d{2}\.dat$""")

    /**
     * 同步/转化的半成品临时文件（`<名>.sync_tmp.<nano>` / `<名>.fmt_tmp.<nano>`），不参与列表/导出/导入。
     * 仅当标记后跟纯数字后缀（System.nanoTime 形态）才判定为临时文件，避免误伤名称中
     * 恰好包含这些子串的合法存档（审查意见：导入时被跳过的合法文件会随目录交换丢失）。
     */
    private fun isTransientTmpName(lower: String): Boolean =
        listOf(".sync_tmp.", ".fmt_tmp.").any { marker ->
            val suffix = lower.substringAfterLast(marker, "")
            suffix.isNotEmpty() && suffix.all(Char::isDigit)
        }

    /**
     * 导入交换后需要从备份移回「被排除项」的引擎：Artemis 引擎资源、AVG32/UK2 的游戏文件
     * （两者存档目录即游戏根）、MV/MZ 的 original/ 留底。
     */
    private fun restoresExcludedFromBackup(engine: EngineType): Boolean =
        engine == EngineType.ARTEMIS ||
            engine == EngineType.AVG32 ||
            engine == EngineType.UK2 ||
            RpgSaveFormat.isRpgWebEngine(engine)

    private fun isArtemisResourceName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.ROOT) ?: return false
        return normalized == "system" || normalized == "movie" ||
            normalized == "artemisengine.exe" || normalized == "system.ini" ||
            normalized.startsWith("root.pfs") || normalized.endsWith(".pfs") ||
            normalized.endsWith(".xp3") || normalized.endsWith(".arc") ||
            normalized.endsWith(".pak") || normalized.endsWith(".dat.arc")
    }

    @Throws(IOException::class)
    private fun createTemporaryDirectory(): File {
        val directory = File.createTempFile("save_zip_", "", appContext.cacheDir)
        if (!directory.delete() || !directory.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_TEMP_DIR)
        return directory
    }

    /** 存档位置描述文案（UI 展示用，非异常协议；P0-5 后续收敛为错误码）。 */
    private fun text(@StringRes id: Int, vararg args: Any): String =
        localizedContext.getString(id, *args)

    companion object {
        /** Restore resources before deleting any saves or transaction recovery copies. */
        internal fun deleteSaveDirectory(
            directory: File,
            exclude: (String) -> Boolean,
            restoreExcluded: ((File, File) -> Unit)? = null,
        ): Int {
            val siblings = transactionSiblings(directory)
            // A failed import may leave the only game assets in any recovery slot.
            // A collision/unreadable tree must fail before destructive save cleanup.
            // Failures here are surfaced as RESOURCE_RESTORE_CONFLICT so the UI can
            // tell "deletion refused to protect game resources" apart from an
            // unavailable save directory (audit of 28eb330f, AF2).
            if (restoreExcluded != null) {
                for (sibling in siblings) {
                    if (!sibling.isDirectory) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
                    try {
                        restoreExcluded(sibling, directory)
                    } catch (error: GameSaveException) {
                        if (error.code == SaveErrorCode.RESOURCE_RESTORE_CONFLICT) throw error
                        throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT, cause = error)
                    } catch (error: IOException) {
                        throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT, cause = error)
                    }
                }
            }
            val deleted = if (directory.isDirectory) clearSaveDirectory(directory, exclude) else 0
            for (sibling in siblings) {
                if (!sibling.deleteRecursively()) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
            return deleted
        }

        private fun clearSaveDirectory(directory: File, exclude: (String) -> Boolean): Int {
            val children = directory.listFiles()
                ?: throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            var deleted = 0
            for (child in children) {
                if (exclude(child.name)) continue
                if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                    deleted += clearSaveDirectory(child, exclude)
                    if (child.listFiles()?.isEmpty() == true && !child.delete()) {
                        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                    }
                } else {
                    if (!child.delete()) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                    deleted++
                }
            }
            return deleted
        }

        internal fun restoreExcludedContents(
            backup: File,
            destination: File,
            exclude: (String) -> Boolean,
            rejectExisting: Boolean = false,
        ) {
            val moved = mutableListOf<Pair<File, File>>()
            try {
                fun restoreRecursive(currentBackup: File, currentDest: File) {
                    // 不可读的备份目录不是「无可恢复」：必须保留备份并报错（#11）
                    val children = currentBackup.listFiles()
                        ?: throw IOException("Backup directory unreadable: " + currentBackup.path)
                    children.forEach { child ->
                        if (exclude(child.name)) {
                            val target = File(currentDest, child.name)
                            if (rejectExisting && target.exists()) throw GameSaveException(SaveErrorCode.RESOURCE_RESTORE_CONFLICT)
                            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR_NAMED, detail = child.name) }
                            if (!child.renameTo(target)) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR_NAMED, detail = child.name)
                            moved += child to target
                        } else if (child.isDirectory) {
                            val subDest = File(currentDest, child.name)
                            restoreRecursive(child, subDest)
                        }
                    }
                }
                restoreRecursive(backup, destination)
            } catch (t: Throwable) {
                moved.forEach { (backupFile, destinationFile) ->
                    runCatching { if (!destinationFile.renameTo(backupFile)) destinationFile.copyRecursively(backupFile, overwrite = true) }
                }
                if (t is kotlinx.coroutines.CancellationException) throw t
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = t)
            }
        }

        /** Import transaction siblings of a save directory (staging/backup/retained copies). */
        private fun transactionSiblings(directory: File): List<File> {
            val parent = directory.parentFile ?: return emptyList()
            return listOf(
                directory.name + ".import_staging",
                directory.name + ".import_backup",
                directory.name + ".import_backup_retained",
                directory.name + ".import_backup_retained1",
                directory.name + ".import_backup_retained2",
            ).map { File(parent, it) }.filter { it.exists() }
        }

        /** Parse the actual import stream independently of Android URI acquisition. */
        @Throws(IOException::class)
        internal fun extractZip(input: InputStream, destination: File): Int {
            return ZipInputStream(input).use { zip ->
                val rootPath = destination.canonicalPath
                val entries = mutableSetOf<String>()
                var extracted = 0
                var totalBytes = 0L
                var entry = zip.nextEntry
                val buffer = ByteArray(BUFFER_SIZE)
                while (entry != null) {
                    val name = safeZipEntryName(entry.name)
                    if (!entries.add(name)) throw GameSaveException(SaveErrorCode.DUPLICATE_ZIP_ENTRY, detail = name)
                    if (entries.size > MAX_SAVE_ZIP_FILES) throw GameSaveException(SaveErrorCode.TOO_MANY_ZIP_FILES)
                    val output = File(destination, name).canonicalFile
                    if (!output.path.startsWith(rootPath + File.separator)) {
                        throw GameSaveException(SaveErrorCode.ILLEGAL_ZIP_PATH, detail = entry.name)
                    }
                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR_NAMED, detail = name)
                    } else {
                        output.parentFile?.let {
                            if (!it.exists() && !it.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR_NAMED, detail = name)
                        }
                        FileOutputStream(output, false).use { out ->
                            var read = zip.read(buffer)
                            while (read != -1) {
                                totalBytes += read.toLong()
                                if (totalBytes > MAX_SAVE_ZIP_BYTES) throw GameSaveException(SaveErrorCode.ZIP_TOO_LARGE)
                                out.write(buffer, 0, read)
                                read = zip.read(buffer)
                            }
                        }
                        if (entry.time > 0L) output.setLastModified(entry.time)
                        extracted++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                extracted
            }
        }

        /** Shared filesystem transaction used by ZIP import; no live save changes before staging passes. */
        @Throws(IOException::class)
        internal fun importSaveDirectory(
            destination: File,
            exclude: (String) -> Boolean,
            restoreExcluded: ((File, File) -> Unit)? = null,
            rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
            sourceProvider: () -> File,
        ): Int {
            val staging = File(destination.parentFile, destination.name + ".import_staging")
            val backup = File(destination.parentFile, destination.name + ".import_backup")
            // Recover an interrupted exchange before making an empty destination. Otherwise
            // the next import could discard the sole remaining copy of the previous saves.
            if (backup.isDirectory) {
                val destinationEmpty = if (destination.isDirectory) {
                    destination.listFiles()?.isEmpty()
                        ?: throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                } else {
                    false
                }
                when {
                    !destination.exists() || destinationEmpty -> {
                        // A launcher may recreate savedata during the exchange window.
                        // Only remove a confirmed empty directory, never user contents.
                        if (destinationEmpty && !destination.delete()) {
                            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                        }
                        if (!rename(backup, destination)) {
                            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                        }
                    }
                    restoreExcluded != null -> {
                        restoreExcluded(backup, destination)
                        // 引擎资源已搬回后，备份里剩余的旧存档同样停靠而不是删除：
                        // 崩溃恢复场景下它是唯一副本（审查第十轮 #1）
                        parkLeftoverBackup(destination, backup, rename)
                    }
                    else -> parkLeftoverBackup(destination, backup, rename)
                }
            }
            if (!destination.exists() && !destination.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR)
            if (!destination.isDirectory) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            var committed = false
            var backupConsumed = false
            try {
                val source = sourceProvider()
                if (staging.exists() && !staging.deleteRecursively()) {
                    throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                }
                if (!staging.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR)
                val copied = stageImportContents(source, staging, exclude)
                // Judge the imported content BEFORE the exchange: for restoreExcluded
                // engines the destination later receives non-empty engine resources,
                // which must not flip an all-empty import into a backup deletion
                // (local audit of df6984c, P2-B).
                val stagedAllEmpty = isDirectoryContentEmpty(staging)
                if (backup.exists() && !backup.deleteRecursively()) {
                    throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                }
                if (!rename(destination, backup)) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                if (!rename(staging, destination)) {
                    // Failed rollback retains backup for the recovery above on the next import.
                    if (backup.exists()) rename(backup, destination)
                    throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                }
                committed = true
                // Artemis resources and RPG Maker interop originals stay excluded from
                // imported saves. Retain backup until all those resources are restored.
                restoreExcluded?.invoke(backup, destination)
                // An all-empty import (the user chose a zero-byte ZIP) replaces a possibly
                // non-empty live directory: keep the previous saves in the backup until the
                // next import resolves it, so an accidental clear stays recoverable on disk.
                backupConsumed = !stagedAllEmpty
                return copied
            } finally {
                staging.deleteRecursively()
                if (committed && backupConsumed) backup.deleteRecursively()
            }
        }

        /**
         * A leftover backup with a non-empty destination means gameplay already wrote new
         * saves after an interrupted exchange: the backup is the only copy of the previous
         * saves and must never be destroyed to free the exchange slot. Park it under a
         * retained name (bounded slots) instead of deleting it.
         */
        private fun parkLeftoverBackup(
            destination: File,
            backup: File,
            rename: (File, File) -> Boolean,
        ) {
            val parent = destination.parentFile ?: throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            val slots = listOf(
                destination.name + ".import_backup_retained",
                destination.name + ".import_backup_retained1",
                destination.name + ".import_backup_retained2",
            )
            for (suffix in slots) {
                val target = File(parent, suffix)
                if (!target.exists() && rename(backup, target)) return
            }
            // All retention slots are taken: the oldest retained copy is superseded by
            // two newer unique copies, so reusing its slot keeps the mechanism bounded.
            val oldest = File(parent, slots[0])
            if (!oldest.deleteRecursively() || !rename(backup, oldest)) {
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
        }

        /** True when [directory] holds at least one regular file and all of them are 0 bytes. */
        private fun isDirectoryContentEmpty(directory: File): Boolean {
            var sawFile = false
            directory.walkTopDown().forEach { file ->
                if (file.isFile) {
                    sawFile = true
                    if (file.length() > 0L) return false
                }
            }
            return sawFile
        }

        /** Filter and count imported saves before the caller can exchange the live directory. */
        internal fun stageImportContents(
            source: File,
            staging: File,
            exclude: (String) -> Boolean,
        ): Int {
            // The import source is a user-provided ZIP, not the mirror target: the
            // mirrorForced 0-byte filter (a legacy-placeholder rule for list/export of
            // the mirror savedata) must not silently drop legitimate empty files here.
            val copied = copyDirectoryContents(source, staging, exclude)
            if (copied == 0) throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
            return copied
        }

        @Throws(IOException::class)
        private fun copyDirectoryContents(
            source: File,
            destination: File,
            exclude: (String) -> Boolean,
        ): Int {
            var copied = 0
            source.listFiles().orEmpty().forEach { child ->
                if (exclude(child.name)) return@forEach
                val target = File(destination, child.name)
                if (child.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR_NAMED, detail = child.name)
                    copied += copyDirectoryContents(child, target, exclude)
                } else if (child.isFile) {
                    target.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR_NAMED, detail = child.name)
                    }
                    child.copyTo(target, overwrite = true)
                    target.setLastModified(child.lastModified())
                    copied++
                }
            }
            return copied
        }

        internal fun collectFiles(
            directory: File,
            out: MutableList<File>,
            exclude: (String) -> Boolean,
            mirrorForced: Boolean = false,
        ) {
            directory.listFiles().orEmpty().forEach { child ->
                if (exclude(child.name)) return@forEach
                when {
                    child.isDirectory -> collectFiles(child, out, exclude, mirrorForced)
                    child.isFile && includesSaveFile(child, mirrorForced) -> out.add(child)
                }
            }
        }

        @Throws(IOException::class)
        internal fun writeZipContents(
            root: File,
            directory: File,
            zip: ZipOutputStream,
            entries: MutableSet<String>,
            exclude: (String) -> Boolean,
            rename: ((String) -> String)? = null,
            mirrorForced: Boolean = false,
        ): Int {
            var written = 0
            directory.listFiles().orEmpty().forEach { child ->
                if (exclude(child.name)) return@forEach
                if (child.isDirectory) {
                    written += writeZipContents(root, child, zip, entries, exclude, rename, mirrorForced)
                } else if (child.isFile && includesSaveFile(child, mirrorForced)) {
                    val relative = root.toPath().relativize(child.toPath()).toString()
                        .replace(File.separatorChar, '/')
                    val renamedName = rename?.let { fn ->
                        val parts = relative.split('/')
                        safeZipEntryName(parts.dropLast(1).plus(fn(parts.last())).joinToString("/"))
                    }
                    // 换名后若与已有条目重名，回退原名，避免整条备份被静默丢弃
                    val safeName = if (renamedName != null && renamedName !in entries) renamedName else safeZipEntryName(relative)
                    if (!entries.add(safeName)) return@forEach
                    zip.putNextEntry(ZipEntry(safeName).apply { time = child.lastModified() })
                    FileInputStream(child).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    written++
                }
            }
            return written
        }

        // Only mirror-forced KRKR saves can contain legacy empty placeholders. Other
        // engines may use legitimate empty files; list and export share this rule.
        private fun includesSaveFile(file: File, mirrorForced: Boolean): Boolean =
            !mirrorForced || file.length() > 0L

        @Throws(IOException::class)
        private fun safeZipEntryName(raw: String?): String {
            val name = raw?.replace('\\', '/')?.trim('/').orEmpty()
            if (name.isBlank() || name.startsWith("/") || name.contains("../")) {
                throw GameSaveException(SaveErrorCode.ILLEGAL_ZIP_PATH, detail = raw.orEmpty())
            }
            return name
        }

        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_SAVE_ZIP_FILES = 20_000
        private const val MAX_SAVE_ZIP_BYTES = 1024L * 1024L * 1024L

        // 导入互斥锁：UI 层的 taskRunning 守卫会随 Activity 重建丢失（旋转屏幕时
        // 旧协程的阻塞 IO 仍在后台跑完），进程级锁保证不会对同一存档并发导入
        private val importLock = Any()

        /**
         * 剥掉导入包的「外层文件夹」包装：外部存档备份常整包为一层或多层目录
         * （如 `save/`、`Save/`、`savedata/`，或 `www/save/`），直接复制到存档目录会多套
         * 一层（`savedata/save/`）导致引擎读不到。只要当前目录「仅含一个子目录」就继续
         * 下钻（上限 [MAX_UNWRAP_DEPTH] 层）；含散文件或多个条目时视为已是内容根、原样返回。
         * 下钻后若无可复制内容，由调用方以 `copied == 0` 拦截，不会清空旧存档。
         *
         * 语义目录绝不剥离：若唯一子目录是 `original/`（转化留底）或 `deleted/`（删除归置），
         * 停止下钻——否则会进入该目录内部，绕过 excludeFor 对目录名的过滤，把历史留底/
         * 已归置文件当成活动存档复制，用旧备份覆盖当前存档。
         *
         * 仅对 RPG Maker MV/MZ 生效：其它引擎的顶层目录（如 `system/`、插件数据目录）可能
         * 带语义，剥离会改变文件结构；非 RPG 引擎原样返回 [extracted]。
         */
        internal fun unwrapOuterDirs(extracted: File, engine: EngineType): File {
            if (!RpgSaveFormat.isRpgWebEngine(engine)) return extracted
            var current = extracted
            var depth = 0
            while (depth < MAX_UNWRAP_DEPTH) {
                val only = current.listFiles().orEmpty().singleOrNull() ?: return current
                if (!only.isDirectory) return current
                if (isSemanticSaveDirName(only.name)) return current
                current = only
                depth++
            }
            return current
        }

        /** 存档目录内的语义子目录名（互通留底/删除归置），导入剥壳时不得穿越。 */
        private fun isSemanticSaveDirName(name: String): Boolean =
            name.equals(RpgSaveFormat.ORIGINAL_DIR, ignoreCase = true) ||
                name.equals(RpgSaveSync.DELETED_DIR, ignoreCase = true)

        private const val MAX_UNWRAP_DEPTH = 3
    }
}
