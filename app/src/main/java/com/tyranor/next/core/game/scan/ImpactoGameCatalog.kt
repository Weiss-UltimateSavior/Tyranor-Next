package com.tyranor.next.core.game.scan

/**
 * 原版 Impacto 支持的游戏 profile 及其数据目录特征。
 *
 * 来源：固定提交 `ba0b6e0884b9016fcd7dfd3e50024c5af1e8aef7` 的 `gamedefinitions.lua` 与各 `profiles/<id>/vfs.lua`
 * （由 `tools/impacto/check-game-catalog.py` 对照校验）。查看器类条目不是游戏，不收录。
 *
 * 上游在 Android 上按精确大小写打开 `<RootGamedataDir>/<profile>/<文件>`，所以这里也只按**精确大小写**的
 * 根目录条目名识别；`MOVIE`/`movie` 影片档不作为特征，缺影片的目录仍可识别。识别只说明目录布局与某个上游
 * profile 一致，不代表已验证该游戏可运行。
 *
 * 已知局限：mo6tw 的档名全部转成小写后，与 darling 的要求集合完全相同，仅凭根目录档名无法区分。此时一律
 * **不识别**（darling 因此不会被自动识别），而不是猜一个 profile。本目录表不尝试读取档案内容来消歧。
 */
object ImpactoGameCatalog {
    data class Game(
        /** 上游 `-g` 参数使用的 profile id，同时也是 `gamedata/<id>` 目录名。 */
        val profileId: String,
        val title: String,
        /** 数据目录根下必须同时存在的条目名（精确大小写，文件或目录均可）。 */
        val requiredEntries: Set<String>,
    )

    private fun game(profileId: String, title: String, vararg entries: String) =
        Game(profileId, title, entries.toSet())

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

    fun byProfileId(profileId: String?): Game? = games.firstOrNull { it.profileId == profileId }

    /**
     * 按根目录条目名（精确大小写）识别 profile。恰好一个 profile 的特征全部命中才返回；
     * 零个或多个（布局有歧义）都返回 null，不猜。
     */
    fun match(rootEntryNames: Set<String>): Game? {
        val hit = games.filter { rootEntryNames.containsAll(it.requiredEntries) }.singleOrNull() ?: return null
        return if (isCaseTwinOfAnother(hit)) null else hit
    }

    /**
     * 全小写的名字集合与另一个 profile 仅差大小写：此时根目录档名分不出是哪个游戏，宁可不识别，也不套错 profile
     * （错的 profile 会带错标题并以 `-g` 启动错误的游戏配置）。
     */
    private fun isCaseTwinOfAnother(game: Game): Boolean {
        if (game.requiredEntries.any { it != it.lowercase() }) return false
        return games.any { other ->
            other !== game && other.requiredEntries.map { it.lowercase() }.toSet() == game.requiredEntries
        }
    }
}
