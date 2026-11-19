package com.tyranor.next.core.game.scan

import java.util.Locale

/**
 * MAGES. 宿主（外置 impacto 宿主 APK）能启动的游戏 profile 及其数据目录特征。
 *
 * 来源有两类：
 * - 上游 impacto 自带的 profile：固定提交 `ba0b6e0884b9016fcd7dfd3e50024c5af1e8aef7` 的 `gamedefinitions.lua` 与各
 *   `profiles/<id>/vfs.lua`（由 `tools/impacto/check-game-catalog.py` 对照校验）。查看器类条目不是游戏，不收录。
 * - 宿主仓库 yuukana1/Tyranor-Next-MAGES 的补丁系列新增的 `mo6tw-pc*`（Windows／Steam 版《秋之回忆6》）：上游没有，
 *   判据与宿主 `engine/impacto/profiles.json` 里对应项的 `detect` 逐项一致（两边各持一份，改动须同步），
 *   不在 `check-game-catalog.py` 的校验范围内。
 *
 * 上游在 Android 上按精确大小写打开 `<RootGamedataDir>/<profile>/<文件>`，所以这里也只按**精确大小写**的条目名识别；
 * `MOVIE`/`movie` 影片档不作为特征，缺影片的目录仍可识别。识别只说明目录布局与某个 profile 一致，不代表已验证该游戏可运行，
 * 也不核对游戏身份（布局相同的其他游戏会被当成同一个）。
 *
 * 已知局限：mo6tw（PS3）的档名全部转成小写后，与 darling 的要求集合完全相同，仅凭根目录档名无法区分。此时一律
 * **不识别**（darling 因此不会被自动识别），而不是猜一个 profile。本目录表不尝试读取档案内容来消歧。
 */
object ImpactoGameCatalog {
    data class Game(
        /** 宿主 `-g` 参数使用的 profile id，同时也是 `gamedata/<id>` 目录名。 */
        val profileId: String,
        val title: String,
        /** 数据目录根下必须同时存在的条目名（精确大小写，文件或目录均可）。 */
        val requiredEntries: Set<String>,
        /** 根下 [DATA_DIR] 目录里必须同时存在的文件名（精确大小写）；只有档案放在 `data/` 里的 PC 版布局非空。 */
        val requiredDataEntries: Set<String> = emptySet(),
        /** 同一份数据按文本语言拆成多个 profile 时的共同标识（如 `mo6tw-pc`）；单一 profile 为 null。 */
        val family: String? = null,
        /** 该 profile 的文本语言（BCP 47：[TEXT_JA]、[TEXT_ZH_HANS]、[TEXT_ZH_HANT]）；不是语言变体时为 null。 */
        val textLanguage: String? = null,
        /** 该 profile 要求的宿主 APK 最低 versionCode；0 表示只受模块级最低版本约束。 */
        val minHostVersionCode: Int = 0,
    )

    /** 上游有「妄想触发鼠标支持」增强项的 profile（Chaos;Head / Chaos;Child 的 Love Chu Chu 版）。 */
    val DELUSION_PROFILES: Set<String> = setOf("chlcc", "cclcc")

    /** Windows／Steam 版把档案放在游戏根下的这个目录里（精确小写，宿主按 `<游戏目录>/data/<档>.cpk` 打开）。 */
    const val DATA_DIR = "data"

    const val TEXT_JA = "ja"
    const val TEXT_ZH_HANS = "zh-Hans"
    const val TEXT_ZH_HANT = "zh-Hant"

    /** `mo6tw-pc*` 由宿主补丁系列提供，宿主 0.2.0（versionCode 4）起才认识；更早的宿主会拒绝这些 profile。 */
    private const val PC_MIN_HOST_VERSION_CODE = 4

    private const val PC_FAMILY = "mo6tw-pc"
    private const val PC_TITLE = "Memories Off 6 ~T-Wave~ (PC)"

    /** 三个 `mo6tw-pc*` 变体共同要求的 `data/` 内文件：脚本档与原文日文的文本档（与宿主 `profiles.json` 的 `allOf` 一致）。 */
    private val PC_COMMON_DATA_ENTRIES = setOf("script.cpk", "mes00.cpk")

    private fun game(profileId: String, title: String, vararg entries: String) =
        Game(profileId, title, entries.toSet())

    /**
     * Windows／Steam 版《秋之回忆6》的一个语言变体。Steam 版同时带着 `mes00/01/02.cpk` 三份文本，读哪一份由 profile 决定，
     * 所以三个 profile 的特征只差各自的文本档；`mes00.cpk`（原文日文）是共同要求。
     */
    private fun pcEdition(profileId: String, textLanguage: String, textArchive: String) = Game(
        profileId = profileId,
        title = PC_TITLE,
        requiredEntries = setOf("game.exe", DATA_DIR),
        requiredDataEntries = PC_COMMON_DATA_ENTRIES + textArchive,
        family = PC_FAMILY,
        textLanguage = textLanguage,
        minHostVersionCode = PC_MIN_HOST_VERSION_CODE,
    )

    val games: List<Game> = listOf(
        game(
            "chlcc", "CHAOS;HEAD Love Chu☆Chu!",
            "BG.CPK", "BGM.CPK", "CHARA.CPK", "MASK.CPK", "SCRIPT.CPK", "SE.CPK", "SYSTEM.CPK", "VOICE.CPK",
        ),
        game(
            "cclcc", "CHAOS;CHILD Love Chu☆Chu!! (PS4)",
            "bg.mpk", "bgeffect.mpk", "bgm.mpk", "chara.mpk", "manual.mpk", "mask.mpk", "script", "se.mpk",
            "sysse.mpk", "system.mpk", "voice.mpk",
        ),
        game(
            "cclcc-switch", "CHAOS;CHILD Love Chu☆Chu!! (Switch)",
            "bg_webp.mpk", "bgm_ninopus.mpk", "chara_webp.mpk", "mask.mpk", "script.cls", "se_ninopus.mpk",
            "sysse_ninopus.mpk", "system_swi.mpk", "voice_ninopus.mpk",
        ),
        game(
            "mo6tw", "Memories Off 6 ~T-Wave~",
            "BG.CPK", "BGM.CPK", "CHARA.CPK", "MASK.CPK", "SCRIPT.cls", "SE.CPK", "SYSTEM.CPK", "VOICE.CPK",
        ),
        // 以下三个是同一份 Windows／Steam 数据的语言变体；顺序即默认偏好，原文日文排第一（见 [pick]）。
        pcEdition("mo6tw-pc", TEXT_JA, "mes00.cpk"),
        pcEdition("mo6tw-pc-zhs", TEXT_ZH_HANS, "mes02.cpk"),
        pcEdition("mo6tw-pc-zht", TEXT_ZH_HANT, "mes01.cpk"),
        game(
            "darling", "STEINS;GATE: My Darling's Embrace",
            "script.cls", "system.cpk", "bgm.cpk", "se.cpk", "voice.cpk", "bg.cpk", "chara.cpk", "mask.cpk",
        ),
        game(
            "dash", "ROBOTICS;NOTES DaSH",
            "script.cls", "system.cls", "bgm.cls", "se.cls", "sysse.cls", "voice.cls", "model.cls", "motion.cls",
            "bg.cls", "mask.cls",
        ),
        game(
            "rne", "ROBOTICS;NOTES ELITE",
            "script.cls", "system.cpk", "bgm.cpk", "se.cpk", "sysse.dat", "voice.cpk", "model.cpk", "bg.cpk",
            "mask.cpk",
        ),
    )

    /** 所有 profile 在 [DATA_DIR] 里要求的文件名之并集；扫描只需记录这些名字，不必把一个大 `data/` 目录的全部文件名都存下来。 */
    val dataEntryNames: Set<String> = games.flatMapTo(HashSet()) { it.requiredDataEntries }

    fun byProfileId(profileId: String?): Game? = games.firstOrNull { it.profileId == profileId }

    /**
     * 特征全部命中的 profile，按目录表顺序。
     *
     * - 空：没有命中，或布局有歧义（互不相干的多个 profile 同时命中，或与另一 profile 仅差大小写），不猜。
     * - 一个：唯一命中。
     * - 多个：同属一个 [Game.family] 的语言变体。它们读同一份数据，只是挑的文本档不同，
     *   选哪个是偏好问题而不是识别问题，交给调用方用 [pick] / [preferredProfileId] 决定。
     *
     * @param rootEntryNames 游戏目录根下的条目名（精确大小写）。
     * @param dataEntryNames 游戏目录根下 [DATA_DIR] 目录里的文件名（精确大小写）；没有该目录时留空。
     */
    fun candidates(rootEntryNames: Set<String>, dataEntryNames: Set<String> = emptySet()): List<Game> {
        val hits = games.filter { it.matches(rootEntryNames, dataEntryNames) }
        if (hits.isEmpty()) return emptyList()
        val family = hits.first().family
        val singleLayout = hits.size == 1 || (family != null && hits.all { it.family == family })
        if (!singleLayout) return emptyList()
        return if (hits.any(::isCaseTwinOfAnother)) emptyList() else hits
    }

    /**
     * 在 [candidates] 的结果里选一个：文本语言等于 [preferredTextLanguage] 的优先，没有偏好或没有这个语言时取目录表里
     * 排最前的（日文原文）。候选为空返回 null。
     */
    fun pick(candidates: List<Game>, preferredTextLanguage: String? = null): Game? =
        candidates.firstOrNull { preferredTextLanguage != null && it.textLanguage == preferredTextLanguage }
            ?: candidates.firstOrNull()

    /**
     * 扫描阶段只带着候选的 profile id（[candidates] 的结果），这里按应用界面语言标签（BCP 47，如 `zh-CN`、`ja`、`en-US`）
     * 选出最终的 profile id。界面语言没有对应的文本语言（如英文）时取默认候选。
     */
    fun preferredProfileId(profileIds: List<String>, uiLanguageTag: String?): String? =
        pick(profileIds.mapNotNull(::byProfileId), textLanguageForUiTag(uiLanguageTag))?.profileId

    /**
     * 应用界面语言 → 文本语言：中文先看脚本（`Hant` 繁体、`Hans` 简体），脚本缺省时再看地区（`TW`、`HK`、`MO` 为繁体，
     * 其余为简体），所以显式写了 `Hans` 的 `zh-Hans-HK` 仍是简体；日文对日文；其他（含英文）没有对应的文本语言，返回 null。
     */
    fun textLanguageForUiTag(uiLanguageTag: String?): String? {
        if (uiLanguageTag.isNullOrBlank()) return null
        val locale = Locale.forLanguageTag(uiLanguageTag)
        return when (locale.language) {
            "ja" -> TEXT_JA
            "zh" -> when {
                locale.script.equals("Hant", ignoreCase = true) -> TEXT_ZH_HANT
                locale.script.equals("Hans", ignoreCase = true) -> TEXT_ZH_HANS
                locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS -> TEXT_ZH_HANT
                else -> TEXT_ZH_HANS
            }
            else -> null
        }
    }

    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")

    private fun Game.matches(rootEntryNames: Set<String>, dataEntryNames: Set<String>): Boolean =
        rootEntryNames.containsAll(requiredEntries) && dataEntryNames.containsAll(requiredDataEntries)

    /** 根条目与 `data/` 内文件放在一起的完整特征，用于比较两个 profile 的布局是否只差大小写。 */
    private fun Game.layout(): Set<String> = requiredEntries + requiredDataEntries.map { "$DATA_DIR/$it" }

    /**
     * 全小写的特征集合与另一个 profile 仅差大小写：此时根目录档名分不出是哪个游戏，宁可不识别，也不套错 profile
     * （错的 profile 会带错标题并以 `-g` 启动错误的游戏配置）。同一 [Game.family] 的语言变体特征不同，不算。
     */
    private fun isCaseTwinOfAnother(game: Game): Boolean {
        val own = game.layout()
        if (own.any { it != it.lowercase() }) return false
        return games.any { other ->
            other !== game && other.layout().map { it.lowercase() }.toSet() == own
        }
    }
}
