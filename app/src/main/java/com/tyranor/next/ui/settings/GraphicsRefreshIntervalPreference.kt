package com.tyranor.next.ui.settings

import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.pressSquish
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tyranor.next.R
import com.tyranor.next.core.settings.GraphicsRefreshInterval
import com.tyranor.next.ui.common.formatUiDecimal
import com.tyranor.next.ui.common.OverlayDropdownPreference

/** 将全局刷新间隔归一到支持的预设，只有有效选项索引才回传毫秒值。 */
@Composable
internal fun GraphicsRefreshIntervalPreference(
    intervalMs: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val presets = GraphicsRefreshInterval.PRESETS_MS
    OverlayDropdownPreference(
        // 不可用时不给按压反馈（与同卡片其他行一致）
        modifier = Modifier.pressSquish(enabled = enabled, scale = PressSquishDefaults.RowScale),
        title = stringResource(R.string.graphics_refresh_interval),
        items = refreshLabels(),
        selectedIndex = presets.indexOf(GraphicsRefreshInterval.normalize(intervalMs)),
        enabled = enabled,
        onSelectedIndexChange = { index -> presets.getOrNull(index)?.let(onChange) },
    )
}

/** 首项以当前全局值展示继承状态；选中继承回传 null，其余选项回传对应的预设毫秒值。 */
@Composable
internal fun GraphicsRefreshIntervalOverridePreference(
    globalIntervalMs: Int,
    overrideIntervalMs: Int?,
    enabled: Boolean,
    onChange: (Int?) -> Unit,
) {
    val presets = GraphicsRefreshInterval.PRESETS_MS
    val inherited = stringResource(
        R.string.engine_settings_follow_global_with_value,
        stringResource(R.string.graphics_refresh_seconds, refreshSeconds(GraphicsRefreshInterval.normalize(globalIntervalMs))),
    )
    OverlayDropdownPreference(
        // 不可用时不给按压反馈（与同卡片其他行一致）
        modifier = Modifier.pressSquish(enabled = enabled, scale = PressSquishDefaults.RowScale),
        title = stringResource(R.string.graphics_refresh_interval),
        items = listOf(inherited) + refreshLabels(),
        selectedIndex = overrideIntervalMs?.let { presets.indexOf(GraphicsRefreshInterval.normalize(it)) + 1 } ?: 0,
        enabled = enabled,
        onSelectedIndexChange = { index ->
            if (index == 0) onChange(null) else presets.getOrNull(index - 1)?.let(onChange)
        },
    )
}

@Composable
private fun refreshLabels(): List<String> = GraphicsRefreshInterval.PRESETS_MS.map {
    stringResource(R.string.graphics_refresh_seconds, refreshSeconds(it))
}

internal fun refreshSeconds(ms: Int): String =
    formatUiDecimal(ms / 1000.0, fractionDigits = 3, trimTrailingZeros = true)
