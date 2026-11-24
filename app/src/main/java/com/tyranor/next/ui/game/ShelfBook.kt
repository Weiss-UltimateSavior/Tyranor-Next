package com.tyranor.next.ui.game

import android.graphics.Typeface
import android.os.Build
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.AbsoluteCutCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tyranor.next.R
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.playtime.GamePlaytimeRepository
import com.tyranor.next.core.game.playtime.GamePlaytimeStats
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.CoverBadgeBackground
import com.tyranor.next.theme.CoverBadgeText
import com.tyranor.next.theme.GlassBorder
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.SheetScrimColor
import com.tyranor.next.theme.ShelfBookClothWeaveDark
import com.tyranor.next.theme.ShelfBookClothWeaveLight
import com.tyranor.next.theme.ShelfBookDeleteStyle
import com.tyranor.next.theme.ShelfBookEndpaperBase
import com.tyranor.next.theme.ShelfBookFoilAccent
import com.tyranor.next.theme.ShelfBookFooterStyle
import com.tyranor.next.theme.ShelfBookGlassBase
import com.tyranor.next.theme.ShelfBookGlassSheen
import com.tyranor.next.theme.ShelfBookGlassSpecular
import com.tyranor.next.theme.ShelfBookGlowAlpha
import com.tyranor.next.theme.ShelfBookHeadbandBase
import com.tyranor.next.theme.ShelfBookHeadbandStripe
import com.tyranor.next.theme.ShelfBookLight
import com.tyranor.next.theme.shelfBookLaunchStyle
import com.tyranor.next.theme.ShelfBookMarker
import com.tyranor.next.theme.ShelfBookPageEdge
import com.tyranor.next.theme.ShelfBookPaperEdgeTint
import com.tyranor.next.theme.ShelfBookPaperHighlight
import com.tyranor.next.theme.ShelfBookPaperSurface
import com.tyranor.next.theme.ShelfBookRibbonEdge
import com.tyranor.next.theme.ShelfBookShade
import com.tyranor.next.theme.ShelfBookStackHighlight
import com.tyranor.next.theme.ShelfBookStackSurface
import com.tyranor.next.theme.ShelfBookTabBorder
import com.tyranor.next.theme.ShelfBookTabIndicator
import com.tyranor.next.theme.ShelfBookTabSurface
import com.tyranor.next.theme.ShelfBookTabWash
import com.tyranor.next.theme.ShelfChamferDim
import com.tyranor.next.theme.ShelfChamferLight
import com.tyranor.next.theme.ShelfDepthShade
import com.tyranor.next.theme.ShelfFaceShade
import com.tyranor.next.theme.ShelfGloss
import com.tyranor.next.theme.ShelfSpineText
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.LocalAppDialogHost
import com.tyranor.next.ui.common.LocalDialogBodyExit
import com.tyranor.next.ui.common.LocalDialogExit
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.SplitEndsActionRow
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.isTabletScreen
import com.tyranor.next.ui.common.motion.SuppressPageDepth
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.nestedPanelContentDim
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.rememberEntranceState
import com.tyranor.next.ui.common.notice.NoticeHost
import com.tyranor.next.ui.common.privacy.DismissOnAppLockEffect
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 「游戏盒」风格的游戏操作：轻点书架正中那一盒，它被抽出来、飞到眼前、封面绕书脊翻开，书页上是这一部的扉页
 * （引擎名牌、第几本、游戏名、档案卡三格数据）与目录（启动文件 / 搜索封面 / 游玩状态 / 存档 / 补丁 / 引擎设置，
 * 一行一章、引导点连到当前值），页脚是「启动游戏」。代替其他卡片风格的底部操作抽屉；条目、显示条件、弹窗、
 * 跳转与启动流程全部与抽屉共用 [GameActions]，不复制行为。
 *
 * 书是实物（封面、封板、书脊跟着这本游戏的封面走）；书页是当前外观主题的面板材质：默认白卡 / 深色卡，
 * 复古玻璃是深色玻璃（发丝描边 + 上缘高光与镜面反光），白雾玻璃是页面底图 + 白纱的亮玻璃。
 * 点书外的空白、返回或把书页往右拖都会合上，书飞回书架原位。运动见 `ShelfBookMotion.kt`。
 */
@Composable
internal fun ShelfOpenBook(
    game: ScanGame,
    stage: ShelfBookStage,
    quickLaunched: Boolean,
    onDismiss: () -> Unit,
    onGameUpdated: (ScanGame) -> Unit,
    onGameCoverUpdated: (String?, ScanGame) -> Unit,
    onGameLaunchFileUpdated: ((String, String?) -> Unit)?,
    onGameBindingUpdated: (String, ScanGame) -> Unit,
    onGameRenamed: ((String, String) -> Unit)?,
    onDeleteGame: () -> Unit,
    onQuickLaunchToggle: () -> Boolean,
    onEngineSettings: () -> Unit,
) {
    // 书摊开着（含起飞 / 合上途中）时背后的页面被整本书盖住：不做页面景深（下沉 / 翻折 / 褪色），
    // 书里弹出的弹窗、抽屉、二级页都沿用，不用各自处理（见 SuppressPageDepth）
    SuppressPageDepth()
    val systemNnnRequest = rememberSystemNnnSheetRequest(game)
    val motion = rememberShelfBookMotion(stage, game.uri) {
        systemNnnRequest.leave()
        onDismiss()
    }
    val owner = LocalLifecycleOwner.current
    // 选项弹出的二级弹窗翻到书的下一页上（见 ShelfBookPages）；书里的选项、弹窗、跳转都从这里经过
    val pages = rememberShelfBookPages()
    val tabMotion = rememberShelfBookTabMotion()
    var spreadLeafSource by rememberSaveable(game.uri) { mutableStateOf(ShelfBookTab.Contents) }
    val host = remember(motion, owner) { ShelfBookHost(motion, owner) }
    val scrimAlpha = SheetScrimColor.alpha
    val quickActions = rememberGameQuickActionsController(
        game = game,
        quickLaunched = quickLaunched,
        closeOnQuickAction = false,
        onDismiss = { motion.close() },
        onGameUpdated = onGameUpdated,
        onGameCoverUpdated = { updated -> onGameCoverUpdated(null, updated) },
        onGameRenamed = { uri, name ->
            onGameRenamed?.invoke(uri, name)
        },
        onDeleteGame = onDeleteGame,
        onQuickLaunchToggle = onQuickLaunchToggle,
        canInteract = { motion.interact() && !pages.turned },
        onShortcutCropOpened = { spreadLeafSource = ShelfBookTab.Manage },
    )
    CompositionLocalProvider(LocalAppDialogHost provides pages) {
        GameActions(
            game = game,
            host = host,
            systemNnnRequest = systemNnnRequest,
            onGameUpdated = onGameUpdated,
            onGameCoverUpdated = onGameCoverUpdated,
            onGameLaunchFileUpdated = onGameLaunchFileUpdated,
            onGameBindingUpdated = onGameBindingUpdated,
            onEngineSettings = onEngineSettings,
        ) { ui ->
            Dialog(
                // 返回：翻到了后面的页先翻回来，在主书页时合上书
                onDismissRequest = { pages.dismissOrClose { motion.close() } },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                motion.BindWindow(scrimAlpha)
                // 书是独立窗口、层级在解锁门之上：应用锁生效时立即收起
                DismissOnAppLockEffect { motion.dismissImmediately() }
                ShelfBookScene(
                    game = game,
                    ui = ui,
                    motion = motion,
                    pages = pages,
                    host = host,
                    stage = stage,
                    tabMotion = tabMotion,
                    quickActions = quickActions,
                    spreadLeafSource = spreadLeafSource,
                    onLeafSource = { spreadLeafSource = it },
                )
                quickActions.HostDialogs()
            }
        }
    }
}

/**
 * 书作为游戏操作的宿主：合上 / 跳转途中拒绝点击；启动成功后等游戏盖上来再收起。
 * 进还没改成书页的二级页面（引擎设置）时书留着、不翻页，直接走全局界面切换动画，回来时仍摊开着目录。
 */
private class ShelfBookHost(
    private val motion: ShelfBookMotion,
    private val owner: LifecycleOwner,
) : GameActionsHost {
    override fun interact(): Boolean = motion.interact()

    override val leaving: Boolean get() = motion.leaving

    override fun openDetails(ordinaryDismiss: Boolean, open: () -> Unit) {
        if (!motion.interact()) return
        // 还没改成书页的二级界面：书不翻页，直接走全局的界面切换动画（侧推）；书留在原处，回来时仍摊开着目录
        open()
        if (motion.enhanced) {
            motion.stayDuringNavigation(owner)
        } else if (ordinaryDismiss) {
            motion.dismissImmediately()
        }
    }

    override fun onGameLaunched() = motion.navigateAway(owner)

    override fun close() = motion.close()
}

/** 翻开书在不同屏幕比例下的摆放形态：靠右露左窄边 / 居中收束左窄边 / 完整双页跨页。 */
internal enum class ShelfBookLayoutMode { SinglePeek, SingleCentered, Spread }

/** 一次测量下书的摆放：右页（= 合着时的封面）的左上角、宽高与左页宽度；双页或居中单页整本居中。 */
internal class ShelfBookFrame(
    val gutterX: Dp,
    val top: Dp,
    val pageWidth: Dp,
    val pageHeight: Dp,
    val spread: Boolean,
    val layoutMode: ShelfBookLayoutMode = if (spread) ShelfBookLayoutMode.Spread else ShelfBookLayoutMode.SinglePeek,
    val leftPageWidth: Dp = if (spread) pageWidth else BOOK_LEFT_PEEK,
)

/** 随单页宽度分档的响应式书卷排版尺度，确保超紧凑平板竖屏、标准手机、大平板各自精致协调。 */
private class ShelfBookMetrics(
    val gutterPad: Dp,
    val outerPad: Dp,
    val topPad: Dp,
    val bottomPad: Dp,
    val coverW: Dp,
    val coverH: Dp,
    val footerHeight: Dp,
    val tocRowMax: Dp,
    val manageRowMax: Dp,
    val ribbonInset: Dp,
    val ribbonWidth: Dp,
    val ribbonLength: Dp,
)

private fun shelfBookMetrics(pageWidth: Dp): ShelfBookMetrics = when {
    pageWidth < 270.dp -> ShelfBookMetrics(
        gutterPad = 14.dp,
        outerPad = 10.dp,
        topPad = 12.dp,
        bottomPad = 10.dp,
        coverW = 48.dp,
        coverH = 64.dp,
        footerHeight = 40.dp,
        tocRowMax = 38.dp,
        manageRowMax = 42.dp,
        ribbonInset = 3.dp,
        ribbonWidth = 7.dp,
        ribbonLength = 28.dp,
    )
    pageWidth < 320.dp -> ShelfBookMetrics(
        gutterPad = if (pageWidth < 290.dp) 17.dp else 20.dp,
        outerPad = 12.dp,
        topPad = 13.dp,
        bottomPad = 11.dp,
        coverW = 54.dp,
        coverH = 72.dp,
        footerHeight = 42.dp,
        tocRowMax = 40.dp,
        manageRowMax = 44.dp,
        ribbonInset = 4.dp,
        ribbonWidth = 8.dp,
        ribbonLength = 31.dp,
    )
    pageWidth < 385.dp -> ShelfBookMetrics(
        gutterPad = 24.dp,
        outerPad = 15.dp,
        topPad = 15.dp,
        bottomPad = 13.dp,
        coverW = 60.dp,
        coverH = 80.dp,
        footerHeight = 46.dp,
        tocRowMax = 44.dp,
        manageRowMax = 48.dp,
        ribbonInset = 4.dp,
        ribbonWidth = 8.dp,
        ribbonLength = 33.dp,
    )
    else -> ShelfBookMetrics(
        gutterPad = 28.dp,
        outerPad = 20.dp,
        topPad = 18.dp,
        bottomPad = 16.dp,
        coverW = 70.dp,
        coverH = 93.dp,
        footerHeight = 48.dp,
        tocRowMax = 48.dp,
        manageRowMax = 54.dp,
        ribbonInset = 5.dp,
        ribbonWidth = 9.dp,
        ribbonLength = 36.dp,
    )
}

/**
 * 书摊开时摆在哪：
 * - 平板设备（[tablet] = true，横屏或竖屏）按极高优先级直接居中摊开完整双页（每页至少 [BOOK_TABLET_SPREAD_MIN_PAGE_WIDTH]），
 *   仅在极窄分屏下兜底单页；非平板设备在两页每页至少 [BOOK_SPREAD_MIN_PAGE_WIDTH] 时同样居中摊开完整双页；
 * - 放不下双页但左侧余量明显宽于窄书边时（特殊方窄比例）采用居中单页，左侧收束为 `22dp` 精致窄书边，绝不裁断半截封面；
 * - 常规手机竖屏右页尽量占满屏宽，左页露出靠书脊的一窄条（`22dp`）。
 */
internal fun shelfBookFrame(
    width: Dp,
    height: Dp,
    insetTop: Dp,
    insetBottom: Dp,
    tablet: Boolean = false,
): ShelfBookFrame {
    val spacious = width >= BOOK_SPACIOUS_WIDTH && height >= BOOK_SPACIOUS_HEIGHT
    val sideMargin = if (spacious) BOOK_SPACIOUS_SIDE_MARGIN else BOOK_SIDE_MARGIN
    val vMargin = if (spacious) BOOK_SPACIOUS_V_MARGIN else BOOK_V_MARGIN
    val availableHeight = (height - insetTop - insetBottom - vMargin * 2).coerceAtLeast(BOOK_MIN_HEIGHT)
    // 按 2:3 放不下一页舒服的宽度（手机横屏、分屏这类很矮的窗口）时，书页变矮胖一些（最多到正方形），不缩成一窄条
    val preferredWidth = maxOf(availableHeight / BOOK_PAGE_ASPECT, minOf(availableHeight, BOOK_COMFORT_PAGE_WIDTH))
    val spreadWidth = minOf(preferredWidth, (width - sideMargin * 2) / 2, BOOK_MAX_PAGE_WIDTH)
    val singleWidth = minOf(preferredWidth, width - BOOK_LEFT_PEEK - sideMargin, BOOK_MAX_PAGE_WIDTH)
    val minSpreadWidth = if (tablet) BOOK_TABLET_SPREAD_MIN_PAGE_WIDTH else BOOK_SPREAD_MIN_PAGE_WIDTH
    val spread = spreadWidth >= singleWidth || spreadWidth >= minSpreadWidth
    val pageWidth: Dp
    val leftPageWidth: Dp
    val layoutMode: ShelfBookLayoutMode
    if (spread) {
        layoutMode = ShelfBookLayoutMode.Spread
        pageWidth = spreadWidth
        leftPageWidth = spreadWidth
    } else {
        pageWidth = singleWidth
        leftPageWidth = BOOK_LEFT_PEEK
        val leftRoom = width - sideMargin - pageWidth
        layoutMode = if (leftRoom > BOOK_LEFT_PEEK + BOOK_CENTER_TRIGGER_SLACK) {
            ShelfBookLayoutMode.SingleCentered
        } else {
            ShelfBookLayoutMode.SinglePeek
        }
    }
    val pageHeight = minOf(pageWidth * BOOK_PAGE_ASPECT, availableHeight)
    val gutterX = when (layoutMode) {
        ShelfBookLayoutMode.Spread -> width / 2
        ShelfBookLayoutMode.SingleCentered ->
            ((width - (leftPageWidth + pageWidth)) / 2 + leftPageWidth).coerceAtLeast(leftPageWidth)
        ShelfBookLayoutMode.SinglePeek -> (width - sideMargin - pageWidth).coerceAtLeast(BOOK_LEFT_PEEK)
    }
    val top = insetTop + vMargin + (availableHeight - pageHeight) / 2
    return ShelfBookFrame(gutterX, top, pageWidth, pageHeight, spread, layoutMode, leftPageWidth)
}

@Composable
private fun ShelfBookScene(
    game: ScanGame,
    ui: GameActionsUi,
    motion: ShelfBookMotion,
    pages: ShelfBookPages,
    host: ShelfBookHost,
    stage: ShelfBookStage,
    tabMotion: ShelfBookTabMotion,
    quickActions: GameQuickActionsController,
    spreadLeafSource: ShelfBookTab,
    onLeafSource: (ShelfBookTab) -> Unit,
) {
    val tint = stage.tint.takeIf { it.isSpecified } ?: game.engine.coverColor()
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    val keyboard = WindowInsets.ime
    val tablet = isTabletScreen()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val insetTop = insets.calculateTopPadding()
        val insetBottom = insets.calculateBottomPadding()
        val targetFrame = remember(maxWidth, maxHeight, insetTop, insetBottom, tablet) {
            shelfBookFrame(maxWidth, maxHeight, insetTop, insetBottom, tablet)
        }
        val morph = rememberShelfBookLayoutMorph(targetFrame, maxWidth, maxHeight, tablet) {
            motion.flight.value >= 0.99f && motion.cover.value >= 0.5f
        }
        val frame = morph.visualFrame(maxWidth, maxHeight)
        val pageLayout = morph.rotationPageLayout(targetFrame, maxWidth, maxHeight, tablet)
        val layoutFrame = pageLayout ?: targetFrame
        val metrics = remember(layoutFrame.pageWidth) { shelfBookMetrics(layoutFrame.pageWidth) }
        val rightPaperSize = pageLayout?.let { with(density) { shelfBookPaperSize(it, left = false) } }
        val leftPaperSize = pageLayout?.let { with(density) { shelfBookPaperSize(it, left = true) } }
        val screenHeight = maxHeight
        val latestDragSpan by rememberUpdatedState(with(density) { frame.pageWidth.toPx() } * BOOK_DRAG_SPAN)
        val glowCenter = with(density) {
            val centerX = when (frame.layoutMode) {
                ShelfBookLayoutMode.Spread -> frame.gutterX
                ShelfBookLayoutMode.SingleCentered ->
                    frame.gutterX + (frame.pageWidth - frame.leftPageWidth) / 2
                ShelfBookLayoutMode.SinglePeek -> frame.gutterX + frame.pageWidth / 2
            }
            Offset(centerX.toPx(), (frame.top + frame.pageHeight / 2).toPx())
        }
        val activeLeafTab = if (morph.currentMode == ShelfBookLayoutMode.Spread) spreadLeafSource else tabMotion.selected
        val glowFilter = remember(tint) { ColorFilter.tint(tint.copy(alpha = 1f), BlendMode.Modulate) }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val glowBrush = Brush.radialGradient(
                        listOf(Color.White, Color.Transparent),
                        center = glowCenter,
                        radius = maxOf(size.width, size.height) * 0.6f,
                    )
                    onDrawBehind {
                        val a = motion.scrimAlpha()
                        if (a > 0f) drawRect(ShelfBookShade.copy(alpha = a))
                        // 身后一团这本封面颜色的柔光：没有边，铺到屏幕外
                        val glow = motion.scrim() * ShelfBookGlowAlpha
                        if (glow > 0.003f) {
                            drawRect(glowBrush, alpha = glow, colorFilter = glowFilter)
                        }
                    }
                }
                // 点书外的空白与返回键一致：先翻回最上面一页，才合整本书。
                .pointerInput(motion, pages) {
                    detectTapGestures {
                        pages.dismissOrClose { motion.close() }
                    }
                }
                // 书上任何地方往右拖：翻到了后面的页时那一页跟着手指翻回来，在目录页时封面跟着手指合回来
                .pointerInput(motion, pages) {
                    val tracker = VelocityTracker()
                    var active = false
                    var turning = false
                    var dragSpan = 0f
                    fun cancelDrag() {
                        if (!active) return
                        active = false
                        if (turning) pages.dragCancel() else motion.dragCancel()
                    }
                    try {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                active = true
                                turning = pages.turned
                                dragSpan = latestDragSpan
                                if (turning) pages.dragStart()
                            },
                            onDragEnd = {
                                active = false
                                val velocity = tracker.calculateVelocity().x
                                if (turning) pages.dragEnd(velocity, dragSpan) else motion.dragEnd(velocity, dragSpan)
                            },
                            onDragCancel = ::cancelDrag,
                        ) { change, amount ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            if (turning) pages.dragBy(amount, dragSpan) else motion.dragBy(amount, dragSpan)
                        }
                    } finally {
                        cancelDrag()
                    }
                },
        ) {
            // 键盘弹起（书页里的输入框）：双页与书体整本一起往上让，最多让到状态栏下
            Box(
                Modifier
                    .fillMaxSize()
                    .nestedPanelContentDim { motion.hostWindow }
                    .offset {
                        val covered = (frame.top + frame.pageHeight + BOOK_V_MARGIN).toPx() - (screenHeight.toPx() - keyboard.getBottom(this))
                        val room = (frame.top - insets.calculateTopPadding() - BOOK_V_MARGIN).toPx()
                        IntOffset(0, -covered.coerceIn(0f, room.coerceAtLeast(0f)).toInt())
                    },
            ) {
                Box(
                    Modifier
                        .offset(x = frame.gutterX, y = frame.top)
                        .size(frame.pageWidth, frame.pageHeight)
                        .shelfBookFlight(motion),
                ) {
                    ShelfBookBody(
                        game = game,
                        ui = ui,
                        motion = motion,
                        pages = pages,
                        host = host,
                        stage = stage,
                        tabMotion = tabMotion,
                        quickActions = quickActions,
                        tint = tint,
                        frame = frame,
                        morph = morph,
                        metrics = metrics,
                        paperSize = rightPaperSize,
                        paperMode = pageLayout?.layoutMode,
                        activeLeafTab = activeLeafTab,
                        onLeafSource = onLeafSource,
                    )
                }
                val leftWidth = frame.leftPageWidth
                val leftCut = minOf(shelfBookLeftPageCut(frame, motion.origin, density), (leftWidth * 0.25f).coerceAtLeast(1.5.dp))
                val leftShape = remember(leftCut) {
                    AbsoluteCutCornerShape(topLeft = leftCut, topRight = 0.dp, bottomRight = 0.dp, bottomLeft = leftCut)
                }
                if (morph.showsLeftMode(ShelfBookLayoutMode.Spread)) {
                    ShelfBookSpreadLeftPage(
                        game = game,
                        stage = stage,
                        motion = motion,
                        pages = pages,
                        quickActions = quickActions,
                        metrics = metrics,
                        shape = leftShape,
                        paperSize = leftPaperSize,
                        enabled = !pages.turned && morph.isInteractiveLeftMode(ShelfBookLayoutMode.Spread),
                        onOpenLeaf = { onLeafSource(ShelfBookTab.Manage) },
                        modifier = Modifier
                            .offset(x = frame.gutterX - leftWidth, y = frame.top)
                            .size(leftWidth, frame.pageHeight)
                            .shelfBookMorphLeftSlot(morph, ShelfBookLayoutMode.Spread, motion, pages),
                    )
                }
            }
            // 书是盖住整屏的窗口：书里的选项（包括翻到书页上的弹窗）发出的提示画在书上面，不被书挡住
            NoticeHost(Modifier.matchParentSize())
        }
    }
}

private fun Density.shelfBookPaperSize(frame: ShelfBookFrame, left: Boolean): IntSize {
    val width = if (left) {
        frame.pageWidth.roundToPx() - (BOOK_LIP + BOOK_LEFT_STACK).roundToPx()
    } else {
        frame.pageWidth.roundToPx() - BOOK_LIP.roundToPx() - BOOK_STACK_RIGHT.roundToPx()
    }
    val bottom = if (left) (BOOK_LIP + BOOK_STACK_BOTTOM).roundToPx() else BOOK_LIP.roundToPx() + BOOK_STACK_BOTTOM.roundToPx()
    val height = frame.pageHeight.roundToPx() - BOOK_LIP.roundToPx() - bottom
    return IntSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
}

private fun shelfBookLeftPageCut(frame: ShelfBookFrame, origin: ShelfBookOrigin?, density: Density): Dp {
    val heroScale = origin?.let { with(density) { frame.pageHeight.toPx() } / it.face.height } ?: 1f
    val heroWidth = origin?.let { with(density) { it.face.width.toDp() } } ?: frame.pageWidth
    val chamfer = shelfChamfer(heroWidth) * heroScale
    return (chamfer - BOOK_LIP * 2 + BOOK_PAGE_CUT_SLACK).coerceIn(chamfer * 0.35f, chamfer)
}

/**
 * 精装书：书脊（起飞时与书架上一样露在左侧）、布纹后封板（露出一圈带斜切倒角与微压印框的厚板边）、
 * 物理分离的立体书芯台面、活动书页、顶底堵头布与中缝装订沟槽、织物丝带书签，最上面是绕书脊翻开的硬壳封面。
 */
@Composable
private fun ShelfBookBody(
    game: ScanGame,
    ui: GameActionsUi,
    motion: ShelfBookMotion,
    pages: ShelfBookPages,
    host: ShelfBookHost,
    stage: ShelfBookStage,
    tabMotion: ShelfBookTabMotion,
    quickActions: GameQuickActionsController,
    tint: Color,
    frame: ShelfBookFrame,
    morph: ShelfBookLayoutMorph,
    metrics: ShelfBookMetrics,
    paperSize: IntSize?,
    paperMode: ShelfBookLayoutMode?,
    activeLeafTab: ShelfBookTab,
    onLeafSource: (ShelfBookTab) -> Unit,
) {
    val density = LocalDensity.current
    val origin = motion.origin
    // 书架上那一盒的尺寸换算到书上：倒角、丝带、▶ 胶囊、书脊按书页与那一盒的横 / 纵比例放大（书页比封面高挑，
    // 横纵比例不同；起飞时整本书反过来按同样的比例压回那一盒的样子），起飞那一刻与书架上的一模一样
    val heroScale = origin?.let { with(density) { frame.pageHeight.toPx() } / it.face.height } ?: 1f
    val heroScaleX = origin?.let { with(density) { frame.pageWidth.toPx() } / it.face.width } ?: 1f
    val heroWidth = origin?.let { with(density) { it.face.width.toDp() } } ?: frame.pageWidth
    val heroHeight = origin?.let { with(density) { it.face.height.toDp() } } ?: frame.pageHeight
    val chamfer = shelfChamfer(heroWidth) * heroScale
    val boardShape = remember(chamfer) { AbsoluteCutCornerShape(topLeft = 0.dp, topRight = chamfer, bottomRight = chamfer, bottomLeft = 0.dp) }
    // 书页跟着封板一起切角，但切得小一点（刚好不越过封板的倒角，倒角处的板边露出精装飘口）
    val pageCut = (chamfer - BOOK_LIP * 2 + BOOK_PAGE_CUT_SLACK).coerceIn(chamfer * 0.35f, chamfer)
    val pageShape = remember(pageCut) { AbsoluteCutCornerShape(topLeft = 0.dp, topRight = pageCut, bottomRight = pageCut, bottomLeft = 0.dp) }
    val leftPageShape = remember(pageCut) { AbsoluteCutCornerShape(topLeft = pageCut, topRight = 0.dp, bottomRight = 0.dp, bottomLeft = pageCut) }
    val board = lerp(tint, ShelfBookShade, BOOK_BOARD_DARKEN)
    val engineColor = game.engine.coverColor()
    val small by rememberCoverBitmap(game.coverUri)
    val cloth = rememberShelfBookClothGrain()
    val accent = MaterialTheme.colorScheme.primary
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()

    if (origin != null && motion.enhanced && origin.spineDepth > 0) {
        val heroSpineDepth = with(density) { origin.spineDepth.toDp() }
        val spineDepth = with(density) { (origin.spineDepth * heroScaleX).toDp() }
        val wash = rememberShelfSpineWash(small)
        val spineTitle = if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
        Box(
            Modifier
                .offset(x = -spineDepth)
                .width(spineDepth)
                .fillMaxHeight()
                .shelfBookSpineFace(motion)
                .background(engineColor),
        ) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(heroSpineDepth, heroHeight)
                    .graphicsLayer {
                        scaleX = heroScaleX
                        scaleY = heroScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                ShelfSpineFaceContent(
                    title = spineTitle,
                    coverHeight = heroHeight,
                    wash = wash,
                    engineColor = engineColor,
                    lastPlayed = isShelfLastPlayed(game),
                )
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = stage.spineShade }.background(ShelfFaceShade))
            }
        }
    }
    // 布纹硬壳后封板：书飞起来时投影与贴身轮廓影跟着出现，旋转形变时同步向观者微抬
    val elevation = with(density) { BOOK_ELEVATION.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .shelfBookFace(motion, morph)
            .drawBehind {
                val f = motion.flight.value.coerceIn(0f, 1f)
                if (f > 0.05f) {
                    val outline = boardShape.createOutline(size, layoutDirection, this)
                    val dx = 2.dp.toPx() * f
                    val dy = 3.5.dp.toPx() * f
                    withTransform({ translate(left = dx, top = dy) }) {
                        drawOutline(outline, ShelfBookShade.copy(alpha = 0.24f * f))
                    }
                }
            }
            .graphicsLayer {
                shadowElevation = elevation * motion.flight.value.coerceIn(0f, 1f)
                shape = boardShape
                clip = false
            }
            .background(board, boardShape)
            .shelfBookHardcoverBoard(boardShape, cloth, isLeftBoard = false, cut = { chamfer }),
    )
    ShelfBookPageBlock(
        game = game,
        ui = ui,
        motion = motion,
        pages = pages,
        host = host,
        stage = stage,
        tint = tint,
        cloth = cloth,
        tabMotion = tabMotion,
        quickActions = quickActions,
        pageShape = pageShape,
        pageCut = pageCut,
        frame = frame,
        morph = morph,
        metrics = metrics,
        paperSize = paperSize,
        paperMode = paperMode,
        activeLeafTab = activeLeafTab,
        onLeafSource = onLeafSource,
    )
    ShelfBookBindingOverlay(motion, morph, tint, accent)
    ShelfBookCover(
        game = game,
        motion = motion,
        pages = pages,
        stage = stage,
        small = small,
        shape = boardShape,
        leftPageShape = leftPageShape,
        cloth = cloth,
        tint = tint,
        board = board,
        chamfer = chamfer,
        pageCut = pageCut,
        pageWidth = frame.pageWidth,
        leftBoardWidth = frame.leftPageWidth,
        heroWidth = heroWidth,
        heroHeight = heroHeight,
        heroScaleX = heroScaleX,
        heroScale = heroScale,
        morph = morph,
    )
}

/**
 * 书页：比封板小一圈的书芯台面，顶层是活动书页。打开二级弹窗时当前页绕书脊翻走，底下厚实的书芯切口保持不动；
 * 每一页都是当前外观主题的面板材质。
 */
@Composable
private fun ShelfBookPageBlock(
    game: ScanGame,
    ui: GameActionsUi,
    motion: ShelfBookMotion,
    pages: ShelfBookPages,
    host: ShelfBookHost,
    stage: ShelfBookStage,
    tint: Color,
    cloth: ImageBitmap,
    tabMotion: ShelfBookTabMotion,
    quickActions: GameQuickActionsController,
    pageShape: AbsoluteCutCornerShape,
    pageCut: Dp,
    frame: ShelfBookFrame,
    morph: ShelfBookLayoutMorph,
    metrics: ShelfBookMetrics,
    paperSize: IntSize?,
    paperMode: ShelfBookLayoutMode?,
    activeLeafTab: ShelfBookTab,
    onLeafSource: (ShelfBookTab) -> Unit,
) {
    val ribbon = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, com.tyranor.next.theme.ShelfRibbonDeep, 0.3f)
    val context = LocalContext.current.applicationContext
    val statsFlow = remember(context, game.uri) { GamePlaytimeRepository.observe(context, game.uri) }
    val stats: GamePlaytimeStats? by statsFlow.collectAsStateWithLifecycle(initialValue = null)
    val clippedLeft = morph.isLeftBoardClipped
    val leftPaperVisible = morph.leftPaperAlpha > 0.5f
    val leftStack = BOOK_LEFT_STACK * morph.leftPaperAlpha
    val turnedClipWidth = if (clippedLeft) {
        (frame.leftPageWidth - BOOK_LIP - leftStack)
            .coerceAtLeast(BOOK_TURNED_CLIP_MIN_WIDTH)
    } else {
        null
    }
    val turnedCorner = when {
        turnedClipWidth == null -> 0.dp
        leftPaperVisible -> (pageCut * 0.65f).coerceIn(1.5.dp, (turnedClipWidth * 0.22f).coerceAtLeast(1.5.dp))
        else -> BOOK_PEEK_CORNER_INNER
    }
    Box(
        Modifier
            .fillMaxSize()
            .shelfBookFace(motion)
            .padding(top = BOOK_LIP, bottom = BOOK_LIP, end = BOOK_LIP),
    ) {
        // 物理书芯台面：独立于活动翻页，右缘与下缘呈现厚实多层的切口边（形变途中与后封板同角微抬）
        ShelfBookPageBlockEdges(pageShape, modifier = Modifier.shelfBookMorphCradle(morph))
        Box(
            Modifier
                .fillMaxSize()
                .padding(end = BOOK_STACK_RIGHT, bottom = BOOK_STACK_BOTTOM),
        ) {
            // 被调用方直接撤掉、正被翻回来的那一页盖住的页：只剩它最后的样子
            pages.ghost?.let { ghost -> Box(Modifier.fillMaxSize().shelfBookMorphCradle(morph).shelfBookGhost(ghost)) }
            val leaves = pages.leaves
            for (index in leaves.indices.reversed()) {
                val leaf = leaves[index]
                val level = index + 1
                key(leaf) {
                    ShelfBookSheet(
                        shape = pageShape,
                        turn = { minOf(leaf.turn.value, motion.cover.value) },
                        under = { minOf(pages.coverOf(leaf), motion.cover.value) },
                        blocked = { pages.leaves.lastOrNull() !== leaf || leaf.leaving },
                        morph = morph,
                        modifier = Modifier.shelfBookLeafSnapshot(leaf),
                        turnedClipWidth = turnedClipWidth,
                        turnedCorner = turnedCorner,
                    ) {
                        ShelfBookLeafPage(leaf, level, pages, activeLeafTab, metrics, paperSize)
                    }
                }
                // 换页：撤掉的那一页叠在它翻开后露出的这一页上面，往前翻过去
                pages.departure?.takeIf { it.above === leaf }?.let { departure ->
                    key(departure) {
                        ShelfBookSheet(
                            shape = pageShape,
                            turn = { minOf(departure.leaf.turn.value, motion.cover.value) },
                            under = { motion.cover.value },
                            blocked = { true },
                            morph = morph,
                            turnedClipWidth = turnedClipWidth,
                            turnedCorner = turnedCorner,
                        ) {
                            Box(Modifier.fillMaxSize().shelfBookGhost(departure.leaf))
                        }
                    }
                }
            }
            ShelfBookSheet(
                shape = pageShape,
                turn = { minOf(pages.toc.value, motion.cover.value) },
                under = { motion.cover.value },
                blocked = { pages.turned },
                morph = morph,
                turnedClipWidth = turnedClipWidth,
                turnedCorner = turnedCorner,
                backContent = if (morph.currentMode == ShelfBookLayoutMode.Spread) {
                    {
                        Box(Modifier.fillMaxSize().shelfBookFitPaper(paperSize, gutterAtRight = true)) {
                            ShelfBookSpreadVerso(game = game, stage = stage, stats = stats, tint = tint, metrics = metrics)
                        }
                    }
                } else {
                    null
                },
            ) {
                ShelfBookPage(
                    game = game,
                    ui = ui,
                    motion = motion,
                    pages = pages,
                    host = host,
                    stage = stage,
                    cloth = cloth,
                    tabMotion = tabMotion,
                    quickActions = quickActions,
                    stats = stats,
                    morph = morph,
                    metrics = metrics,
                    paperSize = paperSize,
                    paperMode = paperMode,
                    onLeafSource = onLeafSource,
                )
                if (isShelfLastPlayed(game)) {
                    ShelfBookRibbon(
                        color = ribbon,
                        modifier = Modifier
                            .padding(start = metrics.ribbonInset)
                            .size(metrics.ribbonWidth, metrics.ribbonLength),
                    )
                }
            }
        }
    }
}

/**
 * 一页纸：当前外观主题的面板材质（默认白卡 / 深色卡，复古玻璃深色玻璃 + 发丝描边 + 光学，白雾玻璃底图 + 白纱），
 * 靠书脊一侧压一道装订阴影，右缘与下缘画出叠起来的书页。[turn] 是这一页被翻过去的程度（翻过一半后只剩背面），
 * [under] 是压在它上面那一页（或封面）被掀开的程度（没掀开时这一页是暗的）；[blocked] 时不接触摸。
 * 正面纸页始终保持同一组合槽位（旋转跨模式不重建），背面按左侧实时宽平滑裁切并印上 [backContent]。
 */
@Composable
private fun ShelfBookSheet(
    shape: AbsoluteCutCornerShape,
    turn: () -> Float,
    under: () -> Float,
    blocked: () -> Boolean,
    morph: ShelfBookLayoutMorph,
    modifier: Modifier = Modifier,
    turnedClipWidth: Dp? = null,
    turnedCorner: Dp = 0.dp,
    backContent: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shade = ShelfBookShade
    Box(
        Modifier
            .fillMaxSize()
            // 被上面那一页盖住时整页不画：玻璃外观的书页是半透明的，叠在下面的页会透出来。上面那一页快要合平（翻回的最后
            // 一小段）时这一页就跟着淡出，不在弹簧收尾的那一两百毫秒里从玻璃底下透出一层字
            .graphicsLayer { alpha = (under() / BOOK_UNDER_FADE).coerceIn(0f, 1f) }
            .shelfBookLeafTurn(turn, morph)
            .then(modifier)
            .shelfBookBlockTouches(blocked)
            // 书页本身吃掉轻点（点空白处不合书、不翻回），拖动照样交给外层
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(Modifier.fillMaxSize().shelfBookLeafSide(turn, back = false)) {
            ShelfBookPaper(
                shape = shape,
                gutterAtStart = true,
                modifier = Modifier
                    .fillMaxSize()
                    .shelfBookUnderShade(under, shade)
                    .shelfBookLeafShade(turn, shade),
                content = content,
            )
        }
        val backClipModifier = if (turnedClipWidth == null) {
            Modifier
        } else {
            val backClipPath = remember { Path() }
            Modifier.drawWithContent {
                shelfBookLeafBackClipPath(
                    size = size,
                    density = density,
                    fullShape = shape,
                    baseWidth = turnedClipWidth,
                    baseCorner = turnedCorner,
                    turnProgress = turn(),
                    outPath = backClipPath,
                )
                clipPath(backClipPath) { this@drawWithContent.drawContent() }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .shelfBookLeafSide(turn, back = true)
                .then(backClipModifier),
        ) {
            ShelfBookPaper(
                shape = shape,
                gutterAtStart = true,
                modifier = Modifier
                    .fillMaxSize()
                    .shelfBookUnderShade(under, shade)
                    .shelfBookLeafShade(turn, shade)
                    .shelfBookMorphSpineShadow(morph, shade, gutterAtStart = true),
            ) {
                if (backContent != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = -1f },
                        content = backContent,
                    )
                }
            }
        }
    }
}

/** 居中窄边 / 形变途中翻页背面的实时透视补偿裁剪路径：保留书脊左侧 `[0, effectiveW]` 并平滑恢复右上 / 右下倒角。 */
private fun shelfBookLeafBackClipPath(
    size: Size,
    density: Density,
    fullShape: AbsoluteCutCornerShape,
    baseWidth: Dp,
    baseCorner: Dp,
    turnProgress: Float,
    outPath: Path,
) {
    val basePx = with(density) { baseWidth.toPx() }.coerceIn(1f, size.width.coerceAtLeast(1f))
    val rotY = BOOK_COVER_TURN * turnProgress.coerceIn(0f, 1f)
    val camPx = SHELF_FACE_CAMERA * BOOK_CAMERA_DISTANCE_PX_UNIT * density.density
    val effectiveW = shelfBookEffectiveClippedWidthPx(basePx, size.width, rotY, camPx)
    val ratio = ((effectiveW - basePx) / (size.width - basePx).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val fullCut = fullShape.cut(size, density)
    val startCut = with(density) { baseCorner.toPx() }
    val cPx = (startCut + (fullCut - startCut) * ratio).coerceAtMost(minOf(effectiveW * 0.25f, size.height / 3f))
    outPath.rewind()
    outPath.moveTo(0f, 0f)
    outPath.lineTo((effectiveW - cPx).coerceAtLeast(0f), 0f)
    outPath.lineTo(effectiveW, cPx)
    outPath.lineTo(effectiveW, (size.height - cPx).coerceAtLeast(cPx))
    outPath.lineTo((effectiveW - cPx).coerceAtLeast(0f), size.height)
    outPath.lineTo(0f, size.height)
    outPath.close()
}

/**
 * 一页纸的材质：四套外观主题各自的温润纸底（默认浅色暖象牙轻型纸、深色炭墨暖灰纸、复古玻璃烟熏磨砂玻璃纸、
 * 白雾玻璃模糊底图 + 温润半透明暖米宣纸纱），再叠上细腻纸纹（柔和纸浆云纹 + 短纤维丝缕 + 双极微粒）、
 * 书脊装订沟的弯曲明暗与拱起柔光、斜向台灯受光、外缘与底边微暖自然沉降，以及跟着倒角斜过去的叠页细边。
 * [gutterAtStart] 为 true 时书脊在左（右页），否则在右（左页）。
 */
@Composable
private fun ShelfBookPaper(
    shape: AbsoluteCutCornerShape,
    gutterAtStart: Boolean,
    modifier: Modifier = Modifier,
    /** 书页（有装订线的弯曲与叠起来的细边）；false 时只是一张平纸（题签）。 */
    page: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val milk = AppThemeColors.isMilkGlass
    val paperSurface = ShelfBookPaperSurface
    val surface = if (milk) Color.Transparent else paperSurface
    val darkPaper = AppThemeColors.isDark || AppThemeColors.isGlass
    val edge = ShelfBookPageEdge
    val edgeHighlight = ShelfBookStackHighlight
    val edgeTint = ShelfBookPaperEdgeTint
    val highlight = ShelfBookPaperHighlight
    val shade = ShelfBookShade
    Box(modifier.clip(shape).background(surface)) {
        if (milk) MilkGlassPanelBackground(Modifier.fillMaxSize(), veilColor = paperSurface)
        Box(
            Modifier
                .fillMaxSize()
                .shelfGlassFinish(
                    shape = shape,
                    corner = BOOK_LIP,
                    sheen = ShelfBookGlassSheen,
                    specular = ShelfBookGlassSpecular,
                    base = ShelfBookGlassBase,
                )
                .then(if (page) Modifier.shelfBookPageRim(shape, gutterAtStart) else Modifier.glassBorder(shape))
                .drawWithCache {
                    val grainBrush = if (darkPaper) shelfPaperGrainDarkBrush else shelfPaperGrainLightBrush
                    val curl = BOOK_GUTTER_CURL.toPx()
                    val gutterX = if (gutterAtStart) 0f else size.width
                    val outerX = size.width - gutterX
                    val towardPage = if (gutterAtStart) 1f else -1f
                    // 书页弯进装订线：贴着书脊深沉，向外平滑收浅，三分之二处一道温润拱起高光，然后舒展变平
                    val bend = Brush.horizontalGradient(
                        0f to shade.copy(alpha = if (darkPaper) 0.44f else 0.30f),
                        0.12f to shade.copy(alpha = if (darkPaper) 0.24f else 0.14f),
                        0.40f to shade.copy(alpha = if (darkPaper) 0.08f else 0.045f),
                        1f to Color.Transparent,
                        startX = gutterX,
                        endX = gutterX + towardPage * curl,
                    )
                    val bulgeCenter = gutterX + towardPage * curl * 0.64f
                    val bulge = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to highlight,
                        1f to Color.Transparent,
                        startX = bulgeCenter - curl * 0.34f,
                        endX = bulgeCenter + curl * 0.34f,
                    )
                    // 纸面自然台灯受光：靠书脊上侧柔和偏暖，向外下角自然微沉（消除平白荧光感）
                    val ambientLight = Brush.linearGradient(
                        0f to highlight.copy(alpha = highlight.alpha * 0.55f),
                        0.45f to Color.Transparent,
                        1f to edgeTint.copy(alpha = edgeTint.alpha * 0.95f),
                        start = Offset(gutterX, 0f),
                        end = Offset(outerX, size.height),
                    )
                    // 纸页外缘与底边极轻的微暖氧化/厚度收边
                    val edgeDepth = BOOK_EDGE_VIGNETTE.toPx()
                    val outerEdgeShade = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to edgeTint,
                        startX = outerX - towardPage * edgeDepth,
                        endX = outerX,
                    )
                    val bottomEdgeShade = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to edgeTint.copy(alpha = edgeTint.alpha * 0.85f),
                        startY = (size.height - edgeDepth).coerceAtLeast(0f),
                        endY = size.height,
                    )
                    val cut = shape.cut(size, this)
                    val outerRim = shelfBookPageRim(size, cut, gutterAtStart)
                    val highlightStroke = Stroke(width = 0.9.dp.toPx())
                    val trimLine = shelfBookStackEdges(size, cut, gutterAtStart, 1.dp.toPx(), lines = 1).firstOrNull()
                    val trimStroke = Stroke(width = 0.5.dp.toPx())
                    onDrawWithContent {
                        // 纸面底光与微暖沉降垫在纤维纸纹下，纸纹与光影自然融合
                        if (page) {
                            drawRect(ambientLight)
                            drawRect(outerEdgeShade)
                            drawRect(bottomEdgeShade)
                        }
                        drawRect(grainBrush, alpha = if (darkPaper) 0.72f else 0.88f)
                        if (page) {
                            drawRect(bend)
                            drawRect(bulge)
                            drawPath(outerRim, edgeHighlight, style = highlightStroke)
                            if (trimLine != null) drawPath(trimLine, edge.copy(alpha = edge.alpha * 0.65f), style = trimStroke)
                        }
                        drawContent()
                    }
                },
            content = content,
        )
    }
}

/**
 * 玻璃外观下书页的发丝描边：只描上缘、外缘与下缘，装订线那一侧不描——书页在那里钉进书脊，
 * 描一圈边就成了一张放在书上的卡片。默认外观原样返回（与 [glassBorder] 一致）。
 */
private fun Modifier.shelfBookPageRim(shape: AbsoluteCutCornerShape, gutterAtStart: Boolean): Modifier {
    if (!AppThemeColors.isGlass) return this
    val color = GlassBorder
    return drawWithCache {
        val rim = shelfBookPageRim(size, shape.cut(size, this), gutterAtStart)
        val stroke = Stroke(width = 0.5.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawPath(rim, color, style = stroke)
        }
    }
}

/** 书页轮廓去掉装订线那一边：从书脊顶端起，沿上缘、倒角、外缘、倒角、下缘回到书脊底端。 */
internal fun shelfBookPageRim(size: Size, cut: Float, gutterAtStart: Boolean): Path =
    Path().apply { fillShelfBookPageRim(size, cut, gutterAtStart, this) }

private fun fillShelfBookPageRim(size: Size, cut: Float, gutterAtStart: Boolean, outPath: Path) {
    val w = size.width
    val h = size.height
    outPath.rewind()
    if (gutterAtStart) {
        outPath.moveTo(0f, 0f)
        outPath.lineTo(w - cut, 0f)
        outPath.lineTo(w, cut)
        outPath.lineTo(w, h - cut)
        outPath.lineTo(w - cut, h)
        outPath.lineTo(0f, h)
    } else {
        outPath.moveTo(w, 0f)
        outPath.lineTo(cut, 0f)
        outPath.lineTo(0f, cut)
        outPath.lineTo(0f, h - cut)
        outPath.lineTo(cut, h)
        outPath.lineTo(w, h)
    }
}

/** 倒角形状在这个尺寸下的切边长度（像素）。 */
private fun AbsoluteCutCornerShape.cut(size: Size, density: Density): Float =
    maxOf(topStart.toPx(size, density), topEnd.toPx(size, density), bottomEnd.toPx(size, density), bottomStart.toPx(size, density))

/**
 * 外缘与下缘叠起来的书页细边：每一道都沿书页轮廓往里收一点，到了倒角处跟着斜过去（不是方角被切掉）。
 * [gutterAtStart] 为 true 时书脊在左、外缘在右。
 */
internal fun shelfBookStackEdges(
    size: Size,
    cut: Float,
    gutterAtStart: Boolean,
    step: Float,
    lines: Int = BOOK_EDGE_LINES,
): List<Path> {
    val w = size.width
    val h = size.height
    return (1..lines).map { i ->
        val d = step * i
        val k = 0.4142f * d // 往里收 d 时倒角那一段两端的移动量（tan 22.5° × d）
        Path().apply {
            if (gutterAtStart) {
                // 上缘倒角 → 外缘 → 下缘倒角 → 下缘
                moveTo(w - cut - d - k, 0f)
                lineTo(w - d, cut + k)
                lineTo(w - d, h - cut - k)
                lineTo(w - cut - k, h - d)
                lineTo(0f, h - d)
            } else {
                moveTo(cut + d + k, 0f)
                lineTo(d, cut + k)
                lineTo(d, h - cut - k)
                lineTo(cut + k, h - d)
                lineTo(w, h - d)
            }
        }
    }
}

/**
 * 纸纹：可无缝平铺的特种书页纤维纹理（只生成一次）。
 * 由三层叠加而成：低频柔和纸浆云纹（明暗自然起伏）+ 细密双极微粒（暖褐暗点与微暖亮点，消除冷灰噪点感）
 * + 极细短纤维丝缕（模拟轻型纸 / 道林纸的横向与微斜造纸纤维）。
 */
private val shelfPaperGrainLight: ImageBitmap by lazy { shelfPaperGrain(dark = false) }
private val shelfPaperGrainDark: ImageBitmap by lazy { shelfPaperGrain(dark = true) }
private val shelfPaperGrainLightBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(shelfPaperGrainLight, TileMode.Repeated, TileMode.Repeated))
}
private val shelfPaperGrainDarkBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(shelfPaperGrainDark, TileMode.Repeated, TileMode.Repeated))
}

private fun shelfPaperGrain(dark: Boolean): ImageBitmap {
    val size = BOOK_GRAIN_SIZE
    val random = java.util.Random(BOOK_GRAIN_SEED)
    // 低频纸浆起伏：8×8 网格做周期性双线性平滑，无缝平铺无接缝
    val coarse = 8
    val grid = FloatArray(coarse * coarse) { random.nextFloat() * 2f - 1f }
    val field = FloatArray(size * size)
    for (y in 0 until size) {
        val gy = y.toFloat() * coarse / size
        val y0 = gy.toInt() % coarse
        val y1 = (y0 + 1) % coarse
        val fy = gy - gy.toInt()
        val sy = fy * fy * (3f - 2f * fy)
        for (x in 0 until size) {
            val gx = x.toFloat() * coarse / size
            val x0 = gx.toInt() % coarse
            val x1 = (x0 + 1) % coarse
            val fx = gx - gx.toInt()
            val sx = fx * fx * (3f - 2f * fx)
            val top = grid[y0 * coarse + x0] * (1f - sx) + grid[y0 * coarse + x1] * sx
            val bot = grid[y1 * coarse + x0] * (1f - sx) + grid[y1 * coarse + x1] * sx
            val cloud = top * (1f - sy) + bot * sy
            // 双极细微纸粒：大多接近 0，少数略深或略浅
            val r1 = random.nextFloat()
            val r2 = random.nextFloat()
            val speck = (r1 * r1 * r1 - r2 * r2 * r2) * 0.9f
            field[y * size + x] = cloud * 0.42f + speck
        }
    }
    // 叠加细短造纸纤维（偏向横向微斜，周期平铺）
    repeat(BOOK_GRAIN_FIBERS) {
        val startX = random.nextInt(size)
        val startY = random.nextInt(size)
        val len = 3 + random.nextInt(5)
        val slope = if (random.nextFloat() < 0.35f) (if (random.nextBoolean()) 1 else -1) else 0
        val sign = if (random.nextFloat() < 0.65f) -1f else 1f
        val strength = (0.35f + random.nextFloat() * 0.45f) * sign
        for (step in 0 until len) {
            val px = (startX + step) % size
            val py = (startY + (step * slope) / 2 + size) % size
            field[py * size + px] = (field[py * size + px] + strength).coerceIn(-1.4f, 1.4f)
        }
    }
    // 暖色纸纹色值：浅色纸暗部偏暖褐(92,74,52)、亮部偏暖白(255,250,240)；深色纸暗部深褐黑、亮部暖米(236,226,210)
    val darkR = if (dark) 10 else 92
    val darkG = if (dark) 9 else 74
    val darkB = if (dark) 8 else 52
    val lightR = if (dark) 236 else 255
    val lightG = if (dark) 226 else 250
    val lightB = if (dark) 210 else 240
    val darkMaxA = if (dark) 22f else 18f
    val lightMaxA = if (dark) 14f else 13f
    val pixels = IntArray(size * size) { i ->
        val v = field[i]
        if (v < 0f) {
            val alpha = ((-v).coerceAtMost(1f) * darkMaxA).toInt().coerceIn(0, 255)
            (alpha shl 24) or (darkR shl 16) or (darkG shl 8) or darkB
        } else {
            val alpha = (v.coerceAtMost(1f) * lightMaxA).toInt().coerceIn(0, 255)
            (alpha shl 24) or (lightR shl 16) or (lightG shl 8) or lightB
        }
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * 翻到的一页：左上角「‹ 目录」翻回上一页；章节扉页（进二级页面时）只有章号与标题；
 * 选项弹出的二级弹窗照书页排版——标题像章节名、下面一道双线、正文、页脚是按钮。
 * 按钮与正文里「点一下就关闭」的条目照 [com.tyranor.next.ui.common.AppAlertDialog] 的约定拿到退场控制器：先翻回再执行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfBookLeafPage(
    leaf: ShelfBookLeaf,
    level: Int,
    pages: ShelfBookPages,
    activeTab: ShelfBookTab,
    metrics: ShelfBookMetrics,
    paperSize: IntSize?,
) {
    val spec = leaf.spec
    // 翻回途中这一页已经关了：收起键盘与输入焦点（与弹窗退场时一致）
    val focus = LocalFocusManager.current
    LaunchedEffect(leaf.leaving) { if (leaf.leaving) focus.clearFocus(force = true) }
    // 「‹」与返回键同一条路：先给这一页自己登记的返回处理（如封面搜索的「作品多封面 → 回一级」），没有再翻回
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val backLabel = when {
        level != 1 -> null
        activeTab == ShelfBookTab.Manage -> stringResource(R.string.game_book_tab_manage)
        else -> stringResource(R.string.game_book_contents)
    }
    Column(
        Modifier
            .fillMaxSize()
            .shelfBookFitPaper(paperSize)
            .padding(start = metrics.gutterPad, end = metrics.outerPad, top = metrics.topPad, bottom = metrics.bottomPad),
    ) {
        ShelfBookBackLink(
            label = backLabel,
            onClick = { if (back != null) back.onBackPressed() else pages.dismissTop() },
        )
        if (spec != null) {
            // 窗口版弹窗的卡片提供正文色与自己的入场节拍；书页照样提供：正文色取条目正文色（白雾玻璃下根部的页面字色是近白），
            // 入场节拍不重播（翻页就是这一页的进场），也不挂到主页面的入场上
            CompositionLocalProvider(
                LocalAppDialogContent provides true,
                LocalContentColor provides TextColor,
                LocalIndication provides NoIndication,
            ) {
                ProvideEntrance(rememberEntranceState(playOnStart = false)) {
                    // 弹窗标题当章名：用书里的宋体（只换这一格的 titleMedium 字体，字号与调用方的写法不变）
                    val typography = MaterialTheme.typography
                    val chapterFont = BookSerifBold
                    val chapterTypography = remember(typography, chapterFont) {
                        typography.copy(titleMedium = typography.titleMedium.copy(fontFamily = chapterFont))
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Box(Modifier.fillMaxWidth().padding(top = spec.topOverhangHeight)) {
                            MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = chapterTypography, shapes = MaterialTheme.shapes) {
                                spec.title()
                            }
                        }
                        spec.topOverhang?.invoke()
                    }
                    ShelfBookDoubleRule(Modifier.padding(top = 8.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .then(if (spec.textScrollEnabled) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    ) {
                        if (spec.showButtonRow) {
                            CompositionLocalProvider(LocalDialogBodyExit provides pages.exit) { spec.text() }
                        } else {
                            CompositionLocalProvider(LocalDialogExit provides pages.exit, LocalDialogBodyExit provides pages.exit) { spec.text() }
                        }
                    }
                    if (spec.showButtonRow) {
                        val dismissButton = spec.dismissButton
                        val actions = Modifier.fillMaxWidth().padding(top = 10.dp)
                        if (spec.buttonArrangement == Arrangement.SpaceBetween && dismissButton != null) {
                            SplitEndsActionRow(
                                modifier = actions,
                                leading = { CompositionLocalProvider(LocalDialogExit provides pages.exit) { dismissButton() } },
                                trailing = { CompositionLocalProvider(LocalDialogExit provides null) { spec.confirmButton() } },
                            )
                        } else {
                            FlowRow(
                                modifier = actions,
                                horizontalArrangement = spec.buttonArrangement,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompositionLocalProvider(LocalDialogExit provides pages.exit) { dismissButton?.invoke() }
                                CompositionLocalProvider(LocalDialogExit provides if (dismissButton == null) pages.exit else null) {
                                    spec.confirmButton()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 翻回上一页的「‹ 目录 / ‹ 管理」（更深的页只有 ‹）。 */
@Composable
private fun ShelfBookBackLink(label: String?, onClick: () -> Unit) {
    val ink = SecondaryTextColor
    val back = stringResource(R.string.common_back)
    Row(
        Modifier
            .heightIn(min = BOOK_BACK_HEIGHT)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = back },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShelfBookChevron(color = ink, forward = false)
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif, fontWeight = FontWeight.SemiBold),
                color = ink,
                modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics { },
            )
        }
    }
}

/** 书页小箭头（向前 › 或返回 ‹）。 */
@Composable
private fun ShelfBookChevron(color: Color, forward: Boolean = true, modifier: Modifier = Modifier) {
    Canvas(modifier.size(6.dp, 11.dp)) {
        val w = 1.4.dp.toPx()
        val tipX = if (forward) size.width else 0f
        val tailX = size.width - tipX
        drawLine(color, Offset(tailX, 0f), Offset(tipX, size.height / 2f), w, cap = StrokeCap.Round)
        drawLine(color, Offset(tipX, size.height / 2f), Offset(tailX, size.height), w, cap = StrokeCap.Round)
    }
}

/**
 * 封面：翻过一半前是封面——高清封面上叠着与书架上那一盒完全相同的反光、棱边、倒角高光、书签丝带与 ▶ 胶囊
 * （按书架上的尺寸画、整体放大），起飞那一刻看不出换了一本；之后是封面内侧的衬纸（或双页模式下的左书页底纸，
 * 窄方居中模式下则优雅收束为贴合书脊的窄边折页，避免半截封面被屏幕左缘生硬截断）。
 */
@Composable
private fun ShelfBookCover(
    game: ScanGame,
    motion: ShelfBookMotion,
    pages: ShelfBookPages,
    stage: ShelfBookStage,
    small: ImageBitmap?,
    shape: AbsoluteCutCornerShape,
    leftPageShape: AbsoluteCutCornerShape,
    cloth: ImageBitmap,
    tint: Color,
    board: Color,
    chamfer: Dp,
    pageCut: Dp,
    pageWidth: Dp,
    leftBoardWidth: Dp,
    heroWidth: Dp,
    heroHeight: Dp,
    heroScaleX: Float,
    heroScale: Float,
    morph: ShelfBookLayoutMorph,
) {
    val density = LocalDensity.current
    val faceShade = stage.faceShade
    val tier = with(density) { CoverDecodeTier.forWidth(pageWidth.roundToPx()) }
    val large by rememberCoverBitmap(game.coverUri, tier = tier)
    val face = large ?: small
    val wash = remember(small) { small?.let { coverWash(it, BOOK_WASH_WIDTH, BOOK_WASH_HEIGHT, 2) } }
    val engineColor = game.engine.coverColor()
    val shade = ShelfBookShade
    val chamferLight = ShelfChamferLight
    val chamferDim = ShelfChamferDim
    val ribbon = MaterialTheme.colorScheme.primary
    val heroChamfer = chamfer / heroScale
    val clippedBoard = morph.isLeftBoardClipped
    // 封面背面按「左页」画（书脊在右），整体镜像后贴上去：翻过来时方向一致
    val mirroredShape = remember(chamfer) { AbsoluteCutCornerShape(topLeft = chamfer, topRight = 0.dp, bottomRight = 0.dp, bottomLeft = chamfer) }
    val effectiveBoardWidthPx: (Float) -> Float = { totalWidthPx ->
        if (!clippedBoard) {
            totalWidthPx
        } else {
            val safeTotal = totalWidthPx.coerceAtLeast(1f)
            val basePx = with(density) { leftBoardWidth.toPx() }.coerceIn(1f, safeTotal)
            val rotY = motion.coverRotationY(morph)
            val camPx = SHELF_FACE_CAMERA * BOOK_CAMERA_DISTANCE_PX_UNIT * density.density /
                motion.scale().coerceAtLeast(0.01f)
            shelfBookEffectiveClippedWidthPx(basePx, safeTotal, rotY, camPx)
        }
    }
    val fillInsideBoardPath: (Size, Path) -> Unit = { sz, outPath ->
        outPath.rewind()
        if (sz.width > 0f && sz.height > 0f) {
            val wPx = effectiveBoardWidthPx(sz.width)
            val peekPx = with(density) { BOOK_LEFT_PEEK.toPx() }
            val ratio = ((wPx - peekPx) / (sz.width - peekPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val maxCut = minOf(wPx * 0.25f, sz.height / 3f).coerceAtLeast(0f)
            val cPx = with(density) { lerp(BOOK_PEEK_CORNER, chamfer, ratio).toPx() }.coerceIn(0f, maxCut)
            val left = (sz.width - wPx).coerceAtLeast(0f)
            outPath.moveTo(sz.width, 0f)
            outPath.lineTo(left + cPx, 0f)
            outPath.lineTo(left, cPx)
            outPath.lineTo(left, (sz.height - cPx).coerceAtLeast(cPx))
            outPath.lineTo(left + cPx, sz.height)
            outPath.lineTo(sz.width, sz.height)
            outPath.close()
        }
    }
    val boardClipPath = remember { Path() }
    val flyleafClipPath = remember { Path() }
    val dynamicBoardShape = remember(leftBoardWidth, pageWidth, chamfer, morph, motion, density, clippedBoard) {
        GenericShape { sz, _ -> fillInsideBoardPath(sz, this) }
    }
    val dynamicCut: () -> Dp = {
        if (clippedBoard) {
            val totalPx = with(density) { pageWidth.toPx() }.coerceAtLeast(1f)
            val wDp = with(density) { effectiveBoardWidthPx(totalPx).toDp() }
            val ratio = ((wDp - BOOK_LEFT_PEEK) / (pageWidth - BOOK_LEFT_PEEK).coerceAtLeast(1.dp)).coerceIn(0f, 1f)
            lerp(BOOK_PEEK_CORNER, chamfer, ratio).coerceAtMost((wDp * 0.25f).coerceAtLeast(1.5.dp))
        } else {
            chamfer
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // 摊平后封面压在书页底下（翻过去的书页叠在封面内侧上面）；立起来以前在最上面
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(0, 0, zIndex = if (motion.isCoverInsideVisible(morph)) -1f else 1f)
                }
            }
            .shelfBookCover(motion, morph),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .shelfBookCoverSide(motion, inside = false, morph = morph)
                .clip(shape)
                .background(engineColor)
                .glassBorder(shape)
                .shelfBookTurnShade(motion, shade),
        ) {
            face?.let { ShelfBookCoverArt(it, game.title, motion) }
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(heroWidth, heroHeight)
                    .graphicsLayer {
                        scaleX = heroScaleX
                        scaleY = heroScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                // 与书架上正面的叠放次序一致：（没有封面时的占位字、）丝带在下，棱边、反光、倒角高光、背光压暗、▶ 依次在上
                if (face == null) ShelfCoverPlaceholder(game.engine.displayName, heroWidth, Modifier.align(Alignment.Center))
                if (isShelfLastPlayed(game)) ShelfCoverRibbon(heroWidth, ribbon)
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfBevelOverlay(ShelfGloss, ShelfDepthShade)
                        .shelfGlossOverlay(ShelfGloss)
                        .shelfChamferGlintsOverlay(heroChamfer, { false }, chamferLight, chamferDim),
                )
                Box(Modifier.fillMaxSize().shelfBookFrontShade(motion, faceShade).background(ShelfFaceShade))
                if (motion.enhanced) ShelfPlayBadge(visibility = { motion.badge() }, onClick = null)
            }
        }
        // 封面内侧：随左侧宽连续裁剪，双页与半页在衬纸之上浮现对应宽度的实体左页纸面与书芯边
        val insideBoardShape: Shape = if (clippedBoard) dynamicBoardShape else mirroredShape
        Box(
            Modifier
                .fillMaxSize()
                .shelfBookCoverSide(motion, inside = true, morph = morph)
                .graphicsLayer { scaleX = -1f }
                .then(
                    if (!clippedBoard) Modifier else Modifier.drawBehind {
                        val flatAlpha = ((motion.cover.value - 0.80f) / 0.20f).coerceIn(0f, 1f)
                        if (flatAlpha > 0.01f) {
                            val outline = dynamicBoardShape.createOutline(size, layoutDirection, this)
                            val dx = -1.5.dp.toPx()
                            val dy = 2.5.dp.toPx()
                            withTransform({ translate(left = dx, top = dy) }) {
                                drawOutline(outline, shade.copy(alpha = 0.28f * flatAlpha))
                            }
                        }
                    },
                )
                .then(
                    if (!clippedBoard) {
                        Modifier.clip(mirroredShape)
                    } else {
                        Modifier.drawWithContent {
                            fillInsideBoardPath(size, boardClipPath)
                            clipPath(boardClipPath) { this@drawWithContent.drawContent() }
                        }
                    },
                )
                .background(board)
                .shelfBookHardcoverBoard(
                    shape = insideBoardShape,
                    cloth = cloth,
                    isLeftBoard = true,
                    cut = dynamicCut,
                    frameStartOffset = {
                        if (clippedBoard) {
                            (it.width - effectiveBoardWidthPx(it.width)).coerceAtLeast(0f)
                        } else {
                            0f
                        }
                    },
                )
                .shelfBookTurnShade(motion, shade),
        ) {
            val leftPaperCut = minOf(pageCut, (leftBoardWidth * 0.2f).coerceAtLeast(1.5.dp))
            val dynamicLeftPageShape = remember(leftPaperCut) {
                AbsoluteCutCornerShape(topLeft = leftPaperCut, topRight = 0.dp, bottomRight = 0.dp, bottomLeft = leftPaperCut)
            }
            if (morph.leftPaperAlpha < 0.99f) {
                val fillFlyleafClipPath: (Size, Path) -> Unit = { sz, outPath ->
                    outPath.rewind()
                    if (sz.width > 0f && sz.height > 0f) {
                        val lipPx = with(density) { BOOK_LIP.toPx() }
                        val totalW = sz.width.coerceAtLeast(1f)
                        val wPx = effectiveBoardWidthPx(totalW)
                        val maxFlyW = (totalW - lipPx).coerceAtLeast(1f)
                        val minFlyW = with(density) { 10.dp.toPx() }.coerceAtMost(maxFlyW)
                        val flyW = (wPx - lipPx).coerceIn(minFlyW, maxFlyW)
                        val peekPx = with(density) { BOOK_LEFT_PEEK.toPx() }
                        val baseFlyW = (peekPx - lipPx).coerceAtLeast(1f)
                        val ratio = ((flyW - baseFlyW) / (maxFlyW - baseFlyW).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val startCut = with(density) {
                            if (morph.leftPaperAlpha < 0.02f) {
                                BOOK_PEEK_CORNER_INNER.toPx()
                            } else {
                                minOf(pageCut.toPx(), (leftBoardWidth * 0.2f).coerceAtLeast(1.5.dp).toPx())
                            }
                        }
                        val endCut = with(density) { pageCut.toPx() }
                        val maxCut = minOf(flyW * 0.25f, sz.height / 3f).coerceAtLeast(0f)
                        val cPx = (startCut + (endCut - startCut) * ratio).coerceIn(0f, maxCut)
                        val left = (totalW - flyW).coerceAtLeast(0f)
                        outPath.moveTo(totalW, 0f)
                        outPath.lineTo(left + cPx, 0f)
                        outPath.lineTo(left, cPx)
                        outPath.lineTo(left, (sz.height - cPx).coerceAtLeast(cPx))
                        outPath.lineTo(left + cPx, sz.height)
                        outPath.lineTo(totalW, sz.height)
                        outPath.close()
                    }
                }
                ShelfBookFlyleaf(
                    wash = wash,
                    tint = tint,
                    pageShape = leftPageShape,
                    modifier = if (!clippedBoard) {
                        Modifier
                            .fillMaxSize()
                            .padding(top = BOOK_LIP, bottom = BOOK_LIP, start = BOOK_LIP)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .padding(top = BOOK_LIP, bottom = BOOK_LIP)
                            .drawWithContent {
                                fillFlyleafClipPath(size, flyleafClipPath)
                                clipPath(flyleafClipPath) { this@drawWithContent.drawContent() }
                            }
                    },
                )
            }
            if (morph.leftPaperAlpha > 0.01f) {
                val leftStack = BOOK_LEFT_STACK * morph.leftPaperAlpha
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(leftBoardWidth)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = morph.leftPaperAlpha }
                        .padding(top = BOOK_LIP, bottom = BOOK_LIP, start = BOOK_LIP),
                ) {
                    ShelfBookPageBlockEdges(
                        pageShape = dynamicLeftPageShape,
                        gutterAtStart = false,
                        sideThickness = leftStack,
                        lines = BOOK_LEFT_STACK_LINES,
                    )
                    ShelfBookPaper(
                        shape = dynamicLeftPageShape,
                        gutterAtStart = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = leftStack, bottom = BOOK_STACK_BOTTOM)
                            .shelfBookMorphSpineShadow(morph, shade, gutterAtStart = false),
                    ) {}
                }
            }
            // 单页窄边模式仅可见的左窄边封板接收点按合书，避免整块透明封面拦截居中模式下左右留白区的背景轻点
            if (morph.currentMode == ShelfBookLayoutMode.SinglePeek || morph.currentMode == ShelfBookLayoutMode.SingleCentered) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(leftBoardWidth)
                        .fillMaxHeight()
                        .pointerInput(motion, morph, pages) {
                            detectTapGestures {
                                if (!motion.isCoverInsideVisible(morph)) return@detectTapGestures
                                pages.dismissOrClose { motion.close() }
                            }
                        },
                )
            }
        }
    }
}

/**
 * 封面图：按「铺满、居中裁切」画，但裁切按书此刻在屏幕上的横纵比算——起飞时整本书横纵缩放不同（书页比封面高挑），
 * 图按反比例补偿，屏幕上看到的封面始终是原图的比例：离开书架那一刻与那一盒完全一样，摊开时是书页比例下的裁切。
 */
@Composable
private fun ShelfBookCoverArt(bitmap: ImageBitmap, title: String, motion: ShelfBookMotion) {
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = title }
            .drawBehind {
                val sx = if (motion.enhanced) motion.scaleX() else 1f
                val sy = if (motion.enhanced) motion.scale() else 1f
                val skew = if (sy > 0f) sx / sy else 1f
                val art = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                // 本地坐标里图的宽高比 = 原图比例 ÷ 此刻的横纵缩放比，屏幕上就是原图的比例
                val local = art / skew
                val boxRatio = size.width / size.height
                val drawWidth: Float
                val drawHeight: Float
                if (boxRatio > local) {
                    drawWidth = size.width
                    drawHeight = size.width / local
                } else {
                    drawHeight = size.height
                    drawWidth = size.height * local
                }
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(((size.width - drawWidth) / 2f).roundToInt(), ((size.height - drawHeight) / 2f).roundToInt()),
                    dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            },
    )
}

/**
 * 封面内侧窄边衬纸（单页模式下露在左侧靠书脊的窄折翼）：
 * 封面主色向当前外观温润纸基色融合，靠书脊一侧弯入中缝阴影，外缘带极薄叠页边与高光。
 */
@Composable
private fun ShelfBookFlyleaf(
    wash: ImageBitmap?,
    tint: Color,
    pageShape: AbsoluteCutCornerShape,
    modifier: Modifier = Modifier,
) {
    val shade = ShelfBookShade
    val darkPaper = AppThemeColors.isDark || AppThemeColors.isGlass
    val leftPageEdge = ShelfBookPageEdge
    val leftPageHighlight = ShelfBookStackHighlight
    val paper = lerp(tint, ShelfBookEndpaperBase, if (darkPaper) BOOK_ENDPAPER_DARKEN else BOOK_ENDPAPER_LIGHTEN)
    Box(
        modifier
            .clip(pageShape)
            .background(paper)
            .drawWithCache {
                val grainBrush = if (darkPaper) shelfPaperGrainDarkBrush else shelfPaperGrainLightBrush
                val curl = BOOK_GUTTER_CURL.toPx()
                val bend = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.58f to shade.copy(alpha = 0.04f),
                    0.88f to shade.copy(alpha = 0.16f),
                    1f to shade.copy(alpha = 0.34f),
                    startX = size.width - curl,
                    endX = size.width,
                )
                val cut = pageShape.cut(size, this)
                val leftStack = shelfBookStackEdges(size, cut, gutterAtStart = false, step = 1.4.dp.toPx(), lines = 2)
                val leftRim = shelfBookPageRim(size, cut, gutterAtStart = false)
                val stackStroke = Stroke(width = 0.6.dp.toPx())
                val rimStroke = Stroke(width = 0.8.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    drawRect(grainBrush, alpha = 0.6f)
                    drawRect(bend)
                    leftStack.forEachIndexed { idx, path ->
                        drawPath(path, leftPageEdge.copy(alpha = leftPageEdge.alpha * (0.9f - idx * 0.28f)), style = stackStroke)
                    }
                    drawPath(leftRim, leftPageHighlight.copy(alpha = leftPageHighlight.alpha * 0.45f), style = rimStroke)
                }
            },
    ) {
        wash?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (darkPaper) BOOK_ENDPAPER_WASH_DARK else BOOK_ENDPAPER_WASH_LIGHT,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 书页古典双装饰线：统一用于双页左/右页题下与二级弹窗标题下。 */
@Composable
private fun ShelfBookDoubleRule(modifier: Modifier = Modifier) {
    val rule = TextColor
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        drawLine(rule.copy(alpha = 0.50f), Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.15.dp.toPx())
        drawLine(rule.copy(alpha = 0.24f), Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 0.6.dp.toPx())
    }
}

/** 双页左页头部：首行书册位置码（与右页引擎名互补不重复）、次行加粗宋体「管理」，高度与右页书名栏齐平。 */
@Composable
private fun ShelfBookManageHeader(game: ScanGame, stage: ShelfBookStage, modifier: Modifier = Modifier) {
    val position = if (stage.count > 0) stringResource(R.string.game_shelf_position, stage.index + 1, stage.count) else null
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        if (position != null) {
            Row(Modifier.clearAndSetSemantics { contentDescription = position }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(Locale.ROOT, "%02d", stage.index + 1),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    String.format(Locale.ROOT, " / %02d", stage.count),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                    color = SecondaryTextColor,
                )
            }
        } else {
            Text(
                game.engine.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                color = SecondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(R.string.game_book_tab_manage),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.Bold),
            color = TextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 双页模式下的左页（「管理」独立整页）：紧贴左页纸面内框，直接展示封面装帧卡、快捷操作条目与底部「删除游戏」，
 * 无需再经分栏切换。右页翻到二级弹窗或切屏时随 [shelfBookMorphLeftSlot] 隐退。
 */
@Composable
private fun ShelfBookSpreadLeftPage(
    game: ScanGame,
    stage: ShelfBookStage,
    motion: ShelfBookMotion,
    pages: ShelfBookPages,
    quickActions: GameQuickActionsController,
    metrics: ShelfBookMetrics,
    shape: AbsoluteCutCornerShape,
    paperSize: IntSize?,
    enabled: Boolean,
    onOpenLeaf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(top = BOOK_LIP, bottom = BOOK_LIP + BOOK_STACK_BOTTOM, start = BOOK_LIP + BOOK_LEFT_STACK)
            .clip(shape)
            // 吸收左页纸面空白轻点，不触发外层点空白合书；水平拖动照常冒泡
            .pointerInput(pages) { detectTapGestures { } },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .shelfBookFitPaper(paperSize, gutterAtRight = true)
                .padding(start = metrics.outerPad, end = metrics.gutterPad, top = metrics.topPad, bottom = metrics.bottomPad),
        ) {
            val compact = maxHeight < BOOK_PAGE_COMPACT_HEIGHT
            if (compact) {
                val pageScroll = rememberScrollState()
                Column(Modifier.fillMaxSize().shelfBookScrollFade(pageScroll).verticalScroll(pageScroll)) {
                    ShelfBookManageHeader(game, stage)
                    ShelfBookDoubleRule(Modifier.padding(top = 8.dp))
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        ShelfBookManageTab(
                            compact = true,
                            game = game,
                            motion = motion,
                            tabMotion = null,
                            tabRole = ShelfBookTabRole.Entering,
                            quickActions = quickActions,
                            metrics = metrics,
                            enabled = enabled,
                            applyInk = false,
                            onOpenLeaf = onOpenLeaf,
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    ShelfBookManageHeader(game, stage)
                    ShelfBookDoubleRule(Modifier.padding(top = 8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp)) {
                        ShelfBookManageTab(
                            compact = false,
                            game = game,
                            motion = motion,
                            tabMotion = null,
                            tabRole = ShelfBookTabRole.Entering,
                            quickActions = quickActions,
                            metrics = metrics,
                            enabled = enabled,
                            applyInk = false,
                            onOpenLeaf = onOpenLeaf,
                        )
                    }
                }
            }
        }
    }
}

/** 典藏藏书票双层斜切花框：呼应整书 45° 斜切角，勾勒优雅内衬与四角微缩线框（不裁剪封面外光晕）。 */
private fun Modifier.shelfBookPlateFrame(
    cut: Dp,
    accent: Color,
    ink: Color,
    compact: Boolean = false,
): Modifier = drawBehind {
    val minDim = minOf(size.width, size.height)
    if (minDim < 36.dp.toPx()) return@drawBehind
    val outerCut = cut.toPx().coerceAtMost(minDim * 0.16f)
    fun chamferRectPath(inset: Float, c: Float): Path {
        val l = inset
        val t = inset
        val r = (size.width - inset).coerceAtLeast(l + 2f)
        val b = (size.height - inset).coerceAtLeast(t + 2f)
        val cc = c.coerceAtMost(minOf(r - l, b - t) * 0.25f)
        return Path().apply {
            moveTo(l + cc, t)
            lineTo(r - cc, t)
            lineTo(r, t + cc)
            lineTo(r, b - cc)
            lineTo(r - cc, b)
            lineTo(l + cc, b)
            lineTo(l, b - cc)
            lineTo(l, t + cc)
            close()
        }
    }
    drawPath(
        chamferRectPath(0f, outerCut),
        color = ink.copy(alpha = if (compact) 0.13f else 0.15f),
        style = Stroke(width = 0.65.dp.toPx()),
    )
    if (!compact) {
        val innerGap = 3.5.dp.toPx()
        val innerCut = (outerCut - innerGap * 0.42f).coerceAtLeast(1.5.dp.toPx())
        drawPath(
            chamferRectPath(innerGap, innerCut),
            color = ink.copy(alpha = 0.075f),
            style = Stroke(width = 0.5.dp.toPx()),
        )
    }
    val arm = if (compact) 4.dp.toPx() else 7.dp.toPx()
    val tickColor = accent.copy(alpha = if (compact) 0.36f else 0.42f)
    val tickStroke = 0.9.dp.toPx()
    val w = size.width
    val h = size.height
    // 四角精巧斜切包角缀饰
    val cornerPaths = listOf(
        Path().apply { moveTo(0f, outerCut + arm); lineTo(0f, outerCut); lineTo(outerCut, 0f); lineTo(outerCut + arm, 0f) },
        Path().apply { moveTo(w - outerCut - arm, 0f); lineTo(w - outerCut, 0f); lineTo(w, outerCut); lineTo(w, outerCut + arm) },
        Path().apply { moveTo(w, h - outerCut - arm); lineTo(w, h - outerCut); lineTo(w - outerCut, h); lineTo(w - outerCut - arm, h) },
        Path().apply { moveTo(outerCut + arm, h); lineTo(outerCut, h); lineTo(0f, h - outerCut); lineTo(0f, h - outerCut - arm) },
    )
    for (p in cornerPaths) {
        drawPath(p, tickColor, style = Stroke(width = tickStroke, cap = StrokeCap.Round))
    }
}

/** 两端渐隐的优雅水平细分割线。 */
@Composable
private fun ShelfBookFadedDivider(modifier: Modifier = Modifier, alpha: Float = 0.20f) {
    val ink = TextColor
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.15f to ink.copy(alpha = alpha),
                0.85f to ink.copy(alpha = alpha),
                1f to Color.Transparent,
            ),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 0.65.dp.toPx(),
        )
    }
}

/** 书页内两端渐隐、中心镶菱的古典分割饰线。 */
@Composable
private fun ShelfBookOrnamentRule(width: Dp = 56.dp, modifier: Modifier = Modifier) {
    val line = TextColor.copy(alpha = 0.30f)
    val gem = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
    Canvas(modifier.size(width, 7.dp)) {
        val midY = size.height / 2f
        val midX = size.width / 2f
        val gap = 6.dp.toPx()
        val stroke = 0.75.dp.toPx()
        if (midX > gap) {
            drawLine(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, line), startX = 0f, endX = midX - gap),
                start = Offset(0f, midY),
                end = Offset(midX - gap, midY),
                strokeWidth = stroke,
            )
            drawLine(
                brush = Brush.horizontalGradient(listOf(line, Color.Transparent), startX = midX + gap, endX = size.width),
                start = Offset(midX + gap, midY),
                end = Offset(size.width, midY),
                strokeWidth = stroke,
            )
        }
        val r = 2.6.dp.toPx()
        val diamond = Path().apply {
            moveTo(midX, midY - r)
            lineTo(midX + r, midY)
            lineTo(midX, midY + r)
            lineTo(midX - r, midY)
            close()
        }
        drawPath(diamond, gem)
    }
}

/** 博物馆级双衬边压凹 3:4 封面版画：柔和封面色温径向光晕 + 双线衬垫 + 轻薄角光。 */
@Composable
private fun ShelfBookFramedCover(
    game: ScanGame,
    width: Dp,
    showEngineFooter: Boolean = false,
    ambientTint: Color? = null,
    museumMat: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tier = with(density) { CoverDecodeTier.forWidth(width.roundToPx()) }
    val cover by rememberCoverBitmap(game.coverUri, tier = tier)
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val cleanTitle = remember(game.title, hideTitleTag) {
        (if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title).trim()
    }
    val darkPaper = AppThemeColors.isDark || AppThemeColors.isGlass
    val height = width * BOOK_COVER_PLATE_ASPECT
    val outerShape = remember { RoundedCornerShape(6.dp) }
    val innerShape = AppComponentShape
    val border = TextColor.copy(alpha = 0.24f)
    val foilBorder = ShelfBookFoilAccent.copy(alpha = 0.45f)
    val innerMat = if (darkPaper) {
        lerp(ShelfBookPaperSurface, ShelfBookLight, 0.08f)
    } else {
        ShelfBookPaperEdgeTint
    }
    val shadow = ShelfBookShade.copy(alpha = 0.24f)
    val matPad = if (museumMat) 4.dp else 3.dp
    Box(
        modifier
            .size(width, height)
            .drawBehind {
                if (ambientTint != null) {
                    val haloAlpha = if (darkPaper) 0.18f else 0.11f
                    val radius = maxOf(size.width, size.height) * 0.92f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(ambientTint.copy(alpha = haloAlpha), Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
                drawRoundRect(
                    color = shadow,
                    topLeft = Offset(2.dp.toPx(), 3.5.dp.toPx()),
                    size = size,
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
            .clip(outerShape)
            .background(innerMat)
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    color = border,
                    size = size,
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 0.85.dp.toPx()),
                )
                if (museumMat) {
                    val inset = 1.8.dp.toPx()
                    drawRoundRect(
                        color = foilBorder,
                        topLeft = Offset(inset, inset),
                        size = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(4.8.dp.toPx()),
                        style = Stroke(width = 0.55.dp.toPx()),
                    )
                }
            }
            .padding(matPad)
            .clip(innerShape)
            .background(game.engine.coverColor()),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = cleanTitle.ifEmpty { game.title },
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            0f to ShelfBookLight.copy(alpha = 0.12f),
                            0.38f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    ),
            )
        } else {
            val initial = cleanTitle.firstOrNull()?.toString() ?: "T"
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 1,
                )
                if (showEngineFooter) {
                    Text(
                        text = game.engine.displayName,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = BookSerif),
                        color = Color.White.copy(alpha = 0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 双页模式翻到二级弹窗时印在左页的「精装典藏扉页」（Verso）：
 * 顶部齐平书眉（「01 / 135」↔「引擎名」） + 斜切双线藏书票框内居中封面色温光晕、博物馆双衬边版画、
 * 渐隐菱形饰线、加粗宋体书名（及不同原名） + 底部游玩统计，浑然一体不留空洞。
 */
@Composable
private fun ShelfBookSpreadVerso(
    game: ScanGame,
    stage: ShelfBookStage,
    stats: GamePlaytimeStats?,
    tint: Color,
    metrics: ShelfBookMetrics,
) {
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val cleanTitle = remember(game.title, hideTitleTag) {
        (if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title).trim()
    }
    val original = remember(game.metadataTitle, cleanTitle, game.title) {
        game.metadataTitle?.trim()?.takeIf { it.isNotEmpty() && it != cleanTitle && it != game.title }
    }
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = metrics.outerPad, end = metrics.gutterPad, top = metrics.topPad, bottom = metrics.bottomPad),
    ) {
        val plateWidth = minOf(maxWidth * 0.56f, maxHeight * 0.40f).coerceIn(82.dp, 188.dp)
        val showStats = maxHeight >= 350.dp
        val showPlateFrame = maxHeight >= 290.dp && maxWidth >= 190.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = BOOK_BACK_HEIGHT)
                    .clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stage.count > 0) {
                    Text(
                        String.format(Locale.ROOT, "%02d", stage.index + 1),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.SemiBold),
                        color = accent,
                    )
                    Text(
                        String.format(Locale.ROOT, " / %02d", stage.count),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                        color = SecondaryTextColor,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    game.engine.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                    color = SecondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
            ShelfBookFadedDivider(Modifier.padding(top = 4.dp), alpha = 0.22f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .then(
                        if (showPlateFrame) {
                            Modifier.shelfBookPlateFrame(cut = 10.dp, accent = accent, ink = TextColor, compact = false)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ShelfBookFramedCover(
                        game = game,
                        width = plateWidth,
                        showEngineFooter = true,
                        ambientTint = tint,
                        museumMat = true,
                    )
                    ShelfBookOrnamentRule(width = 68.dp, modifier = Modifier.padding(top = 14.dp))
                    Text(
                        text = cleanTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.Bold),
                        color = TextColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
                    )
                    if (original != null) {
                        Text(
                            text = original,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = BookSerif),
                            color = SecondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 3.dp, start = 6.dp, end = 6.dp),
                        )
                    }
                }
            }
            if (showStats) {
                ShelfBookFadedDivider(alpha = 0.20f)
                ShelfStatsStrip(
                    game = game,
                    stats = stats,
                    divider = false,
                    labelFont = BookSerif,
                    valueFont = BookSerifBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * 右页：
 * - 单页模式（`SinglePeek` / `SingleCentered`）：书名栏 → 古典双线分栏（「目录 | 管理」）→ 旧墨散去 · 逐字晕墨显影内容区；
 * - 双页模式（`Spread`）：右页直接呈现「目录」，与左页齐平。
 * 以上状态切换在旋转或改变窗口比例时通过 [morph] 连续变形过渡。
 */
@Composable
private fun ShelfBookPage(
    game: ScanGame,
    ui: GameActionsUi,
    motion: ShelfBookMotion,
    pages: ShelfBookPages,
    host: ShelfBookHost,
    stage: ShelfBookStage,
    cloth: ImageBitmap,
    tabMotion: ShelfBookTabMotion,
    quickActions: GameQuickActionsController,
    stats: GamePlaytimeStats?,
    morph: ShelfBookLayoutMorph,
    metrics: ShelfBookMetrics,
    paperSize: IntSize?,
    paperMode: ShelfBookLayoutMode?,
    onLeafSource: (ShelfBookTab) -> Unit,
) {
    val entries = remember(ui.launchFile, ui.items) { listOfNotNull(ui.launchFile) + ui.items }
    val toc = remember { ShelfBookToc() }
    val settled = toc.settled
    val presentation = remember(entries) { entries.map { it.copy(onClick = ShelfBookTocNoAction) } }
    val rows = remember(presentation, settled) { toc.rows(presentation) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .shelfBookFitPaper(paperSize)
            .padding(start = metrics.gutterPad, end = metrics.outerPad, top = metrics.topPad, bottom = metrics.bottomPad),
    ) {
        // 书页很矮（手机横屏两页摊开、分屏）时放不下固定头部 + 正文内容：整页一起滚动
        val compact = maxHeight < BOOK_PAGE_COMPACT_HEIGHT
        val canSwitch = { motion.interact() && !pages.turned && morph.horizontalTabWeight > 0.45f }
        val renderBodyForMode: @Composable (ShelfBookLayoutMode, Boolean, Modifier) -> Unit = { mode, isCompact, slotMod ->
            if (mode == ShelfBookLayoutMode.Spread) {
                Box(slotMod) {
                    ShelfBookContentsTab(
                        compact = isCompact,
                        game = game,
                        ui = ui,
                        motion = motion,
                        host = host,
                        cloth = cloth,
                        tabMotion = null,
                        tabRole = ShelfBookTabRole.Entering,
                        toc = toc,
                        rows = rows,
                        entriesCount = entries.size,
                        stats = stats,
                        metrics = metrics,
                        sectionLabelWeight = if (paperMode != null) 1f else morph.leftPaperAlpha,
                        onOpenLeaf = { onLeafSource(ShelfBookTab.Contents) },
                    )
                }
            } else {
                Box(slotMod) {
                    ShelfBookTabSwitchStack(
                        compact = isCompact,
                        game = game,
                        ui = ui,
                        motion = motion,
                        host = host,
                        cloth = cloth,
                        tabMotion = tabMotion,
                        quickActions = quickActions,
                        toc = toc,
                        rows = rows,
                        entriesCount = entries.size,
                        stats = stats,
                        metrics = metrics,
                        onLeafSource = onLeafSource,
                    )
                }
            }
        }
        val showHeaderPosition = paperMode?.let { it != ShelfBookLayoutMode.Spread } ?: (morph.leftPaperAlpha < 0.5f)
        val renderHeaderAndBody: @Composable (Boolean, Modifier) -> Unit = { isCompact, bodyModifier ->
            ShelfBookHalfTitle(game, stage, showPosition = showHeaderPosition, modifier = Modifier.shelfBookInk(motion, 1))
            ShelfBookRightHeaderSlot(
                tabMotion = tabMotion,
                canInteract = canSwitch,
                morph = morph,
                paperMode = paperMode,
                modifier = Modifier.padding(top = 8.dp).shelfBookInk(motion, 2),
            )
            val fillMod = if (isCompact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
            Box(bodyModifier.fillMaxWidth().padding(top = 10.dp)) {
                val prevMode = morph.previousMode
                if (prevMode != null && morph.rightNeedsSpreadBodySwap(tabMotion.shown)) {
                    renderBodyForMode(prevMode, isCompact, fillMod.shelfBookMorphRightBody(morph, incoming = false))
                    renderBodyForMode(morph.currentMode, isCompact, fillMod.shelfBookMorphRightBody(morph, incoming = true))
                } else {
                    renderBodyForMode(morph.currentMode, isCompact, fillMod)
                }
            }
        }
        if (compact) {
            val pageScroll = rememberScrollState()
            Column(Modifier.fillMaxSize().shelfBookScrollFade(pageScroll).verticalScroll(pageScroll)) {
                renderHeaderAndBody(true, Modifier)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                renderHeaderAndBody(false, Modifier.weight(1f))
            }
        }
    }
}

/**
 * 右页标题下可平滑变形的分栏 / 双线槽：
 * 随 [ShelfBookLayoutMorph.horizontalTabWeight] 在 `34.dp` 分栏栏与 `4.dp` 古典双线间连续伸缩，
 * 始终在底缘画出与左页像素齐平的古典双线。
 */
@Composable
private fun ShelfBookRightHeaderSlot(
    tabMotion: ShelfBookTabMotion,
    canInteract: () -> Boolean,
    morph: ShelfBookLayoutMorph,
    paperMode: ShelfBookLayoutMode?,
    modifier: Modifier = Modifier,
) {
    val tabSurface = ShelfBookTabSurface
    val tabBorder = ShelfBookTabBorder
    val tabIndicator = ShelfBookTabIndicator
    val paperHighlight = ShelfBookPaperHighlight
    val shade = ShelfBookShade
    val darkPaper = AppThemeColors.isDark || AppThemeColors.isGlass
    val rule = TextColor
    val contentsLabel = stringResource(R.string.game_book_contents)
    val manageLabel = stringResource(R.string.game_book_tab_manage)
    val tabWeight = morph.horizontalTabWeight
    val layoutWeight = paperMode?.let { if (it == ShelfBookLayoutMode.Spread) 0f else 1f } ?: tabWeight
    val slotHeight = lerp(BOOK_DOUBLE_RULE_SLOT_HEIGHT, BOOK_TAB_BAR_HEIGHT, layoutWeight)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight)
            .drawBehind {
                val upperY = size.height - 3.5.dp.toPx()
                val lowerY = size.height - 0.5.dp.toPx()
                if (tabWeight > 0.01f) {
                    val tabWidth = size.width / 2f
                    val ind = tabMotion.indicator.value.coerceIn(0f, 1f)
                    val cx = tabWidth * (0.5f + ind)
                    val frameWidth = tabWidth * 0.68f
                    val frameTop = 2.dp.toPx()
                    val frameBottom = (upperY - 3.dp.toPx()).coerceAtLeast(frameTop + 16.dp.toPx())
                    val frameLeft = cx - frameWidth / 2f
                    val frameRight = cx + frameWidth / 2f
                    val cut = 4.dp.toPx().coerceAtMost(minOf(frameWidth, frameBottom - frameTop) * 0.25f)
                    val framePath = Path().apply {
                        moveTo(frameLeft + cut, frameTop)
                        lineTo(frameRight - cut, frameTop)
                        lineTo(frameRight, frameTop + cut)
                        lineTo(frameRight, frameBottom - cut)
                        lineTo(frameRight - cut, frameBottom)
                        lineTo(frameLeft + cut, frameBottom)
                        lineTo(frameLeft, frameBottom - cut)
                        lineTo(frameLeft, frameTop + cut)
                        close()
                    }
                    // 45° 斜切藏书票内衬框：压凹羊皮纸渐变填充 + 古铜细框 + 顶内缘活字压痕
                    drawPath(
                        framePath,
                        Brush.verticalGradient(
                            0f to tabSurface.copy(alpha = (tabSurface.alpha * 1.25f * tabWeight).coerceIn(0f, 1f)),
                            0.65f to tabSurface.copy(alpha = tabSurface.alpha * tabWeight),
                            1f to tabSurface.copy(alpha = tabSurface.alpha * 0.45f * tabWeight),
                            startY = frameTop,
                            endY = frameBottom,
                        ),
                    )
                    drawPath(
                        framePath,
                        color = tabBorder.copy(alpha = tabBorder.alpha * tabWeight),
                        style = Stroke(width = 0.75.dp.toPx()),
                    )
                    val innerHighlight = if (darkPaper) {
                        paperHighlight.copy(alpha = (paperHighlight.alpha * 0.7f * tabWeight).coerceIn(0f, 1f))
                    } else {
                        shade.copy(alpha = 0.06f * tabWeight)
                    }
                    drawLine(
                        color = innerHighlight,
                        start = Offset(frameLeft + cut + 1.dp.toPx(), frameTop + 1.1.dp.toPx()),
                        end = Offset(frameRight - cut - 1.dp.toPx(), frameTop + 1.1.dp.toPx()),
                        strokeWidth = 0.6.dp.toPx(),
                    )
                    // 中央菱形分隔缀饰
                    val diamondCenter = Offset(size.width / 2f, (frameTop + frameBottom) / 2f)
                    val r = 2.1.dp.toPx()
                    val diamond = Path().apply {
                        moveTo(diamondCenter.x, diamondCenter.y - r)
                        lineTo(diamondCenter.x + r, diamondCenter.y)
                        lineTo(diamondCenter.x, diamondCenter.y + r)
                        lineTo(diamondCenter.x - r, diamondCenter.y)
                        close()
                    }
                    drawPath(diamond, tabIndicator.copy(alpha = 0.45f * tabWeight))
                    // 底缘两端收尖雕版笔锋线：紧贴古典双线上缘随游标舒展滑行
                    val barStretch = sin(PI.toFloat() * ind)
                    val barWidth = tabWidth * (0.44f + 0.12f * barStretch)
                    val barBottomY = upperY - 0.5.dp.toPx()
                    val barThickness = 1.8.dp.toPx()
                    val barTopY = barBottomY - barThickness
                    val barMidY = (barTopY + barBottomY) / 2f
                    val barLeft = cx - barWidth / 2f
                    val barRight = cx + barWidth / 2f
                    val taper = 6.dp.toPx().coerceAtMost(barWidth * 0.25f)
                    val nibPath = Path().apply {
                        moveTo(barLeft, barMidY)
                        lineTo(barLeft + taper, barTopY)
                        lineTo(barRight - taper, barTopY)
                        lineTo(barRight, barMidY)
                        lineTo(barRight - taper, barBottomY)
                        lineTo(barLeft + taper, barBottomY)
                        close()
                    }
                    drawPath(nibPath, tabIndicator.copy(alpha = tabWeight))
                }
                // 古典双装饰线：与左页 [ShelfBookDoubleRule] 的线位及透明度完全一致
                drawLine(rule.copy(alpha = 0.50f), Offset(0f, upperY), Offset(size.width, upperY), 1.15.dp.toPx())
                drawLine(rule.copy(alpha = 0.24f), Offset(0f, lowerY), Offset(size.width, lowerY), 0.6.dp.toPx())
            },
        verticalAlignment = Alignment.Top,
    ) {
        if (layoutWeight > 0.01f) {
            Row(
                Modifier
                    .fillMaxSize()
                    .selectableGroup()
                    .shelfBookMorphTabSlot { morph.horizontalTabWeight },
                verticalAlignment = Alignment.Top,
            ) {
                ShelfBookTabItem(
                    label = contentsLabel,
                    tabMotion = tabMotion,
                    tabIndex = ShelfBookTab.Contents.ordinal,
                    onClick = { if (canInteract()) tabMotion.switchTo(ShelfBookTab.Contents) },
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 6.dp),
                )
                ShelfBookTabItem(
                    label = manageLabel,
                    tabMotion = tabMotion,
                    tabIndex = ShelfBookTab.Manage.ordinal,
                    onClick = { if (canInteract()) tabMotion.switchTo(ShelfBookTab.Manage) },
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ShelfBookTabItem(
    label: String,
    tabMotion: ShelfBookTabMotion,
    tabIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .selectable(
                selected = tabMotion.selected.ordinal == tabIndex,
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ShelfBookDualInkTabText(label = label, tabMotion = tabMotion, tabIndex = tabIndex)
    }
}

/** 分栏文字双墨叠层：平时次要色常规宋体与选中粗黑宋体随游标平滑交替。 */
@Composable
private fun ShelfBookDualInkTabText(
    label: String,
    tabMotion: ShelfBookTabMotion,
    tabIndex: Int,
) {
    Box(contentAlignment = Alignment.Center) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif, fontWeight = FontWeight.Medium),
            color = SecondaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .shelfBookTabLabelFade(tabMotion, tabIndex, activeLayer = false)
                .clearAndSetSemantics { },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.Bold),
            color = TextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .shelfBookTabLabelFade(tabMotion, tabIndex, activeLayer = true)
                .clearAndSetSemantics { },
        )
    }
}

/** 叠放退场旧分栏与进场新分栏：退场旧页逐行散墨模糊隐去，新页分元素逐字从模糊墨晕显影并带轻薄墨息掠过纸面。 */
@Composable
private fun ShelfBookTabSwitchStack(
    compact: Boolean,
    game: ScanGame,
    ui: GameActionsUi,
    motion: ShelfBookMotion,
    host: ShelfBookHost,
    cloth: ImageBitmap,
    tabMotion: ShelfBookTabMotion,
    quickActions: GameQuickActionsController,
    toc: ShelfBookToc,
    rows: List<ShelfBookTocRow>,
    entriesCount: Int,
    stats: GamePlaytimeStats?,
    metrics: ShelfBookMetrics,
    onLeafSource: (ShelfBookTab) -> Unit,
) {
    val contentModifier = if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
    Box(contentModifier.shelfBookTabPageWash(tabMotion, ShelfBookTabWash)) {
        tabMotion.exiting?.let { leavingTab ->
            key(leavingTab) {
                Box(contentModifier.shelfBookTabExitingContainer(tabMotion)) {
                    ShelfBookTabContent(
                        tab = leavingTab,
                        compact = compact,
                        game = game,
                        ui = ui,
                        motion = motion,
                        host = host,
                        cloth = cloth,
                        tabMotion = tabMotion,
                        tabRole = ShelfBookTabRole.Exiting,
                        quickActions = quickActions,
                        toc = toc,
                        rows = rows,
                        entriesCount = entriesCount,
                        stats = stats,
                        metrics = metrics,
                        onLeafSource = onLeafSource,
                    )
                }
            }
        }
        key(tabMotion.shown) {
            Box(contentModifier.shelfBookTabEnteringContainer(tabMotion)) {
                ShelfBookTabContent(
                    tab = tabMotion.shown,
                    compact = compact,
                    game = game,
                    ui = ui,
                    motion = motion,
                    host = host,
                    cloth = cloth,
                    tabMotion = tabMotion,
                    tabRole = ShelfBookTabRole.Entering,
                    quickActions = quickActions,
                    toc = toc,
                    rows = rows,
                    entriesCount = entriesCount,
                    stats = stats,
                    metrics = metrics,
                    onLeafSource = onLeafSource,
                )
            }
        }
    }
}

@Composable
private fun ShelfBookTabContent(
    tab: ShelfBookTab,
    compact: Boolean,
    game: ScanGame,
    ui: GameActionsUi,
    motion: ShelfBookMotion,
    host: ShelfBookHost,
    cloth: ImageBitmap,
    tabMotion: ShelfBookTabMotion,
    tabRole: ShelfBookTabRole,
    quickActions: GameQuickActionsController,
    toc: ShelfBookToc,
    rows: List<ShelfBookTocRow>,
    entriesCount: Int,
    stats: GamePlaytimeStats?,
    metrics: ShelfBookMetrics,
    onLeafSource: (ShelfBookTab) -> Unit,
) {
    when (tab) {
        ShelfBookTab.Contents -> ShelfBookContentsTab(
            compact = compact,
            game = game,
            ui = ui,
            motion = motion,
            host = host,
            cloth = cloth,
            tabMotion = tabMotion,
            tabRole = tabRole,
            toc = toc,
            rows = rows,
            entriesCount = entriesCount,
            stats = stats,
            metrics = metrics,
            sectionLabelWeight = 0f,
            onOpenLeaf = { onLeafSource(ShelfBookTab.Contents) },
        )
        ShelfBookTab.Manage -> ShelfBookManageTab(
            compact = compact,
            game = game,
            motion = motion,
            tabMotion = tabMotion,
            tabRole = tabRole,
            quickActions = quickActions,
            metrics = metrics,
            enabled = true,
            applyInk = true,
            onOpenLeaf = { onLeafSource(ShelfBookTab.Manage) },
        )
    }
}

/** 「目录」分栏（亦用于双页右页）：游玩统计三格 +（双页时的古典「目录」小节线）+ 章节目录 + 「启动游戏」按钮。 */
@Composable
private fun ShelfBookContentsTab(
    compact: Boolean,
    game: ScanGame,
    ui: GameActionsUi,
    motion: ShelfBookMotion,
    host: ShelfBookHost,
    cloth: ImageBitmap,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    toc: ShelfBookToc,
    rows: List<ShelfBookTocRow>,
    entriesCount: Int,
    stats: GamePlaytimeStats?,
    metrics: ShelfBookMetrics,
    sectionLabelWeight: Float,
    onOpenLeaf: () -> Unit,
) {
    val exiting = tabMotion != null && tabRole == ShelfBookTabRole.Exiting
    val currentUi by rememberUpdatedState(ui)
    val chapters: @Composable (Dp) -> Unit = { rowHeight ->
        rows.forEachIndexed { index, row ->
            val order = 1 + index
            key(row.item.key) {
                ShelfBookTocEntry(
                    row = row,
                    height = rowHeight,
                    onGone = { toc.gone(row.item.key) },
                    onClick = {
                        val actions = currentUi
                        val item = actions.launchFile?.takeIf { it.key == row.item.key }
                            ?: actions.items.firstOrNull { it.key == row.item.key }
                        if (item != null) {
                            if (item.opensBookLeaf) onOpenLeaf()
                            actions.perform(item)
                        }
                    },
                    tabMotion = tabMotion,
                    tabRole = tabRole,
                    revealOrder = order,
                    modifier = Modifier
                        .shelfBookInk(motion, 3 + index)
                        .let { if (tabMotion != null && exiting) it.shelfBookTabItem(tabMotion, tabRole, order) else it },
                )
            }
        }
    }
    Column(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
        ShelfStatsStrip(
            game = game,
            stats = stats,
            modifier = Modifier
                .padding(top = 2.dp)
                .shelfBookInk(motion, 2),
            divider = false,
            labelFont = BookSerif,
            valueFont = BookSerifBold,
            cellModifier = { col ->
                if (tabMotion == null) Modifier else Modifier.shelfBookTabItem(
                    motion = tabMotion,
                    role = tabRole,
                    order = 0,
                    subDelay = if (exiting) 0f else col * 0.045f,
                )
            },
        )
        if (sectionLabelWeight > 0.01f) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .shelfBookMorphRevealHeight { sectionLabelWeight }
                    .padding(top = 10.dp)
                    .shelfBookInk(motion, 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.game_book_contents),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.SemiBold),
                    color = SecondaryTextColor,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .height(0.6.dp)
                        .background(TextColor.copy(alpha = 0.16f)),
                )
            }
        }
        if (compact) {
            chapters(BOOK_ENTRY_MIN_HEIGHT)
        } else {
            val scroll = rememberScrollState()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                val rowHeight = (maxHeight / maxOf(entriesCount, BOOK_TOC_CAPACITY)).coerceIn(BOOK_ENTRY_MIN_HEIGHT, metrics.tocRowMax)
                Column(Modifier.fillMaxSize().shelfBookScrollFade(scroll).verticalScroll(scroll)) {
                    chapters(rowHeight)
                }
            }
        }
        val footerOrder = 1 + entriesCount
        ShelfBookLaunch(
            cloth = cloth,
            onClick = ui.launch,
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = footerOrder,
            height = metrics.footerHeight,
            modifier = Modifier
                .padding(top = 8.dp)
                .shelfBookInk(motion, 3 + entriesCount)
                .let { if (tabMotion == null) it else it.shelfBookTabFooterReveal(tabMotion, tabRole, footerOrder) },
        )
    }
}

/** 「管理」分栏（亦用于双页左页）：书卷化装帧封面卡（点按换封面） + 快捷操作条目（首页 / 桌面 / 改名） + 对称删除长按钮。 */
@Composable
private fun ShelfBookManageTab(
    compact: Boolean,
    game: ScanGame,
    motion: ShelfBookMotion,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    quickActions: GameQuickActionsController,
    metrics: ShelfBookMetrics,
    enabled: Boolean,
    applyInk: Boolean,
    onOpenLeaf: () -> Unit,
) {
    val exiting = tabMotion != null && tabRole == ShelfBookTabRole.Exiting
    fun Modifier.inkOrder(order: Int): Modifier = if (applyInk) shelfBookInk(motion, order) else this
    val toggleLabel = if (quickActions.shownQuickLaunched) {
        stringResource(R.string.game_remove_quick_launch)
    } else {
        stringResource(R.string.game_add_quick_launch)
    }
    val shortcutLabel = stringResource(R.string.game_add_desktop_shortcut)
    val renameLabel = stringResource(R.string.game_rename)
    val rowsContent: @Composable (Dp) -> Unit = { rowHeight ->
        ShelfBookManageEntry(
            number = 1,
            title = toggleLabel,
            height = rowHeight,
            enabled = enabled,
            onClick = { quickActions.toggleQuickLaunch() },
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = 1,
            modifier = quickActions.feedback.toggleShake()
                .inkOrder(3)
                .let { if (tabMotion != null && exiting) it.shelfBookTabItem(tabMotion, tabRole, 1) else it },
        )
        ShelfBookManageEntry(
            number = 2,
            title = shortcutLabel,
            height = rowHeight,
            enabled = enabled,
            onClick = { quickActions.addDesktopShortcut() },
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = 2,
            modifier = quickActions.feedback.shortcutStatus(quickActions.shortcutRequestInFlight)
                .inkOrder(4)
                .let { if (tabMotion != null && exiting) it.shelfBookTabItem(tabMotion, tabRole, 2) else it },
        )
        ShelfBookManageEntry(
            number = 3,
            title = renameLabel,
            height = rowHeight,
            enabled = enabled,
            onClick = {
                onOpenLeaf()
                quickActions.openRename()
            },
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = 3,
            modifier = Modifier
                .inkOrder(5)
                .let { if (tabMotion != null && exiting) it.shelfBookTabItem(tabMotion, tabRole, 3) else it },
        )
    }
    Column(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
        ShelfBookCoverCard(
            game = game,
            metrics = metrics,
            enabled = enabled,
            onClick = {
                onOpenLeaf()
                quickActions.pickCover()
            },
            tabMotion = tabMotion,
            tabRole = tabRole,
            modifier = quickActions.feedback.coverEntry()
                .inkOrder(2)
                .let { if (tabMotion != null && exiting) it.shelfBookTabItem(tabMotion, tabRole, 0) else it },
        )
        Box(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(0.6.dp)
                .inkOrder(2)
                .let { if (tabMotion == null) it else it.shelfBookTabRuleReveal(tabMotion, tabRole, 0) }
                .background(TextColor.copy(alpha = 0.16f)),
        )
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                rowsContent(BOOK_MANAGE_ROW_MIN_HEIGHT)
            }
        } else {
            val scroll = rememberScrollState()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                val rowHeight = (maxHeight / 3).coerceIn(BOOK_MANAGE_ROW_MIN_HEIGHT, metrics.manageRowMax)
                Column(Modifier.fillMaxSize().shelfBookScrollFade(scroll).verticalScroll(scroll)) {
                    rowsContent(rowHeight)
                }
            }
        }
        ShelfBookDeleteButton(
            onClick = {
                onOpenLeaf()
                quickActions.openDelete()
            },
            enabled = enabled,
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = 4,
            height = metrics.footerHeight,
            modifier = Modifier
                .padding(top = 8.dp)
                .inkOrder(6)
                .let { if (tabMotion == null) it else it.shelfBookTabFooterReveal(tabMotion, tabRole, 4) },
        )
    }
}

/** 书页内封面装帧卡：左侧画框缩略图与右下角换图角标，右侧宋体标题与指引，整行带荧光笔按压。 */
@Composable
private fun ShelfBookCoverCard(
    game: ScanGame,
    metrics: ShelfBookMetrics,
    enabled: Boolean,
    onClick: () -> Unit,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    modifier: Modifier = Modifier,
) {
    val cover by rememberCoverBitmap(game.coverUri)
    val interaction = remember { MutableInteractionSource() }
    val marker = ShelfBookMarker
    val frameShape = remember { RoundedCornerShape(4.dp) }
    val borderColor = TextColor.copy(alpha = 0.24f)
    val shadowColor = ShelfBookShade.copy(alpha = 0.18f)
    val editLabel = stringResource(R.string.game_edit_cover)
    val hintLabel = stringResource(R.string.game_book_cover_hint)
    val entering = tabMotion != null && tabRole == ShelfBookTabRole.Entering
    val editStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.Bold)
    val hintStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.shelfBookMarker(interaction, marker) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(metrics.coverW, metrics.coverH)
                .let { if (entering) it.shelfBookTabItem(tabMotion, tabRole, order = 0) else it }
                .drawBehind {
                    drawRoundRect(
                        color = shadowColor,
                        topLeft = Offset(1.5.dp.toPx(), 2.dp.toPx()),
                        size = size,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
                .clip(frameShape)
                .background(game.engine.coverColor())
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = borderColor,
                        size = size,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 0.8.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "Tyranor",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = BookSerif),
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CoverBadgeBackground),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_sheet_edit_cover),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(CoverBadgeText),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp),
        ) {
            if (tabMotion != null) {
                ShelfBookInkText(
                    text = editLabel,
                    style = editStyle,
                    color = TextColor,
                    tabMotion = tabMotion,
                    tabRole = tabRole,
                    order = 0,
                    subDelay = 0.02f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ShelfBookInkText(
                    text = hintLabel,
                    style = hintStyle,
                    color = SecondaryTextColor,
                    tabMotion = tabMotion,
                    tabRole = tabRole,
                    order = 0,
                    subDelay = 0.06f,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            } else {
                Text(
                    text = editLabel,
                    style = editStyle,
                    color = TextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hintLabel,
                    style = hintStyle,
                    color = SecondaryTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** 书页内管理条目：与右侧目录一致的两位章节序号 + 宋体标题 + 引导点（不带 › 箭头：箭头只留给还没改成书页的二级界面），按压时荧光笔划过，文案变化时原地模糊替换。 */
@Composable
private fun ShelfBookManageEntry(
    number: Int,
    title: String,
    height: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    revealOrder: Int,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val marker = ShelfBookMarker
    val leader = SecondaryTextColor.copy(alpha = 0.55f)
    val entering = tabMotion != null && tabRole == ShelfBookTabRole.Entering
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .then(if (enabled) Modifier.shelfBookMarker(interaction, marker) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            String.format(Locale.ROOT, "%02d", number),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif, fontWeight = FontWeight.SemiBold),
            color = SecondaryTextColor,
            modifier = Modifier
                .width(BOOK_ENTRY_NUMBER_WIDTH)
                .let { if (entering && tabMotion != null) it.shelfBookTabItem(tabMotion, tabRole, revealOrder) else it }
                .clearAndSetSemantics { },
        )
        ShelfBookLeaderLine(
            title = title,
            trailing = null,
            leader = leader,
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = revealOrder,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 扉页上的书名一栏：「06 / 09 · Kirikiri」（[showPosition] 为 false 时交由左页书眉承担序号，此处仅列引擎名）、
 * 书名（宋体加粗，最多两行）、原名（条目元数据里的标题，与书名不同时才有，一行）。整栏一起读屏。
 */
@Composable
private fun ShelfBookHalfTitle(
    game: ScanGame,
    stage: ShelfBookStage,
    showPosition: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val title = if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    val original = game.metadataTitle?.trim()?.takeIf { it.isNotEmpty() && it != title && it != game.title }
    val position = if (showPosition && stage.count > 0) stringResource(R.string.game_shelf_position, stage.index + 1, stage.count) else null
    val serif = BookSerif
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (position != null) {
                Row(Modifier.clearAndSetSemantics { contentDescription = position }, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        String.format(Locale.ROOT, "%02d", stage.index + 1),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        String.format(Locale.ROOT, " / %02d", stage.count),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = serif),
                        color = SecondaryTextColor,
                    )
                }
                Box(Modifier.padding(horizontal = 8.dp).size(3.dp).background(SecondaryTextColor.copy(alpha = 0.6f), CircleShape))
            }
            Text(
                game.engine.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = serif),
                color = SecondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = BookSerifBold, fontWeight = FontWeight.Bold),
            color = TextColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (original != null) {
            Text(
                original,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = serif),
                color = SecondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 目录里的一章与它在页上的状态：[present] 为 false 时这一章刚被撤掉、正在收起；[fresh] 为 true 时它是书翻开以后才插进来的。 */
internal class ShelfBookTocRow(val item: GameActionItem, val number: Int, val present: Boolean, val fresh: Boolean)

private val ShelfBookTocNoAction: () -> Unit = {}

/**
 * 目录的章会随游戏状态增删（外部引擎退出待确认、运行模块能力刷新、模块设置加载完……书翻开着时也可能出现或消失）：
 * 新出现的章在原位展开、下面的章被平滑推开；消失的章先收起再移除，下面的章跟着收上来，不会一下跳过去。
 * 书翻开时就有的章照常随墨迹浮现。只在组合里调用（单线程），状态变化经 [settled] 触发重组。
 */
internal class ShelfBookToc {
    private var initial: Set<String>? = null
    private var last: List<GameActionItem> = emptyList()
    private val leaving = LinkedHashMap<String, Pair<Int, GameActionItem>>()

    /** 收起完的章数（每收完一章加一）：读它的地方在章被移除后重新排列。 */
    var settled by mutableIntStateOf(0)
        private set

    fun rows(entries: List<GameActionItem>): List<ShelfBookTocRow> {
        val keys = entries.mapTo(HashSet()) { it.key }
        val first = initial ?: keys.also { initial = it }
        val previous = last.toMutableList()
        leaving.values.sortedBy { it.first }.forEach { (index, item) ->
            previous.add(index.coerceAtMost(previous.size), item)
        }
        previous.forEachIndexed { index, item ->
            if (item.key !in keys && item.key !in leaving) {
                leaving[item.key] = index to item
            }
        }
        keys.forEach { leaving.remove(it) }
        last = entries
        val rows = entries.toMutableList()
        leaving.values.sortedBy { it.first }.forEach { (index, item) ->
            rows.add(index.coerceAtMost(rows.size), item)
        }
        return rows.mapIndexed { index, item ->
            ShelfBookTocRow(item, index + 1, present = item.key in keys, fresh = item.key in keys && item.key !in first)
        }
    }

    fun gone(key: String) {
        if (leaving.remove(key) != null) settled++
    }
}

/** 目录里的一章随增删展开 / 收起；收起中的那一章不再接受点击。 */
@Composable
private fun ShelfBookTocEntry(
    row: ShelfBookTocRow,
    height: Dp,
    onGone: () -> Unit,
    onClick: () -> Unit,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    revealOrder: Int,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(!row.fresh) }
    if (!shown) LaunchedEffect(Unit) { shown = true }
    val present by rememberUpdatedState(row.present)
    val gone by rememberUpdatedState(onGone)
    val visible = shown && row.present
    // 刚插进来、还没来得及展开就又被撤掉的章：内容从没组合过，等不到收起结束的回调，直接交付
    val appeared = remember { booleanArrayOf(false) }
    if (visible) appeared[0] = true
    if (!row.present && !appeared[0]) SideEffect { gone() }
    ExpandVisibility(visible = visible, modifier = modifier) {
        DisposableEffect(Unit) { onDispose { if (!present) gone() } }
        ShelfBookEntry(
            number = row.number,
            item = row.item,
            height = height,
            onClick = { if (present) onClick() },
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = revealOrder,
        )
    }
}

/** 目录里的一章：「01  标题 ·········· 当前值 ›」；按下时一道荧光笔划过。章号、标题与当前值变化时原位模糊替换。 */
@Composable
private fun ShelfBookEntry(
    number: Int,
    item: GameActionItem,
    height: Dp,
    onClick: () -> Unit,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    revealOrder: Int,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val marker = ShelfBookMarker
    val leader = SecondaryTextColor.copy(alpha = 0.55f)
    val chevron = SecondaryTextColor
    val trailing = item.trailingText ?: item.summary
    val entering = tabMotion != null && tabRole == ShelfBookTabRole.Entering
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .shelfBookMarker(interaction, marker)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlurCrossfade(
            number,
            Modifier
                .width(BOOK_ENTRY_NUMBER_WIDTH)
                .let { if (entering && tabMotion != null) it.shelfBookTabItem(tabMotion, tabRole, revealOrder) else it }
                .clearAndSetSemantics { },
        ) { value ->
            Text(
                String.format(Locale.ROOT, "%02d", value),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif, fontWeight = FontWeight.SemiBold),
                color = SecondaryTextColor,
            )
        }
        ShelfBookLeaderLine(
            title = item.title,
            trailing = trailing,
            leader = leader,
            tabMotion = tabMotion,
            tabRole = tabRole,
            revealOrder = revealOrder,
            modifier = Modifier.weight(1f),
        )
        if (item.showArrow) {
            ShelfBookChevron(
                color = chevron,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .let { if (entering && tabMotion != null) it.shelfBookTabItem(tabMotion, tabRole, revealOrder, subDelay = 0.16f) else it },
            )
        }
    }
}

/** 标题与行尾值之间用引导点连起来（像书的目录）；行尾值最多占四成多宽，放不下就省略；切栏进场时引导点自左向右推进。 */
@Composable
private fun ShelfBookLeaderLine(
    title: String,
    trailing: String?,
    leader: Color,
    modifier: Modifier = Modifier,
    tabMotion: ShelfBookTabMotion? = null,
    tabRole: ShelfBookTabRole = ShelfBookTabRole.Entering,
    revealOrder: Int = 0,
) {
    var dotsStart by remember { mutableFloatStateOf(0f) }
    var dotsEnd by remember { mutableFloatStateOf(0f) }
    var dotsY by remember { mutableFloatStateOf(0f) }
    Layout(
        content = {
            BlurCrossfade(title) { text ->
                if (tabMotion != null && tabRole == ShelfBookTabRole.Entering) {
                    ShelfBookInkText(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif, fontWeight = FontWeight.Medium),
                        color = TextColor,
                        tabMotion = tabMotion,
                        tabRole = tabRole,
                        order = revealOrder,
                        subDelay = 0.02f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif, fontWeight = FontWeight.Medium),
                        color = TextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            BlurCrossfade(trailing, contentAlignment = Alignment.CenterEnd) { text ->
                if (text != null) {
                    if (tabMotion != null && tabRole == ShelfBookTabRole.Entering) {
                        ShelfBookInkText(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                            color = SecondaryTextColor,
                            tabMotion = tabMotion,
                            tabRole = tabRole,
                            order = revealOrder,
                            subDelay = 0.14f,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    } else {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif),
                            color = SecondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        },
        modifier = modifier.drawBehind {
            val leaderT = if (tabMotion != null && tabRole == ShelfBookTabRole.Entering &&
                tabMotion.enhanced && tabMotion.isTransitioning
            ) {
                shelfBookTabLeaderPhase(tabMotion.progress.value, revealOrder)
            } else {
                1f
            }
            if (leaderT <= 0.001f) return@drawBehind
            val gap = BOOK_LEADER_GAP.toPx()
            val radius = 0.9.dp.toPx()
            val span = (dotsEnd - dotsStart).coerceAtLeast(0f)
            val cutoff = dotsStart + span * leaderT
            val softEdge = (gap * 2.5f).coerceAtLeast(1f)
            var x = dotsStart + radius
            while (x <= dotsEnd - radius && x <= cutoff) {
                val dotAlpha = if (leaderT >= 0.999f) 1f else ((cutoff - x) / softEdge).coerceIn(0f, 1f)
                if (dotAlpha > 0.01f) {
                    drawCircle(leader.copy(alpha = leader.alpha * dotAlpha), radius, Offset(x, dotsY))
                }
                x += gap
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val pad = BOOK_LEADER_PAD.roundToPx()
        val trailingPlaceable = measurables[1].measure(
            Constraints(maxWidth = (width * BOOK_TRAILING_SHARE).toInt().coerceAtLeast(0)),
        )
        val trailingWidth = trailingPlaceable.width
        val titlePlaceable = measurables[0].measure(
            Constraints(maxWidth = (width - trailingWidth - pad * 2 - BOOK_LEADER_MIN.roundToPx()).coerceAtLeast(0)),
        )
        val height = maxOf(titlePlaceable.height, trailingPlaceable.height)
        layout(width, height) {
            val titleTop = (height - titlePlaceable.height) / 2
            titlePlaceable.place(0, titleTop)
            trailingPlaceable.place(width - trailingWidth, (height - trailingPlaceable.height) / 2)
            val baseline = titlePlaceable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified }
                ?: (titlePlaceable.height * 3 / 4)
            dotsStart = (titlePlaceable.width + pad).toFloat()
            dotsEnd = (width - trailingWidth - pad).toFloat()
            dotsY = (titleTop + baseline).toFloat() - 1.5f * density
        }
    }
}

/** 生成以 [inset] 内缩、外侧倒角为 [cut] 的对称 45° 斜切八边形路径。 */
private fun shelfBookChamferRectPath(width: Float, height: Float, inset: Float, cut: Float): Path {
    val l = inset
    val t = inset
    val r = (width - inset).coerceAtLeast(l + 2f)
    val b = (height - inset).coerceAtLeast(t + 2f)
    val c = cut.coerceAtMost(minOf(r - l, b - t) * 0.35f)
    return Path().apply {
        moveTo(l + c, t)
        lineTo(r - c, t)
        lineTo(r, t + c)
        lineTo(r, b - c)
        lineTo(r - c, b)
        lineTo(l + c, b)
        lineTo(l, b - c)
        lineTo(l, t + c)
        close()
    }
}

/**
 * 书页页脚精装宝石釉书扣按钮共用样式（启动游戏 / 删除游戏）：
 * 45° 斜切八边形 + 贴身软影微晕 + 精装亚麻布纹与中央径向缎面微光 + 单层克制外细框与极细顶缘发丝光 +
 * 紧凑居中图标与粗宋体标题。
 */
@Composable
private fun ShelfBookPrimaryFooterButton(
    label: String,
    style: ShelfBookFooterStyle,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    cloth: ImageBitmap? = null,
    enabled: Boolean = true,
    tabMotion: ShelfBookTabMotion? = null,
    tabRole: ShelfBookTabRole = ShelfBookTabRole.Entering,
    revealOrder: Int = 0,
    leadingIcon: @Composable BoxScope.() -> Unit,
) {
    val cut = minOf(8.dp, height * 0.18f)
    val buttonShape = remember(cut) { AbsoluteCutCornerShape(cut) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (enabled) Modifier.pressSquish() else Modifier)
            .drawBehind {
                val cutPx = cut.toPx().coerceAtMost(minOf(size.width, size.height) * 0.35f)
                val shadowDy1 = 1.dp.toPx()
                val shadowDy2 = 2.2.dp.toPx()
                if (style.ambientGlow.alpha > 0.005f && size.width > cutPx * 2f + 1f) {
                    val glowY = size.height + 0.8.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.25f to style.ambientGlow,
                            0.75f to style.ambientGlow,
                            1f to Color.Transparent,
                            startX = cutPx,
                            endX = size.width - cutPx,
                        ),
                        start = Offset(cutPx, glowY),
                        end = Offset(size.width - cutPx, glowY),
                        strokeWidth = 3.2.dp.toPx(),
                    )
                }
                if (style.shadowAlpha > 0.005f) {
                    val shadowPath = shelfBookChamferRectPath(size.width, size.height, inset = 0f, cut = cutPx)
                    withTransform({ translate(top = shadowDy2) }) {
                        drawPath(shadowPath, ShelfBookShade.copy(alpha = style.shadowAlpha * 0.45f))
                    }
                    withTransform({ translate(top = shadowDy1) }) {
                        drawPath(shadowPath, ShelfBookShade.copy(alpha = style.shadowAlpha * 0.72f))
                    }
                }
            }
            .clip(buttonShape)
            .drawWithCache {
                val cutPx = cut.toPx().coerceAtMost(minOf(size.width, size.height) * 0.35f)
                val fillBrush = Brush.verticalGradient(0f to style.fillTop, 1f to style.fillBottom)
                val clothBrush = if (cloth != null && style.clothAlpha > 0.005f) {
                    if (cloth === shelfBookClothGrainImage) shelfBookClothGrainBrush
                    else ShaderBrush(ImageShader(cloth, TileMode.Repeated, TileMode.Repeated))
                } else {
                    null
                }
                val sheenCenter = Offset(size.width * 0.5f, size.height * 0.08f)
                val sheenRadius = maxOf(size.width * 0.60f, size.height * 2.2f, 1f)
                val sheenBrush = Brush.radialGradient(
                    colors = listOf(style.radialSheen, Color.Transparent),
                    center = sheenCenter,
                    radius = sheenRadius,
                )
                val outerPath = shelfBookChamferRectPath(size.width, size.height, inset = 0.42.dp.toPx(), cut = cutPx)
                val outerStroke = Stroke(width = 0.85.dp.toPx())
                val specY = 0.75.dp.toPx()
                val specStroke = 0.8.dp.toPx()
                val hasSpan = size.width > cutPx * 2f + 1f
                val specBrush = if (hasSpan) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.16f to style.topSpecular,
                        0.84f to style.topSpecular,
                        1f to Color.Transparent,
                        startX = cutPx,
                        endX = size.width - cutPx,
                    )
                } else {
                    null
                }
                onDrawBehind {
                    drawRect(fillBrush)
                    if (clothBrush != null) drawRect(clothBrush, alpha = style.clothAlpha)
                    drawRect(sheenBrush)
                    if (specBrush != null) {
                        drawLine(
                            brush = specBrush,
                            start = Offset(cutPx, specY),
                            end = Offset(size.width - cutPx, specY),
                            strokeWidth = specStroke,
                        )
                    }
                    drawPath(outerPath, color = style.outerBorder, style = outerStroke)
                }
            }
            .clickable(interactionSource = null, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center, content = leadingIcon)
            val labelStyle = MaterialTheme.typography.titleMedium.copy(
                fontFamily = BookSerifBold,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            val labelModifier = Modifier.weight(1f, fill = false)
            if (tabMotion != null && tabRole == ShelfBookTabRole.Entering) {
                ShelfBookInkText(
                    text = label,
                    style = labelStyle,
                    color = style.content,
                    tabMotion = tabMotion,
                    tabRole = tabRole,
                    order = revealOrder,
                    subDelay = 0.04f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = labelModifier,
                )
            } else {
                Text(
                    label,
                    style = labelStyle,
                    color = style.content,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = labelModifier,
                )
            }
        }
    }
}

/** 页脚：「▶ 启动游戏」。 */
@Composable
private fun ShelfBookLaunch(
    cloth: ImageBitmap,
    onClick: () -> Unit,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    revealOrder: Int,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.game_launch_action)
    val style = shelfBookLaunchStyle(MaterialTheme.colorScheme.primary)
    ShelfBookPrimaryFooterButton(
        label = label,
        style = style,
        onClick = onClick,
        height = height,
        cloth = cloth,
        tabMotion = tabMotion,
        tabRole = tabRole,
        revealOrder = revealOrder,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val h = size.minDimension * 0.66f
                    val w = h * 0.88f
                    val cx = size.width / 2f + w * 0.10f
                    val cy = size.height / 2f
                    val glyph = Path().apply {
                        moveTo(cx - w / 2f, cy - h / 2f)
                        lineTo(cx + w / 2f, cy)
                        lineTo(cx - w / 2f, cy + h / 2f)
                        close()
                    }
                    val round = Stroke(width = size.minDimension * 0.08f, join = StrokeJoin.Round)
                    onDrawBehind {
                        drawPath(glyph, style.content)
                        drawPath(glyph, style.content, style = round)
                    }
                },
        )
    }
}

/** 管理分栏页脚：「删除游戏」——与「启动游戏」对称，随四套外观主题适配克制朱印双线细框配色。 */
@Composable
private fun ShelfBookDeleteButton(
    onClick: () -> Unit,
    tabMotion: ShelfBookTabMotion?,
    tabRole: ShelfBookTabRole,
    revealOrder: Int,
    height: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val style = ShelfBookDeleteStyle
    ShelfBookPrimaryFooterButton(
        label = stringResource(R.string.game_delete_title),
        style = style,
        onClick = onClick,
        height = height,
        enabled = enabled,
        tabMotion = tabMotion,
        tabRole = tabRole,
        revealOrder = revealOrder,
        modifier = modifier,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_sheet_delete),
            contentDescription = null,
            colorFilter = ColorFilter.tint(style.content),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 目录排不下、可以滚动时，被截住的那一边（上 / 下）淡出一小段，提示后面还有章；排得下时什么都不画、不开离屏层。
 */
private fun Modifier.shelfBookScrollFade(scroll: ScrollState): Modifier =
    graphicsLayer {
        compositingStrategy = if (scroll.canScrollBackward || scroll.canScrollForward) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
    }.drawWithCache {
        if (size.width <= 0f || size.height <= 0f) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val fadePx = BOOK_TOC_FADE.toPx()
        val topFadeBrush = Brush.verticalGradient(
            colors = listOf(Color.Black, Color.Transparent),
            startY = 0f,
            endY = fadePx,
        )
        val bottomFadeBrush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black),
            startY = size.height - fadePx,
            endY = size.height,
        )
        onDrawWithContent {
            drawContent()
            val bandH = fadePx.coerceAtMost(size.height)
            if (bandH <= 0f) return@onDrawWithContent
            if (scroll.canScrollBackward) {
                drawRect(
                    brush = topFadeBrush,
                    topLeft = Offset.Zero,
                    size = Size(size.width, bandH),
                    blendMode = BlendMode.DstOut,
                )
            }
            if (scroll.canScrollForward) {
                drawRect(
                    brush = bottomFadeBrush,
                    topLeft = Offset(0f, size.height - bandH),
                    size = Size(size.width, bandH),
                    blendMode = BlendMode.DstOut,
                )
            }
        }
    }

private val shelfBookClothGrainImage: ImageBitmap by lazy { shelfBookClothGrain() }
private val shelfBookClothGrainBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(shelfBookClothGrainImage, TileMode.Repeated, TileMode.Repeated))
}

/**
 * 精装布纹硬壳纹理（64×64 周期无缝）：正交经纬织线叠加微纤维颗粒，
 * 让封板与内折飘口呈现出有触感的亚麻/布面硬壳，而非单调纯色块。
 */
@Composable
private fun rememberShelfBookClothGrain(): ImageBitmap = shelfBookClothGrainImage

private fun shelfBookClothGrain(): ImageBitmap {
    val size = BOOK_CLOTH_GRAIN_SIZE
    val random = java.util.Random(BOOK_CLOTH_SEED)
    val lightBase = ShelfBookClothWeaveLight.toArgb()
    val darkBase = ShelfBookClothWeaveDark.toArgb()
    val lightR = (lightBase shr 16) and 0xFF
    val lightG = (lightBase shr 8) and 0xFF
    val lightB = lightBase and 0xFF
    val darkR = (darkBase shr 16) and 0xFF
    val darkG = (darkBase shr 8) and 0xFF
    val darkB = darkBase and 0xFF
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val wy = sin(y * 2.0 * PI / 4.0).toFloat()
        for (x in 0 until size) {
            val wx = sin(x * 2.0 * PI / 4.0).toFloat()
            val weave = (wx * 0.55f + wy * 0.45f) * 0.5f + ((x xor y) and 1) * 0.14f - 0.07f
            val grit = (random.nextFloat() - 0.5f) * 0.36f
            val v = (weave + grit).coerceIn(-1f, 1f)
            if (v >= 0f) {
                val a = (v * 20f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (a shl 24) or (lightR shl 16) or (lightG shl 8) or lightB
            } else {
                val a = ((-v) * 28f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (a shl 24) or (darkR shl 16) or (darkG shl 8) or darkB
            }
        }
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * 精装硬壳封板质感：布纹平铺 + 外缘立体斜切边（上/外迎光、底缘背光）+
 * 飘口内侧微压印烫线框 + 切角包角亮暗光泽。
 */
private fun Modifier.shelfBookHardcoverBoard(
    shape: Shape,
    cloth: ImageBitmap,
    isLeftBoard: Boolean,
    cut: () -> Dp,
    frameStartOffset: (Size) -> Float = { 0f },
): Modifier {
    val foil = ShelfBookFoilAccent
    return drawWithCache {
        val clothBrush = if (cloth === shelfBookClothGrainImage) {
            shelfBookClothGrainBrush
        } else {
            ShaderBrush(ImageShader(cloth, TileMode.Repeated, TileMode.Repeated))
        }
        val rimBrush = Brush.verticalGradient(
            0f to ShelfBookLight.copy(alpha = 0.28f),
            0.35f to ShelfBookLight.copy(alpha = 0.06f),
            1f to ShelfBookShade.copy(alpha = 0.46f),
        )
        val bevelStroke = Stroke(width = BOOK_BOARD_EDGE.toPx())
        // 压印内框（内缩至飘口中央，形成硬壳内折折痕与微压印光感）
        val inset = (BOOK_LIP * 0.52f).toPx()
        val minInnerCut = 1.5.dp.toPx()
        val foilStroke = Stroke(width = 0.65.dp.toPx())
        val innerPath = Path()
        var cachedOutline: Outline? = null
        var lastCutPx = Float.NaN
        var lastStartOffset = Float.NaN
        onDrawWithContent {
            val cutPx = cut().toPx().coerceAtMost(minOf(size.width, size.height) / 3f)
            val startOffset = frameStartOffset(size)
            val cached = cachedOutline
            val outline = if (cached != null && cutPx == lastCutPx && startOffset == lastStartOffset) {
                cached
            } else {
                lastCutPx = cutPx
                lastStartOffset = startOffset
                val visibleWidth = (size.width - startOffset).coerceAtLeast(1f)
                val innerCut = (cutPx - inset * 0.42f).coerceIn(minInnerCut, (visibleWidth / 3f).coerceAtLeast(minInnerCut))
                fillShelfBookChamferFramePath(
                    size = size,
                    inset = inset,
                    cut = innerCut,
                    gutterAtStart = !isLeftBoard,
                    startOffset = startOffset,
                    outPath = innerPath,
                )
                shape.createOutline(size, layoutDirection, this).also { cachedOutline = it }
            }
            drawOutline(outline, clothBrush)
            drawOutline(outline, rimBrush, style = bevelStroke)
            drawPath(innerPath, foil, style = foilStroke)
            drawContent()
        }
    }
}

/** 填充带有外侧双斜切角的硬壳内框路径（[gutterAtStart] = true 时切角在右侧，否则在左侧）。 */
private fun fillShelfBookChamferFramePath(
    size: Size,
    inset: Float,
    cut: Float,
    gutterAtStart: Boolean,
    startOffset: Float = 0f,
    outPath: Path,
) {
    val l = startOffset + inset
    val t = inset
    val r = (size.width - inset).coerceAtLeast(l + 1f)
    val b = (size.height - inset).coerceAtLeast(t + 1f)
    outPath.rewind()
    if (gutterAtStart) {
        outPath.moveTo(0f, t)
        outPath.lineTo(r - cut, t)
        outPath.lineTo(r, t + cut)
        outPath.lineTo(r, b - cut)
        outPath.lineTo(r - cut, b)
        outPath.lineTo(0f, b)
    } else {
        outPath.moveTo(size.width, t)
        outPath.lineTo(l + cut, t)
        outPath.lineTo(l, t + cut)
        outPath.lineTo(l, b - cut)
        outPath.lineTo(l + cut, b)
        outPath.lineTo(size.width, b)
    }
}

/**
 * 物理书芯台面（Page Block Edges）：独立置于所有活动翻页底下。
 * 1. 向封板飘口投射贴身软影；
 * 2. 沿外侧缘与下缘画出完整带倒角的整摞书页厚边（右页靠右、双页左页靠左）；
 * 3. 在切口台面内绘制多层细密、明暗交替的书纸侧面断面线。
 */
@Composable
private fun ShelfBookPageBlockEdges(
    pageShape: AbsoluteCutCornerShape,
    gutterAtStart: Boolean = true,
    sideThickness: Dp = BOOK_STACK_RIGHT,
    lines: Int = BOOK_STACK_LINES,
    modifier: Modifier = Modifier,
) {
    val stackSurface = ShelfBookStackSurface
    val stackEdge = ShelfBookPageEdge
    val stackHighlight = ShelfBookStackHighlight
    val shade = ShelfBookShade
    val layerPaths = remember(lines) { Array(lines) { Path() } }
    val outerRim = remember { Path() }
    Spacer(
        modifier
            .fillMaxSize()
            .drawWithCache {
                if (size.width <= 0f || size.height <= 0f) {
                    return@drawWithCache onDrawBehind { }
                }
                val outline = pageShape.createOutline(size, layoutDirection, this)
                val shadowDx = if (gutterAtStart) 2.dp.toPx() else -2.dp.toPx()
                val shadowDy = 2.5.dp.toPx()
                val shadowColor = shade.copy(alpha = 0.34f)
                val rimColor = shade.copy(alpha = 0.24f)
                val stackSide = sideThickness.toPx()
                val stackB = BOOK_STACK_BOTTOM.toPx()
                val maxCut = (minOf(size.width - stackSide, size.height - stackB) / 3f).coerceAtLeast(0f)
                val cut = pageShape.cut(size, this).coerceIn(0f, maxCut)
                val stroke = Stroke(width = 0.65.dp.toPx())
                val rimStroke = Stroke(width = 0.85.dp.toPx())
                val layerColors = Array(lines) { idx ->
                    val i = idx + 1
                    val t = i.toFloat() / lines
                    val h = (size.height - stackB * (1f - t)).coerceAtLeast(0f)
                    val clampedCut = cut.coerceAtMost(h)
                    val path = layerPaths[idx]
                    path.rewind()
                    if (gutterAtStart) {
                        val w = (size.width - stackSide * (1f - t)).coerceAtLeast(0f)
                        val cornerCut = clampedCut.coerceAtMost(w)
                        path.moveTo((w - cornerCut).coerceAtLeast(0f), 0f)
                        path.lineTo(w, clampedCut)
                        path.lineTo(w, (h - clampedCut).coerceAtLeast(clampedCut))
                        path.lineTo((w - cornerCut).coerceAtLeast(0f), h)
                        path.lineTo(0f, h)
                    } else {
                        val leftEdge = (stackSide * (1f - t)).coerceAtMost(size.width)
                        path.moveTo((leftEdge + clampedCut).coerceAtMost(size.width), 0f)
                        path.lineTo(leftEdge, clampedCut)
                        path.lineTo(leftEdge, (h - clampedCut).coerceAtLeast(clampedCut))
                        path.lineTo((leftEdge + clampedCut).coerceAtMost(size.width), h)
                        path.lineTo(size.width, h)
                    }
                    if (i % 2 == 1) {
                        stackEdge.copy(alpha = stackEdge.alpha * (0.75f + 0.45f * t))
                    } else {
                        stackHighlight.copy(alpha = stackHighlight.alpha * (0.6f - 0.35f * t))
                    }
                }
                // 外缘与底边最外一道沉边勾勒出书芯厚度收尾
                fillShelfBookPageRim(size, cut, gutterAtStart = gutterAtStart, outPath = outerRim)
                onDrawBehind {
                    withTransform({ translate(left = shadowDx, top = shadowDy) }) {
                        drawOutline(outline, shadowColor)
                    }
                    drawOutline(outline, stackSurface)
                    // 越靠下越沉暗：从顶页底面往下（t: 0..1）画多层页缘切口
                    for (idx in layerPaths.indices) {
                        drawPath(layerPaths[idx], layerColors[idx], style = stroke)
                    }
                    drawPath(outerRim, rimColor, style = rimStroke)
                }
            },
    )
}

/**
 * 中缝装订结构覆盖层：仅在封面翻过一半后柔和显现。
 * 在书脊接缝（`x = 0`）绘制：
 * - 上下两端的「堵头布（Headband / Tailband）」织纹小拱枕（带主题色丝线斜纹）；
 * - 中缝深沟折痕与对称柔影；
 * - 三道克制的锁线装订（Smyth-sewn）缝线压痕。
 */
@Composable
private fun ShelfBookBindingOverlay(
    motion: ShelfBookMotion,
    morph: ShelfBookLayoutMorph,
    tint: Color,
    accent: Color,
) {
    val headbandBase = ShelfBookHeadbandBase
    val headbandStripe = ShelfBookHeadbandStripe
    val shade = ShelfBookShade
    val light = ShelfBookLight
    val bandAccent = remember(tint, accent) { lerp(tint, accent, 0.45f) }
    val bandPaths = remember { Array(2) { Path() } }
    Spacer(
        Modifier
            .offset(x = -BOOK_BINDING_OVERLAY_WIDTH / 2)
            .width(BOOK_BINDING_OVERLAY_WIDTH)
            .fillMaxHeight()
            .shelfBookBindingOverlay(motion, morph)
            .drawWithCache {
                val cx = size.width / 2f
                val lipPx = BOOK_LIP.toPx()
                val topY = lipPx
                val bottomY = (size.height - lipPx - BOOK_STACK_BOTTOM.toPx()).coerceAtLeast(topY + 1f)
                val span = bottomY - topY
                val gutterW = 11.dp.toPx()
                val gutterHalfW = gutterW / 2f
                val gutterBrush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.35f to shade.copy(alpha = 0.08f),
                    0.5f to shade.copy(alpha = 0.38f),
                    0.65f to shade.copy(alpha = 0.08f),
                    1f to Color.Transparent,
                    startX = cx - gutterHalfW,
                    endX = cx + gutterHalfW,
                )
                val gutterTopLeft = Offset(cx - gutterHalfW, topY)
                val gutterSize = Size(gutterW, span)
                val gutterDarkColor = shade.copy(alpha = 0.48f)
                val gutterDarkStroke = 1.1.dp.toPx()
                val gutterLightColor = light.copy(alpha = 0.14f)
                val gutterLightX = cx + gutterDarkStroke
                val gutterLightStroke = 0.6.dp.toPx()
                val stitchLen = 8.dp.toPx()
                val stitchHalf = stitchLen / 2f
                val stitchDotRadius = 1.dp.toPx()
                val stitchDotColor = shade.copy(alpha = 0.5f)
                val stitchThreadColor = headbandStripe.copy(alpha = 0.55f)
                val stitchThreadStroke = 1.dp.toPx()
                val stitchCenterYs = floatArrayOf(topY + span * 0.24f, topY + span * 0.50f, topY + span * 0.76f)
                val hbW = BOOK_HEADBAND_WIDTH.toPx()
                val hbH = BOOK_HEADBAND_HEIGHT.toPx()
                val radius = CornerRadius(hbH / 2f, hbH / 2f)
                val bandShadowDy = 1.dp.toPx()
                val bandShadowColor = shade.copy(alpha = 0.34f)
                val bandLeft = cx - hbW / 2f
                val bandSize = Size(hbW, hbH)
                val bandShadeLeft = shade.copy(alpha = 0.28f)
                val bandShadeMid = light.copy(alpha = 0.22f)
                val bandShadeBrush = Brush.horizontalGradient(
                    0f to bandShadeLeft,
                    0.5f to bandShadeMid,
                    1f to bandShadeLeft,
                    startX = bandLeft,
                    endX = bandLeft + hbW,
                )
                val stripeStep = 3.2.dp.toPx()
                val stripeStroke = 1.4.dp.toPx()
                val bandAccentStripe = bandAccent.copy(alpha = 0.78f)
                val stripeCount = ((hbW + 2f * hbH) / stripeStep).toInt() + 1
                val stripeStartXs = FloatArray(stripeCount)
                var sx = bandLeft - hbH
                var actualStripes = 0
                while (sx < bandLeft + hbW + hbH && actualStripes < stripeCount) {
                    stripeStartXs[actualStripes++] = sx
                    sx += stripeStep
                }
                val bandYs = floatArrayOf(topY - hbH * 0.45f, bottomY - hbH * 0.55f)
                for (bandIdx in bandYs.indices) {
                    val by = bandYs[bandIdx]
                    bandPaths[bandIdx].rewind()
                    bandPaths[bandIdx].addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = bandLeft,
                            top = by,
                            right = bandLeft + hbW,
                            bottom = by + hbH,
                            cornerRadius = radius,
                        ),
                    )
                }
                onDrawBehind {
                    // 中缝折痕左右柔影 + 核心深线
                    drawRect(gutterBrush, topLeft = gutterTopLeft, size = gutterSize)
                    drawLine(gutterDarkColor, Offset(cx, topY), Offset(cx, bottomY), strokeWidth = gutterDarkStroke)
                    drawLine(gutterLightColor, Offset(gutterLightX, topY), Offset(gutterLightX, bottomY), strokeWidth = gutterLightStroke)
                    // 三道锁线装订压痕
                    for (sy in stitchCenterYs) {
                        val topDot = Offset(cx, sy - stitchHalf)
                        val bottomDot = Offset(cx, sy + stitchHalf)
                        drawCircle(stitchDotColor, radius = stitchDotRadius, center = topDot)
                        drawCircle(stitchDotColor, radius = stitchDotRadius, center = bottomDot)
                        drawLine(stitchThreadColor, topDot, bottomDot, strokeWidth = stitchThreadStroke, cap = StrokeCap.Round)
                    }
                    // 顶底两枚堵头布（Headband / Tailband）
                    for (bandIdx in bandYs.indices) {
                        val by = bandYs[bandIdx]
                        drawRoundRect(
                            color = bandShadowColor,
                            topLeft = Offset(bandLeft, by + bandShadowDy),
                            size = bandSize,
                            cornerRadius = radius,
                        )
                        val bandTopLeft = Offset(bandLeft, by)
                        clipPath(bandPaths[bandIdx]) {
                            drawRect(headbandBase, bandTopLeft, bandSize)
                            drawRect(bandShadeBrush, bandTopLeft, bandSize)
                            for (i in 0 until actualStripes) {
                                val startX = stripeStartXs[i]
                                val stripe = if (i % 2 == 0) bandAccentStripe else headbandStripe
                                drawLine(
                                    color = stripe,
                                    start = Offset(startX, by + hbH),
                                    end = Offset(startX + hbH, by),
                                    strokeWidth = stripeStroke,
                                )
                            }
                        }
                    }
                }
            },
    )
}

/** 立体织物丝带书签：带纸面软影、顶部折入书脊暗影、罗纹织物光泽与两侧锁边压纹。 */
@Composable
private fun ShelfBookRibbon(color: Color, modifier: Modifier = Modifier) {
    val shade = ShelfBookShade
    val light = ShelfBookLight
    val edge = ShelfBookRibbonEdge
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val notch = w * 0.45f
            fun ribbonPath(dx: Float, dy: Float) = Path().apply {
                moveTo(dx, dy)
                lineTo(w + dx, dy)
                lineTo(w + dx, h + dy)
                lineTo(w / 2f + dx, h - notch + dy)
                lineTo(dx, h + dy)
                close()
            }
            val shadowPathFar = ribbonPath(1.8.dp.toPx(), 2.2.dp.toPx())
            val shadowColorFar = shade.copy(alpha = 0.24f)
            val shadowPathNear = ribbonPath(0.9.dp.toPx(), 1.1.dp.toPx())
            val shadowColorNear = shade.copy(alpha = 0.16f)
            val body = ribbonPath(0f, 0f)
            val horizSheenBrush = Brush.horizontalGradient(
                0f to shade.copy(alpha = 0.16f),
                0.38f to light.copy(alpha = 0.14f),
                0.68f to Color.Transparent,
                1f to shade.copy(alpha = 0.22f),
            )
            val foldShadeBrush = Brush.verticalGradient(
                0f to shade.copy(alpha = 0.30f),
                0.16f to Color.Transparent,
                startY = 0f,
                endY = h,
            )
            val ribGap = 2.4.dp.toPx().coerceAtLeast(0.5f)
            val ribCount = (h / ribGap).toInt()
            val ribYs = FloatArray(ribCount) { idx -> (idx + 1) * ribGap }
            val ribColor = shade.copy(alpha = 0.06f)
            val ribStroke = 0.6.dp.toPx()
            val seam = 1.8.dp.toPx()
            val seamEndY = h - notch * 0.35f
            val seamStroke = 0.65.dp.toPx()
            val centerCreaseColor = ShelfSpineText.copy(alpha = 0.32f)
            val centerCreaseEndY = h - notch - 2f * density
            val centerCreaseStroke = 0.9.dp.toPx()
            onDrawBehind {
                // 投射在右页上的渐近贴身软影
                drawPath(shadowPathFar, shadowColorFar)
                drawPath(shadowPathNear, shadowColorNear)
                drawPath(body, color)
                clipPath(body) {
                    // 横向微光泽（中间亮、边缘略暗）+ 顶部折出书页的微暗压痕
                    drawRect(horizSheenBrush)
                    drawRect(foldShadeBrush)
                    // 横向极细罗纹织痕
                    for (ry in ribYs) {
                        if (ry < h) drawLine(ribColor, Offset(0f, ry), Offset(w, ry), strokeWidth = ribStroke)
                    }
                    // 左右两侧锁边缝线 + 中线压纹
                    drawLine(edge, Offset(seam, 0f), Offset(seam, seamEndY), strokeWidth = seamStroke)
                    drawLine(edge, Offset(w - seam, 0f), Offset(w - seam, seamEndY), strokeWidth = seamStroke)
                    drawLine(
                        centerCreaseColor,
                        Offset(w / 2f, 0f),
                        Offset(w / 2f, centerCreaseEndY),
                        strokeWidth = centerCreaseStroke,
                    )
                }
            }
        },
    )
}

/** 书在屏幕上的摆放：书页的高宽比、手机竖屏左页至少露出多少、两侧与上下的留白、书页最宽多少、可用高度的下限。 */
private const val BOOK_PAGE_ASPECT = 1.5f

/** 很矮的窗口里书页至少想要这么宽（书名栏、三格数据与目录不至于挤成省略号）；为此书页最矮胖到正方形。 */
private val BOOK_COMFORT_PAGE_WIDTH = 280.dp
private val BOOK_SPREAD_MIN_PAGE_WIDTH = 260.dp
private val BOOK_TABLET_SPREAD_MIN_PAGE_WIDTH = 220.dp
private val BOOK_LEFT_PEEK = 22.dp
private val BOOK_TURNED_CLIP_MIN_WIDTH = 12.dp
private val BOOK_CENTER_TRIGGER_SLACK = 4.dp
private val BOOK_SIDE_MARGIN = 10.dp
private val BOOK_V_MARGIN = 16.dp
private val BOOK_SPACIOUS_WIDTH = 720.dp
private val BOOK_SPACIOUS_HEIGHT = 520.dp
private val BOOK_SPACIOUS_SIDE_MARGIN = 20.dp
private val BOOK_SPACIOUS_V_MARGIN = 22.dp
private val BOOK_MAX_PAGE_WIDTH = 440.dp
private val BOOK_MIN_HEIGHT = 120.dp

/** 往右拖多远（× 书页宽）封面合到底。 */
private const val BOOK_DRAG_SPAN = 1.1f

/** 封板：比书页大出的一圈飘口、颜色（封面主色压暗多少）、书飞起来时的投影高度、窄方屏左折翼外角倒角。 */
private val BOOK_LIP = 6.dp
private val BOOK_PEEK_CORNER = 3.dp
private val BOOK_PEEK_CORNER_INNER = 1.5.dp
private const val BOOK_BOARD_DARKEN = 0.45f
private val BOOK_ELEVATION = 22.dp

/** 书芯侧面切口台：右 / 左页厚度与切口层数、底缘厚度；外缘微暖沉降宽度、装订线弯曲宽度、倒角减量。 */
private val BOOK_STACK_RIGHT = 5.5.dp
private val BOOK_LEFT_STACK = 3.5.dp
private val BOOK_STACK_BOTTOM = 4.5.dp
private const val BOOK_STACK_LINES = 6
private const val BOOK_LEFT_STACK_LINES = 4
private val BOOK_EDGE_VIGNETTE = 26.dp
private val BOOK_GUTTER_CURL = 36.dp
private val BOOK_PAGE_CUT_SLACK = 1.5.dp

/** 封板边线宽与布纹采样尺寸。 */
private val BOOK_BOARD_EDGE = 1.dp
private const val BOOK_CLOTH_GRAIN_SIZE = 64
private const val BOOK_CLOTH_SEED = 20261122L

/** 中缝装订覆盖层宽度、堵头布宽与高。 */
private val BOOK_BINDING_OVERLAY_WIDTH = 24.dp
private val BOOK_HEADBAND_WIDTH = 16.dp
private val BOOK_HEADBAND_HEIGHT = 4.5.dp

/** 纸纹的尺寸（像素）、短纤维根数与随机种子（固定，每次画出来一样）。 */
private const val BOOK_GRAIN_SIZE = 128
private const val BOOK_GRAIN_FIBERS = 220
private const val BOOK_GRAIN_SEED = 20261120L

/** 衬纸用的封面柔焦图尺寸（像素）。 */
private const val BOOK_WASH_WIDTH = 12
private const val BOOK_WASH_HEIGHT = 16

/** 衬纸：封面主色向纸基色融合的比例（深色 / 浅色外观），封面柔焦色印上去的浓淡。 */
private const val BOOK_ENDPAPER_DARKEN = 0.68f
private const val BOOK_ENDPAPER_LIGHTEN = 0.72f
private const val BOOK_ENDPAPER_WASH_DARK = 0.16f
private const val BOOK_ENDPAPER_WASH_LIGHT = 0.24f

/** 目录行最矮行高与外缘线数量。 */
private val BOOK_ENTRY_MIN_HEIGHT = 30.dp
private const val BOOK_EDGE_LINES = 4

/** 分栏栏高度、双线收束槽高、「管理」操作条目最矮行高与藏书封面版画高宽比。 */
private val BOOK_TAB_BAR_HEIGHT = 34.dp
private val BOOK_DOUBLE_RULE_SLOT_HEIGHT = 4.dp
private val BOOK_MANAGE_ROW_MIN_HEIGHT = 38.dp
private const val BOOK_COVER_PLATE_ASPECT = 4f / 3f

/** 目录按一页排几章来定行高；排不下时滚动区两头淡出多高。 */
private const val BOOK_TOC_CAPACITY = 6

/** 上面那一页翻回到只剩这么一点（× 整页翻开）时，下面那一页已经淡出。 */
private const val BOOK_UNDER_FADE = 0.08f

/** 书页正文区比这矮时（极矮分屏）整页一起滚动；常规横屏双页保留固定底栏以保持左右底部按钮齐平。 */
private val BOOK_PAGE_COMPACT_HEIGHT = 320.dp
private val BOOK_TOC_FADE = 18.dp
private val BOOK_ENTRY_NUMBER_WIDTH = 30.dp
private val BOOK_LEADER_GAP = 5.dp
private val BOOK_LEADER_PAD = 8.dp
private val BOOK_LEADER_MIN = 20.dp
private const val BOOK_TRAILING_SHARE = 0.42f

/** 翻回上一页那一行的最小高度；章节扉页上那一小道线的长度。 */
private val BOOK_BACK_HEIGHT = 28.dp

/**
 * 书里印出来的字用宋体（衬线体）：目录、章名、题签、章节扉页——一眼是印在纸上的字，而不是界面文字。字号仍只用
 * titleMedium / bodyMedium 两档；二级页里调用方传进来的弹窗内容（标题、正文、按钮）保持界面字体。
 * 有的系统（如荣耀）把通用的 serif 也换成了自家的无衬线主题字体，所以直接载入系统自带的 Noto Serif CJK
 * （按界面语言取简 / 繁 / 日 / 韩字形，与系统字体配置里的序号一致）；没有这个文件时退回 [FontFamily.Serif]。
 */
internal val BookSerif: FontFamily
    @Composable
    @ReadOnlyComposable
    get() = bookSerif(LocalLocale.current.platformLocale, bold = false)

/** 加粗的宋体（书名、章名、数据）：系统只带常规字重，由系统合成粗体。 */
internal val BookSerifBold: FontFamily
    @Composable
    @ReadOnlyComposable
    get() = bookSerif(LocalLocale.current.platformLocale, bold = true)

private fun bookSerif(locale: Locale, bold: Boolean): FontFamily {
    val index = bookSerifIndex(locale)
    return bookSerifFamilies.computeIfAbsent(index * 2 + if (bold) 1 else 0) {
        // 粗体由同一份常规字面派生，不再读第二次文件
        val regular = bookSerifTypeface(index)
        val typeface = if (bold && regular != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(regular, BOOK_SERIF_BOLD_WEIGHT, false) else Typeface.create(regular, Typeface.BOLD)
        } else {
            regular
        }
        typeface?.let { FontFamily(it) } ?: FontFamily.Serif
    }
}

/** 按界面语言取 Noto Serif CJK 集合里的字形：日 0、韩 1、简 2、繁 3（与系统字体配置的序号一致）。 */
private fun bookSerifIndex(locale: Locale): Int = when {
    locale.language == "ja" -> 0
    locale.language == "ko" -> 1
    locale.language == "zh" && (locale.script == "Hant" || locale.country in BOOK_SERIF_HANT_REGIONS) -> 3
    else -> 2
}

/** 读一次系统的 Noto Serif CJK（这一字形的常规字面）；没有这个文件或读不出来时为 null。 */
private fun bookSerifTypeface(index: Int): Typeface? = bookSerifTypefaces.computeIfAbsent(index) {
    val file = File(BOOK_SERIF_FILE)
    val typeface = if (file.isFile) runCatching { Typeface.Builder(file).setTtcIndex(index).build() }.getOrNull() else null
    typeface ?: BOOK_SERIF_MISSING
}.takeIf { it !== BOOK_SERIF_MISSING }

/**
 * 提前在后台读好宋体：读系统字体集合要几毫秒，放在书第一次组合里会落在起飞的第一帧上。「游戏盒」书架出现时调用
 * （任何线程都可以；只读文件、不碰界面）。
 */
internal fun warmShelfBookSerif(locale: Locale) {
    bookSerif(locale, bold = false)
    bookSerif(locale, bold = true)
}

/** 载入过的宋体（按字形序号与粗细）与读过的字面（按字形序号），只载一次。 */
private val bookSerifFamilies = ConcurrentHashMap<Int, FontFamily>()
private val bookSerifTypefaces = ConcurrentHashMap<Int, Typeface>()

/** 读不到字体文件时记下的占位（ConcurrentHashMap 不收 null）；用到时才取，不在类加载时碰系统字体。 */
private val BOOK_SERIF_MISSING: Typeface by lazy { Typeface.DEFAULT }

/** 系统自带的 Noto Serif CJK（AOSP 标准位置）与用繁体字形的地区。 */
private const val BOOK_SERIF_FILE = "/system/fonts/NotoSerifCJK-Regular.ttc"
private val BOOK_SERIF_HANT_REGIONS = setOf("TW", "HK", "MO")
private const val BOOK_SERIF_BOLD_WEIGHT = 700
