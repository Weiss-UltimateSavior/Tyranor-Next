package com.tyranor.next.core.game.save

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.EngineSettingsResolver
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.core.settings.EngineSettingsStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
    )

    fun resolveSaveLocation(game: ScanGame): SaveLocation {
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
                        File(bridge.KrSafMirror.mirrorRootFor(appContext, game.uri, root, game.title), "savedata"),
                        text(R.string.save_location_krkr_sd_mirror),
                        true,
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
            EngineType.VN, EngineType.WEB_OTHER, EngineType.RPGMAKER, EngineType.RENPY ->
                SaveLocation(null, text(R.string.save_location_engine_no_file_interface, game.engine.displayName), false)
            EngineType.ARTEMIS -> SaveLocation(File(root), text(R.string.save_location_artemis_game_dir), true)
            EngineType.UNKNOWN -> SaveLocation(null, text(R.string.save_location_unknown_unsupported), false)
        }
    }

    /**
     * Tyrano 家族（Tyrano/MV/MZ）的有效存档目录：独立存档开关开启时为外部私有目录
     * （需要外部存储可用，不可用时返回 null），否则 `<游戏根>/savedata`。
     * 与 engine 宿主 resolveSaveDirectory 语义一致；存档互通同步复用，避免路径漂移。
     */
    fun effectiveTyranoFamilySaveDirectory(gameId: String, root: String, scoped: Boolean): File? {
        if (!scoped) return File(root, "savedata")
        val external = appContext.getExternalFilesDir(null) ?: return null
        return File(File(File(external, "save"), "tyrano"), GamePathUtils.safeSaveName(root))
    }

    /** Tyrano 家族独立存档开关：单游戏覆盖优先，否则全局。 */
    fun isTyranoFamilyScoped(gameId: String): Boolean =
        PerGameSettingsStore.getBool(appContext, gameId, PerGameSettingsStore.F_TY_SCOPED)
            ?: EngineSettingsStore.isTyranoScopedSaveDir(appContext)

    fun listSaveFiles(game: ScanGame): List<File> {
        val directory = resolveSaveLocation(game).directory ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        return buildList {
            collectFiles(directory, this, excludeFor(game.engine))
        }
    }

    /** 存档导出格式：Tyranor 原生 / JoiPlay+PC 标准（仅 RPG Maker MV/MZ 可选，内容字节一致仅换名）。 */
    enum class ExportFormat { TYRANOR, STANDARD }

    @Throws(IOException::class)
    fun exportToZip(
        game: ScanGame,
        destinationUri: Uri,
        format: ExportFormat = ExportFormat.TYRANOR,
    ): Int {
        val location = resolveSaveLocation(game)
        val source = location.directory ?: throw GameSaveException(SaveErrorCode.RESOLVE_SAVE_DIR_FAILED)
        if (!source.isDirectory) throw GameSaveException(SaveErrorCode.NO_EXPORTABLE_FILES)
        val rename = exportRename(game.engine, format)
        val output = appContext.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw GameSaveException(SaveErrorCode.CREATE_EXPORT_ZIP)
        ZipOutputStream(output).use { zip ->
            val entries = mutableSetOf<String>()
            val count = writeZipContents(source, source, zip, entries, excludeFor(game.engine), rename)
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
    fun importFromZip(game: ScanGame, sourceUri: Uri): Int = synchronized(importLock) {
        val destination = resolveSaveLocation(game).directory ?: throw GameSaveException(SaveErrorCode.RESOLVE_SAVE_DIR_FAILED)

        // 与目标目录同文件系统的暂存/备份目录：解压+复制阶段完全不触碰原存档；
        // 提交阶段用两次 rename 原子交换（旧目录改名留作备份 → 暂存目录顶替），
        // 任一步失败即从备份回滚。Artemis 的引擎资源在交换完成后再从备份移回，
        // 移回全部成功才清理备份——任何失败路径下备份都保有完整恢复数据。
        val staging = File(destination.parentFile, destination.name + ".import_staging")
        val backup = File(destination.parentFile, destination.name + ".import_backup")
        // 上次导入的遗留备份恢复：必须在创建 destination 前完成，否则前一次
        // 在 destination.renameTo(backup) 与 staging.renameTo(destination) 之间
        // 中断会导致 destination 缺失；若先 mkdirs 会得到空目录并跳过恢复，
        // 随后提交阶段又删除仍含旧存档的 backup，造成永久丢档。
        // Artemis 资源移回中断则逐个移回剩余资源。恢复完成备份里只剩可丢弃的旧存档。
        // 注意 temp 延后创建：备份恢复抛异常时不泄漏 cacheDir 临时目录
        if (backup.isDirectory) {
            when {
                !destination.exists() ->
                    if (!backup.renameTo(destination)) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                restoresExcludedFromBackup(game.engine) ->
                    restoreExcludedFromBackup(backup, destination, game.engine)
            }
        }
        if (!destination.exists() && !destination.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR)
        if (!destination.isDirectory) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
        val temp = createTemporaryDirectory()
        var committed = false
        var backupConsumed = false
        try {
            val extracted = extractZip(sourceUri, temp)
            if (extracted == 0) throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw GameSaveException(SaveErrorCode.CREATE_SAVE_DIR)
            // 过滤引擎资源后可能一件存档都没有（如纯资源 ZIP）：必须在交换前拦截，
            // 否则会用空目录顶替目标并删掉备份，旧存档全部丢失
            // 外部备份包常整包为多层外层文件夹（save/、Save/、www/save/ 等），
            // 需先剥掉再复制，否则会落成 savedata/save/... 多套一层导致引擎读不到。
            val source = unwrapOuterDirs(temp)
            val copied = copyDirectoryContents(source, staging, excludeFor(game.engine))
            if (copied == 0) throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
            if (destination.exists()) {
                // 走到这里备份必已被开头恢复步骤消费（只剩旧存档或不存在），可安全删除
                if (backup.exists() && !backup.deleteRecursively()) {
                    throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                }
                if (!destination.renameTo(backup)) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
            committed = true
            // Artemis 目标即游戏根：被排除的引擎资源（system/、*.pfs 等）不参与导入，
            // 交换后从备份移回；全部移回前备份绝不清理，失败可再次恢复
            // Artemis 引擎资源、MV/MZ 的 original/（互通留底）被排除在导入之外，
            // 交换后从备份移回；全部移回前备份绝不清理，失败可再次恢复
            if (restoresExcludedFromBackup(game.engine)) {
                restoreExcludedFromBackup(backup, destination, game.engine)
            }
            backupConsumed = true
            return copied
        } finally {
            temp.deleteRecursively()
            staging.deleteRecursively()
            // 仅在交换成功且备份内容已消费完毕后清理备份；否则保留备份数据供下次恢复
            if (committed && backupConsumed) backup.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    fun deleteSaves(game: ScanGame): Int {
        val directory = resolveSaveLocation(game).directory ?: throw GameSaveException(SaveErrorCode.RESOLVE_SAVE_DIR_FAILED)
        if (!directory.isDirectory) return 0
        return clearSaveDirectory(directory, game.engine)
    }

    /**
     * 删除游戏时清理应用内数据（独立/镜像存档目录），
     * 仅触碰应用专属存储，绝不删除游戏目录内的任何文件。
     */
    fun cleanupAppData(game: ScanGame) {
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
                    // 可移动存储走 KrSafMirror：清理镜像树与 SAF 索引，避免内部存储持续膨胀
                    targetList += bridge.KrSafMirror.mirrorRootFor(appContext, game.uri, root, game.title)
                    targetList += File(
                        File(appContext.noBackupFilesDir, "krkr_saf_index"),
                        "${bridge.KrSafMirror.mirrorKey(game.uri, root)}.idx",
                    )
                }
                targetList
            }
            EngineType.ONS -> {
                val external = appContext.getExternalFilesDir(null) ?: return
                listOf(File(File(external, "save"), File(root).name))
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
            if (inAppStorage) target.deleteRecursively()
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

    private fun collectFiles(directory: File, out: MutableList<File>, exclude: (String) -> Boolean) {
        directory.listFiles().orEmpty().forEach { child ->
            if (exclude(child.name)) return@forEach
            when {
                child.isDirectory -> collectFiles(child, out, exclude)
                child.isFile -> out.add(child)
            }
        }
    }

    @Throws(IOException::class)
    private fun writeZipContents(
        root: File,
        directory: File,
        zip: ZipOutputStream,
        entries: MutableSet<String>,
        exclude: (String) -> Boolean,
        rename: ((String) -> String)? = null,
    ): Int {
        var written = 0
        directory.listFiles().orEmpty().forEach { child ->
            if (exclude(child.name)) return@forEach
            if (child.isDirectory) {
                written += writeZipContents(root, child, zip, entries, exclude, rename)
            } else if (child.isFile) {
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

    @Throws(IOException::class)
    private fun extractZip(sourceUri: Uri, destination: File): Int {
        val rootPath = destination.canonicalPath
        val entries = mutableSetOf<String>()
        var extracted = 0
        var totalBytes = 0L
        val input = appContext.contentResolver.openInputStream(sourceUri)
            ?: throw GameSaveException(SaveErrorCode.READ_IMPORT_ZIP)
        ZipInputStream(input).use { zip ->
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
        }
        return extracted
    }

    @Throws(IOException::class)
    private fun copyDirectoryContents(source: File, destination: File, exclude: (String) -> Boolean): Int {
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

    /**
     * 把备份目录中不参与导入的 Artemis 引擎资源（system/、*.pfs 等）逐个 rename 回目标目录；
     * 按相对路径递归遍历，嵌套资源如 foo/data.pfs 亦能恢复（copyDirectoryContents 每层均过滤）。
     * 全部成功后清理备份并返回，任一失败先把已移回的资源搬回备份、保留备份再抛出，
     * 保证任何时刻备份都保有完整的引擎资源副本。
     */
    @Throws(IOException::class)
    private fun restoreExcludedFromBackup(backup: File, destination: File, engine: EngineType) {
        val exclude = excludeFor(engine)
        val moved = mutableListOf<Pair<File, File>>()
        try {
            fun restoreRecursive(currentBackup: File, currentDest: File) {
                currentBackup.listFiles().orEmpty().forEach { child ->
                    if (exclude(child.name)) {
                        val target = File(currentDest, child.name)
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
            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = t)
        }
        backup.deleteRecursively()
    }

    private fun clearSaveDirectory(directory: File, engine: EngineType): Int {
        var deleted = 0
        val exclude = excludeFor(engine)
        directory.listFiles().orEmpty().forEach { child ->
            if (exclude(child.name)) return@forEach
            if (child.deleteRecursively()) deleted++
        }
        return deleted
    }

    @Throws(IOException::class)
    private fun safeZipEntryName(raw: String?): String {
        val name = raw?.replace('\\', '/')?.trim('/').orEmpty()
        if (name.isBlank() || name.startsWith("/") || name.contains("../")) {
            throw GameSaveException(SaveErrorCode.ILLEGAL_ZIP_PATH, detail = raw.orEmpty())
        }
        return name
    }

    private fun excludeFor(engine: EngineType): (String) -> Boolean = { name ->
        val lower = name.lowercase(Locale.ROOT)
        // MV/MZ 存档目录内的 original/ 是格式转化/互通留底，不参与列表计数/导出/导入/删除
        (RpgSaveFormat.isRpgWebEngine(engine) && lower == RpgSaveFormat.ORIGINAL_DIR) ||
            (engine == EngineType.ARTEMIS && isArtemisResourceName(name))
    }

    /** 导入交换后需要从备份移回「被排除项」的引擎：Artemis 引擎资源、MV/MZ 的 original/ 留底。 */
    private fun restoresExcludedFromBackup(engine: EngineType): Boolean =
        engine == EngineType.ARTEMIS || RpgSaveFormat.isRpgWebEngine(engine)

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
         */
        internal fun unwrapOuterDirs(extracted: File): File {
            var current = extracted
            var depth = 0
            while (depth < MAX_UNWRAP_DEPTH) {
                val only = current.listFiles().orEmpty().singleOrNull() ?: return current
                if (!only.isDirectory) return current
                current = only
                depth++
            }
            return current
        }

        private const val MAX_UNWRAP_DEPTH = 3
    }
}
