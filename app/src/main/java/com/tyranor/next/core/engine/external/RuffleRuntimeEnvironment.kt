package com.tyranor.next.core.engine.external

import android.os.Environment
import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import java.io.File

/**
 * Ruffle 外置模块的 `configuration.json` 管理（策略 A）。
 *
 * 插件读取路径（`loadConfig`）：
 * - 游戏目录在外部存储根下 → `<gameFolder>/configuration.json`
 * - 否则 → `/storage/emulated/0/JoiPlay/games/<game.id>/configuration.json`
 *
 * 文件存在时插件以文件值覆盖 intent `settings`，因此必须同步为 App 生效值；
 * 策略 A 不创建、不删除文件，与 Ren'Py / RPG Maker 的既有处理保持一致。
 */
object RuffleRuntimeEnvironment {

    /** 受管键：与插件扁平 `configuration.json` 的键名一致。 */
    fun applyManagedKeys(json: JSONObject, settings: EngineSettingsStore.Ruffle) {
        json.put(
            "screenTimeout",
            EngineSettingsStore.normalizeRuffleScreenTimeout(settings.screenTimeout),
        )
        json.put("backend", EngineSettingsStore.normalizeRuffleBackend(settings.backend))
        json.put("quality", EngineSettingsStore.normalizeRuffleQuality(settings.quality))
        json.put("scaleMode", EngineSettingsStore.normalizeRuffleScaleMode(settings.scaleMode))
        json.put("letterbox", EngineSettingsStore.normalizeRuffleLetterbox(settings.letterbox))
        json.put("loadBehavior", EngineSettingsStore.normalizeRuffleLoadBehavior(settings.loadBehavior))
    }

    /** The module uses a literal storage-prefix check, including the storage root itself. */
    fun configFileFor(
        gameFolder: String,
        gameId: String,
        externalRoot: File = Environment.getExternalStorageDirectory(),
    ): File {
        val folder = normalize(gameFolder)
        val root = normalize(externalRoot.absolutePath)
        return if (folder.startsWith(root)) {
            File(folder, "configuration.json")
        } else {
            File(externalRoot, "JoiPlay/games/$gameId/configuration.json")
        }
    }

    /** 目录是否恰为外部存储根（根目录不能套用目录级 configuration.json）。 */
    fun isExternalStorageRoot(
        folder: String,
        externalRoot: File = Environment.getExternalStorageDirectory(),
    ): Boolean {
        val normalized = normalize(folder)
        return normalized.isNotEmpty() && normalized == normalize(externalRoot.absolutePath)
    }

    /** Shared directory files are read-only during launch preparation. */
    internal fun shouldWriteConfigurationFile(
        file: File,
        gameDirectory: File,
        externalRoot: File,
    ): Boolean {
        if (file.parentFile != gameDirectory) return true
        if (isExternalStorageRoot(gameDirectory.absolutePath, externalRoot)) return false
        val siblings = gameDirectory.listFiles() ?: return false
        return siblings.count { it.isFile && it.name.endsWith(".swf", ignoreCase = true) } <= 1
    }

    internal fun reconcileConfigurationFile(
        file: File,
        settings: EngineSettingsStore.Ruffle,
        writable: Boolean,
    ): ExternalEngineSettingsFile.Result = ExternalEngineSettingsFile.reconcile(
        file, JSONObject().also { applyManagedKeys(it, settings) }, writable,
    )

    private fun normalize(path: String): String = path.trimEnd('/')
}
