package com.tyranor.next.ui.main

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.DialogTextButton
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.engine.runtime.RuntimeModuleBindings
import com.tyranor.next.core.engine.runtime.RuntimeModuleRegistry
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DefaultPageBackgroundLayer
import com.tyranor.next.theme.GlassNavSurface
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassPageBackground
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.archive.ArchiveUnpackActivity
import com.tyranor.next.ui.common.LiquidGlassNavItem
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppNavigationRail
import com.tyranor.next.ui.common.AppSidePanel
import com.tyranor.next.ui.common.FloatingGlassNavButton
import com.tyranor.next.ui.common.FloatingNavOutsideTapBridge
import com.tyranor.next.ui.common.observeFloatingNavOutsideTaps
import com.tyranor.next.ui.common.LaunchErrorDialog
import com.tyranor.next.ui.common.LaunchErrorState
import com.tyranor.next.core.game.launch.LaunchFailureBus
import com.tyranor.next.ui.common.LiquidGlassNavigationBar
import com.tyranor.next.ui.common.NavigationTabIcon
import com.tyranor.next.ui.common.SidePanelEdgeExclusionBar
import com.tyranor.next.ui.common.effectiveNavStyle
import com.tyranor.next.ui.common.isSideRailLayout
import com.tyranor.next.ui.common.rememberSidePanelSwipeModifier
import com.tyranor.next.ui.common.syncErrorResource
import com.tyranor.next.ui.common.toErrorState
import com.tyranor.next.ui.common.glass.EnhancedLiquidGlassNavigationBar
import com.tyranor.next.ui.common.glass.GlassShaderSupport
import com.tyranor.next.ui.common.glass.GlassBottomBarSpec
import com.tyranor.next.ui.common.glass.rememberGlassBottomBarColors
import com.tyranor.next.theme.WithoutPressIndication
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.engine.EngineScreen
import com.tyranor.next.ui.game.GameScreen
import com.tyranor.next.ui.game.startActivityWithPageTransition
import com.tyranor.next.ui.home.HomeScreen
import com.tyranor.next.ui.save.WebDavSettingsActivity
import com.tyranor.next.ui.runtime.EngineModuleManagementActivity
import com.tyranor.next.ui.settings.InputSettingsActivity
import com.tyranor.next.ui.settings.SettingsScreen
import com.tyranor.next.ui.common.CoachAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 底部导航栏 Tab 定义
@Immutable
private data class Tab(
  @StringRes val labelRes: Int,
  val iconRes: Int,
)

private val tabItems = listOf(
  Tab(R.string.nav_home, R.drawable.ic_home),
  Tab(R.string.nav_games, R.drawable.ic_game),
  Tab(R.string.nav_engine, R.drawable.ic_module),
  Tab(R.string.nav_settings, R.drawable.ic_settings),
)

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  coachPage: Int? = null,
  onCoachAnchorChanged: (Int, Rect) -> Unit = { _, _ -> },
) {
  val context = LocalContext.current
  var globalLaunchError by remember { mutableStateOf<LaunchErrorState?>(null) }
  val busFailure by LaunchFailureBus.failure.collectAsState()
  LaunchedEffect(busFailure) {
    busFailure?.let {
      globalLaunchError = it.toErrorState(context)
      LaunchFailureBus.clear()
    }
  }
  var selectedIndex by rememberSaveable { mutableStateOf(0) }
  LaunchedEffect(coachPage) {
    if (coachPage != null) selectedIndex = coachPage.coerceIn(tabItems.indices)
  }
  // 引导期间由 coachPage 临时接管当前页；其余时间使用用户选择的 selectedIndex。
  // 所有转场、导航和页面交互都使用同一个 activeIndex，避免首帧出现双索引错位。
  val activeIndex = coachPage?.coerceIn(tabItems.indices) ?: selectedIndex
  val libraryViewModel: MainLibraryViewModel = viewModel()
  val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
  val cleanupError = libraryState.cleanupError
  if (cleanupError != null) {
    val cleanupReason = stringResource(cleanupError.syncErrorResource())
    AppAlertDialog(
      onDismissRequest = libraryViewModel::dismissCleanupError,
      title = { Text(stringResource(R.string.save_operation_failed), style = MaterialTheme.typography.titleMedium) },
      text = { Text(stringResource(R.string.save_sync_cleanup_failed, libraryState.cleanupGameTitle.orEmpty(), cleanupReason),
        style = MaterialTheme.typography.bodyMedium) },
      textScrollEnabled = true,
      confirmButton = {
        DialogTextButton(stringResource(R.string.common_confirm), onClick = libraryViewModel::dismissCleanupError)
      },
    )
  }
  val interactScope = rememberCoroutineScope()
  val unselectedColor = UnselectedGrey
  // 导航栏样式：应用设置 → 默认 / 液态玻璃 · 经典 / 液态玻璃 · 透镜（内存态，设置页切换即时生效）
  LaunchedEffect(Unit) {
    // initNavStyle 是幂等的（设置页也会调用一次），这里与游戏排序一起在 IO 线程读一次 prefs
    val gameSort = withContext(Dispatchers.IO) {
      AppSettingsStore.initNavStyle(context)
      AppSettingsStore.getGameSort(context)
    }
    AppSettingsStore.gameSortState.value = gameSort
    withContext(Dispatchers.IO) { AppSettingsStore.initEngineTabs(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initEngineCardIcon(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initSideRail(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initGameCardHideTitleTag(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initGameCardBadge(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initGameCardStyle(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initHomeStyle(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initFloatingNavPosition(context) }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    // 库刷新（含 xsys35 标题回写的前台补导入，见 MainLibraryViewModel.onForeground）
    libraryViewModel.onForeground()
    // 存档互通前台兜底：对已退出会话的待回写游戏补一次 Tyranor→标准同步
    // （引擎退出后 500ms 强杀、无回调，故在应用回到前台时补齐）。
    interactScope.launch {
      // runCatching 会把协程取消也当作失败吞掉，故显式区分：取消原样传播，其余仅记日志
      try {
        EngineLauncher.flushPendingSaveSync(context)
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        android.util.Log.w("MainScreen", "pending RPG save sync failed", t)
      }
    }
    // 第三方运行时模块：卸载/禁用后把绑定游戏降级为 UNKNOWN；同时刷新模块 label 缓存。
    interactScope.launch {
      try {
        // 给安装器/包替换留出稳定窗口，避免模块更新瞬间把全部绑定误降级。
        delay(1500)
        val degraded = RuntimeModuleBindings.reconcile(context)
        RuntimeModuleRegistry.refresh(context)
        libraryViewModel.refreshFromStorage()
        if (degraded > 0) {
          android.widget.Toast.makeText(
            context,
            R.string.engine_module_management_degraded_toast,
            android.widget.Toast.LENGTH_SHORT,
          ).show()
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        android.util.Log.w("MainScreen", "runtime module reconcile failed", t)
      }
    }
  }
  val navStyle = effectiveNavStyle()
  // ===== 侧边栏（仅四大页面可唤出）：左缘右滑 / 左边缘返回手势打开 =====
  // 面板关闭时把「左边缘返回手势」解读为唤出侧边栏；右边缘/返回键保持系统默认（退出）。
  // 页面级 BackHandler（如 Web 首页回退历史、悬浮按钮展开态）在更晚的组合位置注册，
  // 按返回分发「后注册优先」规则仍优先生效。
  var sidePanelOpen by rememberSaveable { mutableStateOf(false) }
  // 侧边栏条目：内置原生 Kirikiroid2 界面启动失败提示（原引擎设置 KRKR 页的功能迁入）
  var sidePanelLaunchError by remember { mutableStateOf<LaunchErrorState?>(null) }
  val hostActivity = AppLocaleController.findActivity(context)
  PredictiveBackHandler(enabled = !sidePanelOpen) { progress ->
    var fromLeftEdge = false
    progress.collect { event ->
      if (event.swipeEdge == BackEventCompat.EDGE_LEFT) fromLeftEdge = true
    }
    if (fromLeftEdge) sidePanelOpen = true else hostActivity?.finish()
  }
  AppSidePanel(open = sidePanelOpen, onDismiss = { sidePanelOpen = false }) {
    // 妙妙工具条目：启动内置原生 Kirikiroid2 界面（文件浏览器，不启动具体游戏）
    AppNavItem(
      title = stringResource(R.string.side_panel_kr2_native_title),
      leadingIcon = R.drawable.ic_settings_engine,
      containerColor = DialogItemSurface,
      leadingIconTint = MaterialTheme.colorScheme.primary,
      onClick = {
        sidePanelOpen = false
        interactScope.launch {
          val result = withContext(Dispatchers.IO) {
            EngineLauncher.launchNativeKirikiroidUi(context)
          }
          sidePanelLaunchError = result.toErrorState(context)
        }
      },
    )
    // 妙妙工具条目：解包 / 封包工具（原设置页「解包 / 封包」入口迁入）
    AppNavItem(
      title = stringResource(R.string.side_panel_archive_tool_title),
      leadingIcon = R.drawable.ic_sheet_archive,
      containerColor = DialogItemSurface,
      leadingIconTint = MaterialTheme.colorScheme.primary,
      onClick = {
        sidePanelOpen = false
        startActivityWithPageTransition(context, ArchiveUnpackActivity.createIntent(context))
      },
    )
    AppNavItem(
      title = stringResource(R.string.webdav_title),
      leadingIcon = R.drawable.ic_save_cloud,
      containerColor = DialogItemSurface,
      leadingIconTint = MaterialTheme.colorScheme.primary,
      onClick = {
        sidePanelOpen = false
        startActivityWithPageTransition(context, WebDavSettingsActivity.createIntent(context))
      },
    )
    // 妙妙工具条目：输入与手柄（原设置页「输入与手柄」入口迁入）
    AppNavItem(
      title = stringResource(R.string.side_panel_input_tool_title),
      leadingIcon = R.drawable.ic_input_gamepad,
      containerColor = DialogItemSurface,
      leadingIconTint = MaterialTheme.colorScheme.primary,
      onClick = {
        sidePanelOpen = false
        startActivityWithPageTransition(context, InputSettingsActivity.createIntent(context))
      },
    )
    // 妙妙工具条目：引擎模块管理（第三方 APK 运行时模块的发现与启用）
    AppNavItem(
      title = stringResource(R.string.side_panel_engine_module_title),
      leadingIcon = R.drawable.ic_engine_manage,
      containerColor = DialogItemSurface,
      leadingIconTint = MaterialTheme.colorScheme.primary,
      onClick = {
        sidePanelOpen = false
        startActivityWithPageTransition(context, EngineModuleManagementActivity.createIntent(context))
      },
    )
  }
  // 首页样式：Web 首页参与绘制期间底栏改用仅背景采样源（见 webHomeInvolved）
  val homeStyle by AppSettingsStore.homeStyleState.collectAsState()
  // 液态玻璃两档共用同一套宿主准备（录制采样层、转场重定向、底部留白）
  val liquidGlass = navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS ||
    navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED
  val enhanceLiquidGlass = navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED
  // 平板/大窗口：主导航移到侧边。侧栏按外观风格取「默认导航栏」形态，
  // 液态玻璃两档（尤其透镜）不参与侧栏适配，平板下自动落到主题默认形态。
  val floatingButtonStyle = navStyle == AppSettingsStore.NAV_STYLE_FLOATING_BUTTON
  val railLayout = isSideRailLayout() && !floatingButtonStyle
  val liquidBottomActive = liquidGlass && !railLayout
  // 悬浮导航在手机、平板共用圆球交互，安全区与扇形尺寸由组件自适应。
  val floatingButtonActive = floatingButtonStyle && !railLayout
  val floatingNavOutsideTapBridge = remember { FloatingNavOutsideTapBridge() }
  val floatingNavOutsideTapObserver = remember(floatingNavOutsideTapBridge) {
    Modifier.observeFloatingNavOutsideTaps(floatingNavOutsideTapBridge)
  }
  // 侧边栏唤出识别需跳过底部导航栏区域：各底栏形态在布局后把自己顶边（根布局坐标）上报到
  // bottomNavTopY；悬浮按钮 / 平板侧栏没有底部导航栏，恒按「无排除」处理。
  var bottomNavTopY by remember { mutableStateOf(Float.POSITIVE_INFINITY) }
  val reportBottomNavTop = remember {
    Modifier.onGloballyPositioned { bottomNavTopY = it.positionInParent().y }
  }
  // 左缘右滑唤出：挂在根布局上（子内容优先，不遮挡任何点击/滚动）；
  // 起点落在底部导航栏区域内时不识别（见 rememberSidePanelSwipeModifier 的 bottomBarTopY）
  val sidePanelSwipeModifier = rememberSidePanelSwipeModifier(
    onOpen = { sidePanelOpen = true },
    bottomBarTopY = {
      if (railLayout || floatingButtonActive) Float.POSITIVE_INFINITY else bottomNavTopY
    },
    excludeStart = { point ->
      floatingButtonActive && floatingNavOutsideTapBridge.containsControls(point)
    },
  )
  // 玻璃外观风格 + 默认导航样式：导航栏改为悬浮的圆角玻璃条（描边 + 玻璃底）
  val floatingDefaultNav = AppThemeColors.isGlass && !liquidGlass && !floatingButtonStyle && !railLayout
  val tabLabels = tabItems.map { stringResource(it.labelRes) }
  // remember(tabLabels)：labels 内容不变时复用同一份 items，避免每次重组都给增强栏传新 List
  // （增强栏据此跳过重组，进而避免 drawBackdrop 元素被判不等而重建 RenderEffect 管线）
  val liquidGlassTabItems = remember(tabLabels) {
    tabItems.mapIndexed { index, tab -> LiquidGlassNavItem(tabLabels[index], tab.iconRes) }
  }

  // 采样层可用条件：液态玻璃两档或悬浮按钮（底栏形态）
  val backdropSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  val bottomBackdropActive = (liquidBottomActive || floatingButtonActive) &&
    backdropSupported
  // 悬浮按钮的采样层是否真的挂载（API < 31 时组件退实底，无需背景复制）
  val floatingButtonBackdrop = floatingButtonActive && bottomBackdropActive
  // 透镜档**是否真的会渲染**：设置选了它 + 采样层可用 + 本机 AGSL 可用。
  // 后者是运行期探测（见 GlassShaderSupport）：API 33+ 但 AGSL 编译异常的机器上，
  // Backdrop 内部的 RuntimeShader 会在布局期抛出并崩掉整个主界面，此时退回经典档更安全。
  // 注意：回退时经典档也会被要求不传 Highlight，否则兜底路径自己仍依赖 RuntimeShader。
  val enhancedBarActive = enhanceLiquidGlass && liquidBottomActive && bottomBackdropActive &&
    GlassShaderSupport.isRuntimeShaderUsable

  val pageTransition = updateTransition(
    targetState = activeIndex,
    label = "mainTabTransition",
  )
  fun selectPage(index: Int) {
    // 索引保护：宿主可能收到越界请求（例如 items 变化后的晚到回调）
    if (index !in tabItems.indices) return
    if (index == activeIndex) return
    // 透镜档需要「转场期间接受新目标」：透镜已经跟手移动，页面却不动会明显脱节。
    // 这里判的是**实际生效的档**（enhancedBarActive）而不是设置值：AGSL 不可用时界面已回退成
    // 经典档，转场语义必须跟着回退，否则会出现「看着是经典档、行为是透镜档」的错配。
    // 默认路径保持原有守卫，避免把这一行为变更带给未开启该选项的用户（报告 §8.7）。
    if (!enhancedBarActive && !floatingButtonActive && pageTransition.isRunning) return
    selectedIndex = index
  }
  // 外层只负责布局：内容区 + 底部导航栏（不用 Scaffold，避免与子页顶部栏的 inset 冲突）
  Box(
    modifier.fillMaxSize().then(sidePanelSwipeModifier)
      .then(if (floatingButtonActive) floatingNavOutsideTapObserver else Modifier),
  ) {
    // 内容层录制进 backdrop，供液态玻璃导航采样页面内容。
    // 关键：背景必须在 layerBackdrop 之后（内层）——layerBackdrop 只录制它之后的内容，
    // 放在外层（Surface/Column 背景）的内容不会被采样，玻璃会采到透明而漏出文字。
    val backdrop = rememberLayerBackdrop()
    // Web 首页场景的「仅背景」采样层（见下方 webHomeInvolved）：只含页面背景、不含页面内容，
    // 避免 WebView 被录进采样层并逐帧离屏重放。
    val backgroundBackdrop = rememberLayerBackdrop()
    // 采样修复（报告 §5.3 / §8.4 第 1 条）：玻璃外观风格下 PageGrey 透明，根部背景在采样层之外，
    // 透镜档把同一份背景画进被采样内容，保证采样源含完整背景且坐标同源。
    // remember：MainScreen 在切页动画期间每帧重组，复用同一 Modifier 才不会每帧重建绘制缓存。
    val accent = AppThemeColors.primary
    val pageBackground = remember(accent) {
      Modifier.glassPageBackground(accent)
    }
    // 玻璃系需要把页面背景并入采样源的场景（增强透镜 / 悬浮按钮）；
    // 平板侧栏不适用（导航在侧边，不采样内容层）。
    val glassSamplingBackground = AppThemeColors.isGlass &&
      (enhancedBarActive || floatingButtonBackdrop)
    // ===== Web 首页采样降级（防切页闪烁） =====
    // Web 首页参与绘制（前台，或转场起点/终点）时底栏不得采样内容层：layerBackdrop 每个绘制帧
    // 把内容重新录进采样层，而 Compose 的 AndroidView（WebView）经 view.draw 一并入录，底栏/透镜
    // 重放即让 Chromium 把同一帧多次画进不同离屏 FBO。真机实测透镜档每次切页都触发
    // `tile memory limits exceeded, some content may not draw`，网页内容被丢弃而抖动闪烁。
    // 该场景改用仅含页面背景的 backgroundBackdrop：Web 首页在 glassNavBottomInset() 下留有底部
    // 空白，导航栏底下本来就只有页面背景，采样内容零视觉价值；Web 首页不再参与绘制后立即恢复。
    // currentState/targetState 只在转场起止变化（currentState 保持起点直到转场结束），不逐帧重组。
    val webHomeInvolved = bottomBackdropActive &&
      homeStyle == AppSettingsStore.HOME_STYLE_WEB &&
      (activeIndex == 0 || pageTransition.currentState == 0 || pageTransition.targetState == 0)
    // 底栏采样源：Web 首页参与绘制时为仅背景层，其余为内容层（两者同一坐标空间，可直接替换）。
    val navBackdrop = if (webHomeInvolved) backgroundBackdrop else backdrop
    // 仅背景层：只在 Web 首页参与绘制时挂载，供底栏采样（其余情况由根部 GlassBackground 绘制，
    // 避免同一份动画背景在根层与内容层各跑一遍）。
    if (webHomeInvolved) {
      Box(
        Modifier
          .fillMaxSize()
          .layerBackdrop(backgroundBackdrop)
          .then(if (glassSamplingBackground) pageBackground else Modifier),
      ) {
        DefaultPageBackgroundLayer()
      }
    }
    val contentLayerModifier = Modifier
      // source 节点常驻，避免切页结束时重新挂载玻璃录制层；Web 首页参与绘制时改由
      // backgroundBackdrop 采样（见上），不把内容（含 WebView）录进任何采样层。
      .then(if (bottomBackdropActive && !webHomeInvolved) Modifier.layerBackdrop(backdrop) else Modifier)
      .then(
        // 仅玻璃外观风格需要（该模式下 PageGrey 透明、根部背景在采样层之外）；
        // Web 首页场景背景已移到仅背景层，此处不再重复画，避免半透明光晕叠加增强。
        if (glassSamplingBackground && !webHomeInvolved) {
          pageBackground
        } else {
          Modifier
        },
      )
      // 玻璃系：PageGrey（透明）兜底；默认风格由根部背景层绘制（此处不再铺不透明纯色）
      .then(
        if (AppThemeColors.isGlass) {
          Modifier.background(MaterialTheme.colorScheme.background)
        } else {
          Modifier
        },
      )
    if (railLayout) {
      // 平板/大窗口：主导航移到侧边，按外观风格取该主题「默认导航栏」形态
      Row(Modifier.fillMaxSize()) {
        AppNavigationRail(
          selectedIndex = activeIndex,
          items = liquidGlassTabItems,
          onItemClick = { selectPage(it) },
          modifier = Modifier.fillMaxHeight(),
        )
        Column(Modifier.weight(1f).fillMaxHeight().then(contentLayerModifier)) {
          MainTabPages(
            selectedIndex = activeIndex,
            pageTransition = pageTransition,
            onboarding = coachPage != null,
            libraryState = libraryState,
            libraryViewModel = libraryViewModel,
            onCoachAnchorChanged = onCoachAnchorChanged,
          )
        }
      }
    } else {
      Column(Modifier.fillMaxSize().then(contentLayerModifier)) {
        MainTabPages(
          selectedIndex = activeIndex,
          pageTransition = pageTransition,
          onboarding = coachPage != null,
          libraryState = libraryState,
          libraryViewModel = libraryViewModel,
            onCoachAnchorChanged = onCoachAnchorChanged,
        )
        if (!liquidGlass && !floatingDefaultNav && !floatingButtonActive) {
          DefaultBottomNavigationBar(
            selectedIndex = activeIndex,
            tabLabels = tabLabels,
            unselectedColor = unselectedColor,
            onSelectPage = { selectPage(it) },
            modifier = reportBottomNavTop,
          )
        }
      }
    }

    // 液态玻璃 · 经典：悬浮在内容之上。
    // 透镜档（应用设置 → 导航栏样式 → 液态玻璃 · 透镜）改用三层采样 + 折射透镜；默认与经典档走原实现。
    if (liquidBottomActive) {
      // 透镜档必须要有可用采样层（backdropAvailable 已含 API 门槛与液态玻璃条件）；
      // 万一不满足则退回经典档，而不是拿未挂载的 backdrop 渲染（组件契约要求）
      if (enhancedBarActive) {
        EnhancedLiquidGlassNavigationBar(
          backdrop = navBackdrop,
          // 权威选中值先夹取到合法槽位，避免越界值把透镜放到栏外（组件内部也会再夹一次）
          selectedIndex = activeIndex.coerceIn(liquidGlassTabItems.indices),
          colors = rememberGlassBottomBarColors(unselectedColor),
          items = liquidGlassTabItems,
          onItemClick = { selectPage(it) },
          modifier = reportBottomNavTop
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = GlassBottomBarSpec.Default.hostBottomPadding),
        )
      } else {
        LiquidGlassNavigationBar(
          backdrop = navBackdrop,
          selectedIndex = activeIndex,
          primaryColor = MaterialTheme.colorScheme.primary,
          unselectedColor = unselectedColor,
          items = liquidGlassTabItems,
          onItemClick = { selectPage(it) },
          modifier = reportBottomNavTop.align(Alignment.BottomCenter),
        )
      }
    }

    // 玻璃外观风格下的默认导航栏：悬浮圆角玻璃条（玻璃底 + 0.5dp 描边 + 应用圆角），
    // 内容可从其下方滚过，列表底部留白由 glassNavBottomInset() 统一提供。
    if (floatingDefaultNav) {
      Box(
        modifier = reportBottomNavTop
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 12.dp, vertical = 12.dp),
      ) {
        DefaultBottomNavigationBar(
          selectedIndex = activeIndex,
          tabLabels = tabLabels,
          unselectedColor = unselectedColor,
          onSelectPage = { selectPage(it) },
          modifier = Modifier
            .fillMaxWidth()
            // 无文字后按图标高度收窄导航条（64dp）；圆角取「应用圆角调整」值
            .height(64.dp)
            .clip(AppComponentShape)
            .glassBorder(AppComponentShape),
          windowInsets = WindowInsets(0.dp),
          // 复古玻璃的更实玻璃底 + 只显示图标
          containerColor = GlassNavSurface,
          showLabels = false,
        )
      }
    }

    // 悬浮按钮导航：右下角一个圆形液态玻璃按钮，点击后四项沿 1/4 圆弧交错展开；
    // 悬浮不占布局高度，内容底部留白由 glassNavBottomInset() 统一提供。
    if (floatingButtonActive) {
      FloatingGlassNavButton(
        backdrop = if (floatingButtonBackdrop) navBackdrop else null,
        items = liquidGlassTabItems,
        selectedIndex = activeIndex.coerceIn(liquidGlassTabItems.indices),
        primaryColor = MaterialTheme.colorScheme.primary,
        onItemClick = { selectPage(it) },
        // 组件在安全区内自行摆放（长按可拖动、位置持久化），宿主只负责占满可用区域
        modifier = Modifier.fillMaxSize(),
        outsideTapBridge = floatingNavOutsideTapBridge,
      )
    }

    // 侧边栏唤出入口：左缘系统返回手势排除条（无指针输入不拦截事件；识别逻辑在根布局的
    // sidePanelSwipeModifier 上，靠边排除区外的返回手势由 PredictiveBackHandler 兜底）
    SidePanelEdgeExclusionBar(
      modifier = Modifier.align(Alignment.CenterStart),
    )
  }

  // 侧边栏条目启动失败弹窗（与首页/游戏页共用同一个错误弹窗组件）
  sidePanelLaunchError?.let { state ->
    LaunchErrorDialog(state = state, onDismiss = { sidePanelLaunchError = null })
  }
  globalLaunchError?.let { state ->
    LaunchErrorDialog(state = state, onDismiss = { globalLaunchError = null })
  }
}

/**
 * 主界面四页常驻组合：隐藏页只保留已测量节点且 alpha=0，切换时不再重建游戏网格/Miuix 设置页；
 * 中间 Tab 的 alpha 在任意状态均为 0，跨页时只显示起点和终点的直接水平动画。
 * 底栏形态与平板侧栏形态共用本组件，保证两种布局下的页面与转场完全一致。
 */
@Composable
private fun ColumnScope.MainTabPages(
  selectedIndex: Int,
  pageTransition: Transition<Int>,
  onboarding: Boolean,
  libraryState: MainLibraryUiState,
  libraryViewModel: MainLibraryViewModel,
  onCoachAnchorChanged: (Int, Rect) -> Unit,
) {
    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
      WithoutPressIndication {
        // 四页常驻组合：隐藏页只保留已测量节点且 alpha=0，切换时不再重建游戏网格/Miuix 设置页。
        // 中间 Tab 的 alpha 在任意状态均为 0，因此跨页时只显示起点和终点的直接水平动画。
        tabItems.indices.forEach { page ->
          val pageOffset by pageTransition.animateFloat(
            transitionSpec = { tween(durationMillis = if (onboarding) 80 else 200) },
            label = "mainTabOffset$page",
          ) { activePage ->
            when {
              page < activePage -> -1f
              page > activePage -> 1f
              else -> 0f
            }
          }
          val pageAlpha by pageTransition.animateFloat(
            transitionSpec = { tween(durationMillis = if (onboarding) 80 else 160) },
            label = "mainTabAlpha$page",
          ) { activePage -> if (page == activePage) 1f else 0f }
          val pageActive = page == selectedIndex && !pageTransition.isRunning
          val pageInteractive = pageActive
          Box(
            Modifier
              .fillMaxSize()
              .zIndex(if (page == selectedIndex) 1f else 0f)
              .graphicsLayer {
                translationX = pageOffset * size.width
                alpha = pageAlpha
              }
              .then(
                if (pageInteractive) {
                  Modifier
                } else {
                  Modifier
                    .clearAndSetSemantics { }
                    .pointerInput(page) {
                      awaitPointerEventScope {
                        while (true) {
                          awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                      }
                    }
                },
              ),
          ) {
            when (page) {
              0 -> HomeScreen(
                modifier = Modifier.fillMaxSize(),
                libraryState = libraryState,
                onGameUpdated = libraryViewModel::replaceGame,
                onGameDeleted = libraryViewModel::deleteGame,
                onRecentRemoved = libraryViewModel::removeRecentGame,
                onQuickLaunchToggle = libraryViewModel::toggleQuickLaunch,
                onCoachAnchorChanged = { onCoachAnchorChanged(CoachAnchor.HOME, it) },
                isActive = pageActive,
              )
              1 -> GameScreen(
                modifier = Modifier.fillMaxSize(),
                libraryState = libraryState,
                onGameUpdated = libraryViewModel::replaceGame,
                onGameDeleted = libraryViewModel::deleteGame,
                onQuickLaunchToggle = libraryViewModel::toggleQuickLaunch,
                onScanLibrary = libraryViewModel::scanLibrary,
                onScrapeEventShown = libraryViewModel::acknowledgeScrapeEvent,
                onSearchQueryChanged = libraryViewModel::onSearchQueryChanged,
                onAddManualGame = libraryViewModel::addManualGame,
                onCoachAnchorChanged = onCoachAnchorChanged,
                isActive = pageActive,
              )
              2 -> EngineScreen(
                Modifier.fillMaxSize(),
                onCoachAnchorChanged = { onCoachAnchorChanged(CoachAnchor.ENGINE, it) },
                isActive = pageActive,
                gameCount = libraryState.games.size,
              )
              3 -> SettingsScreen(
                Modifier.fillMaxSize(),
                onCoachAnchorChanged = { onCoachAnchorChanged(CoachAnchor.SETTINGS, it) },
                isActive = pageActive,
              )
            }
          }
        }
      }
    }
}

/**
 * 默认底部导航栏（Material3 NavigationBar，含选中态图标填充动画）。
 * 玻璃外观风格下由调用方包一层圆角玻璃容器悬浮显示，并传 `windowInsets = WindowInsets(0.dp)`
 * 由外层统一处理系统栏避让。
 */
@Composable
private fun DefaultBottomNavigationBar(
  selectedIndex: Int,
  tabLabels: List<String>,
  unselectedColor: Color,
  onSelectPage: (Int) -> Unit,
  modifier: Modifier = Modifier,
  windowInsets: WindowInsets = NavigationBarDefaults.windowInsets,
  containerColor: Color = NavWhite,
  showLabels: Boolean = true,
) {
  // 去掉点击 ripple（material3 1.4 起 ripple 读取 LocalRippleConfiguration，置 null 全局禁用）
  CompositionLocalProvider(LocalRippleConfiguration provides null) {
    NavigationBar(
      modifier = modifier,
      containerColor = containerColor,
      contentColor = LocalContentColor.current,
      windowInsets = windowInsets,
    ) {
      tabItems.forEachIndexed { index, tab ->
        val label = tabLabels[index]
        val selected = selectedIndex == index
        val itemColor = if (selected) MaterialTheme.colorScheme.primary else unselectedColor
        NavigationBarItem(
          selected = selected,
          onClick = { onSelectPage(index) },
          icon = {
            // 选中态染色动画与平板侧栏共用同一实现（见 ui/common/NavigationTabIcon.kt）
            NavigationTabIcon(
              iconRes = tab.iconRes,
              contentDescription = label,
              selected = selected,
              unselectedColor = unselectedColor,
              selectedColor = MaterialTheme.colorScheme.primary,
              animationLabel = "navIconFill$index",
            )
          },
          label = if (showLabels) { { Text(label) } } else null,
          // 去掉选中高亮：仅图标颜色填充动画与文字颜色区分选中态
          colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = Color.Transparent,
            unselectedIconColor = unselectedColor,
            unselectedTextColor = unselectedColor,
          ),
        )
      }
    }
  }
}
