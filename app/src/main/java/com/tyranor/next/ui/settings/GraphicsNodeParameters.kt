package com.tyranor.next.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.GraphicsFilterCatalog
import com.tyranor.next.core.settings.GraphicsNode
import com.tyranor.next.core.settings.GraphicsScaleMode
import com.tyranor.next.core.settings.GraphicsSettingsCodec
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogMiuixSlider
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.common.formatUiDecimal
import kotlin.math.round

@Composable
internal fun GraphicsNodeParameters(node: GraphicsNode, onDismiss: () -> Unit, onApply: (GraphicsNode) -> Unit) {
    var draft by remember(node) { mutableStateOf(node) }
    var scale by remember(node) { mutableStateOf(formatScale(node.scaleFactor)) }
    val factor = if (draft.scaleMode == GraphicsScaleMode.FACTOR) validScale(scale) else 1.0
    val result = factor?.let { draft.copy(widthScale = it, heightScale = it) }
    val valid = result != null && GraphicsSettingsCodec.validChain(listOf(result))
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(graphicsFilterOptions().toMap().getValue(node.id), style = MaterialTheme.typography.titleMedium) },
        text = {
            MiuixSettingsTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (GraphicsFilterCatalog.sharpnessAdjustable(node.id)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.graphics_filter_sharpness), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium)
                            Text(formatUiDecimal(draft.sharpness, fractionDigits = 2), style = MaterialTheme.typography.bodyMedium)
                        }
                        DialogMiuixSlider(value = draft.sharpness.toFloat(),
                            onValueChange = { draft = draft.copy(sharpness = round(it.toDouble() * 100) / 100) },
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Text(stringResource(R.string.graphics_filter_scale_type), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    val modes = GraphicsFilterCatalog.scaleModes(node.id)
                    // Keep selection inside this Dialog window; use a mode choice without switch press effects.
                    SlidingTabRow(
                        tabs = modes.map { mode -> stringResource(when (mode) {
                            GraphicsScaleMode.NONE -> R.string.graphics_filter_scale_none
                            GraphicsScaleMode.FIT -> R.string.graphics_filter_scale_fit
                            GraphicsScaleMode.FACTOR -> R.string.graphics_filter_scale_factor
                        }) },
                        selectedIndex = modes.indexOf(draft.scaleMode),
                        onTabSelected = { index ->
                            modes.getOrNull(index)?.let { mode ->
                                draft = draft.copy(scaleMode = mode)
                                if (mode != GraphicsScaleMode.FACTOR) {
                                    draft = draft.copy(widthScale = 1.0, heightScale = 1.0)
                                    scale = "1"
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (draft.scaleMode == GraphicsScaleMode.FACTOR) {
                        ScaleParameter(stringResource(R.string.graphics_filter_scale_multiplier), scale, { scale = it })
                    }
                }
            }
        },
        dismissButton = { DialogTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) },
        confirmButton = {
            DialogTextButton(stringResource(R.string.common_confirm), enabled = valid, onClick = {
                if (valid) onApply(result)
            })
        },
    )
}

@Composable
private fun ScaleParameter(title: String, value: String, onChange: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppSearchField(query = value, onQueryChange = onChange, modifier = Modifier.weight(1f),
            leadingIcon = androidx.compose.ui.res.painterResource(R.drawable.ic_graphics_parameters),
            iconContentDescription = title, textStyle = MaterialTheme.typography.bodyMedium)
        DialogTextButton(stringResource(R.string.graphics_filter_decrease), enabled = (validScale(value) ?: 1.0) > 0.25) {
            onChange(formatScale(((validScale(value) ?: 1.0) - 0.25).coerceAtLeast(0.25)))
        }
        DialogTextButton(stringResource(R.string.graphics_filter_increase), enabled = (validScale(value) ?: 1.0) < 4.0) {
            onChange(formatScale(((validScale(value) ?: 1.0) + 0.25).coerceAtMost(4.0)))
        }
    }
}

private fun formatScale(value: Double): String = formatUiDecimal(value, 6, trimTrailingZeros = true)
private fun validScale(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()
    ?.takeIf { it.isFinite() && it in 0.25..4.0 }
