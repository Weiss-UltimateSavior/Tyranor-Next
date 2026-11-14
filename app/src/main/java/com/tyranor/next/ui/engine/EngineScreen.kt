package com.tyranor.next.ui.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.key
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.tyranor.next.core.game.storage.GameLibraryFacade
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
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.ByteFormats
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.common.tabDragSelect
import com.tyranor.next.ui.common.TabPagerState
import com.tyranor.next.ui.common.rememberTabPagerState
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.coachAnchor
import com.tyranor.next.ui.common.isWideScreen
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.rememberScrollJellyState
import com.tyranor.next.ui.common.motion.scrollJelly
import com.tyranor.next.ui.common.motion.scrollJellyHost
import kotlinx.coroutines.delay
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.HiddenPageAlpha
import com.tyranor.next.ui.common.motion.skipDrawWhenHidden
import com.tyranor.next.ui.common.motion.tabPageTransform
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.RollingNumberText
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.motion.startActivityWithPageTransition
import com.tyranor.next.ui.settings.EngineSettingsActivity
import com.tyranor.next.ui.settings.artVersionOptions
import com.tyranor.next.ui.settings.engineSettingsKindOf
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.notice.AppNotice

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
    // 应用设置「显示引擎卡片图标」：关闭后条目只显示引擎名与状态图标
    val showEngineCardIcon by AppSettingsStore.engineCardIconState.collectSettingAsState()
    var externalModules by remember {
        mutableStateOf(readExternalModuleStatus(context))
    }
    var emulatorInstallStates by remember {
        mutableStateOf(refreshEmulatorInstallStates(context))
    }
    var ppssppVersion by remember { mutableStateOf(EngineSettingsStore.getPpssppVersion(context)) }
    var moduleDialogEngine by rememberSaveable { mutableStateOf<EngineType?>(null) }
    // YU-RIS 等「引擎专属外置运行时」弹窗：只列该引擎的目标（如 Winlator），标题与内置版本弹窗同构
    var emulatorDialogEngine by rememberSaveable { mutableStateOf<EngineType?>(null) }
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
                readExternalModuleStatus(appContext) to refreshEmulatorInstallStates(appContext)
            }
            if (token == refreshToken[0]) {
                externalModules = fresh.first
                emulatorInstallStates = fresh.second
            }
        }
        ppssppVersion = EngineSettingsStore.getPpssppVersion(context)
    }

    Column(modifier.fillMaxSize()) {
        // 顶栏标题的页面入场由 AppTopBar 自带
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        // 分类栏与分页内容共用的状态：点选时指示器弹过去、分页只在旧页和新页之间切换；长按分类栏 / 标题区唤出玻璃透镜选择
        val enhanced = MotionMode.enhanced
        // Switching modes discards an in-flight enhanced page and its offsets together.
        val pager = key(enhanced) { rememberTabPagerState({ selectedTab }, EngineTab.entries.size) }
        val onTabSelected: (Int) -> Unit = { selectedTab = it }
        // 顶部分页（可在应用设置中关闭）：关闭时平铺展示全部引擎项
        // 随生命周期订阅：设置页盖在上面、本页不可见时不在后台重组（后台组合 / 销毁四个分页会占满主线程，
        // 让设置页开关的动画卡顿）；返回本页后再用最新值组合，分类栏弹簧展开 / 收起。
        val categorizeEngines by AppSettingsStore.engineTabsState.collectSettingAsState()
        // 标题区也可以长按唤出透镜来选分类（与分类栏同一个手势，共用 pager）
        AppTopBar(
            title = stringResource(R.string.nav_engine),
            modifier = Modifier.tabDragSelect(
                state = pager,
                enabled = categorizeEngines && MotionMode.enhanced,
                selectedIndex = { selectedTab },
                onSelect = onTabSelected,
            ),
        )
        // 分页开关在应用设置中切换后返回本页：分类栏弹簧展开 / 收起，下方列表被平滑推开
        ExpandVisibility(visible = categorizeEngines) {
            val tabs = listOf(
                stringResource(R.string.engine_tab_gal),
                stringResource(R.string.engine_tab_rpgm),
                stringResource(R.string.engine_tab_console),
                stringResource(R.string.engine_tab_web),
            )
            MiuixSettingsTheme {
                val tabModifier = Modifier
                    // 分类栏紧随顶栏入场（次序 1）
                    .staggeredEntrance(1, distance = 14.dp)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                // 统一用标签栏组件：静止外观与原 Miuix TabRow 一致（白色胶囊指示器、42dp 高、6dp 间距），
                // 切换时的 Q 弹「蠕动」指示器 / 文字弹一下 / 前后沿拉伸见 SlidingTabRow；玻璃系外观额外有描边。
                SlidingTabRow(
                    tabs = tabs,
                    selectedIndex = selectedTab,
                    onTabSelected = onTabSelected,
                    pager = pager,
                    modifier = tabModifier.tabDragSelect(
                        state = pager,
                        enabled = MotionMode.enhanced,
                        selectedIndex = { selectedTab },
                        onSelect = onTabSelected,
                        grabImmediately = true,
                    ),
                )
            }
        }

        // 每个分页各自的引擎列表（页面切换时新旧两页同时存在，所以按页取，不再共用一个）
        val enginesFor: (Int) -> List<EngineType> = { tab ->
            (if (categorizeEngines && tab >= 0) {
                engines.filter { engineTabOf(it) == EngineTab.entries[tab] }
            } else {
                engines
            }).distinctBy { engineDisplayName(it) }
        }
        // 当前页只通过这个 State 对象传递，顶层作用域本身不读 selectedTab：切页时只有真正读它的小作用域重组，
        // 不会把标题 / 分类栏 / 四个分页的网格一起重新执行（实测切页那一帧重组由 25ms 降到个位数）
        val activePageState = remember(categorizeEngines) { derivedStateOf { if (categorizeEngines) selectedTab else -1 } }
        val latestCoachTarget = remember(categorizeEngines, engines) { derivedStateOf { enginesFor(activePageState.value).firstOrNull() } }

        // 引擎列表：竖屏单列；横屏/大屏一行两个
        val engineColumns = if (isWideScreen()) 2 else 1
        // 增强档各分页提前组合好并常驻（与主界面四个 Tab 同一思路），切换时只对各页的图层做位移 / 透明度弹簧动画，
        // 完全没有现场组合、解码的开销，所以不会卡；旧页朝反方向滑走并淡出，新页带弹性滑入并淡入，方向与指示器一致。
        val pageKeys: List<Int> = if (categorizeEngines) EngineTab.entries.indices.toList() else listOf(-1)
        // 当前页立刻组合；仅增强档在稍后空闲时预组合其余分页。普通档始终只保留当前页。
        var composedPages by remember(categorizeEngines, enhanced) {
            mutableStateOf(setOf(Snapshot.withoutReadObservation { activePageState.value }))
        }
        LaunchedEffect(activePageState, enhanced) {
            if (enhanced) snapshotFlow { activePageState.value }.collect { composedPages = composedPages + it }
        }
        LaunchedEffect(categorizeEngines, enhanced) {
            if (!enhanced) return@LaunchedEffect
            delay(700)
            composedPages = composedPages + pageKeys
        }
        // 列表内容里不能直接捕获当前页的值：它一变，四个分页里所有条目都会跟着重组（切换那一帧的主要开销）；
        // 只捕获 activePageState 这个 State 对象，仅引导高亮那一个条目读它的值
        Box(Modifier.fillMaxSize().clipToBounds()) {
        pageKeys.forEach { pageTab ->
        if (if (enhanced) pageTab !in composedPages else pageTab != activePageState.value) return@forEach
        key(pageTab) {
        EnginePageHost(pageTab = pageTab, pager = pager, activePage = activePageState) {
        // 每页的列表固定下来（按 engines / 分页开关 / 页号为 key）：不固定的话每次重组都会生成一个内容相同的新 List，
        // 切换分页时顶层重组会把四个分页的网格都当成「内容变了」重新执行（实测切页那一帧重组 25ms）
        val tabEngines = remember(engines, categorizeEngines, pageTab) { enginesFor(pageTab) }
        // 滚动果冻：快速滚动时卡片沿滚动方向轻微拉长，停下 Q 弹复原（「普通」档不挂）
        val scrollJellyState = rememberScrollJellyState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(engineColumns),
            modifier = Modifier.fillMaxSize().scrollJellyHost(scrollJellyState),
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
                    modifier = Modifier.scrollJelly(scrollJellyState).staggeredEntrance(0),
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
                            // A replaced row must not clear the bounds reported by its new owner.
                            if (latestCoachTarget.value == engine || latestCoachTarget.value == null) {
                                latestOnCoachAnchorChanged.value(Rect.Zero)
                            }
                        }
                    }
                }
                val module = ExternalEngineModuleRegistry.resolveModule(engine, externalModules.renpyVersion)
                val emulator = ExternalEmulatorRegistry.forEngine(engine)
                val installed = when {
                    module != null -> externalModules.installed[module.id] == true
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
                        if (installed) R.string.engine_module_installed
                        else if (module.minimumVersionCode > 0) R.string.engine_module_install_or_update
                        else R.string.engine_module_not_installed

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
                        .scrollJelly(scrollJellyState)
                        .staggeredEntrance(engineIndex + 1)
                        .coachAnchor(
                            enabled = isCoachTarget && isActive && pageTab == activePageState.value,
                            onBounds = onCoachAnchorChanged,
                        )
                        .then(listItemMotion()),
                    showIcon = showEngineCardIcon,
                    onClick = {
                        when {
                            // 外置模拟器（PPSSPP / Eden / Winlator）：打开本引擎的运行时列表弹窗
                            emulator != null -> emulatorDialogEngine = engine
                            else -> moduleDialogEngine = engine
                        }
                    },
                )
            }
        }
        }
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
                    engineDialogEntries(context, externalModules.installed, dialogEngine).forEachIndexed { index, entry ->
                        AppNavItem(
                            title = entry.title,
                            // 弹窗正文内的条目在正文升起后再逐条落位（只改图层，停稳即原样）
                            modifier = Modifier.dialogEntryEntrance(index),
                            summary = stringResource(entry.summaryRes),
                            leadingIcon = R.drawable.ic_engine_chip,
                            containerColor = DialogItemSurface,
                        ) {
                            if (!entry.installed && entry.installUrl != null) {
                                val opened = ExternalEngineLauncher.openInstallPage(context, entry.installUrl)
                                if (!opened) {
                                    AppNotice.makeText(context, engineOpenDownloadFailedMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
                                }
                            }
                            moduleDialogEngine = null
                        }
                    }
                }
            },
            // 底部主操作：进入该引擎族的全局引擎设置页（长按钮）
            confirmButton = {
                EngineGlobalSettingsButton(engine = dialogEngine) { moduleDialogEngine = null }
            },
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
                    entries.forEachIndexed { index, target ->
                        val installed = emulatorInstallStates[target.packageName] == true
                        AppNavItem(
                            title = stringResource(target.displayNameRes),
                            modifier = Modifier.dialogEntryEntrance(index),
                            summary = stringResource(
                                if (installed) R.string.engine_emulator_installed else R.string.engine_emulator_not_installed,
                            ),
                            leadingIcon = R.drawable.ic_engine_chip,
                            containerColor = DialogItemSurface,
                        ) {
                            if (installed) {
                                ExternalEmulatorLauncher.openHome(context, target)
                            } else if (!ExternalEngineLauncher.openInstallPage(context, target.installUrl)) {
                                AppNotice.makeText(context, engineOpenDownloadFailedMessage, Toast.LENGTH_SHORT, AppNotice.Kind.Failure).show()
                            }
                            emulatorDialogEngine = null
                        }
                    }
                }
            },
            // 底部主操作：进入该引擎族的全局引擎设置页（长按钮）
            confirmButton = {
                EngineGlobalSettingsButton(engine = dialogEngine) { emulatorDialogEngine = null }
            },
        )
    }
}

/**
 * 引擎条目弹窗底部的长按钮：直接进入该引擎族的全局引擎设置页。
 * 无全局设置入口的引擎（Switch / 第三方运行时模块 / 安卓游戏 / 未知）不渲染。
 */
@Composable
private fun EngineGlobalSettingsButton(
    engine: EngineType,
    onNavigate: () -> Unit,
) {
    val context = LocalContext.current
    val kind = engineSettingsKindOf(engine) ?: return
    NoRippleButton(
        text = stringResource(R.string.engine_global_settings),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onNavigate()
            startActivityWithPageTransition(context, EngineSettingsActivity.createIntent(context, kind))
        },
    )
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
                        // 游戏目录大小统计中：这一行缓缓明灭，算完随模糊替换停在原样
                        modifier = Modifier.breathing(gameBytesLoading),
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
    val sweep = rememberStorageSweep(if (loading) 0f else safeRatio)
    Box(modifier.size(92.dp), contentAlignment = Alignment.Center) {
        // 统计中只画轨道：轨道与「…」一起缓缓明灭，算完后进度弧再从 0 扫过去
        Canvas(Modifier.fillMaxSize().breathing(loading)) {
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
                    sweepAngle = 360f * sweep().coerceIn(0f, 1f),
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
                modifier = Modifier.breathing(loading),
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

/**
 * 弹窗正文内的条目：标题(0) / 正文(1) 之后按次序逐条轻轻升起（距离短于页面卡片）。
 * 依赖 AppAlertDialog 自带的入场节拍；没有节拍或「普通」档时与原样完全一致。
 */
private fun Modifier.dialogEntryEntrance(index: Int): Modifier =
    this.staggeredEntrance(index + 2, distance = 10.dp, stepMillis = 45L)

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
    showIcon: Boolean = true,
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
        showIcon = showIcon,
        onClick = onClick,
    )
}

/**
 * 原引擎条目布局：左侧引擎图标横幅（右侧渐变融入卡片）+ 标题 + 右侧原状态图标。
 * 高度 60dp；两列紧凑模式下横幅收窄到 88dp；应用设置可关闭横幅，仅保留名称与状态图标。
 */
@Composable
private fun EngineRowCard(
    @DrawableRes iconRes: Int,
    title: String,
    @StringRes statusTextRes: Int,
    installed: Boolean,
    enabled: Boolean,
    compact: Boolean,
    showIcon: Boolean,
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
            .pressSquish(enabled = enabled, scale = PressSquishDefaults.CardScale, refuse = true)
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
            if (showIcon) {
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
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (showIcon) 12.dp else 16.dp, end = 8.dp),
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
    EngineType.CMVS -> R.drawable.ic_engine_card_cmvs
    EngineType.RENPY -> R.drawable.ic_engine_card_renpy
    EngineType.RUFFLE -> R.drawable.ic_engine_card_ruffle
    EngineType.YURIS -> R.drawable.ic_engine_card_yuris
    EngineType.CATSYSTEM2 -> R.drawable.ic_engine_card_catsystem2
    EngineType.PC -> R.drawable.ic_engine_card_pc
    EngineType.PSP -> R.drawable.ic_engine_card_psp
    EngineType.NINTENDO_SWITCH -> R.drawable.ic_engine_card_switch
    EngineType.SYSTEMNNN, EngineType.ANDROID_APP, EngineType.RUNTIME_MODULE, EngineType.UNKNOWN -> R.drawable.ic_engine_icon
}

/** 引擎页顶部分页（顺序即展示顺序）：GAL / RPGM / 主机 / 网页。 */
/**
 * 一个分页的外壳：只改图层的位移 / 透明度（读 [TabPagerState.pageOffset]），非当前页及未停稳页屏蔽触摸与无障碍。
 * 单独成函数是为了让「当前页」这个会在切页时变化的值只在这里被读取——切页时只有这四个小外壳重组，
 * 里面的网格内容（[content]）参数不变，被整体跳过。
 */
@Composable
internal fun EnginePageHost(
    pageTab: Int,
    pager: TabPagerState,
    activePage: State<Int>,
    content: @Composable BoxScope.() -> Unit,
) {
    // 隐藏分页不把内容画进显示列表（见 skipDrawWhenHidden）：只在显隐边界翻转，不读逐帧变化的 alpha
    val shown by remember(pager, pageTab) { derivedStateOf { pageTab >= 0 && pager.pageAlpha(pageTab) > HiddenPageAlpha } }
    val settled by remember(pager, pageTab) {
        derivedStateOf {
            pageTab < 0 || (kotlin.math.abs(pager.pageOffset(pageTab)) < 0.02f && pager.pageAlpha(pageTab) > 0.98f)
        }
    }
    val interactive = pageTab == activePage.value && (!MotionMode.enhanced || settled)
    Box(
        Modifier
            .fillMaxSize()
            // 当前页必须在最上层：几个分页叠在同一个 Box 里，命中测试从最上层的兄弟开始，
            // 排在后面的「非当前页」挡触摸层会把压在它下面的当前页的触摸整片吃掉（列表滑不动、卡片点不了）
            .zIndex(if (pageTab == activePage.value) 1f else 0f)
            // 与主界面底栏切页相同的效果：整宽水平弹簧位移 + 淡入淡出弹簧 + 景深缩放（停稳时全部恒等）。
            // 偏移 / 透明度只读 TabPagerState 里「这一页」自己的值：选中变化时只有旧页（退场）和新页（入场）在动，
            // 跨几个标签也不会让中间的分页闪过。不用离屏图层合成透明度：逐元素调制 alpha（modulateAlpha）
            .tabPageTransform(
                key = pager to pageTab,
                offset = { if (pageTab >= 0) pager.pageOffset(pageTab) else 0f },
                alpha = { if (pageTab >= 0) pager.pageAlpha(pageTab) else 1f },
                modulateAlpha = true,
            )
            .then(
                if (MotionMode.enhanced) Modifier.skipDrawWhenHidden { shown || pageTab == activePage.value } else Modifier,
            )
            .then(
                if (!interactive) {
                    // Draw-phase movement does not move hit targets; expose only the settled page.
                    Modifier
                        .clearAndSetSemantics { }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

private enum class EngineTab { GAL, RPGM, CONSOLE, WEB }

private fun engineTabOf(engine: EngineType): EngineTab = when (engine) {
    EngineType.RPGMAKER, EngineType.RPG_MV, EngineType.RPG_MZ -> EngineTab.RPGM
    EngineType.KIRIKIRI, EngineType.ONS, EngineType.ARTEMIS, EngineType.SIGLUS,
    EngineType.REALLIVE, EngineType.AVG32, EngineType.XSYS35, EngineType.XSYS4, EngineType.UK2, EngineType.FVP,
    EngineType.ETHORNELL, EngineType.CMVS, EngineType.SYSTEMNNN,
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
    EngineType.SYSTEMNNN to listOf(builtinEntry("internal.systemnnn", "SystemNNN-Emberfall 0.6.14")),
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
        builtinEntry("xsys4-46f7c25", "xsystem4-46f7c25"),
    ),
    EngineType.FVP to listOf(
        builtinEntry("fvp-rfvp", "rfvp-xmoezzz"),
    ),
    EngineType.CMVS to listOf(
        builtinEntry("cmvs-rs", "cmvs-rs"),
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
                summaryRes = if (installed) R.string.engine_module_installed
                    else if (module.minimumVersionCode > 0) R.string.engine_module_install_or_update
                    else R.string.engine_module_not_installed,
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

/** 列表和版本弹窗共用这一次刷新的安装结果，Ren'Py 列表行只看当前配置的版本。 */
private data class ExternalModuleStatus(val renpyVersion: String, val installed: Map<String, Boolean>)

private fun readExternalModuleStatus(context: Context) = ExternalModuleStatus(
    EngineSettingsStore.getRenpyVersion(context),
    ExternalEngineModuleRegistry.modules.associate { module ->
        module.id to ExternalEngineLauncher.isPackageInstalled(context, module)
    },
)

/** 外置主机模拟器安装状态（按包名）。 */
private fun refreshEmulatorInstallStates(context: android.content.Context): Map<String, Boolean> =
    ExternalEmulatorRegistry.targets.associate { target ->
        target.packageName to ExternalEmulatorLauncher.isInstalled(context, target)
    }
