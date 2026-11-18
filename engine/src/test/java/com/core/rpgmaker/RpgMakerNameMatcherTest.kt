package com.core.rpgmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 空白容忍匹配的行为锁定。用例取自真实游戏（ホムンクルスの研究記録）的
 * 素材目录快照：磁盘名用半角空格，游戏 data 引用全角空格。
 */
class RpgMakerNameMatcherTest {

    private val realDir = listOf(
        "ホーム画面 朝   .png",
        "ホーム画面  昼  .png",
        "ホーム画面  夜  .png",
        "イースウェン 街並み  .png",
        "イースウェン 街並み2  .png",
        "イースウェン 街並み3  .png",
        "イースウェン 街並み  夕方 .png",
        "イースウェン 街並み  夜 .png",
        "GAME OVER   .png",
    )

    @Test
    fun matchesIdeographicSpaceAgainstAsciiSpace() {
        // 回归用例：全角空格（U+3000）请求 → 磁盘上的半角空格文件
        assertEquals(
            "ホーム画面 朝   .png",
            RpgMakerNameMatcher.match(realDir, "ホーム画面\u3000朝   .png"),
        )
        assertEquals(
            "イースウェン 街並み  .png",
            RpgMakerNameMatcher.match(realDir, "イースウェン\u3000街並み  .png"),
        )
        assertEquals(
            "イースウェン 街並み2  .png",
            RpgMakerNameMatcher.match(realDir, "イースウェン\u3000街並み2  .png"),
        )
        assertEquals(
            "イースウェン 街並み3  .png",
            RpgMakerNameMatcher.match(realDir, "イースウェン\u3000街並み3  .png"),
        )
    }

    @Test
    fun exactMatchWinsOverLooseCandidates() {
        val dir = listOf("abc.png", "abc .png")
        assertEquals("abc.png", RpgMakerNameMatcher.match(dir, "abc.png"))
        assertEquals("abc .png", RpgMakerNameMatcher.match(dir, "abc .png"))
    }

    @Test
    fun caseInsensitiveMatchStillWorks() {
        assertEquals("Town.PNG", RpgMakerNameMatcher.match(listOf("Town.PNG"), "town.png"))
    }

    @Test
    fun ambiguousLooseMatchIsRejectedInsteadOfGuessing() {
        // 「ステ画面フキダシ .png」与「ステ画面フキダシ.png」在忽略尾随空白后同键，
        // 请求其中任一时必须拒绝匹配，否则会画错图
        val dir = listOf("ステ画面フキダシ .png", "ステ画面フキダシ.png")
        assertNull(RpgMakerNameMatcher.match(dir, "ステ画面フキダシ  .png"))
    }

    @Test
    fun differentTextIsNeverMatched() {
        // 昼/夜/朝 之类只有文字不同的文件不得互相匹配
        assertNull(RpgMakerNameMatcher.match(listOf("ホーム画面  昼  .png"), "ホーム画面\u3000朝   .png"))
        assertNull(RpgMakerNameMatcher.match(realDir, "存在しない画面.png"))
    }

    @Test
    fun keysNormalizeWhitespaceClassOnly() {
        assertEquals(
            RpgMakerNameMatcher.whitespaceClassKey("a\u3000b  c"),
            RpgMakerNameMatcher.whitespaceClassKey("a b  c"),
        )
        assertEquals("a.png", RpgMakerNameMatcher.trailingWhitespaceTrimmedKey("a  .png"))
        assertEquals("a.png", RpgMakerNameMatcher.trailingWhitespaceTrimmedKey("a.png"))
    }

    /**
     * 超过条目上限：只保留精确与大小写不敏感两级。
     *
     * 这条策略必须由服务器与 fs 桥共用同一实现——否则同一路径会出现
     * 「XHR 404、fs.existsSync 为真」的分裂（旧注释里禁止的形态）。
     */
    @Test
    fun oversizedDirectoryKeepsOnlyExactAndCaseInsensitive() {
        val dir = listOf("a.png", "B.PNG", "c .png")
        // 上限以内：空白容忍生效
        assertEquals("c .png", RpgMakerNameMatcher.match(dir, "c  .png", maxFuzzyEntries = 10))
        // 超过上限：空白容忍被关闭（精确/大小写不敏感仍可用）
        assertNull(RpgMakerNameMatcher.match(dir, "c  .png", maxFuzzyEntries = 2))
        assertEquals("a.png", RpgMakerNameMatcher.match(dir, "a.png", maxFuzzyEntries = 2))
        assertEquals("B.PNG", RpgMakerNameMatcher.match(dir, "b.png", maxFuzzyEntries = 2))
        // ≤ 0 表示不设上限（仅测试/特殊场景）
        assertEquals("c .png", RpgMakerNameMatcher.match(dir, "c  .png", maxFuzzyEntries = 0))
    }
}
