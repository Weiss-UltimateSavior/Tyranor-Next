package com.tyranor.next.core.game.save

import com.tyranor.next.core.engine.EngineType
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * RPG Maker MV/MZ 存档文件名在「Tyranor 格式」与「JoiPlay/PC 标准格式」之间的映射、检测与路径解析。
 *
 * 两种格式内容字节级一致，仅文件名不同；转化是纯改名（不重编码）。
 *
 * 存档目录统一为 `<游戏根>/savedata`（与 Tyrano 相同，engine 宿主读写处），检测/转化/导出/列表
 * 均以 [saveDirectory] 为唯一来源。
 *
 * MV（引擎经 webStorageKey 派生 `RPG ...` 键，含空格 → 桥按 legacy 文件名落盘或哈希）：
 * | Tyranor 格式        | 标准格式（JoiPlay/PC） |
 * | ------------------ | --------------------- |
 * | RPG Global.bin     | global.rpgsave        |
 * | RPG Config.bin     | config.rpgsave        |
 * | RPG FileN.bin      | fileN.rpgsave         |
 * | RPG Globalbak.bin  | global.rpgsave.bak    |
 * | RPG Configbak.bin  | config.rpgsave.bak    |
 * | RPG FileNbak.bin   | fileN.rpgsave.bak     |
 *
 * MZ（键为 `global`/`config`/`fileN`，核心不带备份）：
 * | global.bin / config.bin / fileN.bin | global.rmmzsave / config.rmmzsave / fileN.rmmzsave |
 *
 * 含空格的 MV 键经 RpgMakerStorage 的确定性哈希映射落成 `key_<sha256(key)>.bin`。该形态**不是**
 * 可读的 Tyranor 名字（引擎读档时按 legacy 文件名找，哈希名只是写入落点），因此导出为「标准模式」
 * 时需要把哈希名反解回标准名——键空间有限（见 [hashedToStandardName]），可枚举还原。
 */
object RpgSaveFormat {

    /** 标准存档扫描结果。 */
    data class Detection(
        /** 可转化的标准存档文件（含 `.bak`），绝对路径。 */
        val standardFiles: List<File>,
        /** 检测到但不可转化的哈希存档数量（key_<sha256>.bin）。 */
        val hashedCount: Int,
    ) {
        val convertibleCount: Int get() = standardFiles.size
    }

    /** 转化结果计数。 */
    data class ConvertResult(
        /** 成功改名为 Tyranor 格式的文件数。 */
        val converted: Int,
        /** 目标已存在而跳过（不覆盖）的文件数。 */
        val skipped: Int,
        /** 处理失败的文件数（源文件原样保留）。 */
        val failed: Int,
        /** 检测到但未转化的哈希存档数量（仅报告）。 */
        val hashedCount: Int,
    )

    /** 转化时源文件留底的子目录名（位于源存档目录内，不参与列表/导出/删除）。 */
    const val ORIGINAL_DIR = "original"

    /** MV 的 Tyranor 文件名：`RPG Global.bin` / `RPG File1bak.bin` 等。 */
    private val MV_TYRANOR_NAME = Regex("^rpg (global|config|file(\\d+))(bak)?\\.bin$", RegexOption.IGNORE_CASE)

    /** MZ 的 Tyranor 文件名：`global.bin` / `file1.bin` 等。 */
    private val MZ_TYRANOR_NAME = Regex("^(global|config|file(\\d+))(bak)?\\.bin$", RegexOption.IGNORE_CASE)

    private val HASHED_KEY_NAME = Regex("^key_([0-9a-f]{64})\\.bin$", RegexOption.IGNORE_CASE)

    private const val MV_EXT = ".rpgsave"
    private const val MZ_EXT = ".rmmzsave"

    /** 枚举哈希反解时的最大存档位（覆盖常见 MV/MZ 存档位，超出则保留原名）。 */
    private const val MAX_SAVE_SLOT = 999

    fun isRpgWebEngine(engine: EngineType): Boolean =
        engine == EngineType.RPG_MV || engine == EngineType.RPG_MZ

    private fun standardExtension(engine: EngineType): String? = when (engine) {
        EngineType.RPG_MV -> MV_EXT
        EngineType.RPG_MZ -> MZ_EXT
        else -> null
    }

    private fun tyranorPattern(engine: EngineType): Regex? = when (engine) {
        EngineType.RPG_MV -> MV_TYRANOR_NAME
        EngineType.RPG_MZ -> MZ_TYRANOR_NAME
        else -> null
    }

    /** MV 才有存档备份（MZ 核心不含 backup）。 */
    private fun supportsBackup(engine: EngineType): Boolean = engine == EngineType.RPG_MV

    // ===== 路径解析 =====

    /**
     * Tyranor 侧存档目录（engine 宿主 resolveSaveDirectory 读写处）：`<游戏根>/savedata`。
     * 检测、转化、导出、列表以本函数为唯一来源，避免多套路径漂移。
     */
    fun saveDirectory(gameRoot: File): File = File(gameRoot, "savedata")

    /**
     * 标准侧（JoiPlay/PC）存档目录：`<内容根>/save`。内容根 = 含 `index.html` / `app.asar`
     * 的目录，与 engine 入口探测同序——MV 常见 `.../Game/www` ⇒ `.../Game/www/save`；
     * 根目录布局（含 MZ 无 www）⇒ `<游戏根>/save`。定位失败回退 `<游戏根>/save`。
     */
    fun standardSaveDirectory(gameRoot: File): File {
        val contentRoot = locateContentRoot(gameRoot) ?: return File(gameRoot, "save")
        return File(contentRoot, "save")
    }

    /** 递归定位游戏内容根（含 index.html / app.asar 的目录），与 engine 入口探测同序。 */
    private fun locateContentRoot(dir: File, depth: Int = 0): File? {
        if (!dir.isDirectory) return null
        dir.resolve("app.asar").takeIf { it.isFile }?.let { return dir }
        dir.resolve("resources/app.asar").takeIf { it.isFile }?.let { return dir }
        dir.resolve("app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let { return it }
        dir.resolve("resources/app.asar").takeIf { it.isDirectory && it.resolve("index.html").isFile }?.let { return it }
        dir.resolve("index.html").takeIf { it.isFile }?.let { return dir }
        if (depth >= MAX_ENTRY_SEARCH_DEPTH) return null
        for (name in WEB_ENTRY_SUBDIRS) {
            val sub = dir.resolve(name)
            if (!sub.isDirectory) continue
            locateContentRoot(sub, depth + 1)?.let { return it }
        }
        return null
    }

    // ===== 检测 =====

    /**
     * 探测存档目录下的标准格式存档与哈希存档（容忍历史大小写 `Savedata`）。
     * 仅扫各目录直接子文件（不递归），因此 `original/` 留底不会重复触发；按规范化路径去重。
     */
    fun detect(gameRoot: File, engine: EngineType): Detection {
        if (standardExtension(engine) == null) return Detection(emptyList(), 0)
        val seen = mutableSetOf<String>()
        val files = mutableListOf<File>()
        var hashed = 0
        listOf(saveDirectory(gameRoot), File(gameRoot, "Savedata")).forEach { root ->
            if (!seen.add(pathKey(root))) return@forEach
            val detection = detectIn(root, engine)
            files += detection.standardFiles
            hashed += detection.hashedCount
        }
        files.sortBy { it.absolutePath.lowercase(Locale.ROOT) }
        return Detection(files, hashed)
    }

    /** 在单个目录直接子文件中检测标准格式存档与哈希存档。 */
    fun detectIn(saveDir: File, engine: EngineType): Detection {
        if (standardExtension(engine) == null || !saveDir.isDirectory) return Detection(emptyList(), 0)
        val standard = mutableListOf<File>()
        var hashed = 0
        saveDir.listFiles().orEmpty().forEach { child ->
            if (!child.isFile) return@forEach
            when {
                isHashedTyranorName(child.name) -> hashed++
                isStandardName(child.name, engine) -> standard += child
            }
        }
        standard.sortBy { it.name.lowercase(Locale.ROOT) }
        return Detection(standard, hashed)
    }

    private fun pathKey(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).lowercase(Locale.ROOT)

    // ===== 名称映射 =====

    /** 标准文件名判定（`global|config|fileN` + 引擎扩展名 + MV 可带 `.bak`）。 */
    fun isStandardName(name: String, engine: EngineType): Boolean {
        val ext = standardExtension(engine) ?: return false
        val lower = name.lowercase(Locale.ROOT)
        val backupSuffix = "$ext.bak"
        if (lower.endsWith(backupSuffix)) {
            if (!supportsBackup(engine)) return false
            return isStandardStem(lower.removeSuffix(backupSuffix))
        }
        if (!lower.endsWith(ext)) return false
        return isStandardStem(lower.removeSuffix(ext))
    }

    private fun isStandardStem(stem: String): Boolean =
        stem == "global" || stem == "config" || Regex("^file\\d+$").matches(stem)

    /** 标准文件名 → Tyranor 文件名；不匹配返回 null。 */
    fun standardToTyranor(name: String, engine: EngineType): String? {
        val ext = standardExtension(engine) ?: return null
        val lower = name.lowercase(Locale.ROOT)
        val backupSuffix = "$ext.bak"
        val isBackup = lower.endsWith(backupSuffix)
        if (isBackup && !supportsBackup(engine)) return null
        val stem = when {
            isBackup -> lower.removeSuffix(backupSuffix)
            lower.endsWith(ext) -> lower.removeSuffix(ext)
            else -> return null
        }
        if (!isStandardStem(stem)) return null
        val base = tyranorBase(stem, engine) ?: return null
        return base + (if (isBackup) "bak" else "") + ".bin"
    }

    /** Tyranor 文件名 → 标准文件名；不匹配返回 null。 */
    fun tyranorToStandard(name: String, engine: EngineType): String? {
        val ext = standardExtension(engine) ?: return null
        val match = tyranorPattern(engine)?.matchEntire(name) ?: return null
        val key = match.groupValues[1].lowercase(Locale.ROOT)
        val id = match.groupValues.getOrNull(2).orEmpty()
        val isBackup = match.groupValues.getOrNull(3).orEmpty().isNotEmpty()
        if (isBackup && !supportsBackup(engine)) return null
        val stem = when {
            key == "global" -> "global"
            key == "config" -> "config"
            else -> "file$id"
        }
        return stem + ext + (if (isBackup) ".bak" else "")
    }

    private fun tyranorBase(standardStem: String, engine: EngineType): String? {
        val mv = engine == EngineType.RPG_MV
        return when {
            standardStem == "global" -> if (mv) "RPG Global" else "global"
            standardStem == "config" -> if (mv) "RPG Config" else "config"
            standardStem.startsWith("file") -> {
                val id = standardStem.removePrefix("file")
                if (mv) "RPG File$id" else "file$id"
            }
            else -> null
        }
    }

    // ===== 槽位键（同步用）=====
    // 槽位键 = 归一化存档位标识：`global` / `config` / `fileN`，MV 备份追加 `.bak`。
    // 两侧文件名都可映射到同一槽位键，便于判断「同一个存档位」是否两边都存在。

    private fun normalizeSlotStem(stem: String): String? =
        if (stem == "global" || stem == "config" || Regex("^file\\d+$").matches(stem)) stem else null

    /** 标准文件名 → 槽位键；不匹配返回 null。 */
    fun standardSlot(name: String, engine: EngineType): String? {
        val ext = standardExtension(engine) ?: return null
        val lower = name.lowercase(Locale.ROOT)
        val backupSuffix = "$ext.bak"
        val isBackup = lower.endsWith(backupSuffix)
        if (isBackup && !supportsBackup(engine)) return null
        val stem = when {
            isBackup -> lower.removeSuffix(backupSuffix)
            lower.endsWith(ext) -> lower.removeSuffix(ext)
            else -> return null
        }
        if (normalizeSlotStem(stem) == null) return null
        return stem + (if (isBackup) ".bak" else "")
    }

    /** Tyranor 文件名（含哈希名反解）→ 槽位键；无法还原返回 null。 */
    fun tyranorSlot(name: String, engine: EngineType): String? {
        val standard = tyranorToStandard(name, engine) ?: hashedToStandardName(name, engine) ?: return null
        return standardSlot(standard, engine)
    }

    /** 槽位键 → 标准文件名。 */
    fun standardNameForSlot(slot: String, engine: EngineType): String? {
        val ext = standardExtension(engine) ?: return null
        val isBackup = slot.endsWith(".bak")
        if (isBackup && !supportsBackup(engine)) return null
        val stem = if (isBackup) slot.removeSuffix(".bak") else slot
        if (normalizeSlotStem(stem) == null) return null
        return stem + ext + (if (isBackup) ".bak" else "")
    }

    /** 槽位键 → Tyranor 文件名。 */
    fun tyranorNameForSlot(slot: String, engine: EngineType): String? {
        val isBackup = slot.endsWith(".bak")
        if (isBackup && !supportsBackup(engine)) return null
        val stem = if (isBackup) slot.removeSuffix(".bak") else slot
        val base = tyranorBase(stem, engine) ?: return null
        return base + (if (isBackup) "bak" else "") + ".bin"
    }

    /** 哈希存档名（key_<sha256>.bin）判定。 */
    fun isHashedTyranorName(name: String): Boolean = HASHED_KEY_NAME.matches(name)

    /**
     * 哈希存档名反解为标准文件名：枚举引擎可能用到的键（`RPG Global`/`RPG FileN`/…+bak 及 MZ 变体）
     * 逐个 sha256 比对，命中即返回标准名；无法还原（插件自定义键）返回 null。
     */
    fun hashedToStandardName(name: String, engine: EngineType): String? {
        val hash = HASHED_KEY_NAME.matchEntire(name)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
        val ext = standardExtension(engine) ?: return null
        val mv = engine == EngineType.RPG_MV
        val candidates = buildList {
            add((if (mv) "RPG Global" else "global") to "global")
            add((if (mv) "RPG Config" else "config") to "config")
            if (mv) {
                add("RPG Globalbak" to "global")
                add("RPG Configbak" to "config")
            }
            for (slot in 1..MAX_SAVE_SLOT) {
                add((if (mv) "RPG File$slot" else "file$slot") to "file$slot")
                if (mv) add("RPG File${slot}bak" to "file$slot")
            }
        }
        for ((key, stem) in candidates) {
            if (sha256(key) != hash) continue
            val isBackup = key.endsWith("bak")
            return stem + ext + (if (isBackup) ".bak" else "")
        }
        return null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val MAX_ENTRY_SEARCH_DEPTH = 2
    private val WEB_ENTRY_SUBDIRS = arrayOf(
        "www", "resources", "app.asar", "app", "tyrano", "data", "scenario", "system", "game",
    )
}
