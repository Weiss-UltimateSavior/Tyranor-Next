package com.tyranor.next.core.settings

import android.content.Context
import com.tyranor.next.core.game.storage.SystemNnnGameDirectory
import com.tyranor.runtime.systemnnn.SaveLayoutConfig
import com.tyranor.runtime.systemnnn.SystemNnnHost
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal object SystemNnnSaveLayout {
    sealed interface Result {
        data object Missing : Result
        data object Unavailable : Result
        data object Failed : Result
        data class Available(
            val path: String,
            val layerRecords: Int,
            val messageWidth: Int,
            val statusBytes: Int,
            val systemSaveVersion: Int,
            val systemSaveFromExecutable: Boolean,
            val executableLayout: Boolean,
            val executableMessageWidth: Boolean,
            val executableStatusBytes: Boolean,
            val okikae: Boolean,
            val executableOkikae: Boolean,
            val extensionStartCode: Int,
        ) : Result
    }

    suspend fun load(context: Context, gameUri: String, regenerate: Boolean): Result =
        runInterruptible(Dispatchers.IO) {
            val root = SystemNnnGameDirectory.accessible(context, gameUri) ?: return@runInterruptible Result.Unavailable
            try {
                val config = SystemNnnHost.saveLayoutConfig(context, root, regenerate)
                    ?: return@runInterruptible Result.Missing
                Result.Available(
                    path = config.path,
                    layerRecords = config.layerRecords,
                    messageWidth = config.messageWidth,
                    statusBytes = config.statusBytes,
                    systemSaveVersion = config.systemSaveVersion,
                    systemSaveFromExecutable = config.systemSaveSource == SaveLayoutConfig.SystemSaveSource.EXECUTABLE,
                    executableLayout = config.executableLayout,
                    executableMessageWidth = config.executableMessageWidth,
                    executableStatusBytes = config.executableStatusBytes,
                    okikae = config.okikae,
                    executableOkikae = config.executableOkikae,
                    extensionStartCode = config.extensionStartCode,
                )
            } catch (_: IOException) {
                Result.Failed
            } catch (_: SecurityException) {
                Result.Unavailable
            }
        }
}
