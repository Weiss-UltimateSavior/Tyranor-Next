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
class RpgSaveSyncState(private val storeDir: File, private val manualStoreDir: File = storeDir) {

    /** 手动版本选择标记的读取结果。 */
    enum class ManualSelectionState {
        /** 该游戏处于「下一轮同步以 Tyranor 侧为准」状态。 */
        Present,

        /** 没有标记文件，或标记被显式写成 false：常规双向同步。 */
        Absent,

        /** Interrupted explicit selection; another explicit selection can repair it. */
        Pending,

        /**
         * 标记文件损坏，或一次导入尚未完成。不能退回常规同步覆盖可能已切换的存档；
         * 下一次显式选择会原子替换此标记。
         */
        Unreadable,
    }

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
                        // 存在标记：字段缺失（旧清单）按 mtime>0 推导；字段存在但类型不是
                        // Boolean 属于损坏清单——静默回退会让 tyr_x 误成 false，把「已删除」
                        // 当「新建」而复活（审查跟进 #1），必须中止同步
                        fun existsFlag(key: String, mtime: Long): Boolean {
                            if (!entry.has(key)) return mtime > 0L
                            return when (val v = entry.get(key)) {
                                is Boolean -> v
                                else -> throw org.json.JSONException("manifest slot flag $key has non-boolean type: $v")
                            }
                        }
                        val stdMtime = entry.getLong(KEY_STANDARD)
                        val tyrMtime = entry.getLong(KEY_TYRANOR)
                        put(
                            slot,
                            SlotState(
                                standardMtime = stdMtime,
                                tyranorMtime = tyrMtime,
                                standardExists = existsFlag(KEY_STANDARD_EXISTS, stdMtime),
                                tyranorExists = existsFlag(KEY_TYRANOR_EXISTS, tyrMtime),
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
            try {
                tmp.writeText(json, Charsets.UTF_8)
                // 必须用 Files.move(REPLACE_EXISTING)：File.renameTo 在 Windows 上不能替换
                // 已存在的目标文件，第二次及以后的清单提交会全部失败（审查中发现）。
                // ATOMIC_MOVE 优先，不支持原子替换的文件系统退回普通替换。
                val target = file.toPath()
                try {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                true
            } finally {
                // 替换成功后 tmp 已不存在；写入异常/替换失败时清理半成品，不残留私有目录（审查 L4）
                if (tmp.exists()) tmp.delete()
            }
        }.getOrDefault(false)
    }

    fun clear(gameKey: String) = synchronized(INTEROP_LOCK) {
        runCatching { fileFor(gameKey).delete() }
        // 游戏从库里真正移除时一并清掉手动版本标记：标记只服务于「下一轮同步」，
        // 游戏都没了还留着，会让同一 uri 被重新添加后凭空执行一次「以 Tyranor 为准」的定向同步。
        // 注意同步失败/进程被杀的路径不会走这里，标记因此天然跨失败保留。
        runCatching { manualFileFor(gameKey).delete() }
        Unit
    }

    /**
     * 是否存在「用户显式选择过存档版本」标记（见 [markManualSelection]）。
     *
     * 只有内容为 `{"manual":true}` 才返回 true；显式的 `false` 视为「没有手动选择」。
     *
     * **失败关闭**：标记文件存在却读不出布尔值（半截 JSON、缺键、类型不符）时抛类型化异常，
     * 既不退化成 false 走常规 mtime 同步（那会用标准侧较新副本覆盖用户刚选定的版本，正是本标记
     * 要防的事），也不当作 Present 去执行「标准侧整体归入 deleted/」这种方向性强的操作。本轮同步
     * 中止、两侧文件与标记原样保留，调用方保留 pending；下次显式选择会原子替换损坏标记。
     *
     * @throws GameSaveException 标记内容损坏、无法判定状态时
     */
    fun hasManualSelection(gameKey: String): Boolean =
        when (readManualSelection(gameKey)) {
            ManualSelectionState.Present -> true
            ManualSelectionState.Absent -> false
            ManualSelectionState.Pending -> throw GameSaveException(SaveErrorCode.SAVE_SELECTION_INCOMPLETE, detail = gameKey)
            ManualSelectionState.Unreadable -> throw GameSaveException(
                SaveErrorCode.SAVE_DIR_UNAVAILABLE,
                detail = gameKey,
            )
        }

    /**
     * 置位标记：用户在云端/ZIP 里**显式激活**某个存档版本后，下一轮互通同步以 Tyranor 侧为准
     * （对应 `RpgSaveSync.sync` 的 `preferTyranor`）。
     *
     * 没有这层标记时，用户刚选定的旧版本会在下次启动被标准侧较新的 mtime 反向覆盖——常规同步
     * 只认时间戳，认不出「这是人挑的」。标记落在不参与备份的独立小文件，同步失败与进程
     * 被杀都不丢，由调用方在同步成功（`failed == 0`）后 [clearManualSelection]。
     *
     * 与清单读写共用 [INTEROP_LOCK]（可重入），故可在导入互斥区内安全调用。
     *
     * @return 是否已确认标记落盘。false = 标记不可用，调用方必须如实上报失败，不得静默降级为
     *   常规同步——那正是本标记要防的事。
     */
    fun markManualSelection(gameKey: String): Boolean = synchronized(INTEROP_LOCK) {
        when (readManualSelection(gameKey)) {
            // 完好的标记无需重写：重复选择不该产生额外 IO
            ManualSelectionState.Present -> return@synchronized true
            // Keep damaged state in place until a complete replacement is committed.
            ManualSelectionState.Pending, ManualSelectionState.Unreadable, ManualSelectionState.Absent -> Unit
        }
        writeManualSelection(gameKey)
    }

    internal fun needsManualSelectionRecovery(gameKey: String): Boolean = when (readManualSelection(gameKey)) {
        ManualSelectionState.Pending, ManualSelectionState.Unreadable -> true
        else -> false
    }

    /** Commit the direction override only for an applied import; interrupted commits fail closed. */
    internal fun <T> withManualSelection(gameKey: String, block: (onDirectoryCommitted: () -> Unit) -> T): T =
        synchronized(INTEROP_LOCK) {
            val file = manualFileFor(gameKey)
            val previous = try {
                if (!file.exists()) null else {
                    if (!file.isFile || file.length() > 64 * 1024) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                    file.readBytes()
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, detail = gameKey).also {
                    it.addSuppressed(failure)
                }
            }
            val pending = JSONObject().put(KEY_MANUAL_SELECTION, "pending").toString().toByteArray(Charsets.UTF_8)
            if (!writeManualSelection(gameKey, pending)) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            var directoryCommitted = false
            try {
                val result = block { directoryCommitted = true }
                if (!directoryCommitted || !markManualSelection(gameKey)) {
                    throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                }
                result
            } catch (failure: Throwable) {
                // Once the directory changed, retain the pending marker so normal mtime sync
                // cannot overwrite it. Before that point, restore even a previously damaged flag.
                if (!directoryCommitted) {
                    val restored = if (previous == null) clearManualSelection(gameKey) else writeManualSelection(gameKey, previous)
                    if (!restored) failure.addSuppressed(GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE))
                }
                throw failure
            }
        }

    /**
     * 清除标记（定向同步成功落盘后调用）。文件本就不存在视为成功，不谎报失败。
     *
     * @return 是否已确认标记不存在。
     */
    fun clearManualSelection(gameKey: String): Boolean = synchronized(INTEROP_LOCK) {
        val file = manualFileFor(gameKey)
        if (!file.isFile) return@synchronized !file.exists()
        runCatching { file.delete() }.getOrDefault(false) && !file.exists()
    }

    private fun readManualSelection(gameKey: String): ManualSelectionState = synchronized(INTEROP_LOCK) {
        val file = manualFileFor(gameKey)
        if (!file.exists()) return@synchronized ManualSelectionState.Absent
        if (!file.isFile) return@synchronized ManualSelectionState.Unreadable
        runCatching {
            // 只有显式的 true 才算「已手动选择」；false 是明确的「无手动选择」，
            // 而缺键/null/类型不符/半截 JSON 都属损坏，交由调用方失败关闭
            when (val value = JSONObject(file.readText(Charsets.UTF_8)).get(KEY_MANUAL_SELECTION)) {
                is Boolean -> if (value) ManualSelectionState.Present else ManualSelectionState.Absent
                "pending" -> ManualSelectionState.Pending
                else -> ManualSelectionState.Unreadable
            }
        }.getOrDefault(ManualSelectionState.Unreadable)
    }

    /** 临时文件 + 原子替换写入（与 [save] 同款）：标记被写成半截 JSON 就等于丢状态。 */
    private fun writeManualSelection(
        gameKey: String,
        content: ByteArray = JSONObject().put(KEY_MANUAL_SELECTION, true).toString().toByteArray(Charsets.UTF_8),
    ): Boolean = synchronized(INTEROP_LOCK) {
        runCatching {
            val file = manualFileFor(gameKey)
            val dir = file.parentFile ?: return@runCatching false
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@runCatching false
            val tmp = File(dir, file.name + ".tmp." + System.nanoTime())
            try {
                java.io.FileOutputStream(tmp).use { output ->
                    output.write(content)
                    output.fd.sync()
                }
                val target = file.toPath()
                try {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                true
            } finally {
                // 替换成功后 tmp 已不存在；失败时清理半成品，不残留私有目录
                if (tmp.exists()) tmp.delete()
            }
        }.getOrDefault(false)
    }

    private fun fileFor(gameKey: String): File = File(storeDir, sha1(gameKey) + MANIFEST_SUFFIX)

    /**
     * 标记文件：同哈希前缀、不同后缀（`<sha1>.manual.json`），生产环境放在 noBackupFilesDir。
     *
     * 清单读写都按精确文件名命中（[fileFor]），不会被标记文件干扰；标记读失败也只影响标记本身，
     * 不会被误当成「同步清单损坏」。
     */
    private fun manualFileFor(gameKey: String): File {
        val target = File(manualStoreDir, sha1(gameKey) + MANUAL_SUFFIX)
        val legacy = File(storeDir, target.name)
        // Preserve pending selections from early feature builds without backing up new markers.
        if (manualStoreDir != storeDir && legacy.exists()) {
            try {
                if (!target.exists()) {
                    if (!manualStoreDir.isDirectory && !manualStoreDir.mkdirs()) {
                        throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
                    }
                    try {
                        java.nio.file.Files.move(legacy.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        java.nio.file.Files.move(legacy.toPath(), target.toPath())
                    }
                } else if (!legacy.delete()) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, cause = failure)
            }
        }
        return target
    }

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

        private const val KEY_MANUAL_SELECTION = "manual"
        private const val MANIFEST_SUFFIX = ".json"
        private const val MANUAL_SUFFIX = ".manual.json"

        /** 应用私有目录：`filesDir/rpg_save_sync/`。 */
        fun forContext(context: Context): RpgSaveSyncState =
            RpgSaveSyncState(
                File(context.applicationContext.filesDir, "rpg_save_sync"),
                File(context.applicationContext.noBackupFilesDir, "save_sync/selections"),
            )
    }
}
