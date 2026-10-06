package com.core.input

/**
 * 逻辑控件 → 多键输出的派发器（虚拟按键与手柄按钮共用）。
 *
 * 语义：
 *  - 普通按下：本控件第一次 press 时按键顺序派发 down，release 时逆序 up；
 *    重复 press（多指/键轴重复）不重复派发。
 *  - autoKeep（保持）：press 翻转——首次按下发出 down 并保持（不随触摸抬起释放），
 *    再次按下才发出 up；用于「跳过 / 加速」这类需要长按的按键。
 *  - 动作（canonical 动作段，如截屏）：没有按下/抬起语义，只在 press 时经
 *    [InputSink.performAction] 触发一次，也不受 autoKeep 影响——因此「截屏按钮」
 *    可以与其他键位混在同一条映射里而互不干扰。
 */
internal class KeyDispatcher(private val sink: InputSink) {

    /** 控件 id → 当前按下的**普通键位**（动作不进入本表）。 */
    private val active = LinkedHashMap<String, List<Int>>()
    private val toggled = HashSet<String>()

    fun press(id: String, keys: List<Int>, autoKeep: Boolean) {
        if (keys.isEmpty()) return
        val actions = keys.filter { CanonicalKeys.isAction(it) }
        val plainKeys = keys.filterNot { CanonicalKeys.isAction(it) }

        if (autoKeep) {
            // 动作是一次性的：保持语义对它无意义，每次按下都触发
            actions.forEach { sink.performAction(it) }
            if (plainKeys.isEmpty()) return
            if (toggled.remove(id)) {
                active.remove(id)
                plainKeys.asReversed().forEach { sink.send(it, false) }
            } else {
                toggled.add(id)
                active[id] = plainKeys
                plainKeys.forEach { sink.send(it, true) }
            }
            return
        }

        if (active.containsKey(id)) return
        active[id] = plainKeys
        actions.forEach { sink.performAction(it) }
        plainKeys.forEach { sink.send(it, true) }
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
