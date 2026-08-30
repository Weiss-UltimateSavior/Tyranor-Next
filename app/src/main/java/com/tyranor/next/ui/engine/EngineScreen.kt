package com.tyranor.next.ui.engine

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ExternalEngineModule
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.settings.artVersionOptions
import android.widget.Toast

/** 引擎页：列表行展示已集成的游戏引擎。 */
@Composable
fun EngineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engines = EngineLauncher.supportedEngines
    var externalInstallStates by remember {
        mutableStateOf(refreshExternalInstallStates(context, engines))
    }
    var moduleStates by remember {
        mutableStateOf(refreshModuleStates(context))
    }
    var moduleDialogEngine by remember { mutableStateOf<EngineType?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        externalInstallStates = refreshExternalInstallStates(context, engines)
        moduleStates = refreshModuleStates(context)
    }

    Column(modifier.fillMaxSize()) {
        // 顶部栏：页面背景色，标题居左
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.nav_engine), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        // 引擎列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + glassNavBottomInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = engines.distinctBy { engineDisplayName(it) },
                key = { it.name },
                contentType = { "engine" },
            ) { engine ->
                val module = ExternalEngineModuleRegistry.moduleForEngine(engine)
                val installed = module == null || externalInstallStates[engine] == true
                EngineRow(
                    engine = engine,
                    module = module,
                    installed = installed,
                    // 外置引擎与 Tyrano / WebOther/VN（内置版本条目）：点击弹出版本模块列表
                    enabled = module != null || engine in dialogOnlyEngines,
                    onClick = { moduleDialogEngine = engine },
                )
            }
        }
    }

    // 版本模块列表弹窗：复用「加入群聊」弹窗的 AppNavItem 条目，展示该引擎各版本
    // 模块的安装状态；未安装的条目点击直达对应下载页。
    moduleDialogEngine?.let { dialogEngine ->
        AppAlertDialog(
            onDismissRequest = { moduleDialogEngine = null },
            title = {
                Text(
                    stringResource(R.string.engine_list_title, engineDisplayName(dialogEngine)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    engineDialogEntries(context, moduleStates, dialogEngine).forEach { entry ->
                        AppNavItem(
                            title = entry.title,
                            summary = stringResource(entry.summaryRes),
                        ) {
                            if (!entry.installed && entry.installUrl != null) {
                                val opened = ExternalEngineLauncher.openInstallPage(context, entry.installUrl)
                                if (!opened) {
                                    Toast.makeText(context, context.getString(R.string.engine_open_download_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                            moduleDialogEngine = null
                        }
                    }
                }
            },
            // 不放取消按钮：点击条目或遮罩即关闭（confirmButton 槽位必填，传空）
            confirmButton = {},
        )
    }
}

@Composable
private fun EngineRow(
    engine: EngineType,
    module: ExternalEngineModule?,
    installed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = NavWhite),
        shape = RoundedCornerShape(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_engine_icon),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    engineDisplayName(engine),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    engineDescription(engine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                if (installed) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = when {
                    module == null -> stringResource(R.string.engine_integrated)
                    installed -> stringResource(R.string.engine_module_installed)
                    else -> stringResource(R.string.engine_module_not_installed)
                },
                tint = if (installed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 列表展示名：RPG Maker MV 与 MZ、WebOther 与 VN 各合并为一项。 */
private fun engineDisplayName(engine: EngineType): String = when (engine) {
    EngineType.RPG_MV, EngineType.RPG_MZ -> "RPG Maker MV/MZ"
    EngineType.WEB_OTHER, EngineType.VN -> "WebOther/VN"
    else -> engine.displayName
}

@Composable
private fun engineDescription(engine: EngineType): String = when (engine) {
    EngineType.KIRIKIRI -> stringResource(R.string.engine_desc_kirikiri)
    EngineType.ONS -> stringResource(R.string.engine_desc_ons)
    EngineType.TYRANO -> stringResource(R.string.engine_desc_tyrano)
    EngineType.RPGMAKER -> stringResource(R.string.engine_desc_rpgmaker)
    EngineType.RPG_MV, EngineType.RPG_MZ -> stringResource(R.string.engine_desc_rpg_mv_mz)
    EngineType.VN, EngineType.WEB_OTHER -> stringResource(R.string.engine_desc_web_other_vn)
    EngineType.ARTEMIS -> stringResource(R.string.engine_desc_artemis)
    EngineType.RENPY -> stringResource(R.string.engine_desc_renpy)
    EngineType.UNKNOWN -> stringResource(R.string.engine_desc_unknown)
}

private fun refreshExternalInstallStates(
    context: android.content.Context,
    engines: List<EngineType>,
): Map<EngineType, Boolean> =
    engines.mapNotNull { engine ->
        // 安装状态与下载/启动一致：Ren'Py 按全局版本解析目标模块（而非「任一版本已装」）
        val module = ExternalEngineModuleRegistry.resolveModule(
            engine,
            EngineSettingsStore.getRenpyVersion(context),
        ) ?: return@mapNotNull null
        engine to ExternalEngineLauncher.isPackageInstalled(context, module)
    }.toMap()

/** 内置 Tyranor 引擎版本名（Tyrano / WebOther/VN / RPG Maker MV/MZ 的网页壳共用）。 */
private const val TYRANOR_BUILTIN_VERSION = "Tyranor-2.3.4"

/** 内置版本条目：恒「已集成」，无下载动作。 */
private fun builtinEntry(id: String, title: String): EngineDialogEntry =
    EngineDialogEntry(id = id, title = title, summaryRes = R.string.engine_integrated, installed = true)

private val tyranorWebBuiltin = listOf(builtinEntry("tyranor-builtin", TYRANOR_BUILTIN_VERSION))

/** 各内置引擎的「版本条目」弹窗内容（条目顺序即展示顺序）。 */
private val builtinDialogEntries: Map<EngineType, List<EngineDialogEntry>> = mapOf(
    EngineType.TYRANO to tyranorWebBuiltin,
    EngineType.WEB_OTHER to tyranorWebBuiltin,
    EngineType.VN to tyranorWebBuiltin,
    EngineType.RPG_MV to tyranorWebBuiltin,
    EngineType.RPG_MZ to tyranorWebBuiltin,
    EngineType.ONS to listOf(
        builtinEntry("ons-builtin", "ONScripter-0.7.6"),
    ),
    EngineType.KIRIKIRI to listOf(
        builtinEntry("krkr-139", "Kirikiroid2-1.3.9"),
        builtinEntry("krkr-134", "Kirikiroid2-1.3.4"),
        builtinEntry("krkr-126", "Kirikiroid2-1.2.6"),
        builtinEntry("krkr-sdl3", "Krkrsdl3-tyn"),
    ),
)

/** 无外置模块、但点击仍展示「版本条目」弹窗的引擎（Artemis 的版本列表来自引擎设置）。 */
private val dialogOnlyEngines: Set<EngineType> = builtinDialogEntries.keys + EngineType.ARTEMIS

/** 弹窗条目：标题 + 状态文案 + 可选下载地址。 */
private data class EngineDialogEntry(
    val id: String,
    val title: String,
    @get:StringRes val summaryRes: Int,
    val installed: Boolean,
    val installUrl: String? = null,
)

/** 弹窗条目列表：外置引擎取注册表模块；Artemis 列引擎设置中的内置版本；其余内置引擎见 [builtinDialogEntries]。 */
@Composable
private fun engineDialogEntries(
    context: android.content.Context,
    moduleStates: Map<String, Boolean>,
    engine: EngineType,
): List<EngineDialogEntry> {
    val modules = ExternalEngineModuleRegistry.modules.filter { it.engine == engine }
    if (modules.isNotEmpty()) {
        return modules.sortedBy { it.displayName }.map { module ->
            val installed = moduleStates[module.id] == true
            EngineDialogEntry(
                id = module.id,
                title = module.displayName(context),
                summaryRes = if (installed) R.string.engine_module_installed else R.string.engine_module_not_installed,
                installed = installed,
                installUrl = module.installUrl,
            )
        }
    }
    return when {
        engine == EngineType.ARTEMIS -> artemisDialogEntries()
        engine in builtinDialogEntries -> builtinDialogEntries[engine].orEmpty()
        else -> emptyList()
    }
}

/** Artemis 弹窗条目：与引擎设置的版本选项同源同序（去掉 auto，非具体版本），全部为内置版本。 */
@Composable
private fun artemisDialogEntries(): List<EngineDialogEntry> =
    artVersionOptions()
        .filterNot { it.first == EngineSettingsStore.ART_ENGINE_AUTO }
        .map { (key, label) ->
            EngineDialogEntry(
                id = "artemis-$key",
                title = artemisDialogTitle(label),
                summaryRes = R.string.engine_integrated,
                installed = true,
            )
        }

/** 「v1（Tyranor/Rev.2762）」→「Tyranor/Rev.2762」：去掉内部版本号与全角括号；不匹配时原样返回。 */
private fun artemisDialogTitle(label: String): String =
    Regex("""^[vV]\d+（(.+)）$""").find(label)?.groupValues?.get(1) ?: label

/** 各外置模块的独立安装状态（按 module id，与行图标的全局版本解析互不影响）。 */
private fun refreshModuleStates(context: android.content.Context): Map<String, Boolean> =
    ExternalEngineModuleRegistry.modules.associate { module ->
        module.id to ExternalEngineLauncher.isPackageInstalled(context, module)
    }
