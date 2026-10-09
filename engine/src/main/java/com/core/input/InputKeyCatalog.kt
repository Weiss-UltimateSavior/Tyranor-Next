package com.core.input

import android.content.Context
import android.view.KeyEvent
import com.core.engine.R

/**
 * 键位选择目录（virtual pad 与手柄映射共用的 canonical 键位清单）。
 *
 * 字母/数字/功能键用通用 ASCII 名称直接展示（跨语言可读）；鼠标键等需要本地化的
 * 条目给字符串资源 id。
 */
object InputKeyCatalog {

    /** @property label ASCII 名称（非空直接展示）；否则用 [labelRes]。 */
    class Entry(val code: Int, val label: String?, val labelRes: Int = 0)

    class Group(val titleRes: Int, val keys: List<Entry>)

    fun groups(): List<Group> = listOf(
        Group(
            R.string.engine_input_group_letters,
            ('A'..'Z').map { ch -> Entry(KeyEvent.KEYCODE_A + (ch - 'A'), ch.toString(), 0) },
        ),
        Group(
            R.string.engine_input_group_digits,
            ('0'..'9').map { ch -> Entry(KeyEvent.KEYCODE_0 + (ch - '0'), ch.toString(), 0) },
        ),
        Group(
            R.string.engine_input_group_direction,
            listOf(
                Entry(KeyEvent.KEYCODE_DPAD_UP, "↑", 0),
                Entry(KeyEvent.KEYCODE_DPAD_DOWN, "↓", 0),
                Entry(KeyEvent.KEYCODE_DPAD_LEFT, "←", 0),
                Entry(KeyEvent.KEYCODE_DPAD_RIGHT, "→", 0),
            ),
        ),
        Group(
            R.string.engine_input_group_function,
            listOf(
                Entry(KeyEvent.KEYCODE_ENTER, "Enter", 0),
                Entry(KeyEvent.KEYCODE_SPACE, "Space", 0),
                Entry(KeyEvent.KEYCODE_ESCAPE, "Esc", 0),
                Entry(KeyEvent.KEYCODE_TAB, "Tab", 0),
                Entry(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", 0),
                Entry(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", 0),
                Entry(KeyEvent.KEYCODE_ALT_LEFT, "Alt", 0),
                Entry(KeyEvent.KEYCODE_PAGE_UP, "PageUp", 0),
                Entry(KeyEvent.KEYCODE_PAGE_DOWN, "PageDown", 0),
                Entry(KeyEvent.KEYCODE_MOVE_HOME, "Home", 0),
                Entry(KeyEvent.KEYCODE_MOVE_END, "End", 0),
                Entry(KeyEvent.KEYCODE_DEL, "Backspace", 0),
                Entry(KeyEvent.KEYCODE_FORWARD_DEL, "Delete", 0),
            ) + (1..12).map { i ->
                Entry(KeyEvent.KEYCODE_F1 + (i - 1), "F$i", 0)
            },
        ),
        Group(
            R.string.engine_input_group_mouse,
            listOf(
                Entry(CanonicalKeys.MOUSE_LEFT, null, R.string.engine_input_mouse_left),
                Entry(CanonicalKeys.MOUSE_RIGHT, null, R.string.engine_input_mouse_right),
                Entry(CanonicalKeys.MOUSE_MIDDLE, null, R.string.engine_input_mouse_middle),
                Entry(CanonicalKeys.SCROLL_UP, null, R.string.engine_input_scroll_up),
                Entry(CanonicalKeys.SCROLL_DOWN, null, R.string.engine_input_scroll_down),
            ),
        ),
        // 动作段：不是按键，触发宿主侧一次性功能（截屏等）
        Group(
            R.string.engine_input_group_actions,
            listOf(
                Entry(CanonicalKeys.ACTION_SCREENSHOT, null, R.string.engine_input_action_screenshot),
            ),
        ),
    )

    /** 已选键位的可读名称（优先 ASCII 名，鼠标走资源；未知给 ""）。 */
    fun label(context: Context, code: Int): String {
        for (group in groups()) {
            val entry = group.keys.firstOrNull { it.code == code } ?: continue
            return entry.label ?: context.getString(entry.labelRes)
        }
        return ""
    }
}
