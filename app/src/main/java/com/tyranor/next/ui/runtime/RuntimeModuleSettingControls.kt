package com.tyranor.next.ui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingCardSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.Slider as MotionSlider
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.motion.pressSquishGroup
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.round
import kotlin.math.roundToInt

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
    MiuixCard(
        cornerRadius = AppComponentCornerRadius,
        modifier = modifier.pressSquishGroup().fillMaxWidth().glassBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
}

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
    MiuixCard(
        cornerRadius = AppComponentCornerRadius,
        modifier = modifier.pressSquishGroup().fillMaxWidth().glassBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                spec.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            spec.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
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
    val titleTextStyle = MaterialTheme.typography.titleMedium
    val valueTextStyle = MaterialTheme.typography.bodyMedium
    Column(Modifier.fillMaxWidth()) {
        when (item.type) {
            RuntimeModuleSettingItemSpec.Type.BOOLEAN -> SwitchPreference(
                checked = value as Boolean,
                onCheckedChange = onChange,
                title = item.title,
                summary = item.description,
                enabled = enabled,
                titleTextStyle = titleTextStyle,
            )
            RuntimeModuleSettingItemSpec.Type.ENUM -> {
                val selected = item.options.indexOfFirst { it.value == value }.coerceAtLeast(0)
                OverlayDropdownPreference(
                    items = item.options.map { it.title },
                    selectedIndex = selected,
                    title = item.title,
                    summary = item.description,
                    enabled = enabled,
                    titleTextStyle = titleTextStyle,
                    valueTextStyle = valueTextStyle,
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
                val intervalCount = runtimeModuleSliderIntervalCount(item)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.title, modifier = Modifier.weight(1f), style = titleTextStyle, color = TextColor)
                    Text(
                        formatRuntimeModuleSliderValue(item, current),
                        style = valueTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MotionSlider(
                    value = if (intervalCount == null) current.toFloat() else runtimeModuleSliderIndex(item, current).toFloat(),
                    onValueChange = { raw ->
                        val selectedValue = if (intervalCount == null) {
                            normalizeSliderValue(item, raw.toDouble())
                        } else {
                            runtimeModuleSliderValueAtIndex(item, raw.roundToInt())
                        }
                        onChange(selectedValue)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics(mergeDescendants = true) {
                        contentDescription = item.title
                    },
                    enabled = enabled,
                    valueRange = if (intervalCount == null) {
                        sliderMin.toFloat()..sliderMax.toFloat()
                    } else {
                        0f..intervalCount.toFloat()
                    },
                    steps = intervalCount?.let { (it - 1).coerceAtLeast(0) } ?: 0,
                )
            }
        }

        val showDescriptionBelow = item.type != RuntimeModuleSettingItemSpec.Type.BOOLEAN &&
            item.type != RuntimeModuleSettingItemSpec.Type.ENUM
        if (showDescriptionBelow) item.description?.let { description ->
            Text(
                description,
                style = valueTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

internal fun runtimeModuleSliderIntervalCount(item: RuntimeModuleSettingItemSpec): Int? {
    val step = item.sliderStep ?: return null
    val min = checkNotNull(item.sliderMin)
    val max = checkNotNull(item.sliderMax)
    val range = BigDecimal.valueOf(max).subtract(BigDecimal.valueOf(min))
    return range.divide(BigDecimal.valueOf(step)).intValueExact()
}

internal fun runtimeModuleSliderIndex(item: RuntimeModuleSettingItemSpec, value: Double): Int {
    val min = checkNotNull(item.sliderMin)
    val step = checkNotNull(item.sliderStep)
    val intervalCount = checkNotNull(runtimeModuleSliderIntervalCount(item))
    val offset = BigDecimal.valueOf(value).subtract(BigDecimal.valueOf(min))
    val index = offset.divide(BigDecimal.valueOf(step), 0, RoundingMode.HALF_EVEN).intValueExact()
    return index.coerceIn(0, intervalCount)
}

internal fun runtimeModuleSliderValueAtIndex(item: RuntimeModuleSettingItemSpec, index: Int): Double {
    val min = checkNotNull(item.sliderMin)
    val max = checkNotNull(item.sliderMax)
    val step = checkNotNull(item.sliderStep)
    val intervalCount = checkNotNull(runtimeModuleSliderIntervalCount(item))
    val minDecimal = BigDecimal.valueOf(min)
    val maxDecimal = BigDecimal.valueOf(max)
    val value = minDecimal.add(
        BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(index.coerceIn(0, intervalCount).toLong())),
    )
    return value.max(minDecimal).min(maxDecimal).stripTrailingZeros().toDouble()
}

internal fun formatRuntimeModuleSliderValue(item: RuntimeModuleSettingItemSpec, value: Double): String {
    val displayValue = if (item.sliderStep != null) {
        runtimeModuleSliderValueAtIndex(item, runtimeModuleSliderIndex(item, value))
    } else {
        value
    }
    return BigDecimal.valueOf(displayValue).stripTrailingZeros().toPlainString()
}

private fun normalizeSliderValue(item: RuntimeModuleSettingItemSpec, raw: Double): Double {
    val min = checkNotNull(item.sliderMin)
    val max = checkNotNull(item.sliderMax)
    val constrained = raw.coerceIn(min, max)
    val step = item.sliderStep ?: return BigDecimal.valueOf(constrained).stripTrailingZeros().toDouble()
    val intervalCount = checkNotNull(runtimeModuleSliderIntervalCount(item))
    val index = round((constrained - min) / step).toInt().coerceIn(0, intervalCount)
    return runtimeModuleSliderValueAtIndex(item, index)
}
