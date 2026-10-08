package com.tyranor.next.core.game.launch

import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.EngineScanner
import java.util.Locale

/**
 * 「启动文件」候选的纯选择逻辑（过滤 / 排序 / 当前项推导），目录 I/O 由调用方注入。
 *
 * 目录枚举有两套来源：真实路径 File 直读（内置存储或已授予“所有文件访问”）与 SAF 兜底
 * （SD 卡 / 未授权时 File 枚举静默为空）。本对象只消费统一的 [Entry]，保证两条来源下的
 * 过滤、排序与回显语义一致，并可脱离 Android 运行时做单元测试。
 */
internal object LaunchFileCandidates {

    /** 目录内一个候选文件；SAF provider 不报体积时为 0。 */
    data class Entry(val name: String, val size: Long)

    /** 旧版目录哨兵 launchTarget（“[游戏目录]”）。 */
    const val LEGACY_GAME_DIR_TARGET = "\u005B\u6E38\u620F\u76EE\u5F55\u005D"

    /** KRKR 脚本/主启动归档优先顺序（自动探测与弹窗回显共用，避开 bgimage 等纯素材档）。 */
    val KR_PREFERRED_NAMES = listOf(
        "data.xp3", "main.xp3", "scn.xp3", "patch.xp3", "scenario.xp3",
        "startup.tjs", "0.ebk",
    )

    /** 手动启动文件（可含子目录相对路径）归一为文件名。 */
    fun fileNameOf(manual: String): String =
        manual.trim().replace('\\', '/').substringAfterLast('/')

    /** KRKR 候选展示顺序：xp3 在前、exe 在后，组内按名称忽略大小写排序。 */
    fun krkrNames(entries: List<Entry>): List<String> {
        val xp3 = entries.filter { it.name.isNotBlank() && it.name.lowercase(Locale.ROOT).endsWith(".xp3") }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { it.name }
        val exe = entries.filter { it.name.isNotBlank() && it.name.lowercase(Locale.ROOT).endsWith(".exe") }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { it.name }
        return xp3 + exe
    }

    /** launchTarget 是否可作为自动入口候选（排除目录哨兵与 bg* 素材档）。 */
    fun isLaunchTargetCandidate(target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        if (target == LEGACY_GAME_DIR_TARGET) return false
        if (target.equals(EngineScanner.LAUNCH_TARGET_GAME_DIR, ignoreCase = true)) return false
        return !target.lowercase(Locale.ROOT).startsWith("bg")
    }

    /**
     * KRKR 当前入口名：手动 → 常见启动档 → launchTarget → 首个非 bg xp3；
     * 按 [entries] 原始枚举顺序推导，与 `EngineLauncher.pickKrActivateEntry` 保持一致。
     * 返回项可能不在展示候选（如 `startup.tjs` / 子目录手动项）内，由弹窗选择是否预选。
     */
    fun krkrCurrentName(entries: List<Entry>, game: ScanGame): String? {
        fun find(name: String): String? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.name

        game.launchFile?.takeIf { it.isNotBlank() }?.let { manual ->
            val normalized = manual.trim().replace('\\', '/')
            // 子目录手动项不在根目录候选列表内：原样返回（弹窗不预选），
            // 避免确认后被改写为根目录同名文件而改变启动项
            if (normalized.contains('/')) return normalized
            return find(normalized) ?: normalized
        }
        KR_PREFERRED_NAMES.forEach { preferred -> find(preferred)?.let { return it } }
        if (isLaunchTargetCandidate(game.launchTarget)) {
            find(fileNameOf(game.launchTarget))?.let { return it }
        }
        return entries.firstOrNull {
            it.name.lowercase(Locale.ROOT).endsWith(".xp3") && !it.name.lowercase(Locale.ROOT).startsWith("bg")
        }?.name
    }

    /**
     * Windows（YU-RIS / PC / CatSystem2）当前入口名：手动（可含子目录）优先，其次自动首选项。
     * 子目录手动项不在根目录候选内，原样返回（弹窗不预选）。
     */
    fun windowsCurrentName(
        candidates: List<YurisLaunchFiles.ExeCandidate>,
        manual: String?,
        allowBin: Boolean,
    ): String? {
        manual?.takeIf { it.isNotBlank() }?.let { raw ->
            val normalized = raw.trim().replace('\\', '/')
            // 子目录手动项不在根目录候选列表内：原样返回（弹窗不预选）
            if (normalized.contains('/')) return normalized
            if (YurisLaunchFiles.isLaunchableName(normalized, allowBin)) {
                // 列表中存在同项时回显列表原始名（SAF 与真实路径大小写可能不同）
                return candidates.firstOrNull { it.name.equals(normalized, ignoreCase = true) }?.name ?: normalized
            }
        }
        return candidates.firstOrNull()?.name
    }
}
