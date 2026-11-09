package com.tyranor.next.core.game.model

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import bridge.PrimaryStorageRoot
import com.tyranor.runtime.systemnnn.SystemNnnGameImport
import java.io.File
import java.io.IOException

internal object SystemNnnGameDirectory {
    private const val STORAGE_PROVIDER = "com.android.externalstorage.documents"
    private val volumeName = Regex("[A-Za-z0-9_-]+")

    /** A committed local copy remains authoritative even if direct source access later returns. */
    fun committed(context: Context, uriText: String): File? {
        val sourceId = sourceIdentity(uriText) ?: return null
        return SystemNnnGameImport.committedRoot(context, sourceId)
    }

    fun sourceIdentity(uriText: String): String? =
        sourceDocument(uriText)?.let(SystemNnnGameImport::sourceIdentity)

    fun sourceUri(sourceId: String): Uri = DocumentsContract.buildDocumentUri(STORAGE_PROVIDER, sourceId)

    /** Read-only path selection shared by launch, backup and WebDAV; never performs a copy. */
    fun accessible(context: Context, uriText: String): File? = try {
        committed(context, uriText) ?: accessible(uriText)
    } catch (_: IOException) {
        // A missing/corrupt committed identity must not silently switch the save authority.
        null
    } catch (_: SecurityException) {
        null
    }

    fun sourceDocument(uriText: String): Uri? {
        val uri = Uri.parse(uriText)
        if (uri.scheme == "content") {
            if (uri.authority != STORAGE_PROVIDER) return null
            return runCatching {
                sourceUri(SystemNnnGameImport.sourceIdentity(uri))
            }.getOrNull()
        }
        val path = resolve(uriText)?.path ?: return null
        val primary = PrimaryStorageRoot.get()
        val aliases = listOf(primary, "/storage/emulated/0", "/storage/self/primary", "/sdcard")
        val primaryPrefix = aliases.firstOrNull { path == it || path.startsWith("$it/") }
        val id = if (primaryPrefix != null) {
            "primary:" + path.removePrefix(primaryPrefix).trimStart('/')
        } else {
            val volume = storageVolume(File(path)) ?: return null
            volume.name + ":" + path.removePrefix(volume.path).trimStart('/')
        }
        return runCatching {
            val source = sourceUri(id)
            SystemNnnGameImport.sourceIdentity(source)
            source
        }.getOrNull()
    }

    /** Resolve the authorized directory lexically so the storage permission gate can run first. */
    fun resolve(uriText: String): File? {
        return try {
            val uri = Uri.parse(uriText)
            when (uri.scheme) {
                "content" -> if (uri.authority != STORAGE_PROVIDER) return null
                "file" -> if (!uri.authority.isNullOrEmpty()) return null
                null -> if (!uriText.startsWith('/')) return null
                else -> return null
            }
            val file = File(GamePathUtils.safUriToPath(uriText) ?: return null)
            val path = file.toPath()
            if (!path.isAbsolute || path != path.normalize()) return null
            if (uri.scheme == "content" && storageVolume(file) == null) return null
            file
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun accessible(uriText: String, candidate: File? = resolve(uriText)): File? {
        if (candidate == null) return null
        return try {
            val directory = candidate.canonicalFile
            if (Uri.parse(uriText).scheme == "content") {
                val volume = storageVolume(candidate)?.canonicalFile ?: return null
                if (!directory.toPath().startsWith(volume.toPath())) return null
            }
            directory.takeIf { it.isDirectory && it.canRead() }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun storageVolume(path: File): File? {
        val primary = File(PrimaryStorageRoot.get())
        if (path.toPath().startsWith(primary.toPath())) return primary
        if (path.toPath().startsWith(File("/storage/emulated/0").toPath())) return File("/storage/emulated/0")
        if (!path.path.startsWith("/storage/")) return null
        val volume = path.path.removePrefix("/storage/").substringBefore('/')
        return if (volume != "emulated" && volumeName.matches(volume)) File("/storage/$volume") else null
    }
}
