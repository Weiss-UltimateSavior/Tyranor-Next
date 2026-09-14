package com.tyranor.next.ui.common.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/**
 * 底栏的**全栏物理输入**识别器（本项目独立实现）。
 *
 * 方案约束（§手势契约）：
 * 1. 物理触摸只进入这一层（D 层 Overlay），A 层 Tab 仅保留无障碍语义；
 * 2. **DOWN 同帧**回调 [onDown]（用于触觉与赴按起跳），不等 touch slop、不等长按；
 * 3. MOVE 回调**绝对坐标**（不是 delta），避免累计误差；
 * 4. UP 回调**最后一个位置**并进入严格一次（once）的终态；
 * 5. CANCEL / 事件被上层消费 / 指针消失 → 走 [onCancel]，**不提交、不补震**；
 * 6. 首指独占：其余手指一律忽略，首指 UP 即结束会话；
 * 7. touch slop 只用于判断「是否算拖动」（装饰性 panelShift / dragged 标记），
 *    不决定是否开始反馈，也不决定是否提交——快速轻点即使没过 slop 也是一次有效选择。
 *
 * 之所以不用 `detectDragGestures`：它会消费事件且自带 slop 与长按变体，与本组件
 * 「DOWN 即时反馈 + 绝对坐标 + 严格 once 终态」的要求冲突。
 */
internal suspend fun PointerInputScope.detectBottomBarPress(
    touchSlopPx: Float,
    isGestureEnabled: () -> Boolean = { true },
    onDown: (position: Offset) -> Unit,
    onDrag: (position: Offset) -> Unit,
    onUp: (position: Offset) -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        // Initial 阶段取按下点：即使有上层在 Initial 阶段拦截，也仍能拿到真实按点
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!isGestureEnabled()) return@awaitEachGesture
        val activeId = down.id
        // DOWN 同帧：先给触觉与赴按起跳，不做任何等待
        onDown(down.position)

        var lastPosition = down.position
        var lastReportedPosition = down.position
        var dragged = false
        var accumulated = Offset.Zero
        var finished = false
        var cancelled = false

        while (!finished && !cancelled) {
            val event = awaitPointerEvent()
            val change = event.changes.fastFirstOrNull { it.id == activeId }
            if (change == null) {
                // 指针从事件流消失：系统取消 / 窗口失焦
                cancelled = true
                break
            }
            if (change.isConsumed) {
                // 被上层接管（滚动、系统手势）：本轮结束且不提交
                cancelled = true
                break
            }

            // ⚠️ 必须先读位置与位移，再 consume：`positionChange()` 在事件被消费后**恒为
            // Offset.Zero**，先消费会导致位移永远为 0——表现为「按下能赴按，但完全无法拖动」。
            val delta = change.positionChange()
            val position = change.position
            // 独占：这是唯一的物理输入层，消费掉避免语义层再处理一次
            change.consume()

            if (change.changedToUpIgnoreConsumed()) {
                // UP 必须带上最后一个位置参与目标计算
                lastPosition = position
                finished = true
                break
            }
            // 位置变了就一定要上报（即使位移恰好被系统折算为 0，也不能丢掉这一帧的跟随）
            if (position != lastReportedPosition) {
                onDrag(position)
                lastReportedPosition = position
            }
            if (delta == Offset.Zero) continue
            if (!dragged) {
                accumulated += delta
                if (accumulated.getDistance() < touchSlopPx) {
                    // 未过 slop：位置已在上面上报，这里只累积判定用的位移
                    continue
                }
                dragged = true
            }
        }

        // 严格 once：无论走哪条分支都只回调一次终态
        if (finished) onUp(lastPosition) else onCancel()
    }
}
