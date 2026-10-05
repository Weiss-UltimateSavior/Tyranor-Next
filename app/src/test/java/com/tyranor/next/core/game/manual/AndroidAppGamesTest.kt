package com.tyranor.next.core.game.manual

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手动添加的安卓游戏：uri 唯一键、包名承载（launchTarget）与记录构造。 */
class AndroidAppGamesTest {

    @Test
    fun uriForUsesPackageScheme() {
        assertEquals("androidapp://com.example.game", AndroidAppGames.uriFor("com.example.game"))
    }

    @Test
    fun toScanGameCarriesPackageInLaunchTarget() {
        val game = AndroidAppGames.toScanGame("示例游戏", "com.example.game")

        assertEquals("示例游戏", game.title)
        assertEquals("androidapp://com.example.game", game.uri)
        assertEquals(EngineType.ANDROID_APP, game.engine)
        assertEquals("com.example.game", game.launchTarget)
        assertEquals("com.example.game", AndroidAppGames.packageNameOf(game))
        // 不变量：包名同时存在于 uri 与 launchTarget，二者必须一致
        assertEquals(game.launchTarget, game.uri.removePrefix(AndroidAppGames.URI_PREFIX))
    }

    @Test
    fun packageNameOfTrimsAndFallsBackToUri() {
        val base = AndroidAppGames.toScanGame("示例游戏", "com.example.game")

        assertEquals("com.example.game", AndroidAppGames.packageNameOf(base.copy(launchTarget = "  com.example.game  ")))
        assertEquals("com.example.game", AndroidAppGames.packageNameOf(base.copy(launchTarget = " ")))
        assertEquals(null, AndroidAppGames.packageNameOf(base.copy(uri = "content://tree/game", launchTarget = "")))
    }

    @Test
    fun isAndroidAppRecognizesEngineAndUriPrefix() {
        val game = AndroidAppGames.toScanGame("示例游戏", "com.example.game")

        assertTrue(AndroidAppGames.isAndroidApp(game))
        // engine 字段损坏回退 UNKNOWN 时仍按 uri 前缀识别
        assertTrue(AndroidAppGames.isAndroidApp(game.copy(engine = EngineType.UNKNOWN)))
        assertFalse(AndroidAppGames.isAndroidApp(game.copy(engine = EngineType.UNKNOWN, uri = "content://tree/game")))
        assertFalse(AndroidAppGames.isAndroidApp(game.copy(engine = EngineType.PC, uri = "content://tree/pc")))
    }
}
