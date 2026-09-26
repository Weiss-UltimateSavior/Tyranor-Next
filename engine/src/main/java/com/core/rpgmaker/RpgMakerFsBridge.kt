package com.core.rpgmaker

import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File
import java.util.Base64
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
    private val asar: AsarArchive? = null,
) {
    private val root: File = gameRoot.canonicalFile

    /**
     * asar 会话里网页根相对压缩包的路径前缀（`www/` 等）；无 asar 时为空。
     * 游戏资源在压缩包内、磁盘上不存在，因此读路径采用「asar 优先、磁盘兜底」的
     * 覆盖层语义：先前 asar 游戏的插件读数据表/require 自己模块会全部失败。
     * 写入始终落磁盘（压缩包不可写），与 NW.js 下写 save/ 目录的行为一致。
     */
    private val asarPrefix: String = when {
        asar == null -> ""
        asar.has("index.html") -> ""
        asar.has("www/index.html") -> "www/"
        else -> ""
    }

    /**
     * 请求路径 → 相对网页根的路径。绝对路径只有在位于 contentRoot 之下时才可映射到
     * asar 条目；范围外返回 null（避免把 /sdcard/... 之类当成包内路径）。
     */
    private fun relativeFor(path: String): String? {
        val raw = path.takeIf { it.isNotEmpty() } ?: return null
        val normalized = raw.removePrefix("file://").replace('\\', '/')
        if (!normalized.startsWith("/")) return normalized
        val base = contentRoot.absolutePath.replace('\\', '/').trimEnd('/')
        return when {
            normalized == base -> ""
            normalized.startsWith("$base/") -> normalized.substring(base.length + 1)
            else -> null
        }
    }

    /** 请求路径 → asar 内条目路径（无 asar 或越界时返回 null）。 */
    private fun asarKey(relative: String?): String? {
        if (asar == null || relative.isNullOrEmpty()) return null
        val path = relative.replace('\\', '/').trimStart('/')
        if (path.contains("..")) return null
        return asarPrefix + path
    }

    /**
     * 覆盖层优先级必须是**磁盘优先**，理由有二：
     *  1. 与本地 HTTP 服务器一致——服务器承载引擎全部资源加载（XHR 取 data/、图片、音频），
     *     其 `canonicalIfValid` 要求磁盘命中且是**文件**（`isFile`）才返回，asar 仅兜底。
     *     若桥反过来，同一个路径经 XHR 与经 fs 会解析出不同内容。
     *  2. 写入必须可读回——插件写出的文件若被同名 asar 条目遮蔽，读回的是包内旧值，
     *     表现为「配置/数据表改了却不生效」，且写入本身没有报错。
     */

    /** 磁盘上的同名**文件**（与服务器的命中条件一致）；目录不算命中，会继续检查 asar。 */
    private fun diskFile(path: String?): File? = resolve(path)?.takeIf { it.isFile }

    /** 磁盘上的同名条目（文件或目录），用于存在性/类型/stat 判定。 */
    private fun diskEntry(path: String?): File? = resolve(path)?.takeIf { it.exists() }

    /** __dirname 的取值：网页根（游戏 www/ 目录）。 */
    @JavascriptInterface
    fun baseDir(): String = contentRoot.absolutePath

    /** NW.js 的 App.dataPath 语义：游戏目录下的 AppData（可写，供插件存自己的配置）。 */
    @JavascriptInterface
    fun dataDir(): String = File(root, "AppData").absolutePath

    @JavascriptInterface
    fun exists(path: String?): Boolean {
        if (diskEntry(path) != null) return true
        asarKey(path?.let { relativeFor(it) })?.let { if (asar?.has(it) == true) return true }
        return false
    }

    @JavascriptInterface
    fun isFile(path: String?): Boolean {
        diskEntry(path)?.let { return it.isFile }
        asarKey(path?.let { relativeFor(it) })?.let { key ->
            if (asar?.has(key) == true) return asar.isDirectory(key).not()
        }
        return false
    }

    @JavascriptInterface
    fun isDir(path: String?): Boolean {
        diskEntry(path)?.let { return it.isDirectory }
        asarKey(path?.let { relativeFor(it) })?.let { key ->
            if (asar?.has(key) == true) return asar.isDirectory(key)
        }
        return false
    }

    /**
     * 读失败的原因码（stateless、可重复调用）：`ENOENT` / `EISDIR` / `E2BIG` / `EPERM`。
     *
     * 读方法返回 null 时 JS 侧需要知道「是不存在还是被拒绝」——把超限或越界也报成
     * ENOENT 会让插件按「文件不存在」处理，这正是要避免的静默错判。
     * 本方法只依赖路径本身（不依赖上一次调用），故与读方法之间无竞态。
     */
    @JavascriptInterface
    fun errorFor(path: String?): String {
        // 磁盘优先：磁盘有同名条目时按磁盘判定（与读路径同一顺序）
        diskEntry(path)?.let { entry ->
            if (entry.isDirectory) return "EISDIR"
            if (entry.length() > MAX_READ_BYTES) return "E2BIG"
            return "ENOENT"
        }
        asarKey(path?.let { relativeFor(it) })?.let { key ->
            if (asar?.has(key) == true) {
                if (asar.isDirectory(key)) return "EISDIR"
                if ((asar.entrySize(key) ?: 0L) > MAX_READ_BYTES) return "E2BIG"
                return "ENOENT"
            }
        }
        // resolve 对「合法但不存在」的路径同样返回 File（它不查磁盘），
        // 因此返回 null 只可能是非法字符或越界 → EPERM。
        return if (resolve(path) == null) "EPERM" else "ENOENT"
    }

    /** 读文本；不存在/不可读返回 null（JS 侧映射为 null，与 Node 的抛错由调用方兜底区分）。 */
    @JavascriptInterface
    fun readText(path: String?): String? {
        // 磁盘优先（与 HTTP 服务器及写入语义一致）：磁盘无同名文件时才回退 asar。
        // 顺序反了会让「插件写入的文件」被 asar 遮蔽，读回包内旧值。
        if (diskFile(path) != null) return readDiskText(path)
        return readAsarBytes(path)?.let { String(it, StandardCharsets.UTF_8) }
    }

    private fun readDiskText(path: String?): String? {
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

    /** asar 会话的条目读取；无命中返回 null（由调用方回落到磁盘或报错）。 */
    private fun readAsarBytes(path: String?): ByteArray? {
        val archive = asar ?: return null
        val key = asarKey(path?.let { relativeFor(it) }) ?: return null
        if (!archive.has(key) || archive.isDirectory(key)) return null
        // 先按条目声明大小判断，避免「读进内存后再拒绝」——AsarArchive 允许单条目
        // 至 256MiB，而这里的读上限是 16MiB，先读会白占最多 256MiB。
        archive.entrySize(key)?.let { size ->
            if (size > MAX_READ_BYTES) {
                Log.w(TAG, "asar read rejected (too large $size): $key")
                return null
            }
        }
        return try {
            val data = archive.read(key) ?: return null
            if (data.size > MAX_READ_BYTES) {
                Log.w(TAG, "asar read rejected (too large ${data.size}): $key")
                return null
            }
            data
        } catch (error: Throwable) {
            Log.w(TAG, "asar read failed: $key", error)
            null
        }
    }

    /** 读二进制（base64）；Buffer 语义用，避免桥只能传字符串的限制。 */
    @JavascriptInterface
    fun readBase64(path: String?): String? {
        // 磁盘优先（见 readText 的说明）：磁盘无同名文件时才回退 asar
        if (diskFile(path) != null) return readDiskBase64(path)
        readAsarBytes(path)?.let { return Base64.getEncoder().encodeToString(it) }
        return readDiskBase64(path)
    }

    private fun readDiskBase64(path: String?): String? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        if (file.length() > MAX_READ_BYTES) {
            Log.w(TAG, "fs read(Buffer) rejected (too large ${file.length()}): ${file.path}")
            return null
        }
        return try {
            Base64.getEncoder().encodeToString(file.readBytes())
        } catch (error: Throwable) {
            Log.w(TAG, "fs read(Buffer) failed: ${file.path}", error)
            null
        }
    }

    /** 目录项名列表（JSON 数组字符串）；非目录返回空数组。 */
    @JavascriptInterface
    fun readdir(path: String?): String {
        // 磁盘上有同名条目即不再回退 asar——覆盖层规则必须与 exists/isFile/isDir/stat
        // 完全一致，否则同一路径会「既是文件又是目录」：例如磁盘有文件 `data`、
        // asar 内有目录 `data/`，若这里回退 asar 就会列出子项，而 isDir 返回 false。
        diskEntry(path)?.let { entry ->
            if (!entry.isDirectory) return "[]"   // 磁盘是文件（或其它非目录）→ 不是目录
            val names = entry.list() ?: return "[]"
            val array = JSONArray()
            names.forEach { array.put(it) }
            return array.toString()
        }
        // 磁盘无同名条目时才查压缩包索引（asar 内的目录在磁盘上通常不存在）
        asarKey(path?.let { relativeFor(it) })?.let { key ->
            val archive = asar
            if (archive != null && archive.has(key) && archive.isDirectory(key)) {
                val array = JSONArray()
                archive.children(key).forEach { array.put(it.first) }
                return array.toString()
            }
        }
        return "[]"
    }

    /** stat/lstat：JSON `{file,dir,size,mtime}`；不存在返回空串。 */
    @JavascriptInterface
    fun stat(path: String?): String {
        // 磁盘优先：写入后 size/mtime 必须来自磁盘，否则插件按 asar 的旧 size 判断会误判
        diskEntry(path)?.let { file ->
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
        readAsarBytes(path)?.let { data ->
            return JSONObject()
                .put("file", true).put("dir", false)
                .put("size", data.size).put("mtime", 0)
                .toString()
        }
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
    fun writeText(path: String?, data: String?): Boolean {
        // 与 writeBase64 / 读路径共用同一上限：此前文本写入无任何校验，
        // 插件传入任意长字符串会直接落盘（16MiB 的读上限也就形同虚设）。
        val text = data.orEmpty()
        // 预检（无分配）：UTF-8 字节数恒 ≥ UTF-16 字符数，字符数已超限即可直接拒绝，
        // 避免为必然被拒的载荷先分配一份全量字节数组。
        if (text.length > MAX_WRITE_BYTES) {
            Log.w(TAG, "fs write rejected pre-encode (chars=${text.length}): $path")
            return false
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            Log.w(TAG, "fs write rejected (too large ${bytes.size}): $path")
            return false
        }
        return write(path) { it.write(bytes) }
    }

    @JavascriptInterface
    fun writeBase64(path: String?, data: String?): Boolean {
        val encoded = data.orEmpty()
        // 预检（无分配）：按有效字符数算出解码长度的**下界**（每 4 个有效字符 ⇒ 3 字节），
        // 下界已超限即可拒绝，避免先 MIME 解码出全量字节再判。
        if (decodedBase64LowerBound(encoded) > MAX_WRITE_BYTES) {
            Log.w(TAG, "fs write rejected pre-decode (chars=${encoded.length}): $path")
            return false
        }
        val bytes = try {
            decodeBase64Lenient(encoded)
        } catch (error: Throwable) {
            Log.w(TAG, "fs write rejected (bad base64): $path", error)
            return false
        }
        if (bytes.size > MAX_WRITE_BYTES) {
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

    /** utimesSync：Android 只能设 mtime（无 atime），够插件做「最近修改」判断。 */
    @JavascriptInterface
    fun setTimes(path: String?, mtimeMillis: Long): Boolean {
        val file = resolve(path) ?: return false
        return try {
            file.setLastModified(mtimeMillis)
        } catch (error: Throwable) {
            Log.w(TAG, "fs utimes failed: ${file.path}", error)
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
    /**
     * base64 解码长度下界（不做任何分配）。
     *
     * 仅统计 base64 字母表内字符（MIME 解码器会跳过空白与非法字符），
     * 按「每 4 个有效字符编码 3 字节」并扣除 padding 得到下界——
     * 用于在解码前拒绝必然超限的载荷，且不会误拒合法载荷（下界 ≤ 实际）。
     */
    private fun decodedBase64LowerBound(value: String): Long {
        var valid = 0L
        var padding = 0L
        for (ch in value) {
            when {
                ch == '=' -> padding++
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '+' || ch == '/' || ch == '-' || ch == '_' -> valid++
                // 其余字符（空白/换行等）会被 MIME 解码器忽略，不计入
            }
        }
        val groups = valid / 4
        val decoded = groups * 3 - padding
        return if (decoded < 0) 0 else decoded
    }

    private fun resolve(path: String?): File? {
        // 注意不要 trim()：文件名里的尾随空格是真实存在的（部分素材名带尾随空格），
        // trim 会让这类文件永远无法访问（Node 的 fs 同样不做 trim）。
        val raw = path?.takeIf { it.isNotEmpty() && it.any { c -> !c.isWhitespace() } } ?: return null
        if (raw.any { it == '\u0000' || it.isISOControl() }) return null
        val normalized = raw.removePrefix("file://").replace('\\', '/')
        val candidate = if (normalized.startsWith("/")) File(normalized) else File(contentRoot, normalized)
        val canonical = try {
            candidate.canonicalFile
        } catch (error: Throwable) {
            Log.w(TAG, "fs path canonicalize failed: $path", error)
            return null
        }
        if (!isInsideRoot(canonical)) {
            Log.w(TAG, "fs path rejected (outside game dir): $path")
            return null
        }
        return canonical
    }

    private fun isInsideRoot(target: File): Boolean {
        val rootPath = root.path
        return target.path == rootPath || target.path.startsWith(rootPath + File.separator)
    }

    /** 宽松 base64 解码：容忍换行/空白（与原先 android.util.Base64.DEFAULT 一致）。 */
    private fun decodeBase64Lenient(value: String): ByteArray =
        Base64.getMimeDecoder().decode(value)

    companion object {
        private const val TAG = "YukiRpgMaker"
        private const val MAX_READ_BYTES = 16L * 1024L * 1024L

        /** 单次写入上限；与读取上限分开命名以便独立调整（当前同值）。 */
        private const val MAX_WRITE_BYTES = 16L * 1024L * 1024L
    }
}
