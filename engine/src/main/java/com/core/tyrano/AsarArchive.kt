package com.core.tyrano

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
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

    fun read(path: String): ByteArray? {
        return try {
            val e = entries[normalize(path)] ?: return null
            if (e.directory) return null
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

    /** 条目字节大小；目录或不存在返回 null。用于流式响应前判定与 Range 计算，避免整体读入内存。 */
    fun fileSize(path: String): Long? {
        val e = entries[normalize(path)] ?: return null
        return if (e.directory) null else e.size
    }

    /**
     * 将条目 [path] 的 `[start, start+length)` 区间流式写入 [out]（供视频等大文件的 Range 响应使用，
     * 不整体读入内存）。返回是否成功；越界或读取失败返回 false。
     */
    fun writeRange(path: String, start: Long, length: Long, out: OutputStream): Boolean {
        val e = entries[normalize(path)] ?: return false
        if (e.directory || start < 0L || length < 0L || start + length > e.size) return false
        if (length == 0L) return true
        return try {
            val buffer = ByteArray(64 * 1024)
            var position = start
            var left = length
            while (left > 0L) {
                val chunk = Math.min(buffer.size.toLong(), left).toInt()
                // 锁内只做 seek+读块，写出放在锁外，避免慢客户端长时间占住归档文件指针
                val read = synchronized(raf) {
                    raf.seek(dataOffset + e.offset + position)
                    raf.read(buffer, 0, chunk)
                }
                if (read <= 0) return false
                out.write(buffer, 0, read)
                position += read
                left -= read
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "writeRange failed path=$path start=$start length=$length", t)
            false
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
                val size = parseLong(node.optString("size", "0"))
                val offset = parseLong(node.optString("offset", "0"))
                entries[normalize(prefix)] = Entry(directory = false, size = size, offset = offset)
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
