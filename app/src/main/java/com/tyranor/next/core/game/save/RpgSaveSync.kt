package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

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

    /**
     * 进程级互斥：启动前同步、前台兜底回写、存档管理页「立即同步」可能并发触发，
     * 交错复制会破坏「较新者胜」的 mtime 判定并让同步清单互相覆盖。所有同步入口在此
     * 串行化，并与 [RpgSaveSyncState] 的清单读写共用同一把锁（删除游戏时的 clear 也走它）。
     * 调用方均在 IO 线程，锁内是阻塞文件 IO，串行不会阻塞主线程。
     */
    private val syncLock: Any = RpgSaveSyncState.INTEROP_LOCK

    /** 内容比对时的读取缓冲（64 KiB）。 */
    private const val SYNC_HASH_BUFFER_BYTES = 64 * 1024

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

    /** 单个槽位同步后的落盘状态（存在标记 + mtime + size），见 [RpgSaveSyncState.SlotState]。 */
    private fun slotState(
        std: File?,
        tyr: File?,
        stdMtime: Long,
        tyrMtime: Long,
        stdExists: Boolean,
        tyrExists: Boolean,
    ): RpgSaveSyncState.SlotState = RpgSaveSyncState.SlotState(
        standardMtime = stdMtime,
        tyranorMtime = tyrMtime,
        standardExists = stdExists,
        tyranorExists = tyrExists,
        standardSize = if (stdExists) (std?.length() ?: 0L) else 0L,
        tyranorSize = if (tyrExists) (tyr?.length() ?: 0L) else 0L,
    )

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
    ): Result = synchronized(syncLock) {
        syncLocked(standardDirs, tyranorDir, engine, stateStore, gameKey)
    }

    /** [sync] 的实际实现；进入前已持有 [syncLock]，故可安全读写清单与两侧文件。 */
    private fun syncLocked(
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
            // 显式存在标记（而非 mtime>0 反推）：mtime 为 0 的存档不应被当成「从未存在」
            val existedOnTyranor = previous[slot]?.tyranorExists == true
            var stdMtime = std?.lastModified() ?: 0L
            var tyrMtime = tyr?.lastModified() ?: 0L
            // 显式存在标记：不能用 mtime>0 反推（mtime 为 0 的存档会被误判为不存在）。
            // 注意必须随分支更新——导入/导出后对侧文件是新建的，不能沿用同步前的引用判断。
            var stdExistsNow = std != null
            var tyrExistsNow = tyr != null
            var record = true
            try {
                when {
                    std != null && tyr != null -> {
                        val s = std.lastModified()
                        val t = tyr.lastModified()
                        when {
                            s == t -> {
                                // mtime 相同：先看 (mtime,size) 是否都与上次同步后一致——
                                // 两侧都未变动才能免去内容哈希（否则每轮都要哈希全部存档）
                                val metaUnchanged = previous[slot]?.let { prev ->
                                    prev.standardExists == stdExistsNow && prev.tyranorExists == tyrExistsNow &&
                                        prev.standardMtime == s && prev.tyranorMtime == t &&
                                        prev.standardSize == std.length() && prev.tyranorSize == tyr.length()
                                } == true
                                if (metaUnchanged || sameContent(std, tyr)) {
                                    skipped++
                                } else {
                                    // 无法判定新旧：以标准侧为准，且**无条件**把 Tyranor 侧留底——
                                    // 平局下较旧一方未知，留底才能保证不丢任何一份
                                    if (!preserveLoser(tyr, tyranorDir, standardSide = false)) {
                                        throw IOException("cannot preserve ${tyr.absolutePath} before tie-break overwrite")
                                    }
                                    copyOverwrite(std, tyr); toTyranor++; tyrMtime = s
                                }
                            }
                            s > t -> {
                                // 首次同步该槽位时较旧一方无历史记录，先留底再覆盖，避免误删唯一副本；
                                // 留底失败则中止本次覆盖（两侧原样保留、计入 failed、下轮重试），绝不先毁后写
                                if (!hadPrevious && !preserveLoser(tyr, tyranorDir, standardSide = false)) {
                                    throw IOException("cannot preserve ${tyr.absolutePath} before overwrite")
                                }
                                copyOverwrite(std, tyr); toTyranor++; tyrMtime = s
                            }
                            else -> {
                                if (!hadPrevious &&
                                    !preserveLoser(std, std.parentFile ?: preferredStandardDir, standardSide = true)
                                ) {
                                    throw IOException("cannot preserve ${std.absolutePath} before overwrite")
                                }
                                copyOverwrite(tyr, std); toStandard++; stdMtime = t
                            }
                        }
                    }
                    std != null -> {
                        if (existedOnTyranor) {
                            // Tyranor 侧已删除该槽位 → 标准文件归入 deleted/，不再导回
                            if (moveToDeleted(std, std.parentFile ?: preferredStandardDir)) {
                                movedToDeleted++; stdMtime = 0L; stdExistsNow = false
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
                                tyrExistsNow = true
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
                            stdExistsNow = true
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // 取消必须原样传播，不能被当作单个槽位失败吞掉
                throw cancelled
            } catch (_: IOException) {
                // 仅收敛预期的文件异常：失败不丢历史，沿用上一次的清单条目，
                // 避免「已删除」被误判为「新建」而复活
                failed++
                record = false
                previous[slot]?.let { nextState[slot] = it }
            }
            if (record) {
                // 两侧都不存在的槽位不记录，避免「删档后被再次导入」
                if (stdExistsNow || tyrExistsNow) {
                    nextState[slot] = slotState(std, tyr, stdMtime, tyrMtime, stdExistsNow, tyrExistsNow)
                }
            }
        }

        // 清单提交失败必须如实上报：否则「同步成功」却丢了下一次的新建/已删除判定依据
        if (!stateStore.save(gameKey, nextState)) {
            failed++
        }
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
     * mtime 相同时判断两侧内容是否一致：先比长度（廉价，能挡掉多数差异），长度相同再比 SHA-256。
     * 读取失败时返回 false（视为不同）——后续走原子复制，不会截断损坏任一副本。
     */
    private fun sameContent(a: File, b: File): Boolean = runCatching {
        a.length() == b.length() && sha256(a) == sha256(b)
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(SYNC_HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        var committed = false
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            if (tmp.renameTo(target)) {
                committed = true
            } else {
                // rename 失败（目标被占用等）：删除目标后重试一次
                target.delete()
                if (tmp.renameTo(target)) {
                    committed = true
                } else {
                    // 两次 rename 都失败：目标刚被删、源仍在。此时若按常规在 finally 删掉 tmp，
                    // 该槽位将同时没有目标与临时副本（源虽在，但调用方记录为失败后不会立刻重试）。
                    // 故保留 tmp 并抛错：`.sync_tmp` 后缀不匹配任何存档名，不会被列表/导出/同步误认，
                    // 下一次同步因目标缺失会从完好的源重新复制，数据不丢。
                    throw IOException("cannot replace ${target.absolutePath}; kept ${tmp.absolutePath}")
                }
            }
            // mtime 同步：失败会使目标 mtime 变成「现在」，可能引发下一轮反向覆盖（乒乓）。
            // 失败时把源 mtime 对齐到目标实际值，保持两侧一致、避免乒乓。
            if (!target.setLastModified(source.lastModified())) {
                val actual = target.lastModified()
                if (actual > 0L) source.setLastModified(actual)
            }
        } finally {
            // 仅在已提交后清理（rename 成功后 tmp 已不存在）；失败路径刻意保留 tmp
            if (committed && tmp.exists()) tmp.delete()
        }
    }

    /**
     * 首次同步某槽位、需要覆盖较旧一方前，把较旧文件留底，避免因标准侧 mtime 被复制/导入
     * 刷新成「较新」而误删唯一的手机存档。standardSide=true 归入 `<标准侧>/deleted/`，
     * 否则归入 Tyranor 侧 `original/`。
     *
     * @return 是否已成功留底；false 时调用方中止覆盖并计入 failed，绝不覆盖未留底的较旧副本。
     */
    private fun preserveLoser(loser: File, sideDir: File, standardSide: Boolean): Boolean {
        if (!loser.exists()) return true
        val dir = if (standardSide) File(sideDir, DELETED_DIR) else File(sideDir, RpgSaveFormat.ORIGINAL_DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        var target = File(dir, loser.name)
        var index = 1
        while (target.exists()) {
            val name = loser.name
            val dot = name.lastIndexOf('.')
            target = File(dir, if (dot > 0) name.substring(0, dot) + "_" + index + name.substring(dot) else name + "_" + index)
            index++
        }
        if (loser.renameTo(target)) return true
        // 跨设备/被占用时退回复制；必须确认副本完整落盘（存在且字节数一致）才算成功
        return runCatching {
            loser.copyTo(target, overwrite = false)
            target.isFile && target.length() == loser.length()
        }.getOrDefault(false)
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
