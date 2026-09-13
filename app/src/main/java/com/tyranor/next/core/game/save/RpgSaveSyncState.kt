package com.tyranor.next.core.game.save

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 存档互通（[RpgSaveSync]）的同步清单持久化：按游戏记录各槽位两侧的修改时间。
 *
 * 清单的作用是把「文件不存在」区分为「从未同步过（新建）」与「同步过但已被删除」——
 * 这是删除语义成立的前提（Tyranor 删档后，下次同步才能把标准侧对应文件归入 deleted/，
 * 而不是把它当成新存档再导入回来）。
 *
 * 存储位置为应用私有目录 `filesDir/rpg_save_sync/`，不触碰游戏目录与 engine。
 * 构造参数是纯 [File]，便于单测直接用临时目录。
 */
class RpgSaveSyncState(private val storeDir: File) {

    /**
     * 单个槽位在两侧最后一次同步后的状态。
     *
     * [standardExists]/[tyranorExists] 显式记录该侧当时是否存在——不能用 `mtime > 0` 反推：
     * mtime 为 0（时间戳不可用/纪元时间）的文件会被误判为「不存在」，进而把「已删除」当成
     * 「新建」而复写。默认值由 mtime 推导，兼容本次改动前写入的旧清单（缺少这两个字段）。
     */
    data class SlotState(
        val standardMtime: Long,
        val tyranorMtime: Long,
        val standardExists: Boolean = standardMtime > 0L,
        val tyranorExists: Boolean = tyranorMtime > 0L,
    )

    /** [load] 的结果：区分「清单不存在（首次同步）」与「清单损坏/读取失败（必须中止同步）」。 */
    sealed interface LoadResult {
        /** 清单文件不存在：按首次同步处理（空状态）。 */
        data object Missing : LoadResult

        /** 清单读取并解析成功。 */
        data class Loaded(val slots: Map<String, SlotState>) : LoadResult

        /**
         * 清单存在但读取/解析失败。**不能**当作空清单继续同步——清单里记录的「已删除」
         * 会被当成「新建」，已删除的存档会被重新导入。调用方必须中止本轮同步。
         */
        data object Unreadable : LoadResult
    }

    fun load(gameKey: String): LoadResult = synchronized(INTEROP_LOCK) {
        val file = fileFor(gameKey)
        if (!file.isFile) return@synchronized LoadResult.Missing
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            // 形状校验：缺 slots 或任一槽位条目不是对象/缺必需字段，都是损坏清单——
            // 静默跳过会丢掉该槽位的「已删除」历史，导致已删存档被当新存档导入
            val slots = root.optJSONObject(KEY_SLOTS) ?: return@runCatching LoadResult.Unreadable
            LoadResult.Loaded(
                buildMap {
                    slots.keys().forEach { slot ->
                        val entry = slots.optJSONObject(slot)
                            ?: return@runCatching LoadResult.Unreadable
                        if (!entry.has(KEY_STANDARD) || !entry.has(KEY_TYRANOR)) {
                            return@runCatching LoadResult.Unreadable
                        }
                        put(
                            slot,
                            SlotState(
                                standardMtime = entry.getLong(KEY_STANDARD),
                                tyranorMtime = entry.getLong(KEY_TYRANOR),
                                // 旧清单无显式标记：按 mtime>0 推导
                                standardExists = entry.optBoolean(KEY_STANDARD_EXISTS, entry.getLong(KEY_STANDARD) > 0L),
                                tyranorExists = entry.optBoolean(KEY_TYRANOR_EXISTS, entry.getLong(KEY_TYRANOR) > 0L),
                            ),
                        )
                    }
                },
            )
        }.getOrElse {
            // 半截 JSON/损坏内容：如实上报，绝不能兜底成「首次同步」
            LoadResult.Unreadable
        }
    }

    /**
     * 原子写入清单。
     *
     * @return 是否已成功提交；false 表示写入/替换失败，此时**保留旧清单**（绝不截断重写，
     *   否则半截 JSON 会被 [load] 当作空清单，使已删除的存档被当新存档复活），由调用方报告失败。
     */
    fun save(gameKey: String, slots: Map<String, SlotState>): Boolean = synchronized(INTEROP_LOCK) {
        runCatching {
            val file = fileFor(gameKey)
            val dir = file.parentFile ?: return@runCatching false
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@runCatching false
            val encoded = JSONObject()
            slots.forEach { (slot, state) ->
                encoded.put(
                    slot,
                    JSONObject()
                        .put(KEY_STANDARD, state.standardMtime)
                        .put(KEY_TYRANOR, state.tyranorMtime)
                        .put(KEY_STANDARD_EXISTS, state.standardExists)
                        .put(KEY_TYRANOR_EXISTS, state.tyranorExists),
                )
            }
            val root = JSONObject().put(KEY_SLOTS, encoded)
            val json = root.toString()
            val tmp = File(dir, file.name + ".tmp." + System.nanoTime())
            tmp.writeText(json, Charsets.UTF_8)
            if (tmp.renameTo(file)) {
                true
            } else {
                // 替换失败：保留旧清单、清理临时文件
                tmp.delete()
                false
            }
        }.getOrDefault(false)
    }

    fun clear(gameKey: String) = synchronized(INTEROP_LOCK) {
        runCatching { fileFor(gameKey).delete() }
        Unit
    }

    private fun fileFor(gameKey: String): File = File(storeDir, sha1(gameKey) + ".json")

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * 存档互通全局互斥：[RpgSaveSync.sync] 与清单读写共用同一把锁，串行化可能并发的
         * 同步入口（启动前 / 前台兜底 / 存档页手动）与删除游戏时的清单清理。可重入，
         * 故 sync 持锁期间调用 load/save 不会死锁。
         */
        internal val INTEROP_LOCK = Any()

        private const val KEY_SLOTS = "slots"
        private const val KEY_STANDARD = "std"
        private const val KEY_TYRANOR = "tyr"
        private const val KEY_STANDARD_EXISTS = "std_x"
        private const val KEY_TYRANOR_EXISTS = "tyr_x"

        /** 应用私有目录：`filesDir/rpg_save_sync/`。 */
        fun forContext(context: Context): RpgSaveSyncState =
            RpgSaveSyncState(File(context.applicationContext.filesDir, "rpg_save_sync"))
    }
}
