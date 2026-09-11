package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * RPG Maker MV/MZ 标准存档（JoiPlay/PC）→ Tyranor 格式的转化：纯改名，不重编码。
 *
 * 源标准存档可能出现在引擎存档目录（`<游戏根>/savedata`）或 PC 存档目录
 * （`<内容根>/save`）；输出的 Tyranor 命名文件统一写入**引擎存档目录**，
 * 这样引擎（读取 `<游戏根>/savedata`）才能读到。
 *
 * 规则：
 * - 逐文件「复制源内容到目标名 → 源文件移入其所在目录的 `original/` 留底」，内容字节级一致。
 * - 目标文件已存在时**跳过不覆盖**（记为 skipped），源文件同样移入 `original/`。
 * - 单文件失败只影响该文件：源文件原样保留、清理半成品目标，计入 failed。
 * - `key_<sha256>.bin` 哈希存档是 Tyranor 自身写入形态（引擎可按 legacy 名读到），只统计不处理。
 */
object RpgSaveFormatConverter {

    /**
     * 把 [gameRoot] 下（引擎/PC 两处）检测到的标准存档转化为 Tyranor 格式，输出到引擎存档目录。
     *
     * @throws IOException 引擎存档目录无法创建时抛出（调用方据此提示启动失败）。
     */
    @Throws(IOException::class)
    fun convert(gameRoot: File, engine: EngineType): RpgSaveFormat.ConvertResult {
        if (!RpgSaveFormat.isRpgWebEngine(engine)) {
            return RpgSaveFormat.ConvertResult(0, 0, 0, 0)
        }
        val detection = RpgSaveFormat.detect(gameRoot, engine)
        if (detection.standardFiles.isEmpty()) {
            return RpgSaveFormat.ConvertResult(0, 0, 0, detection.hashedCount)
        }
        val outputDir = RpgSaveFormat.engineSaveDirectory(gameRoot)
        if (!outputDir.exists() && !outputDir.mkdirs() && !outputDir.isDirectory) {
            throw IOException("save directory unavailable: ${outputDir.absolutePath}")
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
                    skipped++
                } else {
                    copyAtomically(source, target)
                    copied = true
                    converted++
                }
                moveToOriginal(source)
            } catch (_: Throwable) {
                // 单文件失败不影响其它文件。复制阶段失败时目标可能是半成品，而源文件仍在原位；
                // 清掉半成品目标以便下次重试——否则残缺目标会被判为「已存在」而跳过，源文件被
                // 移入 original/ 后永久读不到。
                if (copied && source.exists()) {
                    runCatching { target.delete() }
                    converted--
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
                // rename 失败（目标被占用等）退回直接复制，保底不丢数据
                source.copyTo(target, overwrite = false)
                tmp.delete()
            }
            target.setLastModified(source.lastModified())
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 源文件移入其所在目录的 `original/`；重名追加 `_1`/`_2`… 避免覆盖既有留底。 */
    private fun moveToOriginal(source: File) {
        if (!source.exists()) return
        val originalDir = File(source.parentFile, RpgSaveFormat.ORIGINAL_DIR)
        if (!originalDir.isDirectory && !originalDir.mkdirs() && !originalDir.isDirectory) return
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
        if (!source.renameTo(target)) {
            // rename 跨设备/被占用时退回复制+删除
            runCatching {
                source.copyTo(target, overwrite = false)
                source.delete()
            }
        }
    }
}
