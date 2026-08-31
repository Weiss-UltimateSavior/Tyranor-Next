package com.tyranor.next.ui.main

import android.os.Build
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
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
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.UnselectedGrey
import com.tyranor.next.ui.common.LiquidGlassNavItem
import com.tyranor.next.ui.common.LiquidGlassNavigationBar
import com.tyranor.next.ui.common.WithoutPressIndication
import com.tyranor.next.ui.engine.EngineScreen
import com.tyranor.next.ui.game.GameScreen
import com.tyranor.next.ui.home.HomeScreen
import com.tyranor.next.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
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
  }
  val liquidGlass = AppSettingsStore.navStyleState.value == AppSettingsStore.NAV_STYLE_LIQUID_GLASS
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
      if (!liquidGlass) {
        NavigationBar(
          containerColor = com.tyranor.next.theme.NavWhite,
          contentColor = androidx.compose.material3.LocalContentColor.current,
        ) {
          tabItems.forEachIndexed { index, tab ->
            val label = tabLabels[index]
            val selected = selectedIndex == index
            val itemColor = if (selected) MaterialTheme.colorScheme.primary else unselectedColor
            NavigationBarItem(
              selected = selected,
              onClick = { selectPage(index) },
              icon = {
                Image(
                  painter = painterResource(tab.iconRes),
                  contentDescription = label,
                  colorFilter = ColorFilter.tint(itemColor),
                  modifier = Modifier.size(28.dp),
                )
              },
              label = { Text(label) },
              // 去掉选中高亮：仅图标与文字通过主题色区分选中态
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
  }
}
