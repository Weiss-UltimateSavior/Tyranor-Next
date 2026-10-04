package com.core.rpgmaker

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 存档键 → 落盘文件名的映射规则，**引擎写入与应用侧转化共用的唯一实现**。
 *
 * 规则（与历史 Tyranor/Rinne 落盘名保持兼容）：
 * - 键为普通文件名（匹配 [DIRECT_FILE_KEY]）⇒ 直接用 `键 + 扩展名`。这类键是 MZ 的
 *   `fileN`/`global`/`config` 等纯 ASCII 形态，改名会让引擎永远读不到既有存档；
 * - 键含空格/Unicode 等特殊字符 ⇒ 映射为确定性哈希名 `key_<sha256(键)>.bin`。
 *   MV 的键形如 `RPG File3`（必含空格）固定走这条路径；键本身是数据、绝不进入文件路径。
 *
 * 为什么必须共用：应用侧转化按该规则写入文件，引擎按同一规则读取。两处各写一份实现时
 * 一旦漂移（哪怕只差一处判断），转化出的存档就会「写进去、读不到」，且没有任何报错。
 * 此前 app 的 `RpgSaveFormat.tyranorAppliedName` 与 engine 的 `RpgMakerStorage.resolveFile`
 * 正是两份独立实现，仅靠「MV 键必含空格」这一巧合保持等价。
 */
object RpgSaveKeyMapping {

    /** 直接用作文件名的键形态（MZ 的纯 ASCII 键）；不匹配者走哈希映射。 */
    val DIRECT_FILE_KEY = Regex("[A-Za-z0-9._-]{1,128}")

    /** 键长度上限（防御异常键撑爆文件名）。 */
    const val MAX_KEY_CHARS: Int = 512

    /** 哈希名前缀。 */
    const val HASH_PREFIX: String = "key_"

    /**
     * 校验并归一化键；不合法返回 null。
     *
     * 键是数据不是路径：拒绝目录分隔符与 `..`，避免越界；控制字符与超长键一并拒绝。
     */
    fun sanitizeKey(key: String?): String? {
        val clean = key?.trim() ?: return null
        if (clean.isEmpty() || clean.length > MAX_KEY_CHARS) return null
        if (clean.any { it == '\u0000' || it.isISOControl() }) return null
        if (clean.contains('/') || clean.contains('\\') || clean.contains("..")) return null
        return clean
    }

    /**
     * 归一化键 → 规范落盘文件名（不含目录）。
     *
     * 注意这只是**写入目标名**；读取时引擎还会先探测同名 legacy 文件（见 [RpgMakerStorage.resolveFile]），
     * 以兼容历史上按原始键落盘的存档。
     */
    fun canonicalFileName(cleanKey: String, extension: String): String =
        if (DIRECT_FILE_KEY.matches(cleanKey)) "$cleanKey$extension"
        else "$HASH_PREFIX${sha256Hex(cleanKey)}$extension"

    /**
     * 从哈希名反解其中的 sha256 摘要（小写）；非哈希名返回 null。
     *
     * 这是「识别 + 反解」的**唯一原语**：应用侧的哈希名判定与「摘要 → 标准名」反解索引
     * 都基于它，避免两处各自维护正则（此前 app 侧另有一份 `HASHED_KEY_NAME`）。
     * 大小写不敏感（历史文件名可能来自大小写混用的写入方），返回前统一转小写。
     */
    fun hashFromFileName(fileName: String?, extension: String = ".bin"): String? {
        val name = fileName ?: return null
        if (!name.startsWith(HASH_PREFIX) || !name.endsWith(extension, ignoreCase = true)) return null
        val stem = name.substring(HASH_PREFIX.length, name.length - extension.length)
        if (stem.length != 64) return null
        if (!stem.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return stem.lowercase()
    }

    /** 判断文件名是否为本规则产出的哈希名。 */
    fun isHashedFileName(fileName: String?): Boolean = hashFromFileName(fileName) != null

    /** SHA-256 十六进制小写。 */
    fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
