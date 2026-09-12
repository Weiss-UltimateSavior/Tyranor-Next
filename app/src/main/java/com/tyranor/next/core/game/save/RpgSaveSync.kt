package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * MV/MZ 存档互通：在「标准侧」与「Tyranor 侧」之间双向同步存档。
 *
 * 两侧内容字节级一致，仅文件名不同（映射见 [RpgSaveFormat]），同步 = 按文件名映射复制。
 * 标准侧始终保留，供 JoiPlay/PC 使用。
 *
 * 判定规则（逐槽位，槽位 = `global`/`config`/`fileN`，MV 备份 `.bak`）：
 * - 两侧都有：修改时间不同则**较新者覆盖较旧者**（复制后把目标 mtime 设为源 mtime，保证下一轮幂等）；
 *   相同则跳过。
 * - 仅标准侧有：清单显示该槽位曾在 Tyranor 存在 ⇒ 判为「Tyranor 侧已删除」→
 *   把标准文件移入 `<标准侧>/deleted/`；否则视为新 PC 存档 → 导入 Tyranor。
 * - 仅 Tyranor 侧有：清单显示标准侧曾存在 ⇒（外部删除标准档）按既定策略**保留 Tyranor 并重新导出**，
 *   不删手机存档；否则视为 Tyranor 新存档 → 导出标准侧。
 *
 * 两侧都不存在的槽位不记录，避免「删档后被再次导入」。
 */
object RpgSaveSync {

    /** 删除归置子目录（位于标准存档目录内）。 */
    const val DELETED_DIR = "deleted"

    data class Result(
        /** 新 PC 存档导入到 Tyranor 的文件数。 */
        val imported: Int = 0,
        /** Tyranor 存档导出到标准侧的文件数。 */
        val exported: Int = 0,
        /** 两侧都有、标准侧较新而覆盖 Tyranor 的文件数。 */
        val toTyranor: Int = 0,
        /** 两侧都有、Tyranor 较新而覆盖标准侧的文件数。 */
        val toStandard: Int = 0,
        /** Tyranor 侧已删除、标准侧对应文件归入 `deleted/` 的数量。 */
        val movedToDeleted: Int = 0,
        /** 两侧一致、无需处理的数量。 */
        val skipped: Int = 0,
        /** Tyranor 侧无法反解的哈希名数量（插件自定义键，保留原名，仅报告）。 */
        val unmapped: Int = 0,
        /** 处理失败数（源文件保留，计入下次重试）。 */
        val failed: Int = 0,
    ) {
        val changed: Int get() = imported + exported + toTyranor + toStandard + movedToDeleted
    }

    /**
     * 执行一次双向同步。
     *
     * @param standardDir 标准侧目录（`<内容根>/save`）。
     * @param tyranorDir Tyranor 侧有效存档目录（`<游戏根>/savedata`，或开启独立存档时的外部目录）。
     * @param stateStore 同步清单（区分「新建」与「已删除」）。
     * @param gameKey 清单键（用 game.uri 即可）。
     */
    fun sync(
        standardDir: File,
        tyranorDir: File,
        engine: EngineType,
        stateStore: RpgSaveSyncState,
        gameKey: String,
    ): Result = sync(listOf(standardDir), tyranorDir, engine, stateStore, gameKey)

    /**
     * @param standardDirs 标准侧候选目录，第一个为默认写入目录（兼容 `save` / `Save`）；
     *   某个槽位若已存在于非首选目录，则就地更新，避免产生重复文件。
     */
    fun sync(
        standardDirs: List<File>,
        tyranorDir: File,
        engine: EngineType,
        stateStore: RpgSaveSyncState,
        gameKey: String,
    ): Result {
        if (!RpgSaveFormat.isRpgWebEngine(engine)) return Result()
        if (standardDirs.isEmpty()) return Result()

        val preferredStandardDir = standardDirs.first()
        val standardFiles = collectStandard(standardDirs, engine)
        val tyranorFiles = collectTyranor(tyranorDir, engine)
        val previous = stateStore.load(gameKey)

        // 槽位已存在的标准文件所在目录（就地更新），否则用首选目录
        fun standardParentFor(slot: String): File =
            standardFiles[slot]?.parentFile ?: preferredStandardDir

        var imported = 0
        var exported = 0
        var toTyranor = 0
        var toStandard = 0
        var movedToDeleted = 0
        var skipped = 0
        var failed = 0
        val nextState = mutableMapOf<String, RpgSaveSyncState.SlotState>()

        for (slot in (standardFiles.keys + tyranorFiles.keys).sorted()) {
            val std = standardFiles[slot]
            val tyr = tyranorFiles[slot]
            val hadPrevious = previous.containsKey(slot)
            val existedOnTyranor = (previous[slot]?.tyranorMtime ?: 0L) > 0L
            var stdMtime = std?.lastModified() ?: 0L
            var tyrMtime = tyr?.lastModified() ?: 0L
            var record = true
            try {
                when {
                    std != null && tyr != null -> {
                        val s = std.lastModified()
                        val t = tyr.lastModified()
                        when {
                            s == t -> skipped++
                            s > t -> {
                                // 首次同步该槽位时较旧的一方无历史记录，先留底再覆盖，避免误删唯一副本
                                if (!hadPrevious) preserveLoser(tyr, tyranorDir, standardSide = false)
                                copyOverwrite(std, tyr); toTyranor++; tyrMtime = s
                            }
                            else -> {
                                if (!hadPrevious) preserveLoser(std, std.parentFile ?: preferredStandardDir, standardSide = true)
                                copyOverwrite(tyr, std); toStandard++; stdMtime = t
                            }
                        }
                    }
                    std != null -> {
                        if (existedOnTyranor) {
                            // Tyranor 侧已删除该槽位 → 标准文件归入 deleted/，不再导回
                            if (moveToDeleted(std, std.parentFile ?: preferredStandardDir)) {
                                movedToDeleted++; stdMtime = 0L
                            } else {
                                failed++; record = false
                            }
                        } else {
                            val name = RpgSaveFormat.tyranorNameForSlot(slot, engine)
                            if (name == null) {
                                failed++; record = false
                            } else {
                                val target = File(tyranorDir, name)
                                copyOverwrite(std, target); imported++; tyrMtime = stdMtime
                            }
                        }
                    }
                    tyr != null -> {
                        val name = RpgSaveFormat.standardNameForSlot(slot, engine)
                        if (name == null) {
                            failed++; record = false
                        } else {
                            // 标准侧缺失：无论外部删除还是 Tyranor 新建，都导出到标准侧（首选目录）
                            val target = File(preferredStandardDir, name)
                            copyOverwrite(tyr, target); exported++; stdMtime = tyrMtime
                        }
                    }
                }
            } catch (_: Throwable) {
                // 失败不丢历史：沿用上一次的清单条目，避免「已删除」被误判为「新建」而复活
                failed++
                record = false
                previous[slot]?.let { nextState[slot] = it }
            }
            if (record) {
                // 只要有任一侧仍存在就记录（mtime 不可用记 0，仍能识别后续删除）
                val stillExists = std?.exists() == true || tyr?.exists() == true
                if (stillExists) {
                    nextState[slot] = RpgSaveSyncState.SlotState(stdMtime, tyrMtime)
                }
            }
        }

        stateStore.save(gameKey, nextState)
        return Result(
            imported = imported,
            exported = exported,
            toTyranor = toTyranor,
            toStandard = toStandard,
            movedToDeleted = movedToDeleted,
            skipped = skipped,
            unmapped = tyranorUnmappedCount(tyranorDir, engine),
            failed = failed,
        )
    }

    /** 枚举多个标准侧目录（`save`/`Save`）；同槽位多个文件时优先首选目录（列表靠前者）。 */
    private fun collectStandard(dirs: List<File>, engine: EngineType): Map<String, File> {
        val out = mutableMapOf<String, File>()
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles().orEmpty().forEach { file ->
                if (!file.isFile) return@forEach
                val slot = RpgSaveFormat.standardSlot(file.name, engine) ?: return@forEach
                // 首选目录在前，putIfAbsent 使先出现者胜
                out.putIfAbsent(slot, file)
            }
        }
        return out
    }

    private fun collectTyranor(dir: File, engine: EngineType): Map<String, File> {
        if (!dir.isDirectory) return emptyMap()
        val out = mutableMapOf<String, File>()
        dir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            val slot = RpgSaveFormat.tyranorSlot(file.name, engine) ?: return@forEach
            val existing = out[slot]
            if (existing == null) {
                out[slot] = file
            } else {
                // 同槽位多文件（如 RPG File3.bin 与 key_<hash>.bin 并存）：优先非哈希的规范名
                if (RpgSaveFormat.isHashedTyranorName(existing.name) && !RpgSaveFormat.isHashedTyranorName(file.name)) {
                    out[slot] = file
                }
            }
        }
        return out
    }

    /** 统计无法反解为槽位的哈希存档（插件自定义键）：保留原位，仅报告。 */
    private fun tyranorUnmappedCount(dir: File, engine: EngineType): Int {
        if (!dir.isDirectory) return 0
        return dir.listFiles().orEmpty().count { file ->
            file.isFile && RpgSaveFormat.isHashedTyranorName(file.name) &&
                RpgSaveFormat.tyranorSlot(file.name, engine) == null
        }
    }

    /**
     * 复制覆盖目标；先写同目录临时文件再 rename，并把目标 mtime 设为源 mtime。
     * 绝不就地覆盖目标：即使 rename 失败也重试（先删目标再 rename），避免留下比源更新的
     * 截断文件——否则下一轮「较新者胜」会用残缺内容覆盖完好的源，造成数据丢失。
     */
    @Throws(IOException::class)
    private fun copyOverwrite(source: File, target: File) {
        val parent = target.parentFile ?: throw IOException("no parent for ${target.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create ${parent.absolutePath}")
        }
        val tmp = File(parent, target.name + ".sync_tmp." + System.nanoTime())
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) {
                // rename 失败（目标被占用等）：删除目标后重试一次；仍失败则抛错（调用方保留清单、下轮重试）
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw IOException("cannot replace ${target.absolutePath}")
                }
            }
            // mtime 同步：失败会使目标 mtime 变成「现在」，可能引发下一轮反向覆盖（乒乓）。
            // 失败时把源 mtime 对齐到目标实际值，保持两侧一致、避免乒乓。
            if (!target.setLastModified(source.lastModified())) {
                val actual = target.lastModified()
                if (actual > 0L) source.setLastModified(actual)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 首次同步某槽位、需要覆盖较旧一方前，把较旧文件留底，避免因标准侧 mtime 被复制/导入
     * 刷新成「较新」而误删唯一的手机存档。standardSide=true 归入 `<标准侧>/deleted/`，
     * 否则归入 Tyranor 侧 `original/`。留底失败不阻断（后续覆盖照常，但会如实计数为失败）。
     */
    private fun preserveLoser(loser: File, sideDir: File, standardSide: Boolean) {
        if (!loser.exists()) return
        val dir = if (standardSide) File(sideDir, DELETED_DIR) else File(sideDir, RpgSaveFormat.ORIGINAL_DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return
        var target = File(dir, loser.name)
        var index = 1
        while (target.exists()) {
            val name = loser.name
            val dot = name.lastIndexOf('.')
            target = File(dir, if (dot > 0) name.substring(0, dot) + "_" + index + name.substring(dot) else name + "_" + index)
            index++
        }
        if (!loser.renameTo(target)) {
            runCatching { loser.copyTo(target, overwrite = false) }
        }
    }

    /** 把标准文件移入 `<standardDir>/deleted/`；重名追加 `_1`/`_2`…。仅当源确实已移走才算成功。 */
    private fun moveToDeleted(source: File, standardDir: File): Boolean {
        val dir = File(standardDir, DELETED_DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        var target = File(dir, source.name)
        var index = 1
        while (target.exists()) {
            val name = source.name
            val dot = name.lastIndexOf('.')
            val candidate = if (dot > 0) {
                name.substring(0, dot) + "_" + index + name.substring(dot)
            } else {
                name + "_" + index
            }
            target = File(dir, candidate)
            index++
        }
        if (source.renameTo(target)) return true
        // 跨设备/被占用时退回复制；必须确认源已删除，否则视为失败（避免「已删」误判后复活）
        runCatching {
            source.copyTo(target, overwrite = false)
            source.delete()
        }
        return !source.exists()
    }
}
