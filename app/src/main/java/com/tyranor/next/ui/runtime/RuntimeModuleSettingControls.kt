package com.tyranor.next.ui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingCardSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import com.tyranor.next.theme.BookSerif
import com.tyranor.next.theme.BookSerifBold
import com.tyranor.next.theme.CurrentBookPaperPalette
import com.tyranor.next.theme.LocalShelfBookLeaf
import com.tyranor.next.ui.common.AppSectionCard
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.SliderValueHeader
import com.tyranor.next.ui.common.Slider as MotionSlider
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.appRowHorizontalPadding
import com.tyranor.next.ui.common.motion.pressSquishGroup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.math.BigDecimal
import kotlin.math.roundToInt

/** Renders one runtime module setting with the common App controls. */
@Composable
internal fun RuntimeSettingItemCard(
    modifier: Modifier = Modifier,
    item: RuntimeModuleSettingItemSpec,
    value: Any,
    enabled: Boolean,
    onChange: (Any) -> Unit,
    onEdit: () -> Unit,
    onPickPath: () -> Unit,
) {
    AppSectionCard(
        modifier = if (item.type == RuntimeModuleSettingItemSpec.Type.SLIDER) {
            modifier
        } else {
            modifier.pressSquishGroup(enabled = enabled)
        },
        insidePadding = PaddingValues(vertical = 8.dp),
    ) {
        RuntimeSettingContent(
            item = item,
            value = value,
            enabled = enabled,
            onChange = onChange,
            onEdit = onEdit,
            onPickPath = onPickPath,
        )
    }
}

/** Renders a declared card while keeping its settings keys flat in the edit state. */
@Composable
internal fun RuntimeSettingsGroupCard(
    modifier: Modifier = Modifier,
    spec: RuntimeModuleSettingCardSpec,
    effectiveValues: Map<String, Any>,
    enabled: Boolean,
    onChange: (RuntimeModuleSettingItemSpec, Any) -> Unit,
    onEdit: (RuntimeModuleSettingItemSpec) -> Unit,
    onPickPath: (RuntimeModuleSettingItemSpec) -> Unit,
) {
    val inBook = LocalShelfBookLeaf.current
    AppSectionCard(
        modifier = modifier.pressSquishGroup(enabled = enabled),
        title = spec.title,
        insidePadding = PaddingValues(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            spec.description?.let {
                Text(
                    it,
                    style = if (inBook) MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif) else MaterialTheme.typography.bodyMedium,
                    color = if (inBook) CurrentBookPaperPalette.inkSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = appRowHorizontalPadding()),
                )
            }
            spec.items.forEach { item ->
                RuntimeSettingContent(
                    item = item,
                    value = effectiveValues[item.key] ?: item.defaultValue,
                    enabled = enabled,
                    onChange = { onChange(item, it) },
                    onEdit = { onEdit(item) },
                    onPickPath = { onPickPath(item) },
                )
            }
        }
    }
}

@Composable
private fun RuntimeSettingContent(
    item: RuntimeModuleSettingItemSpec,
    value: Any,
    enabled: Boolean,
    onChange: (Any) -> Unit,
    onEdit: () -> Unit,
    onPickPath: () -> Unit,
) {
    val inBook = LocalShelfBookLeaf.current
    val titleTextStyle = if (inBook) {
        MaterialTheme.typography.titleMedium.copy(fontFamily = BookSerifBold)
    } else {
        MaterialTheme.typography.titleMedium
    }
    val valueTextStyle = if (inBook) {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    Column(Modifier.fillMaxWidth()) {
        when (item.type) {
            RuntimeModuleSettingItemSpec.Type.BOOLEAN -> SwitchPreference(
                checked = value as Boolean,
                onCheckedChange = onChange,
                title = item.title,
                summary = item.description,
                enabled = enabled,
            )
            RuntimeModuleSettingItemSpec.Type.ENUM -> {
                val selected = item.options.indexOfFirst { it.value == value }.coerceAtLeast(0)
                OverlayDropdownPreference(
                    items = item.options.map { it.title },
                    selectedIndex = selected,
                    title = item.title,
                    summary = item.description,
                    enabled = enabled,
                    onSelectedIndexChange = { index -> item.options.getOrNull(index)?.let { onChange(it.value) } },
                )
            }
            RuntimeModuleSettingItemSpec.Type.TEXT,
            RuntimeModuleSettingItemSpec.Type.INTEGER -> AppNavItem(
                title = item.title,
                titleTextStyle = titleTextStyle,
                summary = value.toString(),
                summaryTextStyle = valueTextStyle,
                drawSurface = false,
                showLeadingIcon = false,
                showArrow = enabled,
                onClick = if (enabled) onEdit else null,
            )
            RuntimeModuleSettingItemSpec.Type.FILE,
            RuntimeModuleSettingItemSpec.Type.FOLDER -> {
                val selectedPath = value as String
                val summary = if (selectedPath.isNotBlank()) {
                    selectedPath
                } else {
                    stringResource(
                        if (item.type == RuntimeModuleSettingItemSpec.Type.FILE) {
                            R.string.runtime_settings_choose_file
                        } else {
                            R.string.runtime_settings_choose_folder
                        },
                    )
                }
                AppNavItem(
                    title = item.title,
                    titleTextStyle = titleTextStyle,
                    summary = summary,
                    summaryTextStyle = valueTextStyle,
                    drawSurface = false,
                    showLeadingIcon = false,
                    showArrow = enabled,
                    onClick = if (enabled) onPickPath else null,
                )
            }
            RuntimeModuleSettingItemSpec.Type.SLIDER -> {
                val sliderMin = checkNotNull(item.sliderMin)
                val sliderMax = checkNotNull(item.sliderMax)
                val current = (value as Number).toDouble().coerceIn(sliderMin, sliderMax)
                val intervalCount = item.sliderIntervalCount()
                val titleColor = when {
                    inBook && enabled -> CurrentBookPaperPalette.ink
                    inBook -> CurrentBookPaperPalette.inkMuted
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MiuixTheme.colorScheme.disabledOnSurface
                }
                val valueColor = when {
                    inBook && enabled -> CurrentBookPaperPalette.inkSecondary
                    inBook -> CurrentBookPaperPalette.inkMuted
                    enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MiuixTheme.colorScheme.disabledOnSurface
                }
                SliderValueHeader(
                    title = item.title,
                    value = formatRuntimeModuleSliderValue(item, current),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = appRowHorizontalPadding()),
                    titleStyle = titleTextStyle,
                    valueStyle = valueTextStyle,
                    titleColor = titleColor,
                    valueColor = valueColor,
                )
                MotionSlider(
                    value = if (intervalCount == null) current.toFloat() else item.sliderIndex(current).toFloat(),
                    onValueChange = { raw ->
                        runtimeModuleSliderValueFromProgress(item, raw)?.let(onChange)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = appRowHorizontalPadding()).semantics(mergeDescendants = true) {
                        contentDescription = item.title
                    },
                    enabled = enabled,
                    valueRange = if (intervalCount == null) {
                        sliderMin.toFloat()..sliderMax.toFloat()
                    } else {
                        0f..intervalCount.toFloat()
                    },
                    steps = intervalCount?.let { (it - 1).coerceAtLeast(0) } ?: 0,
                    keyPoints = if (intervalCount == 1) listOf(0f, 1f) else null,
                )
            }
        }

        val showDescriptionBelow = item.type != RuntimeModuleSettingItemSpec.Type.BOOLEAN &&
            item.type != RuntimeModuleSettingItemSpec.Type.ENUM
        if (showDescriptionBelow) item.description?.let { description ->
            Text(
                description,
                style = valueTextStyle,
                color = when {
                    inBook && enabled -> CurrentBookPaperPalette.inkSecondary
                    inBook -> CurrentBookPaperPalette.inkMuted
                    enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MiuixTheme.colorScheme.disabledOnSurface
                },
                modifier = Modifier.padding(horizontal = appRowHorizontalPadding(), vertical = 4.dp),
            )
        }
    }
}

/** Formats declared ticks with decimal precision instead of exposing binary arithmetic tails. */
internal fun formatRuntimeModuleSliderValue(item: RuntimeModuleSettingItemSpec, value: Double): String {
    val displayValue = if (item.sliderStep != null) {
        item.sliderValueAtIndex(item.sliderIndex(value))
    } else {
        value
    }
    return BigDecimal.valueOf(displayValue).stripTrailingZeros().toPlainString()
}

/** Convert slider Float progress to a declared value, ignoring non-finite accessibility input. */
internal fun runtimeModuleSliderValueFromProgress(item: RuntimeModuleSettingItemSpec, raw: Float): Double? {
    if (!raw.isFinite()) return null
    val min = checkNotNull(item.sliderMin)
    val max = checkNotNull(item.sliderMax)
    return if (item.sliderStep == null) {
        raw.toString().toDoubleOrNull()?.coerceIn(min, max)
    } else {
        item.sliderValueAtIndex(raw.roundToInt())
    }
}
