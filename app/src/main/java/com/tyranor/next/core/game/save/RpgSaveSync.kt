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
 *   相同则必须内容哈希一致才跳过（mtime 可被外部改写，不能只信时间戳）。
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

    /** 单个槽位同步后的落盘状态（存在标记 + mtime），见 [RpgSaveSyncState.SlotState]。 */
    private fun slotState(
        stdMtime: Long,
        tyrMtime: Long,
        stdExists: Boolean,
        tyrExists: Boolean,
    ): RpgSaveSyncState.SlotState = RpgSaveSyncState.SlotState(
        standardMtime = stdMtime,
        tyranorMtime = tyrMtime,
        standardExists = stdExists,
        tyranorExists = tyrExists,
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

        // 清单验证必须先行：collectStandard 的冲突隔离会移动文件，「失败不做任何文件改动」
        // 的契约要求清单 Unreadable 时在任何文件操作前中止本轮同步
        val previous: Map<String, RpgSaveSyncState.SlotState> = when (val loaded = stateStore.load(gameKey)) {
            is RpgSaveSyncState.LoadResult.Loaded -> loaded.slots
            is RpgSaveSyncState.LoadResult.Missing -> emptyMap()
            is RpgSaveSyncState.LoadResult.Unreadable -> {
                // core 层不做日志（保持纯 File 依赖、单测可跑）：失败计数由调用方上报
                return Result(failed = 1)
            }
        }

        val preferredStandardDir = standardDirs.first()
        val (standardFiles, quarantinedDuplicates) = collectStandard(standardDirs, engine)
        val tyranorFiles = collectTyranor(tyranorDir, engine)

        var imported = 0
        var exported = 0
        var toTyranor = 0
        var toStandard = 0
        var movedToDeleted = quarantinedDuplicates
        var skipped = 0
        var failed = 0
        val nextState = mutableMapOf<String, RpgSaveSyncState.SlotState>()

        /** 槽位处理失败（内联或异常）：沿用上一次的清单条目，避免「已删除」被误判为「新建」而复活。 */
        fun keepPrevious(slot: String) {
            previous[slot]?.let { nextState[slot] = it }
        }

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
                                // mtime 相同也必须比内容：内容可以在 mtime/长度都不变的情况下被改
                                // （外部编辑器改写后回设时间戳等）。跳过与否只能以内容哈希为准。
                                if (sameContent(std, tyr)) {
                                    skipped++
                                } else {
                                    // 无法判定新旧：以标准侧为准，且**无条件**把 Tyranor 侧留底——
                                    // 平局下较旧一方未知，留底才能保证不丢任何一份
                                    overwriteWithPreserve(
                                        winner = std,
                                        loser = tyr,
                                        loserDir = tyranorDir,
                                        preserveLoserSide = false,
                                        mustPreserve = true,
                                    )
                                    toTyranor++; tyrMtime = s
                                }
                            }
                            s > t -> {
                                // 首次同步该槽位时较旧一方无历史记录，先留底再覆盖，避免误删唯一副本；
                                // 留底失败则中止本次覆盖（两侧原样保留、计入 failed、下轮重试），绝不先毁后写
                                overwriteWithPreserve(
                                    winner = std,
                                    loser = tyr,
                                    loserDir = tyranorDir,
                                    preserveLoserSide = false,
                                    mustPreserve = !hadPrevious,
                                )
                                toTyranor++; tyrMtime = s
                            }
                            else -> {
                                overwriteWithPreserve(
                                    winner = tyr,
                                    loser = std,
                                    loserDir = std.parentFile ?: preferredStandardDir,
                                    preserveLoserSide = true,
                                    mustPreserve = !hadPrevious,
                                )
                                toStandard++; stdMtime = t
                            }
                        }
                    }
                    std != null -> {
                        if (existedOnTyranor) {
                            // Tyranor 侧已删除该槽位 → 标准文件归入 deleted/，不再导回
                            if (moveToDeleted(std, std.parentFile ?: preferredStandardDir)) {
                                movedToDeleted++; stdMtime = 0L; stdExistsNow = false
                            } else {
                                failed++; record = false; keepPrevious(slot)
                            }
                        } else {
                            val name = RpgSaveFormat.tyranorNameForSlot(slot, engine)
                            if (name == null) {
                                failed++; record = false; keepPrevious(slot)
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
                            failed++; record = false; keepPrevious(slot)
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
                // 仅收敛预期的文件异常（IO/权限）：失败不丢历史，沿用上一次的清单条目
                failed++
                record = false
                keepPrevious(slot)
            } catch (_: SecurityException) {
                // SAF 权限中途失效等：同样按单槽位失败处理，不得中断整轮（审查 M5）
                failed++
                record = false
                keepPrevious(slot)
            }
            if (record) {
                // 两侧都不存在的槽位不记录，避免「删档后被再次导入」
                if (stdExistsNow || tyrExistsNow) {
                    nextState[slot] = slotState(stdMtime, tyrMtime, stdExistsNow, tyrExistsNow)
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

    /**
     * 枚举多个标准侧目录（`save`/`Save`）。
     *
     * 同一槽位出现在多个目录时不再静默取先者：内容一致视为等价副本（任取其一）；
     * 内容不一致则把**未选中的副本**隔离进其所在目录的 `deleted/`（保留数据、避免旧副本
     * 长期分叉），再以首选目录的文件为同步源。返回值第二项为隔离数量（映射到结果里的
     * movedToDeleted）；隔离失败则保留原位、以首选目录为准继续同步。
     */
    private fun collectStandard(dirs: List<File>, engine: EngineType): Pair<Map<String, File>, Int> {
        val out = mutableMapOf<String, File>()
        var quarantined = 0
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles().orEmpty().forEach { file ->
                if (!file.isFile) return@forEach
                val slot = RpgSaveFormat.standardSlot(file.name, engine) ?: return@forEach
                val existing = out[slot]
                if (existing == null) {
                    // 首选目录在前：先出现者胜
                    out[slot] = file
                    return@forEach
                }
                // 同一文件（大小写不敏感 FS 上 save==Save）不算重复
                if (samePath(existing, file)) return@forEach
                if (sameContent(existing, file)) return@forEach
                // 冲突副本：隔离到 deleted/ 留证，同步以首选目录文件为准
                if (moveToDeleted(file, file.parentFile ?: dir)) {
                    quarantined++
                }
            }
        }
        return out to quarantined
    }

    /** 路径等价判定：优先 canonicalPath，失败退回大小写不敏感字符串比较。 */
    private fun samePath(a: File, b: File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: Throwable) {
        a.absolutePath.equals(b.absolutePath, ignoreCase = true)
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
     * 覆盖较旧一方：**先**把赢家内容写入目标同目录临时文件，**再**留底输家，**最后**原子替换。
     *
     * 顺序即安全性（PR 审查：失败路径绝不允许出现无法恢复的「目标缺失」）：
     * - 临时文件写入失败 → 输家与目标都原样保留；
     * - 留底失败（[mustPreserve] 时抛错）→ 临时文件清理、目标原样保留；
     * - 原子替换失败 → 留底**回滚**（备份移回原位，恢复「目标在原位」的不变量；
     *   回滚也失败时数据仍在留底目录，配合调用方 keepPrevious 保住清单历史，下一轮重试）。
     * 注意留底之后、替换成功之前，输家数据在留底目录而非目标路径——故替换失败必须回滚，
     * 不能假定目标路径仍有旧内容（审查跟进 #5）。
     */
    @Throws(IOException::class)
    private fun overwriteWithPreserve(
        winner: File,
        loser: File,
        loserDir: File,
        preserveLoserSide: Boolean,
        mustPreserve: Boolean,
    ) {
        // 1. 先写临时文件（此刻还未碰输家与目标）
        val tmp = writeTmpCopy(winner, loser)
        try {
            // 2. 留底输家（失败且必须留底时中止，目标原样保留）
            val backup = if (mustPreserve) preserveLoser(loser, loserDir, standardSide = preserveLoserSide) else null
            try {
                // 3. 原子替换（绝不先删目标）
                replaceAtomically(tmp, loser, winner)
            } catch (t: Throwable) {
                // 替换失败：把留底放回原位，恢复「目标在原位」的不变量（此时输家原路径为空）
                if (backup != null) {
                    runCatching {
                        if (!backup.renameTo(loser)) {
                            backup.copyTo(loser, overwrite = true)
                            backup.delete()
                        }
                    }
                }
                throw t
            }
        } finally {
            discardTmp(tmp)
        }
    }

    /** 把源内容写入目标同目录临时文件并 fsync；失败时清理半成品后原样抛出。 */
    @Throws(IOException::class)
    private fun writeTmpCopy(source: File, target: File): File {
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
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        return tmp
    }

    /**
     * 原子替换目标并同步 mtime：Files.move(REPLACE_EXISTING)，ATOMIC_MOVE 优先。
     * 绝不先删目标——替换失败时目标保持旧内容，源与留底均完好，不存在「两侧都丢」的窗口。
     * （不能用 File.renameTo：Windows 上它不能替换已存在目标。）
     */
    @Throws(IOException::class)
    private fun replaceAtomically(tmp: File, target: File, source: File) {
        try {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        // mtime 同步：失败会使目标 mtime 变成「现在」，可能引发下一轮反向覆盖（乒乓）。
        // 失败时把源 mtime 对齐到目标实际值，保持两侧一致、避免乒乓。
        if (!target.setLastModified(source.lastModified())) {
            val actual = target.lastModified()
            if (actual > 0L) source.setLastModified(actual)
        }
    }

    /** 清理临时文件（替换成功后已不存在）；仅删除匹配临时名模式的文件，防误删。 */
    private fun discardTmp(tmp: File) {
        if (tmp.exists() && tmp.name.contains(".sync_tmp.")) tmp.delete()
    }

    /**
     * 复制覆盖目标（无留底需求的新建/导出路径）：临时文件 + 原子替换，失败时目标原样保留。
     */
    @Throws(IOException::class)
    private fun copyOverwrite(source: File, target: File) {
        val tmp = writeTmpCopy(source, target)
        try {
            replaceAtomically(tmp, target, source)
        } finally {
            discardTmp(tmp)
        }
    }

    /**
     * 首次同步某槽位、需要覆盖较旧一方前，把较旧文件留底，避免因标准侧 mtime 被复制/导入
     * 刷新成「较新」而误删唯一的手机存档。standardSide=true 归入 `<标准侧>/deleted/`，
     * 否则归入 Tyranor 侧 `original/`。
     *
     * @return 留底文件（供替换失败时回滚）；输家不存在（无留底必要）返回 null；
     *   留底失败抛 [IOException]，由调用方中止覆盖——绝不覆盖未留底的较旧副本。
     */
    @Throws(IOException::class)
    private fun preserveLoser(loser: File, sideDir: File, standardSide: Boolean): File? {
        if (!loser.exists()) return null
        val dir = if (standardSide) File(sideDir, DELETED_DIR) else File(sideDir, RpgSaveFormat.ORIGINAL_DIR)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create backup dir ${dir.absolutePath}")
        }
        var target = File(dir, loser.name)
        var index = 1
        while (target.exists()) {
            val name = loser.name
            val dot = name.lastIndexOf('.')
            target = File(dir, if (dot > 0) name.substring(0, dot) + "_" + index + name.substring(dot) else name + "_" + index)
            index++
        }
        if (loser.renameTo(target)) return target
        // 跨设备/被占用时退回复制；必须确认副本完整落盘（存在且字节数一致）且源已移走才算成功
        val copied = runCatching {
            loser.copyTo(target, overwrite = false)
            target.isFile && target.length() == loser.length() && loser.delete()
        }.getOrDefault(false)
        if (!copied) {
            runCatching { target.delete() }
            throw IOException("cannot preserve ${loser.absolutePath} into ${dir.absolutePath}")
        }
        return target
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
