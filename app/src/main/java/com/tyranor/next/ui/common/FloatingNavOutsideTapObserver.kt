package com.tyranor.next.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/** The page ancestor observes taps without adding a sibling that intercepts the page. */
internal class FloatingNavOutsideTapBridge {
    var hostCoordinates: LayoutCoordinates? = null
    var handler: FloatingNavOutsideTapHandler? = null

    fun containsControls(hostPoint: Offset): Boolean {
        val coordinates = hostCoordinates?.takeIf { it.isAttached } ?: return false
        return handler?.containsControls?.invoke(coordinates.localToWindow(hostPoint)) == true
    }
}

internal class FloatingNavOutsideTapHandler(
    val containsControls: (Offset) -> Boolean,
    val startsOutside: (Offset) -> Boolean,
    val dismiss: () -> Unit,
)

internal fun Modifier.observeFloatingNavOutsideTaps(bridge: FloatingNavOutsideTapBridge): Modifier =
    onGloballyPositioned { bridge.hostCoordinates = it }
        .pointerInput(bridge) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val handler = bridge.handler ?: return@awaitEachGesture
                val coordinates = bridge.hostCoordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                if (!handler.startsOutside(coordinates.localToWindow(down.position))) return@awaitEachGesture
                val origin = down.position
                while (true) {
                    // The underlying button may consume its DOWN and UP. That is expected:
                    // observe the same tap in Final, without consuming any part of it.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    if ((change.position - origin).getDistance() > viewConfiguration.touchSlop) break
                    if (change.changedToUpIgnoreConsumed()) {
                        handler.dismiss()
                        break
                    }
                }
            }
        }
