package com.core.rpgmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 404 修复的端到端验证：用真实游戏（ホムンクルスの研究記録）pictures 文件夹
 * 的真实文件名快照，验证服务器回退链能解析出游戏请求的名字。
 *
 * 这些用例直接对应日志里那 204 条 404：
 *   img/pictures/ホーム画面　朝   .png   （U+3000，来自游戏 data）
 *   img/pictures/イースウェン　街並み  .png 等
 * 而磁盘上是同名的 U+0020 版本。
 */
class RpgMakerHttpServerFallbackTest {

    /** 取自用户导出的 pictures 目录（含相关邻居文件，用于验证歧义拒绝）。 */
    private val diskNames = listOf(
        "ホーム画面 朝   .png",
        "ホーム画面  昼  .png",
        "ホーム画面  夜  .png",
        "イースウェン 街並み  .png",
        "イースウェン 街並み  夕方 .png",
        "イースウェン 街並み  夜 .png",
        "イースウェン 街並み2  .png",
        "イースウェン 街並み3  .png",
        "ステ画面フキダシ .png",
        "ステ画面フキダシ.png",
        "GAME OVER   .png",
    )

    @Test
    fun allFourLoggedFourZeroFoursResolveWithWhitespaceTolerance() {
        val requested = listOf(
            "ホーム画面\u3000朝   .png",
            "イースウェン\u3000街並み  .png",
            "イースウェン\u3000街並み2  .png",
            "イースウェン\u3000街並み3  .png",
        )
        val expected = listOf(
            "ホーム画面 朝   .png",
            "イースウェン 街並み  .png",
            "イースウェン 街並み2  .png",
            "イースウェン 街並み3  .png",
        )
        requested.forEachIndexed { index, name ->
            assertEquals(expected[index], RpgMakerNameMatcher.match(diskNames, name))
        }
    }

    @Test
    fun ambiguousPairIsNotResolvedByTrailingWhitespaceRule() {
        // 「ステ画面フキダシ .png」与「ステ画面フキダシ.png」仅差尾随空格：
        // 请求第三种空格数量时必须拒绝，不能画错图
        assertNull(RpgMakerNameMatcher.match(diskNames, "ステ画面フキダシ  .png"))
    }

    @Test
    fun neighboursWithDifferentWordsAreNeverMatched() {
        assertNull(RpgMakerNameMatcher.match(diskNames, "ホーム画面\u3000夕方  .png"))
        assertNull(RpgMakerNameMatcher.match(diskNames, "イースウェン\u3000街並み4  .png"))
    }

    @Test
    fun asciiNamesAreUntouched() {
        assertEquals("GAME OVER   .png", RpgMakerNameMatcher.match(diskNames, "GAME OVER   .png"))
        assertNull(RpgMakerNameMatcher.match(diskNames, "GAME  OVER   .png"))
    }
}
