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
 *    都是 Enter+Space）。按键记录持有者，只有最后一个持有者释放时才真正发 keyup，
 *    否则「按住屏幕 OK 再碰一下手柄 A」会让屏幕上的长按被提前打断。
 *  - **来源命名空间**：调用方用前缀区分来源（`pad:` / `gp:`），[releaseScope] 只释放
 *    自己那一侧。手柄拔插、轴 CANCEL 这类「只应影响手柄」的事件因此不会把屏幕上
 *    按住的虚拟按键一起放掉。
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

    /**
     * 释放指定来源（id 前缀，如 `pad:` / `gp:`）持有的全部键。
     *
     * 供「只要影响本来源」的场景使用：手柄拔插、轴 CANCEL、关闭手柄开关、虚拟按键层
     * 卸载。共享同一个派发器时，若不分来源地整体释放，会把另一侧按住的键一并放掉——
     * 因此本类**不提供**全局释放入口，调用方按 scope 各自释放。
     */
    fun releaseScope(scope: String) {
        val affected = ArrayList<Int>()
        active.keys.filter { it.startsWith(scope) }.forEach { id ->
            val keys = active.remove(id) ?: return@forEach
            keys.forEach { key ->
                keyHolders[key]?.remove(id)
                if (keyHolders[key]?.isEmpty() == true) {
                    keyHolders.remove(key)
                    affected.add(key)
                }
            }
        }
        toggled.removeAll { it.startsWith(scope) }
        // 逆序释放，保证组合键（如 Ctrl+A）的抬键顺序与按下的加键顺序相反
        affected.asReversed().forEach { sink.send(it, false) }
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
}
