package com.tyranor.next.core.game.save

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/** Trust SDK storage boundaries, never a directory chosen by the game or the user. */
internal object SaveStorageRoots {
    fun forContext(context: Context): List<File> {
        val externalFiles = context.getExternalFilesDirs(null).filterNotNull()
        val volumes = mutableListOf(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storage = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            storage?.storageVolumes?.mapNotNullTo(volumes) { it.directory }
        }
        for (files in externalFiles) {
            val packageDirectory = files.parentFile ?: continue
            val data = packageDirectory.parentFile ?: continue
            val android = data.parentFile ?: continue
            if (files.name == "files" && data.name == "data" && android.name == "Android") {
                android.parentFile?.let(volumes::add)
            }
        }
        val roots = listOfNotNull(context.filesDir, context.noBackupFilesDir, context.filesDir.parentFile) +
            externalFiles + volumes
        val canonicalVolumes = volumes.mapNotNull(::canonicalOrNull).toSet()
        val aliases = listOf(File("/sdcard"), File("/mnt/sdcard"), File("/storage/self/primary"))
            .filter { alias -> canonicalOrNull(alias)?.let { it in canonicalVolumes } == true }
        return (roots + aliases).distinctBy { it.absolutePath }
    }

    private fun canonicalOrNull(file: File): File? = try {
        file.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
