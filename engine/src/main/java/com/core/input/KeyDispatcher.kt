package com.core.input

/**
 * 逻辑控件 → 多键输出的派发器（虚拟按键与手柄按钮共用**同一个实例**）。
 *
 * 语义：
 *  - 普通按下：本控件第一次 press 时按键顺序派发 down，release 时逆序 up；
 *    重复 press（多指/键轴重复）不重复派发。
 *  - autoKeep（保持）：press 翻转——首次按下发出 down 并保持（不随触摸抬起释放），
 *    再次按下才发出 up；用于「跳过 / 加速」这类需要长按的按键。
 *  - 动作（canonical 动作段，如截屏）：没有按下/抬起语义，只在 press 时经
 *    [InputSink.performAction] 触发一次，也不受 autoKeep 影响。
 *  - **键级引用计数**：多个控件可映射到同一键（默认布局里虚拟按键 OK 与手柄 A
 *    都是 Enter+Space）。按 id 记录持有者，只有最后一个持有者释放时才真正发 keyup，
 *    否则「按住屏幕 OK 再碰一下手柄 A」会让屏幕上的长按被提前打断。
 *
 * 因此虚拟按键层与手柄路由器必须共享同一实例（见 InputRemapController）。
 */
class KeyDispatcher(private val sink: InputSink) {

    /** 控件 id → 当前按下的**普通键位**（动作不进入本表）。 */
    private val active = LinkedHashMap<String, List<Int>>()
    private val toggled = HashSet<String>()

    /** 键位 → 仍持有它的控件 id 集合（键级引用计数）。 */
    private val keyHolders = HashMap<Int, MutableSet<String>>()

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
                releaseKeys(id, plainKeys)
            } else {
                toggled.add(id)
                active[id] = plainKeys
                plainKeys.forEach { acquire(it, id) }
            }
            return
        }

        if (active.containsKey(id)) return
        active[id] = plainKeys
        actions.forEach { sink.performAction(it) }
        plainKeys.forEach { acquire(it, id) }
    }

    fun release(id: String, autoKeep: Boolean) {
        if (autoKeep) return
        val keys = active.remove(id) ?: return
        releaseKeys(id, keys)
    }

    fun isToggled(id: String): Boolean = toggled.contains(id)

    fun releaseAll() {
        active.keys.toList().asReversed().forEach { id ->
            active.remove(id)
            removeHolders(id)
        }
        // 全部持有者撤销后统一释放：按首次登记顺序逆序，保证组合键语义稳定
        val remaining = keyHolders.keys.toList().asReversed()
        keyHolders.clear()
        remaining.forEach { sink.send(it, false) }
        toggled.clear()
    }

    /** 登记持有者；集合由空变非空（0 → 1 个持有者）时才发 keydown，避免重复 down。 */
    private fun acquire(key: Int, id: String) {
        val holders = keyHolders.getOrPut(key) { LinkedHashSet() }
        val wasEmpty = holders.isEmpty()
        holders.add(id)
        if (wasEmpty) sink.send(key, true)
    }

    /** 撤销持有者；最后一个持有者离开时才真正发 keyup。 */
    private fun releaseKeys(id: String, keys: List<Int>) {
        keys.asReversed().forEach { key ->
            val holders = keyHolders[key] ?: return@forEach
            holders.remove(id)
            if (holders.isEmpty()) {
                keyHolders.remove(key)
                sink.send(key, false)
            }
        }
    }

    private fun removeHolders(id: String) {
        keyHolders.values.forEach { it.remove(id) }
    }
}
