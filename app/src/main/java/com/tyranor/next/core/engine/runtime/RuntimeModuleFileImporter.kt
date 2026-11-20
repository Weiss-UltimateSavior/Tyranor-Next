package com.tyranor.next.core.engine.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.SavePathSafety
import com.tyranor.next.core.game.save.SaveStorageRoots
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Locale

internal object RuntimeModuleFileImporter {
    private const val MAX_FILES = 32
    private val writeMutex = Mutex()

    sealed interface Preparation {
        data object Unavailable : Preparation
        class Ready internal constructor(
            internal val root: File,
            internal val spec: RuntimeModuleFileImportSpec,
            val entries: List<Entry>,
        ) : Preparation
    }

    class Entry internal constructor(
        internal val source: Uri,
        val relativePath: String,
        internal val existing: FileSnapshot?,
    ) {
        val replacesFile: Boolean get() = existing != null
    }

    data class FileSnapshot(val size: Long, val modified: FileTime, val key: Any?)

    sealed interface Result {
        data object Success : Result
        data class Failed(val imported: Int) : Result
    }

    suspend fun prepare(
        context: Context,
        game: ScanGame,
        spec: RuntimeModuleFileImportSpec,
        sources: List<Uri>,
    ): Preparation = withContext(Dispatchers.IO) {
        try {
            require(sources.size in 1..(if (spec.multiple) MAX_FILES else 1))
            val root = resolveRoot(context, game, spec)
            val names = hashSetOf<String>()
            val entries = sources.map { source ->
                require(source.scheme == ContentResolver.SCHEME_CONTENT)
                val name = spec.fileName ?: readDisplayName(context.contentResolver, source)
                require(RuntimeModuleFileImportParser.isValidFileName(name))
                val relativePath = spec.destination(name)
                require(names.add(relativePath.lowercase(Locale.ROOT)))
                val target = checkedTarget(root, relativePath)
                Entry(source, relativePath, snapshot(target))
            }
            Preparation.Ready(root, spec, entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Preparation.Unavailable
        }
    }

    suspend fun write(context: Context, game: ScanGame, plan: Preparation.Ready): Result =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                var imported = 0
                try {
                    check(resolveRoot(context, game, plan.spec) == plan.root)
                    plan.entries.forEach { entry ->
                        check(snapshot(checkedTarget(plan.root, entry.relativePath)) == entry.existing)
                    }
                    for (entry in plan.entries) {
                        currentCoroutineContext().ensureActive()
                        writeFile(context, game, plan, entry)
                        imported++
                    }
                    Result.Success
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Result.Failed(imported)
                }
            }
        }

    private fun resolveRoot(context: Context, game: ScanGame, spec: RuntimeModuleFileImportSpec): File {
        val packageName = checkNotNull(game.runtimeModulePackage)
        val current = GameLibraryFacade.loadGames(context).firstOrNull { it.uri == game.uri }
        check(current?.engine == EngineType.RUNTIME_MODULE && current.runtimeModulePackage == packageName)
        check(packageName in AppSettingsStore.getEnabledRuntimeModules(context))
        val discovery = RuntimeModuleDiscovery.discover(context) as? RuntimeModuleDiscoveryResult.Success
        check(discovery?.modules?.any { it.packageName == packageName && spec in it.fileImports } == true)

        val uri = game.uri.toUri()
        check(uri.scheme == "file" || game.uri.startsWith('/') ||
            (uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents"))
        val directory = File(checkNotNull(GamePathUtils.safUriToPath(game.uri)))
        val path = directory.absoluteFile.toPath()
        val anchor = SaveStorageRoots.forContext(context)
            .filter { path != it.absoluteFile.toPath() && path.startsWith(it.absoluteFile.toPath()) }
            .maxByOrNull { it.absoluteFile.toPath().nameCount }
        val root = SavePathSafety.requireSafeRoot(directory, checkNotNull(anchor))
        check(root.isDirectory)
        return root
    }

    internal fun checkedTarget(root: File, relativePath: String): File {
        val name = relativePath.substringAfterLast('/')
        val directory = relativePath.substringBeforeLast('/', ".")
        require(RuntimeModuleFileImportParser.isValidFileName(name))
        require('/' !in relativePath || RuntimeModulePaths.isValidRelativePath(directory))
        val parent = SavePathSafety.requireSafeRoot(if ('/' in relativePath) File(root, directory) else root, root)
        check(parent.toPath().startsWith(root.toPath()))
        return File(parent, name)
    }

    internal fun snapshot(file: File): FileSnapshot? {
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!attributes.isRegularFile) throw IOException("Import target is not a regular file")
        return FileSnapshot(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    }

    private fun readDisplayName(resolver: ContentResolver, source: Uri): String =
        resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: throw IOException("Import source has no display name")

    private suspend fun writeFile(context: Context, game: ScanGame, plan: Preparation.Ready, entry: Entry) {
        val target = checkedTarget(plan.root, entry.relativePath)
        val parent = checkNotNull(target.parentFile)
        Files.createDirectories(parent.toPath())
        check(checkedTarget(plan.root, entry.relativePath) == target)
        stageAndWrite(target, entry.replacesFile, {
            context.contentResolver.openInputStream(entry.source)
                ?: throw IOException("Unable to open import source")
        }) {
            check(resolveRoot(context, game, plan.spec) == plan.root)
            check(checkedTarget(plan.root, entry.relativePath) == target && snapshot(target) == entry.existing)
        }
    }

    internal suspend fun stageAndWrite(
        target: File,
        replace: Boolean,
        openSource: () -> InputStream,
        verifyTarget: () -> Unit,
    ) {
        val parent = checkNotNull(target.parentFile)
        val staged = Files.createTempFile(parent.toPath(), ".tyranor-import-", ".tmp")
        try {
            val coroutine = currentCoroutineContext()
            openSource().use { source ->
                Files.newOutputStream(staged).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutine.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            coroutine.ensureActive()
            verifyTarget()
            if (replace) {
                Files.move(staged, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } else {
                Files.move(staged, target.toPath())
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}
