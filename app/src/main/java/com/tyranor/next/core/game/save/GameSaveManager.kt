package com.tyranor.next.core.game.save

import android.content.Context
import android.net.Uri
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
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

    data class SaveLocation(
        val directory: File?,
        val description: String,
        val available: Boolean,
    )

    fun resolveSaveLocation(game: ScanGame): SaveLocation {
        val root = resolveGameDirectory(game)
            ?: return SaveLocation(null, "无法解析游戏本地目录", false)

        return when (game.engine) {
            EngineType.KIRIKIRI -> {
                val scoped = PerGameSettingsStore.getBool(appContext, game.uri, PerGameSettingsStore.F_SCOPED_SAVE_DIR)
                    ?: EngineSettingsStore.isKrScopedSaveDir(appContext)
                if (scoped) {
                    if (effectiveKrKernel(game, root) == EngineSettingsStore.KERNEL_KRKRSDL3) {
                        val external = appContext.getExternalFilesDir(null)
                            ?: return SaveLocation(null, "KRKR SDL3 应用独立存储目录不可用", false)
                        SaveLocation(
                            File(File(external, "save"), EngineScanner.safeSaveName(root)),
                            "KRKR SDL3 独立存档目录",
                            true,
                        )
                    } else {
                        val internal = appContext.filesDir
                            ?: return SaveLocation(null, "应用内部存储目录不可用", false)
                        SaveLocation(
                            File(File(File(internal, "krkr_mirror"), EngineScanner.safeSaveName(root)), "savedata"),
                            "KRKR 独立存档目录",
                            true,
                        )
                    }
                } else {
                    SaveLocation(File(root, "savedata"), "KRKR 游戏目录存档", true)
                }
            }
            EngineType.ONS -> {
                val scoped = effectiveOnsScoped(game)
                if (scoped) {
                    val external = appContext.getExternalFilesDir(null)
                        ?: return SaveLocation(null, "ONS 应用独立存储目录不可用", false)
                    SaveLocation(File(File(external, "save"), File(root).name), "ONS 应用独立存档目录", true)
                } else {
                    SaveLocation(File(root, "save"), "ONS 游戏内存档目录", true)
                }
            }
            EngineType.TYRANO,
            EngineType.RPG_MV,
            EngineType.RPG_MZ -> {
                // Tyrano 与 RPG Maker Web 共用 TyranoActivity，存档目录开关保持同一套配置。
                val scoped = PerGameSettingsStore.getBool(appContext, game.uri, "ty_scoped")
                    ?: EngineSettingsStore.isTyranoScopedSaveDir(appContext)
                if (scoped) {
                    val external = appContext.getExternalFilesDir(null)
                        ?: return SaveLocation(null, "Tyrano 应用独立存储目录不可用", false)
                    SaveLocation(
                        File(File(File(external, "save"), "tyrano"), EngineScanner.safeSaveName(root)),
                        "${game.engine.displayName} 应用独立存档目录",
                        true,
                    )
                } else {
                    SaveLocation(
                        File(root, "savedata"),
                        "${game.engine.displayName} 游戏内存档目录",
                        true,
                    )
                }
            }
            EngineType.VN, EngineType.WEB_OTHER, EngineType.RENPY ->
                SaveLocation(null, "${game.engine.displayName} 没有标准文件存档接口", false)
            EngineType.ARTEMIS -> SaveLocation(File(root), "Artemis 游戏目录存档", true)
            EngineType.UNKNOWN -> SaveLocation(null, "未知引擎不支持存档管理", false)
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
        if (!source.isDirectory) throw IOException("暂未发现可导出的存档文件")
        val output = appContext.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException("无法创建导出压缩包")
        ZipOutputStream(output).use { zip ->
            val entries = mutableSetOf<String>()
            val count = writeZipContents(source, source, zip, entries, excludeFor(game.engine))
            if (count == 0) throw IOException("暂未发现可导出的存档文件")
            return count
        }
    }

    @Throws(IOException::class)
    fun importFromZip(game: ScanGame, sourceUri: Uri): Int {
        val destination = resolveSaveLocation(game).directory ?: throw IOException("无法解析实际存档目录")
        if (!destination.exists() && !destination.mkdirs()) throw IOException("无法创建存档目录")
        if (!destination.isDirectory) throw IOException("存档目录不可用")

        val temp = createTemporaryDirectory()
        try {
            val extracted = extractZip(sourceUri, temp)
            if (extracted == 0) throw IOException("压缩包中未找到存档文件")
            clearSaveDirectory(destination, game.engine)
            copyDirectoryContents(temp, destination, excludeFor(game.engine))
            return extracted
        } finally {
            temp.deleteRecursively()
        }
    }

    @Throws(IOException::class)
    fun deleteSaves(game: ScanGame): Int {
        val directory = resolveSaveLocation(game).directory ?: throw IOException("无法解析实际存档目录")
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
            ?: throw IOException("无法读取导入压缩包")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(BUFFER_SIZE)
            while (entry != null) {
                val name = safeZipEntryName(entry.name)
                if (!entries.add(name)) throw IOException("压缩包包含重复文件：$name")
                if (entries.size > MAX_SAVE_ZIP_FILES) throw IOException("压缩包文件数量过多")
                val output = File(destination, name).canonicalFile
                if (!output.path.startsWith(rootPath + File.separator)) {
                    throw IOException("压缩包包含非法路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) throw IOException("无法创建存档目录：$name")
                } else {
                    output.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) throw IOException("无法创建存档目录：$name")
                    }
                    FileOutputStream(output, false).use { out ->
                        var read = zip.read(buffer)
                        while (read != -1) {
                            totalBytes += read.toLong()
                            if (totalBytes > MAX_SAVE_ZIP_BYTES) throw IOException("压缩包解压后过大")
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
                if (!target.exists() && !target.mkdirs()) throw IOException("无法创建存档目录：${child.name}")
                copied += copyDirectoryContents(child, target, exclude)
            } else if (child.isFile) {
                target.parentFile?.let {
                    if (!it.exists() && !it.mkdirs()) throw IOException("无法创建存档目录：${child.name}")
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
            throw IOException("压缩包包含非法路径：$raw")
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
        if (!directory.delete() || !directory.mkdirs()) throw IOException("无法创建临时解压目录")
        return directory
    }

    companion object {
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_SAVE_ZIP_FILES = 20_000
        private const val MAX_SAVE_ZIP_BYTES = 1024L * 1024L * 1024L
    }
}
