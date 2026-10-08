package com.core.input

/**
 * 虚拟按键层的触摸指针账本（纯 Kotlin，无 Android 依赖，可直接单测）。
 *
 * 记录「哪根手指按住哪个按钮」。抽成独立类有两个原因：
 *
 *  1. **可测性**：[VirtualPadView] 依赖 MotionEvent/RectF，JVM 单测无法构造，而这里承载的
 *     「拖离取消」「多指共按同一按钮」「恰好释放一次」三条语义正是既往出问题的位置；
 *  2. **消除迭代器误用**：调用方过去直接遍历 Map 并在循环里 `remove`，`return@let` 只退出
 *     lambda、控制流继续走到第二次 `remove` 就抛 `IllegalStateException`（按住按钮拖出
 *     范围这一常见取消手势即崩溃）。改成「迭代器删除 + 每轮至多删一次」的形态后，
 *     这类错误在结构上不再可能——迭代器由本类独占持有。
 *
 * 线程约束：只在主线程（View 的触摸事件）使用。
 */
internal class PointerLedger {

    private val targets = HashMap<Int, String>()
    private val buttons = HashMap<Int, PadButton>()

    val size: Int get() = targets.size

    fun isEmpty(): Boolean = targets.isEmpty()

    fun isNotEmpty(): Boolean = targets.isNotEmpty()

    /** 记下该指针当前按住的按钮。 */
    fun press(pointerId: Int, button: PadButton) {
        targets[pointerId] = button.id
        buttons[pointerId] = button
    }

    /** 该指针当前按住的按钮；未按住时返回 null。 */
    fun buttonOf(pointerId: Int): PadButton? = buttons[pointerId]

    /**
     * 当前是否仍有指针按着同 id 的按钮（多指共按同一按钮时的释放守卫）。
     *
     * 调用方先 [release] 再问这里：还有人就别释放，否则先抬的那一指会提前送 keyup，
     * 打断另一根手指仍按着的长按。
     */
    fun isHeldById(buttonId: String): Boolean = buttons.values.any { it.id == buttonId }

    /**
     * 移除所有不满足 [keep] 的指针，返回被移除的指针所对应的按钮。
     *
     * 迭代器由本类持有并只在循环内前进一次，因此每轮至多删除一项，不会出现
     * 「同一轮删两次」这类迭代器失配。释放判定由调用方用 [isHeldById] 收口。
     */
    fun retainOnly(keep: (pointerId: Int) -> Boolean): List<PadButton> {
        val removed = ArrayList<PadButton>()
        val iterator = targets.entries.iterator()
        while (iterator.hasNext()) {
            val (pointerId, _) = iterator.next()
            if (keep(pointerId)) continue
            iterator.remove()
            buttons.remove(pointerId)?.let(removed::add)
        }
        return removed
    }

    /** 指针抬起：移除并返回其按住的按钮（未按住返回 null）。 */
    fun release(pointerId: Int): PadButton? {
        targets.remove(pointerId)
        return buttons.remove(pointerId)
    }

    /**
     * 全部指针按住的按钮，**按 id 去重**。
     *
     * 供整层释放使用：同一按钮被多指按住时若按指针逐条返回，调用方会对同一按钮
     * 重复释放，送出错位的 keyup 计数。
     */
    fun allButtons(): List<PadButton> = buttons.values.distinctBy { it.id }

    fun clear() {
        targets.clear()
        buttons.clear()
    }
}
