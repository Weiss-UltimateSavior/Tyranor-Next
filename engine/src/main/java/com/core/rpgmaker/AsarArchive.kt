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
