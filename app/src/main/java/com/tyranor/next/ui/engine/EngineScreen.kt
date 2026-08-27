package com.tyranor.next.ui.engine

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
import androidx.compose.material3.TextButton
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
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.glassNavBottomInset
import android.widget.Toast

/** 引擎页：列表行展示已集成的游戏引擎。 */
@Composable
fun EngineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engines = EngineLauncher.supportedEngines
    var externalInstallStates by remember {
        mutableStateOf(refreshExternalInstallStates(context, engines))
    }
    var missingModule by remember { mutableStateOf<ExternalEngineModule?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        externalInstallStates = refreshExternalInstallStates(context, engines)
    }

    Column(modifier.fillMaxSize()) {
        // 顶部栏：页面背景色，标题居左
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("引擎", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
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
                    onClick = {
                        if (module != null && !installed) {
                            missingModule = module
                        }
                    },
                )
            }
        }
    }

    missingModule?.let { module ->
        AppAlertDialog(
            onDismissRequest = { missingModule = null },
            title = {
                Text(
                    "模块未安装",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "未检测到 ${module.displayName}，需要下载安装后才能启动 ${module.engine.displayName} 游戏。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            dismissButton = {
                TextButton(onClick = { missingModule = null }) {
                    Text("取消", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val opened = ExternalEngineLauncher.openInstallPage(context, module)
                        if (!opened) {
                            Toast.makeText(context, "无法打开下载页面", Toast.LENGTH_SHORT).show()
                        }
                        missingModule = null
                    },
                ) {
                    Text("去下载", style = MaterialTheme.typography.bodyMedium)
                }
            },
        )
    }
}

@Composable
private fun EngineRow(
    engine: EngineType,
    module: ExternalEngineModule?,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = module != null && !installed, onClick = onClick),
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
                    module == null -> "已集成"
                    installed -> "模块已安装"
                    else -> "模块未安装"
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

private fun engineDescription(engine: EngineType): String = when (engine) {
    EngineType.KIRIKIRI -> "Kirikiroid2 / krkrsdl3，.xp3 与 startup.tjs 游戏"
    EngineType.ONS -> "ONScripter，nscript.dat 与 .nsa 归档游戏"
    EngineType.TYRANO -> "TyranoBuilder，index.html 与 tyrano/ 脚本游戏"
    EngineType.RPG_MV, EngineType.RPG_MZ -> "RPG Maker MV/MZ，www 与 js/rpg_core.js、rmmz_core.js 游戏"
    EngineType.VN, EngineType.WEB_OTHER -> "WebOther/VN，globalData.vndata 或通用 index.html 网页游戏"
    EngineType.ARTEMIS -> "Artemis，system.ini 与 .pfs 归档游戏"
    EngineType.RENPY -> "Ren'Py 外置 APK 模块，检测安装状态后启动"
    EngineType.UNKNOWN -> "未知引擎"
}

private fun refreshExternalInstallStates(
    context: android.content.Context,
    engines: List<EngineType>,
): Map<EngineType, Boolean> =
    engines.mapNotNull { engine ->
        val module = ExternalEngineModuleRegistry.moduleForEngine(engine) ?: return@mapNotNull null
        engine to ExternalEngineLauncher.isPackageInstalled(context, module)
    }.toMap()
