package com.tyranor.next.core.game.save

import android.content.Context
import java.io.File

/**
 * 「待回写同步」的游戏集合：开启存档互通的游戏在启动时登记，应用回到前台时
 * 对这些游戏做一次 Tyranor→标准的补写（引擎退出后 500ms 会强杀进程，
 * 不能依赖退出回调，故以前台兜底）。
 *
 * 存储为应用私有文件 `filesDir/rpg_save_pending`，每行一个 game uri；
 * 单进程写（主进程 UI 调用），读写均 runCatching 兜底，损坏时视为空集合。
 */
object RpgSavePendingStore {
    private const val FILE_NAME = "rpg_save_pending"

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun add(context: Context, gameUri: String) {
        runCatching {
            val current = all(context).toMutableSet()
            if (current.add(gameUri)) write(context, current)
        }
    }

    @Synchronized
    fun remove(context: Context, gameUri: String) {
        runCatching {
            val current = all(context).toMutableSet()
            if (current.remove(gameUri)) write(context, current)
        }
    }

    fun all(context: Context): List<String> =
        runCatching {
            val f = file(context)
            if (!f.isFile) emptyList()
            else f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())

    private fun write(context: Context, uris: Set<String>) {
        val f = file(context)
        if (uris.isEmpty()) {
            f.delete()
        } else {
            f.writeText(uris.joinToString("\n"), Charsets.UTF_8)
        }
    }
}
