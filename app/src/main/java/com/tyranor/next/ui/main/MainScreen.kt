package com.tyranor.next.ui.main

import androidx.activity.compose.BackHandler
import android.app.Activity
import android.content.Intent
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.DefaultPageBackgroundLayer
import com.tyranor.next.theme.GlassNavSurface
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassPageBackground
import com.tyranor.next.ui.common.LiquidGlassNavItem
import com.tyranor.next.ui.common.LocalMainOverlayHost
import com.tyranor.next.ui.common.MainOverlayHost
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavigationRail
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.motion.rotationMorphBlock
import com.tyranor.next.ui.common.motion.LocalPageDepth
import com.tyranor.next.ui.common.motion.depthFoldStack
import com.tyranor.next.ui.common.motion.LocalSheetBackdrop
import com.tyranor.next.ui.common.motion.SheetBackdrop
import com.tyranor.next.ui.common.motion.rememberSheetBackdrop
import com.tyranor.next.ui.common.FloatingGlassNavButton
import com.tyranor.next.ui.common.FloatingNavOutsideTapBridge
import com.tyranor.next.ui.common.observeFloatingNavOutsideTaps
import com.tyranor.next.ui.common.LaunchErrorDialog
import com.tyranor.next.ui.common.LaunchErrorState
import com.tyranor.next.core.game.launch.LaunchFailureBus
import com.tyranor.next.ui.common.LiquidGlassNavigationBar
import com.tyranor.next.ui.common.NavigationTabIcon
import com.tyranor.next.ui.common.effectiveNavStyle
import com.tyranor.next.ui.common.isSideRailLayout
import com.tyranor.next.ui.common.toErrorState
import com.tyranor.next.ui.common.syncErrorResource
import com.tyranor.next.ui.common.glass.EnhancedLiquidGlassNavigationBar
import com.tyranor.next.ui.common.glass.GlassShaderSupport
import com.tyranor.next.ui.common.glass.GlassBottomBarSpec
import com.tyranor.next.ui.common.glass.rememberGlassBottomBarColors
import com.tyranor.next.theme.WithoutPressIndication
import com.tyranor.next.theme.AppComponentShape
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import com.tyranor.next.ui.engine.EngineScreen
import com.tyranor.next.ui.game.GameScreen
import com.tyranor.next.ui.home.HomeScreen
import com.tyranor.next.ui.tools.ToolsScreen
import com.tyranor.next.ui.settings.SettingsScreen
import com.tyranor.next.ui.common.CoachAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.tyranor.next.ui.common.privacy.blockAppInteractionKeys
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.motion.ProvideEntrance
import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import com.tyranor.next.ui.common.motion.EntranceState
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.HiddenPageAlpha
import com.tyranor.next.ui.common.motion.skipDrawWhenHidden
import com.tyranor.next.ui.common.motion.tabPageTransform
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.notice.AppNotice

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
  Tab(R.string.nav_tools, R.drawable.ic_nav_tools),
  Tab(R.string.nav_settings, R.drawable.ic_settings),
)

/** 托管五个主标签页及根 Miuix 浮层；透明且零 inset 的宿主让菜单遮罩覆盖底栏。 */
@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  coachPage: Int? = null,
  onCoachAnchorChanged: (Int, Rect) -> Unit = { _, _ -> },
) {
  val context = LocalContext.current
  var globalLaunchError by remember { mutableStateOf<LaunchErrorState?>(null) }
  val busFailure by LaunchFailureBus.failure.collectAsStateWithLifecycle()
  LaunchedEffect(busFailure) {
    busFailure?.let {
      globalLaunchError = it.toErrorState(context)
      LaunchFailureBus.clear()
    }
  }
  // 「显示首页」开关（应用设置，默认关；老用户升级自动保留开）：关闭后首页不参与导航，游戏页作为第一页
  // 随生命周期订阅：设置页盖在上面、主界面不可见时不在后台增删首页（组合 / 销毁首页会占满主线程，让设置页开关卡顿）
  // 先从 prefs 初始化要订阅的流；首帧页数和默认选中项使用同一个值，关闭首页时不会先闪出全部导航图标。
  val homePageEnabled by remember(context) {
    AppSettingsStore.initHomePageEnabled(context)
    AppSettingsStore.homePageEnabledState
  }.collectSettingAsState()
  var selectedIndex by rememberSaveable {
    mutableIntStateOf(if (homePageEnabled) 0 else 1)
  }
  // 关闭首页时，若当前停在其上则收敛到游戏页（引导页索引同样收敛，避免空白页）
  LaunchedEffect(homePageEnabled) {
    if (!homePageEnabled && selectedIndex == 0) selectedIndex = 1
  }
  // 引导期间由 coachPage 临时接管当前页；其余时间使用用户选择的 selectedIndex。
  // 所有转场、导航和页面交互都使用同一个 activeIndex，避免首帧出现双索引错位。
  val requestedIndex = coachPage?.coerceIn(tabItems.indices) ?: selectedIndex
  val activeIndex = if (!homePageEnabled && requestedIndex == 0) 1 else requestedIndex
  val libraryViewModel: MainLibraryViewModel = viewModel()
  val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
  val interactScope = rememberCoroutineScope()
  var pendingSaveSync by remember { mutableStateOf<Job?>(null) }
  var pendingModuleReconcile by remember { mutableStateOf<Job?>(null) }
  val moduleReconcileMutex = remember { Mutex() }
  val unselectedColor = UnselectedGrey
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) { AppSettingsStore.initEngineTabs(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initEngineCardIcon(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initGameCardHideTitleTag(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initGameCardBadge(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initGameCardStyle(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initGameCardCorners(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initHomeStyle(context) }
    withContext(Dispatchers.IO) { AppSettingsStore.initFloatingNavPosition(context) }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    // 库刷新（含 xsys35 标题回写的前台补导入，见 MainLibraryViewModel.onForeground）
    libraryViewModel.onForeground()
    // 存档互通前台兜底：对已退出会话的待回写游戏补一次 Tyranor→标准同步
    // （引擎退出后 500ms 强杀、无回调，故在应用回到前台时补齐）。
    pendingSaveSync?.cancel()
    pendingSaveSync = interactScope.launch {
      // runCatching 会把协程取消也当作失败吞掉，故显式区分：取消原样传播，其余仅记日志
      try {
        withContext(Dispatchers.IO) { EngineLauncher.flushPendingSaveSync(context) }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        android.util.Log.w("MainScreen", "pending RPG save sync failed", t)
      }
    }
    // 第三方运行时模块：卸载/禁用后把绑定游戏降级为 UNKNOWN；同时刷新模块 label 缓存。
    pendingModuleReconcile?.cancel()
    pendingModuleReconcile = interactScope.launch {
      try {
        // 给安装器/包替换留出稳定窗口，避免模块更新瞬间把全部绑定误降级。
        delay(1500)
        moduleReconcileMutex.withLock {
          pendingModuleReconcile = null
          val degraded = withContext(Dispatchers.IO) {
            val count = RuntimeModuleBindings.reconcile(context)
            RuntimeModuleRegistry.refresh(context)
            count
          }
          libraryViewModel.refreshFromStorage()
          if (degraded > 0) {
            AppNotice.makeText(
              context,
              R.string.engine_module_management_degraded_toast,
              android.widget.Toast.LENGTH_SHORT,
            ).show()
          }
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        android.util.Log.w("MainScreen", "runtime module reconcile failed", t)
      }
    }
  }
  val navStyle = effectiveNavStyle()
  // 页面级返回处理后注册，仍优先处理网页历史和浮层关闭。
  val hostActivity = AppLocaleController.findActivity(context)
  BackHandler { hostActivity?.leaveMainScreen() }
  // 页面区里的抽屉与弹窗（AppAlertDialog）升起时，页面区只褪色、不再上下翻折
  val overlayBackdrop = rememberSheetBackdrop()
  val cleanupError = libraryState.cleanupError
  if (cleanupError != null || libraryState.copyCleanupPending) {
    val cleanupMessage = if (cleanupError != null) {
      stringResource(R.string.settings_systemnnn_copy_cleanup_schedule_failed, libraryState.cleanupGameTitle.orEmpty(),
        stringResource(cleanupError.syncErrorResource()))
    } else {
      stringResource(R.string.settings_systemnnn_copy_cleanup_pending)
    }
    CompositionLocalProvider(LocalSheetBackdrop provides overlayBackdrop) {
      AppAlertDialog(
        onDismissRequest = libraryViewModel::dismissCleanupError,
        title = { Text(stringResource(R.string.save_operation_failed), style = MaterialTheme.typography.titleMedium) },
        text = { Text(cleanupMessage, style = MaterialTheme.typography.bodyMedium) },
        textScrollEnabled = true,
        confirmButton = {
          DialogTextButton(stringResource(R.string.common_confirm), onClick = libraryViewModel::dismissCleanupError)
        },
      )
    }
  }
  // 首页样式：Web 首页参与绘制期间底栏改用仅背景采样源（见 webHomeInvolved）
  val homeStyle by AppSettingsStore.homeStyleState.collectSettingAsState()
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
  // 玻璃外观风格 + 默认导航样式：导航栏改为悬浮的圆角玻璃条（描边 + 玻璃底）
  val floatingDefaultNav = AppThemeColors.isGlass && !liquidGlass && !floatingButtonStyle && !railLayout
  // 可见 Tab：关闭首页后首页不参与导航（游戏页成为第一页）
  val visibleTabEntries = remember(homePageEnabled) {
    tabItems.mapIndexedNotNull { index, tab ->
      if (homePageEnabled || index != 0) index to tab else null
    }
  }
  val tabLabels = visibleTabEntries.map { stringResource(it.second.labelRes) }
  // remember(tabLabels)：labels 内容不变时复用同一份 items，避免每次重组都给增强栏传新 List
  // （增强栏据此跳过重组，进而避免 drawBackdrop 元素被判不等而重建 RenderEffect 管线）
  val liquidGlassTabItems = remember(tabLabels) {
    visibleTabEntries.mapIndexed { index, entry -> LiquidGlassNavItem(tabLabels[index], entry.second.iconRes) }
  }
  // 导航组件内部使用「可见条目下标」，选中值需从页面下标换算
  val selectedNavIndex = visibleTabEntries.indexOfFirst { it.first == activeIndex }.coerceAtLeast(0)

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

  val pageTransition = updateTransition(targetState = activeIndex, label = "mainTabTransition")
  val tabMotion = rememberMainTabMotion(pageTransition, tabItems.size, onboarding = coachPage != null)
  // 每个标签页一份入场节拍：切到该页时卡片 / 列表项依次弹入（页内 staggeredEntrance 使用）。
  // 在切页的同一帧同步重播，页内元素首帧即按隐藏绘制，不会先闪一下
  val tabEntrances = tabItems.indices.map { page -> rememberEntranceState(playOnStart = page == activeIndex) }
  val tabLeftAt = remember { LongArray(tabItems.size) }

  /** Both coach-driven and user-driven page changes share the existing hidden-page entrance gate. */
  fun changeSelectedPage(previous: Int, index: Int) {
    val now = SystemClock.uptimeMillis()
    tabLeftAt[previous] = now
    tabMotion[index].replayEntranceIfHidden(tabEntrances[index], now - tabLeftAt[index])
    selectedIndex = index
  }

  LaunchedEffect(coachPage) {
    val index = coachPage?.coerceIn(tabItems.indices) ?: return@LaunchedEffect
    if (index != selectedIndex) changeSelectedPage(selectedIndex, index)
  }

  /** Validates tab requests, preserves ordinary-mode guards, and replays entrance only for hidden pages. */
  fun selectPage(index: Int) {
    // 索引保护：宿主可能收到越界请求（例如 items 变化后的晚到回调）；
    // 关闭首页时首页下标同样不可达
    if (visibleTabEntries.none { it.first == index }) return
    if (index == activeIndex) return
    // 透镜档需要「转场期间接受新目标」：透镜已经跟手移动，页面却不动会明显脱节。
    // 这里判的是**实际生效的档**（enhancedBarActive）而不是设置值：AGSL 不可用时界面已回退成
    // 经典档，转场语义必须跟着回退，否则会出现「看着是经典档、行为是透镜档」的错配。
    // 默认路径保持原有守卫，避免把这一行为变更带给未开启该选项的用户（报告 §8.7）。
    // 「普通」动效档：原版守卫（转场进行中忽略）；「增强」档不设守卫：切页是弹簧转场，
    // 转场途中点新页签会从当前位置与速度直接转向新目标（可打断）
    val guardActive = !MotionMode.enhanced
    if (!enhancedBarActive && !floatingButtonActive && pageTransition.isRunning && guardActive) return
    // 切回刚离开不久的标签页（A→B→A）时不重播：那一页还在屏幕上，重播会把正在显示的内容清空再弹入
    changeSelectedPage(activeIndex, index)
  }
  // 导航组件回调使用可见条目下标；这里换算回页面下标（关闭首页后二者不同）
  val onSelectNavPage: (Int) -> Unit = { navIndex ->
    visibleTabEntries.getOrNull(navIndex)?.let { selectPage(it.first) }
  }
  // 外层只负责布局：内容区 + 底部导航栏（不用 Scaffold，避免与子页顶部栏的 inset 冲突）
  // 透明、零 inset 的最外层 Miuix Scaffold 只用来托管浮层：各标签页里的下拉菜单等 Miuix 浮层默认渲染到最外层 Scaffold，
  // 放在这里后浮层与其背景变暗盖住整个屏幕（含底部导航栏），而不是夹在页面与底栏之间、让底栏单独亮着。
  // 浮层内容在这里组合，故宿主套同一个 MiuixSettingsTheme，配色与原先在设置页内渲染时一致。
  val mainOverlayHost = remember { MainOverlayHost() }
  CompositionLocalProvider(LocalMainOverlayHost provides mainOverlayHost) {
  MiuixScaffold(
    modifier = Modifier.fillMaxSize(),
    popupHost = { MiuixSettingsTheme { MiuixPopupUtils.MiuixPopupHost() } },
    containerColor = Color.Transparent,
    contentWindowInsets = WindowInsets(0.dp),
  ) { _ ->
  Box(
    modifier.fillMaxSize()
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
    val homeInTransition = activeIndex == 0 || pageTransition.currentState == 0 || pageTransition.targetState == 0
    // 「增强」动效档允许转场途中转向（首页→游戏→引擎）：转向时 currentState 会变成上一个目标，首页其实还在淡出，
    // 必须保持「首页参与绘制」直到转场完全停下，否则底栏会提前改采内容层，把 WebView 录进采样层而闪烁
    var homeLatched by remember { mutableStateOf(false) }
    // 只以 homeInTransition 作为 key：isRunning 不在组合期读取，转场起止不再触发 MainScreen 重组；
    // 首页离开时等到转场真正停下（snapshotFlow 读取的是当前值，已停下则立即继续）再解除锁存。
    LaunchedEffect(homeInTransition) {
      if (homeInTransition) {
        homeLatched = true
      } else {
        snapshotFlow { pageTransition.isRunning }.first { !it }
        homeLatched = false
      }
    }
    val webHomeInvolved = bottomBackdropActive &&
      homeStyle == AppSettingsStore.HOME_STYLE_WEB &&
      (homeInTransition || (MotionMode.enhanced && homeLatched))
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
    Box(
      Modifier.fillMaxSize()
        .then(if (mainOverlayHost.active) Modifier.clearAndSetSemantics { } else Modifier)
        .then(Modifier.blockAppInteractionKeys(mainOverlayHost.active)),
    ) {
    if (railLayout) {
      // 平板/大窗口：主导航移到侧边，按外观风格取该主题「默认导航栏」形态
      Row(Modifier.fillMaxSize()) {
        AppNavigationRail(
          selectedIndex = selectedNavIndex,
          items = liquidGlassTabItems,
          onItemClick = onSelectNavPage,
        )
        Column(Modifier.weight(1f).fillMaxHeight().then(contentLayerModifier)) {
          MainTabPages(
            selectedIndex = activeIndex,
            pageTransition = pageTransition,
            tabMotion = tabMotion,
            tabEntrances = tabEntrances,
            homePageEnabled = homePageEnabled,
            libraryState = libraryState,
            libraryViewModel = libraryViewModel,
            onCoachAnchorChanged = onCoachAnchorChanged,
            overlay = overlayBackdrop,
            foldDesaturate = !homePageEnabled || homeStyle != AppSettingsStore.HOME_STYLE_WEB,
            onboarding = coachPage != null,
          )
        }
      }
    } else {
      Column(Modifier.fillMaxSize().then(contentLayerModifier)) {
        MainTabPages(
          selectedIndex = activeIndex,
          pageTransition = pageTransition,
          tabMotion = tabMotion,
          tabEntrances = tabEntrances,
          homePageEnabled = homePageEnabled,
          libraryState = libraryState,
          libraryViewModel = libraryViewModel,
          onCoachAnchorChanged = onCoachAnchorChanged,
          overlay = overlayBackdrop,
          foldDesaturate = !homePageEnabled || homeStyle != AppSettingsStore.HOME_STYLE_WEB,
          onboarding = coachPage != null,
        )
        if (!liquidGlass && !floatingDefaultNav && !floatingButtonActive) {
          DefaultBottomNavigationBar(
            selectedIndex = selectedNavIndex,
            items = liquidGlassTabItems,
            unselectedColor = unselectedColor,
            onSelectPage = onSelectNavPage,
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
          selectedIndex = selectedNavIndex,
          colors = rememberGlassBottomBarColors(unselectedColor),
          items = liquidGlassTabItems,
          onItemClick = onSelectNavPage,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = GlassBottomBarSpec.Default.hostBottomPadding),
        )
      } else {
        LiquidGlassNavigationBar(
          backdrop = navBackdrop,
          selectedIndex = selectedNavIndex,
          primaryColor = MaterialTheme.colorScheme.primary,
          unselectedColor = unselectedColor,
          items = liquidGlassTabItems,
          onItemClick = onSelectNavPage,
          modifier = Modifier.align(Alignment.BottomCenter),
        )
      }
    }

    // 玻璃外观风格下的默认导航栏：悬浮圆角玻璃条（玻璃底 + 0.5dp 描边 + 应用圆角），
    // 内容可从其下方滚过，列表底部留白由 glassNavBottomInset() 统一提供。
    if (floatingDefaultNav) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 12.dp, vertical = 12.dp),
      ) {
        DefaultBottomNavigationBar(
          selectedIndex = selectedNavIndex,
          items = liquidGlassTabItems,
          unselectedColor = unselectedColor,
          onSelectPage = onSelectNavPage,
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

    // 悬浮按钮导航：右下角一个圆形液态玻璃按钮，点击后各导航项沿 1/4 圆弧交错展开；
    // 悬浮不占布局高度，内容底部留白由 glassNavBottomInset() 统一提供。
    if (floatingButtonActive) {
      FloatingGlassNavButton(
        backdrop = if (floatingButtonBackdrop) navBackdrop else null,
        items = liquidGlassTabItems,
        selectedIndex = selectedNavIndex,
        primaryColor = MaterialTheme.colorScheme.primary,
        onItemClick = onSelectNavPage,
        // 组件在安全区内自行摆放（长按可拖动、位置持久化），宿主只负责占满可用区域
        modifier = Modifier.fillMaxSize(),
        outsideTapBridge = floatingNavOutsideTapBridge,
      )
    }
    }

    if (mainOverlayHost.active) {
      mainOverlayHost.stack.forEach { entry ->
        key(entry.id) {
          Box(Modifier.fillMaxSize().zIndex(10f)) {
            entry.content()
          }
        }
      }
    }

  }
  }
  }

  globalLaunchError?.let { state ->
    LaunchErrorDialog(state = state, onDismiss = { globalLaunchError = null })
  }
}

/**
 * 主界面可见页常驻组合（默认五页；应用设置关闭首页后为四页）：隐藏页只保留已测量节点且 alpha=0，
 * 切换时不再重建游戏网格/Miuix 设置页；
 * 中间 Tab 的 alpha 在任意状态均为 0，跨页时只显示起点和终点的直接水平动画。
 * 底栏形态与平板侧栏形态共用本组件，保证两种布局下的页面与转场完全一致。
 */
/** 页面内容的入场节拍 + 景深句柄：页面里的抽屉与弹窗（AppAlertDialog）经 LocalSheetBackdrop 登记，页面区随之翻折。 */
@Composable
private fun ProvideEntranceAndBackdrop(
  entrance: EntranceState,
  overlay: SheetBackdrop,
  content: @Composable () -> Unit,
) {
  ProvideEntrance(entrance) {
    CompositionLocalProvider(LocalSheetBackdrop provides overlay, content = content)
  }
}

@Composable
private fun ColumnScope.MainTabPages(
  selectedIndex: Int,
  pageTransition: Transition<Int>,
  tabMotion: List<MainTabMotion>,
  tabEntrances: List<EntranceState>,
  homePageEnabled: Boolean,
  libraryState: MainLibraryUiState,
  libraryViewModel: MainLibraryViewModel,
  onCoachAnchorChanged: (Int, Rect) -> Unit,
  overlay: SheetBackdrop,
  foldDesaturate: Boolean,
  onboarding: Boolean,
) {
    // 抽屉 / 弹窗与页面推入共用景深层，按最大进度合成，不叠加。
    Box(
      Modifier.weight(1f).fillMaxWidth().clipToBounds()
        .depthFoldStack(overlay = overlay, panel = null, push = LocalPageDepth.current, desaturate = foldDesaturate),
    ) {
      WithoutPressIndication {
        // 可见页面常驻组合：隐藏页只保留已测量节点且 alpha=0，切换时不再重建游戏网格/Miuix 设置页。
        // 中间 Tab 的 alpha 在任意状态均为 0，因此跨页时只显示起点和终点的直接水平动画。
        // 关闭首页时不再组合首页（下标 0），游戏页成为第一个可见页。
        val visiblePages = if (homePageEnabled) tabItems.indices.toList() else tabItems.indices.drop(1)
        // key(page)：页面身份按 Tab 下标固定。可见集合变化（如关闭/恢复首页）时，
        // 未变化页面的组合槽位与 pageOffset/pageAlpha 动画状态不会被错位复用而闪现。
        visiblePages.forEach { page ->
          key(page) {
            val selected = page == selectedIndex
            MainTabPageHost(
              page = page,
              selected = selected,
              pageTransition = pageTransition,
              motion = tabMotion[page],
              entrance = tabEntrances[page],
              overlay = overlay,
              allowHiddenDraw = page != 0 || foldDesaturate,
              reportActive = onboarding,
            ) { pageActive ->
              when (page) {
                0 -> HomeScreen(
                  modifier = Modifier.fillMaxSize(),
                  libraryState = libraryState,
                  onGameUpdated = libraryViewModel::replaceGame,
                  onGameCoverUpdated = libraryViewModel::applyCoverUpdate,
                  onGameRenamed = libraryViewModel::renameGame,
                  onGameLaunchFileUpdated = libraryViewModel::updateLaunchFile,
                  onGameBindingUpdated = libraryViewModel::applyBindingUpdate,
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
                  onGameCoverUpdated = libraryViewModel::applyCoverUpdate,
                  onGameRenamed = libraryViewModel::renameGame,
                  onGameLaunchFileUpdated = libraryViewModel::updateLaunchFile,
                  onGameBindingUpdated = libraryViewModel::applyBindingUpdate,
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
                  reportCoachAnchor = pageActive,
                  isSelected = selected,
                  gameCount = libraryState.games.size,
                )
                3 -> ToolsScreen(Modifier.fillMaxSize())
                4 -> SettingsScreen(
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
}

/** 保留桌面根任务正常返回时移到后台的系统语义。 */
private fun Activity.leaveMainScreen() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isTaskRoot &&
    intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true &&
    moveTaskToBack(false)
  ) return
  finish()
}

/** 各页独立订阅交互门控。某一页到位时，无需重新执行其他常驻页的外壳。 */
@Composable
internal fun MainTabPageHost(
  page: Int,
  selected: Boolean,
  pageTransition: Transition<Int>,
  motion: MainTabMotion,
  entrance: EntranceState,
  overlay: SheetBackdrop,
  allowHiddenDraw: Boolean,
  reportActive: Boolean,
  content: @Composable (Boolean) -> Unit,
) {
  // 可交互：选中且已基本到位（弹簧尾巴不再阻挡点击）
  val pageSettled by remember(motion) {
    derivedStateOf { kotlin.math.abs(motion.offset.value) < 0.02f && motion.alpha.value > 0.98f }
  }
  val pageInteractive = selected &&
    (if (MotionMode.enhanced) pageSettled else !pageTransition.isRunning)
  // 隐藏页（alpha=0）不把内容画进显示列表（见 skipDrawWhenHidden）。Web 首页（WebView）的原生视图靠 Compose 的绘制回调
  // 才画出来，不参与；「普通」档保持原样
  val gateHiddenDraw = MotionMode.enhanced && allowHiddenDraw
  val pageShown by remember(motion) { derivedStateOf { motion.alpha.value > HiddenPageAlpha } }
  val pageSelected by rememberUpdatedState(selected)
  // 首页在停稳后恢复 Web 生命周期或导入游戏时长；其余页面只在引导需要锚点时订阅。
  val pageActive = (page == 0 || reportActive) && selected && !pageTransition.isRunning
  Box(
    Modifier
      .fillMaxSize()
      // 旋转过渡：后台常驻页不跟着虚拟窗口逐帧重排（见 ScreenRotation.kt 的 rotationMorphBlock）；当前页是轻的内容照常重排
      .rotationMorphBlock(freeze = !selected)
      .skipEntranceOnDrag(entrance)
      .zIndex(if (selected) 1f else 0f)
      // 整宽水平位移 + 淡入淡出 + 景深缩放（滑动途中两页略微缩小，停稳时为 1，外观不变）；增强档的位移 / 缩放在绘制阶段图层里（见 tabPageTransform）
      .tabPageTransform(
        key = motion,
        offset = { motion.offset.value },
        alpha = { motion.alpha.value },
      )
      .then(
        if (gateHiddenDraw) {
          Modifier.skipDrawWhenHidden { pageShown || pageSelected }
        } else {
          Modifier
        },
      )
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
    ProvideEntranceAndBackdrop(entrance, overlay) { content(pageActive) }
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
  items: List<LiquidGlassNavItem>,
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
      items.forEachIndexed { index, item ->
        val label = item.label
        val selected = selectedIndex == index
        val itemColor = if (selected) MaterialTheme.colorScheme.primary else unselectedColor
        NavigationBarItem(
          selected = selected,
          onClick = { onSelectPage(index) },
          icon = {
            // 选中态染色动画与平板侧栏共用同一实现（见 ui/common/NavigationTabIcon.kt）
            NavigationTabIcon(
              iconRes = item.iconRes,
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
