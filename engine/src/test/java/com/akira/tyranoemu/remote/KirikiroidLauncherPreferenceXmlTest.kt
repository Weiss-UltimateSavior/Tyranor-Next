package com.akira.tyranoemu.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
