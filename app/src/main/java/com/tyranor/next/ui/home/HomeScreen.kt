package com.tyranor.next.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.NavWhiteOverlay
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.QuickLaunchFallback
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.coachAnchor
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.boxBlurArgb
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.isWideScreen
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.motionBlurAlpha
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.game.GameActionsSheet
import com.tyranor.next.ui.game.GameQuickActionsDialog
import com.tyranor.next.ui.game.coverColor
import com.tyranor.next.ui.game.rememberCoverBitmap
import com.tyranor.next.ui.game.startActivityWithPageTransition
import com.tyranor.next.ui.main.MainLibraryUiState
import com.tyranor.next.ui.settings.PerGameSettingsActivity

/**
 * Hosts the selected native or web home and shares game actions and launch gates with the library.
 * The active-tab flag controls the web home's lifecycle while the main tabs remain composed.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    libraryState: MainLibraryUiState,
    onGameUpdated: (ScanGame) -> Unit,
    onGameDeleted: (ScanGame) -> Unit,
    onRecentRemoved: (ScanGame) -> Unit,
    onQuickLaunchToggle: (ScanGame) -> Boolean,
    onCoachAnchorChanged: (Rect) -> Unit = {},
    /** 首页 Tab 是否处于前台（四页常驻：Web 首页据此暂停/恢复，避免后台耗电）。 */
    isActive: Boolean = true,
) {
    val context = LocalContext.current
    val quickLaunch = libraryState.quickLaunch
    val homeStyle by AppSettingsStore.homeStyleState.collectAsState()
    // 锚点生命周期跟随页面，而不是 LazyColumn 首项；拖动时保留上一帧目标，避免立即退化为全屏拦截。
    DisposableEffect(homeStyle, isActive) {
        onDispose { onCoachAnchorChanged(Rect.Zero) }
    }
    val homeWebUrl by AppSettingsStore.homeWebUrlState.collectAsState()
    var webReloadSignal by remember { mutableIntStateOf(0) }
    var selectedGame by remember { mutableStateOf<ScanGame?>(null) }
    // 卡片长按的操作弹窗目标：只存 uri，每次从最新列表解析（改名/封面更新后仍是新对象）
    var quickActionsGameUri by rememberSaveable { mutableStateOf<String?>(null) }
    val quickActionsGame = remember(libraryState.games, quickActionsGameUri) {
        quickActionsGameUri?.let { uri -> libraryState.games.firstOrNull { it.uri == uri } }
    }

    LaunchedEffect(libraryState.games, quickActionsGameUri) {
        selectedGame = selectedGame?.let { selected ->
            libraryState.games.firstOrNull { it.uri == selected.uri }
        }
        quickActionsGameUri?.let { uri ->
            if (libraryState.games.none { it.uri == uri }) quickActionsGameUri = null
        }
    }

    fun replaceGame(updated: ScanGame) {
        selectedGame = selectedGame?.let { if (it.uri == updated.uri) updated else it }
        onGameUpdated(updated)
    }

    // ===== Web 首页：应用设置「首页样式切换」为网页时，整页替换为 WebView（保留顶栏） =====
    if (homeStyle == AppSettingsStore.HOME_STYLE_WEB) {
        Column(modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.nav_home),
                trailing = {
                    TopBarIcon(
                        painterResource(R.drawable.ic_refresh),
                        stringResource(R.string.home_web_refresh_content_description),
                        MaterialTheme.colorScheme.primary,
                    ) { webReloadSignal++ }
                },
            )
            HomeWebView(
                url = homeWebUrl,
                isActive = isActive,
                reloadSignal = webReloadSignal,
                // 悬浮玻璃底栏不占布局：网页底部留白避免内容被永久遮挡（侧栏布局该函数返回 0）
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = glassNavBottomInset())
                    .coachAnchor(isActive, onCoachAnchorChanged),
            )
        }
        return
    }

    // ===== 原生首页（Web 已在上面分支返回） =====
    NativeHomeContent(
        modifier = modifier,
        libraryState = libraryState,
        onGameClick = { selectedGame = it },
        // 长按不再直接启动：打开统一操作弹窗（快捷启动 / 桌面快捷方式 / 改名 / 改封面 / 删除）
        onGameLongClick = { quickActionsGameUri = it.uri },
        onRecentRemoved = onRecentRemoved,
        onCoachAnchorChanged = onCoachAnchorChanged,
        isActive = isActive,
    )

    // ===== 与游戏页统一：点按打开操作抽屉，长按打开操作弹窗 =====
    selectedGame?.let { game ->
        key(game.uri) {
            GameActionsSheet(
                game = game,
                onDismiss = { selectedGame = null },
                onGameUpdated = { replaceGame(it) },
                onEngineSettings = {
                    startActivityWithPageTransition(context, PerGameSettingsActivity.createIntent(context, game))
                    // 增强动效档由抽屉在侧推开始时自己平滑收起；普通档保持原版立即关闭
                    if (!MotionMode.enhanced) selectedGame = null
                },
            )
        }
    }

    // ===== 长按卡片：统一操作弹窗（快捷启动 / 桌面快捷方式 / 改名 / 修改封面 / 删除） =====
    quickActionsGame?.let { game ->
        GameQuickActionsDialog(
            game = game,
            quickLaunched = quickLaunch.any { it.uri == game.uri },
            onDismiss = { quickActionsGameUri = null },
            onGameUpdated = { replaceGame(it) },
            onDeleteGame = { onGameDeleted(game) },
            onQuickLaunchToggle = { onQuickLaunchToggle(game) },
        )
    }
}

/**
 * 原生首页（应用设置「首页样式」= 默认时挂载）：快捷启动大卡 + 最近打开列表。
 * 交互：点按开操作抽屉；快捷启动卡长按打开操作弹窗；最近卡片长按删除该条记录（仅删记录不删库）。
 */
@Composable
private fun NativeHomeContent(
    modifier: Modifier,
    libraryState: MainLibraryUiState,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    onRecentRemoved: (ScanGame) -> Unit,
    onCoachAnchorChanged: (Rect) -> Unit,
    isActive: Boolean,
) {
    val quickLaunch = libraryState.quickLaunch
    val recentGames = libraryState.recentGames
    // 横屏/大屏一行五列，竖屏手机保持两列
    val recentColumns = if (isWideScreen()) 5 else 2
    val recentRows = remember(recentGames, recentColumns) { recentGames.chunked(recentColumns) }
    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.nav_home))

        // ===== 快捷启动卡与最近打开列表合并为同一个滚动列表 =====
        // 水平内边距统一由 contentPadding 提供（覆盖快捷启动区与列表行）
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = 16.dp + glassNavBottomInset(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!libraryState.loaded) {
                // 加载中：快捷启动区（空态）在顶部，指示器占满剩余空间居中
                item(key = "loading", contentType = "loading") {
                    Column(Modifier.fillParentMaxSize()) {
                        QuickLaunchSection(
                            quickLaunch = quickLaunch,
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                            onCoachAnchorChanged = onCoachAnchorChanged,
                            isActive = isActive,
                        )
                        Box(Modifier.fillMaxWidth().weight(1f).staggeredEntrance(1), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            } else if (recentGames.isEmpty()) {
                item(key = "recent_empty", contentType = "recent_empty") {
                    Column(Modifier.fillParentMaxSize()) {
                        QuickLaunchSection(
                            quickLaunch = quickLaunch,
                            onGameClick = onGameClick,
                            onGameLongClick = onGameLongClick,
                            onCoachAnchorChanged = onCoachAnchorChanged,
                            isActive = isActive,
                        )
                        Box(Modifier.fillMaxWidth().weight(1f).staggeredEntrance(1), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.home_recent_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PageSecondaryTextColor,
                            )
                        }
                    }
                }
            } else {
                item(key = "quick_launch", contentType = "quick_launch") {
                    QuickLaunchSection(
                        modifier = if (MotionMode.enhanced) {
                            Modifier.animateItem(
                                fadeInSpec = MotionTokens.fadeIn(),
                                placementSpec = MotionTokens.layout(IntOffset.VisibilityThreshold),
                                fadeOutSpec = MotionTokens.fadeOut(),
                            )
                        } else {
                            Modifier
                        },
                        quickLaunch = quickLaunch,
                        onGameClick = onGameClick,
                        onGameLongClick = onGameLongClick,
                        onCoachAnchorChanged = onCoachAnchorChanged,
                        isActive = isActive,
                    )
                }
                itemsIndexed(
                    items = recentRows,
                    key = { _, row -> row.first().uri },
                    contentType = { _, _ -> "recent_row" },
                ) { rowIndex, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .staggeredEntrance(rowIndex + 1)
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { game ->
                            RecentGameCard(
                                game = game,
                                modifier = Modifier.weight(1f),
                                onClick = { onGameClick(game) },
                                onLongClick = { onRecentRemoved(game) },
                            )
                        }
                        repeat(recentColumns - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 快捷启动区（最多 3 个）：小屏（可用宽度 < 600dp，竖屏手机）单张大卡左右滑动切换（限宽居中）；
 * 大屏（横屏/平板）一行三个卡位**占满可用宽度**，空槽显示占位。
 */
@Composable
private fun QuickLaunchSection(
    quickLaunch: List<ScanGame>,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    modifier: Modifier = Modifier,
    onCoachAnchorChanged: (Rect) -> Unit = {},
    isActive: Boolean = true,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().staggeredEntrance(0).coachAnchor(isActive, onCoachAnchorChanged),
        contentAlignment = Alignment.Center,
    ) {
        if (maxWidth >= 600.dp) {
            // 大屏（平板/横屏）三张卡并排**占满可用宽度**（不再设 900dp 上限，
            // 否则平板/大窗口下两侧会空出一条）；卡内封面宽度有 99dp 上限、文字列自适应，
            // 因此拉宽不会挤压内容。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(3) { i ->
                    val game = quickLaunch.getOrNull(i)
                    // 按游戏 uri 做 key：删除卡片后槽位状态跟随游戏而不是按位置复用，
                    // 避免复用被删卡的封面 MutableState（上一个封面串到另一张卡）。
                    key(game?.uri ?: "quick_launch_empty_$i") {
                        if (game != null) {
                            QuickLaunchCard(
                                game = game,
                                onClick = { onGameClick(game) },
                                onLongClick = { onGameLongClick(game) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            QuickLaunchEmptyCard(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else if (quickLaunch.isEmpty()) {
            QuickLaunchEmptyCard(modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth())
        } else {
            val pagerState = rememberPagerState { quickLaunch.size }
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    pageSpacing = 10.dp,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val game = quickLaunch.getOrNull(page) ?: return@HorizontalPager
                    // 按游戏 uri 做 key：删除卡片后页面状态跟随游戏而不是按位置复用，
                    // 避免复用被删卡的封面 MutableState（上一个封面串到另一张卡）。
                    key(game.uri) {
                        QuickLaunchCard(
                            game = game,
                            onClick = { onGameClick(game) },
                            onLongClick = { onGameLongClick(game) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // 口径：0/1 项不显示指示；2–3 项在卡片下方显示「当前页胶囊 + 其余圆点」
                if (quickLaunch.size > 1) {
                    QuickLaunchPagerIndicator(
                        pageCount = quickLaunch.size,
                        currentPage = pagerState.currentPage,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * 快捷启动翻页指示：当前页为加宽胶囊（主题色实底 + 同色柔光），其余为半透明小圆点。
 * 只在可滑动（2–3 项）的小屏单卡形态下绘制于卡片下方；大屏三卡并排与 0/1 项不显示。
 */
@Composable
private fun QuickLaunchPagerIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    val activePage = currentPage.coerceIn(0, pageCount - 1)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == activePage
            val width by animateDpAsState(
                targetValue = if (selected) 24.dp else 8.dp,
                animationSpec = if (MotionMode.enhanced) MotionTokens.control(0.1.dp) else tween(durationMillis = 200),
                label = "quickLaunchIndicatorWidth$index",
            )
            val animatedColor by animateColorAsState(
                targetValue = if (selected) primary else primary.copy(alpha = 0.32f),
                animationSpec = MotionTokens.fadeIn(),
                label = "quickLaunchIndicatorColor$index",
            )
            val color = if (MotionMode.enhanced) animatedColor else if (selected) primary else primary.copy(alpha = 0.32f)
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .then(
                        if (selected) {
                            Modifier.shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = color,
                                spotColor = color,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * 首页快捷启动大卡：封面高斯模糊铺满整卡作背景（无封面/加载中退回中性深灰底），
 * 左上角引擎类型（下方游戏名）、右侧游戏封面。交互与游戏页统一——点按开操作抽屉、长按打开操作弹窗。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickLaunchCard(
    game: ScanGame,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val pressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    BoxWithConstraints(
        modifier = modifier
            .pressSquish(scale = PressSquishDefaults.CardScale)
            .height(172.dp)
            .clip(AppComponentShape)
            .background(QuickLaunchFallback)
            .glassBorder(),
    ) {
        val engineName = when (game.engine) {
            EngineType.UNKNOWN -> stringResource(R.string.engine_name_unknown)
            // 卡片用短名「Switch」，避免「Nintendo Switch」过长
            EngineType.NINTENDO_SWITCH -> stringResource(R.string.engine_name_switch)
            EngineType.RUNTIME_MODULE -> stringResource(R.string.engine_name_runtime_module)
            else -> game.engine.displayName
        }
        val cardMaxWidth = maxWidth
        val coverBitmap by rememberCoverBitmap(game.coverUri)
        // 背景封面加载完成后淡入
        val animatedBgAlpha by animateFloatAsState(
            targetValue = if (coverBitmap != null) 1f else 0f,
            animationSpec = MotionTokens.fadeIn(0.001f),
            label = "quickLaunchBgFadeIn",
        )
        coverBitmap?.let { bmp ->
            // 封面缩到 40px 再拉伸铺满：API 31+ 由 Modifier.blur（RenderEffect）做真高斯；
            // API 26-30 无 RenderEffect，若只放大 40px 会呈马赛克（issue #76），
            // 因此对小图先做 CPU 盒式模糊（3 轮近似高斯）再拉伸，保证低版本同样柔和。
            val blurSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            val blurred = remember(bmp, blurSupported) {
                val src = bmp.asAndroidBitmap()
                val width = 40
                val height = (src.height * width / src.width).coerceAtLeast(1)
                val scaled = android.graphics.Bitmap.createScaledBitmap(src, width, height, true)
                if (blurSupported) {
                    scaled.asImageBitmap()
                } else {
                    val smallWidth = scaled.width
                    val smallHeight = scaled.height
                    val pixels = IntArray(smallWidth * smallHeight)
                    scaled.getPixels(pixels, 0, smallWidth, 0, 0, smallWidth, smallHeight)
                    boxBlurArgb(pixels, smallWidth, smallHeight, radius = 3)
                    android.graphics.Bitmap
                        .createBitmap(pixels, smallWidth, smallHeight, android.graphics.Bitmap.Config.ARGB_8888)
                        .asImageBitmap()
                }
            }
            Image(
                bitmap = blurred,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer {
                  alpha = if (MotionMode.enhanced) animatedBgAlpha else 1f // 此处 coverBitmap 已非空，原非增强分支恒为 1f
                }.blur(24.dp),
            )
            // 黑色压暗遮罩：保证白色文字可读
            Box(Modifier.matchParentSize().graphicsLayer {
              alpha = if (MotionMode.enhanced) animatedBgAlpha else 1f
            }.background(Color.Black.copy(alpha = 0.25f)))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(pressModifier)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        engineName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        game.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // 封面按卡宽取比例宽度（大屏三卡并排时自动缩小，给文字留空间），上限 99dp，
                // 垂直居中于文字行高度内；左侧与文字列保持 12dp 间距
                Box(
                    modifier = Modifier.fillMaxHeight().padding(start = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverWidth = (cardMaxWidth * 0.32f).coerceAtMost(99.dp)
                    Box(
                        modifier = Modifier
                            .width(coverWidth)
                            .height(coverWidth * 4f / 3f)
                            .clip(AppComponentShape)
                            .background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BlurCrossfade(
                            targetState = coverBitmap,
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                            contentKey = { it != null },
                        ) { bmp ->
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = game.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // 替换容器会把内容撑满封面位：外包一层居中，保持原先居中的占位文字
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "Tyranor",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
                // 封面右侧的指向箭头，仅作视觉引导，不单独响应点击；左侧与封面间距 9dp
                Icon(
                    painter = painterResource(R.drawable.ic_quick_launch_arrow),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = 9.dp)
                        .size(24.dp),
                )
            }
        }
    }
}

/** 快捷启动空状态：尚未设置任何快捷启动时显示整张白色卡片 + 加号。 */
@Composable
private fun QuickLaunchEmptyCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(172.dp)
            .clip(AppComponentShape)
            .background(NavWhite)
            .glassBorder(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = stringResource(R.string.home_empty_slot_content_description),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            stringResource(R.string.home_quick_launch),
            style = MaterialTheme.typography.bodyMedium,
            color = TextColor,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 最近打开卡片：竖屏两列、横屏/大屏五列网格，封面铺满卡片（无封面时用引擎分类色底），
 * 底部信息面板仅显示游戏名；点按打开操作抽屉，长按删除该条最近记录。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentGameCard(
    game: ScanGame,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val coverBitmap by rememberCoverBitmap(game.coverUri)
    val bmp = coverBitmap
    val coverAlpha by animateFloatAsState(
        targetValue = if (bmp != null) 1f else 0f,
        animationSpec = if (MotionMode.enhanced) MotionTokens.fadeIn(0.001f) else tween(durationMillis = 300),
        label = "recentCoverFadeIn",
    )
    Box(
        modifier
            .pressSquish(scale = PressSquishDefaults.CardScale)
            .aspectRatio(1f)
            .clip(AppComponentShape)
            .background(game.engine.coverColor())
            .glassBorder()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.common_delete),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .motionBlurAlpha(
                        alpha = { coverAlpha },
                        radius = { (1f - coverAlpha) * 8.dp.toPx() },
                    ),
            )
        } else {
            Text(
                "Tyranor",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        // 底部信息面板：仅展示游戏名（比卡片默认标题小一号）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(NavWhiteOverlay)
                .padding(horizontal = 12.dp, vertical = 12.5.dp),
        ) {
            Text(
                game.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
