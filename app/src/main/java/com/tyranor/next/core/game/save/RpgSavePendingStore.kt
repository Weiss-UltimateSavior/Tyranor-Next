package com.tyranor.next.core.game.save

import android.content.Context
import java.io.File

/**
 * 「待回写同步」的游戏集合：开启存档互通的游戏在启动时登记，应用回到前台时
 * 对这些游戏做一次 Tyranor→标准的补写（引擎退出后 500ms 会强杀进程，
 * 不能依赖退出回调，故以前台兜底）。
 *
 * 存储为应用私有文件 `filesDir/rpg_save_pending`，每行一个 game uri；
 * 单进程写（主进程 UI 调用），写入原子替换、失败返回 false 并由调用方上报，
 * 读取损坏时视为空集合。
 */
object RpgSavePendingStore {
    private const val FILE_NAME = "rpg_save_pending"

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    /** 登记待回写游戏；返回是否已成功落盘（false = 写入失败，本次登记未生效）。 */
    @Synchronized
    fun add(context: Context, gameUri: String): Boolean = runCatching {
        val current = all(context).toMutableSet()
        if (current.add(gameUri)) write(context, current) else true
    }.getOrDefault(false)

    /** 移除待回写游戏；返回是否已成功落盘（false = 写入失败，记录可能仍在）。 */
    @Synchronized
    fun remove(context: Context, gameUri: String): Boolean = runCatching {
        val current = all(context).toMutableSet()
        if (current.remove(gameUri)) write(context, current) else true
    }.getOrDefault(false)

    fun all(context: Context): List<String> =
        runCatching {
            val f = file(context)
            if (!f.isFile) emptyList()
            else f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())

    /** 原子写入；返回是否提交成功。失败保留旧文件（不截断重写），由调用方决定如何上报。 */
    private fun write(context: Context, uris: Set<String>): Boolean {
        val f = file(context)
        if (uris.isEmpty()) {
            return runCatching { f.delete() && true || !f.exists() }.getOrDefault(false)
        }
        // 原子写：先写同目录临时文件再替换。直接 writeText 会先截断目标，进程终止或 I/O
        // 失败会留下空/半截文件，而 all() 把损坏结果当空集合——待回写记录会永久丢失。
        // 用 Files.move(REPLACE_EXISTING)：File.renameTo 在 Windows 上不能替换已存在目标。
        val dir = f.parentFile ?: return false
        val tmp = File(dir, f.name + ".tmp." + System.nanoTime())
        return try {
            tmp.writeText(uris.joinToString("\n"), Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    f.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            // 替换成功后 tmp 已不存在；失败/异常路径清理半成品
            if (tmp.exists()) tmp.delete()
        }
    }
}
