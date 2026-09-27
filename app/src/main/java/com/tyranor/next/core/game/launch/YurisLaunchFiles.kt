package com.tyranor.next.core.game.launch

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import java.io.File
import java.util.Locale

/**
 * Windows 游戏主程序（.exe）解析（YU-RIS 自动挑选与手动添加的 PC 游戏共用）。
 *
 * 目录根下通常只有主程序与 `settings.exe` 等少量可执行文件，主程序体积明显更大但没有固定命名。
 * 解析顺序：
 * 1. 单游戏「启动文件」手动指定（[ScanGame.launchFile]，支持子目录相对路径）；
 * 2. 根目录 `.exe` 中排除 settings/unins/setup/install/update/patch/crack/keygen 等干扰项后，
 *    优先「文件名与目录名匹配」，其次「体积最大」，最后按名称排序；
 * 3. 若排除后为空，退回未过滤集合（仍按目录名匹配 → 体积最大排序）。
 *
 * [candidatesOf] 供 SAF 侧（PC 游戏添加弹窗）使用：只需要「名称 + 体积」两个字段。
 */
object YurisLaunchFiles {

    /** 候选 exe（名称 + 体积）。 */
    data class ExeCandidate(val name: String, val size: Long)

    /** 干扰项关键词：出现在文件名任意位置即排除（不含扩展名）。 */
    private val EXCLUDED_KEYWORDS = listOf(
        "settings", "unins", "setup", "install", "update", "updater", "patch", "crack", "keygen",
    )

    /** 根目录候选（已过滤干扰项并排序）；[allowBin] 额外接受 `.bin`（CatSystem2 Runtime 可能改名/改扩展名）。 */
    fun candidates(
        projectDir: File,
        allowBin: Boolean = false,
        preferCs2Runtime: Boolean = false,
    ): List<ExeCandidate> {
        val entries = projectDir.listFiles()
            ?.filter { it.isFile && isLaunchableName(it.name, allowBin) }
            ?.map { ExeCandidate(it.name, it.length()) }
            ?: return emptyList()
        return candidatesOf(entries, projectDir.name, allowBin, preferCs2Runtime)
    }

    /** 候选（文件名 + 体积）：过滤干扰项后按「cs2 Runtime → 目录名匹配 → 体积降序 → 名称」排序。 */
    fun candidatesOf(
        entries: List<ExeCandidate>,
        dirName: String,
        allowBin: Boolean = false,
        preferCs2Runtime: Boolean = false,
    ): List<ExeCandidate> {
        val launchable = entries.filter { isLaunchableName(it.name, allowBin) }
        val filtered = launchable.filterNot { isExcluded(it.name) }
        return sortCandidates(filtered.ifEmpty { launchable }, dirName, preferCs2Runtime)
    }

    private fun isLaunchableName(name: String, allowBin: Boolean): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".exe") || (allowBin && lower.endsWith(".bin"))
    }

    /** 解析实际启动的 exe；返回 null 表示目录内没有可用 exe。 */
    fun resolveExe(game: ScanGame, projectDirPath: String): File? {
        val projectDir = File(projectDirPath)
        if (!projectDir.isDirectory) return null
        val allowBin = game.engine == EngineType.CATSYSTEM2
        val preferCs2 = game.engine == EngineType.CATSYSTEM2
        game.launchFile?.takeIf { it.isNotBlank() }?.let { manual ->
            val candidate = File(projectDir, manual.trim())
            if (candidate.isFile && isLaunchableName(candidate.name, allowBin)) return candidate
        }
        return candidates(projectDir, allowBin, preferCs2).firstOrNull()?.let { File(projectDir, it.name) }
    }

    /** 解析出的 exe 文件名（相对游戏目录）；无可用 exe 返回 null。 */
    fun resolveExeName(game: ScanGame, projectDirPath: String): String? =
        resolveExe(game, projectDirPath)?.name

    /** 当前生效的启动文件名（供「启动文件」选择器回显）。 */
    fun currentLaunchFileName(projectDir: File): String? = candidates(projectDir).firstOrNull()?.name

    internal fun isExcluded(fileName: String): Boolean {
        val stem = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
        return EXCLUDED_KEYWORDS.any { stem.contains(it) }
    }

    private fun sortCandidates(
        exes: List<ExeCandidate>,
        dirName: String,
        preferCs2Runtime: Boolean,
    ): List<ExeCandidate> {
        val normalizedDir = normalizeName(dirName)
        return exes.sortedWith(
            compareBy<ExeCandidate> { candidate ->
                val stem = normalizeName(candidate.name.substringBeforeLast('.'))
                when {
                    preferCs2Runtime && stem == "cs2" -> 0
                    normalizedDir.isNotEmpty() && stem == normalizedDir -> 1
                    normalizedDir.isNotEmpty() && (stem.contains(normalizedDir) || normalizedDir.contains(stem)) -> 2
                    else -> 3
                }
            }.thenByDescending { it.size }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )
    }

    /** 归一化：仅保留字母数字（小写），用于目录名与 exe 名的宽松比较。 */
    private fun normalizeName(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
