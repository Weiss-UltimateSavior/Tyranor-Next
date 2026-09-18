package com.core.rpgmaker

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * RPG Maker MV/MZ v2 会话的文件系统桥：把注入兼容层里「静默空实现」的 fs/require
 * 换成真读写游戏目录，语义对齐 JoiPlay 的原生桥。
 *
 * 背景：v0/v1 的 NW.js 兼容层把 fs 桩成 `existsSync()=false` / `readFileSync()=""`，
 * 且**静默无日志**。插件最典型的写法 `if (fs.existsSync(p)) table = JSON.parse(fs.readFileSync(p))`
 * 在第一道门就返回 false，数据表没加载，后续查表得到 undefined 并被画进游戏文本
 * （实测现象：对话框名字渲染成 `名字[001undefined]`），而日志一片安静、无从定位。
 *
 * 边界：所有路径先按 [contentRoot] 解析相对路径，再 canonicalize 并要求落在
 * [gameRoot] 之内（与 RpgMakerStorage 同款 `insideRoot` 约束）；越界、控制字符、
 * 超限文件一律拒绝并记日志，不静默。
 */
internal class RpgMakerFsBridge(
    gameRoot: File,
    private val contentRoot: File,
) {
    private val root: File = gameRoot.canonicalFile

    /** __dirname 的取值：网页根（游戏 www/ 目录）。 */
    @JavascriptInterface
    fun baseDir(): String = contentRoot.absolutePath

    /** NW.js 的 App.dataPath 语义：游戏目录下的 AppData（可写，供插件存自己的配置）。 */
    @JavascriptInterface
    fun dataDir(): String = File(root, "AppData").absolutePath

    @JavascriptInterface
    fun exists(path: String?): Boolean = resolve(path) != null

    @JavascriptInterface
    fun isFile(path: String?): Boolean = resolve(path)?.isFile == true

    @JavascriptInterface
    fun isDir(path: String?): Boolean = resolve(path)?.isDirectory == true

    /** 读文本；不存在/不可读返回 null（JS 侧映射为 null，与 Node 的抛错由调用方兜底区分）。 */
    @JavascriptInterface
    fun readText(path: String?): String? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        if (file.length() > MAX_READ_BYTES) {
            Log.w(TAG, "fs read rejected (too large ${file.length()}): ${file.path}")
            return null
        }
        return try {
            String(file.readBytes(), StandardCharsets.UTF_8)
        } catch (error: Throwable) {
            Log.w(TAG, "fs read failed: ${file.path}", error)
            null
        }
    }

    /** 读二进制（base64）；Buffer 语义用，避免桥只能传字符串的限制。 */
    @JavascriptInterface
    fun readBase64(path: String?): String? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        if (file.length() > MAX_READ_BYTES) {
            Log.w(TAG, "fs read(Buffer) rejected (too large ${file.length()}): ${file.path}")
            return null
        }
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (error: Throwable) {
            Log.w(TAG, "fs read(Buffer) failed: ${file.path}", error)
            null
        }
    }

    /** 目录项名列表（JSON 数组字符串）；非目录返回空数组。 */
    @JavascriptInterface
    fun readdir(path: String?): String {
        val dir = resolve(path) ?: return "[]"
        if (!dir.isDirectory) return "[]"
        val names = dir.list() ?: return "[]"
        val array = JSONArray()
        names.forEach { array.put(it) }
        return array.toString()
    }

    /** stat/lstat：JSON `{file,dir,size,mtime}`；不存在返回空串。 */
    @JavascriptInterface
    fun stat(path: String?): String {
        val file = resolve(path) ?: return ""
        return try {
            JSONObject()
                .put("file", file.isFile)
                .put("dir", file.isDirectory)
                .put("size", file.length())
                .put("mtime", file.lastModified())
                .toString()
        } catch (error: Throwable) {
            Log.w(TAG, "fs stat failed: ${file.path}", error)
            ""
        }
    }

    @JavascriptInterface
    fun writeText(path: String?, data: String?): Boolean =
        write(path) { it.write(data.orEmpty().toByteArray(StandardCharsets.UTF_8)) }

    @JavascriptInterface
    fun writeBase64(path: String?, data: String?): Boolean {
        val bytes = try {
            Base64.decode(data.orEmpty(), Base64.DEFAULT)
        } catch (error: Throwable) {
            Log.w(TAG, "fs write rejected (bad base64): $path", error)
            return false
        }
        if (bytes.size > MAX_READ_BYTES) {
            Log.w(TAG, "fs write rejected (too large ${bytes.size}): $path")
            return false
        }
        return write(path) { it.write(bytes) }
    }

    @JavascriptInterface
    fun makeDirs(path: String?): Boolean {
        val dir = resolve(path) ?: return false
        return try {
            dir.isDirectory || dir.mkdirs() || dir.isDirectory
        } catch (error: Throwable) {
            Log.w(TAG, "fs mkdir failed: ${dir.path}", error)
            false
        }
    }

    @JavascriptInterface
    fun remove(path: String?): Boolean {
        val file = resolve(path) ?: return false
        return try {
            !file.exists() || file.delete()
        } catch (error: Throwable) {
            Log.w(TAG, "fs remove failed: ${file.path}", error)
            false
        }
    }

    private inline fun write(path: String?, block: (java.io.FileOutputStream) -> Unit): Boolean {
        val file = resolve(path) ?: return false
        val parent = file.parentFile ?: return false
        return try {
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
            java.io.FileOutputStream(file).use { out ->
                block(out)
                out.fd.sync()
            }
            true
        } catch (error: Throwable) {
            Log.w(TAG, "fs write failed: ${file.path}", error)
            false
        }
    }

    /** 相对路径按网页根解析；越界（不在游戏目录内）返回 null。 */
    private fun resolve(path: String?): File? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.any { it == '\u0000' || it.isISOControl() }) return null
        val normalized = raw.removePrefix("file://").replace('\\', '/')
        val candidate = if (normalized.startsWith("/")) File(normalized) else File(contentRoot, normalized)
        val canonical = try {
            candidate.canonicalFile
        } catch (error: Throwable) {
            Log.w(TAG, "fs path canonicalize failed: $path", error)
            return null
        }
        val rootPath = root.path
        val inside = canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)
        if (!inside) {
            Log.w(TAG, "fs path rejected (outside game dir): $path")
            return null
        }
        return canonical
    }

    companion object {
        private const val TAG = "YukiRpgMaker"
        private const val MAX_READ_BYTES = 16L * 1024L * 1024L
    }
}
