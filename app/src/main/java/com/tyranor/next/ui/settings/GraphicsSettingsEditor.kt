package com.tyranor.next.ui.settings

import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.pressSquish
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.GraphicsFamily
import com.tyranor.next.R
import com.tyranor.next.core.settings.GraphicsAnalyzerPosition
import com.tyranor.next.core.settings.GraphicsCapabilities
import com.tyranor.next.core.settings.GraphicsOverride
import com.tyranor.next.core.settings.GraphicsSettings
import com.tyranor.next.core.settings.GraphicsSettingsCodec
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import com.tyranor.next.ui.common.SwitchPreference

/**
 * 按引擎图形能力编辑全局滤镜与分析器设置，只通过 [onChange] 回传副本。
 * 不支持或依赖运行库的能力以提示说明，分析器相关入口遵循解析后的有效启用状态。
 */
@Composable
internal fun GlobalGraphicsSettings(
    settings: GraphicsSettings,
    capability: GraphicsCapabilities,
    onChange: (GraphicsSettings) -> Unit,
) {
    EngineCard(stringResource(R.string.graphics_settings_title)) {
        ExpandVisibility(!capability.supported || capability.runtimeDependent) {
            Text(stringResource(if (capability.runtimeDependent) R.string.graphics_settings_runtime_dependent
                else R.string.graphics_settings_unsupported), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        SwitchPreference(
            modifier = Modifier.pressSquish(enabled = capability.supported, scale = PressSquishDefaults.RowScale),
            title = stringResource(R.string.graphics_settings_enabled),
            checked = settings.enabled,
            enabled = capability.supported,
            onCheckedChange = { onChange(settings.copy(enabled = it)) },
        )
        GraphicsChainEditor(settings.chain, capability.supported) { onChange(settings.copy(chain = it)) }
        SwitchPreference(
            modifier = Modifier.pressSquish(enabled = GraphicsSettingsCodec.effectiveEnabled(settings, capability), scale = PressSquishDefaults.RowScale),
            title = stringResource(R.string.graphics_analyzer_title),
            checked = settings.analyzer,
            enabled = GraphicsSettingsCodec.effectiveEnabled(settings, capability),
            onCheckedChange = { onChange(settings.copy(analyzer = it)) },
        )
        OverlayDropdownPreference(
            modifier = Modifier.pressSquish(enabled = GraphicsSettingsCodec.effectiveAnalyzer(settings, capability), scale = PressSquishDefaults.RowScale),
            title = stringResource(R.string.graphics_analyzer_position),
            items = graphicsPositionLabels(), selectedIndex = settings.analyzerPosition.ordinal,
            enabled = GraphicsSettingsCodec.effectiveAnalyzer(settings, capability),
            onSelectedIndexChange = { index ->
                GraphicsAnalyzerPosition.entries.getOrNull(index)?.let { position ->
                    onChange(settings.copy(analyzerPosition = position))
                }
            },
        )
        GraphicsRefreshIntervalPreference(
            intervalMs = settings.refreshIntervalMs,
            enabled = GraphicsSettingsCodec.effectiveEnabled(settings, capability) && settings.analyzer,
            onChange = { onChange(settings.copy(refreshIntervalMs = it)) },
        )
    }
}

/**
 * 编辑单游戏图形覆盖；null 表示跟随全局，展示和入口可用性使用解析后的有效设置。
 * 自定义滤镜链与继承切换由编解码门面处理，避免把继承状态误写为固定副本。
 */
@Composable
internal fun PerGameGraphicsSettings(
    global: GraphicsSettings,
    override: GraphicsOverride,
    capability: GraphicsCapabilities,
    onChange: (GraphicsOverride) -> Unit,
) {
    val resolved = GraphicsSettingsCodec.resolve(global, override)
    EngineCard(stringResource(R.string.graphics_settings_title)) {
        ExpandVisibility(!capability.supported || capability.runtimeDependent) {
            Text(stringResource(if (capability.runtimeDependent) R.string.graphics_settings_runtime_dependent
                else R.string.graphics_settings_unsupported), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        OverrideSwitch(
            stringResource(R.string.graphics_settings_enabled), global.enabled, override.enabled,
            enabled = capability.supported,
        ) { onChange(override.copy(enabled = it)) }
        OverlayDropdownPreference(
            modifier = Modifier.pressSquish(enabled = capability.supported, scale = PressSquishDefaults.RowScale),
            title = stringResource(R.string.graphics_chain_title),
            items = listOf(stringResource(R.string.engine_settings_follow_global), stringResource(R.string.graphics_chain_custom)),
            selectedIndex = if (override.chain == null) 0 else 1,
            enabled = capability.supported,
            onSelectedIndexChange = { index ->
                onChange(GraphicsSettingsCodec.setChainInheritance(global, override, inherit = index == 0))
            },
        )
        GraphicsChainEditor(resolved.chain, capability.supported && override.chain != null) {
            onChange(GraphicsSettingsCodec.setCustomChain(override, it))
        }
        OverrideSwitch(
            stringResource(R.string.graphics_analyzer_title), global.analyzer, override.analyzer,
            enabled = GraphicsSettingsCodec.effectiveEnabled(resolved, capability),
        ) { onChange(override.copy(analyzer = it)) }
        OverlayDropdownPreference(
            modifier = Modifier.pressSquish(enabled = GraphicsSettingsCodec.effectiveAnalyzer(resolved, capability), scale = PressSquishDefaults.RowScale),
            title = stringResource(R.string.graphics_analyzer_position),
            items = listOf(stringResource(R.string.engine_settings_follow_global)) + graphicsPositionLabels(),
            selectedIndex = override.analyzerPosition?.let { it.ordinal + 1 } ?: 0,
            enabled = GraphicsSettingsCodec.effectiveAnalyzer(resolved, capability),
            onSelectedIndexChange = { index ->
                if (index == 0) onChange(override.copy(analyzerPosition = null))
                else GraphicsAnalyzerPosition.entries.getOrNull(index - 1)?.let { position ->
                    onChange(override.copy(analyzerPosition = position))
                }
            },
        )
        GraphicsRefreshIntervalOverridePreference(
            globalIntervalMs = global.refreshIntervalMs,
            overrideIntervalMs = override.refreshIntervalMs,
            enabled = GraphicsSettingsCodec.effectiveAnalyzer(resolved, capability),
            onChange = { onChange(override.copy(refreshIntervalMs = it)) },
        )
    }
}

internal fun EngineSettingsKind.graphicsFamily(): GraphicsFamily? = when (this) {
    EngineSettingsKind.KRKR -> GraphicsFamily.KR
    EngineSettingsKind.ARTEMIS -> GraphicsFamily.ARTEMIS
    EngineSettingsKind.SIGLUS -> GraphicsFamily.SIGLUS
    else -> null
}

internal fun EngineType.graphicsFamily(): GraphicsFamily? = when (this) {
    EngineType.KIRIKIRI -> GraphicsFamily.KR
    EngineType.ARTEMIS -> GraphicsFamily.ARTEMIS
    EngineType.SIGLUS -> GraphicsFamily.SIGLUS
    else -> null
}

@Composable
private fun graphicsPositionLabels(): List<String> = listOf(
    stringResource(R.string.graphics_analyzer_top_center), stringResource(R.string.graphics_analyzer_bottom_center),
)
