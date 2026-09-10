package com.tyranor.next.core.game.save

import com.core.engine.WebGameEntryLocator
import com.tyranor.next.core.engine.EngineType
import java.io.File
import java.util.Locale

/**
 * RPG Maker MV/MZ 存档文件名在「Tyranor 格式」与「JoiPlay/PC 标准格式」之间的映射与检测。
 *
 * 两种格式内容字节级一致，仅文件名不同；因此转化是纯改名（不重编码），导出/导入按需换名即可。
 *
 * MV（引擎经 webStorageKey 派生 `RPG ...` 键，桥按 legacy 文件名落盘）：
 * | Tyranor 格式        | 标准格式（JoiPlay/PC） |
 * | ------------------ | --------------------- |
 * | RPG Global.bin     | global.rpgsave        |
 * | RPG Config.bin     | config.rpgsave        |
 * | RPG FileN.bin      | fileN.rpgsave         |
 * | RPG Globalbak.bin  | global.rpgsave.bak    |
 * | RPG Configbak.bin  | config.rpgsave.bak    |
 * | RPG FileNbak.bin   | fileN.rpgsave.bak     |
 *
 * MZ（hook 以 `global`/`config`/`fileN` 为键直接落盘，核心不带备份）：
 * | global.bin / config.bin / fileN.bin | global.rmmzsave / config.rmmzsave / fileN.rmmzsave |
 *
 * `key_<sha256>.bin` 形态是 TyranorStorage 对含空格/非 ASCII 键的确定性哈希映射（键不可逆），
 * 只报告不转化。
 */
object RpgSaveFormat {

    /** 标准存档扫描结果。 */
    data class Detection(
        /** 可转化的标准存档文件（含 `.bak`）。 */
        val standardFiles: List<File>,
        /** 检测到但不可转化的 Tyranor 哈希存档数量（key_<sha256>.bin）。 */
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

    /** 转化时源文件留底的子目录名（位于存档目录内，不参与列表/导出/删除）。 */
    const val ORIGINAL_DIR = "original"

    /** MV 的 Tyranor 文件名：`RPG Global.bin` / `RPG File1bak.bin` 等（键含空格，走 legacy 文件名）。 */
    private val MV_TYRANOR_NAME = Regex("^rpg (global|config|file(\\d+))(bak)?\\.bin$", RegexOption.IGNORE_CASE)

    /** MZ 的 Tyranor 文件名：`global.bin` / `file1.bin` 等（键为纯 ASCII，直接落盘）。 */
    private val MZ_TYRANOR_NAME = Regex("^(global|config|file(\\d+))(bak)?\\.bin$", RegexOption.IGNORE_CASE)

    private val HASHED_KEY_NAME = Regex("^key_[0-9a-f]{64}\\.bin$", RegexOption.IGNORE_CASE)

    private const val MV_EXT = ".rpgsave"
    private const val MZ_EXT = ".rmmzsave"

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

    /**
     * 存档目录：`<内容根>/save`（内容根 = 含 index.html / app.asar 的目录）。定位失败时回退
     * `<游戏根>/save`。与引擎宿主写入端（WebGameEntryLocator + contentRoot/save）同源。
     */
    fun resolveSaveDirectory(gameRoot: File, engine: EngineType): File {
        val contentRoot = WebGameEntryLocator.locate(gameRoot)?.contentRoot ?: gameRoot
        return File(contentRoot, "save")
    }

    /** 备选存档目录（历史大小写 `Save/`）：仅用于读取检测；转化统一写入小写 `save/`。 */
    fun legacyCaseSaveDirectory(saveDir: File): File = File(saveDir.parentFile, "Save")

    /**
     * 在存档目录直接子文件中检测标准格式存档与哈希存档。
     * 仅扫描给定目录本身（不递归），因此 `original/` 内的留底不会重复触发提示。
     */
    fun detect(saveDir: File, engine: EngineType): Detection {
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

    /**
     * 汇总探测所有候选存档目录：`save/`、历史大写 `Save/`，以及冗余包装的 `save/save/`
     * （导入带单层文件夹的备份包时会出现该形态）。按规范化路径去重，避免大小写不敏感文件系统
     * 上同一目录被重复计数。转化始终写回 `save/` 根，因此命中的嵌套文件会被归位。
     */
    fun detectAny(saveDir: File, engine: EngineType): Detection {
        if (standardExtension(engine) == null) return Detection(emptyList(), 0)
        val seen = mutableSetOf<String>()
        val files = mutableListOf<File>()
        var hashed = 0
        candidateSaveDirs(saveDir).forEach { root ->
            if (!seen.add(pathKey(root))) return@forEach
            val detection = detect(root, engine)
            files += detection.standardFiles
            hashed += detection.hashedCount
        }
        return Detection(files, hashed)
    }

    /** 候选存档目录：本体、历史大写、以及冗余 `save/save`（低风险自愈形态）。 */
    private fun candidateSaveDirs(saveDir: File): List<File> = listOf(
        saveDir,
        legacyCaseSaveDirectory(saveDir),
        File(saveDir, "save"),
        File(saveDir, "Save"),
    )

    private fun pathKey(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).lowercase(Locale.ROOT)

    /** 标准文件名判定（`global|config|fileN` + 引擎扩展名 + MV 可带 `.bak`）。 */
    fun isStandardName(name: String, engine: EngineType): Boolean {
        val ext = standardExtension(engine) ?: return false
        val lower = name.lowercase(Locale.ROOT)
        val backupSuffix = "$ext.bak"
        // 必须先判定 .bak 后缀：扩展名判定对 "global.rpgsave.bak" 不成立（结尾是 .bak）
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
        val base = when {
            stem == "global" -> if (engine == EngineType.RPG_MV) "RPG Global" else "global"
            stem == "config" -> if (engine == EngineType.RPG_MV) "RPG Config" else "config"
            else -> {
                val id = stem.removePrefix("file")
                if (engine == EngineType.RPG_MV) "RPG File$id" else "file$id"
            }
        }
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

    /** 哈希存档名（key_<sha256>.bin）判定，供 UI 报告。 */
    fun isHashedTyranorName(name: String): Boolean = HASHED_KEY_NAME.matches(name)
}
