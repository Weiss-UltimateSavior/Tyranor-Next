package com.tyranor.next.core.game.save

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
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
    fun importFromZip(game: ScanGame, sourceUri: Uri): Int {
        val destination = resolveSaveLocation(game).directory ?: throw IOException(text(R.string.save_error_resolve_actual_dir))
        if (!destination.exists() && !destination.mkdirs()) throw IOException(text(R.string.save_error_create_save_dir))
        if (!destination.isDirectory) throw IOException(text(R.string.save_error_save_dir_unavailable))

        val temp = createTemporaryDirectory()
        try {
            val extracted = extractZip(sourceUri, temp)
            if (extracted == 0) throw IOException(text(R.string.save_error_no_files_in_zip))
            clearSaveDirectory(destination, game.engine)
            copyDirectoryContents(temp, destination, excludeFor(game.engine))
            return extracted
        } finally {
            temp.deleteRecursively()
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
        val appExternal = appContext.getExternalFilesDir(null)?.canonicalPath
        targets.forEach { target ->
            val inAppStorage = target.canonicalPath.startsWith(appInternal) ||
                (appExternal != null && target.canonicalPath.startsWith(appExternal + File.separator))
            if (inAppStorage) target.deleteRecursively()
        }
    }

    private fun resolveGameDirectory(game: ScanGame): String? {
        EngineScanner.safUriToPath(game.uri)?.let { path ->
            if (File(path).isDirectory) return File(path).absolutePath
        }
        val uri = runCatching { Uri.parse(game.uri) }.getOrNull()
        if (uri?.scheme.equals("file", ignoreCase = true)) return uri?.path
        return null
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
    }
}
