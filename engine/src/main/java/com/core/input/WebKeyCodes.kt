package com.core.input

import android.view.KeyEvent

/**
 * Android keycode → 浏览器 `KeyboardEvent.keyCode` 映射。
 *
 * MV/MZ 的 `Input` 与 Tyrano 的 KAG 均读 `event.keyCode`（原 `__touch_pad.js` 已验证的
 * 事件模型），合成事件在 JS 侧组装；本表为纯函数，便于单测锚定。
 */
object WebKeyCodes {

    /** 返回对应 JS keyCode；不支持返回 0。 */
    fun toJs(key: Int): Int = when (key) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> key - KeyEvent.KEYCODE_A + 65
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> key - KeyEvent.KEYCODE_0 + 48
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> key - KeyEvent.KEYCODE_F1 + 112
        KeyEvent.KEYCODE_DPAD_UP -> 38
        KeyEvent.KEYCODE_DPAD_DOWN -> 40
        KeyEvent.KEYCODE_DPAD_LEFT -> 37
        KeyEvent.KEYCODE_DPAD_RIGHT -> 39
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
        KeyEvent.KEYCODE_SPACE -> 32
        KeyEvent.KEYCODE_ESCAPE -> 27
        KeyEvent.KEYCODE_TAB -> 9
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 16
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 17
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 18
        KeyEvent.KEYCODE_PAGE_UP -> 33
        KeyEvent.KEYCODE_PAGE_DOWN -> 34
        KeyEvent.KEYCODE_MOVE_HOME -> 36
        KeyEvent.KEYCODE_MOVE_END -> 35
        KeyEvent.KEYCODE_DEL -> 8
        KeyEvent.KEYCODE_FORWARD_DEL -> 46
        else -> 0
    }

    fun isSupported(key: Int): Boolean = toJs(key) != 0
}
