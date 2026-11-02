package com.tyranor.next.ui.runtime

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleBindings
import com.tyranor.next.core.engine.runtime.RuntimeModuleDescriptor
import com.tyranor.next.core.engine.runtime.RuntimeModuleRegistry
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 引擎模块管理页：展示已发现的第三方运行时模块（包名前缀 + LAUNCH action），并维护启用开关。
 *
 * 页面只负责「发现 + 启用状态」：启用/禁用只写开关并刷新快照，不触发游戏库扫描、不写库；
 * 扫描仍由游戏页的现有入口发起。禁用有绑定游戏的模块前先确认，确认后由 core 完成降级。
 * 空列表不提供下载/安装入口，只说明协议要求后提示点右上角刷新。
 */
@Composable
internal fun EngineModuleManagementScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by RuntimeModuleRegistry.state.collectAsStateWithLifecycle()
    // 预先解析带数量的 Toast 格式串，避免在点击回调里用 LocalContext 查资源（Compose lint）。
    val disabledToastPattern = stringResource(R.string.engine_module_management_disabled_toast)

    var refreshing by remember { mutableStateOf(true) }
    // 每个模块当前绑定的游戏数量：在 IO 读取整库后按包名分组，不在组合主线程读库。
    var gameCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // 数量尚未读到时禁用前先补读，避免把「未加载」误当成 0 而跳过降级确认。
    var countsLoaded by remember { mutableStateOf(false) }
    // 正在提交开关的模块：避免快速连点导致非点击顺序落盘。
    var togglingPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 禁用确认：只对「有绑定游戏」的模块弹窗，确认后调用 core 降级。
    var pendingDisableModule by remember { mutableStateOf<RuntimeModuleDescriptor?>(null) }
    var pendingDisableCount by remember { mutableIntStateOf(0) }
    // 首次组合由 LaunchedEffect 刷新；observer 注册时重放的首个 ON_RESUME 跳过，避免重复查询。
    var resumeAfterFirst by remember { mutableStateOf(false) }

    suspend fun loadGameCounts() {
        val counts = withContext(Dispatchers.IO) {
            GameLibraryFacade.loadGames(context)
                .asSequence()
                .filter { game ->
                    game.engine == EngineType.RUNTIME_MODULE &&
                        !game.runtimeModulePackage.isNullOrBlank()
                }
                .groupingBy { game -> game.runtimeModulePackage.orEmpty() }
                .eachCount()
        }
        gameCounts = counts
        countsLoaded = true
    }

    suspend fun refreshModules() {
        refreshing = true
        try {
            RuntimeModuleRegistry.refresh(context)
            // 管理页刷新时同步核对绑定：模块在页面打开期间被卸载/禁用也能及时降级。
            RuntimeModuleBindings.reconcile(context)
            loadGameCounts()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Toast.makeText(
                context,
                R.string.engine_module_management_operation_failed,
                Toast.LENGTH_SHORT,
            ).show()
        } finally {
            refreshing = false
        }
    }

    fun applyEnabled(module: RuntimeModuleDescriptor, enabled: Boolean) {
        if (module.packageName in togglingPackages) return
        togglingPackages += module.packageName
        scope.launch {
            try {
                val degraded = RuntimeModuleRegistry.setEnabled(context, module.packageName, enabled)
                if (enabled) {
                    Toast.makeText(
                        context,
                        if (module.scan == null) {
                            R.string.engine_module_management_enabled_no_scan_toast
                        } else {
                            R.string.engine_module_management_enabled_toast
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (degraded > 0) {
                    Toast.makeText(
                        context,
                        String.format(java.util.Locale.getDefault(), disabledToastPattern, degraded),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Toast.makeText(
                    context,
                    R.string.engine_module_management_operation_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                togglingPackages -= module.packageName
                runCatching { loadGameCounts() }
            }
        }
    }

    fun requestEnabledChange(module: RuntimeModuleDescriptor, enabled: Boolean) {
        if (enabled) {
            applyEnabled(module, true)
            return
        }
        // 绑定数量还没读完时先补读，再按真实数量决定是否弹降级确认。
        if (!countsLoaded) {
            scope.launch {
                try {
                    loadGameCounts()
                    requestEnabledChange(module, false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    Toast.makeText(
                        context,
                        R.string.engine_module_management_operation_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return
        }
        val boundGames = gameCounts[module.packageName] ?: 0
        if (boundGames > 0) {
            pendingDisableCount = boundGames
            pendingDisableModule = module
        } else {
            applyEnabled(module, false)
        }
    }

    LaunchedEffect(Unit) {
        refreshModules()
    }
    // 从安装器返回后重新发现模块：ON_RESUME 覆盖安装/卸载后的列表变化。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumeAfterFirst) {
            scope.launch { refreshModules() }
        } else {
            resumeAfterFirst = true
        }
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.engine_module_management_title),
                    background = Color.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_game_scan),
                            stringResource(R.string.engine_module_management_refresh),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            scope.launch { refreshModules() }
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                // 顶栏透明：列表整体垫在顶栏下方（持久 padding），避免滚动时内容穿过顶栏
                modifier = Modifier.fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val lastError = snapshot.lastError
                if (lastError != null) {
                    item(key = "load_error") {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    stringResource(R.string.engine_module_management_load_failed),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                if (refreshing && snapshot.modules.isEmpty() && lastError == null) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                if (!refreshing && snapshot.modules.isEmpty() && lastError == null) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillParentMaxHeight().fillMaxWidth().padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_module),
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(40.dp),
                                )
                                Text(
                                    stringResource(R.string.engine_module_management_empty),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }

                items(
                    items = snapshot.modules,
                    key = { module -> module.packageName },
                ) { module ->
                    EngineModuleCard(
                        module = module,
                        gameCount = gameCounts[module.packageName] ?: 0,
                        toggleEnabled = module.packageName !in togglingPackages,
                        onEnabledChange = { enabled -> requestEnabledChange(module, enabled) },
                    )
                }
            }
        }
    }

    val disableTarget = pendingDisableModule
    if (disableTarget != null) {
        AppAlertDialog(
            onDismissRequest = { pendingDisableModule = null },
            title = {
                Text(
                    stringResource(R.string.engine_module_management_disable_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    stringResource(R.string.engine_module_management_disable_message, pendingDisableCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.engine_module_management_disable_confirm),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        pendingDisableModule = null
                        applyEnabled(disableTarget, false)
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingDisableModule = null },
                )
            },
        )
    }
}

/** 单个模块卡片：label + 包名/版本/协议/扫描能力/绑定数量，右侧启用开关。 */
@Composable
private fun EngineModuleCard(
    module: RuntimeModuleDescriptor,
    gameCount: Int,
    toggleEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val version = module.versionName
        ?.takeIf { it.isNotBlank() }
        ?.let { "$it (${module.versionCode})" }
        ?: module.versionCode.toString()
    val scanText = stringResource(
        if (module.scan != null) {
            R.string.engine_module_management_scan_supported
        } else {
            R.string.engine_module_management_scan_unsupported
        },
    )
    val summary = stringResource(
        R.string.engine_module_management_summary,
        module.packageName,
        version,
        module.protocol,
        scanText,
        gameCount,
    )

    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SwitchPreference(
                title = module.label.ifBlank { module.packageName },
                summary = summary,
                checked = module.enabled,
                enabled = toggleEnabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}
