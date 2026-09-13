package com.tyranor.next.ui.common.glass

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull

/**
 * 透镜的「按下即拖动」识别器（本项目独立实现，非移植代码）。
 *
 * 行为契约（由产品需求与可选交互方案决定，与参考实现的观感一致）：
 * 1. 手指按下**立即**进入会话（不等长按）：材质与按压反馈即时响应；
 *    但**位移要超过 [touchSlopPx] 才开始报告拖动**——否则「滑动途中轻点一下」会被手指抖动
 *    判成拖动，把正在滑动的透镜截停并提交到相邻槽位；
 * 2. 回调提供当前位置与上一帧位置，交给调用方判断是否仍在透镜范围内；
 * 3. **不消费任何事件**：未被透镜覆盖的 Tab 仍会收到自己的点击（透镜只监听、不拦截）；
 *    注意命中测试是「最上层兄弟独占」，被透镜覆盖的那个 Tab 由透镜接管；
 * 4. 事件被上层消费（例如宿主滚动容器接管）时立即中止会话，走 [onCancel]；
 * 5. 主指抬起时若仍有其它手指按住，把会话移交给该手指，保证多指操作下拖动连续；
 * 6. 指针从事件流中消失（系统取消、窗口失焦）同样走 [onCancel]。
 *
 * 之所以不用 `detectDragGestures`：它会消费事件，与本组件「不抢其它 Tab 点击」的要求冲突。
 *
 * @param touchSlopPx 开始报告拖动所需的最小位移（取平台 `ViewConfiguration` 的 scaledTouchSlop）。
 * @param onRelease 参数为「本次会话是否真的拖动过」：调用方据此决定松手是否提交，
 *   纯点击不提交（保持滑动目标不变）。
 */
internal suspend fun PointerInputScope.detectLensPressDrag(
    touchSlopPx: Float,
    onPress: () -> Unit,
    onDrag: (position: Offset, previous: Offset, delta: Offset) -> Unit,
    onRelease: (dragged: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        // Initial 阶段取按下点：即使有上层在 Initial 阶段拦截，也仍能拿到真实按点
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var activeId = down.id
        onPress()

        var dragged = false
        var accumulated = Offset.Zero
        var released = false
        var aborted = false
        while (!released && !aborted) {
            val event = awaitPointerEvent()
            val change = event.changes.fastFirstOrNull { it.id == activeId }
            if (change == null) {
                // 指针消失：系统取消或宿主换了事件流
                aborted = true
                break
            }
            if (change.isConsumed) {
                // 被上层接管（滚动、系统手势、父级点击）——本轮对话结束，不提交
                aborted = true
                break
            }
            if (change.changedToUpIgnoreConsumed()) {
                val next = event.changes.fastFirstOrNull { it.pressed }
                if (next == null) {
                    released = true
                } else {
                    activeId = next.id
                }
                continue
            }
            val delta = change.positionChange()
            if (delta == Offset.Zero) continue
            if (!dragged) {
                // 起手先攒位移：超过平台 slop 才算真的拖动（并把攒下的位移一并交出去）
                accumulated += delta
                if (accumulated.getDistance() < touchSlopPx) continue
                dragged = true
                onDrag(change.position, change.previousPosition, accumulated)
                continue
            }
            onDrag(change.position, change.previousPosition, delta)
        }

        if (released) onRelease(dragged) else onCancel()
    }
}
