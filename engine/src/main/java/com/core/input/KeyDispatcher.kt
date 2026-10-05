package com.core.input

/**
 * 逻辑控件 → 多键输出的派发器（虚拟按键与手柄按钮共用）。
 *
 * 语义：
 *  - 普通按下：本控件第一次 press 时按键顺序派发 down，release 时逆序 up；
 *    重复 press（多指/键轴重复）不重复派发。
 *  - autoKeep（保持）：press 翻转——首次按下发出 down 并保持（不随触摸抬起释放），
 *    再次按下才发出 up；用于「跳过 / 加速」这类需要长按的按键。
 */
internal class KeyDispatcher(private val sink: InputSink) {

    private val active = LinkedHashMap<String, List<Int>>()
    private val toggled = HashSet<String>()

    fun press(id: String, keys: List<Int>, autoKeep: Boolean) {
        if (keys.isEmpty()) return
        if (autoKeep) {
            if (toggled.remove(id)) {
                active.remove(id)
                keys.asReversed().forEach { sink.send(it, false) }
            } else {
                toggled.add(id)
                active[id] = keys
                keys.forEach { sink.send(it, true) }
            }
            return
        }
        if (active.containsKey(id)) return
        active[id] = keys
        keys.forEach { sink.send(it, true) }
    }

    fun release(id: String, autoKeep: Boolean) {
        if (autoKeep) return
        val keys = active.remove(id) ?: return
        keys.asReversed().forEach { sink.send(it, false) }
    }

    fun isToggled(id: String): Boolean = toggled.contains(id)

    fun releaseAll() {
        active.entries.toList().asReversed().forEach { (_, keys) ->
            keys.asReversed().forEach { sink.send(it, false) }
        }
        active.clear()
        toggled.clear()
    }
}
