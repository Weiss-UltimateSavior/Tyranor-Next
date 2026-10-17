package com.tyranor.next.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed

/** One pointer owns the whole press, including movement outside the originating orb. */
internal suspend fun PointerInputScope.detectFloatingNavGesture(
    toContainer: (Offset) -> Offset,
    onDown: (Offset) -> Boolean,
    onMove: (Offset, Boolean) -> Unit,
    onHold: () -> Unit,
    onUp: (Offset, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val origin = toContainer(down.position)
        if (!onDown(origin)) return@awaitEachGesture
        down.consume()
        var moved = false
        var held = false
        var completed = false
        try {
            while (true) {
                val remaining = viewConfiguration.longPressTimeoutMillis -
                    (currentEvent.changes.firstOrNull { it.id == down.id }?.uptimeMillis
                        ?.minus(down.uptimeMillis) ?: 0L)
                val event = if (!moved && !held) {
                    withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    held = true
                    onHold()
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // A second finger or another owner cancels selection, never commits it.
                if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                val point = toContainer(change.position)
                if (!moved && (point - origin).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                val released = change.changedToUpIgnoreConsumed()
                change.consume()
                if (released) {
                    completed = true
                    onUp(point, moved, held)
                    break
                }
                onMove(point, moved)
            }
        } finally {
            // Includes disposal, resizing and Android ACTION_CANCEL.
            if (!completed) onCancel()
        }
    }
}
