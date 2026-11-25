package com.tyranor.next.ui.game

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.AbsoluteCutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.playtime.GamePlaytimeRepository
import com.tyranor.next.core.game.playtime.GamePlaytimeStats
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBorder
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.ShelfBackdropAlpha
import com.tyranor.next.theme.ShelfCardSurface
import com.tyranor.next.theme.ShelfContactShadow
import com.tyranor.next.theme.ShelfFaceShade
import com.tyranor.next.theme.ShelfGlassBase
import com.tyranor.next.theme.ShelfGlassSheen
import com.tyranor.next.theme.ShelfGlassSpecular
import com.tyranor.next.theme.ShelfGloss
import com.tyranor.next.theme.ShelfChamferDim
import com.tyranor.next.theme.ShelfChamferLight
import com.tyranor.next.theme.ShelfLedgeRim
import com.tyranor.next.theme.ShelfLedgeSurface
import com.tyranor.next.theme.ShelfPlaySurface
import com.tyranor.next.theme.ShelfRibbonDeep
import com.tyranor.next.theme.ShelfRibbonShadow
import com.tyranor.next.theme.ShelfReflectionAlpha
import com.tyranor.next.theme.ShelfBoardEdge
import com.tyranor.next.theme.ShelfDepthShade
import com.tyranor.next.theme.ShelfFog
import com.tyranor.next.theme.ShelfKnobGlass
import com.tyranor.next.theme.ShelfKnobGlow
import com.tyranor.next.theme.ShelfTrackInk
import com.tyranor.next.theme.ShelfTrackOutline
import com.tyranor.next.theme.ShelfWallSurface
import com.tyranor.next.theme.ShelfSpineScrimBottom
import com.tyranor.next.theme.ShelfSpineScrimTop
import com.tyranor.next.theme.ShelfSpineRoundLight
import com.tyranor.next.theme.ShelfSpineRoundShade
import com.tyranor.next.theme.ShelfSpineTitle
import com.tyranor.next.theme.ShelfSpineText
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassEdgeStroke
import com.tyranor.next.ui.common.boxBlurArgb
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.common.motion.rotationMorphBlock
import com.tyranor.next.ui.common.motion.sinkBleedX
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.BlurVisibility
import com.tyranor.next.ui.common.motion.CoverReveal
import com.tyranor.next.ui.common.motion.CoverSurface
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.PagerTickHaptics
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.RollingNumberText
import com.tyranor.next.ui.common.motion.dissolveTint
import com.tyranor.next.ui.common.motion.dissolvingGames
import com.tyranor.next.ui.common.motion.pagePosition
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.rememberCoverReveal
import com.tyranor.next.ui.common.motion.rememberLastNonNull
import com.tyranor.next.ui.common.motion.rememberPagerVelocity
import com.tyranor.next.ui.common.motion.staggeredEntrance
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 游戏页「书架」卡片风格：游戏像一盒盒立在玻璃搁板上的游戏盒，整体气质照 galgame 标题画面来做。
 *
 * - 背景是当前游戏封面的大面积柔焦色晕（像 CG 背景），随滑动位置在相邻两本之间实时混合；
 * - 一排书立成一条水平直线、贴着下方对话框；居中的一盒正面朝外、被「抽出」（抬起、放大、外倾），封面底部浮着
 *   一枚单层的 ▶ 玻璃小球，点一下直接启动；其余侧转露出书脊（封面色调 + 书名——中日文逐字竖排、西文整行侧转——
 *   + 烟灰书腰与序号），每盒高矮略有不同、有的微微歪着；上次游玩的那一本封面上挂一根小书签丝带；
 * - 游戏很少时盒子转得浅一些，两侧也露出封面，不会只剩一排孤零零的书脊；
 * - 下方是 ADV 对话框：名牌写引擎，正文是游戏名与一句打字机台词（短短一句，没有说明与角标），再下面是累计时长 /
 *   上次游玩 / 最近一次三格数据；宽屏时左边另有「我的书架」卡；
 * - 最下面是一根似有似无的进度轨道（静止时很淡，滑动 / 拖动时浮现），拖动快速定位，气泡提示正在经过的游戏。
 *
 * 横向拖动 / 甩动书架翻找（甩一下能翻过一串）；点书脊先把那一盒转到正中，点正中的盒子把它抽出来翻开（[book]，
 * 见 `ShelfBook.kt`，代替其他风格的操作抽屉），长按打开操作弹窗。打开时直接停在最近玩过的那一盒。
 */
@Composable
internal fun GameShelf(
    games: List<ScanGame>,
    isInteractive: () -> Boolean,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    book: ShelfBookStage? = null,
    catalog: ShelfCatalogStage? = null,
    initialUri: () -> String? = { null },
    onCurrentGameChanged: ((ScanGame) -> Unit)? = null,
    onSettledGameChanged: ((String) -> Unit)? = null,
) {
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val latestGames by rememberUpdatedState(games)
    val pagerState = remember { PagerState(currentPage = shelfFocusPage(games, initialUri()), pageCount = { latestGames.size }) }
    val scope = rememberCoroutineScope()
    val knobSeek = rememberShelfKnobSeek()
    val scrubbing = remember(pagerState) { mutableStateOf(false) }

    val notifyCurrentGame by rememberUpdatedState(onCurrentGameChanged)
    val notifySettledGame by rememberUpdatedState(onSettledGameChanged)
    val latestInteractive by rememberUpdatedState(isInteractive)
    val pendingUri = catalog?.pendingFocusUri
    val focusToken = catalog?.focusRequestToken
    val interactive = isInteractive()
    val adjustment = remember(pagerState, games) {
        Snapshot.withoutReadObservation {
            val uri = pagerState.currentShelfPageKey() ?: return@withoutReadObservation null
            val page = shelfPageAdjustment(games, pagerState.currentPage, uri) ?: return@withoutReadObservation null
            page to if (games[page].uri == uri) pagerState.currentPageOffsetFraction else 0f
        }
    }
    LaunchedEffect(pagerState, games) {
        if (adjustment != null && latestInteractive() && catalog?.pendingFocusUri == null) {
            pagerState.requestScrollToPage(adjustment.first, adjustment.second)
        }
    }
    LaunchedEffect(pagerState, games, catalog, focusToken, pendingUri, interactive) {
        if (!latestInteractive() || games.isEmpty()) return@LaunchedEffect
        if (catalog != null && focusToken != null && pendingUri != null) {
            val requestPage = games.indexOfFirst { it.uri == pendingUri }
            if (requestPage < 0) {
                if (latestGames.none { it.uri == pendingUri }) catalog.clearPendingFocus(focusToken)
                return@LaunchedEffect
            }
            fun requestIsPending() = catalog.focusRequestToken == focusToken && catalog.pendingFocusUri == pendingUri &&
                latestGames.getOrNull(requestPage)?.uri == pendingUri && latestInteractive()
            if (!requestIsPending()) return@LaunchedEffect
            if (!pagerState.isShelfPageCentered(requestPage, pendingUri)) {
                with(pagerState) {
                    scroll(MutatePriority.PreventUserInput) {
                        if (requestIsPending()) updateCurrentPage(requestPage)
                    }
                }
            }
            snapshotFlow { !requestIsPending() || pagerState.isShelfPageCentered(requestPage, pendingUri) }.first { it }
            if (requestIsPending() && pagerState.isShelfPageCentered(requestPage, pendingUri)) {
                catalog.markFocusAligned(focusToken, pendingUri)
            }
        }
    }
    LaunchedEffect(pagerState, catalog) {
        snapshotFlow {
            val page = pagerState.currentPage
            val uri = latestGames.getOrNull(page)?.uri
            uri?.takeIf { latestInteractive() && catalog?.pendingFocusUri == null && pagerState.currentShelfPageKey() == it }
        }.collect { uri ->
            if (uri != null && latestInteractive()) {
                latestGames.getOrNull(pagerState.currentPage)?.takeIf { it.uri == uri }?.let { notifyCurrentGame?.invoke(it) }
            }
        }
    }
    LaunchedEffect(pagerState, catalog) {
        snapshotFlow {
            if (pagerState.isScrollInProgress || scrubbing.value || knobSeek.active ||
                !latestInteractive() || catalog?.pendingFocusUri != null
            ) {
                null
            } else {
                val page = pagerState.settledPage
                latestGames.getOrNull(page)?.uri?.takeIf { pagerState.isShelfPageCentered(page, it) }
            }
        }.collect { uri -> if (uri != null) notifySettledGame?.invoke(uri) }
    }

    // 翻开的书用宋体：书架一出现就在后台把系统字体读好，不落到书起飞的第一帧上
    if (book != null) {
        val locale = LocalLocale.current.platformLocale
        LaunchedEffect(locale) { withContext(Dispatchers.IO) { warmShelfBookSerif(locale) } }
    }
    val listChangedAt = rememberCoverBatchChange(games)
    // 点按进度轨道跳转时旋钮自己飞过去；跳转途中翻页触觉静音（只在点按那一下震）
    // 手指翻过一本书脊轻点一下（程序化滚动、刻度尺定位不重复震）
    PagerTickHaptics(pagerState, muted = { knobSeek.active })
    // 每本封面的主色（背景色晕解出来时顺手算一次）：正中那一盒脚下与身后的光用它
    val coverTints = remember { mutableStateMapOf<String, Color>() }
    LaunchedEffect(games) {
        val active = games.mapNotNullTo(HashSet(games.size)) { it.coverUri }
        coverTints.keys.retainAll(active)
    }
    // 直接启动：不经操作抽屉，走与桌面快捷方式相同的启动蹦床（Artemis 补丁确认、存档格式确认、SystemNNN、
    // 错误提示都在里面），书架只负责发起
    val launchContext = LocalContext.current
    val launchGame: (ScanGame) -> Unit = { game ->
        if (isInteractive()) launchContext.startActivity(GameShortcutActivity.createIntent(launchContext, game.uri))
    }
    val centerGame: (Int) -> Unit = { page ->
        if (isInteractive()) scope.launch { if (isInteractive()) pagerState.centerCover(page) }
    }

    // 书正在合上时书架被拨动（拖动、甩动、点别的书脊、拖进度条）：合上的书不再飞回，就地淡出（见 ShelfBookStage.interruptClosing）
    LaunchedEffect(pagerState, book) {
        if (book == null) return@LaunchedEffect
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling -> if (scrolling) book.interruptClosing() }
    }

    Box(Modifier.fillMaxSize().shelfTouchIntent { if (isInteractive()) catalog?.interruptExit() }) {
        ShelfBackdrop(pagerState, games, coverTints, Modifier.fillMaxSize())
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = glassNavBottomInset()),
        ) {
            val chrome = shelfChromeForHeight(maxHeight, LocalDensity.current.fontScale, games.size > 1)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                ShelfStage(
                    games = games,
                    pagerState = pagerState,
                    hideTitleTag = hideTitleTag,
                    batchChangedAt = listChangedAt,
                    coverTints = coverTints,
                    book = book,
                    catalog = catalog,
                    isInteractive = isInteractive,
                    onGameClick = onGameClick,
                    onGameLongClick = onGameLongClick,
                    onPlay = launchGame,
                    onCenter = centerGame,
                    // 旋转过渡：书架的书不逐帧重排，第一帧按最终尺寸排好后等比缩放贴进分给它的矩形（见 rotationMorphBlock）
                    modifier = Modifier.fillMaxWidth().weight(1f).rotationMorphBlock(),
                )
                if (chrome.infoHeight > 0.dp) {
                    ShelfInfoRow(
                        pagerState = pagerState,
                        knobSeek = knobSeek,
                        scrubbing = scrubbing,
                        games = games,
                        hideTitleTag = hideTitleTag,
                        onContinue = centerGame,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = chrome.infoHeight)
                            .shelfCatalogChromeRecede(catalog)
                            .staggeredEntrance(SHELF_INFO_ENTRANCE_INDEX, distance = 20.dp, stepMillis = 40L)
                            .padding(start = 20.dp, end = 20.dp, top = 6.dp),
                    )
                }
                if (chrome.showScrubber) {
                    ShelfScrubber(
                        pagerState = pagerState,
                        knobSeek = knobSeek,
                        scrubbingState = scrubbing,
                        games = games,
                        hideTitleTag = hideTitleTag,
                        isInteractive = isInteractive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .shelfCatalogChromeRecede(catalog)
                            .staggeredEntrance(SHELF_SCRUBBER_ENTRANCE_INDEX, distance = 16.dp, stepMillis = 40L)
                            .padding(top = SCRUBBER_TOP_SPACE, bottom = SCRUBBER_BOTTOM_SPACE),
                    )
                } else if (chrome.singleGameSpacer) {
                    Spacer(Modifier.height(SINGLE_GAME_SPACER))
                }
            }
        }
    }
}

internal data class ShelfChrome(val infoHeight: Dp, val showScrubber: Boolean, val singleGameSpacer: Boolean)

internal fun shelfChromeForHeight(height: Dp, fontScale: Float, multipleGames: Boolean): ShelfChrome {
    val spare = height - MIN_STAGE_HEIGHT
    val bottomSpace = if (multipleGames) RULER_HEIGHT + SCRUBBER_TOP_SPACE + SCRUBBER_BOTTOM_SPACE else SINGLE_GAME_SPACER
    val showBottomSpace = spare >= bottomSpace
    val infoHeight = (spare - if (showBottomSpace) bottomSpace else 0.dp)
        .takeIf { it >= MIN_INFO_HEIGHT * fontScale.coerceAtLeast(1f) } ?: 0.dp
    return ShelfChrome(infoHeight, multipleGames && showBottomSpace, !multipleGames && showBottomSpace)
}

internal fun shelfStageScale(heightBudget: Dp): Float = (heightBudget / MIN_STAGE_HEIGHT).coerceIn(0f, 1f)

internal fun shelfCoverWidthForHeight(heightBudget: Dp): Dp =
    MIN_COVER_WIDTH * shelfStageScale(heightBudget) +
        ((heightBudget - MIN_STAGE_HEIGHT) * (0.75f / (1f + FLOOR_RATIO + LIFT_ROOM_RATIO))).coerceAtLeast(0.dp)

internal fun shelfFocusPage(games: List<ScanGame>, uri: String?): Int =
    games.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }
        ?: games.indices.maxByOrNull { games[it].openTime }?.takeIf { games[it].openTime > 0L }
        ?: 0

internal fun shelfPageAdjustment(games: List<ScanGame>, page: Int, measuredUri: String): Int? =
    if (games.isEmpty() || games.getOrNull(page)?.uri == measuredUri) null else shelfFocusPage(games, measuredUri)

private fun PagerState.currentShelfPageKey(): String? =
    layoutInfo.visiblePagesInfo.firstOrNull { it.index == currentPage }?.key as? String

private fun PagerState.isShelfPageCentered(page: Int, uri: String): Boolean =
    currentPage == page && currentPageOffsetFraction == 0f && !isScrollInProgress &&
        layoutInfo.visiblePagesInfo.any { it.index == page && it.key == uri && it.offset == 0 }

/** 书架主体：玻璃搁板与一排游戏盒。 */
@Composable
private fun ShelfStage(
    games: List<ScanGame>,
    pagerState: PagerState,
    hideTitleTag: Boolean,
    batchChangedAt: Long,
    coverTints: Map<String, Color>,
    book: ShelfBookStage?,
    catalog: ShelfCatalogStage?,
    isInteractive: () -> Boolean,
    onGameClick: (ScanGame) -> Unit,
    onGameLongClick: (ScanGame) -> Unit,
    onPlay: (ScanGame) -> Unit,
    onCenter: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 书架落在舞台底部、贴着下面的对话框：多出来的高度留在上方，让夜景的「天空」透出来（像 galgame 里立绘站在
    // 画面下方、上面是背景 CG），而不是在搁板和对话框之间空出一截
    BoxWithConstraints(modifier.shelfCatalogStageRecede(catalog, LocalDensity.current.density), contentAlignment = Alignment.BottomCenter) {
        if (maxWidth < 1.dp || maxHeight < 1.dp) return@BoxWithConstraints
        // 封面宽取「容器宽度一定比例」与「可用高度能容下的宽度」的较小值：横屏 / 平板时书脊更多、盒子不被裁切。
        // 游戏很少时盒子大一点，撑起画面。
        // 平板竖屏（又宽又高的舞台）：封面放大、整座书架抬高——原来按手机的比例放，封面只占舞台一半高，
        // 上面是一大片空，书架贴着下面的对话框，上下都显得空
        val tall = maxWidth >= TALL_STAGE_MIN_WIDTH && maxHeight >= maxWidth * TALL_STAGE_MIN_ASPECT
        val widthRatio = when {
            tall -> COVER_WIDTH_RATIO_TALL
            games.size <= FEW_GAMES -> COVER_WIDTH_RATIO_FEW
            else -> COVER_WIDTH_RATIO
        }
        val heightBudget = if (tall) maxHeight * TALL_STAGE_FILL else maxHeight
        val compactScale = shelfStageScale(heightBudget)
        val lift = LIFT * compactScale
        val belowBoard = SHELF_BELOW_BOARD.dp * compactScale
        val coverWidth = minOf(
            maxWidth * widthRatio,
            shelfCoverWidthForHeight(heightBudget),
        )
        val coverHeight = coverWidth * 4f / 3f
        val floorDepth = coverHeight * FLOOR_RATIO
        val topRoom = LIFT_ROOM * compactScale + coverHeight * LIFT_ROOM_RATIO
        // 多出来的高度按比例分给搁板下面（抬高底板）与书顶上面：不再整块堆在上面
        val raise = if (tall) {
            ((maxHeight - (topRoom + coverHeight + floorDepth + belowBoard)) * TALL_STAGE_RAISE).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        val spineDepth = (coverWidth * SPINE_RATIO).coerceIn(MIN_SPINE, MAX_SPINE)
        val density = LocalDensity.current
        val count = games.size
        val viewport = with(density) { maxWidth.toPx() }
        val endPad = with(density) { (BOOKEND_WIDTH.dp + BOOKEND_CLEARANCE.dp + ROW_END_MARGIN).toPx() }
        val geometry = remember(coverWidth, spineDepth, count, viewport, endPad, density) {
            with(density) {
                fittingShelfGeometry(count, viewport, endPad) { turn ->
                    ShelfGeometry(
                        coverWidth = coverWidth.toPx(),
                        coverHeight = coverHeight.toPx(),
                        spineDepth = spineDepth.toPx(),
                        gap = SPINE_GAP.toPx(),
                        restGap = REST_GAP.toPx(),
                        maxTurn = turn,
                    )
                }
            }
        }
        // 手指拖过「一本」的距离 = 正中那一盒移到相邻位置走过的距离：盒子与手指同速移动（跟手、不飘），
        // 也远大于书脊间距，轻碰不会滑到下一个游戏
        val dragPitch = geometry.dragPitch
        val dragPitchDp = with(density) { dragPitch.coerceAtLeast(1f).toDp() }
        val sidePadding = ((maxWidth - dragPitchDp) / 2).coerceAtLeast(0.dp)
        // 书架投影：已放平（不再倾斜），投影为恒等，当前那盒停在正中；接口保留，各处照常调用
        val rail = remember(viewport, geometry) { ShelfRail(viewport, geometry.coverHeight * RAIL_EYE) }
        val anchor = viewport * RAIL_ANCHOR
        // 整排平移：放得下就整排居中，放不下时当前那盒停在正中、翻到两端贴边（只在布局 / 绘制阶段读取翻页位置）
        val visibleStart = rail.railX(-viewport / 2f)
        val visibleEnd = rail.railX(viewport / 2f)
        val rowShift: () -> Float = remember(pagerState, geometry, count, viewport, endPad, anchor, visibleStart, visibleEnd) {
            { geometry.rowShift(pagerState.pagePosition(), count, viewport, endPad, anchor, visibleStart, visibleEnd) }
        }
        // Pager 按拖动间距决定组合哪些页，而屏幕上按更密的书脊间距排、整排平移还可能再挪进半屏：
        // 补足屏幕上看得到却不在 Pager 视口里的那几本，免得边上出现空位
        val maxShift = (viewport / 2f - geometry.coverWidth / 2f - endPad).coerceAtLeast(0f)
        val booksPerSide = kotlin.math.ceil((viewport / 2f + maxShift - geometry.coverWidth / 2f) / geometry.pitch).toInt() + 1
        val pagerPerSide = (viewport / 2f / dragPitch).toInt()
        val beyond = (booksPerSide - pagerPerSide + 1).coerceIn(1, MAX_BEYOND_BOOKS)
        // 抽出来的那一盒的封面解码档位：按它在屏幕上最多放大到的像素宽度
        val heroTier = remember(coverWidth, density) {
            CoverDecodeTier.forWidth(with(density) { (coverWidth * SHELF_HERO_MAX_ZOOM).roundToPx() })
        }
        val chamfer = remember(coverWidth) { shelfChamfer(coverWidth) }
        val dims = remember(coverWidth, coverHeight, spineDepth, floorDepth, topRoom, belowBoard, lift, heroTier, chamfer) {
            ShelfDims(coverWidth, coverHeight, spineDepth, floorDepth, topRoom, belowBoard, lift, heroTier, chamfer)
        }
        val sharedShapes = remember(chamfer) { ShelfBoxShapes(chamfer) }
        if (catalog != null) {
            val currentOriginProvider by rememberUpdatedState<(Offset, androidx.compose.ui.unit.IntSize, String?) -> List<ShelfCatalogBookOrigin>> { stageTopLeft, stageSize, uriFilter ->
                if (stageSize.width <= 0 || stageSize.height <= 0 || games.isEmpty()) {
                    return@rememberUpdatedState emptyList()
                }
                val raisePx = with(density) { raise.toPx() }
                val floorYScreen = stageTopLeft.y + stageSize.height - raisePx - with(density) { (dims.floorDepth + dims.belowBoard).toPx() }
                val desiredInnerHeightPx = with(density) { (dims.topRoom + dims.coverHeight + dims.floorDepth + dims.belowBoard).toPx() }
                val stageOverflowPx = (desiredInnerHeightPx - (stageSize.height.toFloat() - raisePx)).coerceAtLeast(0f)
                val pagerZeroCenterX = stageTopLeft.x + with(density) { sidePadding.toPx() } + dragPitch * 0.5f
                val liftPx = with(density) { dims.lift.toPx() }
                val position = pagerState.pagePosition()
                val shift = rowShift()
                if (uriFilter != null) {
                    val page = games.indexOfFirst { it.uri == uriFilter }
                    if (page < 0) return@rememberUpdatedState emptyList()
                    val game = games[page]
                    return@rememberUpdatedState listOf(
                        shelfCatalogBookOriginFromGeometry(
                            uri = game.uri,
                            pageIndex = page,
                            offset = page - position,
                            geometry = geometry,
                            rowShiftPx = shift,
                            rail = rail,
                            pagerZeroCenterX = pagerZeroCenterX,
                            floorYScreen = floorYScreen,
                            liftPx = liftPx,
                            trait = shelfBookTrait(game.uri),
                            tint = coverTints[game.coverUri] ?: game.engine.coverColor(),
                            stageOverflowPx = stageOverflowPx,
                        ),
                    )
                }
                val center = kotlin.math.round(position).toInt().coerceIn(0, games.lastIndex)
                val start = (center - MAX_BEYOND_BOOKS).coerceAtLeast(0)
                val end = (center + MAX_BEYOND_BOOKS).coerceAtMost(games.lastIndex)
                val list = ArrayList<ShelfCatalogBookOrigin>(end - start + 1)
                for (page in start..end) {
                    val game = games[page]
                    list += shelfCatalogBookOriginFromGeometry(
                        uri = game.uri,
                        pageIndex = page,
                        offset = page - position,
                        geometry = geometry,
                        rowShiftPx = shift,
                        rail = rail,
                        pagerZeroCenterX = pagerZeroCenterX,
                        floorYScreen = floorYScreen,
                        liftPx = liftPx,
                        trait = shelfBookTrait(game.uri),
                        tint = coverTints[game.coverUri] ?: game.engine.coverColor(),
                        stageOverflowPx = stageOverflowPx,
                    )
                }
                list
            }
            DisposableEffect(catalog, dims.heroTier) {
                val stableProvider: (Offset, androidx.compose.ui.unit.IntSize, String?) -> List<ShelfCatalogBookOrigin> = { tl, sz, uri ->
                    currentOriginProvider(tl, sz, uri)
                }
                catalog.bindStageGeometry(dims.heroTier, stableProvider)
                onDispose { catalog.unbindStageGeometry(stableProvider) }
            }
        }
        val velocity = rememberPagerVelocity(pagerState)
        val accent = MaterialTheme.colorScheme.primary
        val wallColor = ShelfWallSurface
        val wallShadeColor = ShelfDepthShade
        val boardColor = ShelfLedgeSurface
        val boardEdgeColor = ShelfBoardEdge
        val rimColor = ShelfLedgeRim
        val caseColors = remember(accent, wallColor, wallShadeColor, boardColor, boardEdgeColor, rimColor) {
            ShelfCaseColors(
                wall = wallColor,
                wallShade = wallShadeColor,
                board = boardColor,
                boardEdge = boardEdgeColor,
                rim = rimColor,
                glow = accent.copy(alpha = LEDGE_GLOW_ALPHA),
            )
        }
        // 正中那一盒脚下与身后的光：封面的主色（还没解出来时用引擎色），翻页途中在相邻两本之间实时过渡
        val glowAt: (Float) -> Color = remember(games, coverTints) {
            { position ->
                val base = kotlin.math.floor(position).toInt().coerceIn(0, games.lastIndex)
                val next = (base + 1).coerceAtMost(games.lastIndex)
                val fraction = (position - base).coerceIn(0f, 1f)
                fun tintOf(page: Int): Color = games[page].let { coverTints[it.coverUri] ?: it.engine.coverColor() }
                androidx.compose.ui.graphics.lerp(tintOf(base), tintOf(next), fraction).copy(alpha = LEDGE_GLOW_ALPHA)
            }
        }
        val floorYPx = with(density) { (topRoom + coverHeight).toPx() }
        val floorDepthPx = with(density) { floorDepth.toPx() }
        val boardEdgePx = with(density) { (SHELF_BOARD_EDGE.dp * compactScale).toPx() }
        val bookHeightPx = with(density) { coverHeight.toPx() }
        Box(
            modifier = Modifier
                .padding(bottom = raise)
                .fillMaxWidth()
                .height(topRoom + coverHeight + floorDepth + belowBoard),
        ) {
            // 搁板与书盒共用宿主全局入场节拍且互不嵌套，切回界面时搁板轻盈就位、书盒依次立起
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    // 搁板通栏铺到两缘：页面下沉时反向横向放大，板的两端不从屏幕边缘缩进去（见 sinkBleedX）
                    .sinkBleedX()
                    .staggeredEntrance(SHELF_CASE_ENTRANCE_INDEX, distance = 12.dp, stepMillis = 40L, replay = false)
                    .shelfCase(
                        positionProvider = { pagerState.pagePosition() },
                        shiftProvider = rowShift,
                        rail = rail,
                        floorY = floorYPx,
                        depth = floorDepthPx,
                        edge = boardEdgePx,
                        bookHeight = bookHeightPx,
                        colors = caseColors,
                        glowProvider = if (count > 0) glowAt else { _ -> caseColors.glow },
                    ),
            )
            HorizontalPager(
                state = pagerState,
                key = { games[it].uri },
                userScrollEnabled = isInteractive(),
                modifier = Modifier.fillMaxSize(),
                pageSize = PageSize.Fixed(dragPitchDp),
                contentPadding = PaddingValues(horizontal = sidePadding),
                beyondViewportPageCount = beyond,
                // 甩动：按手指的力度一路滑行、减速停在某一本上，甩得够猛就滑到头、撞上橡皮筋弹回来
                flingBehavior = rememberShelfFling(pagerState),
                // 翻到两头还往外拉：整排书带着阻力跟出去，松手 Q 弹回来（增强档）
                overscrollEffect = rememberShelfOverscroll(),
            ) { page ->
                val game = games.getOrNull(page) ?: return@HorizontalPager
                ShelfBox(
                    game = game,
                    page = page,
                    pagerState = pagerState,
                    geometry = geometry,
                    slotPitch = dragPitch,
                    viewport = viewport,
                    rowShift = rowShift,
                    rail = rail,
                    dims = dims,
                    shapes = sharedShapes,
                    velocity = velocity,
                    batchChangedAt = batchChangedAt,
                    hideTitleTag = hideTitleTag,
                    book = book,
                    catalog = catalog,
                    coverTints = coverTints,
                    onClick = click@{
                        if (!isInteractive()) return@click
                        if (page == pagerState.currentPage && book?.reopenClosing(game.uri) == true) {
                            // 这一本正在合上、还没落回：点回它就从此刻的样子接着翻开（见 ShelfBookStage.reopenClosing）
                        } else if (page == pagerState.currentPage) {
                            // 抽出来翻开：记下这一盒（位置在书那边按坐标现取）、序号与封面主色
                            book?.capture(
                                uri = game.uri,
                                index = page,
                                count = count,
                                tint = coverTints[game.coverUri] ?: Color.Unspecified,
                                faceShade = geometry.faceShade(0f, spine = false),
                                spineShade = geometry.faceShade(0f, spine = true),
                            )
                            onGameClick(game)
                        } else {
                            onCenter(page)
                        }
                    },
                    onLongClick = { if (isInteractive()) onGameLongClick(game) },
                    onPlay = {
                        if (page == pagerState.currentPage) onPlay(game) else onCenter(page)
                    },
                )
            }
        }
    }
}

/**
 * 书不多时，挑能让整排书（含书立）完整放进屏幕的最浅转角：平板上几本书大方地摊开、两侧也看得到封面，
 * 手机上转得深一些但一本不缺；无论如何都放不下（书多）时才收成标准的一排书脊。
 */
private inline fun fittingShelfGeometry(
    count: Int,
    viewport: Float,
    endPad: Float,
    build: (Float) -> ShelfGeometry,
): ShelfGeometry {
    if (count in 2..FIT_SEARCH_MAX_BOOKS) {
        var degrees = OPEN_TURN_DEGREES
        while (degrees < SPINE_TURN_DEGREES) {
            val candidate = build(Math.toRadians(degrees).toFloat())
            if (candidate.maxRowWidth(count, endPad) <= viewport) return candidate
            degrees += TURN_SEARCH_STEP
        }
    }
    return build(SHELF_SPINE_TURN)
}

/** 一次测量下书架各部分的 dp 尺寸。 */
@Immutable
private class ShelfDims(
    val coverWidth: Dp,
    val coverHeight: Dp,
    val spineDepth: Dp,
    val floorDepth: Dp,
    val topRoom: Dp,
    val belowBoard: Dp,
    val lift: Dp,
    val heroTier: CoverDecodeTier,
    /** 封面开口那一侧两个角的倒角边长。 */
    val chamfer: Dp,
)

/**
 * 书架上的一盒：书脊与正面两个面拼成立体盒子；位置、转角、抬起、倒影全部由翻页位置在布局 / 图层 / 绘制阶段算出。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBox(
    game: ScanGame,
    page: Int,
    pagerState: PagerState,
    geometry: ShelfGeometry,
    slotPitch: Float,
    viewport: Float,
    rowShift: () -> Float,
    rail: ShelfRail,
    dims: ShelfDims,
    shapes: ShelfBoxShapes,
    velocity: State<Float>,
    batchChangedAt: Long,
    hideTitleTag: Boolean,
    book: ShelfBookStage?,
    catalog: ShelfCatalogStage?,
    coverTints: Map<String, Color>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val cover by key(game.coverUri) { rememberCoverBitmap(game.coverUri) }
    if (book != null) {
        DisposableEffect(book, game.uri) { onDispose { book.forget(game.uri) } }
    }
    if (catalog != null) {
        DisposableEffect(catalog, game.uri) { onDispose { catalog.forget(game.uri) } }
    }
    // 抽出来的那一盒按它在屏幕上的实际像素换高清档（书脊和远处的盒子用网格档就够了）：同一时间只有当前那一盒多解一张
    val isHero by remember(pagerState, page) { derivedStateOf { pagerState.currentPage == page } }
    val heroCover by key(game.coverUri) { rememberCoverBitmap(game.coverUri.takeIf { isHero }, dims.heroTier) }
    val faceCover = cover ?: heroCover
    // 删除消散：与其他卡片风格一致，期间触摸由 dissolve 吞掉
    val dissolve = rememberLastNonNull(dissolvingGames[game.uri])
    val reveal = rememberCoverReveal(faceCover != null, CoverSurface.Flow)
    // 仅在入场 / 换批瞬间读取 settledPage，避免翻页停稳后触发全部 20~49 个 ShelfBox 全员重组
    val anchorPage = remember(batchChangedAt) { pagerState.settledPage }
    val density = LocalDensity.current
    val floorY = with(density) { (dims.topRoom + dims.coverHeight).toPx() }
    val floorDepth = with(density) { dims.floorDepth.toPx() }
    val lift = with(density) { dims.lift.toPx() }
    val engineColor = game.engine.coverColor()
    val reflectionAlpha = ShelfReflectionAlpha
    val trait = remember(game.uri) { shelfBookTrait(game.uri) }
    val errorColor = MaterialTheme.colorScheme.error
    val chamferLight = ShelfChamferLight
    val chamferDim = ShelfChamferDim
    val fog = ShelfFog
    // 活跃局部区隔离：离开正中 > 2.0 本的书脊共用夹断偏移，滑动途中不触发宽度重测与倒影/姿态重录
    val localOffset = remember(pagerState, page) {
        derivedStateOf {
            val o = page - pagerState.pagePosition()
            when {
                o > SHELF_ACTIVE_ZONE -> SHELF_ACTIVE_ZONE_CLAMP
                o < -SHELF_ACTIVE_ZONE -> -SHELF_ACTIVE_ZONE_CLAMP
                else -> o
            }
        }
    }
    val visibleWidthPx = remember(pagerState, page, geometry) {
        derivedStateOf {
            val off = localOffset.value
            val turn = if (abs(off) >= 1f) geometry.maxTurn else abs(geometry.yaw(off))
            geometry.visibleWidth(turn).roundToInt().coerceAtLeast(1)
        }
    }
    // 正面此刻转得多侧：只在换档时才让封面重画（见 shelfCoverMipLevel）
    val mipLevel = remember(pagerState, page, geometry) {
        derivedStateOf { shelfCoverMipLevel(geometry.yaw(localOffset.value)) }
    }
    val flatRail = rail.isFlat
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .shelfSlot(pagerState, page, geometry, slotPitch, rowShift, rail, viewport)
            .shelfRailPose(pagerState, page, geometry, rowShift, rail, floorY)
            // 首屏入场：离停稳位置越近越先立起来
            .staggeredEntrance(shelfBookEntranceIndex(page, anchorPage), stepMillis = 40L, replay = false)
            .shelfReflection(
                offsetProvider = { localOffset.value },
                floorY = floorY,
                bookHeight = geometry.coverHeight,
                liftPx = lift,
                reflectionHeight = floorDepth,
                reflectionAlpha = reflectionAlpha,
                shadow = ShelfContactShadow,
                // 被抽出去翻开时或飞入书架时隐藏书体；背后的悬浮影与玻璃倒影随起落连续渐隐/渐显
                bodyAlpha = {
                    val ba = if (book != null && book.lentUri == game.uri) book.lentAlpha else 1f
                    val ca = if (catalog != null && game.uri in catalog.lentUris) catalog.lentAlpha else 1f
                    (ba * ca).coerceIn(0f, 1f)
                },
                groundAlpha = {
                    val bs = if (book != null && book.lentUri == game.uri) book.lentShadowAlpha else 1f
                    val cs = if (catalog != null && game.uri in catalog.lentUris) {
                        shelfCatalogGroundAlpha(catalog.recedeProgress)
                    } else {
                        1f
                    }
                    (bs * cs).coerceIn(0f, 1f)
                },
            )
            .padding(top = dims.topRoom, bottom = dims.floorDepth + dims.belowBoard)
            .shelfBoxPose(pagerState, { localOffset.value }, lift, velocity, trait)
            .gameRemovalDust(dissolve)
            .shelfBatchArrival(batchChangedAt, kotlin.math.abs(page - anchorPage))
            .pressSquish(scale = PressSquishDefaults.CardScale, charge = true)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = game.title }
            .shelfVisibleWidth(geometry) { visibleWidthPx.value },
    ) {
        ShelfSpine(
            game = game,
            shadeOverlay = { Modifier.shelfFaceShade({ localOffset.value }, geometry, spine = true) },
            fogOverlay = if (flatRail) null else {
                { Modifier.shelfRailFog(pagerState, page, geometry, rowShift, rail).background(fog) }
            },
            cover = cover ?: heroCover,
            reveal = reveal,
            hideTitleTag = hideTitleTag,
            engineColor = engineColor,
            dims = dims,
            modifier = Modifier
                .size(dims.spineDepth, dims.coverHeight)
                .shelfSpineFace({ localOffset.value }, geometry)
                .shelfBookSpine(book, game.uri)
                .shelfCatalogSpine(catalog, game.uri)
                .dissolveTint(dissolve, errorColor),
        )
        ShelfFront(
            game = game,
            overlays = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfBevelOverlay(ShelfGloss, ShelfDepthShade),
                )
                Box(Modifier.fillMaxSize().shelfFrontGloss({ localOffset.value }, ShelfGloss))
                // 倒角高光：书脊在左时封面的倒角在右侧（开口那一侧）
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfChamferGlint({ localOffset.value }, geometry)
                        .shelfChamferGlintsOverlay(dims.chamfer, { false }, chamferLight, chamferDim),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfFaceShade({ localOffset.value }, geometry, spine = false)
                        .background(ShelfFaceShade),
                )
                if (!flatRail) {
                    Box(Modifier.fillMaxSize().shelfRailFog(pagerState, page, geometry, rowShift, rail).background(fog))
                }
                // 启动：印在被抽出来的那一盒封面底部，转到正中时浮现、离开时收起
                ShelfPlayBadge(
                    visibility = { shelfHeroness(localOffset.value) },
                    onClick = if (isHero) onPlay else null,
                )
            },
            cover = faceCover,
            heroCover = heroCover,
            reveal = reveal,
            engineColor = engineColor,
            dims = dims,
            mipLevel = mipLevel,
            borderShapes = shapes,
            spineOnRight = { geometry.yaw(localOffset.value) < 0f },
            modifier = Modifier
                .size(dims.coverWidth, dims.coverHeight)
                .shelfFrontFace({ localOffset.value }, geometry, shapes)
                .shelfBookFront(book, game.uri)
                .shelfCatalogFront(
                    stage = catalog,
                    uri = game.uri,
                    pageIndex = page,
                    yawRadiansProvider = { geometry.yaw(page - pagerState.pagePosition()) },
                    tiltDegreesProvider = { HERO_TILT_DEGREES * shelfHeroness(page - pagerState.pagePosition()) },
                    scaleProvider = {
                        val off = page - pagerState.pagePosition()
                        val t = shelfSideness(off)
                        val rx = geometry.centerX(off) + rowShift()
                        shelfPoseScale(off) * (1f - (1f - trait.sizeScale) * t) * rail.scale(rx)
                    },
                    leanDegreesProvider = {
                        val off = page - pagerState.pagePosition()
                        trait.restLeanDegrees * shelfSideness(off)
                    },
                    frontShadeProvider = { geometry.faceShade(page - pagerState.pagePosition(), spine = false) },
                    spineShadeProvider = { geometry.faceShade(page - pagerState.pagePosition(), spine = true) },
                    heroFactorProvider = { shelfHeroness(page - pagerState.pagePosition()) },
                    tintProvider = { coverTints[game.coverUri] ?: engineColor },
                )
                .dissolveTint(dissolve, errorColor),
        )
    }
}

/**
 * 封面上的启动：一枚单层的小玻璃球，材质与页面卡片同源（默认白卡 / 深色卡、复古玻璃、白雾玻璃，比卡片更透、不挡封面），
 * 球心一个 ▶（用卡片正文色，不用主题色）；不再套圈、不再带文字，任何封面宽度下都是同一枚球，
 * 书架、翻开的书起飞与藏书阁里的封面上完全一致。读屏文案是「启动游戏」。
 * 随「被抽出」程度淡入（在图层里读位置，滑动中不重组）；离开正中后几乎透明，误触时也只会把那一盒转到正中。
 */
@Composable
internal fun BoxScope.ShelfPlayBadge(visibility: () -> Float, onClick: (() -> Unit)?) {
    val label = stringResource(R.string.game_launch_action)
    val surface = ShelfPlaySurface
    val content = TextColor
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = PLAY_BADGE_INSET)
            // 按压位移必须在外层：若放在带透明度的图层内侧，拖动略微偏离正中（alpha < 1）时
            // 外层自动离屏缓冲会按原布局框把偏移出的下半截裁切掉；跟手上限也收在底边距之内
            .pressSquish(enabled = onClick != null, follow = PLAY_BADGE_FOLLOW)
            .graphicsLayer {
                val raw = visibility()
                val v = when {
                    raw <= 0.002f -> 0f
                    raw >= 0.999f -> 1f
                    else -> raw
                }
                alpha = v
                val scale = 0.86f + 0.14f * v
                scaleX = scale
                scaleY = scale
                translationY = (1f - v) * 10.dp.toPx()
            }
            .size(PLAY_BALL_SIZE)
            .background(surface, CircleShape)
            .glassEdgeStroke(CircleShape, width = 1.dp, color = ShelfTrackOutline)
            .shelfGlassFinish(shape = CircleShape, corner = PLAY_BALL_SIZE / 2)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(interactionSource = null, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                        .semantics(mergeDescendants = true) { contentDescription = label }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val glyph = playTrianglePath(size, PLAY_BALL_GLYPH_RATIO)
                val round = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = size.minDimension * PLAY_GLYPH_ROUND,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                )
                onDrawBehind {
                    drawPath(glyph, content)
                    drawPath(glyph, content, style = round)
                }
            },
    ) { }
}

/** 启动球里的 ▶：外接框略偏右（三角形的重心偏左，这样看起来才居中）。 */
internal fun playTrianglePath(size: androidx.compose.ui.geometry.Size, ratio: Float = PLAY_GLYPH_RATIO): Path {
    val h = size.minDimension * ratio
    val w = h * 0.88f
    val cx = size.width / 2f + w * 0.1f
    val cy = size.height / 2f
    return Path().apply {
        moveTo(cx - w / 2f, cy - h / 2f)
        lineTo(cx + w / 2f, cy)
        lineTo(cx - w / 2f, cy + h / 2f)
        close()
    }
}

/**
 * 没有封面时正面的占位字（「Tyranor」+ 引擎名）：书架上那一盒、翻开的书起飞时的封面与藏书阁里的封面共用。
 * 字号按所在封面的宽度 [coverWidth] 等比（参照宽度 [PLACEHOLDER_REFERENCE_WIDTH]，大封面上的字不再显得小）：封面在书架、飞行途中、书柜里
 * 大小不同，字相对封面的大小始终不变，交接的那一刻不会突然放大缩小。
 */
@Composable
internal fun ShelfCoverPlaceholder(engineName: String, coverWidth: Dp, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.bodyMedium
    val ratio = (coverWidth / PLACEHOLDER_REFERENCE_WIDTH).coerceIn(0.4f, 3.2f)
    val style = base.copy(
        fontSize = (base.fontSize.value * ratio).sp,
        lineHeight = if (base.lineHeight.isSp) (base.lineHeight.value * ratio).sp else base.lineHeight,
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Tyranor", style = style, color = ShelfSpineText.copy(alpha = 0.7f), maxLines = 1)
        Text(
            engineName,
            style = style,
            color = ShelfSpineText,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp * ratio),
        )
    }
}

/** 正面：封面（无封面时引擎色 + 引擎名占位）；玩过的游戏封面上挂一根主题色书签丝带。 */
@Composable
private fun ShelfFront(
    game: ScanGame,
    overlays: @Composable () -> Unit,
    cover: ImageBitmap?,
    heroCover: ImageBitmap?,
    reveal: CoverReveal,
    engineColor: Color,
    dims: ShelfDims,
    modifier: Modifier = Modifier,
    /** 书架上的这一盒正面转得有多侧（见 [shelfCoverMipLevel]）：给了就按它换水平预缩的封面，不给就一直用原图。 */
    mipLevel: State<Int>? = null,
    borderShapes: ShelfBoxShapes? = null,
    spineOnRight: (() -> Boolean)? = null,
) {
    val ribbon = MaterialTheme.colorScheme.primary
    val safeCover = cover?.takeUnless { it.asAndroidBitmap().isRecycled }
    val safeHero = heroCover?.takeUnless { it.asAndroidBitmap().isRecycled }
    Box(
        modifier
            .background(engineColor)
            .then(
                if (borderShapes != null && spineOnRight != null) {
                    Modifier.drawShelfFrontGlassBorder(borderShapes, spineOnRight)
                } else {
                    Modifier.glassBorder(androidx.compose.ui.graphics.RectangleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 占位文字只在没有封面时组合：被封面盖住也照样会画，而且在透视下要逐字按路径绘制
        if (safeCover == null && safeHero == null) {
            ShelfCoverPlaceholder(game.engine.displayName, dims.coverWidth, Modifier.clearAndSetSemantics { })
        }
        safeCover?.let { bitmap ->
            val mips = if (mipLevel != null) rememberShelfCoverMips(bitmap) else null
            Spacer(
                Modifier
                    .then(reveal.image(Modifier.fillMaxSize()))
                    .shelfCoverMipped(bitmap, mips, mipLevel),
            )
        }
        // 抽到正中的那一盒换高清封面：高清图解出来后叠在网格档那张上面淡入，不是一帧换掉（换图的那一下会闪、清晰度突变）
        if (safeHero != null && safeHero.asAndroidBitmap() !== safeCover?.asAndroidBitmap()) {
            Image(
                bitmap = safeHero,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().shelfHeroCoverFade(safeHero, startOpaque = safeCover == null),
            )
        }
        if (isShelfLastPlayed(game)) ShelfCoverRibbon(dims.coverWidth, ribbon)
        overlays()
    }
}

private fun Modifier.drawShelfFrontGlassBorder(
    shapes: ShelfBoxShapes,
    spineOnRight: () -> Boolean,
): Modifier =
    if (!AppThemeColors.isGlass) {
        this
    } else {
        drawWithCache {
            val leftOutline = shapes.frontWithSpineLeft.createOutline(size, layoutDirection, this)
            val rightOutline = shapes.frontWithSpineRight.createOutline(size, layoutDirection, this)
            val stroke = Stroke(width = 0.5.dp.toPx())
            val color = GlassBorder
            onDrawWithContent {
                drawContent()
                drawOutline(
                    outline = if (spineOnRight()) rightOutline else leftOutline,
                    color = color,
                    style = stroke,
                )
            }
        }
    }

/**
 * 封面正面转得越侧，水平方向被压得越窄（转到九十度附近一整张封面挤进几个像素）。系统的 mipmap 是各向同性的：
 * 竖向没缩小、横向缩小五六倍时它选不出合适的一档，细密的线稿 / 网点直接被稀疏采样，转动途中满是闪烁的噪点。
 * 所以自己做一条「只缩宽度」的预缩链（宽度 ½ / ¼ / ⅛，每一级都是上一级横向取平均），再按正面此刻被压窄的程度
 * 选一档：落在所选一档上的横向缩小始终不到 2 倍，双线性采样就不会漏采。0 = 原图。
 */
internal fun shelfCoverMipLevel(yaw: Float): Int {
    val squeeze = kotlin.math.cos(abs(yaw)).coerceIn(0.02f, 1f)
    return kotlin.math.floor(kotlin.math.log2(1f / squeeze)).toInt().coerceIn(0, SHELF_COVER_MIPS)
}

internal const val SHELF_COVER_MIPS = 3
private const val SHELF_COVER_MIP_CACHE_SIZE = 48
private const val SPINE_WASH_CACHE_SIZE = 160
private const val BACKDROP_WASH_CACHE_SIZE = 64

/** 有界弱键 LRU 位图缓存：按 Bitmap 对象身份缓存并自动驱逐已回收/最久未用条目，同时去重同 key 并发后台计算。 */
private class BoundedBitmapCache<V : Any>(private val maxEntries: Int) {
    private class Key(bitmap: Bitmap) {
        private val ref = WeakReference(bitmap)
        private val hash = System.identityHashCode(bitmap)

        val isDead: Boolean get() = ref.get()?.isRecycled != false

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            val mine = ref.get() ?: return false
            val theirs = other.ref.get() ?: return false
            return mine === theirs
        }
    }

    private val lock = Any()
    private val entries = object : LinkedHashMap<Key, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, V>?): Boolean = size > maxEntries
    }
    private val inFlight = HashMap<Key, CompletableDeferred<V?>>()

    private fun pruneDeadLocked() {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key.isDead) it.remove()
        }
    }

    fun getIfPresent(bitmap: Bitmap): V? {
        if (bitmap.isRecycled) return null
        val key = Key(bitmap)
        return synchronized(lock) {
            pruneDeadLocked()
            entries[key]
        }
    }

    suspend fun getOrCompute(bitmap: Bitmap, compute: () -> V?): V? {
        if (bitmap.isRecycled) return null
        val key = Key(bitmap)
        val existing: CompletableDeferred<V?>?
        val created: CompletableDeferred<V?>?
        synchronized(lock) {
            pruneDeadLocked()
            entries[key]?.let { return it }
            existing = inFlight[key]
            if (existing != null) {
                created = null
            } else {
                created = CompletableDeferred()
                inFlight[key] = created
            }
        }
        if (existing != null) return existing.await()
        val result = try {
            if (bitmap.isRecycled) null else compute()
        } catch (t: Throwable) {
            synchronized(lock) { inFlight.remove(key) }
            created!!.complete(null)
            throw t
        }
        synchronized(lock) {
            inFlight.remove(key)
            if (result != null && !bitmap.isRecycled) {
                entries[key] = result
            }
        }
        created!!.complete(result)
        return result
    }
}

private val shelfCoverMipCache = BoundedBitmapCache<List<ImageBitmap>>(SHELF_COVER_MIP_CACHE_SIZE)

/** 这张封面的横向预缩链（后台生成并按位图缓存；还没好时为 null，先用原图）。 */
@Composable
internal fun rememberShelfCoverMips(cover: ImageBitmap): List<ImageBitmap>? {
    val source = cover.asAndroidBitmap()
    if (source.isRecycled) return null
    val cached = remember(source) { shelfCoverMipCache.getIfPresent(source) }
    if (cached != null) return cached
    val computed by key(source) {
        produceState<List<ImageBitmap>?>(initialValue = null) {
            value = withContext(Dispatchers.Default) {
                shelfCoverMipCache.getOrCompute(source) { buildShelfCoverMips(source) }
            }
        }
    }
    return computed
}

private fun buildShelfCoverMips(source: Bitmap): List<ImageBitmap>? = runCatching {
    if (source.isRecycled) return@runCatching null
    val chain = ArrayList<ImageBitmap>(SHELF_COVER_MIPS)
    var previous = source
    repeat(SHELF_COVER_MIPS) {
        if (previous.isRecycled) return@repeat
        val width = previous.width / 2
        if (width < 8 || previous.height <= 0) return@repeat
        // 恰好缩一半的双线性 = 横向相邻两个像素取平均，没有漏采
        val next = Bitmap.createScaledBitmap(previous, width, previous.height, true)
        chain += next.asImageBitmap()
        previous = next
    }
    chain.takeIf { it.isNotEmpty() }
}.getOrNull()

/** 画封面：与 `ContentScale.Crop` 一样铺满裁切；[level] 为 k（≥ 1）时用第 k 档横向预缩的图，没有预缩链或 [level] 为 0 时用原图。 */
internal fun Modifier.shelfCoverMipped(base: ImageBitmap, mips: List<ImageBitmap>?, level: State<Int>?): Modifier =
    drawWithCache {
        val bw = base.width
        val bh = base.height
        if (bw <= 0 || bh <= 0 || size.width <= 0f || size.height <= 0f) {
            return@drawWithCache onDrawBehind { }
        }
        val rect = shelfCoverCropRect(bw, bh, size.width, size.height)
        val dstOffset = androidx.compose.ui.unit.IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
        val dstSize = androidx.compose.ui.unit.IntSize(rect.width.roundToInt(), rect.height.roundToInt())
        onDrawBehind {
            if (base.asAndroidBitmap().isRecycled) return@onDrawBehind
            val k = if (mips == null || level == null) 0 else level.value.coerceIn(0, mips.size)
            val chosen = if (k == 0 || mips == null) base else mips[k - 1]
            val bitmap = if (chosen.asAndroidBitmap().isRecycled) base else chosen
            if (bitmap.asAndroidBitmap().isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return@onDrawBehind
            }
            // 铺满裁切的尺寸与偏移一律按**原图**的宽高比算：预缩图只是横向更窄的同一张图，画进同一个目标矩形里自然被横向拉回原样；
            // 若按预缩图自己的宽高比算，铺满裁切会多裁掉一大块并把剩下的竖向放大——侧转的封面就成了一条条竖向拉长的条纹
            clipRect {
                drawImage(
                    image = bitmap,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                )
            }
        }
    }

/** 一张 [srcWidth]×[srcHeight] 的封面铺满 [dstWidth]×[dstHeight] 并居中裁切（等同 `ContentScale.Crop`）时，图片落在目标里的矩形。 */
internal fun shelfCoverCropRect(srcWidth: Int, srcHeight: Int, dstWidth: Float, dstHeight: Float): androidx.compose.ui.geometry.Rect {
    val scale = maxOf(dstWidth / srcWidth, dstHeight / srcHeight)
    val w = srcWidth * scale
    val h = srcHeight * scale
    val left = (dstWidth - w) / 2f
    val top = (dstHeight - h) / 2f
    return androidx.compose.ui.geometry.Rect(left, top, left + w, top + h)
}

/**
 * 上次游玩的那一本：书签只挂在它身上（封面、翻开的书页、藏书阁的书脊都一样），不再给每部玩过的游戏都挂一根。
 * 由游戏页按全部游戏（不受筛选影响）算出后经 [LocalShelfLastPlayedUri] 提供；一部都没玩过时为 null。
 */
internal fun shelfLastPlayedUri(games: List<ScanGame>): String? =
    games.maxByOrNull { it.openTime }?.takeIf { it.openTime > 0L }?.uri

internal val LocalShelfLastPlayedUri = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** 这部游戏是不是上次游玩的那一本。 */
@Composable
internal fun isShelfLastPlayed(game: ScanGame): Boolean = LocalShelfLastPlayedUri.current == game.uri

/**
 * 封面上的书签丝带（上次游玩的那一本）：一条细细的小丝带从封面上缘垂下，宽 [width]、长约 2.7 倍宽，燕尾收口，
 * 离右缘一成左右；绸面横向一抹微光、顶端压一道折痕、两侧各一线锁边，下面垂一点贴身软影，色取 [color] 压暗一些——
 * 小而静，不抢封面，也不遮封面上的画面和字。
 */
@Composable
internal fun BoxScope.ShelfCoverRibbon(
    coverWidth: Dp,
    color: Color,
    width: Dp = (coverWidth * RIBBON_WIDTH_RATIO).coerceIn(RIBBON_MIN_WIDTH, RIBBON_MAX_WIDTH),
    length: Dp = width * RIBBON_LENGTH_RATIO,
) {
    Spacer(
        Modifier
            .align(Alignment.TopEnd)
            .padding(end = coverWidth * 0.1f)
            .size(width, length)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val notch = w * 0.5f
                fun outline(dx: Float, dy: Float) = Path().apply {
                    moveTo(dx, dy)
                    lineTo(w + dx, dy)
                    lineTo(w + dx, h + dy)
                    lineTo(w / 2f + dx, h - notch + dy)
                    lineTo(dx, h + dy)
                    close()
                }
                val shadowPath = outline(0.6.dp.toPx(), 1.2.dp.toPx())
                val body = outline(0f, 0f)
                val silk = androidx.compose.ui.graphics.lerp(color, ShelfRibbonDeep, RIBBON_DEEPEN).copy(alpha = RIBBON_ALPHA)
                val hGloss = Brush.horizontalGradient(
                    0f to ShelfRibbonDeep.copy(alpha = 0.22f),
                    0.4f to ShelfSpineText.copy(alpha = 0.2f),
                    0.75f to Color.Transparent,
                    1f to ShelfRibbonDeep.copy(alpha = 0.3f),
                )
                val topCrease = Brush.verticalGradient(
                    0f to ShelfRibbonDeep.copy(alpha = 0.4f),
                    0.2f to Color.Transparent,
                    endY = h,
                )
                val seam = 1.2.dp.toPx()
                val seamEnd = h - notch * 0.4f
                val seamColor = ShelfSpineText.copy(alpha = 0.3f)
                val seamWidth = 0.5.dp.toPx()
                val shadowColor = ShelfRibbonShadow
                onDrawBehind {
                    drawPath(shadowPath, shadowColor)
                    drawPath(body, silk)
                    clipPath(body) {
                        drawRect(hGloss)
                        drawRect(topCrease)
                        drawLine(seamColor, Offset(seam, 0f), Offset(seam, seamEnd), strokeWidth = seamWidth)
                        drawLine(seamColor, Offset(w - seam, 0f), Offset(w - seam, seamEnd), strokeWidth = seamWidth)
                    }
                }
            },
    )
}

/**
 * 书脊正面内容（封面柔焦底、顶部封条与两道烫金细线、竖排/侧转宋体书名、书脚的烫金线与引擎色小菱饰、圆润的明暗、四边棱光）：
 * 书架书脊与翻开的书飞行书脊共用，保证起飞与落回交接逐像素一致。
 * 像一本精装书的书脊：只印书名，不印序号（序号在下方对话框与进度轨道里已经有了，不在每条书脊上重复）。
 */
@Composable
internal fun BoxScope.ShelfSpineFaceContent(
    title: String,
    coverHeight: Dp,
    wash: ImageBitmap?,
    engineColor: Color,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    /** 上次游玩的那一本：书脊右上挂一枚小书签（封面色）。 */
    lastPlayed: Boolean = false,
) {
    val density = LocalDensity.current
    // 烫印的线、菱饰与顶上的封条都取这本书封面的主色（没有封面时退回引擎色），不用主题色
    val ink = rememberShelfSpineInk(wash, engineColor)
    val foil = remember(ink) { androidx.compose.ui.graphics.lerp(ink, Color.White, SPINE_FOIL_LIGHTEN) }
    val footHeight = coverHeight * SPINE_FOOT_RATIO
    val maxChars = with(density) {
        ((coverHeight - SPINE_TITLE_TOP - footHeight - 6.dp).toPx() / SPINE_LINE_HEIGHT.toPx()).toInt()
    }.coerceIn(1, SPINE_MAX_CHARS)
    val stacked = remember(title, maxChars) { stackSpineTitle(title, maxChars) }
    val latinTitle = remember(title) { isMostlyLatin(title) }
    val spineTextLength = coverHeight - SPINE_TITLE_TOP - footHeight - 6.dp
    ShelfSpineBase(wash, ink, imageModifier)
    // 竖排名称属书脊上的小型标注，按页面两档字号会塞不下（见 AGENTS.md 文字尺寸豁免）；用宋体，像书脊上烫印的书名
    val spineStyle = MaterialTheme.typography.labelSmall.copy(
        color = ShelfSpineTitle,
        fontFamily = BookSerifBold,
        fontWeight = FontWeight.SemiBold,
        lineHeight = SPINE_LINE_HEIGHT,
        textAlign = TextAlign.Center,
    )
    if (latinTitle) {
        // 西文书名：像真实书脊那样整行侧转排印（从上往下读），不再把字母一个个竖着堆；最多 [SPINE_MAX_LATIN_CHARS] 个字符
        Text(
            text = limitShelfTitle(title, SPINE_MAX_LATIN_CHARS),
            style = spineStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = SPINE_TITLE_TOP)
                .heightIn(max = spineTextLength)
                .spineSideways()
                .clearAndSetSemantics { },
        )
    } else {
        Text(
            text = stacked,
            style = spineStyle,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = SPINE_TITLE_TOP)
                .clearAndSetSemantics { },
        )
    }
    // 烫金与明暗 + 四边棱边合并于单一 `drawWithCache`，绘制期零分配并省去冗余叠加节点
    val bevelLight = ShelfGloss
    val bevelDark = ShelfDepthShade
    val roundLight = ShelfSpineRoundLight
    val roundShade = ShelfSpineRoundShade
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val roundBrush = Brush.horizontalGradient(
                    0f to roundLight,
                    0.34f to Color.Transparent,
                    0.68f to Color.Transparent,
                    1f to roundShade,
                )
                val edge = BEVEL_WIDTH.toPx()
                val leftBevel = Brush.horizontalGradient(0f to bevelLight, 1f to Color.Transparent, startX = 0f, endX = edge)
                val topBevel = Brush.verticalGradient(0f to bevelLight, 1f to Color.Transparent, startY = 0f, endY = edge)
                val rightBevel = Brush.horizontalGradient(0f to Color.Transparent, 1f to bevelDark, startX = w - edge, endX = w)
                val bottomBevel = Brush.verticalGradient(0f to Color.Transparent, 1f to bevelDark, startY = h - edge, endY = h)
                val inset = (w * 0.2f).coerceAtLeast(3.dp.toPx())
                val thick = 0.9.dp.toPx()
                val thin = 0.5.dp.toPx()
                val pair = 2.6.dp.toPx()
                val topRuleY = SPINE_CAP.toPx() + 4.dp.toPx()
                val footPx = footHeight.toPx()
                val footTop = h - footPx
                val foilMain = foil.copy(alpha = 0.66f)
                val foilSub = foil.copy(alpha = 0.4f)
                val foilStrokeColor = foil.copy(alpha = 0.7f)
                val foilStroke = Stroke(width = 0.6.dp.toPx())
                val half = minOf(w * 0.17f, 3.6.dp.toPx())
                val cx = w / 2f
                val cy = footTop + pair + (footPx - pair) * 0.5f
                val diamond = Path().apply {
                    moveTo(cx, cy - half)
                    lineTo(cx + half, cy)
                    lineTo(cx, cy + half)
                    lineTo(cx - half, cy)
                    close()
                }
                onDrawBehind {
                    drawRect(roundBrush)
                    drawLine(foilMain, Offset(inset, topRuleY), Offset(w - inset, topRuleY), strokeWidth = thick)
                    drawLine(foilSub, Offset(inset, topRuleY + pair), Offset(w - inset, topRuleY + pair), strokeWidth = thin)
                    drawLine(foilMain, Offset(inset, footTop), Offset(w - inset, footTop), strokeWidth = thick)
                    drawLine(foilSub, Offset(inset, footTop + pair), Offset(w - inset, footTop + pair), strokeWidth = thin)
                    drawPath(diamond, ink)
                    drawPath(diamond, foilStrokeColor, style = foilStroke)
                    drawRect(leftBevel, size = androidx.compose.ui.geometry.Size(edge, h))
                    drawRect(topBevel, size = androidx.compose.ui.geometry.Size(w, edge))
                    drawRect(rightBevel, topLeft = Offset(w - edge, 0f), size = androidx.compose.ui.geometry.Size(edge, h))
                    drawRect(bottomBevel, topLeft = Offset(0f, h - edge), size = androidx.compose.ui.geometry.Size(w, edge))
                }
            },
    )
    if (lastPlayed) {
        // 上次游玩：书脊右上挂一枚小书签（封面色，燕尾）
        Spacer(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 3.dp, top = SPINE_CAP)
                .size(SPINE_TAG_WIDTH, SPINE_TAG_LENGTH)
                .drawWithCache {
                    val notch = size.width * 0.5f
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(size.width / 2f, size.height - notch)
                        lineTo(0f, size.height)
                        close()
                    }
                    onDrawBehind { drawPath(path, foil) }
                },
        )
    }
}

private data class SpineWashResult(val wash: ImageBitmap, val ink: Color)

private val spineResultByWash = java.util.WeakHashMap<Bitmap, Color>()

/** 书脊烫印用的封面主色：优先复用后台色带计算结果，避免主线程 `getPixels` 阻塞。 */
@Composable
internal fun rememberShelfSpineInk(wash: ImageBitmap?, engineColor: Color): Color {
    val bmp = wash?.asAndroidBitmap()
    return remember(bmp, engineColor) {
        if (bmp == null || bmp.isRecycled) {
            engineColor
        } else {
            synchronized(spineResultByWash) { spineResultByWash[bmp] } ?: shelfCoverTint(wash)
        }
    }
}

/** 书脊：封面色调的柔光底，顶部一道引擎色封条与烫金线，竖排宋体书名，书脚一对烫金线夹一枚引擎色小菱饰。 */
@Composable
private fun ShelfSpine(
    game: ScanGame,
    shadeOverlay: () -> Modifier,
    fogOverlay: (() -> Modifier)?,
    cover: ImageBitmap?,
    reveal: CoverReveal,
    hideTitleTag: Boolean,
    engineColor: Color,
    dims: ShelfDims,
    modifier: Modifier = Modifier,
) {
    val title = if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    // 书脊不直接切封面（会切到封面上的标题字）：缩成一条极小的柔焦色带再拉伸，只保留封面的配色与上下渐变
    val wash = rememberShelfSpineWash(cover)
    Box(modifier.background(engineColor)) {
        ShelfSpineFaceContent(
            title = title,
            coverHeight = dims.coverHeight,
            wash = wash,
            engineColor = engineColor,
            imageModifier = reveal.image(Modifier.fillMaxSize()),
            lastPlayed = isShelfLastPlayed(game),
        )
        // 背光压暗：独立覆盖层，滚动时只改它的透明度
        Box(Modifier.fillMaxSize().then(shadeOverlay()).background(ShelfFaceShade))
        // 空气透视：仅在斜轨倾斜时组合雾效层
        if (fogOverlay != null) {
            Box(Modifier.fillMaxSize().then(fogOverlay()))
        }
    }
}

private val spineWashCache = BoundedBitmapCache<SpineWashResult>(SPINE_WASH_CACHE_SIZE)

/** 书脊底色用的封面柔焦色带：缓存命中即刻同步返回，未命中于后台一并生成色带与烫印主色。 */
@Composable
internal fun rememberShelfSpineWash(cover: ImageBitmap?): ImageBitmap? {
    val source = cover?.asAndroidBitmap()
    if (cover == null || source == null || source.isRecycled) return null
    val cached = remember(source) { spineWashCache.getIfPresent(source)?.wash }
    if (cached != null) return cached
    val computed by key(source) {
        produceState<ImageBitmap?>(initialValue = null) {
            value = withContext(Dispatchers.Default) {
                spineWashCache.getOrCompute(source) {
                    coverWash(cover, SPINE_WASH_WIDTH, SPINE_WASH_HEIGHT, 1)?.let { wash ->
                        val ink = shelfCoverTint(wash)
                        synchronized(spineResultByWash) {
                            spineResultByWash[wash.asAndroidBitmap()] = ink
                        }
                        SpineWashResult(wash, ink)
                    }
                }?.wash
            }
        }
    }
    return computed
}

/** 书脊的底：封面柔焦色带 + 上浅下深的压暗 + 顶部一道引擎色封条（书架上的书脊与翻开的书起飞时的书脊共用）。 */
@Composable
internal fun BoxScope.ShelfSpineBase(wash: ImageBitmap?, engineColor: Color, imageModifier: Modifier = Modifier.fillMaxSize()) {
    val scrimTop = ShelfSpineScrimTop
    val scrimBottom = ShelfSpineScrimBottom
    wash?.takeUnless { it.asAndroidBitmap().isRecycled }?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = imageModifier,
        )
    }
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val scrimBrush = Brush.verticalGradient(listOf(scrimTop, scrimBottom))
                val capSize = androidx.compose.ui.geometry.Size(size.width, SPINE_CAP.toPx())
                onDrawBehind {
                    drawRect(scrimBrush)
                    drawRect(engineColor, topLeft = Offset.Zero, size = capSize)
                }
            },
    )
}

/** 缓存四边棱边画刷，避免每次重绘新建 Gradient Shader。 */
internal fun Modifier.shelfBevelOverlay(light: Color, dark: Color): Modifier = drawWithCache {
    val edge = BEVEL_WIDTH.toPx()
    val leftBrush = Brush.horizontalGradient(0f to light, 1f to Color.Transparent, startX = 0f, endX = edge)
    val topBrush = Brush.verticalGradient(0f to light, 1f to Color.Transparent, startY = 0f, endY = edge)
    val rightBrush = Brush.horizontalGradient(0f to Color.Transparent, 1f to dark, startX = size.width - edge, endX = size.width)
    val bottomBrush = Brush.verticalGradient(0f to Color.Transparent, 1f to dark, startY = size.height - edge, endY = size.height)
    onDrawBehind {
        drawRect(leftBrush, size = androidx.compose.ui.geometry.Size(edge, size.height))
        drawRect(topBrush, size = androidx.compose.ui.geometry.Size(size.width, edge))
        drawRect(rightBrush, topLeft = Offset(size.width - edge, 0f), size = androidx.compose.ui.geometry.Size(edge, size.height))
        drawRect(bottomBrush, topLeft = Offset(0f, size.height - edge), size = androidx.compose.ui.geometry.Size(size.width, edge))
    }
}

/** 缓存左右两组倒角高光路径，绘制阶段零分配按 [leftProvider] 切换绘制侧。 */
internal fun Modifier.shelfChamferGlintsOverlay(
    chamfer: Dp,
    leftProvider: () -> Boolean,
    light: Color,
    dim: Color,
): Modifier = drawWithCache {
    val chamferPx = chamfer.toPx()
    if (chamferPx <= 0f) {
        onDrawBehind { }
    } else {
        val line = CHAMFER_GLINT_WIDTH.toPx()
        val facet = CHAMFER_FACET_WIDTH.toPx()
        fun buildCorner(left: Boolean, top: Boolean, width: Float): Path {
            val grow = width * SQRT_2
            val x0 = if (left) 0f else size.width
            val inward = if (left) 1f else -1f
            val y0 = if (top) 0f else size.height
            val down = if (top) 1f else -1f
            return Path().apply {
                moveTo(x0 + inward * chamferPx, y0)
                lineTo(x0 + inward * (chamferPx + grow), y0)
                lineTo(x0, y0 + down * (chamferPx + grow))
                lineTo(x0, y0 + down * chamferPx)
                close()
            }
        }
        val facetLight = light.copy(alpha = light.alpha * CHAMFER_FACET_SHARE)
        val facetDim = dim.copy(alpha = dim.alpha * CHAMFER_FACET_SHARE)
        val leftPaths = arrayOf(
            buildCorner(left = true, top = true, width = facet) to facetLight,
            buildCorner(left = true, top = false, width = facet) to facetDim,
            buildCorner(left = true, top = true, width = line) to light,
            buildCorner(left = true, top = false, width = line) to dim,
        )
        val rightPaths = arrayOf(
            buildCorner(left = false, top = true, width = facet) to facetLight,
            buildCorner(left = false, top = false, width = facet) to facetDim,
            buildCorner(left = false, top = true, width = line) to light,
            buildCorner(left = false, top = false, width = line) to dim,
        )
        onDrawBehind {
            val group = if (leftProvider()) leftPaths else rightPaths
            for ((path, color) in group) {
                drawPath(path, color)
            }
        }
    }
}

/** 盒面棱边（静态录制一次）：左、上缘一线受光，右、下缘一线背光。 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShelfBevel(light: Color, dark: Color) {
    val edge = BEVEL_WIDTH.toPx()
    drawRect(
        Brush.horizontalGradient(0f to light, 1f to Color.Transparent, startX = 0f, endX = edge),
        size = androidx.compose.ui.geometry.Size(edge, size.height),
    )
    drawRect(
        Brush.verticalGradient(0f to light, 1f to Color.Transparent, startY = 0f, endY = edge),
        size = androidx.compose.ui.geometry.Size(size.width, edge),
    )
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, 1f to dark, startX = size.width - edge, endX = size.width),
        topLeft = androidx.compose.ui.geometry.Offset(size.width - edge, 0f),
        size = androidx.compose.ui.geometry.Size(edge, size.height),
    )
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to dark, startY = size.height - edge, endY = size.height),
        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - edge),
        size = androidx.compose.ui.geometry.Size(size.width, edge),
    )
}

/**
 * 倒角高光（静态录制一次）：沿 [left] 一侧上下两个切角各画一道很细的亮线，像切角的斜面迎着光；
 * 线贴着切边往里画，整条都落在裁切轮廓里。上面两个角用 [light]，下面两个角用更淡的 [dim]。
 * 斜面再铺一层很淡的宽一点的光，亮线不显得是描上去的。
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShelfChamferGlints(
    chamfer: Float,
    left: Boolean,
    light: Color,
    dim: Color,
) {
    if (chamfer <= 0f) return
    val line = CHAMFER_GLINT_WIDTH.toPx()
    val facet = CHAMFER_FACET_WIDTH.toPx()
    fun corner(top: Boolean, width: Float, color: Color) {
        // 切边从横边上的点 a 到竖边上的点 b；往盒子里平移 width（沿 45° 法线，在两条边上各多出 width·√2）
        val grow = width * SQRT_2
        val x0 = if (left) 0f else size.width
        val inward = if (left) 1f else -1f
        val y0 = if (top) 0f else size.height
        val down = if (top) 1f else -1f
        val path = Path().apply {
            moveTo(x0 + inward * chamfer, y0)
            lineTo(x0 + inward * (chamfer + grow), y0)
            lineTo(x0, y0 + down * (chamfer + grow))
            lineTo(x0, y0 + down * chamfer)
            close()
        }
        drawPath(path, color)
    }
    corner(top = true, width = facet, color = light.copy(alpha = light.alpha * CHAMFER_FACET_SHARE))
    corner(top = false, width = facet, color = dim.copy(alpha = dim.alpha * CHAMFER_FACET_SHARE))
    corner(top = true, width = line, color = light)
    corner(top = false, width = line, color = dim)
}

/** 游戏盒倒角的边长：随封面宽度变化，夹在一个看得出切角、又不至于削掉封面的范围里。 */
internal fun shelfChamfer(coverWidth: Dp): Dp = (coverWidth * CHAMFER_RATIO).coerceIn(MIN_CHAMFER, MAX_CHAMFER)

/** 书名主要由西文字符组成（拉丁字母、数字、常见符号）时返回 true：这类书名在书脊上整行侧转排印。 */
internal fun isMostlyLatin(title: String): Boolean {
    val chars = title.filterNot { it.isWhitespace() }
    if (chars.isEmpty()) return false
    val latin = chars.count { it.code < CJK_START }
    return latin >= chars.length * LATIN_SHARE
}

/** 把一行文字顺时针转 90° 竖着放：按「长 = 可用高度、宽 = 行高」测量，绕中心旋转后摆进去。 */
internal fun Modifier.spineSideways(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        androidx.compose.ui.unit.Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth),
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width - placeable.height) / 2,
            y = (placeable.width - placeable.height) / 2,
        ) { rotationZ = 90f }
    }
}

/** 按字数截断书名（按字符数而不是按宽度）：超过 [max] 个字时保留前 max - 1 个字再加「…」。 */
internal fun limitShelfTitle(title: String, max: Int): String {
    val points = title.trim().codePoints().toArray()
    if (points.size <= max) return title.trim()
    return String(points, 0, (max - 1).coerceAtLeast(0)).trimEnd() + "…"
}

/** 书脊竖排文字：空白折叠成一格，超出 [maxChars] 时末格换成竖向省略号。 */
internal fun stackSpineTitle(title: String, maxChars: Int): String {
    val chars = title.trim().replace(Regex("\\s+"), " ").codePoints().toArray()
        .map { String(Character.toChars(it)) }
    val shown = if (chars.size <= maxChars) {
        chars
    } else {
        chars.take((maxChars - 1).coerceAtLeast(0)).dropLastWhile { it.isBlank() } + "⋮"
    }
    return shown.joinToString("\n")
}

/**
 * 封面柔焦色带：缩到 [width]×[height] 像素再做盒式模糊，拉伸后就是一片只保留配色与明暗走向的柔光
 * （书脊底色、书架背景）。只在封面就绪时算一次，滚动途中零开销；API 版本无关，不依赖 RenderEffect。
 */
private data class BackdropWashResult(val wash: ImageBitmap, val tint: Color)

private val backdropWashCache = BoundedBitmapCache<BackdropWashResult>(BACKDROP_WASH_CACHE_SIZE)

internal fun coverWash(cover: ImageBitmap, width: Int, height: Int, radius: Int): ImageBitmap? = runCatching {
    if (width <= 0 || height <= 0) return@runCatching null
    val source = cover.asAndroidBitmap()
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return@runCatching null
    val scaled = Bitmap.createScaledBitmap(source, width, height, true)
    val pixels = IntArray(width * height)
    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
    if (scaled !== source) scaled.recycle()
    boxBlurArgb(pixels, width, height, radius)
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

/**
 * 背景色晕：当前游戏封面的柔焦大图铺满书架区，上下淡出到页面背景，像 galgame 标题画面后面的 CG。
 * 跟着手指实时变化：翻页途中按位置在相邻两本的色晕之间混合，不等停稳才切换。没有封面的书用对应引擎色的
 * 柔和氛围光场铺满（与封面柔焦图同样的不透明色场结构），快速滑过有封面与无封面书籍之间始终连续过渡、不闪烁。
 */
@Composable
private fun rememberBackdropWashResult(
    game: ScanGame?,
    tints: MutableMap<String, Color>,
): BackdropWashResult? {
    val coverUri = game?.coverUri ?: return null
    val cover by key(coverUri) { rememberCoverBitmap(coverUri) }
    val coverSnapshot = cover ?: return null
    val source = coverSnapshot.asAndroidBitmap().takeUnless { it.isRecycled } ?: return null
    val cached = remember(source) { backdropWashCache.getIfPresent(source) }
    val resolved = cached ?: key(source) {
        val computed by produceState<BackdropWashResult?>(initialValue = null) {
            value = withContext(Dispatchers.Default) {
                backdropWashCache.getOrCompute(source) {
                    coverWash(coverSnapshot, BACKDROP_WASH_WIDTH, BACKDROP_WASH_HEIGHT, 2)?.let { wash ->
                        BackdropWashResult(wash, shelfCoverTint(wash))
                    }
                }
            }
        }
        computed
    }
    val valid = resolved?.takeUnless { it.wash.asAndroidBitmap().isRecycled }
    if (valid != null) {
        SideEffect { tints[coverUri] = valid.tint }
    }
    return valid
}

/**
 * 背景色晕：当前游戏封面的柔焦大图铺满书架区，上下淡出到页面背景。
 *
 * - 双页封面柔焦色带均已就绪时（常态 99%+ 帧）：在 CPU 预烘培垂直淡出遮罩的 18×96 4 槽位 Ring Buffer 中混合后
 *   通过单次 `drawBitmap` 直绘至主画布，消除每帧 `ComposeShader`/`BitmapShader` 重建、单图写冲突与全屏离屏 FBO；
 * - 任一页尚在异步解码或无封面时：自动降级为原双页叠层融合路径，视觉 100% 连续无闪烁。
 */
@Composable
private fun ShelfBackdrop(
    pagerState: PagerState,
    games: List<ScanGame>,
    tints: MutableMap<String, Color>,
    modifier: Modifier = Modifier,
) {
    val alpha = ShelfBackdropAlpha
    // 只在跨过整本时重组（换一对相邻的色晕）；混合比例在绘制/图层阶段读取，滑动途中不重组
    val base by remember(pagerState) { derivedStateOf { kotlin.math.floor(pagerState.pagePosition()).toInt() } }
    val baseGame = games.getOrNull(base)
    val nextGame = games.getOrNull(base + 1)
    val baseResolved = rememberBackdropWashResult(baseGame, tints)
    val nextResolved = rememberBackdropWashResult(nextGame, tints)
    val baseWash = baseResolved?.wash
    val nextWash = nextResolved?.wash
    if (baseWash != null && (nextGame == null || nextWash != null)) {
        val baseBmp = baseWash.asAndroidBitmap()
        val nextBmp = nextWash?.asAndroidBitmap()
        val ring = remember { BackdropRingBuffer(BACKDROP_WASH_WIDTH, BACKDROP_MASK_HEIGHT) }
        val maskAlpha = remember {
            IntArray(BACKDROP_MASK_HEIGHT) { y ->
                val t = (y + 0.5f) / BACKDROP_MASK_HEIGHT
                val a = when {
                    t <= 0.22f -> t / 0.22f
                    t <= 0.62f -> 1f
                    else -> (1f - t) / (1f - 0.62f)
                }
                (a.coerceIn(0f, 1f) * 255f).roundToInt()
            }
        }
        Spacer(
            modifier
                .sinkBleedX()
                .drawWithCache {
                    val width = size.width
                    val height = size.height
                    if (width <= 0f || height <= 0f) {
                        return@drawWithCache onDrawBehind { }
                    }
                    val dstRect = RectF(0f, 0f, width, height)
                    val paint = android.graphics.Paint(
                        android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG,
                    ).apply {
                        this.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
                    }
                    onDrawBehind {
                        if (baseBmp.isRecycled) return@onDrawBehind
                        val position = pagerState.pagePosition()
                        val fraction = if (nextBmp != null && !nextBmp.isRecycled) {
                            (position - base).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val drawBmp = if (fraction <= 0.002f || nextBmp == null || nextBmp.isRecycled || fraction >= 0.998f) {
                            val activeBmp = if (fraction >= 0.998f && nextBmp != null && !nextBmp.isRecycled) nextBmp else baseBmp
                            ring.staticBitmap(activeBmp, maskAlpha, width, height)
                        } else {
                            ring.blendedBitmap(baseBmp, nextBmp, fraction, maskAlpha, width, height)
                        }
                        drawContext.canvas.nativeCanvas.drawBitmap(drawBmp, null, dstRect, paint)
                    }
                },
        )
        return
    }

    Box(
        modifier
            // 浮层升起、页面下沉时反向横向放大，背景色晕始终铺到屏幕两缘（不露出两边的空隙，见 sinkBleedX）
            .sinkBleedX()
            .graphicsLayer {
                this.alpha = alpha
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val mask = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.22f to Color.Black,
                    0.62f to Color.Black,
                    1f to Color.Transparent,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            },
    ) {
        if (baseGame != null) {
            key(baseGame.uri) {
                ShelfBackdropWashFallback(
                    engineColor = baseGame.engine.coverColor(),
                    wash = baseWash,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (nextGame != null) {
            key(nextGame.uri) {
                ShelfBackdropWashFallback(
                    engineColor = nextGame.engine.coverColor(),
                    wash = nextWash,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val position = pagerState.pagePosition()
                            this.alpha = (1f - ((base + 1) - position)).coerceIn(0f, 1f)
                        },
                )
            }
        }
    }
}

private const val BACKDROP_MASK_HEIGHT = 96

private class BackdropRingBuffer(private val w: Int, private val h: Int) {
    private val count = w * h
    private val bitmaps = Array(4) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    private var slotIdx = 0
    private val srcPixels = IntArray(BACKDROP_WASH_WIDTH * BACKDROP_WASH_HEIGHT)
    private val bufA = IntArray(count)
    private val bufB = IntArray(count)
    private val out = IntArray(count)
    private var cachedA: Bitmap? = null
    private var cachedAW = -1f
    private var cachedAH = -1f
    private var cachedB: Bitmap? = null
    private var cachedBW = -1f
    private var cachedBH = -1f
    private var lastStaticBmp: Bitmap? = null
    private var lastStaticW = -1f
    private var lastStaticH = -1f

    private fun sampleCrop(src: Bitmap, dst: IntArray, dstW: Float, dstH: Float) {
        val srcW = src.width.coerceAtLeast(1)
        val srcH = src.height.coerceAtLeast(1)
        val px = if (srcW * srcH == srcPixels.size) srcPixels else IntArray(srcW * srcH)
        src.getPixels(px, 0, srcW, 0, 0, srcW, srcH)
        val scale = maxOf(dstW / srcW, dstH / srcH).coerceAtLeast(1e-4f)
        val dx = (dstW - srcW * scale) * 0.5f
        val dy = (dstH - srcH * scale) * 0.5f
        val maxX = (srcW - 1).coerceAtLeast(0)
        val maxY = (srcH - 1).coerceAtLeast(0)
        for (y in 0 until h) {
            val py = ((y + 0.5f) / h) * dstH
            val sy = ((py - dy) / scale - 0.5f).coerceIn(0f, maxY.toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(maxY)
            val fy = ((sy - y0) * 256f).roundToInt().coerceIn(0, 256)
            val iy = 256 - fy
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = y * w
            for (x in 0 until w) {
                val pxCoord = ((x + 0.5f) / w) * dstW
                val sx = ((pxCoord - dx) / scale - 0.5f).coerceIn(0f, maxX.toFloat())
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(maxX)
                val fx = ((sx - x0) * 256f).roundToInt().coerceIn(0, 256)
                val ix = 256 - fx
                val c00 = px[row0 + x0]
                val c10 = px[row0 + x1]
                val c01 = px[row1 + x0]
                val c11 = px[row1 + x1]
                val w00 = ix * iy
                val w10 = fx * iy
                val w01 = ix * fy
                val w11 = fx * fy
                val r = (((c00 ushr 16) and 0xFF) * w00 + ((c10 ushr 16) and 0xFF) * w10 +
                    ((c01 ushr 16) and 0xFF) * w01 + ((c11 ushr 16) and 0xFF) * w11) ushr 16
                val g = (((c00 ushr 8) and 0xFF) * w00 + ((c10 ushr 8) and 0xFF) * w10 +
                    ((c01 ushr 8) and 0xFF) * w01 + ((c11 ushr 8) and 0xFF) * w11) ushr 16
                val b = ((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
                    (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11) ushr 16
                dst[dstRow + x] = (r shl 16) or (g shl 8) or b
            }
        }
    }

    fun staticBitmap(src: Bitmap, maskAlpha: IntArray, dstW: Float, dstH: Float): Bitmap {
        if (lastStaticBmp === src && lastStaticW == dstW && lastStaticH == dstH) {
            return bitmaps[slotIdx]
        }
        val srcBuf = if (cachedB === src && cachedBW == dstW && cachedBH == dstH) {
            bufB
        } else {
            if (cachedA !== src || cachedAW != dstW || cachedAH != dstH) {
                sampleCrop(src, bufA, dstW, dstH)
                cachedA = src
                cachedAW = dstW
                cachedAH = dstH
            }
            bufA
        }
        for (y in 0 until h) {
            val aShift = maskAlpha[y] shl 24
            val rowStart = y * w
            for (x in 0 until w) {
                val idx = rowStart + x
                out[idx] = aShift or (srcBuf[idx] and 0x00FFFFFF)
            }
        }
        slotIdx = (slotIdx + 1) and 3
        val bmp = bitmaps[slotIdx]
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        lastStaticBmp = src
        lastStaticW = dstW
        lastStaticH = dstH
        return bmp
    }

    fun blendedBitmap(a: Bitmap, b: Bitmap, fraction: Float, maskAlpha: IntArray, dstW: Float, dstH: Float): Bitmap {
        lastStaticBmp = null
        if (cachedA !== a || cachedAW != dstW || cachedAH != dstH) {
            sampleCrop(a, bufA, dstW, dstH)
            cachedA = a
            cachedAW = dstW
            cachedAH = dstH
        }
        if (cachedB !== b || cachedBW != dstW || cachedBH != dstH) {
            sampleCrop(b, bufB, dstW, dstH)
            cachedB = b
            cachedBW = dstW
            cachedBH = dstH
        }
        val weight = (fraction * 256f).roundToInt().coerceIn(0, 256)
        val inv = 256 - weight
        for (y in 0 until h) {
            val aShift = maskAlpha[y] shl 24
            val rowStart = y * w
            for (x in 0 until w) {
                val idx = rowStart + x
                val c0 = bufA[idx]
                val c1 = bufB[idx]
                val r = ((((c0 ushr 16) and 0xFF) * inv) + (((c1 ushr 16) and 0xFF) * weight)) shr 8
                val g = ((((c0 ushr 8) and 0xFF) * inv) + (((c1 ushr 8) and 0xFF) * weight)) shr 8
                val bl = (((c0 and 0xFF) * inv) + ((c1 and 0xFF) * weight)) shr 8
                out[idx] = aShift or (r shl 16) or (g shl 8) or bl
            }
        }
        slotIdx = (slotIdx + 1) and 3
        val bmp = bitmaps[slotIdx]
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}

@Composable
private fun ShelfBackdropWashFallback(
    engineColor: Color,
    wash: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    if (wash != null) {
        Image(bitmap = wash, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        ShelfEngineBackdrop(engineColor, modifier)
    }
}

/**
 * 无封面（及封面尚未解码好）时的背景氛围光场：按引擎色生成不透明的柔焦明暗层次，
 * 中上部书架身后亮起一团柔和主光、四周自然沉暗，与有封面时的 [coverWash] 色场亮度与空间感对齐。
 */
@Composable
private fun ShelfEngineBackdrop(engineColor: Color, modifier: Modifier = Modifier) {
    val palette = remember(engineColor) { shelfEngineBackdropColors(engineColor) }
    Box(
        modifier.drawWithCache {
            val base = Brush.verticalGradient(
                0f to palette.top,
                0.48f to palette.mid,
                1f to palette.bottom,
            )
            val heroGlow = Brush.radialGradient(
                0f to palette.glow,
                0.52f to palette.glow.copy(alpha = 0.42f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.5f, size.height * 0.38f),
                radius = maxOf(size.width, size.height) * 0.62f,
            )
            val sideGlow = Brush.radialGradient(
                0f to palette.accentGlow,
                1f to Color.Transparent,
                center = Offset(size.width * 0.18f, size.height * 0.28f),
                radius = size.width * 0.72f,
            )
            onDrawBehind {
                drawRect(base)
                drawRect(heroGlow)
                drawRect(sideGlow)
            }
        },
    )
}

private class EngineBackdropPalette(
    val top: Color,
    val mid: Color,
    val bottom: Color,
    val glow: Color,
    val accentGlow: Color,
)

private fun shelfEngineBackdropColors(engineColor: Color): EngineBackdropPalette {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(engineColor.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1].coerceIn(0.18f, 0.78f)
    val value = hsv[2].coerceIn(0.45f, 0.82f)
    fun hsvColor(h: Float, s: Float, v: Float, alpha: Float = 1f): Color {
        val wrapped = ((h % 360f) + 360f) % 360f
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(wrapped, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))).copy(alpha = alpha)
    }
    return EngineBackdropPalette(
        top = hsvColor(hue - 8f, sat * 0.82f, value * 0.78f),
        mid = hsvColor(hue, sat * 0.92f, value * 0.88f),
        bottom = hsvColor(hue + 10f, sat * 0.78f, value * 0.54f),
        glow = hsvColor(hue - 6f, (sat * 1.05f).coerceAtMost(0.86f), (value * 1.18f).coerceAtMost(0.95f), alpha = 0.85f),
        accentGlow = hsvColor(hue + 16f, sat * 0.85f, (value * 1.06f).coerceAtMost(0.9f), alpha = 0.35f),
    )
}

/**
 * 封面的主色：柔焦色带里按饱和度加权平均（鲜艳的部分说了算，灰白的背景不把颜色拉灰），再把饱和度略提、亮度拉到
 * 适合当光的范围；本来就是黑白灰的封面仍然是中性的光。
 */
internal fun shelfCoverTint(wash: ImageBitmap): Color {
    val bitmap = wash.asAndroidBitmap()
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return Color.White
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val hsv = FloatArray(3)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var total = 0.0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val weight = TINT_BASE_WEIGHT + hsv[1] * hsv[1] * hsv[2]
        red += r * weight
        green += g * weight
        blue += b * weight
        total += weight
    }
    if (total <= 0.0) return Color.White
    android.graphics.Color.RGBToHSV((red / total).toInt(), (green / total).toInt(), (blue / total).toInt(), hsv)
    hsv[1] = (hsv[1] * TINT_SATURATION_BOOST).coerceAtMost(TINT_MAX_SATURATION)
    hsv[2] = hsv[2].coerceIn(TINT_MIN_VALUE, TINT_MAX_VALUE)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * 书架下方的信息行：ADV 对话框，宽屏（≥ [WIDE_INFO_MIN_WIDTH]）时左边再放一张「我的书架」卡填上两侧空白。
 * 这一行只订阅允许显示的页，不直接跟随滚动途中的每一页。
 */
@Composable
private fun ShelfInfoRow(
    pagerState: PagerState,
    knobSeek: ShelfKnobSeek,
    scrubbing: State<Boolean>,
    games: List<ScanGame>,
    hideTitleTag: Boolean,
    onContinue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val wide = maxWidth >= WIDE_INFO_MIN_WIDTH
        val reveal = rememberShelfSummaryReveal(wide)
        val showSummary by remember(reveal) { derivedStateOf(structuralEqualityPolicy()) { reveal.value > 0.001f } }
        ShelfInfoLayout(reveal = { reveal.value }) {
            if (showSummary) {
                ShelfSummaryCard(
                    games = games,
                    hideTitleTag = hideTitleTag,
                    onContinue = onContinue,
                    modifier = Modifier.layoutId(ShelfInfoSlot.Summary),
                )
            }
            val index = rememberShelfInfoPage(pagerState, knobSeek, scrubbing)
            ShelfAdvWindow(
                game = games.getOrNull(index),
                index = index,
                count = games.size,
                hideTitleTag = hideTitleTag,
                modifier = Modifier.layoutId(ShelfInfoSlot.Adv),
            )
        }
    }
}

private enum class ShelfInfoSlot { Summary, Adv }

/**
 * 信息行的排布：「我的书架」卡与 ADV 对话框并排，**两张卡的上沿与下沿都对齐**（名牌压在对话框上沿之外，不算卡高）。
 * 左边卡的高度跟着对话框走（对话框里的字行数不同时，两张卡一起变高变矮），内容自己放不下时才比对话框高。
 * Row 做不到：它先量左边再量右边，左边没法按右边的高度定尺寸。
 */
@Composable
private fun ShelfInfoLayout(reveal: () -> Float, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val gap = SHELF_INFO_GAP.roundToPx()
        val overlap = NAME_TAG_OVERLAP.roundToPx()
        val summary = measurables.firstOrNull { it.layoutId == ShelfInfoSlot.Summary }
        val adv = measurables.firstOrNull { it.layoutId == ShelfInfoSlot.Adv }
        // 卡片按出现进度让出宽度（0..1）：宽度连续变化，对话框随之连续让出 / 收回
        val f = if (summary != null) reveal().coerceIn(0f, 1f) else 0f
        val summaryWidth = if (summary != null) SUMMARY_WIDTH.roundToPx().coerceAtMost(constraints.maxWidth) else 0
        val used = if (summary != null) ((summaryWidth + gap) * f).roundToInt() else 0
        val advWidth = (constraints.maxWidth - used).coerceIn(0, ADV_MAX_WIDTH.roundToPx())
        val advPlaceable = adv?.measure(Constraints(advWidth, advWidth, 0, constraints.maxHeight))
        val advHeight = advPlaceable?.height ?: 0
        // 卡片本体 = 对话框去掉上面的名牌悬出的那一截
        val body = (advHeight - overlap).coerceAtLeast(0)
        val summaryHeight = summary?.let { maxOf(body, it.minIntrinsicHeight(summaryWidth)).coerceAtMost(constraints.maxHeight) } ?: 0
        val summaryPlaceable = summary?.measure(Constraints.fixed(summaryWidth, summaryHeight))
        val height = maxOf(advHeight, summaryHeight + overlap).coerceAtMost(constraints.maxHeight)
        val width = used + (advPlaceable?.width ?: 0)
        layout(width, height) {
            // 卡片整张（内容排版不变）从左侧滑进来并淡入，不被挤窄
            summaryPlaceable?.placeWithLayer(0, height - summaryHeight) {
                alpha = f
                translationX = -(1f - f) * (summaryWidth + gap)
            }
            advPlaceable?.place(used, height - advHeight)
        }
    }
}

/**
 * 玻璃外观风格（复古玻璃 / 白雾玻璃）下给书架上的卡片、气泡与启动胶囊加一层玻璃光学：上缘一抹高光、
 * 顶边一线镜面反光（两端随圆角淡出）、底部压一点深，读起来是一块有厚度的玻璃；默认外观（浅色 / 深色）原样返回，
 * 仍是平的卡片——两类外观的材质分得出来。挂在背景之后、内边距之前。
 */
internal fun Modifier.shelfGlassFinish(
    shape: androidx.compose.ui.graphics.Shape = AppComponentShape,
    corner: Dp = AppComponentCornerRadius,
    sheen: Color = ShelfGlassSheen,
    specular: Color = ShelfGlassSpecular,
    base: Color = ShelfGlassBase,
): Modifier {
    if (!AppThemeColors.isGlass && !AppThemeColors.isMilkGlass) return this
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val body = Brush.verticalGradient(
            0f to sheen,
            GLASS_SHEEN_REACH to Color.Transparent,
            1f - GLASS_BASE_REACH to Color.Transparent,
            1f to base,
        )
        val inset = minOf(corner.toPx(), size.height / 2f, size.width / 2f)
        val line = 1.dp.toPx()
        val edge = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.25f to specular,
            0.75f to specular,
            1f to Color.Transparent,
            startX = inset,
            endX = size.width - inset,
        )
        onDrawWithContent {
            drawOutline(outline, body)
            drawContent()
            if (size.width - 2f * inset > line) {
                drawLine(edge, Offset(inset, line), Offset(size.width - inset, line), strokeWidth = line)
            }
        }
    }
}

/**
 * ADV 对话框：左上角名牌写引擎名，正文是游戏名（模糊替换）与序号（滚动数字），下面一句打字机短句，
 * 最下面是档案卡式的三格数据。
 */
@Composable
private fun ShelfAdvWindow(
    game: ScanGame?,
    index: Int,
    count: Int,
    hideTitleTag: Boolean,
    modifier: Modifier = Modifier,
) {
    if (game == null) return
    // 对话框里的书名最多 [ADV_MAX_CHARS] 个字（比书脊、气泡宽松得多），超出用「…」收尾；再按宽度省略兜底，不压到右边的序号
    val title = limitShelfTitle(if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title, ADV_MAX_CHARS)
    val position = stringResource(R.string.game_shelf_position, index + 1, count)
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current.applicationContext
    val statsFlow = remember(context, game.uri) { GamePlaytimeRepository.observe(context, game.uri) }
    val stats: GamePlaytimeStats? by statsFlow.collectAsStateWithLifecycle(initialValue = null)
    Box(modifier) {
        Column(
            modifier = Modifier
                .padding(top = NAME_TAG_OVERLAP)
                .fillMaxWidth()
                .background(ShelfCardSurface, AppComponentShape)
                .glassBorder()
                .shelfGlassFinish()
                .padding(start = 18.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlurCrossfade(title, modifier = Modifier.weight(1f)) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clearAndSetSemantics { contentDescription = position },
                ) {
                    RollingNumberText(
                        text = String.format(Locale.ROOT, "%02d", index + 1),
                        color = accent,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = String.format(Locale.ROOT, " / %02d", count),
                        color = SecondaryTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ShelfLine(game, stats, Modifier.padding(top = 6.dp).fillMaxWidth())
            ShelfStatsStrip(game, stats, Modifier.padding(top = 10.dp))
        }
        // 名牌：像 ADV 里说话人的名字框，压在对话框左上沿
        BlurCrossfade(
            game.engine.displayName,
            modifier = Modifier.padding(start = 14.dp),
            blur = 0.dp,
        ) { engineName ->
            Text(
                engineName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ShelfSpineText,
                maxLines = 1,
                modifier = Modifier
                    .background(accent, AppComponentShape)
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            )
        }
    }
}

/** 对话框台词：玩过 → 一起度过的时长；打开过但没记到时长 → 一句续上故事的短句；没玩过 → 一句初见的招呼（短短一行，不带说明与角标）。逐字打出。 */
@Composable
private fun ShelfLine(game: ScanGame, stats: GamePlaytimeStats?, modifier: Modifier = Modifier) {
    val totalMillis = stats?.totalMillis?.takeIf { GamePlaytimeRepository.supports(game.engine) && it > 0L }
    val line = when {
        totalMillis != null -> stringResource(R.string.game_shelf_playtime, formatShelfDuration(totalMillis))
        game.openTime > 0L -> stringResource(R.string.game_shelf_continue)
        else -> stringResource(R.string.game_shelf_unplayed)
    }
    BlurCrossfade(line, modifier = modifier.clearAndSetSemantics { contentDescription = line }) { text ->
        val shown = rememberTypewriterLength(text)
        Text(
            text = text.take(shown),
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「我的书架」卡（仅宽屏）：收藏几部、翻开过几部（滚动数字），以及「继续：最近玩的那一本」——
 * 点一下书架就转到那一盒；一部都没翻开过时换成一句邀请。
 */
@Composable
private fun ShelfSummaryCard(
    games: List<ScanGame>,
    hideTitleTag: Boolean,
    onContinue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val opened = remember(games) { games.count { it.openTime > 0L } }
    val latest = remember(games) {
        games.indices.filter { games[it].openTime > 0L }.maxByOrNull { games[it].openTime }
    }
    Column(
        modifier = modifier
            .background(ShelfCardSurface, AppComponentShape)
            .glassBorder()
            .shelfGlassFinish()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        // 卡高由 ShelfInfoLayout 定成与 ADV 对话框一样高：多出来的空隙匀给三块内容之间，不堆在最底下
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✦",
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                stringResource(R.string.game_shelf_summary_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextColor,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            ShelfSummaryNumber(games.size, stringResource(R.string.game_shelf_summary_count))
            ShelfSummaryNumber(opened, stringResource(R.string.game_shelf_summary_opened))
        }
        if (latest != null) {
            val latestTitle = limitShelfTitle(games[latest].title.let { if (hideTitleTag) GameTitleTags.stripAll(it) else it }, SUMMARY_MAX_CHARS)
            Text(
                stringResource(R.string.game_shelf_summary_continue, latestTitle),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .pressSquish()
                    .background(accent.copy(alpha = 0.12f), AppComponentShape)
                    .clickable(interactionSource = null, indication = null) { onContinue(latest) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Text(
                stringResource(R.string.game_shelf_summary_fresh),
                style = MaterialTheme.typography.bodyMedium,
                color = SecondaryTextColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ShelfSummaryNumber(value: Int, label: String) {
    Column(Modifier.semantics(mergeDescendants = true) { }) {
        RollingNumberText(
            text = value.toString(),
            color = TextColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SecondaryTextColor)
    }
}

private suspend fun AwaitPointerEventScope.awaitShelfTrackTapOrDrag(down: PointerInputChange): PointerInputChange? {
    val slop = viewConfiguration.touchSlop
    var withinTapSlop = true
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.singleOrNull() ?: return null
        if (change.id != down.id || change.isConsumed) return null
        if (change.isOutOfBounds(size, extendedTouchPadding)) return null
        val travel = change.position - down.position
        withinTapSlop = withinTapSlop && travel.getDistance() < slop
        if (change.changedToUp()) {
            if (!withinTapSlop || change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                return null
            }
            change.consume()
            return change
        }
        if (abs(travel.x) >= slop) {
            change.consume()
            return change
        }
        val finalChange = awaitPointerEvent(PointerEventPass.Final).changes.singleOrNull() ?: return null
        if (finalChange.isConsumed) return null
    }
}

/**
 * 进度轨道（似有似无）：一根两端渐隐的发丝线，翻过的那段略深，每本一颗小点（多了改成竖直短刻度）；
 * 书架静止时淡到几乎看不出，一滑动、一拖动就浮现。轨道上的旋钮是当前那一盒的封面小头像。按住旋钮直接拖（旋钮 Q 弹放大、上方气泡显示正在经过的游戏，书架实时跟手转动，
 * 每越过一本轻点一下），松手吸附到最近的一盒；点轨道其它位置：震一下，旋钮飞到手指下、书架翻到那一盒；在别处横向拖动同样能定位。
 * 游戏少时轨道按本数收短、居中。
 */
@Composable
private fun ShelfScrubber(
    pagerState: PagerState,
    knobSeek: ShelfKnobSeek,
    scrubbingState: androidx.compose.runtime.MutableState<Boolean>,
    games: List<ScanGame>,
    hideTitleTag: Boolean,
    isInteractive: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val count = games.size
    val scope = rememberCoroutineScope()
    var scrubbing by scrubbingState
    var scrubSpeed by remember { mutableFloatStateOf(1f) }
    val knobLift = rememberTrackKnobLift(scrubbing)
    val scrubberMotion = rememberShelfScrubberMotion()
    val glide = rememberShelfGlide(pagerState)
    val interactive = isInteractive()
    val canSeek by rememberUpdatedState(isInteractive)
    // 旋钮一动轨道就跟着 Q 弹：单协程连续驱动弹簧，滑动期间零协程取消与重建
    LaunchedEffect(pagerState, knobSeek, scrubberMotion, count) {
        scrubberMotion.trackKnob(pagerState, knobSeek, count)
    }
    val scrubChannel = remember(pagerState) { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(pagerState, scrubChannel) {
        for (targetPos in scrubChannel) {
            if (!canSeek()) continue
            val nearest = targetPos.roundToInt()
            val fraction = targetPos - nearest
            coroutineScope {
                val scroll = async { pagerState.scrollToPage(nearest, fraction) }
                try {
                    scroll.await()
                } catch (_: CancellationException) {
                    // Pager 的互斥滚动可抢占这次寻址；真正取消消费协程时仍向外传播。
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }
    val localDensity = LocalDensity.current
    val bubbleActive by rememberShelfScrubberBubbleActive(scrubbing)
    // 似有似无：书架静止时轨道很淡，一滑动、一拖动就浮现；中性墨色，不用主题色
    val presence = rememberTrackPresence(scrubbing || pagerState.isScrollInProgress)
    val ink = ShelfTrackInk
    val description = stringResource(R.string.game_shelf_scrubber_description)
    fun maxOverpull(inset: Float, density: Density): Float = with(density) {
        minOf(SCRUBBER_MAX_RUBBER_DP.dp.toPx(), (inset - KNOB_SIZE.toPx() * 0.78f).coerceAtLeast(0f))
    }
    Box(modifier.height(RULER_HEIGHT)) {
        Spacer(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(count, glide, interactive) {
                    if (!interactive) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!canSeek()) return@awaitEachGesture
                        val width = size.width.toFloat()
                        val inset = rulerInset(width, count, this)
                        val overpullLimit = maxOverpull(inset, this)
                        val knobX = shelfRulerVisualX(
                            position = knobSeek.position(pagerState.pagePosition()),
                            count = count,
                            width = width,
                            inset = inset,
                            rubberPx = scrubberMotion.rubberPx,
                            maxOverpullPx = overpullLimit,
                        )
                        val booksPerPx = (count - 1) / (width - 2f * inset).coerceAtLeast(1f)
                        var last = pagerState.pagePosition().roundToInt()
                        var target = pagerState.pagePosition()
                        fun seekTo(rawPosition: Float) {
                            val lastIndex = (count - 1).toFloat().coerceAtLeast(0f)
                            target = rawPosition.coerceIn(0f, lastIndex)
                            val overPx = (rawPosition - target) / booksPerPx.coerceAtLeast(1e-6f)
                            scrubberMotion.onEdgeOverpull(overPx, overpullLimit)
                            val nearest = target.roundToInt()
                            scrubChannel.trySend(target)
                            if (nearest != last) {
                                last = nearest
                                MotionHaptics.perform(MotionHaptics.Kind.Tick)
                            }
                        }
                        // 相对拖动：从抓住的位置起算；手指往上移就放慢（换档时重新起算，不跳）
                        var anchorX: Float
                        var anchorPosition: Float
                        var lastX: Float
                        var lastTimeMs: Long
                        var speed = 1f
                        val pointer = if (abs(down.position.x - knobX) <= KNOB_TOUCH_RADIUS.toPx()) {
                            // 按在旋钮上：立刻抓住，旋钮不跳到手指下
                            down.consume()
                            knobSeek.cancel()
                            scrubberMotion.onDragStart()
                            anchorX = down.position.x
                            anchorPosition = target
                            lastX = down.position.x
                            lastTimeMs = down.uptimeMillis
                            scrubbing = true
                            down.id
                        } else {
                            val drag = awaitShelfTrackTapOrDrag(down) ?: return@awaitEachGesture
                            if (!canSeek()) return@awaitEachGesture
                            if (!drag.pressed) {
                                // 轻点轨道：只在这一下震一次，旋钮带着 Q 弹果冻拉伸飞到手指下，书架跟着翻到那一本
                                val tapped = shelfRulerPosition(down.position.x, count, width, inset).roundToInt()
                                scrubberMotion.onTapSeek()
                                MotionHaptics.perform(MotionHaptics.Kind.Tick)
                                scope.launch { if (canSeek()) knobSeek.seek(pagerState, glide, tapped) }
                                return@awaitEachGesture
                            }
                            knobSeek.cancel()
                            scrubberMotion.onDragStart()
                            scrubbing = true
                            seekTo(shelfRulerPosition(drag.position.x, count, width, inset))
                            anchorX = drag.position.x
                            anchorPosition = target
                            lastX = drag.position.x
                            lastTimeMs = drag.uptimeMillis
                            drag.id
                        }
                        try {
                            drag(pointer) dragMotion@{ change ->
                                if (!canSeek()) return@dragMotion
                                val dt = (change.uptimeMillis - lastTimeMs) / 1000f
                                val dx = change.position.x - lastX
                                lastX = change.position.x
                                lastTimeMs = change.uptimeMillis
                                val nextSpeed = shelfScrubSpeed(down.position.y - change.position.y, density)
                                if (nextSpeed != speed) {
                                    speed = nextSpeed
                                    scrubSpeed = nextSpeed
                                    anchorX = change.position.x
                                    anchorPosition = target
                                }
                                seekTo(anchorPosition + (change.position.x - anchorX) * booksPerPx * speed)
                                scrubberMotion.onDragMotion(dx, dt, density)
                                change.consume()
                            }
                        } finally {
                            scrubbing = false
                            scrubSpeed = 1f
                            scrubberMotion.onDragEnd()
                        }
                        if (canSeek()) {
                            scope.launch { if (canSeek()) pagerState.settleScrub(last) }
                        }
                    }
                }
                .shelfTrack(
                    count = count,
                    insetProvider = { w -> rulerInset(w, count, localDensity) },
                    ink = ink,
                    presenceProvider = { presence.value },
                    positionProvider = { knobSeek.position(pagerState.pagePosition()) },
                    knobGapProvider = {
                        val scale = shelfKnobScale(knobLift.value, KNOB_LIFT_SCALE)
                        with(localDensity) { (KNOB_SIZE.toPx() / 2f * scale + KNOB_GAP.toPx()).coerceAtLeast(0f) }
                    },
                    knobXProvider = { position, width, inset ->
                        shelfRulerVisualX(
                            position = position,
                            count = count,
                            width = width,
                            inset = inset,
                            rubberPx = scrubberMotion.rubberPx,
                            maxOverpullPx = maxOverpull(inset, localDensity),
                        )
                    },
                    lineScaleProvider = { 1f + 0.35f * knobLift.value.coerceAtLeast(0f) },
                    agitationProvider = { scrubberMotion.railBounce },
                ),
        )
        // 旋钮：跟着翻页位置连续移动（布局阶段读位置，滑动中不重组）
        ShelfTrackKnob(
            pagerState = pagerState,
            games = games,
            lift = knobLift,
            scrubberMotion = scrubberMotion,
            presence = presence,
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val width = constraints.maxWidth.toFloat()
                val inset = rulerInset(width, count, this)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val center = shelfRulerVisualX(
                        position = knobSeek.position(pagerState.pagePosition()),
                        count = count,
                        width = width,
                        inset = inset,
                        rubberPx = scrubberMotion.rubberPx,
                        maxOverpullPx = maxOverpull(inset, localDensity),
                    )
                    placeable.place(
                        (center - placeable.width / 2f).roundToInt(),
                        ((constraints.maxHeight - placeable.height) / 2f).roundToInt(),
                    )
                }
            },
        )
        // 拖动时的气泡：平时完全静止时不订阅书架偏移，拖动期间才挂载渲染
        if (bubbleActive) {
            Box(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val width = constraints.maxWidth.toFloat()
                    val inset = rulerInset(width, count, this)
                    layout(constraints.maxWidth, 0) {
                        val center = shelfRulerVisualX(
                            position = knobSeek.position(pagerState.pagePosition()),
                            count = count,
                            width = width,
                            inset = inset,
                            rubberPx = scrubberMotion.rubberPx,
                            maxOverpullPx = maxOverpull(inset, localDensity),
                        )
                        val x = (center - placeable.width / 2f)
                            .coerceIn(0f, (constraints.maxWidth - placeable.width).coerceAtLeast(0).toFloat())
                        placeable.place(x.roundToInt(), -placeable.height - BUBBLE_GAP.roundToPx())
                    }
                },
            ) {
                BlurVisibility(scrubbing) {
                    ShelfScrubBubble(
                        pagerState = pagerState,
                        games = games,
                        hideTitleTag = hideTitleTag,
                        speed = scrubSpeed,
                        modifier = Modifier.graphicsLayer {
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            rotationZ = scrubberMotion.bubbleTiltDegrees
                        },
                    )
                }
            }
        }
    }
}

/**
 * 进度轨道的旋钮：当前那一盒的封面小头像，套一圈半透明玻璃环；书架静止时略淡，按住 / 拖动 / 点按时带有
 * 随轨道上下浮动，按住时等比例放大并亮起中性柔光。翻到另一盒时头像淡入替换（不加模糊：拖动时每越过一本都会换一次）。
 */
@Composable
private fun ShelfTrackKnob(
    pagerState: PagerState,
    games: List<ScanGame>,
    lift: State<Float>,
    scrubberMotion: ShelfScrubberMotion,
    presence: State<Float>,
    modifier: Modifier = Modifier,
) {
    val game = games.getOrNull(pagerState.currentPage) ?: return
    val glow = ShelfKnobGlow
    val ring = ShelfKnobGlass
    val outline = ShelfTrackOutline
    Box(
        modifier
            .size(KNOB_SIZE)
            // 中性柔光只在按住时亮起：渐变着色器在 drawWithCache 中按最大缩放缓存一次，每帧仅随按住缩放绘制
            .drawWithCache {
                val baseRadius = size.minDimension
                val maxRadius = baseRadius * 1.5f
                val centerOffset = Offset(size.width / 2f, size.height / 2f)
                val haloBrush = Brush.radialGradient(
                    0f to glow,
                    1f to glow.copy(alpha = 0f),
                    center = centerOffset,
                    radius = maxRadius,
                )
                onDrawBehind {
                    val pressed = lift.value.coerceAtLeast(0f)
                    if (pressed > 0.01f) {
                        val scale = shelfKnobScale(lift.value, KNOB_LIFT_SCALE)
                        val drawAlpha = (0.6f * pressed * trackKnobAlpha(presence.value)).coerceIn(0f, 1f)
                        val r = baseRadius * scale
                        val s = r / maxRadius
                        withTransform({
                            scale(s, s, pivot = center)
                        }) {
                            drawCircle(haloBrush, radius = maxRadius, center = center, alpha = drawAlpha)
                        }
                    }
                }
            }
            .graphicsLayer {
                val scale = shelfKnobScale(lift.value, KNOB_LIFT_SCALE)
                scaleX = scale
                scaleY = scale
                // 随轨道一起弹：上下浮一点，线头始终接在旋钮上（只平移，旋钮始终是正圆）
                translationY = -scrubberMotion.railBounce * KNOB_BOUNCE_RATIO.dp.toPx()
                alpha = trackKnobAlpha(presence.value)
            }
            // 玻璃环：半透明白面 + 一圈发丝轮廓，透出底下的画面
            .background(ring, CircleShape)
            .glassEdgeStroke(CircleShape, width = 1.dp, color = outline)
            .padding(KNOB_RING)
            .clip(CircleShape)
            .background(game.engine.coverColor())
            .clearAndSetSemantics { },
    ) {
        BlurCrossfade(game, modifier = Modifier.fillMaxSize(), blur = 0.dp, contentKey = { it.uri }) { shown ->
            val cover by rememberCoverBitmap(shown.coverUri)
            cover?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 进度轨道两端留白：游戏少时按每本最多 [RULER_MAX_SPACING] 收短轨道并居中。 */
private fun rulerInset(width: Float, count: Int, density: Density): Float = with(density) {
    val minInset = RULER_INSET.toPx()
    if (count <= 1) return minInset
    val natural = (count - 1) * RULER_MAX_SPACING.toPx()
    maxOf(minInset, (width - natural) / 2f)
}

/**
 * 拖动气泡：正在经过的游戏名（不带序号，序号在下方对话框里）；放慢时显示当前速度，书很多时提示可以上滑放慢。
 */
@Composable
private fun ShelfScrubBubble(
    pagerState: PagerState,
    games: List<ScanGame>,
    hideTitleTag: Boolean,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    val game = games.getOrNull(pagerState.currentPage) ?: return
    // 气泡里的书名最多 [BUBBLE_MAX_CHARS] 个字：拖动时一眼认出是哪一本就够了，全名在对话框里
    val title = limitShelfTitle(if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title, BUBBLE_MAX_CHARS)
    val hint = when {
        speed < 1f -> stringResource(R.string.game_shelf_scrub_speed, shelfSpeedLabel(speed))
        games.size > FINE_SCRUB_HINT_BOOKS -> stringResource(R.string.game_shelf_scrub_slow)
        else -> null
    }
    Column(
        modifier = modifier
            .background(NavWhite, AppComponentShape)
            .glassEdgeStroke(AppComponentShape, width = 1.dp, color = ShelfTrackOutline)
            .shelfGlassFinish()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = TextColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 200.dp),
    )
    if (hint != null) {
        BlurCrossfade(hint, modifier = Modifier.padding(top = 2.dp)) { text ->
            Text(text, style = MaterialTheme.typography.bodyMedium, color = SecondaryTextColor, maxLines = 1)
        }
    }
    }
}

/** 精细拖动速度的显示：½、¼、1/10。 */
private fun shelfSpeedLabel(speed: Float): String = when {
    speed >= 0.5f -> "1/2"
    speed >= 0.25f -> "1/4"
    else -> "1/10"
}

/** 封面宽度上限（容器宽度比例；游戏很少时更大）与下限。 */
private const val COVER_WIDTH_RATIO = 0.5f
private const val COVER_WIDTH_RATIO_FEW = 0.5f
private val MIN_COVER_WIDTH = 96.dp
private val MIN_INFO_HEIGHT = 150.dp
private val SINGLE_GAME_SPACER = 16.dp

/**
 * 平板竖屏（舞台宽 ≥ [TALL_STAGE_MIN_WIDTH]、高宽比 ≥ [TALL_STAGE_MIN_ASPECT]）：封面占舞台宽的 [COVER_WIDTH_RATIO_TALL]，
 * 书架（含倒影）最多占舞台高的 [TALL_STAGE_FILL]，多出来的高度按 [TALL_STAGE_RAISE] 的比例垫在搁板下面（抬高底板），其余留在上面。
 */
private val TALL_STAGE_MIN_WIDTH = 560.dp
private const val TALL_STAGE_MIN_ASPECT = 1.15f
private const val COVER_WIDTH_RATIO_TALL = 0.62f
private const val TALL_STAGE_FILL = 0.88f
private const val TALL_STAGE_RAISE = 0.5f

/** 占位字号的参照封面宽度：书架上正中那一盒手机竖屏时字号为 bodyMedium × 它与这个宽度之比。 */
private val PLACEHOLDER_REFERENCE_WIDTH = 150.dp

/**
 * 「游戏很少」的上限（盒子略大）；书不多时自适应转角的搜索范围：最浅 / 标准书脊角度、步长（度），
 * 以及参与搜索的最多本数（再多肯定放不下，直接用书脊排列）。
 */
private const val FEW_GAMES = 5
private const val OPEN_TURN_DEGREES = 58.0
private const val SPINE_TURN_DEGREES = 86.0
private const val TURN_SEARCH_STEP = 2.0
private const val FIT_SEARCH_MAX_BOOKS = 16

/** 书脊厚度（封面宽比例）及上下限。 */
private const val SPINE_RATIO = 0.15f
private val MIN_SPINE = 22.dp
private val MAX_SPINE = 36.dp

/** 书脊间缝、停稳时居中盒子两侧的呼吸位。 */
private val SPINE_GAP = 3.dp
private val REST_GAP = 12.dp

/** 被选中的那一盒抬起的高度，以及为它（抬起 + 放大 + 外倾）预留的顶部空间。 */
private val LIFT = 18.dp
private val LIFT_ROOM = 34.dp

/** 封面越大，抽出放大时冒出去的越多：顶部额外按盒高的这个比例留空间。 */
private const val LIFT_ROOM_RATIO = 0.1f
private val STAGE_TOP_ROOM = 8.dp

/** 搁板（倒影区）深度占盒子高度的比例。 */
private const val FLOOR_RATIO = 0.14f
private val MIN_STAGE_HEIGHT = LIFT_ROOM + STAGE_TOP_ROOM + SHELF_BELOW_BOARD.dp +
    MIN_COVER_WIDTH * (4f / 3f * (1f + FLOOR_RATIO + LIFT_ROOM_RATIO))

/** 居中盒子脚下与身后那团光（封面主色）的透明度。 */
private const val LEDGE_GLOW_ALPHA = 0.42f

/** 整排平移时额外组合的本数上限、两端书立外侧的边距。 */
private const val MAX_BEYOND_BOOKS = 24
private val ROW_END_MARGIN = 12.dp

/** 书脊：顶部封条高度、竖排文字起点与行高、书腰高度（占盒子高度）、柔焦色带采样尺寸（像素）。 */
internal val SPINE_CAP = 3.dp
private val SPINE_TITLE_TOP = 12.dp

/** 书脊上最多排几个字：中日文逐字竖排时、西文整行侧转时。 */
private const val SPINE_MAX_CHARS = 8
private const val SPINE_MAX_LATIN_CHARS = 16

/** 拖动进度时气泡里书名最多几个字。 */
private const val BUBBLE_MAX_CHARS = 12

/** 书架下方卡片里书名最多几个字：ADV 对话框的标题行、「我的书架」卡里的「继续」。 */
private const val ADV_MAX_CHARS = 18
private const val SUMMARY_MAX_CHARS = 12

/** CJK 字符区的起点（之前的都算西文）；西文字符占到这个比例的书名整行侧转。 */
private const val CJK_START = 0x2E80
private const val LATIN_SHARE = 0.7f
private val SPINE_LINE_HEIGHT = 13.sp
private const val SPINE_FOOT_RATIO = 0.12f
private const val SPINE_FOIL_LIGHTEN = 0.62f
private val SPINE_TAG_WIDTH = 4.dp
private val SPINE_TAG_LENGTH = 11.dp
private const val SPINE_WASH_WIDTH = 4
private const val SPINE_WASH_HEIGHT = 32

/** 背景色晕的采样尺寸（像素）。 */
private const val BACKDROP_WASH_WIDTH = 18
private const val BACKDROP_WASH_HEIGHT = 24

/** 封面主色：每个像素的基础权重（再加上饱和度² × 亮度）、饱和度放大倍数与上限、亮度范围。 */
private const val TINT_BASE_WEIGHT = 0.05f
private const val TINT_SATURATION_BOOST = 1.3f
private const val TINT_MAX_SATURATION = 0.85f
private const val TINT_MIN_VALUE = 0.6f
private const val TINT_MAX_VALUE = 0.95f

/** 玻璃光学：上缘高光向下铺到多高、底部压深从多高开始（占卡片高度的比例）。 */
private const val GLASS_SHEEN_REACH = 0.45f
private const val GLASS_BASE_REACH = 0.3f

/** 书签丝带尺寸。 */
private const val RIBBON_WIDTH_RATIO = 0.05f
private val RIBBON_MIN_WIDTH = 6.dp
private val RIBBON_MAX_WIDTH = 9.dp
private const val RIBBON_LENGTH_RATIO = 2.7f
private const val RIBBON_DEEPEN = 0.3f
private const val RIBBON_ALPHA = 0.94f

/** 对话框：最大宽度（平板 / 横屏不拉满）、名牌压在对话框上沿的高度。 */
private val ADV_MAX_WIDTH = 560.dp

/** 显示「我的书架」卡所需的最小宽度，以及卡片宽度。 */
private val WIDE_INFO_MIN_WIDTH = 760.dp
private val SUMMARY_WIDTH = 240.dp
private val SHELF_INFO_GAP = 16.dp
private val NAME_TAG_OVERLAP = 13.dp

/** 进度轨道：区域高度、两端最小留白、游戏少时每本的最大间距；旋钮尺寸、两侧断口、触摸半径、按住放大；气泡间距。 */
private val RULER_HEIGHT = 38.dp
private val SCRUBBER_TOP_SPACE = 10.dp
private val SCRUBBER_BOTTOM_SPACE = 6.dp
private val RULER_INSET = 34.dp
private val RULER_MAX_SPACING = 48.dp
private val KNOB_SIZE = 26.dp
private val KNOB_GAP = 5.dp
private val KNOB_RING = 3.dp
private val KNOB_TOUCH_RADIUS = 26.dp
private const val KNOB_LIFT_SCALE = 0.22f
private const val KNOB_BOUNCE_RATIO = 0.8f

/** 书多于这么多本时，拖动气泡提示可以上滑放慢。 */
private const val FINE_SCRUB_HINT_BOOKS = 30

/** 封面上的启动：离封面底边的距离、跟手位移上限（收在底边距内避免撞边）、▶ 圆片的直径；封面窄于这个宽度时只留 ▶。 */
private val PLAY_BADGE_INSET = 12.dp
private val PLAY_BADGE_FOLLOW = 4.dp
private val PLAY_BALL_SIZE = 38.dp

/** ▶ 的高度（占圆片直径的比例）与圆角描边粗细（同上）。 */
private const val PLAY_GLYPH_RATIO = 0.4f
private const val PLAY_BALL_GLYPH_RATIO = 0.36f
private const val PLAY_GLYPH_ROUND = 0.09f

/** 盒面棱边的宽度。 */
private val BEVEL_WIDTH = 3.dp

/** 封面倒角：边长占封面宽的比例与上下限。倒角高光：亮线宽、斜面那层淡光的宽度与浓度（相对亮线）。 */
private const val CHAMFER_RATIO = 0.04f
private val MIN_CHAMFER = 4.dp
private val MAX_CHAMFER = 8.dp
private val CHAMFER_GLINT_WIDTH = 0.9.dp
private val CHAMFER_FACET_WIDTH = 2.6.dp
private const val CHAMFER_FACET_SHARE = 0.28f
private const val SQRT_2 = 1.4142135f
private val BUBBLE_GAP = 8.dp
