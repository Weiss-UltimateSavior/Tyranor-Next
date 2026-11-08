package com.tyranor.next.ui.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.engine.external.EmulatorLaunchStyle
import com.tyranor.next.core.engine.external.ExternalEmulatorLauncher
import com.tyranor.next.core.engine.external.ExternalEmulatorRegistry
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.ByteFormats
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.coachAnchor
import com.tyranor.next.ui.common.isWideScreen
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.RollingNumberText
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.settings.artVersionOptions
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import androidx.compose.ui.graphics.Color
import android.widget.Toast

/** 引擎页：列表行展示已集成的游戏引擎。 */
@Composable
fun EngineScreen(
    modifier: Modifier = Modifier,
    onCoachAnchorChanged: (Rect) -> Unit = {},
    isActive: Boolean = true,
    gameCount: Int = 0,
) {
    val latestOnCoachAnchorChanged = rememberUpdatedState(onCoachAnchorChanged)
    val context = LocalContext.current
    val engineOpenDownloadFailedMessage = stringResource(R.string.engine_open_download_failed)
    val engines = EngineLauncher.supportedEngines
    var externalInstallStates by remember {
        mutableStateOf(refreshExternalInstallStates(context, engines))
    }
    var moduleStates by remember {
        mutableStateOf(refreshModuleStates(context))
    }
    var emulatorInstallStates by remember {
        mutableStateOf(refreshEmulatorInstallStates(context))
    }
    var ppssppVersion by remember { mutableStateOf(EngineSettingsStore.getPpssppVersion(context)) }
    var moduleDialogEngine by remember { mutableStateOf<EngineType?>(null) }
    var showExternalJumpDialog by remember { mutableStateOf(false) }
    // YU-RIS 等「引擎专属外置运行时」弹窗：只列该引擎的目标（如 Winlator），标题与内置版本弹窗同构
    var emulatorDialogEngine by remember { mutableStateOf<EngineType?>(null) }
    // 内部存储读取是常数级开销，先同步显示；游戏目录大小在后台用批量查询补齐，
    // 避免首次进入时卡片长时间停在加载态。
    val internalStorage = remember { readInternalStorage() }
    val gameDirsBytes by produceState<Long?>(initialValue = null, gameCount) {
        value = withContext(Dispatchers.IO) {
            GameDirsSizeCache.getOrCompute(context, gameCount)
        }
    }
    val storageStats = StorageStats(
        internalTotalBytes = internalStorage.totalBytes,
        gameBytes = gameDirsBytes ?: 0L,
        freeBytes = internalStorage.freeBytes,
    )

    // 安装状态刷新：PackageManager.getPackageInfo 逐项查询放到 IO 线程，回主线程后一次性写入 Compose 状态。
    // 结果到达前沿用旧值；若期间又发起了更新的一轮刷新，旧一轮的结果丢弃（以免用旧快照覆盖新快照）。
    val refreshScope = rememberCoroutineScope()
    val refreshToken = remember { IntArray(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val token = ++refreshToken[0]
        refreshScope.launch {
            val appContext = context.applicationContext
            val fresh = withContext(Dispatchers.IO) {
                Triple(
                    refreshExternalInstallStates(appContext, engines),
                    refreshModuleStates(appContext),
                    refreshEmulatorInstallStates(appContext),
                )
            }
            if (token == refreshToken[0]) {
                externalInstallStates = fresh.first
                moduleStates = fresh.second
                emulatorInstallStates = fresh.third
            }
        }
        ppssppVersion = EngineSettingsStore.getPpssppVersion(context)
    }

    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.nav_engine))

        // 顶部分页（可在应用设置中关闭）：关闭时平铺展示全部引擎项
        val categorizeEngines by AppSettingsStore.engineTabsState.collectAsState()
        var selectedTab by remember { mutableIntStateOf(0) }
        if (categorizeEngines) {
            val tabs = listOf(
                stringResource(R.string.engine_tab_gal),
                stringResource(R.string.engine_tab_rpgm),
                stringResource(R.string.engine_tab_console),
                stringResource(R.string.engine_tab_web),
            )
            MiuixSettingsTheme {
                val tabModifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                if (AppThemeColors.isGlass || AppThemeColors.isMilkGlass) {
                    // 玻璃外观：Miuix TabRow 不支持指示器描边，改用统一标签栏组件
                    // （亮卡指示器 + 滑动动画 + 0.5dp 发丝描边，见 AGENTS.md「标签栏统一规范」）
                    // 玻璃/白雾外观：Miuix TabRow 的选中文字取 onBackground，会在亮指示器上
                    // 变成浅色；改用统一标签栏组件（自带亮底深字/深底浅字分派）。
                    SlidingTabRow(
                        tabs = tabs,
                        selectedIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = tabModifier,
                    )
                } else {
                    TabRow(
                        tabs = tabs,
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        // 圆角与组件统一入口同源：取「应用圆角调整」值
                        cornerRadius = AppComponentCornerRadius,
                        // 容器背景透明（只保留选中指示器）：Miuix 默认取 surface，深色下呈不透明黑块
                        colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
                        modifier = tabModifier,
                    )
                }
            }
        }

        val tabEngines = (if (categorizeEngines) {
            engines.filter { engineTabOf(it) == EngineTab.entries[selectedTab] }
        } else {
            engines
        }).distinctBy { engineDisplayName(it) }

        // 引擎列表：竖屏单列；横屏/大屏一行三个
        val engineColumns = if (isWideScreen()) 3 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(engineColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp + glassNavBottomInset()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 存储概览作为列表首项，随列表一起滚动（顶栏与分类栏保持固定）
            item(
                key = "storage_overview",
                contentType = "storage_overview",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                StorageOverviewCard(
                    gameCount = gameCount,
                    engineCount = engines.distinctBy { engineDisplayName(it) }.size,
                    stats = storageStats,
                    gameBytesLoading = gameDirsBytes == null,
                    modifier = Modifier.staggeredEntrance(0),
                )
            }
            gridItemsIndexed(
                items = tabEngines,
                key = { _, it -> it.name },
                contentType = { _, _ -> "engine" },
            ) { engineIndex, engine ->
                val isCoachTarget = engine == tabEngines.firstOrNull()
                if (isCoachTarget) {
                    DisposableEffect(engine) {
                        onDispose {
                            latestOnCoachAnchorChanged.value(Rect.Zero)
                        }
                    }
                }
                val module = ExternalEngineModuleRegistry.moduleForEngine(engine)
                val emulator = ExternalEmulatorRegistry.forEngine(engine)
                val installed = when {
                    module != null -> externalInstallStates[engine] == true
                    emulator != null -> {
                        // PSP 的安装态跟随「PPSSPP 版本」生效值（标准版/黄金版）
                        val pkg = if (emulator.supports(EngineType.PSP)) {
                            ExternalEmulatorRegistry.ppssppTarget(ppssppVersion).packageName
                        } else {
                            emulator.packageName
                        }
                        emulatorInstallStates[pkg] == true
                    }
                    else -> true
                }
                val statusRes = when {
                    module != null ->
                        if (installed) R.string.engine_module_installed else R.string.engine_module_not_installed

                    emulator != null ->
                        if (installed) R.string.engine_emulator_installed else R.string.engine_emulator_not_installed

                    else -> R.string.engine_integrated
                }
                EngineRow(
                    engine = engine,
                    statusTextRes = statusRes,
                    installed = installed,
                    // 外置模块 / 外置模拟器 / Tyrano/WebOther/VN/Artemis（内置版本条目）：点击弹窗
                    enabled = module != null || emulator != null || engine in dialogOnlyEngines,
                    compact = engineColumns > 1,
                    modifier = Modifier
                        .coachAnchor(
                            enabled = isCoachTarget && isActive,
                            onBounds = onCoachAnchorChanged,
                        )
                        .staggeredEntrance(engineIndex + 1)
                        .then(
                            if (MotionMode.enhanced) {
                                Modifier.animateItem(
                                    fadeInSpec = MotionTokens.fadeIn(),
                                    placementSpec = MotionTokens.layout(IntOffset.VisibilityThreshold),
                                    fadeOutSpec = MotionTokens.fadeOut(),
                                )
                            } else {
                                Modifier
                            },
                        ),
                    onClick = {
                        when {
                            // Winlator 系（YU-RIS / 手动 PC）：只展示本引擎的外置运行时，标题「<引擎> 引擎列表」
                            emulator != null && emulator.launchStyle == EmulatorLaunchStyle.WINLATOR_EXTERNAL ->
                                emulatorDialogEngine = engine
                            emulator != null -> showExternalJumpDialog = true
                            else -> moduleDialogEngine = engine
                        }
                    },
                )
            }

            // 外置跳转支持（PPSSPP / Eden / Winlator 聚合入口）：分类模式仅主机系列展示，平铺模式始终展示
            if (!categorizeEngines || selectedTab == EngineTab.CONSOLE.ordinal) {
                item(
                    key = "external-jump",
                    contentType = "external-jump",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    ExternalJumpRow(
                        modifier = Modifier.staggeredEntrance(99).then(
                            if (MotionMode.enhanced) {
                                Modifier.animateItem(
                                    fadeInSpec = MotionTokens.fadeIn(),
                                    placementSpec = MotionTokens.layout(IntOffset.VisibilityThreshold),
                                    fadeOutSpec = MotionTokens.fadeOut(),
                                )
                            } else {
                                Modifier
                            },
                        ),
                        installedCount = emulatorInstallStates.values.count { it },
                        onClick = { showExternalJumpDialog = true },
                    )
                }
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
                    // 条目较多（如 Artemis 全版本）时超出弹窗高度上限，需可滚动避免末条被截断
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    engineDialogEntries(context, moduleStates, dialogEngine).forEach { entry ->
                        AppNavItem(
                            title = entry.title,
                            summary = stringResource(entry.summaryRes),
                            leadingIcon = R.drawable.ic_engine_chip,
                            containerColor = DialogItemSurface,
                        ) {
                            if (!entry.installed && entry.installUrl != null) {
                                val opened = ExternalEngineLauncher.openInstallPage(context, entry.installUrl)
                                if (!opened) {
                                    Toast.makeText(context, engineOpenDownloadFailedMessage, Toast.LENGTH_SHORT).show()
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

    // Winlator 系引擎专属弹窗：只列该引擎的外置运行时（Winlator），未安装跳下载页、已安装打开主界面
    emulatorDialogEngine?.let { dialogEngine ->
        val entries = ExternalEmulatorRegistry.targets.filter { it.supports(dialogEngine) }
        AppAlertDialog(
            onDismissRequest = { emulatorDialogEngine = null },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entries.forEach { target ->
                        val installed = emulatorInstallStates[target.packageName] == true
                        AppNavItem(
                            title = stringResource(target.displayNameRes),
                            summary = stringResource(
                                if (installed) R.string.engine_emulator_installed else R.string.engine_emulator_not_installed,
                            ),
                            leadingIcon = R.drawable.ic_engine_chip,
                            containerColor = DialogItemSurface,
                        ) {
                            if (installed) {
                                ExternalEmulatorLauncher.openHome(context, target)
                            } else if (!ExternalEngineLauncher.openInstallPage(context, target.installUrl)) {
                                Toast.makeText(context, engineOpenDownloadFailedMessage, Toast.LENGTH_SHORT).show()
                            }
                            emulatorDialogEngine = null
                        }
                    }
                }
            },
            // 不放取消按钮：点击条目或遮罩即关闭（confirmButton 槽位必填，传空）
            confirmButton = {},
        )
    }

    // 外置跳转支持弹窗：列 PPSSPP / Eden / Winlator，未安装点击跳下载页，已安装点击打开主界面
    if (showExternalJumpDialog) {
        AppAlertDialog(
            onDismissRequest = { showExternalJumpDialog = false },
            title = {
                Text(
                    stringResource(R.string.engine_external_jump_title),
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
                    ExternalEmulatorRegistry.targets.forEach { target ->
                        val installed = emulatorInstallStates[target.packageName] == true
                        AppNavItem(
                            title = stringResource(target.displayNameRes),
                            summary = stringResource(
                                if (installed) R.string.engine_emulator_installed else R.string.engine_emulator_not_installed,
                            ),
                            leadingIcon = R.drawable.ic_engine_chip,
                            containerColor = DialogItemSurface,
                        ) {
                            if (installed) {
                                ExternalEmulatorLauncher.openHome(context, target)
                            } else {
                                val opened = ExternalEngineLauncher.openInstallPage(context, target.installUrl)
                                if (!opened) {
                                    Toast.makeText(context, engineOpenDownloadFailedMessage, Toast.LENGTH_SHORT).show()
                                }
                            }
                            showExternalJumpDialog = false
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** 本机存储概览：内部存储 + 已添加游戏目录的总占用比例，附剩余空间与库数量。 */
@Composable
private fun StorageOverviewCard(
    gameCount: Int,
    engineCount: Int,
    stats: StorageStats,
    gameBytesLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = NavWhite,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = AppComponentShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_engine_storage),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        stringResource(R.string.engine_storage_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 数值变化（加载完成、剩余空间、数量）时整句模糊替换
                BlurCrossfade(
                    targetState = stringResource(
                        R.string.engine_storage_game_usage,
                        if (gameBytesLoading) "…" else ByteFormats.formatBinarySize(stats.gameBytes),
                    ),
                ) { usageText ->
                    Text(
                        usageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BlurCrossfade(
                    targetState = stringResource(
                        R.string.engine_storage_remaining,
                        ByteFormats.formatBinarySize(stats.freeBytes),
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                ) { remainingText ->
                    Text(
                        remainingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BlurCrossfade(
                    targetState = stringResource(R.string.engine_storage_counts, gameCount, engineCount),
                    modifier = Modifier.padding(top = 2.dp),
                ) { countsText ->
                    Text(
                        countsText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StorageRing(
                ratio = stats.ratio,
                loading = gameBytesLoading,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * Shows bounded storage usage, retaining the last settled sweep across lazy-item recreation.
 * Enhanced mode animates the sweep; ordinary mode draws the latest ratio immediately.
 */
@Composable
private fun StorageRing(
    ratio: Float,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val safeRatio = ratio.coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val progressColor = MaterialTheme.colorScheme.primary
    // 进度弧：首次显示与数值变化时从当前值弹簧扫过去（加载中保持 0）
    // 记住上次停稳的值：滚出屏幕再滚回来时直接停在原位，不重新从 0 扫
    var settledSweep by rememberSaveable { mutableFloatStateOf(0f) }
    val sweep = remember { Animatable(settledSweep) }
    val sweepTarget = if (loading) 0f else safeRatio
    LaunchedEffect(sweepTarget) {
        if (MotionMode.enhanced) {
            // 特例：环形占用弧刻意用更长的 0.9s 平滑扫过（比 value 慢），强调「占用量」读数
            sweep.animateTo(sweepTarget, MotionSprings.smooth(900, 0.001f))
        } else {
            sweep.snapTo(sweepTarget)
        }
        settledSweep = sweepTarget
    }
    Box(modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (!loading) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * (if (MotionMode.enhanced) sweep.value else sweepTarget).coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RollingNumberText(
                if (loading) "…" else "${(safeRatio * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.engine_storage_ring_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class InternalStorage(
    val totalBytes: Long,
    val freeBytes: Long,
)

private fun readInternalStorage(): InternalStorage = runCatching {
    val statFs = StatFs(Environment.getDataDirectory().absolutePath)
    val total = statFs.totalBytes
    InternalStorage(
        totalBytes = total,
        freeBytes = statFs.availableBytes,
    )
}.getOrElse { InternalStorage(0L, 0L) }

private data class StorageStats(
    val internalTotalBytes: Long,
    /** 已添加游戏目录的总大小（环的分子）。 */
    val gameBytes: Long,
    val freeBytes: Long,
) {
    /** 严格按「游戏目录 / 内部总容量」；游戏目录超过内部总量时夹到 100%。 */
    val ratio: Float
        get() = if (internalTotalBytes <= 0L) {
            0f
        } else {
            (gameBytes.toDouble() / internalTotalBytes).toFloat().coerceIn(0f, 1f)
        }
}

/** 游戏目录大小缓存：按「根列表 + 游戏数」记忆，避免切页/重组重复遍历。 */
private object GameDirsSizeCache {
    private var key: String? = null
    private var size: Long = 0L

    @Synchronized
    fun getOrCompute(context: Context, gameCount: Int): Long {
        val roots = GameLibraryFacade.loadRoots(context)
        val cacheKey = "$gameCount|" + roots.joinToString("|")
        if (key == cacheKey) return size
        val computed = roots.sumOf { directorySizeBytes(context, it) }
        key = cacheKey
        size = computed
        return computed
    }
}

/** 统计单个已添加目录的大小；SAF 目录走 DocumentsContract 批量查询，file:// 走 File 遍历。 */
private fun directorySizeBytes(context: Context, root: String): Long = runCatching {
    val uri = Uri.parse(root)
    when (uri.scheme) {
        "file" -> {
            val path = uri.path ?: return@runCatching 0L
            File(path).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        "content" -> treeSizeBytes(context, uri)
        else -> 0L
    }
}.getOrDefault(0L)

/**
 * SAF 目录大小：每次查询直接取子项的 DOCUMENT_ID / MIME_TYPE / SIZE。
 * 相比 `DocumentFile.listFiles()` 不再逐项创建对象/额外查询元数据，目录多时快一个量级。
 */
private fun treeSizeBytes(context: Context, treeUri: Uri): Long {
    val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return 0L
    val resolver = context.contentResolver
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    var total = 0L
    val stack = ArrayDeque<String>()
    stack.addLast(rootId)
    while (stack.isNotEmpty()) {
        val parentId = stack.removeLast()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idCol < 0 || mimeCol < 0) return@use
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeCol)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        cursor.getString(idCol)?.let { stack.addLast(it) }
                    } else if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
                        total += cursor.getLong(sizeCol).coerceAtLeast(0L)
                    }
                }
            }
        }
    }
    return total
}

/** Resolves an engine's icon and label, forwarding the caller's installation status and click behavior. */
@Composable
private fun EngineRow(
    engine: EngineType,
    @StringRes statusTextRes: Int,
    installed: Boolean,
    enabled: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    EngineRowCard(
        modifier = modifier,
        iconRes = engineIconRes(engine),
        title = engineDisplayName(engine),
        statusTextRes = statusTextRes,
        installed = installed,
        enabled = enabled,
        compact = compact,
        onClick = onClick,
    )
}

/** 外置跳转支持行：与引擎行同视觉，右侧按“是否至少装了一个模拟器”显示状态。 */
@Composable
private fun ExternalJumpRow(
    installedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EngineRowCard(
        modifier = modifier,
        iconRes = R.drawable.ic_engine_card_external,
        title = stringResource(R.string.engine_external_jump_title),
        statusTextRes = R.string.engine_external_jump_summary,
        installed = installedCount > 0,
        enabled = true,
        compact = false,
        onClick = onClick,
    )
}

/**
 * 原引擎条目布局：左侧引擎图标横幅（右侧渐变融入卡片）+ 标题 + 右侧原状态图标。
 * 高度 60dp；三列紧凑模式下横幅收窄到 88dp。状态图标保持 CheckCircle / Cancel。
 */
@Composable
private fun EngineRowCard(
    @DrawableRes iconRes: Int,
    title: String,
    @StringRes statusTextRes: Int,
    installed: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bannerWidth = if (compact) 88.dp else 120.dp
    val fadeWidth = minOf(47.dp, bannerWidth * 0.5f)
    val fadeStart = (bannerWidth - fadeWidth).value / bannerWidth.value
    // 白雾/复古的卡片底色带透明度，固定色遮罩会留下接缝；让图片自身 alpha 淡出，
    // 过渡终点直接取卡片背景。默认主题保持原来的 NavWhite 色遮罩，不动其它风格。
    val useImageFadeMask = AppThemeColors.isMilkGlass || AppThemeColors.isGlass
    Card(
        modifier = modifier
            .pressSquish(enabled = enabled, scale = PressSquishDefaults.CardScale)
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .glassBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = NavWhite,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = AppComponentShape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(bannerWidth)
                    .fillMaxHeight(),
            ) {
                if (useImageFadeMask) {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // DstIn 必须先离屏，否则会作用到整张卡片/父层而不是这张图片。
                                compositingStrategy = CompositingStrategy.Offscreen
                                clip = true
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.Black,
                                        fadeStart to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                } else {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 默认主题保持原有的 NavWhite 色遮罩（44 → 47dp，再加长 3dp）
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(fadeWidth)
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    1f to NavWhite,
                                ),
                            ),
                    )
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            )
            BlurCrossfade(
                targetState = installed,
                modifier = Modifier
                    .padding(start = 8.dp, end = 16.dp)
                    // 安装状态变化时整个图标位弹一下（放在替换容器上，新旧图标都随之形变）
                    .bounceOnChange(installed)
                    .size(20.dp),
                contentAlignment = Alignment.Center,
            ) { isInstalled ->
                Icon(
                    if (isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = stringResource(statusTextRes),
                    tint = if (isInstalled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@DrawableRes
private fun engineIconRes(engine: EngineType): Int = when (engine) {
    EngineType.KIRIKIRI -> R.drawable.ic_engine_card_kirikiri
    EngineType.ONS -> R.drawable.ic_engine_card_ons
    EngineType.TYRANO -> R.drawable.ic_engine_card_tyrano
    EngineType.RPGMAKER -> R.drawable.ic_engine_card_rpgmaker
    EngineType.RPG_MV, EngineType.RPG_MZ -> R.drawable.ic_engine_card_rpg_mv_mz
    EngineType.VN, EngineType.WEB_OTHER -> R.drawable.ic_engine_card_web_vn
    EngineType.ARTEMIS -> R.drawable.ic_engine_card_artemis
    EngineType.SIGLUS -> R.drawable.ic_engine_card_siglus
    EngineType.REALLIVE -> R.drawable.ic_engine_card_reallive
    EngineType.AVG32 -> R.drawable.ic_engine_card_avg32
    EngineType.XSYS35 -> R.drawable.ic_engine_card_system35
    EngineType.XSYS4 -> R.drawable.ic_engine_card_system4
    EngineType.UK2 -> R.drawable.ic_engine_card_uk2
    EngineType.FVP -> R.drawable.ic_engine_card_fvp
    EngineType.ETHORNELL -> R.drawable.ic_engine_card_ethornell
    EngineType.RENPY -> R.drawable.ic_engine_card_renpy
    EngineType.RUFFLE -> R.drawable.ic_engine_card_ruffle
    EngineType.YURIS -> R.drawable.ic_engine_card_yuris
    EngineType.CATSYSTEM2 -> R.drawable.ic_engine_card_catsystem2
    EngineType.PC -> R.drawable.ic_engine_card_pc
    EngineType.PSP -> R.drawable.ic_engine_card_psp
    EngineType.NINTENDO_SWITCH -> R.drawable.ic_engine_card_switch
    EngineType.ANDROID_APP, EngineType.RUNTIME_MODULE, EngineType.UNKNOWN -> R.drawable.ic_engine_icon
}

/** 引擎页顶部分页（顺序即展示顺序）：GAL / RPGM / 主机 / 网页。 */
private enum class EngineTab { GAL, RPGM, CONSOLE, WEB }

private fun engineTabOf(engine: EngineType): EngineTab = when (engine) {
    EngineType.RPGMAKER, EngineType.RPG_MV, EngineType.RPG_MZ -> EngineTab.RPGM
    EngineType.KIRIKIRI, EngineType.ONS, EngineType.ARTEMIS, EngineType.SIGLUS,
    EngineType.REALLIVE, EngineType.AVG32, EngineType.XSYS35, EngineType.XSYS4, EngineType.UK2, EngineType.FVP,
    EngineType.ETHORNELL,
    EngineType.RENPY, EngineType.YURIS, EngineType.CATSYSTEM2 -> EngineTab.GAL
    // PC（手动添加，经 Winlator 运行）与主机模拟器同属「主机」分类
    EngineType.PSP, EngineType.NINTENDO_SWITCH, EngineType.PC -> EngineTab.CONSOLE
    // 手动添加的安卓应用不进入引擎页（supportedEngines 未收录），此处仅为 when 完备性
    EngineType.ANDROID_APP -> EngineTab.CONSOLE
    // Flash 属于 Web 时代的格式，与 Tyrano / WebOther / VN 同归「网页」分类
    EngineType.RUFFLE, EngineType.TYRANO, EngineType.WEB_OTHER, EngineType.VN -> EngineTab.WEB
    // 第三方运行时模块不进入引擎页列表；此处仅为 when 完备性。
    EngineType.RUNTIME_MODULE -> EngineTab.WEB
    EngineType.UNKNOWN -> EngineTab.WEB
}

/** 列表展示名：RPG Maker MV 与 MZ、WebOther 与 VN 各合并为一项。 */
private fun engineDisplayName(engine: EngineType): String = when (engine) {
    EngineType.RPG_MV, EngineType.RPG_MZ -> "RPG Maker MV/MZ"
    EngineType.WEB_OTHER, EngineType.VN -> "WebOther/VN"
    else -> engine.displayName
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

/** RPG Maker MV/MZ 弹窗条目：内置网页壳 + TyranorNext 系列（0.1/0.2 合并显示）。 */
private val rpgMakerWebBuiltin = listOf(
    builtinEntry("tyranor-builtin", TYRANOR_BUILTIN_VERSION),
    builtinEntry("tyranornext-01-02", "TyranorNext-0.1/0.2"),
)

/** 各内置引擎的「版本条目」弹窗内容（条目顺序即展示顺序）。 */
private val builtinDialogEntries: Map<EngineType, List<EngineDialogEntry>> = mapOf(
    EngineType.ETHORNELL to listOf(builtinEntry("internal.ethornell", "ethornell-rs / Android host ABI 1")),
    EngineType.TYRANO to tyranorWebBuiltin,
    EngineType.WEB_OTHER to tyranorWebBuiltin,
    EngineType.VN to tyranorWebBuiltin,
    EngineType.RPG_MV to rpgMakerWebBuiltin,
    EngineType.RPG_MZ to rpgMakerWebBuiltin,
    EngineType.ONS to listOf(
        builtinEntry("ons-builtin", "ONScripter-0.7.7"),
    ),
    EngineType.SIGLUS to listOf(
        builtinEntry("siglus-rs", "siglus_rs-xmoezzz"),
    ),
    // 三引擎由同一 game_launcher 运行库承载
    EngineType.REALLIVE to listOf(
        builtinEntry("game-launcher", "game_launcher-xmoezzz"),
    ),
    EngineType.AVG32 to listOf(
        builtinEntry("game-launcher", "game_launcher-xmoezzz"),
    ),
    EngineType.UK2 to listOf(
        builtinEntry("game-launcher", "game_launcher-xmoezzz"),
    ),
    EngineType.XSYS35 to listOf(
        builtinEntry("xsys35-2.20.0", "xsystem35-2.20.0"),
    ),
    EngineType.XSYS4 to listOf(
        builtinEntry("xsys4-6a94a0c", "xsystem4-6a94a0c"),
    ),
    EngineType.FVP to listOf(
        builtinEntry("fvp-rfvp", "rfvp-xmoezzz"),
    ),
    EngineType.KIRIKIRI to listOf(
        builtinEntry("krkr-139", "Kirikiroid2-1.3.9"),
        builtinEntry("krkr-134", "Kirikiroid2-1.3.4"),
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
        } + EngineDialogEntry(
            // 自研 clean-room 内核：随插件包内置，与官方 revision 同列为「已集成」
            id = "artemis-${EngineSettingsStore.ART_KERNEL_CLEAN}",
            title = "TyranorNext/artemis-compat",
            summaryRes = R.string.engine_integrated,
            installed = true,
        )

/** 「v1（Tyranor/Rev.2762）」→「Tyranor/Rev.2762」：去掉内部版本号与全角括号；不匹配时原样返回。 */
private fun artemisDialogTitle(label: String): String =
    Regex("""^[vV]\d+（(.+)）$""").find(label)?.groupValues?.get(1) ?: label

/** 各外置模块的独立安装状态（按 module id，与行图标的全局版本解析互不影响）。 */
private fun refreshModuleStates(context: android.content.Context): Map<String, Boolean> =
    ExternalEngineModuleRegistry.modules.associate { module ->
        module.id to ExternalEngineLauncher.isPackageInstalled(context, module)
    }

/** 外置主机模拟器安装状态（按包名）。 */
private fun refreshEmulatorInstallStates(context: android.content.Context): Map<String, Boolean> =
    ExternalEmulatorRegistry.targets.associate { target ->
        target.packageName to ExternalEmulatorLauncher.isInstalled(context, target)
    }
