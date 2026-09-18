package com.tyranor.next.ui.main

import android.os.Build
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassNavSurface
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassPageBackground
import com.tyranor.next.ui.common.LiquidGlassNavItem
import com.tyranor.next.ui.common.LiquidGlassNavigationBar
import com.tyranor.next.ui.common.glass.EnhancedLiquidGlassNavigationBar
import com.tyranor.next.ui.common.glass.GlassShaderSupport
import com.tyranor.next.ui.common.glass.GlassBottomBarSpec
import com.tyranor.next.ui.common.glass.rememberGlassBottomBarColors
import com.tyranor.next.theme.WithoutPressIndication
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.engine.EngineScreen
import com.tyranor.next.ui.game.GameScreen
import com.tyranor.next.ui.home.HomeScreen
import com.tyranor.next.ui.settings.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
fun MainScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var selectedIndex by rememberSaveable { mutableStateOf(0) }
  val libraryViewModel: MainLibraryViewModel = viewModel()
  val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
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
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    libraryViewModel.refreshFromStorage()
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
  }
  val navStyle by AppSettingsStore.navStyleState.collectAsState()
  // 液态玻璃两档共用同一套宿主准备（录制采样层、转场重定向、底部留白）
  val liquidGlass = navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS ||
    navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED
  val enhanceLiquidGlass = navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS_ENHANCED
  // 玻璃外观风格 + 默认导航样式：导航栏改为悬浮的圆角玻璃条（描边 + 玻璃底）
  val floatingDefaultNav = AppThemeColors.isGlass && !liquidGlass
  val tabLabels = tabItems.map { stringResource(it.labelRes) }
  // remember(tabLabels)：labels 内容不变时复用同一份 items，避免每次重组都给增强栏传新 List
  // （增强栏据此跳过重组，进而避免 drawBackdrop 元素被判不等而重建 RenderEffect 管线）
  val liquidGlassTabItems = remember(tabLabels) {
    tabItems.mapIndexed { index, tab -> LiquidGlassNavItem(tabLabels[index], tab.iconRes) }
  }

  val backdropAvailable = liquidGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  // 透镜档**是否真的会渲染**：设置选了它 + 采样层可用 + 本机 AGSL 可用。
  // 后者是运行期探测（见 GlassShaderSupport）：API 33+ 但 AGSL 编译异常的机器上，
  // Backdrop 内部的 RuntimeShader 会在布局期抛出并崩掉整个主界面，此时退回经典档更安全。
  // 注意：回退时经典档也会被要求不传 Highlight，否则兜底路径自己仍依赖 RuntimeShader。
  val enhancedBarActive = enhanceLiquidGlass && backdropAvailable && GlassShaderSupport.isRuntimeShaderUsable

  val pageTransition = updateTransition(targetState = selectedIndex, label = "mainTabTransition")
  fun selectPage(index: Int) {
    // 索引保护：宿主可能收到越界请求（例如 items 变化后的晚到回调）
    if (index !in tabItems.indices) return
    if (index == selectedIndex) return
    // 透镜档需要「转场期间接受新目标」：透镜已经跟手移动，页面却不动会明显脱节。
    // 这里判的是**实际生效的档**（enhancedBarActive）而不是设置值：AGSL 不可用时界面已回退成
    // 经典档，转场语义必须跟着回退，否则会出现「看着是经典档、行为是透镜档」的错配。
    // 默认路径保持原有守卫，避免把这一行为变更带给未开启该选项的用户（报告 §8.7）。
    if (!enhancedBarActive && pageTransition.isRunning) return
    selectedIndex = index
  }
  // 外层只负责布局：内容区 + 底部导航栏（不用 Scaffold，避免与子页顶部栏的 inset 冲突）
  Box(modifier.fillMaxSize()) {
    // 内容层录制进 backdrop，供液态玻璃导航采样页面内容。
    // 关键：背景必须在 layerBackdrop 之后（内层）——layerBackdrop 只录制它之后的内容，
    // 放在外层（Surface/Column 背景）的内容不会被采样，玻璃会采到透明而漏出文字。
    val backdrop = rememberLayerBackdrop()
    // 采样修复（报告 §5.3 / §8.4 第 1 条）：玻璃外观风格下 PageGrey 透明，根部背景在采样层之外，
    // 透镜档把同一份背景画进被采样内容，保证透镜采样源含完整背景且坐标同源。
    // remember：MainScreen 在切页动画期间每帧重组，复用同一 Modifier 才不会每帧重建绘制缓存。
    val accent = AppThemeColors.primary
    val pageBackground = remember(accent) { Modifier.glassPageBackground(accent) }
    val contentModifier = Modifier
      .fillMaxSize()
      // source 节点常驻，避免切页结束时重新挂载玻璃录制层。
      .then(if (backdropAvailable) Modifier.layerBackdrop(backdrop) else Modifier)
      .then(
        // 仅玻璃外观风格需要（该模式下 PageGrey 透明、根部背景在采样层之外）
        if (enhancedBarActive && AppThemeColors.isGlass) {
          pageBackground
        } else {
          Modifier
        },
      )
      .background(MaterialTheme.colorScheme.background)
    Column(contentModifier) {
      Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
        WithoutPressIndication {
          // 四页常驻组合：隐藏页只保留已测量节点且 alpha=0，切换时不再重建游戏网格/Miuix 设置页。
          // 中间 Tab 的 alpha 在任意状态均为 0，因此跨页时只显示起点和终点的直接水平动画。
          tabItems.indices.forEach { page ->
            val pageOffset by pageTransition.animateFloat(
              transitionSpec = { tween(durationMillis = 200) },
              label = "mainTabOffset$page",
            ) { activePage ->
              when {
                page < activePage -> -1f
                page > activePage -> 1f
                else -> 0f
              }
            }
            val pageAlpha by pageTransition.animateFloat(
              transitionSpec = { tween(durationMillis = 160) },
              label = "mainTabAlpha$page",
            ) { activePage -> if (page == activePage) 1f else 0f }
            val pageInteractive = page == selectedIndex && !pageTransition.isRunning
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
                )
                2 -> EngineScreen(Modifier.fillMaxSize())
                3 -> SettingsScreen(Modifier.fillMaxSize())
              }
            }
          }
        }
      }
      if (!liquidGlass && !floatingDefaultNav) {
        DefaultBottomNavigationBar(
          selectedIndex = selectedIndex,
          tabLabels = tabLabels,
          unselectedColor = unselectedColor,
          onSelectPage = { selectPage(it) },
        )
      }
    }

    // 液态玻璃 · 经典：悬浮在内容之上。
    // 透镜档（应用设置 → 导航栏样式 → 液态玻璃 · 透镜）改用三层采样 + 折射透镜；默认与经典档走原实现。
    if (liquidGlass) {
      // 透镜档必须要有可用采样层（backdropAvailable 已含 API 门槛与液态玻璃条件）；
      // 万一不满足则退回经典档，而不是拿未挂载的 backdrop 渲染（组件契约要求）
      if (enhancedBarActive) {
        EnhancedLiquidGlassNavigationBar(
          backdrop = backdrop,
          // 权威选中值先夹取到合法槽位，避免越界值把透镜放到栏外（组件内部也会再夹一次）
          selectedIndex = selectedIndex.coerceIn(liquidGlassTabItems.indices),
          colors = rememberGlassBottomBarColors(unselectedColor),
          items = liquidGlassTabItems,
          onItemClick = { selectPage(it) },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = GlassBottomBarSpec.Default.hostBottomPadding),
        )
      } else {
        LiquidGlassNavigationBar(
          backdrop = backdrop,
          selectedIndex = selectedIndex,
          primaryColor = MaterialTheme.colorScheme.primary,
          unselectedColor = unselectedColor,
          items = liquidGlassTabItems,
          onItemClick = { selectPage(it) },
          // 仅在「需要 AGSL 而 AGSL 不可用」（API 33+ 探测失败）时掐掉高光：
          // 库的 HighlightStyle 在 33+ 同样构造 RuntimeShader，不掐则回退路径自己会崩；
          // 而 API 31–32 库走非 shader 的描边路径、本来有高光，必须保持 true
          //（别用 isRuntimeShaderUsable——它在 33 以下恒为 false，会误删 Android 12 的高光）。
          highlightAvailable = GlassShaderSupport.highlightAllowed,
          modifier = Modifier.align(Alignment.BottomCenter),
        )
      }
    }

    // 玻璃外观风格下的默认导航栏：悬浮圆角玻璃条（玻璃底 + 0.5dp 描边 + 16dp 圆角），
    // 内容可从其下方滚过，列表底部留白由 glassNavBottomInset() 统一提供
    if (floatingDefaultNav) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 12.dp, vertical = 12.dp),
      ) {
        DefaultBottomNavigationBar(
          selectedIndex = selectedIndex,
          tabLabels = tabLabels,
          unselectedColor = unselectedColor,
          onSelectPage = { selectPage(it) },
          modifier = Modifier
            .fillMaxWidth()
            // 无文字后按图标高度收窄导航条（64dp）；圆角 32dp（半高），呈全圆角胶囊观感
            .height(64.dp)
            .clip(AppComponentShape)
            .glassBorder(AppComponentShape),
          windowInsets = WindowInsets(0.dp),
          // 玻璃风格：更实的玻璃底 + 只显示图标（不显示文字）
          containerColor = GlassNavSurface,
          showLabels = false,
        )
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
            // 选中态染色动画：底层铺未选中灰，上层主题色图标用渐变遮罩自下而上填充
            // （fill 0→1 时遮罩分界线从底边升到顶边），取消选中时自上而下退色。
            val fill by animateFloatAsState(
              targetValue = if (selected) 1f else 0f,
              animationSpec = tween(durationMillis = 700),
              label = "navIconFill$index",
            )
            Box(Modifier.size(28.dp)) {
              Image(
                painter = painterResource(tab.iconRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(unselectedColor),
              )
              Image(
                painter = painterResource(tab.iconRes),
                contentDescription = label,
                modifier = Modifier
                  .fillMaxSize()
                  .graphicsLayer {
                    // 离屏合成，保证 DstIn 遮罩只作用于本层图标
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                  }
                  .drawWithCache {
                    onDrawWithContent {
                      // fill=0 → 分界线在底边（全隐藏）；fill=1 → 分界线在顶边（全显示）
                      val edge = 1f - fill
                      val mask = Brush.verticalGradient(
                        colorStops = arrayOf(edge to Color.Transparent, edge to Color.White),
                      )
                      drawContent()
                      drawRect(brush = mask, blendMode = BlendMode.DstIn)
                    }
                  },
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
              )
            }
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
