package com.tyranor.next.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.DialogItemSurface
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix 0.9.2 has no switch for its built-in thumb press scale. Keep the visual idle,
 * and expose one interactive outer control so dialogs retain their no-feedback policy. */
@Composable
internal fun DialogMiuixSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val currentValue by rememberUpdatedState(value)
    val update by rememberUpdatedState(onValueChange)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val thumbHeight = with(LocalDensity.current) { 28.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    fun change(next: Float): Boolean {
        if (!next.isFinite()) return false
        val constrained = next.coerceIn(0f, 1f)
        if (constrained == currentValue) return false
        update(constrained)
        return true
    }
    fun fromPosition(x: Float) {
        // Match the native Slider's half-height inset at both track ends.
        if (width <= thumbHeight) return
        val fraction = ((x - thumbHeight / 2f) / (width - thumbHeight)).coerceIn(0f, 1f)
        change(if (rtl) 1f - fraction else fraction)
    }
    Box(
        modifier.fillMaxWidth().height(48.dp).onSizeChanged { width = it.width }
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(currentValue, 0f..1f)
                setProgress { change(it) }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val increment = when (event.key) {
                    Key.DirectionRight -> if (rtl) -0.01f else 0.01f
                    Key.DirectionLeft -> if (rtl) 0.01f else -0.01f
                    Key.DirectionUp -> 0.01f
                    Key.DirectionDown -> -0.01f
                    else -> return@onKeyEvent false
                }
                change(currentValue + increment)
                true
            }.focusable()
            .pointerInput(rtl, thumbHeight) {
                detectTapGestures(onTap = { fromPosition(it.x) })
            }.pointerInput(rtl, thumbHeight) {
                detectHorizontalDragGestures(
                    onDragStart = { fromPosition(it.x) },
                    onHorizontalDrag = { pointer, _ -> pointer.consume(); fromPosition(pointer.position.x) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = value,
            onValueChange = {},
            enabled = false,
            valueRange = 0f..1f,
            hapticEffect = SliderDefaults.SliderHapticEffect.None,
            colors = SliderDefaults.sliderColors(
                disabledForegroundColor = MiuixTheme.colorScheme.primary,
                disabledBackgroundColor = DialogItemSurface,
                disabledThumbColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}
