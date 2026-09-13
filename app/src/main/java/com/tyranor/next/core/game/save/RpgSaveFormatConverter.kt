package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * RPG Maker MV/MZ 标准存档（JoiPlay/PC）→ Tyranor 格式的转化：纯改名，不重编码。
 *
 * 源与目标都在引擎实际读写的生效存档目录（独立存档开启时为外部目录，否则 `<游戏根>/savedata`）；
 * 标准存档经导入或外部放入后，转化在生效目录内按文件名映射生成 Tyranor 命名文件，源文件移入
 * `original/` 留底。
 * 转化在同目录内按文件名映射生成 Tyranor 命名文件，源文件移入 `<savedata>/original/` 留底。
 *
 * 规则：
 * - 逐文件「复制源内容到目标名 → 源文件移入 `original/` 留底」，内容字节级一致。
 * - 目标文件已存在时**跳过不覆盖**（记为 skipped），源文件同样移入 `original/`。
 * - 单文件失败只影响该文件：源文件原样保留、清理半成品目标，计入 failed。
 * - `key_<sha256>.bin` 哈希存档是 Tyranor 自身写入形态（引擎可按 legacy 名读到），只统计不处理。
 */
object RpgSaveFormatConverter {

    /**
     * 把 [gameRoot] 下的标准存档转化为 Tyranor 格式（非独立存档布局的兼容入口）：
     * 扫描 `<游戏根>/savedata` 与历史 `Savedata/`，输出到引擎读写的 `savedata`。
     *
     * 独立存档开启时引擎实际读写外部目录，调用方应改用 [convert] 传入生效目录。
     *
     * @throws IOException 存档目录无法创建时抛出（调用方据此提示启动失败）。
     */
    @Throws(IOException::class)
    fun convert(gameRoot: File, engine: EngineType): RpgSaveFormat.ConvertResult =
        convert(
            outputDir = RpgSaveFormat.saveDirectory(gameRoot),
            sourceDirs = listOf(RpgSaveFormat.saveDirectory(gameRoot), File(gameRoot, "Savedata")),
            engine = engine,
        )

    /**
     * 把 [sourceDirs] 中检测到的标准存档转化为 Tyranor 格式，输出到 [outputDir]
     * （引擎实际读写的生效存档目录，与同步侧一致）。
     *
     * 规则：
     * - 逐文件「复制源内容到目标名 → 源文件移入其所在目录的 `original/` 留底」，内容字节级一致。
     * - 目标文件已存在时**跳过不覆盖**（记为 skipped），源文件同样移入 `original/`。
     * - 单文件失败只影响该文件：源文件原样保留、清理半成品目标，计入 failed。
     * - `key_<sha256>.bin` 哈希存档是 Tyranor 自身写入形态（引擎可按 legacy 名读到），只统计不处理。
     *
     * @throws IOException [outputDir] 无法创建时抛出（调用方据此提示启动失败）。
     */
    @Throws(IOException::class)
    fun convert(outputDir: File, sourceDirs: List<File>, engine: EngineType): RpgSaveFormat.ConvertResult {
        if (!RpgSaveFormat.isRpgWebEngine(engine)) {
            return RpgSaveFormat.ConvertResult(0, 0, 0, 0)
        }
        val detection = RpgSaveFormat.detectInDirs(sourceDirs, engine)
        if (detection.standardFiles.isEmpty()) {
            return RpgSaveFormat.ConvertResult(0, 0, 0, detection.hashedCount)
        }
        if (!outputDir.exists() && !outputDir.mkdirs() && !outputDir.isDirectory) {
            throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE, detail = outputDir.absolutePath)
        }
        var converted = 0
        var skipped = 0
        var failed = 0
        detection.standardFiles.forEach { source ->
            val targetName = RpgSaveFormat.standardToTyranor(source.name, engine)
            if (targetName == null) {
                failed++
                return@forEach
            }
            val target = File(outputDir, targetName)
            var copied = false
            try {
                if (target.exists()) {
                    // 目标已有 Tyranor 存档：跳过不覆盖，仅把源文件留底
                    if (!moveToOriginal(source)) {
                        failed++
                        return@forEach
                    }
                    skipped++
                } else {
                    copyAtomically(source, target)
                    copied = true
                    // 留底失败不算转化成功：源文件仍在活动目录，下次检测会再次提示转化
                    if (!moveToOriginal(source)) {
                        throw IOException("cannot preserve ${source.absolutePath}")
                    }
                    converted++
                }
            } catch (cancelled: CancellationException) {
                // 取消必须原样传播（AGENT.md：取消不得被吞掉），且不计入 failed
                throw cancelled
            } catch (_: Throwable) {
                // 单文件失败不影响其它文件。复制阶段失败时目标可能是半成品，而源文件仍在原位；
                // 清掉半成品目标以便下次重试——否则残缺目标会被判为「已存在」而跳过，源文件被
                // 移入 original/ 后永久读不到。
                if (copied && source.exists()) {
                    runCatching { target.delete() }
                }
                failed++
            }
        }
        return RpgSaveFormat.ConvertResult(converted, skipped, failed, detection.hashedCount)
    }

    /** 目标已存在时由调用方保证不进入；先写同目录临时文件再 rename，避免留下截断文件。 */
    @Throws(IOException::class)
    private fun copyAtomically(source: File, target: File) {
        val parent = target.parentFile ?: throw IOException("no parent for ${target.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create ${parent.absolutePath}")
        }
        val tmp = File(parent, target.name + ".fmt_tmp." + System.nanoTime())
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) {
                // rename 失败（目标被占用等）只能放弃本文件：直接向最终路径复制不是原子操作，
                // 进程中途被杀会留下半成品目标——外层 catch 此时尚未置 copied，不会清理它，
                // 下次转化会把残缺目标当作「已存在」跳过并把完整源移入 original/，数据卡死。
                // 抛错则源文件原样保留在活动目录，下次重试。
                throw IOException("cannot atomically replace ${target.absolutePath}")
            }
            target.setLastModified(source.lastModified())
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 源文件移入其所在目录的 `original/`；重名追加 `_1`/`_2`… 避免覆盖既有留底。
     *
     * @return 源文件是否已成功留底（不在活动目录）。false 时调用方必须记为失败：
     *   否则结果会报告成功、但标准源文件仍在活动目录，下次检测重复提示转化。
     */
    private fun moveToOriginal(source: File): Boolean {
        if (!source.exists()) return true
        val originalDir = File(source.parentFile, RpgSaveFormat.ORIGINAL_DIR)
        if (!originalDir.isDirectory && !originalDir.mkdirs() && !originalDir.isDirectory) return false
        var target = File(originalDir, source.name)
        var index = 1
        while (target.exists()) {
            val name = source.name
            val dot = name.lastIndexOf('.')
            val candidate = if (dot > 0) {
                name.substring(0, dot) + "_" + index + name.substring(dot)
            } else {
                name + "_" + index
            }
            target = File(originalDir, candidate)
            index++
        }
        if (source.renameTo(target)) return true
        // rename 跨设备/被占用时退回复制+删除；必须确认源已移走才算成功
        runCatching {
            source.copyTo(target, overwrite = false)
            source.delete()
        }
        return !source.exists()
    }
}
