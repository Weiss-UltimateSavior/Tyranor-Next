package com.core.input

/**
 * 统一按键空间（canonical keys）：虚拟按键、手柄映射与后续输入源只产出本空间的键值，
 * 由各宿主 [InputSink] 转换到目标引擎的键位空间。
 *
 * 键盘段直接采用 `android.view.KeyEvent.KEYCODE_*` 整数值（Web 宿主经 [WebKeyCodes]
 * 转浏览器 keyCode；后续 KRKR/Siglus 等宿主同样以 Android keycode 为源做转换）；
 * 鼠标段与动作段为本项目自定义区间，不与 Android keycode 冲突。
 */
object CanonicalKeys {

    /** 鼠标键区间起点（含滚轮）。 */
    const val MOUSE_LEFT = 1000
    const val MOUSE_RIGHT = 1001
    const val MOUSE_MIDDLE = 1002
    const val SCROLL_UP = 1003
    const val SCROLL_DOWN = 1004
    const val MOUSE_END = 1099

    /** 功能动作区间起点（非按键，由宿主自行处理）。 */
    const val ACTION_BASE = 2000

    /** 显示 / 隐藏虚拟按键层。 */
    const val ACTION_TOGGLE_PAD = 2000

    fun isMouse(key: Int): Boolean = key in MOUSE_LEFT..MOUSE_END

    fun isAction(key: Int): Boolean = key >= ACTION_BASE

    fun isKeyboard(key: Int): Boolean = key in 1..254
}
