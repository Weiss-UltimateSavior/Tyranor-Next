package com.tyranor.next.core.game.launch

import com.core.engine.LaunchContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字体 extras 装配回归：值来自 ResolvedEngineSettings，scope extra 仅在游戏作用域出现
 * （缺省=引擎按全局落点）；FORCE_DEFAULT_FONT 必须是 Boolean——字符串会被
 * 引擎侧 getBooleanExtra 静默读成默认 false；两个全局参照 extras（FONT_GLOBAL_*）
 * 恒下发，是引擎侧升级自愈回收的 hasExtra 门控判据。
 */
class EngineLauncherFontScopeTest {

    private fun extras(
        defaultFont: String = "",
        defaultFontGameScope: Boolean = false,
        forceFont: Boolean = false,
        forceFontGameScope: Boolean = false,
        globalDefaultFont: String = "",
        globalForceFont: Boolean = false,
    ): Map<String, Any> =
        krFontLaunchExtras(defaultFont, defaultFontGameScope, forceFont, forceFontGameScope,
            globalDefaultFont, globalForceFont).toMap()

    @Test
    fun `follow global sends values without scope extras`() {
        val map = extras()
        assertEquals("", map[LaunchContract.DEFAULT_FONT])
        assertEquals(false, map[LaunchContract.FORCE_DEFAULT_FONT])
        assertFalse(map.containsKey(LaunchContract.FONT_SCOPE_DEFAULT))
        assertFalse(map.containsKey(LaunchContract.FONT_SCOPE_FORCE))
    }

    @Test
    fun `game scoped values attach scope extras`() {
        val map = extras("/f.ttf", true, true, true)
        assertEquals("/f.ttf", map[LaunchContract.DEFAULT_FONT])
        assertEquals(true, map[LaunchContract.FORCE_DEFAULT_FONT])
        assertEquals(LaunchContract.SCOPE_GAME, map[LaunchContract.FONT_SCOPE_DEFAULT])
        assertEquals(LaunchContract.SCOPE_GAME, map[LaunchContract.FONT_SCOPE_FORCE])
    }

    @Test
    fun `empty override marks game scope builtin with boolean force`() {
        val map = extras("", true, true, false)
        assertEquals("", map[LaunchContract.DEFAULT_FONT])
        assertEquals(LaunchContract.SCOPE_GAME, map[LaunchContract.FONT_SCOPE_DEFAULT])
        assertFalse(map.containsKey(LaunchContract.FONT_SCOPE_FORCE))
        assertTrue(map[LaunchContract.FORCE_DEFAULT_FONT] is Boolean)
    }

    @Test
    fun `force value is always a boolean extra`() {
        assertTrue(extras(forceFont = false).getValue(LaunchContract.FORCE_DEFAULT_FONT) is Boolean)
        assertTrue(extras(forceFont = true).getValue(LaunchContract.FORCE_DEFAULT_FONT) is Boolean)
    }

    @Test
    fun `force off with game scope keeps boolean type and scope extra`() {
        // force=false + game scope 组合（#183 二轮审查低⑥）：引擎侧按 "0" 写游戏目录，
        // 可覆盖全局 force=1；extra 值必须保持 Boolean 而非字符串化
        val map = extras("/f.ttf", true, false, true)
        assertEquals(false, map[LaunchContract.FORCE_DEFAULT_FONT])
        assertTrue(map[LaunchContract.FORCE_DEFAULT_FONT] is Boolean)
        assertEquals(LaunchContract.SCOPE_GAME, map[LaunchContract.FONT_SCOPE_FORCE])
    }

    @Test
    fun `per key scopes are independent`() {
        // 默认字体跟随全局 + 强制开 game scope 的非对称组合：只有 FORCE 带 scope extra
        val map = extras("", false, true, true)
        assertFalse(map.containsKey(LaunchContract.FONT_SCOPE_DEFAULT))
        assertEquals(LaunchContract.SCOPE_GAME, map[LaunchContract.FONT_SCOPE_FORCE])
        assertEquals(true, map[LaunchContract.FORCE_DEFAULT_FONT])
    }

    @Test
    fun `global reference extras are always attached`() {
        // 全局参照恒下发（引擎回收全局陈旧带标记注入项的判据）；force 参照保持 Boolean
        val map = extras(globalDefaultFont = "/g.ttf", globalForceFont = true)
        assertEquals("/g.ttf", map[LaunchContract.FONT_GLOBAL_DEFAULT])
        assertTrue(map[LaunchContract.FONT_GLOBAL_FORCE] is Boolean)
        assertEquals(true, map[LaunchContract.FONT_GLOBAL_FORCE])
    }
}
