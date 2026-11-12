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

    /** 配置文件只做受管键同步，超过该大小视为异常内容，不再整体读入内存。 */
    private const val MAX_CONFIG_FILE_BYTES = 1L * 1024 * 1024

    /** 同进程写锁：启动与设置保存可能并发触发同步，避免读到写了一半的临时态。 */
    private val configWriteLock = Any()

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

    /**
     * 插件实际会读取的配置文件路径（与插件 `loadConfig` 的二选一逻辑一致）。
     *
     * 只把「根目录本身或根目录下的子目录」视为外部存储内路径；`/storage/emulated/0abc`
     * 这类同前缀但不同目录的路径必须按外部路径处理，否则会把配置写进非游戏目录。
     * 存储根本身没有「游戏目录」语义，使用按游戏的 `JoiPlay/games/<id>/` 路径。
     *
     * [externalRoot] 仅用于单测注入；设备上使用 [Environment.getExternalStorageDirectory]。
     */
    fun configFileFor(
        gameFolder: String,
        gameId: String,
        externalRoot: File = Environment.getExternalStorageDirectory(),
    ): File {
        val root = normalize(externalRoot.absolutePath)
        val folder = normalize(gameFolder)
        return if (folder.isNotEmpty() && folder != root && folder.startsWith("$root/")) {
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

    /**
     * 是否允许把目录级 `configuration.json` 同步为 App 生效值。
     *
     * 插件优先读取 `<gameFolder>/configuration.json`，且这份文件是**目录级共享**的：
     * - 同一目录多个 SWF（目录里放多个游戏）时，任一游戏的设置都会覆盖其它游戏，
     *   因此保留 intent `settings`（每次启动按当前游戏生效值下发）作为事实源；
     * - 外部存储根目录同理，不能把根级配置当成某个游戏的私有配置。
     *
     * [externalRoot] 仅用于单测注入。
     */
    fun shouldSyncConfigurationFile(
        folder: String,
        swfFileCount: Int,
        externalRoot: File = Environment.getExternalStorageDirectory(),
    ): Boolean = swfFileCount <= 1 && !isExternalStorageRoot(folder, externalRoot)

    /**
     * 文件存在且为 JSON 对象时同步受管键并写回，返回是否发生写入；
     * 文件不存在、超过大小上限或非 JSON 时不动（非致命，交由插件按默认值处理）。
     *
     * 写入使用同目录临时文件 + rename：避免中途失败把原配置截断成半个 JSON。
     * 不创建、不删除配置文件本身（策略 A）。
     */
    fun syncConfigurationFile(file: File, settings: EngineSettingsStore.Ruffle): Boolean {
        if (!isSyncable(file)) return false
        synchronized(configWriteLock) {
            if (!isSyncable(file)) return false
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false
            applyManagedKeys(json, settings)
            return writeAtomically(file, json.toString())
        }
    }

    private fun isSyncable(file: File): Boolean =
        file.isFile && file.length() <= MAX_CONFIG_FILE_BYTES

    private fun writeAtomically(target: File, text: String): Boolean {
        val parent = target.parentFile ?: return false
        val temp = File(parent, "${target.name}.tmp")
        if (!runCatching { temp.writeText(text) }.isSuccess) {
            runCatching { temp.delete() }
            return false
        }
        val renamed = runCatching { temp.renameTo(target) }.getOrDefault(false)
        if (!renamed) {
            // rename 失败时保留原文件（绝不截断目标），清理临时文件后按非致命失败返回。
            runCatching { temp.delete() }
        }
        return renamed
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')
}
