package com.tyranor.next.core.game.model

import android.net.Uri
import java.io.File
import java.io.IOException

internal object SystemNnnGameDirectory {
    private const val STORAGE_PROVIDER = "com.android.externalstorage.documents"
    private val volumeName = Regex("[A-Za-z0-9_-]+")

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
        if (path.toPath().startsWith(File("/storage/emulated/0").toPath())) return File("/storage/emulated/0")
        if (!path.path.startsWith("/storage/")) return null
        val volume = path.path.removePrefix("/storage/").substringBefore('/')
        return if (volume != "emulated" && volumeName.matches(volume)) File("/storage/$volume") else null
    }
}
