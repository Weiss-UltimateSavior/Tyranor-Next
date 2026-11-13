package com.core.rpgmaker

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import org.json.JSONObject

/**
 * localStorage 的「未落盘数据」恢复队列（落盘于 `AppData/Local Storage`）。
 *
 * 背景：`setItem` 是内存镜像 + 合并写，真正的写入发生在 JS 定时器 / 退出钩子里。宿主在
 * 退出或重建时会销毁 WebView，销毁后 JS 内存与定时器一起消失——`flushAll` 一旦没写完
 * （预算耗尽、单键 I/O 失败、文件系统暂时不可写），剩下的值就永久丢失。
 *
 * 因此收尾改成「先入队、再落盘」：入队是一次小的原子写（比几十次 fsync 更可能在坏盘上
 * 成功），落盘失败或被打断都不再丢数据；下一次文档初始化时在游戏脚本之前重放。
 *
 * 重放的安全条件（缺一就会把新值盖回旧值）：
 *  1. 该条目入队之后目标文件没有被改过——比对 `(存在, size, mtime)` 与内容 CRC32
 *     （FAT/exFAT 的 mtime 粒度约 2s，同毫秒同长度改写只靠 mtime 分不出来）；
 *  2. 该条目所属会话没有被更新的会话写过该路径（进程内的账本判定）。
 *
 * 期号是**进程内**单调的，因此队列里还带一个进程标识 `run`：来自上一个进程的条目一律
 * 按「最旧」处理（session=0），否则上一进程留下的大期号会把本进程的判定压死。同一进程
 * 内按条目期号判序，晚到的旧文档入队不能顶掉新文档已入队的同名条目。
 */
internal object RpgMakerStoragePendingJournal {

    const val FILE_NAME = ".tyranor-pending.json"

    /** 正在重放的队列副本：先改名占有、锁外逐条应用、结束后删除（避免长时间持队列锁）。 */
    private const val CLAIM_NAME = ".tyranor-pending.claim"

    private const val TAG = "YukiRpgMaker"

    /** 随机进程标识，跨进程条目归零，避免复用旧会话期号。 */
    private val RUN_ID: String = UUID.randomUUID().toString()

    /** 条目数上限：正常待写键只有几十个，超过说明调用方出了问题。 */
    private const val MAX_ENTRIES = 4096

    /**
     * 单条值上限（字符）。取值保证 `MAX_VALUE_CHARS * 3 ≤ MAX_CRC_BYTES`：
     * 任何被接受的值（最坏 UTF-8 三字节/字符）都还在计算内容指纹的范围内。
     */
    private const val MAX_VALUE_CHARS = 2 * 1024 * 1024

    /** 队列总字符上限（按合并结果计，不是按单次入参计）。 */
    private const val MAX_TOTAL_CHARS = 12L * 1024L * 1024L

    /** 队列文件大小上限（字节）：写入前与读取前都校验，避免「写进去却读不出来」。 */
    private const val MAX_JOURNAL_BYTES = 36L * 1024L * 1024L

    /** 参与内容校验的文件上限；见 [MAX_VALUE_CHARS] 的不变量。 */
    private const val MAX_CRC_BYTES = 8L * 1024L * 1024L

    /**
     * 合法文件名：`<UTF-8 十六进制的键>.dat`，或超长键的 `h<base36>.dat`。
     * 只接受这一种形态，杜绝 `..`、绝对路径与目录分隔符借恢复队列越界。
     */
    private val NAME_PATTERN = Regex("^(?:[0-9a-f]{0,180}|h[0-9a-z]{1,64})\\.dat$")

    /** 队列文件级的互斥：读改写在同一个锁内，避免旧会话的收尾与新会话的重放互相覆盖。 */
    private val lock = Any()

    /** 一条待恢复项；[value] 为 null 表示删除意图。 */
    internal class Entry(
        val value: String?,
        val existed: Boolean,
        val mtime: Long,
        val size: Long,
        /** 入队时目标文件内容的 CRC32；文件不存在时为 null。 */
        val crc: Long?,
        /** 入队它的会话期号；来自上一个进程的条目为 0（视为最旧）。 */
        val session: Long,
        /** 已在 Storage 返回前接收的新意图；恢复时不再把自身失败物化误判为外部改写。 */
        val accepted: Boolean = false,
    )

    /** 队列快照。 */
    internal class Snapshot(val entries: Map<String, Entry>)

    /**
     * 读取队列（只读，不发占有）。调用方都持有 [lock]；重放本身用 [claim] 而非本方法。
     */
    private fun read(storeDir: File): Snapshot? = parse(File(storeDir, FILE_NAME))

    internal fun isStorageName(name: String): Boolean = NAME_PATTERN.matches(name)

    internal fun pendingEntries(storeDir: File): LinkedHashMap<String, Entry> = synchronized(lock) {
        val merged = LinkedHashMap<String, Entry>()
        parse(File(storeDir, CLAIM_NAME))?.entries?.forEach { (name, entry) -> merged[name] = entry }
        read(storeDir)?.entries?.forEach { (name, entry) ->
            if ((merged[name]?.session ?: Long.MIN_VALUE) <= entry.session) merged[name] = entry
        }
        merged
    }

    /** 先退休旧 claim，再发布新意图；退休失败不能使本次接收出现「返回失败却已修改」。 */
    private fun retireClaim(storeDir: File): LinkedHashMap<String, Entry> {
        val merged = pendingEntries(storeDir)
        val claim = File(storeDir, CLAIM_NAME)
        if (claim.exists()) {
            if (!writeSnapshot(File(storeDir, FILE_NAME), merged)) throw IOException("Storage claim migration failed")
            deleteQuietly(claim)
        }
        return merged
    }

    /**
     * 把一批待写值与删除意图并入队列（同名的旧条目只在期号不小于新条目时保留）。
     *
     * 入队时记录目标文件当前的 `(存在, size, mtime, crc)`：重放前若发现文件已变化，
     * 说明入队之后有别的会话写过它，这一条必须作废。
     *
     * 同名条目若**值没有变化**（同一批键在 pagehide / beforeunload / 宿主 onDestroy 会被
     * 重复入队），保留首次的指纹：重新拍快照会让「入队后文件被改过」这条判据失效。
     */
    fun stage(
        storeDir: File,
        session: Long,
        writes: Map<String, String>,
        deletes: Collection<String>,
        accepted: Boolean = false,
    ): Boolean =
        synchronized(lock) {
            if (writes.isEmpty() && deletes.isEmpty()) return true
            try {
                cleanupTempFiles(storeDir)
                val merged = retireClaim(storeDir)
                var rejected = 0
                writes.forEach { (name, value) ->
                    if (!NAME_PATTERN.matches(name)) {
                        rejected++
                        return@forEach
                    }
                    if (value.length > MAX_VALUE_CHARS) {
                        Log.w(TAG, "pending storage value rejected (too large ${value.length} chars): $name")
                        rejected++
                        return@forEach
                    }
                    val existing = merged[name]
                    merged[name] = when {
                        // 更新的会话已经入队过同名条目：旧文档晚到的入队必须让位
                        existing != null && existing.session > session -> existing
                        !accepted && existing != null && existing.value == value -> existing
                        else -> fingerprinted(storeDir, name, value, session, accepted)
                    }
                }
                deletes.forEach { name ->
                    if (!NAME_PATTERN.matches(name)) {
                        rejected++
                        return@forEach
                    }
                    val existing = merged[name]
                    if (existing != null) {
                        // 更新的会话或同值删除都保留原条目
                        if (existing.session > session) return@forEach
                        if (!accepted && existing.value == null) return@forEach
                    }
                    merged[name] = fingerprinted(storeDir, name, null, session, accepted)
                }
                // 有被拒条目就整份不写：写入一部分却报「已持久化」，会让宿主把真实丢失当成 0
                if (rejected > 0) {
                    Log.w(TAG, "pending storage journal rejected (invalid or oversized $rejected entries)")
                    return false
                }
                val mergedChars = merged.values.sumOf { (it.value?.length ?: 0).toLong() }
                if (merged.size > MAX_ENTRIES || mergedChars > MAX_TOTAL_CHARS) {
                    Log.w(TAG, "pending storage journal rejected (entries=${merged.size} chars=$mergedChars)")
                    return false
                }
                writeSnapshot(File(storeDir, FILE_NAME), merged)
            } catch (error: Throwable) {
                Log.w(TAG, "pending storage stage failed: $storeDir", error)
                false
            }
        }

    /** 丢弃指定会话及更旧会话的条目（`Storage.clear()`：不清掉更新文档刚交进来的数据）。 */
    fun clearUpTo(storeDir: File, session: Long): Boolean = synchronized(lock) {
        try {
            val existing = retireClaim(storeDir)
            val remaining = existing.filterValues { it.session > session }
            if (remaining.size == existing.size) return true
            if (remaining.isEmpty()) {
                deleteQuietly(File(storeDir, FILE_NAME))
                true
            } else {
                writeSnapshot(File(storeDir, FILE_NAME), remaining)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "pending storage clear failed: $storeDir", error)
            false
        }
    }

    /**
     * 丢弃指定文件名的条目。
     *
     * 用于 `Storage.removeItem()`：入队之后同一个键又被删除时，队列里那份旧值已经失效，
     * 若继续保留，重放会因为「目标文件仍不存在、快照匹配」而把已删的键写回来。
     *
     * [expectations] 的值为该条目应当持有的内容：只有内容一致才丢弃。值为 null 表示
     * 不比对内容、直接丢弃（`removeItem` 的「这个键必须不存在」）。两种情况下都只处理
     * [session] 及更旧会话的条目，避免旧文档删掉新文档刚交进来的数据。
     */
    fun discard(storeDir: File, session: Long, expectations: Map<String, String?>): Boolean =
        synchronized(lock) {
            if (expectations.isEmpty()) return true
            try {
                val existing = retireClaim(storeDir)
                val remaining = existing.filter { (name, entry) ->
                    if (!expectations.containsKey(name)) return@filter true
                    if (entry.session > session) return@filter true
                    val expected = expectations[name]
                    expected != null && entry.value != expected
                }
                if (remaining.size == existing.size) return true
                if (remaining.isEmpty()) {
                    deleteQuietly(File(storeDir, FILE_NAME))
                    true
                } else {
                    writeSnapshot(File(storeDir, FILE_NAME), remaining)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "pending storage discard failed: $storeDir", error)
                false
            }
        }

    /**
     * 占有当前队列用于重放：在锁内把队列并入 `.claim` 并改名占有，调用方随后在锁外逐条
     * 应用、结束后调用 [finish]。
     *
     * 这样队列锁只被持有一次「读 + 写 claim」，另一个文档的 `stagePendingStorage`（同步的
     * `@JavascriptInterface`，跑在渲染线程上）不会被整段重放堵住。
     */
    fun claim(storeDir: File): Snapshot? = synchronized(lock) {
        val journal = File(storeDir, FILE_NAME)
        val claim = File(storeDir, CLAIM_NAME)
        try {
            val leftover = if (claim.isFile) parse(claim) else null
            val current = if (journal.isFile) parse(journal) else null
            if (leftover == null && current == null) {
                deleteQuietly(claim)
                deleteQuietly(journal)
                return null
            }
            // 队列里的条目（更晚入队）覆盖遗留 claim 的同名条目
            val merged = LinkedHashMap<String, Entry>()
            leftover?.entries?.forEach { (name, entry) -> merged[name] = entry }
            current?.entries?.forEach { (name, entry) ->
                if ((merged[name]?.session ?: Long.MIN_VALUE) <= entry.session) merged[name] = entry
            }
            if (merged.isEmpty()) {
                deleteQuietly(claim)
                deleteQuietly(journal)
                return null
            }
            if (!writeSnapshot(claim, merged)) {
                Log.w(TAG, "pending storage claim write failed: ${claim.path}")
                throw IOException("pending storage claim write failed: ${claim.path}")
            }
            deleteQuietly(journal)
            Snapshot(merged)
        } catch (error: Throwable) {
            Log.w(TAG, "pending storage claim failed: $storeDir", error)
            throw error
        }
    }

    /**
     * 结束一次重放：把没有恢复成功的条目并回队列（队列里已有的同名条目优先，它更新），
     * 然后删除 `.claim`。
     *
     * 合并写失败时**保留** `.claim`：下次 [claim] 会把它当作遗留副本继续重放，
     * 而不是让这些没恢复成功的条目随 claim 一起消失。
     */
    fun finish(storeDir: File, failed: Map<String, Entry>): Boolean = synchronized(lock) {
        val claim = File(storeDir, CLAIM_NAME)
        val requeued = try {
            if (failed.isEmpty()) {
                true
            } else {
                val merged = LinkedHashMap<String, Entry>()
                read(storeDir)?.entries?.forEach { (name, entry) -> merged[name] = entry }
                failed.forEach { (name, entry) ->
                    if ((merged[name]?.session ?: Long.MIN_VALUE) < entry.session) merged[name] = entry
                }
                writeSnapshot(File(storeDir, FILE_NAME), merged)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "pending storage finish failed: $storeDir", error)
            false
        }
        if (requeued) deleteQuietly(claim)
        requeued
    }

    private fun parse(file: File): Snapshot? {
        if (!file.exists()) return null
        if (!file.isFile || file.length() > MAX_JOURNAL_BYTES) {
            throw IOException("pending storage journal unreadable: ${file.path}")
        }
        return parseContent(file)
    }

    private fun parseContent(file: File): Snapshot? {
        val raw = String(file.readBytes(), StandardCharsets.UTF_8)
        val json = JSONObject(raw)
        // 没有 run 标识的旧格式同样归零；不删除损坏队列，接收/重放向调用方报告失败。
        val sameRun = json.optString("run", "") == RUN_ID
        val entriesJson = json.getJSONObject("entries")
        if (entriesJson.length() > MAX_ENTRIES) throw IOException("Too many Storage entries")
        val out = LinkedHashMap<String, Entry>()
        val names = entriesJson.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (!NAME_PATTERN.matches(name)) throw IOException("Invalid Storage journal entry")
            val item = entriesJson.getJSONObject(name)
            if (item.has("d") && item.get("d") !is Boolean) throw IOException("Invalid Storage delete flag")
            if (item.has("a") && item.get("a") !is Boolean) throw IOException("Invalid Storage acceptance flag")
            val deleted = item.optBoolean("d", false)
            if (!deleted && (!item.has("v") || item.get("v") !is String)) {
                throw IOException("Missing Storage value")
            }
            if (item.get("e") !is Boolean || item.get("m") !is Number || item.get("s") !is Number) {
                throw IOException("Invalid Storage fingerprint")
            }
            if (item.getLong("m") < 0 || item.getLong("s") < 0) throw IOException("Invalid Storage file metadata")
            if (item.has("n") && (item.get("n") !is Number || item.getLong("n") < 0)) {
                throw IOException("Invalid Storage session")
            }
            if (item.has("c") && (item.get("c") !is Number || item.getLong("c") < 0)) {
                throw IOException("Invalid Storage checksum")
            }
            val value = if (deleted) null else item.getString("v")
            if (value != null && value.length > MAX_VALUE_CHARS) throw IOException("Storage value too large")
            out[name] = Entry(
                value = value,
                existed = item.optBoolean("e", false),
                mtime = item.optLong("m", 0L),
                size = item.optLong("s", 0L),
                crc = if (item.has("c")) item.optLong("c") else null,
                session = if (sameRun) item.optLong("n", 0L) else 0L,
                accepted = item.optBoolean("a", false),
            )
        }
        if (out.values.sumOf { (it.value?.length ?: 0).toLong() } > MAX_TOTAL_CHARS) {
            throw IOException("Storage journal values too large")
        }
        return Snapshot(out)
    }

    private fun fingerprinted(
        storeDir: File, name: String, value: String?, session: Long, accepted: Boolean,
    ): Entry {
        val file = File(storeDir, name)
        val exists = file.exists()
        val size = if (exists) file.length() else 0L
        return Entry(
            value = value,
            existed = exists,
            mtime = if (exists) file.lastModified() else 0L,
            size = size,
            crc = if (!accepted && file.isFile && size <= MAX_CRC_BYTES) {
                crc32Of(file) ?: throw IOException("Storage fingerprint unavailable")
            } else null,
            session = session,
            accepted = accepted,
        )
    }

    /** 文件内容 CRC32；读失败返回 null（调用方据此把条目视为「未确认」而非「已过期」）。 */
    internal fun crc32Of(file: File): Long? = try {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        crc.value
    } catch (error: Throwable) {
        Log.w(TAG, "pending storage fingerprint failed: ${file.path}", error)
        null
    }

    private fun toJson(entry: Entry): JSONObject {
        val json = JSONObject()
            .put("e", entry.existed)
            .put("m", entry.mtime)
            .put("s", entry.size)
            .put("n", entry.session)
            .put("a", entry.accepted)
        entry.crc?.let { json.put("c", it) }
        if (entry.value == null) json.put("d", true) else json.put("v", entry.value)
        return json
    }

    /** 把整份条目写回队列（调用方必须持有 [lock]）。 */
    private fun writeSnapshot(target: File, entries: Map<String, Entry>): Boolean {
        val jsonEntries = LinkedHashMap<String, JSONObject>()
        entries.forEach { (name, entry) -> jsonEntries[name] = toJson(entry) }
        val payload = JSONObject()
            .put("run", RUN_ID)
            .put("entries", JSONObject(jsonEntries as Map<*, *>))
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        // 写入与读取用同一字节上限：否则「写进去却读不出来」会被当成空队列静默丢弃
        if (bytes.size > MAX_JOURNAL_BYTES) {
            Log.w(TAG, "pending storage journal too large (${bytes.size} bytes): ${target.path}")
            return false
        }
        return writeAtomically(target, bytes)
    }

    /**
     * 清掉上次进程被杀时残留的临时文件（`.tyranor-pending.json.tmp.<nanos>`）。
     * 只在入队时扫一次：目录里通常只有几十个 `.dat`，成本可忽略。
     */
    private fun cleanupTempFiles(storeDir: File) {
        val prefix = FILE_NAME + ".tmp."
        try {
            storeDir.listFiles()?.forEach { candidate ->
                if (candidate.isFile && candidate.name.startsWith(prefix)) candidate.delete()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "pending storage temp cleanup failed: ${storeDir.path}", error)
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists() && !file.delete()) throw IOException("pending storage file not removable: ${file.path}")
        } catch (error: Throwable) {
            Log.w(TAG, "pending storage file not removable: ${file.path}", error)
            throw error
        }
    }

    /**
     * 原子写：临时文件 + fsync + 原子替换。
     *
     * 不要「先删旧文件再改名」：那条路径一旦第二次改名失败，旧队列与新内容会一起消失。
     * `Files.move(ATOMIC_MOVE)` 在支持它的文件系统上直接替换，失败时旧内容原样保留。
     */
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        var temp: File? = null
        return try {
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
            val tmp = File(parent, target.name + ".tmp." + System.nanoTime())
            temp = tmp
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!moveReplacing(tmp, target)) {
                Log.w(TAG, "pending storage journal replace failed: ${target.path}")
                return false
            }
            temp = null
            true
        } catch (error: Throwable) {
            Log.w(TAG, "pending storage journal write failed: ${target.path}", error)
            false
        } finally {
            temp?.let { runCatching { it.delete() } }
        }
    }

    private fun moveReplacing(source: File, target: File): Boolean {
        return try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (error: Throwable) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            } catch (fallbackError: Throwable) {
                Log.w(TAG, "pending storage journal move failed: ${target.path}", fallbackError)
                false
            }
        }
    }
}
