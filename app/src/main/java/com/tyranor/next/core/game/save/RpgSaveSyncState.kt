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

    /** 单个槽位在两侧最后一次同步后的修改时间；0 表示该侧当时不存在。 */
    data class SlotState(val standardMtime: Long, val tyranorMtime: Long)

    fun load(gameKey: String): Map<String, SlotState> {
        val file = fileFor(gameKey)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val slots = root.optJSONObject(KEY_SLOTS) ?: return@runCatching emptyMap()
            buildMap {
                slots.keys().forEach { slot ->
                    val entry = slots.optJSONObject(slot) ?: return@forEach
                    put(
                        slot,
                        SlotState(
                            standardMtime = entry.optLong(KEY_STANDARD, 0L),
                            tyranorMtime = entry.optLong(KEY_TYRANOR, 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(gameKey: String, slots: Map<String, SlotState>) {
        runCatching {
            val file = fileFor(gameKey)
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val root = JSONObject()
            val encoded = JSONObject()
            slots.forEach { (slot, state) ->
                encoded.put(
                    slot,
                    JSONObject()
                        .put(KEY_STANDARD, state.standardMtime)
                        .put(KEY_TYRANOR, state.tyranorMtime),
                )
            }
            root.put(KEY_SLOTS, encoded)
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    fun clear(gameKey: String) {
        runCatching { fileFor(gameKey).delete() }
    }

    private fun fileFor(gameKey: String): File = File(storeDir, sha1(gameKey) + ".json")

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_SLOTS = "slots"
        private const val KEY_STANDARD = "std"
        private const val KEY_TYRANOR = "tyr"

        /** 应用私有目录：`filesDir/rpg_save_sync/`。 */
        fun forContext(context: Context): RpgSaveSyncState =
            RpgSaveSyncState(File(context.applicationContext.filesDir, "rpg_save_sync"))
    }
}
