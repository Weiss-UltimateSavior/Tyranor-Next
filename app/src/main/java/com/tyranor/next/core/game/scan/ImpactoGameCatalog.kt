package com.tyranor.next.core.game.scan

import android.content.Context
import java.util.Locale

/**
 * MAGES. 宿主（外置 impacto 宿主 APK）能启动的游戏 profile 及其数据目录特征。
 *
 * 来源有两类：
 * - 上游 impacto 自带的 profile：固定提交 `ba0b6e0884b9016fcd7dfd3e50024c5af1e8aef7` 的 `gamedefinitions.lua` 与各
 *   `profiles/<id>/vfs.lua`（由 `tools/impacto/check-game-catalog.py` 对照校验）。查看器类条目不是游戏，不收录。
 * - 宿主仓库 SIRENA-Core/Tyranor-Next-MAGES 的补丁系列新增的 `mo6tw-pc*`（Windows／Steam 版《秋之回忆6》）：上游没有，
 *   判据与宿主 `engine/impacto/profiles.json` 里对应项的 `detect` 逐项一致（两边各持一份，改动须同步），
 *   不在 `check-game-catalog.py` 的校验范围内。
 *
 * 上游在 Android 上按精确大小写打开 `<RootGamedataDir>/<profile>/<文件>`，所以这里也只按**精确大小写**的条目名识别；
 * `MOVIE`/`movie` 影片档不作为特征，缺影片的目录仍可识别。识别只说明目录布局与某个 profile 一致，不代表已验证该游戏可运行，
 * 也不核对游戏身份（布局相同的其他游戏会被当成同一个）。
 *
 * 已知局限：mo6tw（PS3）的档名全部转成小写后，与 darling 的要求集合完全相同，仅凭根目录档名无法区分。此时一律
 * **不识别**（darling 因此不会被自动识别），而不是猜一个 profile。本目录表不尝试读取档案内容来消歧。
 *
 * 规则来源：已安装的宿主带有识别规则（[ImpactoHostProfiles]）时以宿主的为准，宿主新增的 profile 不用更新本 App 就能识别；
 * 宿主未安装或是没有这份规则的旧版本时，用下面的内置目录表 [builtInGames]。扫描开始前由 [refresh] 读取一次。
 *
 * 未支持的 MAGES. 布局：没有任何 profile 全部命中、但目录布局像 MAGES. 游戏（[UnsupportedLayout]，不区分大小写）时，
 * 扫描仍把它当作 MAGES. 游戏入库，标注「暂不支持」，启动时用该布局指定的 profile 尝试运行。多个 profile 同时全部命中
 * （布局有歧义）的目录仍然不识别。
 */
object ImpactoGameCatalog {
    data class Game(
        /** 宿主 `-g` 参数使用的 profile id，同时也是 `gamedata/<id>` 目录名。 */
        val profileId: String,
        val title: String,
        /** 数据目录根下必须同时存在的条目名（精确大小写，文件或目录均可）。 */
        val requiredEntries: Set<String>,
        /**
         * 根下 [DATA_DIR] 目录里必须同时存在的文件名（精确大小写）；内置目录表里只有档案放在 `data/` 里的 PC 版布局非空。
         * 来自宿主规则的 profile 把子目录里的条目直接写在 [requiredEntries] 里（如 `USRDIR/script.mpk`），这里为空。
         */
        val requiredDataEntries: Set<String> = emptySet(),
        /** 同一份数据按文本语言拆成多个 profile 时的共同标识（如 `mo6tw-pc`）；单一 profile 为 null。 */
        val family: String? = null,
        /** 该 profile 的文本语言（BCP 47：[TEXT_JA]、[TEXT_ZH_HANS]、[TEXT_ZH_HANT]）；不是语言变体时为 null。 */
        val textLanguage: String? = null,
        /** 该 profile 要求的宿主 APK 最低 versionCode；0 表示只受模块级最低版本约束。 */
        val minHostVersionCode: Int = 0,
        /**
         * 必须**不存在**的条目（路径规则同 [requiredEntries]，宿主规则的 `detect.noneOf`）：同一代引擎的另一个游戏
         * 带着这个 profile 的全部特征时，用它把那个游戏排除掉（如 `sg-pc` 排除带 `USRDIR/mpk_bg.lst` 的 STEINS;GATE 0）。
         */
        val excludedEntries: Set<String> = emptySet(),
    )

    /**
     * 目录布局像 MAGES. 游戏、但没有 profile 能识别时的「未支持布局」：[requiredEntries] 全部存在（不区分大小写，
     * 路径规则同 [Game.requiredEntries]）即命中，启动时用 [tryProfile] 尝试运行。
     */
    data class UnsupportedLayout(
        val id: String,
        val requiredEntries: Set<String>,
        val tryProfile: String,
    )

    /**
     * 一套识别规则：profile 目录表与未支持布局表。一次扫描 / 一次识别只用同一个 [Rules] 快照
     * （[currentRules]），扫描途中宿主规则被替换也不会混用两套规则。
     */
    class Rules(val games: List<Game>, val unsupportedLayouts: List<UnsupportedLayout>) {
        private val entries: List<String> by lazy {
            // 排除条目也要记录下来，否则扫描时看不到它们，排除不起作用
            games.flatMap { it.layout() + it.excludedEntries } + unsupportedLayouts.flatMap { it.requiredEntries }
        }

        /** 规则里出现的子目录名（小写）：扫描时只展开根目录下这些子目录。 */
        val subdirectoryNames: Set<String> by lazy {
            entries.mapNotNullTo(HashSet()) { entry ->
                entry.substringBefore('/', "").takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            }
        }

        /** 这些子目录里需要记录的文件名（小写；只记文件，不记目录）；不在其中的文件不影响识别。 */
        val subdirectoryEntryNames: Set<String> by lazy {
            entries.mapNotNullTo(HashSet()) { entry ->
                entry.substringAfter('/', "").takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            }
        }
    }

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

    /** Steam 版 STEINS;GATE（`sg-pc`）由宿主补丁系列提供，宿主 0.3.0（versionCode 5）起才认识。 */
    private const val SG_PC_MIN_HOST_VERSION_CODE = 5

    /** Steam 版 STEINS;GATE 0（`sg0-pc`）由宿主补丁系列提供，宿主 versionCode 11（0.4.3 之后的第一个版本）起才认识。 */
    private const val SG0_PC_MIN_HOST_VERSION_CODE = 11

    /**
     * 宿主补丁系列提供、没有语言变体的 Windows／Steam 版 profile：[entries] 是相对游戏目录的路径（精确大小写，
     * 最多一层子目录，如 `USRDIR/script.mpk`），与宿主 `profiles.json` 对应项的 `detect.allOf` 一致。
     */
    private fun pcGame(
        profileId: String,
        title: String,
        minHostVersionCode: Int,
        vararg entries: String,
        excludedEntries: Set<String> = emptySet(),
    ) = Game(
        profileId = profileId,
        title = title,
        requiredEntries = entries.toSet(),
        minHostVersionCode = minHostVersionCode,
        excludedEntries = excludedEntries,
    )

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

    /** 内置目录表：宿主未提供识别规则时使用。 */
    val builtInGames: List<Game> = listOf(
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
        pcGame(
            "sg-pc", "STEINS;GATE (PC, Steam)", SG_PC_MIN_HOST_VERSION_CODE,
            "Game.exe", "USRDIR/script.mpk", "USRDIR/system.mpk",
            // STEINS;GATE 0（宿主 `sg0-pc`）的目录也带着上面三项，`mpk_bg.lst` 只有它有（与宿主 detect.noneOf 一致）
            excludedEntries = setOf("USRDIR/mpk_bg.lst"),
        ),
        pcGame(
            "sg0-pc", "STEINS;GATE 0 (PC, Steam)", SG0_PC_MIN_HOST_VERSION_CODE,
            "Game.exe", "USRDIR/script.mpk", "USRDIR/system.mpk", "USRDIR/mpk_bg.lst",
        ),
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

    /**
     * 内置的未支持布局：宿主未提供识别规则时使用。`tryProfile` 取内置目录表里最接近的 profile
     * （同为 Windows／Steam 版 `data/` 里的 `.cpk`、同为 `.mpk` 或 `.CPK` 档案的布局）。
     */
    val builtInUnsupportedLayouts: List<UnsupportedLayout> = listOf(
        UnsupportedLayout("pc-data-cpk", setOf("$DATA_DIR/script.cpk", "$DATA_DIR/system.cpk"), "mo6tw-pc"),
        UnsupportedLayout("pc-usrdir-mpk", setOf("USRDIR/script.mpk", "USRDIR/system.mpk"), "sg-pc"),
        UnsupportedLayout("mpk", setOf("script.mpk", "system.mpk", "bgm.mpk"), "cclcc"),
        UnsupportedLayout("cpk", setOf("script.cpk", "system.cpk", "bgm.cpk"), "chlcc"),
    )

    private val BUILT_IN_RULES = Rules(builtInGames, builtInUnsupportedLayouts)

    /** 已安装宿主提供的规则；null 时用内置规则。扫描线程与启动流程都会读，所以是 volatile 的整体替换。 */
    @Volatile
    private var hostRules: Rules? = null

    /** 当前生效的规则快照：宿主规则优先，没有时为内置规则。 */
    fun currentRules(): Rules = hostRules ?: BUILT_IN_RULES

    /** 当前生效的 profile 目录表：宿主规则优先，没有时为 [builtInGames]。 */
    val games: List<Game> get() = currentRules().games

    /** 当前生效的未支持布局表。 */
    val unsupportedLayouts: List<UnsupportedLayout> get() = currentRules().unsupportedLayouts

    /**
     * 从已安装的宿主重新读取识别规则。在扫描与启动前调用；读取在调用线程完成。
     *
     * - 读到合格的规则：改用它。
     * - 宿主未安装、旧宿主没有这份资产、内容不合格式：回到内置规则（这些是确定的状态，不是偶发错误）。
     * - 读取途中出错（I/O、系统服务异常）：保留上一次的规则，不因一次偶发失败把宿主规则退回内置规则。
     */
    fun refresh(context: Context) {
        when (val loaded = ImpactoHostProfiles.load(context.applicationContext)) {
            is ImpactoHostProfiles.Load.Loaded -> hostRules = loaded.rules
            ImpactoHostProfiles.Load.Unavailable -> hostRules = null
            ImpactoHostProfiles.Load.Failed -> Unit
        }
    }

    /** 测试与 [refresh] 共用的替换入口；传 null 回到内置规则。 */
    internal fun useHostRules(rules: Rules?) {
        hostRules = rules
    }

    /** 按 profile id 查找：先查 [rules]（默认为当前生效的规则），再查内置目录表（宿主规则里没有的旧 profile 仍可启动）。 */
    fun byProfileId(profileId: String?, rules: Rules = currentRules()): Game? =
        rules.games.firstOrNull { it.profileId == profileId } ?: builtInGames.firstOrNull { it.profileId == profileId }

    /**
     * profile 要求的宿主最低 versionCode：取内置目录表与当前规则里的较大值。宿主规则不带版本要求（能提供规则的宿主
     * 本身已经足够新），不能让它把内置的要求（如 `sg-pc` 的 5）覆盖成 0；未知 profile 为 0。
     */
    fun minHostVersionCodeFor(profileId: String?): Int = maxOf(
        currentRules().games.firstOrNull { it.profileId == profileId }?.minHostVersionCode ?: 0,
        builtInGames.firstOrNull { it.profileId == profileId }?.minHostVersionCode ?: 0,
    )

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
    fun candidates(rootEntryNames: Set<String>, dataEntryNames: Set<String> = emptySet()): List<Game> =
        candidatesFor(rootEntryNames + dataEntryNames.map { "$DATA_DIR/$it" })

    /**
     * 同 [candidates]，条目以相对路径给出：根目录条目名，加上子目录里的文件 `目录名/文件名`（精确大小写）。
     * [rules] 是本次识别使用的规则快照。
     */
    fun candidatesFor(entries: Set<String>, rules: Rules = currentRules()): List<Game> {
        val hits = fullMatches(entries, rules)
        if (hits.isEmpty()) return emptyList()
        val family = hits.first().family
        val singleLayout = hits.size == 1 || (family != null && hits.all { it.family == family })
        if (!singleLayout) return emptyList()
        return if (hits.any { isCaseTwinOfAnother(it, rules) }) emptyList() else hits
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

    private fun fullMatches(entries: Set<String>, rules: Rules): List<Game> =
        rules.games.filter { game -> entries.containsAll(game.layout()) && game.excludedEntries.none { it in entries } }

    /**
     * 没有任何 profile 全部命中时，目录是否是「未支持的 MAGES. 布局」：返回第一个命中的 [UnsupportedLayout]
     * （不区分大小写；其 `tryProfile` 必须能找到）。有 profile 全部命中（含布局有歧义而不识别的情况）时返回 null。
     */
    fun unsupportedLayoutFor(entries: Set<String>, rules: Rules = currentRules()): UnsupportedLayout? {
        if (fullMatches(entries, rules).isNotEmpty()) return null
        val lower = entries.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return rules.unsupportedLayouts.firstOrNull { layout ->
            layout.requiredEntries.all { it.lowercase(Locale.ROOT) in lower } &&
                byProfileId(layout.tryProfile, rules) != null
        }
    }

    /** 根条目与 `data/` 内文件放在一起的完整特征，用于比较两个 profile 的布局是否只差大小写。 */
    private fun Game.layout(): Set<String> = requiredEntries + requiredDataEntries.map { "$DATA_DIR/$it" }

    /**
     * 全小写的特征集合与另一个 profile 仅差大小写：此时根目录档名分不出是哪个游戏，宁可不识别，也不套错 profile
     * （错的 profile 会带错标题并以 `-g` 启动错误的游戏配置）。同一 [Game.family] 的语言变体特征不同，不算。
     */
    private fun isCaseTwinOfAnother(game: Game, rules: Rules): Boolean {
        val own = game.layout()
        if (own.any { it != it.lowercase() }) return false
        return rules.games.any { other ->
            other !== game && other.layout().map { it.lowercase() }.toSet() == own
        }
    }
}
