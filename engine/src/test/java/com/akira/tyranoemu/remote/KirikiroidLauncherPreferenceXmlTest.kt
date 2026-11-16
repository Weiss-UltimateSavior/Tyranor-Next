package com.akira.tyranoemu.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 游戏目录引擎偏好 XML 的所有权语义回归（PR #155 审查）：
 * 键名改为内核真名后，游戏目录里可能出现用户在内核设置界面写入的同名 Item（无
 * `_rinne_injected_*` 标记）。global scope 启动的陈旧项清理必须只回收宿主注入的值，
 * 不得清除内核侧写入的单游戏偏好。
 *
 * PR #183 追加：fontWriteTarget 作用域落点矩阵（二轮低③）、写入四态 outcome 可辨（低④）、
 * 全局参照收敛回收三态（二轮中①：空参照移除/非空参照收敛改写/无标记不触碰/防御 disown）。
 */
class KirikiroidLauncherPreferenceXmlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val marker = "_rinne_injected_renderer"

    private fun xml(vararg items: Pair<String, String>): String =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><GlobalPreference>" +
            items.joinToString("") { (k, v) -> "<Item key=\"$k\" value=\"$v\" />" } +
            "</GlobalPreference>"

    private fun items(file: File): Map<String, String> =
        Regex("Item key=\"([^\"]*)\" value=\"([^\"]*)\"").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun gameFileWith(vararg items: Pair<String, String>): File =
        tmp.newFile("game.xml").apply { writeText(xml(*items)) }

    private fun globalFile(): File = tmp.newFile("global.xml").apply { writeText(xml()) }

    private fun globalFileWith(vararg items: Pair<String, String>): File =
        globalFileWith("global.xml", *items)

    private fun globalFileWith(name: String, vararg items: Pair<String, String>): File =
        tmp.newFile(name).apply { writeText(xml(*items)) }

    @Test
    fun `kernel-written item without marker survives stale clear`() {
        val game = gameFileWith("renderer" to "software", "fps_limit" to "30")
        KirikiroidLauncherBaseActivity.clearStalePreferenceItem(globalFile(), game, "renderer", marker)
        val after = items(game)
        assertEquals("software", after["renderer"])
        assertEquals("30", after["fps_limit"])
        assertFalse(after.containsKey(marker))
    }

    @Test
    fun `host-injected item still at injected value is removed by stale clear`() {
        val game = gameFileWith(
            "renderer" to "opengl",
            marker to "opengl",
            "force_default_font" to "1",
        )
        KirikiroidLauncherBaseActivity.clearStalePreferenceItem(globalFile(), game, "renderer", marker)
        val after = items(game)
        assertFalse(after.containsKey("renderer"))
        assertFalse(after.containsKey(marker))
        assertEquals("1", after["force_default_font"])
    }

    @Test
    fun `item changed by kernel ui after injection is disowned not removed`() {
        val game = gameFileWith("renderer" to "software", marker to "opengl")
        KirikiroidLauncherBaseActivity.clearStalePreferenceItem(globalFile(), game, "renderer", marker)
        val after = items(game)
        assertEquals("software", after["renderer"])
        assertFalse(after.containsKey(marker))
    }

    @Test
    fun `applyPreferenceItem writes value and marker then unsets on ownership match`() {
        val game = tmp.newFile("game.xml").apply { writeText(xml()) }
        KirikiroidLauncherBaseActivity.applyPreferenceItem(game, "renderer", marker, "opengl", true)
        var after = items(game)
        assertEquals("opengl", after["renderer"])
        assertEquals("opengl", after[marker])
        KirikiroidLauncherBaseActivity.applyPreferenceItem(game, "renderer", marker, null, true)
        after = items(game)
        assertFalse(after.containsKey("renderer"))
        assertFalse(after.containsKey(marker))
    }

    @Test
    fun `legacy app-written font item is migrated by prefix`() {
        val game = tmp.newFile("game.xml").apply {
            writeText(xml("default_font" to "/data/user/0/com.tyranor.next/files/fonts/old.ttf"))
        }
        KirikiroidLauncherBaseActivity.clearLegacyAppPrivateFontItem(
            game, "default_font", "_rinne_injected_default_font",
            "/data/user/0/com.tyranor.next/files/fonts",
        )
        assertFalse(items(game).containsKey("default_font"))
    }

    @Test
    fun `kernel-ui font item outside app fonts dir is kept`() {
        val game = tmp.newFile("game.xml").apply {
            writeText(xml("default_font" to "/storage/emulated/0/Fonts/picked.ttf"))
        }
        KirikiroidLauncherBaseActivity.clearLegacyAppPrivateFontItem(
            game, "default_font", "_rinne_injected_default_font",
            "/data/user/0/com.tyranor.next/files/fonts",
        )
        assertEquals("/storage/emulated/0/Fonts/picked.ttf", items(game)["default_font"])
    }

    @Test
    fun `marked font item is not touched by legacy migration`() {
        val game = tmp.newFile("game.xml").apply {
            writeText(xml(
                "default_font" to "/data/user/0/com.tyranor.next/files/fonts/new.ttf",
                "_rinne_injected_default_font" to "/data/user/0/com.tyranor.next/files/fonts/new.ttf",
            ))
        }
        KirikiroidLauncherBaseActivity.clearLegacyAppPrivateFontItem(
            game, "default_font", "_rinne_injected_default_font",
            "/data/user/0/com.tyranor.next/files/fonts",
        )
        assertEquals("/data/user/0/com.tyranor.next/files/fonts/new.ttf", items(game)["default_font"])
        assertTrue(items(game).containsKey("_rinne_injected_default_font"))
    }

    @Test
    fun `missing preference file is created on write`() {
        val dir = tmp.newFolder("pregame")
        val game = File(dir, "Kirikiroid2Preference.xml")
        val changed = KirikiroidLauncherBaseActivity.applyPreferenceItem(
            game, "renderer", "_rinne_injected_renderer", "software", true,
        )
        assertTrue(changed)
        assertTrue(game.isFile)
        val after = items(game)
        assertEquals("software", after["renderer"])
        assertEquals("software", after["_rinne_injected_renderer"])
    }

    @Test
    fun `unset on missing file is a no-op without creating it`() {
        val dir = tmp.newFolder("pregame2")
        val game = File(dir, "Kirikiroid2Preference.xml")
        val changed = KirikiroidLauncherBaseActivity.applyPreferenceItem(
            game, "renderer", "_rinne_injected_renderer", null, true,
        )
        assertFalse(changed)
        assertFalse(game.exists())
    }

    @Test
    fun `legacy font migration no-ops on missing file`() {
        val dir = tmp.newFolder("pregame3")
        val game = File(dir, "Kirikiroid2Preference.xml")
        KirikiroidLauncherBaseActivity.clearLegacyAppPrivateFontItem(
            game, "default_font", "_rinne_injected_default_font",
            "/data/user/0/com.tyranor.next/files/fonts",
        )
        assertFalse(game.exists())
    }

    @Test
    fun `directory at preference path is not treated as empty config`() {
        val dir = tmp.newFolder("pregame4")
        // 路径存在但是目录：不能按缺失（空配置）写回——writePreferenceXml 的
        // 替换重试会删除路径上的空目录，再改名把临时文件顶替进去。
        val game = File(dir, "Kirikiroid2Preference.xml").apply { mkdirs() }
        val changed = KirikiroidLauncherBaseActivity.applyPreferenceItem(
            game, "renderer", "_rinne_injected_renderer", "software", true,
        )
        assertFalse(changed)
        assertTrue(game.isDirectory)
        assertEquals(listOf("Kirikiroid2Preference.xml"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun `font item value maps empty to unset only in global scope`() {
        // "" 在全局作用域=所有权清除（issue #74 契约）；在游戏作用域=写空 Item（内置字体）
        org.junit.Assert.assertNull(KirikiroidLauncherBaseActivity.fontItemValue("", false))
        assertEquals("", KirikiroidLauncherBaseActivity.fontItemValue("", true))
        assertEquals("/f.ttf", KirikiroidLauncherBaseActivity.fontItemValue("/f.ttf", true))
        assertEquals("/f.ttf", KirikiroidLauncherBaseActivity.fontItemValue("/f.ttf", false))
    }

    @Test
    fun `font write target skips when the scope-required file is unavailable`() {
        // game scope 请求 + 游戏目录不可用：落点为 null=跳过写入，不能降级写全局
        // （否则重新泄漏或误清全局字体）；global scope 只需要全局文件——两个作用域
        // 各自需要的文件缺失互不阻断（#183 二轮审查低③）
        val global = tmp.newFile("global.xml")
        val game = tmp.newFile("game.xml")
        assertNull(KirikiroidLauncherBaseActivity.fontWriteTarget(true, null, global))
        assertNull(KirikiroidLauncherBaseActivity.fontWriteTarget(false, null, null))
        assertNull(KirikiroidLauncherBaseActivity.fontWriteTarget(true, null, null))
        assertSame(game, KirikiroidLauncherBaseActivity.fontWriteTarget(true, game, null))
        assertSame(global, KirikiroidLauncherBaseActivity.fontWriteTarget(false, null, global))
        assertSame(game, KirikiroidLauncherBaseActivity.fontWriteTarget(true, game, global))
        // game scope 落点写入不得触碰全局文件（组合性质锚定：全局文件仅由 global 落点/回收路径改写）
        val globalBefore = global.readText()
        KirikiroidLauncherBaseActivity.applyPreferenceItemDetailed(game, "default_font", "_rinne_injected_default_font", "/f.ttf")
        assertEquals(globalBefore, global.readText())
    }

    @Test
    fun `stale global injected font is recycled when it disagrees with app reference`() {
        // 泄漏场景：旧版把游戏覆盖 X 泄漏进全局（带标记），App 参照=未设全局（""）→ 空参照
        // 收敛=移除（与所有权 unset 同语义）
        val global = globalFileWith(
            "default_font" to "/f.ttf", "_rinne_injected_default_font" to "/f.ttf",
        )
        val outcome = KirikiroidLauncherBaseActivity.clearStaleGlobalFontItem(
            global, "default_font", "_rinne_injected_default_font", "",
        )
        assertEquals(KirikiroidLauncherBaseActivity.PREF_WRITE_APPLIED, outcome)
        val after = items(global)
        assertFalse(after.containsKey("default_font"))
        assertFalse(after.containsKey("_rinne_injected_default_font"))
    }

    @Test
    fun `stale global injected font converges to app reference instead of being deleted`() {
        // 收敛写：泄漏 X + App 全局参照 B → 全局项改写为 B（marker 同步），其他游戏立即
        // 跟随 App 全局值，而不是回落内置字体等下一次跟随全局启动（多角色自审 P2-1）
        val global = globalFileWith(
            "default_font" to "/f.ttf", "_rinne_injected_default_font" to "/f.ttf",
        )
        val outcome = KirikiroidLauncherBaseActivity.clearStaleGlobalFontItem(
            global, "default_font", "_rinne_injected_default_font", "/b.ttf",
        )
        assertEquals(KirikiroidLauncherBaseActivity.PREF_WRITE_APPLIED, outcome)
        val after = items(global)
        assertEquals("/b.ttf", after["default_font"])
        assertEquals("/b.ttf", after["_rinne_injected_default_font"])
    }

    @Test
    fun `global recycle is a no-op sentinel when marker matches reference or is absent`() {
        // FONT_RECYCLE_NONE：marker==参照（合法全局=覆盖同值）与无标记项均无回收动作
        val matched = globalFileWith(
            "global-a.xml",
            "default_font" to "/f.ttf", "_rinne_injected_default_font" to "/f.ttf",
        )
        assertEquals(
            KirikiroidLauncherBaseActivity.FONT_RECYCLE_NONE,
            KirikiroidLauncherBaseActivity.clearStaleGlobalFontItem(matched, "default_font", "_rinne_injected_default_font", "/f.ttf"),
        )
        val unmarked = globalFileWith(
            "global-b.xml",
            "default_font" to "/legacy.ttf",
        )
        assertEquals(
            KirikiroidLauncherBaseActivity.FONT_RECYCLE_NONE,
            KirikiroidLauncherBaseActivity.clearStaleGlobalFontItem(unmarked, "default_font", "_rinne_injected_default_font", ""),
        )
    }

    @Test
    fun `rewritten global entry is disowned defensively not converged`() {
        // 防御路径（单写者前提被破坏）：current≠marker → 仅去标记保留值，不收敛覆盖
        val global = globalFileWith(
            "default_font" to "/user.ttf", "_rinne_injected_default_font" to "/f.ttf",
        )
        val outcome = KirikiroidLauncherBaseActivity.clearStaleGlobalFontItem(
            global, "default_font", "_rinne_injected_default_font", "/b.ttf",
        )
        assertEquals(KirikiroidLauncherBaseActivity.PREF_WRITE_APPLIED, outcome)
        val after = items(global)
        assertEquals("/user.ttf", after["default_font"])
        assertFalse(after.containsKey("_rinne_injected_default_font"))
    }

    @Test
    fun `global injected font matching app reference survives game scope launch`() {
        // 碰撞配置：用户全局字体恰与单游戏覆盖同值 → 参照一致 → 保留（不得误删全局字体）
        val global = globalFileWith(
            "default_font" to "/f.ttf", "_rinne_injected_default_font" to "/f.ttf",
        )
        KirikiroidLauncherBaseActivity.clearStaleGlobalFontItem(global, "default_font", "_rinne_injected_default_font", "/f.ttf")
        val after = items(global)
        assertEquals("/f.ttf", after["default_font"])
        assertEquals("/f.ttf", after["_rinne_injected_default_font"])
    }

    @Test
    fun `unmarked global font entry is never recycled by reference check`() {
        // 无标记旧项（pre-marker 构建）：无所有权信号，参照判据不触碰，按文档需手动清理
        val global = globalFileWith("default_font" to "/legacy.ttf")
        KirikiroidLauncherBaseActivity.clearStaleGlobalFontItem(global, "default_font", "_rinne_injected_default_font", "")
        assertEquals("/legacy.ttf", items(global)["default_font"])
    }

    @Test
    fun `write outcome distinguishes unchanged from skipped write`() {
        // 写回被跳过（截断/非普通文件）必须与「值未变」可区分，排障时不再误读
        // （#183 二轮审查低④）
        val written = gameFileWith("fps_limit" to "30")
        assertEquals(
            KirikiroidLauncherBaseActivity.PREF_WRITE_APPLIED,
            KirikiroidLauncherBaseActivity.applyPreferenceItemDetailed(written, "renderer", marker, "opengl", true),
        )
        assertEquals(
            KirikiroidLauncherBaseActivity.PREF_WRITE_UNCHANGED,
            KirikiroidLauncherBaseActivity.applyPreferenceItemDetailed(written, "renderer", marker, "opengl", true),
        )
        val truncated = tmp.newFile("truncated.xml").apply {
            // 与既有截断夹具同型：末尾 Item 无闭合标签与文档结尾（自闭合 Item 后截断会被
            // 解析器当作正常文档结尾，不触发 INCOMPLETE）
            writeText(xml("fps_limit" to "30") + "<Item key=\"renderer\" value=\"op")
        }
        assertEquals(
            KirikiroidLauncherBaseActivity.PREF_WRITE_SKIPPED_PARSE_INCOMPLETE,
            KirikiroidLauncherBaseActivity.applyPreferenceItemDetailed(truncated, "renderer", marker, "opengl", true),
        )
        assertFalse(items(truncated).containsKey("renderer"))
        val dir = tmp.newFolder("dir.xml")
        assertEquals(
            KirikiroidLauncherBaseActivity.PREF_WRITE_SKIPPED_NOT_FILE,
            KirikiroidLauncherBaseActivity.applyPreferenceItemDetailed(dir, "renderer", marker, "opengl", true),
        )
        assertTrue(
            KirikiroidLauncherBaseActivity.prefWriteOutcomeSuffix(
                KirikiroidLauncherBaseActivity.PREF_WRITE_SKIPPED_PARSE_INCOMPLETE,
            ).contains("skipped"),
        )
        assertEquals("", KirikiroidLauncherBaseActivity.prefWriteOutcomeSuffix(
            KirikiroidLauncherBaseActivity.PREF_WRITE_APPLIED),
        )
    }

    @Test
    fun `game scoped builtin font item is cleaned when override returns to global`() {
        val global = globalFile()
        val game = tmp.newFile("game.xml").apply { writeText(xml()) }
        KirikiroidLauncherBaseActivity.applyPreferenceItem(
            game, "default_font", "_rinne_injected_default_font", "", true,
        )
        assertEquals("", items(game)["default_font"])
        KirikiroidLauncherBaseActivity.clearStalePreferenceItem(
            global, game, "default_font", "_rinne_injected_default_font",
        )
        assertFalse(items(game).containsKey("default_font"))
        assertFalse(items(game).containsKey("_rinne_injected_default_font"))
    }

    @Test
    fun `value equal to directory prefix is treated as app written`() {
        val game = tmp.newFile("game.xml").apply {
            writeText(xml(*arrayOf("default_font" to "/data/user/0/com.tyranor.next/files/fonts")))
        }
        KirikiroidLauncherBaseActivity.clearLegacyAppPrivateFontItem(
            game, "default_font", "_rinne_injected_default_font",
            "/data/user/0/com.tyranor.next/files/fonts",
        )
        assertFalse(items(game).containsKey("default_font"))
    }

    @Test
    fun `similar directory fonts2 is not matched by fonts prefix`() {
        val game = tmp.newFile("game.xml").apply {
            writeText(xml("default_font" to "/data/user/0/com.tyranor.next/files/fonts2/picked.ttf"))
        }
        KirikiroidLauncherBaseActivity.clearLegacyAppPrivateFontItem(
            game, "default_font", "_rinne_injected_default_font",
            "/data/user/0/com.tyranor.next/files/fonts",
        )
        assertEquals("/data/user/0/com.tyranor.next/files/fonts2/picked.ttf", items(game)["default_font"])
    }

    @Test
    fun `truncated xml is left untouched by writes`() {
        val game = tmp.newFile("game.xml").apply {
            // 截断的偏好文件：末尾 Item 无闭合标签与文档结尾
            writeText(
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><GlobalPreference>" +
                                        "<Item key=\"keep_me\" value=\"1\" /><Item key=\"renderer\" value=\"op",
            )
        }
        val before = game.readText()
        KirikiroidLauncherBaseActivity.applyPreferenceItem(
            game, "renderer", "_rinne_injected_renderer", "software", true,
        )
        KirikiroidLauncherBaseActivity.clearLegacyAppPrivateFontItem(
            game, "default_font", "_rinne_injected_default_font",
            "/data/user/0/com.tyranor.next/files/fonts",
        )
        assertEquals(before, game.readText())
    }

    @Test
    fun `round trip preserves custom and keymap entries`() {
        val game = tmp.newFile("game.xml").apply {
            writeText(
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><GlobalPreference>" +
                    "<Item key=\"renderer\" value=\"opengl\" />" +
                    "<Item key=\"_rinne_injected_renderer\" value=\"opengl\" />" +
                    "<Custom key=\"-fullscr\" value=\"1\" />" +
                    "<KeyMap key=\"82\" value=\"49\" />" +
                    "</GlobalPreference>",
            )
        }
        KirikiroidLauncherBaseActivity.clearStalePreferenceItem(globalFile(), game, "renderer", marker)
        val text = game.readText()
        assertTrue(text.contains("Custom key=\"-fullscr\""))
        assertTrue(text.contains("KeyMap key=\"82\""))
        assertFalse(items(game).containsKey("renderer"))
    }
}
