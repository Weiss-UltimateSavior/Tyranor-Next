package com.tyranor.next.core.game.save

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
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

        return when (game.engine) {
            EngineType.KIRIKIRI -> {
                // 可移动存储上启动器强制走 KrSafMirror 镜像（与独立存档开关无关），
                // 引擎实际读写的是镜像目录内的 savedata，存档管理必须指向同一处。
                if (EngineScanner.isRemovableStoragePath(root)) {
                    return SaveLocation(
                        File(bridge.KrSafMirror.mirrorRootFor(appContext, game.uri, root, game.title), "savedata"),
                        text(R.string.save_location_krkr_sd_mirror),
                        true,
                    )
                }
                val scoped = PerGameSettingsStore.getBool(appContext, game.uri, PerGameSettingsStore.F_SCOPED_SAVE_DIR)
                    ?: EngineSettingsStore.isKrScopedSaveDir(appContext)
                if (scoped) {
                    if (effectiveKrKernel(game, root) == EngineSettingsStore.KERNEL_KRKRSDL3) {
                        val external = appContext.getExternalFilesDir(null)
                            ?: return SaveLocation(null, text(R.string.save_error_krkr_sdl3_external_unavailable), false)
                        SaveLocation(
                            File(File(external, "save"), EngineScanner.safeSaveName(root)),
                            text(R.string.save_location_krkr_sdl3_scoped),
                            true,
                        )
                    } else {
                        val internal = appContext.filesDir
                            ?: return SaveLocation(null, text(R.string.save_error_app_internal_unavailable), false)
                        SaveLocation(
                            File(File(File(internal, "krkr_mirror"), EngineScanner.safeSaveName(root)), "savedata"),
                            text(R.string.save_location_krkr_scoped),
                            true,
                        )
                    }
                } else {
                    SaveLocation(File(root, "savedata"), text(R.string.save_location_krkr_game_dir), true)
                }
            }
            EngineType.ONS -> {
                val scoped = effectiveOnsScoped(game)
                if (scoped) {
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
                val scoped = PerGameSettingsStore.getBool(appContext, game.uri, PerGameSettingsStore.F_TY_SCOPED)
                    ?: EngineSettingsStore.isTyranoScopedSaveDir(appContext)
                if (scoped) {
                    val external = appContext.getExternalFilesDir(null)
                        ?: return SaveLocation(null, text(R.string.save_error_tyrano_external_unavailable), false)
                    SaveLocation(
                        File(File(File(external, "save"), "tyrano"), EngineScanner.safeSaveName(root)),
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

    fun listSaveFiles(game: ScanGame): List<File> {
        val directory = resolveSaveLocation(game).directory ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        return buildList {
            collectFiles(directory, this, excludeFor(game.engine))
        }
    }

    @Throws(IOException::class)
    fun exportToZip(game: ScanGame, destinationUri: Uri): Int {
        val location = resolveSaveLocation(game)
        val source = location.directory ?: throw IOException(location.description)
        if (!source.isDirectory) throw IOException(text(R.string.save_error_no_exportable_files))
        val output = appContext.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException(text(R.string.save_error_create_export_zip))
        ZipOutputStream(output).use { zip ->
            val entries = mutableSetOf<String>()
            val count = writeZipContents(source, source, zip, entries, excludeFor(game.engine))
            if (count == 0) throw IOException(text(R.string.save_error_no_exportable_files))
            return count
        }
    }

    @Throws(IOException::class)
    fun importFromZip(game: ScanGame, sourceUri: Uri): Int = synchronized(importLock) {
        val destination = resolveSaveLocation(game).directory ?: throw IOException(text(R.string.save_error_resolve_actual_dir))
        if (!destination.exists() && !destination.mkdirs()) throw IOException(text(R.string.save_error_create_save_dir))
        if (!destination.isDirectory) throw IOException(text(R.string.save_error_save_dir_unavailable))

        val temp = createTemporaryDirectory()
        // 与目标目录同文件系统的暂存/备份目录：解压+复制阶段完全不触碰原存档；
        // 提交阶段用两次 rename 原子交换（旧目录改名留作备份 → 暂存目录顶替），
        // 任一步失败即从备份回滚。Artemis 的引擎资源在交换完成后再从备份移回，
        // 移回全部成功才清理备份——任何失败路径下备份都保有完整恢复数据。
        val staging = File(destination.parentFile, destination.name + ".import_staging")
        val backup = File(destination.parentFile, destination.name + ".import_backup")
        // 上次导入的遗留备份恢复：目标缺失（rename 间隙被杀）整体还原；
        // Artemis 资源移回中断则逐个移回剩余资源。恢复完成备份里只剩可丢弃的旧存档。
        if (backup.isDirectory) {
            when {
                !destination.exists() ->
                    if (!backup.renameTo(destination)) throw IOException(text(R.string.save_error_save_dir_unavailable))
                game.engine == EngineType.ARTEMIS ->
                    restoreExcludedFromBackup(backup, destination, game.engine)
            }
        }
        var committed = false
        var backupConsumed = false
        try {
            val extracted = extractZip(sourceUri, temp)
            if (extracted == 0) throw IOException(text(R.string.save_error_no_files_in_zip))
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException(text(R.string.save_error_create_save_dir))
            // 过滤引擎资源后可能一件存档都没有（如纯资源 ZIP）：必须在交换前拦截，
            // 否则会用空目录顶替目标并删掉备份，旧存档全部丢失
            val copied = copyDirectoryContents(temp, staging, excludeFor(game.engine))
            if (copied == 0) throw IOException(text(R.string.save_error_no_files_in_zip))
            if (destination.exists()) {
                // 走到这里备份必已被开头恢复步骤消费（只剩旧存档或不存在），可安全删除
                if (backup.exists() && !backup.deleteRecursively()) {
                    throw IOException(text(R.string.save_error_save_dir_unavailable))
                }
                if (!destination.renameTo(backup)) throw IOException(text(R.string.save_error_save_dir_unavailable))
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw IOException(text(R.string.save_error_save_dir_unavailable))
            }
            committed = true
            // Artemis 目标即游戏根：被排除的引擎资源（system/、*.pfs 等）不参与导入，
            // 交换后从备份移回；全部移回前备份绝不清理，失败可再次恢复
            if (game.engine == EngineType.ARTEMIS) {
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
        val directory = resolveSaveLocation(game).directory ?: throw IOException(text(R.string.save_error_resolve_actual_dir))
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
                    File(File(internal, "krkr_mirror"), EngineScanner.safeSaveName(root)),
                )
                appContext.getExternalFilesDir(null)?.let { external ->
                    targetList += File(File(external, "save"), EngineScanner.safeSaveName(root))
                }
                if (EngineScanner.isRemovableStoragePath(root)) {
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
                listOf(File(File(File(external, "save"), "tyrano"), EngineScanner.safeSaveName(root)))
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
        EngineScanner.safUriToPath(game.uri)?.let { path ->
            val f = File(path)
            if (f.isDirectory) return f.absolutePath
            // 可移动存储上的 KRKR 允许仅 SAF 可读（启动器将以镜像模式运行），需解析出同一路径
            if (game.engine == EngineType.KIRIKIRI && EngineScanner.isRemovableStoragePath(path)) {
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

    private fun effectiveOnsScoped(game: ScanGame): Boolean {
        var ons = EngineSettingsStore.loadOns(appContext)
        PerGameSettingsStore.loadOnsOverride(appContext, game.uri)?.let { override ->
            if (override.has("scopedsavedir")) ons = ons.copy(scopedSaveDir = override.optBoolean("scopedsavedir"))
        }
        return ons.scopedSaveDir
    }

    private fun effectiveKrKernel(game: ScanGame, root: String): String {
        val requested = PerGameSettingsStore.getStr(appContext, game.uri, PerGameSettingsStore.F_ENGINE_KERNEL)
            ?: EngineSettingsStore.getKrKernel(appContext)
        return if (EngineScanner.isRemovableStoragePath(root) && requested == EngineSettingsStore.KERNEL_KRKRSDL3) {
            EngineSettingsStore.KERNEL_KIRIKIRI2
        } else {
            requested
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
    ): Int {
        var written = 0
        directory.listFiles().orEmpty().forEach { child ->
            if (exclude(child.name)) return@forEach
            if (child.isDirectory) {
                written += writeZipContents(root, child, zip, entries, exclude)
            } else if (child.isFile) {
                val relative = root.toPath().relativize(child.toPath()).toString()
                    .replace(File.separatorChar, '/')
                val safeName = safeZipEntryName(relative)
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
            ?: throw IOException(text(R.string.save_error_read_import_zip))
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(BUFFER_SIZE)
            while (entry != null) {
                val name = safeZipEntryName(entry.name)
                if (!entries.add(name)) throw IOException(text(R.string.save_error_duplicate_zip_entry, name))
                if (entries.size > MAX_SAVE_ZIP_FILES) throw IOException(text(R.string.save_error_too_many_zip_files))
                val output = File(destination, name).canonicalFile
                if (!output.path.startsWith(rootPath + File.separator)) {
                    throw IOException(text(R.string.save_error_illegal_zip_path, entry.name))
                }
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) throw IOException(text(R.string.save_error_create_save_dir_named, name))
                } else {
                    output.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) throw IOException(text(R.string.save_error_create_save_dir_named, name))
                    }
                    FileOutputStream(output, false).use { out ->
                        var read = zip.read(buffer)
                        while (read != -1) {
                            totalBytes += read.toLong()
                            if (totalBytes > MAX_SAVE_ZIP_BYTES) throw IOException(text(R.string.save_error_zip_too_large))
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
                if (!target.exists() && !target.mkdirs()) throw IOException(text(R.string.save_error_create_save_dir_named, child.name))
                copied += copyDirectoryContents(child, target, exclude)
            } else if (child.isFile) {
                target.parentFile?.let {
                    if (!it.exists() && !it.mkdirs()) throw IOException(text(R.string.save_error_create_save_dir_named, child.name))
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
     * 全部成功后清理备份并返回，任一失败先把已移回的资源搬回备份、保留备份再抛出，
     * 保证任何时刻备份都保有完整的引擎资源副本。
     */
    @Throws(IOException::class)
    private fun restoreExcludedFromBackup(backup: File, destination: File, engine: EngineType) {
        val exclude = excludeFor(engine)
        val resources = backup.listFiles().orEmpty().filter { exclude(it.name) }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            resources.forEach { resource ->
                val target = File(destination, resource.name)
                if (!resource.renameTo(target)) throw IOException(text(R.string.save_error_create_save_dir_named, resource.name))
                moved += resource to target
            }
        } catch (t: Throwable) {
            moved.forEach { (backupFile, destinationFile) ->
                runCatching { if (!destinationFile.renameTo(backupFile)) destinationFile.copyRecursively(backupFile, overwrite = true) }
            }
            throw IOException(text(R.string.save_error_save_dir_unavailable), t)
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
            throw IOException(text(R.string.save_error_illegal_zip_path, raw.orEmpty()))
        }
        return name
    }

    private fun excludeFor(engine: EngineType): (String) -> Boolean = { name ->
        engine == EngineType.ARTEMIS && isArtemisResourceName(name)
    }

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
        if (!directory.delete() || !directory.mkdirs()) throw IOException(text(R.string.save_error_create_temp_dir))
        return directory
    }

    private fun text(@StringRes id: Int, vararg args: Any): String =
        localizedContext.getString(id, *args)

    companion object {
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_SAVE_ZIP_FILES = 20_000
        private const val MAX_SAVE_ZIP_BYTES = 1024L * 1024L * 1024L

        // 导入互斥锁：UI 层的 taskRunning 守卫会随 Activity 重建丢失（旋转屏幕时
        // 旧协程的阻塞 IO 仍在后台跑完），进程级锁保证不会对同一存档并发导入
        private val importLock = Any()
    }
}
