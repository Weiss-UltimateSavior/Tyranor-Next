package com.core.input

/**
 * Web 引擎（Tyrano / MV / MZ / VN / WebOther）按键出口。
 *
 * 合成事件由页面内注入的 `__tyranorInput`（assets/__tyranor_input.js）组装：
 * 键盘键派发带 keyCode 的 keydown/keyup（MV/MZ 的 Input 与 Tyrano 的 KAG 均读 keyCode）；
 * 鼠标键为「按下一刻触发」的点击语义（与 `__tnMouse` 已验证的手势一致，作用于视口中心）。
 *
 * 动作段（截屏等）不进入页面，交由宿主提供的 [onAction] 处理。
 */
class WebInputSink(
    private val dispatchJs: (String) -> Unit,
    private val onAction: (Int) -> Boolean = { false },
) : InputSink {

    private val held = LinkedHashSet<Int>()

    override fun send(key: Int, down: Boolean) {
        if (CanonicalKeys.isAction(key)) return
        if (CanonicalKeys.isMouse(key)) {
            // 鼠标键为一次性动作：按下时触发点击/右键/滚轮，抬起忽略。
            if (!down) {
                held.remove(key)
                return
            }
            held.add(key)
            dispatchJs("window.__tyranorInput&&window.__tyranorInput.mouse($key,1)")
            return
        }
        val js = WebKeyCodes.toJs(key)
        if (js == 0) return
        if (down) held.add(key) else held.remove(key)
        dispatchJs("window.__tyranorInput&&window.__tyranorInput.key($js,${if (down) 1 else 0})")
    }

    override fun performAction(action: Int): Boolean = onAction(action)

    override fun releaseAll() {
        held.toList().forEach { key ->
            if (!CanonicalKeys.isMouse(key) && !CanonicalKeys.isAction(key)) {
                val js = WebKeyCodes.toJs(key)
                if (js != 0) dispatchJs("window.__tyranorInput&&window.__tyranorInput.key($js,0)")
            }
        }
        held.clear()
        dispatchJs("window.__tyranorInput&&window.__tyranorInput.cancel()")
    }
}
