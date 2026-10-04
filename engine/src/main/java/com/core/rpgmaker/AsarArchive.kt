package com.core.rpgmaker

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Minimal ASAR reader for Tyrano/NW.js style packages.
 *
 * Supports the common Electron ASAR layout used by Tyranor:
 * - header magic/version = 4
 * - header size
 * - json length
 * - header json
 * - file data area
 */
class AsarArchive @Throws(Exception::class) constructor(file: File?) : Closeable {

    private val archiveFile: File?
    private val raf: RandomAccessFile
    private val dataOffset: Long
    private val entries = HashMap<String, Entry>()

    init {
        archiveFile = file?.canonicalFile
        if (archiveFile == null || !archiveFile.isFile) {
            throw IllegalArgumentException("asar file missing")
        }
        val opened = RandomAccessFile(archiveFile, "r")
        val parsedDataOffset: Long
        try {
            val magic = readIntLE(opened)
            if (magic != 4) throw IllegalStateException("not asar file")
            val headerSize = readUInt32LE(opened)
            if (headerSize < 8L || headerSize > MAX_HEADER_BYTES || 8L + headerSize > opened.length()) {
                throw IOException("invalid asar header size: $headerSize")
            }
            parsedDataOffset = 8L + headerSize
            opened.skipBytes(4)
            val jsonLen = readUInt32LE(opened)
            if (jsonLen <= 0L || jsonLen > MAX_HEADER_BYTES || opened.filePointer + jsonLen > parsedDataOffset) {
                throw IOException("invalid asar json size: $jsonLen")
            }
            val jsonBytes = ByteArray(jsonLen.toInt())
            opened.readFully(jsonBytes)
            parseNode("", JSONObject(String(jsonBytes, StandardCharsets.UTF_8)))
            validateEntries(opened.length(), parsedDataOffset)
        } catch (error: Exception) {
            try { opened.close() } catch (_: Throwable) {
                // 流关闭失败可安全忽略（资源由 GC 最终回收）
            }
            throw error
        } catch (error: Error) {
            try { opened.close() } catch (_: Throwable) {
                // 流关闭失败可安全忽略（资源由 GC 最终回收）
            }
            throw error
        }
        raf = opened
        dataOffset = parsedDataOffset
        logInfo("asar loaded file=$archiveFile entries=${entries.size} dataOffset=$dataOffset")
    }

    fun has(path: String): Boolean {
        return entries.containsKey(normalize(path))
    }

    fun isDirectory(path: String): Boolean {
        val e = entries[normalize(path)]
        return e != null && e.directory
    }

    /**
     * 条目大小（字节）；非文件条目或不存在返回 null。
     *
     * 供调用方在**读取之前**做上限判断——`read()` 会把整个条目读进内存，
     * 先读再判上限等于上限失效（本类允许单条目至 [MAX_ENTRY_BYTES]）。
     */
    fun entrySize(path: String?): Long? {
        val e = entries[normalize(path)] ?: return null
        if (e.directory) return null
        return e.size
    }

    /**
     * 列出目录的直接子项（名字 + 是否目录）。供 v2 文件系统桥在 asar 会话里
     * 提供 readdir 语义——此前 asar 游戏的插件读不到任何目录内容。
     */
    fun children(path: String?): List<Pair<String, Boolean>> {
        val prefix = normalize(path).trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
        val out = LinkedHashMap<String, Boolean>()
        for ((key, entry) in entries) {
            if (key == prefix.trimEnd('/')) continue
            if (!key.startsWith(prefix)) continue
            val rest = key.substring(prefix.length)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            val name = if (slash >= 0) rest.substring(0, slash) else rest
            if (name.isEmpty()) continue
            val isDir = slash >= 0 || entry.directory
            // 同名既可能是文件也可能是目录前缀，目录优先
            out[name] = (out[name] ?: false) || isDir
        }
        return out.map { it.key to it.value }
    }

    /**
     * 打开条目的流式读取（PR review 意见：大体积媒体以完整 ByteArray 在内存流转，
     * 既无法 Range/seek 也有 OOM 风险）。返回 [输入流, 条目总大小]；每次调用独立
     * 打开 RandomAccessFile，调用方关闭输入流时同步关闭底层文件句柄。
     */
    fun openStream(path: String): Pair<java.io.InputStream, Long>? {
        return try {
            val e = entries[normalize(path)] ?: return null
            if (e.directory) return null
            val raf = RandomAccessFile(archiveFile, "r")
            try {
                raf.seek(dataOffset + e.offset)
            } catch (t: Throwable) {
                try { raf.close() } catch (_: Throwable) {}
                return null
            }
            Pair(EntryStream(raf, e.size), e.size)
        } catch (t: Throwable) {
            Log.w(TAG, "openStream failed path=$path", t)
            null
        }
    }

    private class EntryStream(private val raf: RandomAccessFile, private val remaining: Long) : java.io.InputStream() {
        private var left = remaining

        override fun read(): Int {
            if (left <= 0) return -1
            val b = raf.read()
            if (b < 0) { left = 0; return -1 }
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) { left = 0; return -1 }
            left -= n
            return n
        }

        override fun close() {
            try { raf.close() } catch (_: Throwable) {}
        }
    }

    fun read(path: String): ByteArray? {
        val key = normalize(path)
        val e = entries[key] ?: return null
        if (e.directory) return null
        // unpacked 条目：数据在 `<archive>.unpacked/<path>`，不在归档数据区。
        // 绝不能沿用 packed 路径（offset=0 会返回归档开头**别的文件**的字节，
        // 实测 readText 拿到的是前一个文件的内容）。
        if (e.unpacked) return readUnpacked(key, e)
        return try {
            val data = ByteArray(e.size.toInt())
            synchronized(raf) {
                raf.seek(dataOffset + e.offset)
                raf.readFully(data)
            }
            data
        } catch (t: Throwable) {
            Log.w(TAG, "read failed path=$path", t)
            null
        }
    }

    /**
     * 读取 unpacked 条目：数据位于 `<archive>.unpacked/` 下的同名相对路径。
     *
     * 文件不存在（未随包分发）时返回 null，由调用方按「资源缺失」处理 ——
     * 不再像旧实现那样返回归档里其它文件的内容。
     */
    private fun readUnpacked(key: String, entry: Entry): ByteArray? {
        val archive = archiveFile ?: return null
        val parent = archive.parentFile ?: return null
        val unpackedRoot = File(parent, archive.name + ".unpacked")
        val target = File(unpackedRoot, key)
        val insideRoot = try {
            val rootPath = unpackedRoot.canonicalFile.path
            val targetPath = target.canonicalFile.path
            targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        } catch (_: Throwable) {
            false
        }
        if (!insideRoot || !target.isFile) {
            Log.w(TAG, "unpacked entry missing on disk: $key")
            return null
        }
        return try {
            val data = target.readBytes()
            if (entry.size > 0L && data.size.toLong() != entry.size) {
                Log.w(TAG, "unpacked entry size mismatch: $key (declared ${entry.size}, actual ${data.size})")
            }
            data
        } catch (t: Throwable) {
            Log.w(TAG, "unpacked read failed: $key", t)
            null
        }
    }

    @Throws(IOException::class)
    override fun close() {
        synchronized(raf) {
            raf.close()
        }
    }

    fun getArchiveName(): String {
        return archiveFile?.name ?: ""
    }

    @Throws(IOException::class)
    private fun validateEntries(archiveLength: Long, parsedDataOffset: Long) {
        for ((key, entry) in entries) {
            if (entry.directory) continue
            // unpacked 条目的数据不在归档内，offset 无意义、大小也可能超限
            // （真实大文件），不参与 packed 范围校验。
            if (entry.unpacked) continue
            if (entry.size < 0L || entry.size > MAX_ENTRY_BYTES || entry.size > Int.MAX_VALUE) {
                throw IOException("invalid asar entry size: $key")
            }
            if (entry.offset < 0L || entry.offset > archiveLength - parsedDataOffset
                || entry.size > archiveLength - parsedDataOffset - entry.offset
            ) {
                throw IOException("invalid asar entry range: $key")
            }
        }
    }

    @Throws(Exception::class)
    private fun parseNode(prefix: String, node: JSONObject) {
        val files = node.optJSONObject("files")
        if (files == null) {
            if (prefix.isNotEmpty()) {
                // Electron 的 asar 会把大文件（视频等）标记为 unpacked：其数据**不在归档里**，
                // 而在 `<archive>.unpacked/<path>`。这类条目没有 `offset` 字段，
                // 旧实现用 optString 默认成 0 → read() 会返回归档数据区开头**别的文件**的
                // 字节（实测读到前一个文件内容），且 errorFor 又报 ENOENT，自相矛盾。
                val unpacked = node.optBoolean("unpacked", false)
                val size = parseLong(node.optString("size", "0"))
                val offset = if (unpacked) 0L else parseLong(node.optString("offset", "0"))
                entries[normalize(prefix)] = Entry(
                    directory = false,
                    size = size,
                    offset = offset,
                    unpacked = unpacked,
                )
            }
            return
        }
        if (prefix.isNotEmpty()) {
            entries[normalize(prefix)] = Entry(directory = true, size = 0, offset = 0)
        }
        val keys = files.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val child = files.optJSONObject(name) ?: continue
            val next = if (prefix.isEmpty()) name else "$prefix/$name"
            parseNode(next, child)
        }
    }

    private data class Entry(
        val directory: Boolean,
        /** Electron 的 unpacked 条目：数据不在归档里，而在 `<archive>.unpacked/` 下。 */
        val unpacked: Boolean = false,
        val size: Long,
        val offset: Long
    )

    companion object {
        private const val TAG = "YukiAsar"
        private const val MAX_HEADER_BYTES = 16L * 1024L * 1024L
        private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L

        private fun logInfo(message: String) {
            try { Log.i(TAG, message) } catch (_: RuntimeException) {
                // 日志失败可安全忽略（不影响加载流程）
            }
        }

        private fun normalize(path: String?): String {
            if (path == null) return ""
            var p = path.trim().replace('\\', '/')
            while (p.startsWith("/")) p = p.substring(1)
            return p
        }

        @Throws(Exception::class)
        private fun readIntLE(raf: RandomAccessFile): Int {
            return readUInt32LE(raf).toInt()
        }

        @Throws(Exception::class)
        private fun readUInt32LE(raf: RandomAccessFile): Long {
            val b1 = raf.read()
            val b2 = raf.read()
            val b3 = raf.read()
            val b4 = raf.read()
            if ((b1 or b2 or b3 or b4) < 0) throw EOFException()
            return (b4.toLong() shl 24) or (b3.toLong() shl 16) or (b2.toLong() shl 8) or b1.toLong()
        }

        private fun parseLong(value: String?): Long {
            return try {
                if (value == null || value.trim().isEmpty()) 0L else value.trim().toLong()
            } catch (_: Throwable) {
                // 数字字段解析失败回退 0L（asar 头字段非关键路径，§8 兜底）
                0L
            }
        }
    }
}
