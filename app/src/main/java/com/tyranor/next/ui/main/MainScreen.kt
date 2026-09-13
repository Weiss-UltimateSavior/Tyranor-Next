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
import com.tyranor.next.ui.common.LiquidGlassNavItem
import com.tyranor.next.ui.common.LiquidGlassNavigationBar
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
  // 导航栏样式：应用设置 → 默认 / 圆角液态玻璃（内存态，设置页切换即时生效）
  LaunchedEffect(Unit) {
    val stored = withContext(Dispatchers.IO) {
      AppSettingsStore.getNavStyle(context) to AppSettingsStore.getGameSort(context)
    }
    AppSettingsStore.navStyleState.value = stored.first
    AppSettingsStore.gameSortState.value = stored.second
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
  val liquidGlass = navStyle == AppSettingsStore.NAV_STYLE_LIQUID_GLASS
  // 玻璃外观风格 + 默认导航样式：导航栏改为悬浮的圆角玻璃条（描边 + 玻璃底）
  val floatingDefaultNav = AppThemeColors.isGlass && !liquidGlass
  val tabLabels = tabItems.map { stringResource(it.labelRes) }
  val liquidGlassTabItems = tabItems.mapIndexed { index, tab -> LiquidGlassNavItem(tabLabels[index], tab.iconRes) }

  val pageTransition = updateTransition(targetState = selectedIndex, label = "mainTabTransition")
  fun selectPage(index: Int) {
    // 动画期拒绝二次切换，保证起点/终点动画完成后再接收下一次导航。
    if (index == selectedIndex || pageTransition.isRunning) return
    selectedIndex = index
  }
  val backdropAvailable = liquidGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

  // 外层只负责布局：内容区 + 底部导航栏（不用 Scaffold，避免与子页顶部栏的 inset 冲突）
  Box(modifier.fillMaxSize()) {
    // 内容层录制进 backdrop，供液态玻璃导航采样页面内容。
    // 关键：背景必须在 layerBackdrop 之后（内层）——layerBackdrop 只录制它之后的内容，
    // 放在外层（Surface/Column 背景）的内容不会被采样，玻璃会采到透明而漏出文字。
    val backdrop = rememberLayerBackdrop()
    val contentModifier = Modifier
      .fillMaxSize()
      // source 节点常驻，避免切页结束时重新挂载玻璃录制层。
      .then(if (backdropAvailable) Modifier.layerBackdrop(backdrop) else Modifier)
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

    // 圆角液态玻璃导航：悬浮在内容之上
    if (liquidGlass) {
      LiquidGlassNavigationBar(
        backdrop = backdrop,
        selectedIndex = selectedIndex,
        primaryColor = MaterialTheme.colorScheme.primary,
        unselectedColor = unselectedColor,
        items = liquidGlassTabItems,
        onItemClick = { selectPage(it) },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
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
