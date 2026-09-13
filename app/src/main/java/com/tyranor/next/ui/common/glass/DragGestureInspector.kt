package com.tyranor.next.ui.common.glass

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull

/**
 * 按下即进入拖动的手势识别（无长按计时、无 touch slop 等待）。
 *
 * 移植自 legado-with-MD3 `app/src/main/java/io/legado/app/ui/util/DragGestureInspector.kt`
 * （commit fb01a76ebbbca41423e2c4c00080cc0861239fbd），按分析报告 §6.5 / §8.6 保留以下源行为：
 * 1. `awaitFirstDown` 之后立即回调 [onDragStart]，不添加长按延迟；
 * 2. 不使用 `detectDragGestures` 的 touch slop：第一次位移即回调 [onDrag]；
 * 3. 主指抬起时若仍有其它按下的指针，则把拖动会话交接给该指针（多指连续性）；
 * 4. 输入被上游消费（`isConsumed`）或指针丢失时走 [onDragCancel]；
 * 5. 本函数**不**调用 `consume()`，因此底栏下方的可点击项仍能收到点击事件
 *    （源实现同样如此：透镜按住与同一项的点击可以共存）。
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = drag(
            pointerId = initialDown.id,
            onDrag = { onDrag(it, it.positionChange()) },
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
