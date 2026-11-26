package com.tyranor.next.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GameLibraryConditions
import com.tyranor.next.core.game.model.GameTitleTags
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBorder
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.PageGrey
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.ShelfBoardEdge
import com.tyranor.next.theme.ShelfCatalogAlcoveShadow
import com.tyranor.next.theme.ShelfCatalogBackdrop
import com.tyranor.next.theme.ShelfCatalogCabinetFrame
import com.tyranor.next.theme.ShelfCatalogChipBorder
import com.tyranor.next.theme.ShelfCatalogChipSurface
import com.tyranor.next.theme.ShelfCatalogHeaderRule
import com.tyranor.next.theme.ShelfCatalogHeaderSurface
import com.tyranor.next.theme.ShelfCatalogPlaqueSurface
import com.tyranor.next.theme.ShelfCatalogPlaqueText
import com.tyranor.next.theme.ShelfCatalogSectionRule
import com.tyranor.next.theme.ShelfCatalogSpineCylindricalLight
import com.tyranor.next.theme.ShelfCatalogSpineCylindricalShade
import com.tyranor.next.theme.ShelfCatalogSpineFoil
import com.tyranor.next.theme.ShelfCatalogSpineFooterRule
import com.tyranor.next.theme.ShelfCatalogSpineFooterSurface
import com.tyranor.next.theme.ShelfCatalogSpineTopShadow
import com.tyranor.next.theme.ShelfChamferDim
import com.tyranor.next.theme.ShelfChamferLight
import com.tyranor.next.theme.ShelfContactShadow
import com.tyranor.next.theme.ShelfDepthShade
import com.tyranor.next.theme.ShelfFaceShade
import com.tyranor.next.theme.ShelfGloss
import com.tyranor.next.theme.ShelfLedgeRim
import com.tyranor.next.theme.ShelfLedgeSurface
import com.tyranor.next.theme.ShelfObiSurface
import com.tyranor.next.theme.ShelfSelectedGilt
import com.tyranor.next.theme.ShelfObiText
import com.tyranor.next.theme.ShelfSpineText
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassEdgeStroke
import com.tyranor.next.theme.glassPageBackground
import com.tyranor.next.theme.shelfCatalogBackdropAccentText
import com.tyranor.next.theme.shelfCatalogSelectedChipBg
import com.tyranor.next.theme.shelfCatalogSelectedChipBorder
import com.tyranor.next.theme.shelfCatalogSelectedChipText
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.LocalSlidingTabLightPanel
import com.tyranor.next.ui.common.MainOverlayPortal
import com.tyranor.next.ui.common.MainOverlayShareTouches
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.motion.rotationMorphBlock
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.BlurVisibility
import com.tyranor.next.ui.common.motion.CoverReveal
import com.tyranor.next.ui.common.motion.CoverSurface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.SlidingTabRow
import com.tyranor.next.ui.common.motion.ListItemPlacement
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.RollingNumberText
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.rememberCoverReveal
import com.tyranor.next.ui.common.motion.rememberScrollJellyState
import com.tyranor.next.ui.common.motion.scrollJelly
import com.tyranor.next.ui.common.motion.scrollJellyHost
import com.tyranor.next.ui.common.privacy.DismissOnAppLockEffect
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/**
 * 书架布局「藏书阁」全员合卷入柜全景模式：
 *
 * 长按单排书架任意游戏盒进入，当前视口内所有可见游戏盒组成编队，逐层滑入带有柜柱、纵深凹槽暗影、
 * 区间铭牌与厚搁板的立体藏书柜；支持**「书脊立排（默认）」**与**「封面陈列」**双形态切换及实时搜索、
 * 游玩状态、排序与引擎筛选。点选任一游戏后，所选书携可落地邻书同步抽出书柜并 3D 侧转翻开封面落回
 * 单排书架正中英雄位。
 */
@Composable
internal fun ShelfCatalogOverlay(
    allGames: List<ScanGame>,
    filteredGames: List<ScanGame>,
    anchorGame: ScanGame?,
    currentFocusGame: ScanGame?,
    /** 进入书架前显示的那一本（仍在筛选结果里时）：只有它带选定效果；筛选后其它本、翻页途中换的本都不带。 */
    selectedGame: ScanGame? = null,
    layoutMode: ShelfCatalogLayoutMode = ShelfCatalogLayoutMode.SPINES,
    onLayoutModeChange: (ShelfCatalogLayoutMode) -> Unit = {},
    stage: ShelfCatalogStage,
    query: String,
    conditions: GameLibraryConditions,
    onQueryChange: (String) -> Unit,
    onOpenFilter: () -> Unit,
    onResetAll: () -> Unit,
    onPickGame: (ScanGame) -> Int,
    onDismiss: () -> Unit,
) {
    val hideTitleTag by AppSettingsStore.gameCardTitleTagState.collectSettingAsState()
    val focusManager = LocalFocusManager.current
    val closeAnchor = currentFocusGame ?: anchorGame
    val motion = rememberShelfCatalogMotion(
        stage = stage,
        anchorGame = anchorGame ?: currentFocusGame,
        initialLayoutMode = layoutMode,
        onDismiss = onDismiss,
    )
    val filterBatch = remember(motion, conditions.engineFilter, conditions.playedFilter) { Any() }
    val openingBatch = remember(motion) { filterBatch }
    val openingUri = anchorGame?.uri ?: currentFocusGame?.uri
    val initialAnchorUri = remember(filteredGames, openingUri, filterBatch) {
        if (filterBatch === openingBatch) shelfCatalogFocusUri(filteredGames, openingUri) else null
    }
    LaunchedEffect(motion, motion.layoutMode) {
        if (layoutMode != motion.layoutMode) onLayoutModeChange(motion.layoutMode)
    }
    motion.currentFocusUri = selectedGame?.uri
    motion.syncCatalogGames(filteredGames, allGames)

    val lastPlayedUri = LocalShelfLastPlayedUri.current

    MainOverlayPortal {
        CompositionLocalProvider(
            LocalShelfLastPlayedUri provides lastPlayedUri,
            LocalIndication provides NoIndication,
        ) {
            LaunchedEffect(motion) { motion.enter() }
            // 退场（合上 / 点选）期间不再挡触摸：手指直接落到下面的书架上，想切页、想点另一个游戏、想再长按进藏书阁都不用等动画放完
            // （书架被碰到时由 interruptExit 把退场收尾）。覆盖层里的控件在 closing 期间自己不响应（见下面各处的 enabled 判断）
            MainOverlayShareTouches(motion.closing)
            BackHandler(enabled = !motion.closing) {
                focusManager.clearFocus(force = true)
                motion.close(closeAnchor)
            }
            DismissOnAppLockEffect { motion.dismissImmediately() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPlaced { motion.overlayOrigin = it.localToScreen(Offset.Zero) },
            ) {
                ShelfCatalogBackground(motion = motion, modifier = Modifier.fillMaxSize())
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .imePadding(),
                ) {
                    ShelfCatalogControlDeck(
                        motion = motion,
                        anchorGame = closeAnchor,
                        totalCount = allGames.size,
                        shownCount = filteredGames.size,
                        query = query,
                        conditions = conditions,
                        onLayoutSelect = { target ->
                            if (motion.switchLayout(target)) MotionHaptics.perform(MotionHaptics.Kind.Tick)
                        },
                        onQueryChange = onQueryChange,
                        onOpenFilter = onOpenFilter,
                        onClose = {
                            focusManager.clearFocus(force = true)
                            motion.close(closeAnchor)
                        },
                    )
                BlurCrossfade(
                    targetState = filteredGames.isEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 旋转过渡：书柜不逐帧重排（每层放几本由最终宽度定），等比缩放贴进分给它的矩形（见 rotationMorphBlock）
                        .rotationMorphBlock()
                        .shelfCatalogWallPull(motion),
                    contentAlignment = Alignment.Center,
                ) { empty ->
                    if (empty) {
                        ShelfCatalogEmptyState(
                            onReset = {
                                if (motion.closing) return@ShelfCatalogEmptyState
                                MotionHaptics.perform(MotionHaptics.Kind.Tick)
                                onResetAll()
                            },
                        )
                    } else {
                        val revision = motion.layoutRevision
                        ShelfCatalogModeSwitcher(
                            mode = motion.layoutMode,
                            revision = revision,
                            leavingMode = motion.activeMorph.firstOrNull()?.fromMode,
                            progress = { motion.morphProgressSeenBy(revision) },
                            blockTouches = motion.morphing,
                            modifier = Modifier.fillMaxSize(),
                        ) { mode ->
                            ShelfCatalogWall(
                                layoutMode = mode,
                                games = filteredGames,
                                initialAnchorUri = initialAnchorUri,
                                currentFocusUri = selectedGame?.uri,
                                conditions = conditions,
                                hideTitleTag = hideTitleTag,
                                motion = motion,
                                onPickGame = { picked ->
                                    if (motion.interact()) {
                                        focusManager.clearFocus(force = true)
                                        MotionHaptics.perform(MotionHaptics.Kind.Tick)
                                        motion.pickAndClose(picked, onPickGame)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // 书脊 ↔ 封面逐本变形层：在书柜之上、飞行编队之下
            ShelfCatalogMorphOverlay(motion = motion, hideTitleTag = hideTitleTag)
            // 全员 3D 飞行编队层：覆盖全屏最顶层，实现单排书架所有可见盒 ↔ 立体藏书柜的无隙同步穿梭
            ShelfCatalogTroupeOverlay(
                motion = motion,
                hideTitleTag = hideTitleTag,
            )
            }
        }
    }
}

/**
 * 「书脊 | 封面」逐本变形层：切换陈列时，两边都看得见的每一本书是一张卡片——从旧陈列里的位置与大小，一路变形到新陈列里的
 * 位置与大小，同时书脊的面渐渐换成封面的面（反之亦然）；位置 / 大小 / 进度由 [ShelfCatalogMotion.morphPoseOf] 给出，
 * 在图层块里读取，变形途中不重组。
 */
@Composable
private fun ShelfCatalogMorphOverlay(motion: ShelfCatalogMotion, hideTitleTag: Boolean) {
    val flights = motion.activeMorph
    if (flights.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    val highlightStroke = shelfCatalogSelectedChipBorder(accent)
    val verticalSpineShape = remember {
        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 1.dp, bottomEnd = 1.dp)
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            // 卡片只在书柜视口里画，单 Pass 绘制并以顶部细条渐变做顶缘渐隐，下方 95% 区域不做 DstIn 过绘
            .drawWithCache {
                val startPx = WALL_TOP_FADE_START.toPx()
                val fadeHeight = startPx + WALL_TOP_FADE.toPx()
                val fadeBrush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                    startY = startPx,
                    endY = fadeHeight,
                )
                onDrawWithContent {
                    val viewport = motion.wallViewport
                    val origin = motion.overlayOrigin
                    if (viewport.width <= 0f || viewport.height <= 0f) {
                        drawContent()
                    } else {
                        val left = viewport.left - origin.x
                        val top = viewport.top - origin.y
                        val right = viewport.right - origin.x
                        val bottom = viewport.bottom - origin.y
                        clipRect(left = left, top = top, right = right, bottom = bottom) {
                            this@onDrawWithContent.drawContent()
                        }
                        val bandBottom = (top + fadeHeight).coerceAtMost(bottom)
                        if (bandBottom > top) {
                            withTransform({ translate(0f, top) }) {
                                drawRect(
                                    brush = fadeBrush,
                                    topLeft = Offset(left, 0f),
                                    size = Size(viewport.width, bandBottom - top),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                    }
                }
            },
    ) {
        for (flight in flights) {
            key(flight.game.uri) {
                ShelfCatalogMorphBook(flight, motion, hideTitleTag, accent, highlightStroke, verticalSpineShape)
            }
        }
    }
}

@Composable
private fun ShelfCatalogMorphBook(
    flight: ShelfCatalogMorphFlight,
    motion: ShelfCatalogMotion,
    hideTitleTag: Boolean,
    accent: Color,
    highlightStroke: Color,
    verticalSpineShape: androidx.compose.ui.graphics.Shape,
) {
    val game = flight.game
    val cover by rememberCoverBitmap(game.coverUri, CoverDecodeTier.GRID)
    val wash = rememberShelfSpineWash(cover)
    val engineColor = game.engine.coverColor()
    val title = remember(game.title, hideTitleTag) {
        if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    }
    val density = LocalDensity.current
    val toRect = flight.toRect
    val fromWidth = with(density) { flight.fromRect.width.toDp() }
    val fromHeight = with(density) { flight.fromRect.height.toDp() }

    @Composable
    fun Face(mode: ShelfCatalogLayoutMode, width: Dp, height: Dp, modifier: Modifier) {
        if (mode == ShelfCatalogLayoutMode.SPINES) {
            ShelfCatalogVerticalSpineFace(
                title = title,
                uri = game.uri,
                spineWidth = width,
                boxHeight = height,
                isCurrent = flight.isCurrent,
                lastPlayed = isShelfLastPlayed(game),
                engineColor = engineColor,
                wash = wash,
                spineShape = verticalSpineShape,
                recessShadeAlpha = if (flight.isCurrent) 0f else shelfCatalogSpineRecess(game.uri).shadeAlpha,
                modifier = modifier,
            )
        } else {
            val coverShapes = remember(width) { ShelfBoxShapes(shelfChamfer(width)) }
            ShelfCatalogCoverFace(
                game = game,
                globalIndex = flight.spineIndex,
                coverWidth = width,
                coverHeight = height,
                isCurrent = flight.isCurrent,
                accent = accent,
                highlightStroke = highlightStroke,
                engineColor = engineColor,
                cover = cover,
                revealModifier = Modifier.fillMaxSize(),
                chamfer = shelfChamfer(width),
                boxShape = coverShapes.frontWithSpineLeft,
                modifier = modifier,
            )
        }
    }

    // 旧陈列的面：起点大小，随进度换成新陈列的面（新陈列里看不见的书只淡出）
    Face(
        mode = flight.fromMode,
        width = fromWidth,
        height = fromHeight,
        modifier = Modifier.morphCard(motion, flight, natural = { flight.fromRect.size }, incoming = false),
    )
    if (toRect != null) {
        Face(
            mode = flight.toMode,
            width = with(density) { toRect.width.toDp() },
            height = with(density) { toRect.height.toDp() },
            modifier = Modifier.morphCard(motion, flight, natural = { toRect.size }, incoming = true),
        )
    }
}

/** 藏书阁沉浸全景背景。 */
@Composable
private fun ShelfCatalogBackground(
    motion: ShelfCatalogMotion,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val backdrop = ShelfCatalogBackdrop
    val closing = motion.closing
    Box(
        modifier
            .shelfCatalogBackdropAlpha(motion)
            .then(
                // 退场期间不再吞触摸：没有触摸输入的节点不会被命中，手指穿过背景落到下面的页面（覆盖层本身另有共享触摸，见 MainOverlayShareTouches）
                if (closing) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            ),
    ) {
        when {
            AppThemeColors.isMilkGlass -> MilkGlassPanelBackground(Modifier.fillMaxSize(), veilColor = backdrop)
            AppThemeColors.isGlass -> Box(
                Modifier
                    .fillMaxSize()
                    .glassPageBackground(accent)
                    .background(backdrop),
            )
            else -> Box(Modifier.fillMaxSize().background(backdrop))
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val glow = Brush.radialGradient(
                        0f to accent.copy(alpha = 0.14f),
                        0.6f to accent.copy(alpha = 0.04f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.5f, size.height * 0.14f),
                        radius = maxOf(size.width, size.height) * 0.72f,
                    )
                    onDrawBehind { drawRect(glow) }
                },
        )
    }
}

/**
 * 顶部典籍检索台：标题计数与「书脊 | 封面」分栏、搜索框 + 「筛选与排序」图标。
 * 引擎 / 排序方式 / 游玩状态不在检索台里：点图标打开与游戏页同一个全局「筛选与排序」抽屉（[GameFilterSheet]，由 GameScreen 承载）；
 * 「书脊 | 封面」分栏用全应用统一的 [SlidingTabRow]（不传 pager：不带长按拖选的透镜，文字也不会被拖着走）。
 */
@Composable
private fun ShelfCatalogControlDeck(
    motion: ShelfCatalogMotion,
    anchorGame: ScanGame?,
    totalCount: Int,
    shownCount: Int,
    query: String,
    conditions: GameLibraryConditions,
    onLayoutSelect: (ShelfCatalogLayoutMode) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenFilter: () -> Unit,
    onClose: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val accent = MaterialTheme.colorScheme.primary
    val headerSurface = ShelfCatalogHeaderSurface
    val headerRuleColor = ShelfCatalogHeaderRule
    val pullGesture = rememberShelfCatalogPullToDismiss(motion, anchorGame)
    val emblemColor = shelfCatalogSelectedChipText(accent)

    val layoutLabels = listOf(
        stringResource(R.string.game_shelf_catalog_layout_spines),
        stringResource(R.string.game_shelf_catalog_layout_covers),
    )
    val filterActive = conditions.isActive

    CompositionLocalProvider(LocalSlidingTabLightPanel provides true) {
        Column(
        modifier = Modifier
            .fillMaxWidth()
            .shelfCatalogHeaderEntrance(motion)
            .padding(horizontal = DECK_OUTER_H_PAD, vertical = DECK_OUTER_V_PAD)
            .then(pullGesture)
            .clip(AppComponentShape)
            .background(headerSurface, AppComponentShape)
            .glassEdgeStroke(AppComponentShape, width = 0.85.dp)
            .padding(horizontal = DECK_INNER_H_PAD, vertical = DECK_INNER_V_PAD),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 4.dp)
                .size(width = 36.dp, height = 3.5.dp)
                .clip(CircleShape)
                .background(headerRuleColor),
        )
        // 第一行：星芒标题 + 动态数量 + 「书脊 | 封面」分栏 + 关闭钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShelfCatalogEmblem(color = emblemColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.game_shelf_catalog_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = BookSerifBold,
                    fontWeight = FontWeight.Bold,
                ),
                color = TextColor,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            ShelfCatalogCountBadge(
                shown = shownCount,
                total = totalCount,
                color = SecondaryTextColor,
                // 极窄屏放不下整串「共 N 部」时整块不画，免得被挤成一字一行的竖排
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                        val fits = placeable.width <= constraints.maxWidth
                        layout(constraints.maxWidth, if (fits) placeable.height else 0) {
                            if (fits) placeable.placeRelative(0, 0)
                        }
                    },
            )
            GameFilterTabTrack(Modifier.width(DECK_LAYOUT_TABS_WIDTH)) {
                SlidingTabRow(
                    tabs = layoutLabels,
                    selectedIndex = if (motion.requestedLayout == ShelfCatalogLayoutMode.SPINES) 0 else 1,
                    onTabSelected = { index ->
                        // 退场期间触摸是递给下面书架的，这里不响应
                        if (!motion.closing) {
                            onLayoutSelect(if (index == 0) ShelfCatalogLayoutMode.SPINES else ShelfCatalogLayoutMode.COVERS)
                        }
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            ShelfCatalogCloseButton(onClick = {
                if (motion.closing) return@ShelfCatalogCloseButton
                MotionHaptics.perform(MotionHaptics.Kind.Tick)
                onClose()
            })
        }

        Spacer(Modifier.height(6.dp))

        // 第二行：统一搜索输入框 + 「筛选与排序」图标
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppSearchField(
                query = query,
                onQueryChange = onQueryChange,
                // 搜索条自带左右各 12dp 的外边距：向两侧撑回去，让药丸左缘与标题星芒、右缘与图标 / 关闭钮对齐
                modifier = Modifier
                    .weight(1f)
                    .layout { measurable, constraints ->
                        val bleed = SEARCH_FIELD_SIDE_INSET.roundToPx()
                        val width = constraints.maxWidth + bleed * 2
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
                    },
            )
            // 与游戏页搜索栏右侧同款的「筛选与排序」图标：打开全局「筛选与排序」抽屉（引擎 / 排序方式 / 游玩状态都在抽屉里）
            GameFilterActionIcon(isActive = filterActive) {
                if (motion.closing) return@GameFilterActionIcon
                MotionHaptics.perform(MotionHaptics.Kind.Tick)
                focusManager.clearFocus(force = true)
                onOpenFilter()
            }
        }
        }
    }
}

@Composable
private fun ShelfCatalogEmblem(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val r = size.minDimension * 0.48f
        val inner = r * 0.34f
        val path = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx + inner, cy - inner)
            lineTo(cx + r, cy)
            lineTo(cx + inner, cy + inner)
            lineTo(cx, cy + r)
            lineTo(cx - inner, cy + inner)
            lineTo(cx - r, cy)
            lineTo(cx - inner, cy - inner)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun ShelfCatalogCountBadge(
    shown: Int,
    total: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val label = if (shown == total) {
        stringResource(R.string.game_shelf_catalog_count_total, total)
    } else {
        stringResource(R.string.game_shelf_catalog_count_filtered, shown, total)
    }
    RollingNumberText(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun ShelfCatalogCloseButton(onClick: () -> Unit) {
    val description = stringResource(R.string.game_shelf_catalog_close)
    val iconColor = TextColor
    val bg = ShelfCatalogChipSurface
    val stroke = ShelfCatalogChipBorder
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(0.8.dp, stroke, CircleShape)
            .pressSquish(scale = PressSquishDefaults.Scale, follow = 0.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(iconColor, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = s.width, cap = s.cap)
            drawLine(iconColor, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = s.width, cap = s.cap)
        }
    }
}

@Composable
private fun ShelfCatalogEmptyState(
    onReset: () -> Unit,
) {
    BlurVisibility(visible = true) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 320.dp)
                .clip(AppComponentShape)
                .background(ShelfCatalogHeaderSurface, AppComponentShape)
                .glassEdgeStroke(AppComponentShape, width = 0.85.dp)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShelfCatalogEmblem(
                color = shelfCatalogSelectedChipText(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.game_shelf_catalog_empty_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = BookSerifBold),
                color = TextColor,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            NoRippleButton(
                text = stringResource(R.string.game_shelf_catalog_empty_action),
                onClick = onReset,
            )
        }
    }
}

/** 藏书阁立体书柜墙：统一调度「书脊立排」与「封面陈列」双形态。 */
@Composable
private fun ShelfCatalogWall(
    layoutMode: ShelfCatalogLayoutMode,
    games: List<ScanGame>,
    initialAnchorUri: String?,
    currentFocusUri: String?,
    conditions: GameLibraryConditions,
    hideTitleTag: Boolean,
    motion: ShelfCatalogMotion,
    onPickGame: (ScanGame) -> Unit,
) {
    val anchorY = motion.layoutAnchorY
    val anchorUri = motion.layoutAnchorUri.takeIf { anchorY != null }
    val isLeaving = LocalShelfCatalogLeavingWall.current
    val groupByEngine = conditions.sortMode == AppSettingsStore.GAME_SORT_ENGINE
    val accent = MaterialTheme.colorScheme.primary
    val highlightStroke = shelfCatalogSelectedChipBorder(accent)
    val sharedVerticalSpineShape = remember {
        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 1.dp, bottomEnd = 1.dp)
    }
    val currentLabel = stringResource(R.string.game_shelf_catalog_current_badge)
    // 搜索 / 筛选换了一批书：新出现的书依次「立」起来，不是一下子冒出来（首次组合不算换批）
    val batchChangedAt = rememberCoverBatchChange(games)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().shelfCatalogWallViewport(motion)) {
        val wallDensity = LocalDensity.current
        val cells = with(wallDensity) { shelfCatalogGridCells(maxWidth, layoutMode) }
        val cabinetOuterInset = cells.cabinetInset
        val stileWidth = CABINET_STILE_WIDTH
        val interiorInset = CABINET_INTERIOR_INSET
        val columns = cells.columns
        if (cells.cellWidth <= 0.dp) return@BoxWithConstraints
        val cabinetWidthPx = with(wallDensity) { (maxWidth.roundToPx() - 2 * cabinetOuterInset.roundToPx()).coerceAtLeast(0) }
        val innerWidthPx = with(wallDensity) {
            (cabinetWidthPx - 2 * (stileWidth + interiorInset).roundToPx()).coerceAtLeast(0)
        }

        if (layoutMode == ShelfCatalogLayoutMode.SPINES) {
            val rows = remember(games, columns, groupByEngine) {
                buildShelfCatalogVerticalRows(games, columns, groupByEngine)
            }
            val spineWidth = cells.cellWidth
            val spineHeight = shelfCatalogSpineHeight(maxWidth)
            val baseCellWidthPx = with(wallDensity) { spineWidth.toPx() }
            val maxBookHeightPx = with(wallDensity) { spineHeight.toPx() }
            val trailingLeanReservePx = shelfCatalogEndBookLeanShiftPx(
                leanDegrees = -6.5f,
                bookHeightPx = maxBookHeightPx,
                bookWidthPx = baseCellWidthPx * SPINE_MAX_THICKNESS_FACTOR,
            )
            val tierEstimate = VERTICAL_TIER_TOP_ROOM + spineHeight + TIER_LEDGE_DEPTH +
                TIER_BOARD_EDGE + TIER_UNDER_SHADOW + VERTICAL_TIER_PLAQUE_EXTRA
            val visibleTiersHint = (maxHeight / tierEstimate).toInt().coerceAtLeast(1)
            val initialRow = remember(rows, initialAnchorUri, visibleTiersHint) {
                shelfCatalogInitialRow(rows, initialAnchorUri, visibleTiersHint)
            }
            // 网格几何报给变形：看不见的书按它外推位置（含自然厚度、随机缝宽、内收微抬与每本高度）
            SideEffect {
                motion.reportGrid(
                    ShelfCatalogLayoutMode.SPINES,
                    ShelfCatalogGridMetrics(
                        columns = columns,
                        pitchX = cells.pitchPx.toFloat(),
                        pitchY = with(wallDensity) { tierEstimate.toPx() },
                        grouped = groupByEngine,
                        density = wallDensity.density,
                        baseCellWidthPx = baseCellWidthPx,
                        innerWidthPx = innerWidthPx,
                        maxBookHeightPx = maxBookHeightPx,
                        focusLiftPx = with(wallDensity) { SPINE_FOCUS_LIFT.roundToPx().toFloat() },
                        trailingLeanReservePx = trailingLeanReservePx,
                    ),
                )
            }
            // 从另一种陈列切过来：让锚点书落在旧陈列里的同一高度（能落的话），不整排上蹿下跳
            val aligned = remember(rows, anchorUri, anchorY, spineHeight, currentFocusUri, wallDensity) {
                val bookHeight = spineHeight * (anchorUri?.let(::shelfCatalogSpineHeightFactor) ?: 1f)
                val verticalOffset = when {
                    anchorUri == null -> 0.dp
                    anchorUri == currentFocusUri -> SPINE_FOCUS_LIFT
                    else -> (-shelfCatalogSpineRecess(anchorUri).liftDp).dp
                }
                with(wallDensity) {
                    shelfCatalogAlignedScroll(
                        rows = rows,
                        anchorUri = anchorUri,
                        anchorY = anchorY,
                        cellCenterPx = (VERTICAL_TIER_TOP_ROOM + (spineHeight - bookHeight)).roundToPx() +
                            bookHeight.roundToPx() / 2f + verticalOffset.roundToPx(),
                        topPaddingPx = WALL_TOP_FADE_START.roundToPx(),
                    )
                }
            }
            val listState = rememberLazyListState(aligned?.first ?: initialRow, aligned?.second ?: 0)
            StopLeavingWallScroll(listState, isLeaving)
            SettleEntryOnWallDrag(listState, motion)
            val keepAnchor = rememberCatalogAnchorOnReflow(listState, rows, columns, visibleTiersHint, conditions)
            val jelly = rememberScrollJellyState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(keepAnchor)
                    .shelfCatalogTopFade()
                    .scrollJellyHost(jelly),
                contentPadding = PaddingValues(top = WALL_TOP_FADE_START, bottom = 28.dp),
                userScrollEnabled = !motion.morphing && !motion.closing,
            ) {
                items(
                    items = rows,
                    key = { it.key },
                    contentType = { row ->
                        when (row) {
                            is ShelfCatalogRowItem.SectionHeader -> "section"
                            is ShelfCatalogRowItem.VerticalTier -> "vertical_tier"
                            is ShelfCatalogRowItem.CoverTier -> "cover_tier"
                        }
                    },
                ) { row ->
                    when (row) {
                        is ShelfCatalogRowItem.SectionHeader -> ShelfCatalogSectionBanner(
                            engine = row.engine,
                            count = row.count,
                            tierHint = 0,
                            cabinetInset = cabinetOuterInset,
                            stileWidth = stileWidth,
                            motion = motion,
                            modifier = Modifier
                                .then(listItemMotion(ListItemPlacement.Reflow))
                                .scrollJelly(jelly),
                        )
                        is ShelfCatalogRowItem.VerticalTier -> ShelfCatalogVerticalTierRow(
                            tier = row,
                            columns = columns,
                            cabinetInset = cabinetOuterInset,
                            stileWidth = stileWidth,
                            interiorInset = interiorInset,
                            baseCellWidthPx = baseCellWidthPx,
                            innerWidthPx = innerWidthPx,
                            trailingLeanReservePx = trailingLeanReservePx,
                            spineHeight = spineHeight,
                            currentFocusUri = currentFocusUri,
                            hideTitleTag = hideTitleTag,
                            accent = accent,
                            highlightStroke = highlightStroke,
                            verticalSpineShape = sharedVerticalSpineShape,
                            currentLabel = currentLabel,
                            motion = motion,
                            batchChangedAt = batchChangedAt,
                            onPickGame = onPickGame,
                            modifier = Modifier
                                .then(listItemMotion(ListItemPlacement.Layout))
                                .scrollJelly(jelly),
                        )
                        is ShelfCatalogRowItem.CoverTier -> Unit
                    }
                }
                item(key = "nav_spacer") {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        } else {
            val rows = remember(games, columns, groupByEngine) {
                buildShelfCatalogCoverRows(games, columns, groupByEngine)
            }
            val coverGap = cells.gap
            val coverWidth = cells.cellWidth
            val coverHeight = (coverWidth / COVER_ASPECT_RATIO).coerceIn(100.dp, 196.dp)
            val sharedCoverChamfer = remember(coverWidth) { shelfChamfer(coverWidth) }
            val sharedCoverBoxShape = remember(sharedCoverChamfer) {
                ShelfBoxShapes(sharedCoverChamfer).frontWithSpineLeft
            }
            val tierEstimate = COVER_TIER_TOP_ROOM + coverHeight + TIER_LEDGE_DEPTH +
                TIER_BOARD_EDGE + TIER_UNDER_SHADOW + COVER_TITLE_BAND_HEIGHT
            val visibleTiersHint = (maxHeight / tierEstimate).toInt().coerceAtLeast(1)
            val initialRow = remember(rows, initialAnchorUri, visibleTiersHint) {
                shelfCatalogInitialRow(rows, initialAnchorUri, visibleTiersHint)
            }
            SideEffect {
                motion.reportGrid(
                    ShelfCatalogLayoutMode.COVERS,
                    ShelfCatalogGridMetrics(
                        columns = columns,
                        pitchX = cells.pitchPx.toFloat(),
                        pitchY = with(wallDensity) { tierEstimate.toPx() },
                        grouped = groupByEngine,
                        maxBookHeightPx = with(wallDensity) { coverHeight.toPx() },
                        focusLiftPx = with(wallDensity) { COVER_FOCUS_LIFT.toPx() },
                    ),
                )
            }
            val aligned = remember(rows, anchorUri, anchorY, coverHeight, currentFocusUri, wallDensity) {
                val bookHeight = coverHeight * (anchorUri?.let(::shelfCatalogCoverHeightFactor) ?: 1f)
                with(wallDensity) {
                    shelfCatalogAlignedScroll(
                        rows = rows,
                        anchorUri = anchorUri,
                        anchorY = anchorY,
                        cellCenterPx = (COVER_TIER_TOP_ROOM + (coverHeight - bookHeight)).roundToPx() +
                            bookHeight.roundToPx() / 2f +
                            (if (anchorUri == currentFocusUri) COVER_FOCUS_LIFT else 0.dp).roundToPx(),
                        topPaddingPx = WALL_TOP_FADE_START.roundToPx(),
                    )
                }
            }
            val listState = rememberLazyListState(aligned?.first ?: initialRow, aligned?.second ?: 0)
            StopLeavingWallScroll(listState, isLeaving)
            SettleEntryOnWallDrag(listState, motion)
            val keepAnchor = rememberCatalogAnchorOnReflow(listState, rows, columns, visibleTiersHint, conditions)
            val jelly = rememberScrollJellyState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(keepAnchor)
                    .shelfCatalogTopFade()
                    .scrollJellyHost(jelly),
                contentPadding = PaddingValues(top = WALL_TOP_FADE_START, bottom = 28.dp),
                userScrollEnabled = !motion.morphing && !motion.closing,
            ) {
                items(
                    items = rows,
                    key = { it.key },
                    contentType = { row ->
                        when (row) {
                            is ShelfCatalogRowItem.SectionHeader -> "section"
                            is ShelfCatalogRowItem.VerticalTier -> "vertical_tier"
                            is ShelfCatalogRowItem.CoverTier -> "cover_tier"
                        }
                    },
                ) { row ->
                    when (row) {
                        is ShelfCatalogRowItem.SectionHeader -> ShelfCatalogSectionBanner(
                            engine = row.engine,
                            count = row.count,
                            tierHint = 0,
                            cabinetInset = cabinetOuterInset,
                            stileWidth = stileWidth,
                            motion = motion,
                            modifier = Modifier
                                .then(listItemMotion(ListItemPlacement.Reflow))
                                .scrollJelly(jelly),
                        )
                        is ShelfCatalogRowItem.CoverTier -> ShelfCatalogCoverTierRow(
                            tier = row,
                            columns = columns,
                            cabinetInset = cabinetOuterInset,
                            stileWidth = stileWidth,
                            interiorInset = interiorInset,
                            coverGap = coverGap,
                            coverWidth = coverWidth,
                            coverHeight = coverHeight,
                            chamfer = sharedCoverChamfer,
                            boxShape = sharedCoverBoxShape,
                            currentLabel = currentLabel,
                            currentFocusUri = currentFocusUri,
                            hideTitleTag = hideTitleTag,
                            accent = accent,
                            highlightStroke = highlightStroke,
                            motion = motion,
                            batchChangedAt = batchChangedAt,
                            onPickGame = onPickGame,
                            modifier = Modifier
                                .then(listItemMotion(ListItemPlacement.Layout))
                                .scrollJelly(jelly),
                        )
                        is ShelfCatalogRowItem.VerticalTier -> Unit
                    }
                }
                item(key = "nav_spacer") {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

/** 书柜视口上缘的柔化高度：书柜内容在这一段里由透明渐变到不透明，不在检索台下面被一刀切断。 */
private val WALL_TOP_FADE = 12.dp

/** 渐隐从视口上缘往下这么远才开始：与第一层搁位顶上留的空隙（LazyColumn 的 contentPadding）一样，让搁位的顶边恰好从全透明起步。 */
private val WALL_TOP_FADE_START = 6.dp

/**
 * 在当前图层上缘把内容渐隐掉：[WALL_TOP_FADE_START] 以内全透明，再用 [WALL_TOP_FADE] 的高度渐变到不透明。
 * 单 Pass 绘制后仅对顶部细条施用预缓存的 `DstIn` 渐变，避免两次子树重放导致 RenderThread 翻倍。
 */
private fun Modifier.shelfCatalogTopFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val startPx = WALL_TOP_FADE_START.toPx()
            val fadeHeight = startPx + WALL_TOP_FADE.toPx()
            val fadeBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black,
                startY = startPx,
                endY = fadeHeight,
            )
            onDrawWithContent {
                drawContent()
                val bandH = fadeHeight.coerceAtMost(size.height)
                if (bandH > 0f && size.width > 0f) {
                    drawRect(
                        brush = fadeBrush,
                        topLeft = Offset.Zero,
                        size = Size(size.width, bandH),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
        }

internal fun resolveCatalogCenterUri(
    info: androidx.compose.foundation.lazy.LazyListLayoutInfo,
    rows: List<ShelfCatalogRowItem>,
): String? {
    if (info.totalItemsCount != rows.size + 1) return null
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val item = info.visibleItemsInfo.filter { item ->
        val row = rows.getOrNull(item.index)
        row != null && row.key == item.key && row !is ShelfCatalogRowItem.SectionHeader
    }.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) } ?: return null
    val tier = rows.getOrNull(item.index)?.takeIf { it.key == item.key } ?: return null
    val games = when (tier) {
        is ShelfCatalogRowItem.VerticalTier -> tier.games
        is ShelfCatalogRowItem.CoverTier -> tier.games
        else -> null
    }
    return games?.getOrNull(games.size / 2)?.uri
}

@Composable
private fun StopLeavingWallScroll(listState: LazyListState, isLeaving: Boolean) {
    LaunchedEffect(listState, isLeaving) {
        if (isLeaving && listState.isScrollInProgress) listState.stopScroll(MutatePriority.PreventUserInput)
    }
}

/**
 * 入场还没放完就用手指拖动书墙：飞行编队的落点是进场那一刻量好的屏幕位置，书墙一滚动，被藏起的真书就不在那里了，
 * 编队封面会落在旧位置、与滚动中的书叠在一起（「封面位置异常」）。所以第一次手指拖动就把入场收敛到终态，不再等动画。
 * 只看手指拖动（[DragInteraction.Start]）：程序化的滚动（筛选回顶等）不算。
 */
@Composable
private fun SettleEntryOnWallDrag(listState: LazyListState, motion: ShelfCatalogMotion) {
    if (motion.hasEntered || motion.closing) return
    LaunchedEffect(listState, motion) {
        listState.interactionSource.interactions.first { it is DragInteraction.Start }
        motion.settleEntryForScroll()
    }
}

/** 筛选回顶；排序或列数变化时仍让原来视口正中那一本所在的层留在视口内。 */
@Composable
private fun rememberCatalogAnchorOnReflow(
    listState: LazyListState,
    rows: List<ShelfCatalogRowItem>,
    columns: Int,
    visibleTiersHint: Int,
    conditions: GameLibraryConditions,
): Modifier {
    val holder = remember(listState) { CatalogAnchorHolder() }
    SideEffect {
        when (shelfCatalogReflow(holder.conditions, conditions, holder.columns != columns)) {
            ShelfCatalogReflow.RESET_TOP -> {
                holder.centerUri = null
                listState.requestScrollToItem(0)
            }
            ShelfCatalogReflow.KEEP_CENTER -> {
                val uri = resolveCatalogCenterUri(listState.layoutInfo, holder.laidOutRows) ?: holder.centerUri
                if (uri != null && findShelfCatalogAnchorRow(rows, uri) >= 0) {
                    listState.requestScrollToItem(shelfCatalogInitialRow(rows, uri, visibleTiersHint))
                }
            }
            ShelfCatalogReflow.NONE -> Unit
        }
        holder.columns = columns
        holder.conditions = conditions
    }
    LaunchedEffect(listState, rows) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                resolveCatalogCenterUri(listState.layoutInfo, holder.laidOutRows)?.let { holder.centerUri = it }
            }
        }
    }
    return Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        holder.laidOutRows = rows
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

private class CatalogAnchorHolder {
    var columns = 0
    var conditions: GameLibraryConditions? = null
    var centerUri: String? = null
    var laidOutRows: List<ShelfCatalogRowItem> = emptyList()
}

/** 按引擎分架时的书柜章节横梁铭牌。 */
@Composable
private fun ShelfCatalogSectionBanner(
    engine: EngineType,
    count: Int,
    tierHint: Int,
    cabinetInset: Dp,
    stileWidth: Dp,
    motion: ShelfCatalogMotion,
    modifier: Modifier = Modifier,
) {
    val engineColor = engine.coverColor()
    val ruleColor = ShelfCatalogSectionRule
    val plaqueBg = ShelfCatalogPlaqueSurface
    val plaqueFg = ShelfCatalogPlaqueText
    val frameColor = ShelfCatalogCabinetFrame
    val rimColor = ShelfLedgeRim

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = cabinetInset)
            .shelfCatalogSectionEntrance(motion, tierHint)
            .drawBehind {
                val stPx = stileWidth.toPx()
                drawRect(frameColor, topLeft = Offset.Zero, size = Size(stPx, size.height))
                drawRect(frameColor, topLeft = Offset(size.width - stPx, 0f), size = Size(stPx, size.height))
                drawLine(rimColor.copy(alpha = 0.45f), Offset(stPx, 0f), Offset(stPx, size.height), strokeWidth = 0.8.dp.toPx())
                drawLine(rimColor.copy(alpha = 0.45f), Offset(size.width - stPx, 0f), Offset(size.width - stPx, size.height), strokeWidth = 0.8.dp.toPx())
            }
            .padding(start = stileWidth + 10.dp, end = stileWidth + 10.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(AppComponentShape)
                    .background(plaqueBg, AppComponentShape)
                    .border(0.7.dp, ruleColor, AppComponentShape)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(engineColor, CircleShape))
                Text(
                    text = engine.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = BookSerifBold,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = plaqueFg,
                    maxLines = 1,
                )
            }
            Box(Modifier.weight(1f).height(1.dp).background(ruleColor))
            Text(
                text = stringResource(R.string.game_shelf_catalog_section_count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = PageSecondaryTextColor,
                maxLines = 1,
            )
        }
    }
}

/** 预缓存画刷后的单层立体书柜绘制背景（左右柜柱、凹槽暗影、预计算接触影/聚焦光晕与厚搁板），供「书脊」与「封面」双形态共用。 */
private fun Modifier.shelfCatalogTierScaffold(
    rowBooksSpan: Dp,
    focusCenterOffset: Dp?,
    focusItemWidth: Dp,
    floorYDp: Dp,
    alcoveTopFractionHeight: Dp,
    stileWidth: Dp,
    interiorInset: Dp,
    glowRadiusScale: Float,
    frameColor: Color,
    alcoveShadow: Color,
    boardColor: Color,
    boardEdge: Color,
    rimColor: Color,
    contactColor: Color,
    depthShade: Color,
    accent: Color,
): Modifier = drawWithCache {
    val stPx = stileWidth.toPx()
    val floorY = floorYDp.toPx()
    val depthPx = TIER_LEDGE_DEPTH.toPx()
    val edgePx = TIER_BOARD_EDGE.toPx()
    val underPx = TIER_UNDER_SHADOW.toPx()
    val booksStartX = stPx + interiorInset.toPx()
    val innerLeft = stPx
    val innerRight = (size.width - stPx).coerceAtLeast(innerLeft + 1f)
    val alcoveTopH = alcoveTopFractionHeight.toPx().coerceAtLeast(1f)
    val wallShadowW = 18.dp.toPx()

    val alcoveTopBrush = Brush.verticalGradient(
        0f to alcoveShadow,
        0.45f to alcoveShadow.copy(alpha = alcoveShadow.alpha * 0.45f),
        1f to Color.Transparent,
        startY = 0f,
        endY = alcoveTopH,
    )
    val leftWallBrush = Brush.horizontalGradient(
        0f to alcoveShadow.copy(alpha = alcoveShadow.alpha * 0.65f),
        1f to Color.Transparent,
        startX = innerLeft,
        endX = innerLeft + wallShadowW,
    )
    val rightWallBrush = Brush.horizontalGradient(
        0f to Color.Transparent,
        1f to alcoveShadow.copy(alpha = alcoveShadow.alpha * 0.65f),
        startX = innerRight - wallShadowW,
        endX = innerRight,
    )
    val topBrush = Brush.verticalGradient(
        0f to boardColor.copy(alpha = boardColor.alpha * 0.66f),
        1f to boardColor,
        startY = floorY,
        endY = floorY + depthPx,
    )
    val underBrush = Brush.verticalGradient(
        0f to depthShade,
        1f to Color.Transparent,
        startY = floorY + depthPx + edgePx,
        endY = floorY + depthPx + edgePx + underPx,
    )
    val rowSpanW = rowBooksSpan.toPx().coerceAtLeast(0f)
    val contactTopY = floorY - depthPx * 0.12f
    val contactEndY = floorY + depthPx * 0.85f
    val contactBrush = Brush.verticalGradient(
        0f to contactColor,
        0.55f to contactColor.copy(alpha = contactColor.alpha * 0.4f),
        1f to Color.Transparent,
        startY = contactTopY,
        endY = contactEndY,
    )
    val glowBrushAndTop = if (focusCenterOffset != null) {
        val cx = booksStartX + focusCenterOffset.toPx()
        val glowR = (focusItemWidth.toPx() * glowRadiusScale).coerceAtLeast(1f)
        val brush = Brush.radialGradient(
            0f to accent.copy(alpha = 0.38f),
            0.55f to accent.copy(alpha = 0.14f),
            1f to Color.Transparent,
            center = Offset(cx, floorY + depthPx * 0.46f),
            radius = glowR,
        )
        Triple(brush, Offset(cx - glowR, floorY - depthPx * 0.35f), Size(glowR * 2f, depthPx * 1.85f))
    } else {
        null
    }

    onDrawBehind {
        drawRect(alcoveTopBrush, topLeft = Offset(innerLeft, 0f), size = Size(innerRight - innerLeft, alcoveTopH))
        drawRect(leftWallBrush, topLeft = Offset(innerLeft, 0f), size = Size(wallShadowW, floorY))
        drawRect(rightWallBrush, topLeft = Offset(innerRight - wallShadowW, 0f), size = Size(wallShadowW, floorY))

        drawRect(frameColor, topLeft = Offset.Zero, size = Size(stPx, size.height))
        drawRect(frameColor, topLeft = Offset(innerRight, 0f), size = Size(stPx, size.height))
        drawLine(rimColor.copy(alpha = 0.55f), Offset(stPx, 0f), Offset(stPx, size.height), strokeWidth = 0.85.dp.toPx())
        drawLine(rimColor.copy(alpha = 0.55f), Offset(innerRight, 0f), Offset(innerRight, size.height), strokeWidth = 0.85.dp.toPx())

        drawRect(topBrush, topLeft = Offset(innerLeft, floorY), size = Size(innerRight - innerLeft, depthPx))
        clipRect(left = innerLeft, top = 0f, right = innerRight, bottom = size.height) {
            if (rowSpanW > 0f) {
                drawRect(contactBrush, topLeft = Offset(booksStartX, contactTopY), size = Size(rowSpanW, contactEndY - contactTopY))
            }
            if (glowBrushAndTop != null) {
                drawOval(brush = glowBrushAndTop.first, topLeft = glowBrushAndTop.second, size = glowBrushAndTop.third)
            }
        }

        drawRect(boardEdge, topLeft = Offset(0f, floorY + depthPx), size = Size(size.width, edgePx))
        drawLine(rimColor, Offset(innerLeft, floorY + depthPx), Offset(innerRight, floorY + depthPx), strokeWidth = 1.dp.toPx())
        drawLine(rimColor.copy(alpha = rimColor.alpha * 0.5f), Offset(innerLeft, floorY), Offset(innerRight, floorY), strokeWidth = 0.75.dp.toPx())
        drawRect(underBrush, topLeft = Offset(innerLeft, floorY + depthPx + edgePx), size = Size(innerRight - innerLeft, underPx))
    }
}

private fun formatShelfCatalogRangeText(startIndex: Int, count: Int): String {
    val first = startIndex + 1
    val last = startIndex + count
    return if (first == last) {
        String.format(Locale.ROOT, "%02d", first)
    } else {
        String.format(Locale.ROOT, "%02d — %02d", first, last)
    }
}

/** 区间铭牌胶囊（注意调用方将 `.shelfCatalogLedgeEntrance` 置於最前以防首帧空框透出）。 */
@Composable
private fun ShelfCatalogRangePlaque(
    text: String,
    plaqueBg: Color,
    plaqueFg: Color,
    ruleColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = plaqueFg,
        maxLines = 1,
        modifier = modifier
            .clip(AppComponentShape)
            .background(plaqueBg.copy(alpha = 0.86f), AppComponentShape)
            .border(0.6.dp, ruleColor.copy(alpha = 0.65f), AppComponentShape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .clearAndSetSemantics { },
    )
}

/**
 * 藏书阁一层竖排立脊书柜格（各本书脊按自然厚薄靠左依次排布、层末空位自然留白，相邻缝宽随 uri 轻微变化）。
 */
@Composable
private fun ShelfCatalogVerticalTierRow(
    tier: ShelfCatalogRowItem.VerticalTier,
    columns: Int,
    cabinetInset: Dp,
    stileWidth: Dp,
    interiorInset: Dp,
    baseCellWidthPx: Float,
    innerWidthPx: Int,
    trailingLeanReservePx: Float,
    spineHeight: Dp,
    currentFocusUri: String?,
    hideTitleTag: Boolean,
    accent: Color,
    highlightStroke: Color,
    verticalSpineShape: androidx.compose.ui.graphics.Shape,
    currentLabel: String,
    motion: ShelfCatalogMotion,
    batchChangedAt: Long,
    onPickGame: (ScanGame) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val topRoom = VERTICAL_TIER_TOP_ROOM
    val shelfStackHeight = TIER_LEDGE_DEPTH + TIER_BOARD_EDGE + TIER_UNDER_SHADOW
    val tierGapsPx = remember(tier.games, density.density) {
        allocateShelfCatalogSpineGapsPx(tier.games.map { it.uri }, density.density)
    }
    val tierWidthsPx = remember(
        tier.games,
        columns,
        baseCellWidthPx,
        innerWidthPx,
        tierGapsPx,
        trailingLeanReservePx,
    ) {
        allocateShelfCatalogSpineWidths(
            uris = tier.games.map { it.uri },
            maxColumns = columns,
            baseCellWidthPx = baseCellWidthPx,
            innerWidthPx = innerWidthPx,
            gapsSumPx = tierGapsPx.sum(),
            trailingLeanReservePx = trailingLeanReservePx,
        )
    }
    val focusIndex = remember(tier.games, currentFocusUri) {
        if (currentFocusUri == null) -1 else tier.games.indexOfFirst { it.uri == currentFocusUri }
    }
    val rowBooksSpan = remember(tierWidthsPx, tierGapsPx, density) {
        val sumPx = tierWidthsPx.sum() + tierGapsPx.sum()
        with(density) { sumPx.toDp() }
    }
    val focusLayout = remember(tierWidthsPx, tierGapsPx, focusIndex, density) {
        if (focusIndex !in tierWidthsPx.indices) {
            null
        } else {
            var offsetPx = 0
            for (i in 0 until focusIndex) offsetPx += tierWidthsPx[i] + tierGapsPx[i]
            val centerDp = with(density) { (offsetPx + tierWidthsPx[focusIndex] * 0.5f).toDp() }
            val widthDp = with(density) { tierWidthsPx[focusIndex].toDp() }
            centerDp to widthDp
        }
    }
    val rangeText = remember(tier.startIndex, tier.games.size) {
        formatShelfCatalogRangeText(tier.startIndex, tier.games.size)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = cabinetInset)
            .shelfCatalogLedgeEntrance(motion, tier.rowGridIndex, ShelfCatalogLayoutMode.SPINES),
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(topRoom + spineHeight + shelfStackHeight + VERTICAL_TIER_PLAQUE_EXTRA)
                .shelfCatalogTierScaffold(
                    rowBooksSpan = rowBooksSpan,
                    focusCenterOffset = focusLayout?.first,
                    focusItemWidth = focusLayout?.second ?: 0.dp,
                    floorYDp = topRoom + spineHeight,
                    alcoveTopFractionHeight = topRoom + spineHeight * 0.28f,
                    stileWidth = stileWidth,
                    interiorInset = interiorInset,
                    glowRadiusScale = 1.4f,
                    frameColor = ShelfCatalogCabinetFrame,
                    alcoveShadow = ShelfCatalogAlcoveShadow,
                    boardColor = ShelfLedgeSurface,
                    boardEdge = ShelfBoardEdge,
                    rimColor = ShelfLedgeRim,
                    contactColor = ShelfContactShadow,
                    depthShade = ShelfDepthShade,
                    accent = ShelfSelectedGilt,
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = stileWidth + interiorInset, end = stileWidth + interiorInset),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            tier.games.forEachIndexed { col, game ->
                val bookWidth = with(density) { tierWidthsPx[col].toDp() }
                val isCurrent = game.uri == currentFocusUri
                val lean = shelfCatalogEndBookLeanDegrees(
                    colIndex = col,
                    tierCount = tier.games.size,
                    maxColumns = columns,
                    isCurrent = isCurrent,
                )
                // 按书身份分组合：搜索 / 筛选后这一格换了另一本，就是一本新书立起来（见 shelfBatchArrival），不是原地换了张脸
                key(game.uri) {
                    ShelfCatalogVerticalSpine(
                        game = game,
                        globalIndex = tier.startIndex + col,
                        rowGridIndex = tier.rowGridIndex,
                        colIndex = col,
                        spineWidth = bookWidth,
                        maxHeight = spineHeight,
                        topRoom = topRoom,
                        isCurrent = isCurrent,
                        leanDegrees = lean,
                        hideTitleTag = hideTitleTag,
                        accent = accent,
                        highlightStroke = highlightStroke,
                        spineShape = verticalSpineShape,
                        currentLabel = currentLabel,
                        motion = motion,
                        batchChangedAt = batchChangedAt,
                        onClick = { onPickGame(game) },
                    )
                }
                if (col < tier.games.lastIndex) {
                    Spacer(Modifier.width(with(density) { tierGapsPx[col].toDp() }))
                }
            }
        }

        ShelfCatalogRangePlaque(
            text = rangeText,
            plaqueBg = ShelfCatalogPlaqueSurface,
            plaqueFg = ShelfCatalogPlaqueText,
            ruleColor = ShelfCatalogSectionRule,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = stileWidth + interiorInset, bottom = 2.dp),
        )
    }
}

/** 竖排单本立脊：复用 [ShelfCatalogVerticalSpineFace] 精装立体外观并接入编队测量与整层波纹进场。 */
@Composable
private fun ShelfCatalogVerticalSpine(
    game: ScanGame,
    globalIndex: Int,
    rowGridIndex: Int,
    colIndex: Int,
    spineWidth: Dp,
    maxHeight: Dp,
    topRoom: Dp,
    isCurrent: Boolean,
    leanDegrees: Float,
    hideTitleTag: Boolean,
    accent: Color,
    highlightStroke: Color,
    spineShape: androidx.compose.ui.graphics.Shape,
    currentLabel: String,
    motion: ShelfCatalogMotion,
    batchChangedAt: Long,
    onClick: () -> Unit,
) {
    DisposableEffect(motion, game.uri) {
        onDispose { motion.forgetSlot(game.uri, ShelfCatalogLayoutMode.SPINES) }
    }
    val density = LocalDensity.current
    val cover by rememberCoverBitmap(game.coverUri, CoverDecodeTier.GRID)
    val wash = rememberShelfSpineWash(cover)
    val engineColor = game.engine.coverColor()
    val heightFactor = remember(game.uri) { shelfCatalogSpineHeightFactor(game.uri) }
    val recess = if (isCurrent) {
        ShelfCatalogSpineRecess.None
    } else {
        remember(game.uri) { shelfCatalogSpineRecess(game.uri) }
    }
    val verticalOffsetDp = if (isCurrent) SPINE_FOCUS_LIFT else (-recess.liftDp).dp
    val boxHeight = maxHeight * heightFactor
    val leanShiftX = remember(leanDegrees, boxHeight, spineWidth, density) {
        with(density) {
            shelfCatalogEndBookLeanShiftPx(
                leanDegrees = leanDegrees,
                bookHeightPx = boxHeight.toPx(),
                bookWidthPx = spineWidth.toPx(),
            ).toDp()
        }
    }
    val title = remember(game.title, hideTitleTag) {
        if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    }

    Column(
        modifier = Modifier.width(spineWidth).shelfBatchArrival(batchChangedAt, colIndex),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(topRoom + (maxHeight - boxHeight)))
        ShelfCatalogVerticalSpineFace(
            title = title,
            uri = game.uri,
            spineWidth = spineWidth,
            boxHeight = boxHeight,
            isCurrent = isCurrent,
            lastPlayed = isShelfLastPlayed(game),
            engineColor = engineColor,
            wash = wash,
            spineShape = spineShape,
            recessShadeAlpha = recess.shadeAlpha,
            modifier = Modifier
                .then(if (verticalOffsetDp != 0.dp) Modifier.offset(y = verticalOffsetDp) else Modifier)
                .then(if (leanShiftX > 0.dp) Modifier.offset(x = leanShiftX) else Modifier)
                .shelfCatalogSlotPlaced(motion, game.uri, rowGridIndex, colIndex, ShelfCatalogLayoutMode.SPINES, leanDegrees)
                .shelfCatalogBookLean(leanDegrees)
                .shelfCatalogSlotVisible(motion, game.uri)
                // 退场期间触摸是递给下面书架的，书不再按压反馈、也不再响应点选
                .pressSquish(enabled = !motion.closing, scale = PressSquishDefaults.CardScale)
                .clickable(
                    enabled = !motion.closing,
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = if (isCurrent) "$title ($currentLabel)" else title
                },
        )
    }
}

/**
 * 藏书阁里的立脊：与书架上、翻开的书起飞时的书脊是同一个烫金书脊（[ShelfSpineFaceContent]：封面柔焦底、宋体书名、
 * 烫线与书脚小菱饰取这本书的封面主色），只是顶上两角略带圆；选定的那一本多一道暖金双框与顶上一抹暖光。
 */
@Composable
private fun ShelfCatalogVerticalSpineFace(
    title: String,
    /** 用来还原这本书的高矮厚薄系数：排字按「基准厚度 + 最矮高度」算，书的外形差异不影响书名显示多长。 */
    uri: String,
    spineWidth: Dp,
    boxHeight: Dp,
    isCurrent: Boolean,
    lastPlayed: Boolean,
    engineColor: Color,
    wash: ImageBitmap?,
    spineShape: androidx.compose.ui.graphics.Shape,
    recessShadeAlpha: Float = 0f,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(spineWidth, boxHeight)
            .clip(spineShape)
            .background(engineColor),
    ) {
        ShelfSpineFaceContent(
            title = title,
            coverHeight = boxHeight,
            wash = wash,
            engineColor = engineColor,
            lastPlayed = lastPlayed,
            spineWidth = spineWidth,
            textWidth = shelfCatalogSpineTextWidth(uri, spineWidth),
            textHeight = shelfCatalogSpineTextHeight(uri, boxHeight),
        )
        if (!isCurrent && recessShadeAlpha > 0f) {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .background(ShelfFaceShade.copy(alpha = recessShadeAlpha)),
            )
        }
        if (isCurrent) {
            val gilt = ShelfSelectedGilt
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val brush = Brush.verticalGradient(
                            0f to gilt.copy(alpha = 0.2f),
                            0.32f to Color.Transparent,
                            endY = size.height * 0.45f,
                        )
                        onDrawBehind { drawRect(brush) }
                    },
            )
            ShelfCatalogSelectedFrame(spineShape)
        }
    }
}

/** 书脊排字用的参照宽：去掉这本书的厚薄系数，同一书柜里的书都按基准厚度排字。 */
internal fun shelfCatalogSpineTextWidth(uri: String, spineWidth: Dp): Dp =
    spineWidth / shelfCatalogSpineThicknessFactor(uri)

/** 书脊排字用的参照高：去掉这本书的高矮系数后按最矮的书留空间，高书只是上下多出一截，书名不会因此多排或少排字。 */
internal fun shelfCatalogSpineTextHeight(uri: String, boxHeight: Dp): Dp =
    boxHeight / shelfCatalogSpineHeightFactor(uri) * SHELF_CATALOG_MIN_SPINE_HEIGHT_FACTOR

/** 藏书阁一层封面陈列书柜格：沿用立柱、凹槽暗影与厚搁板，每盒呈精装切角封面配板下书名。 */
@Composable
private fun ShelfCatalogCoverTierRow(
    tier: ShelfCatalogRowItem.CoverTier,
    columns: Int,
    cabinetInset: Dp,
    stileWidth: Dp,
    interiorInset: Dp,
    coverGap: Dp,
    coverWidth: Dp,
    coverHeight: Dp,
    chamfer: Dp,
    boxShape: androidx.compose.ui.graphics.Shape,
    currentLabel: String,
    currentFocusUri: String?,
    hideTitleTag: Boolean,
    accent: Color,
    highlightStroke: Color,
    motion: ShelfCatalogMotion,
    batchChangedAt: Long,
    onPickGame: (ScanGame) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topRoom = COVER_TIER_TOP_ROOM
    val shelfStackHeight = TIER_LEDGE_DEPTH + TIER_BOARD_EDGE + TIER_UNDER_SHADOW
    val titleBandHeight = COVER_TITLE_BAND_HEIGHT
    val totalHeight = topRoom + coverHeight + shelfStackHeight + titleBandHeight
    val focusIndex = remember(tier.games, currentFocusUri) {
        if (currentFocusUri == null) -1 else tier.games.indexOfFirst { it.uri == currentFocusUri }
    }
    val rowBooksSpan = remember(tier.games.size, coverWidth, coverGap) {
        if (tier.games.isEmpty()) 0.dp else coverWidth * tier.games.size + coverGap * (tier.games.size - 1)
    }
    val focusCenterOffset = remember(focusIndex, tier.games.size, coverWidth, coverGap) {
        if (focusIndex in tier.games.indices) {
            (coverWidth + coverGap) * focusIndex + coverWidth * 0.5f
        } else {
            null
        }
    }
    val rangeText = remember(tier.startIndex, tier.games.size) {
        formatShelfCatalogRangeText(tier.startIndex, tier.games.size)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = cabinetInset)
            .shelfCatalogLedgeEntrance(motion, tier.rowGridIndex, ShelfCatalogLayoutMode.COVERS),
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .shelfCatalogTierScaffold(
                    rowBooksSpan = rowBooksSpan,
                    focusCenterOffset = focusCenterOffset,
                    focusItemWidth = coverWidth,
                    floorYDp = topRoom + coverHeight,
                    alcoveTopFractionHeight = topRoom + coverHeight * 0.24f,
                    stileWidth = stileWidth,
                    interiorInset = interiorInset,
                    glowRadiusScale = 0.72f,
                    frameColor = ShelfCatalogCabinetFrame,
                    alcoveShadow = ShelfCatalogAlcoveShadow,
                    boardColor = ShelfLedgeSurface,
                    boardEdge = ShelfBoardEdge,
                    rimColor = ShelfLedgeRim,
                    contactColor = ShelfContactShadow,
                    depthShade = ShelfDepthShade,
                    accent = ShelfSelectedGilt,
                ),
        )

        ShelfCatalogRangePlaque(
            text = rangeText,
            plaqueBg = ShelfCatalogPlaqueSurface,
            plaqueFg = ShelfCatalogPlaqueText,
            ruleColor = ShelfCatalogSectionRule,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = stileWidth + interiorInset, top = 4.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = stileWidth + interiorInset, end = stileWidth + interiorInset),
        ) {
            Spacer(Modifier.height(topRoom))
            Row(
                modifier = Modifier.fillMaxWidth().height(coverHeight),
                horizontalArrangement = Arrangement.spacedBy(coverGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (col in 0 until columns) {
                    val game = tier.games.getOrNull(col)
                    if (game != null) {
                        key(game.uri) {
                            ShelfCatalogCoverBook(
                                game = game,
                                globalIndex = tier.startIndex + col,
                                rowGridIndex = tier.rowGridIndex,
                                colIndex = col,
                                coverWidth = coverWidth,
                                coverHeight = coverHeight,
                                chamfer = chamfer,
                                boxShape = boxShape,
                                currentLabel = currentLabel,
                                isCurrent = game.uri == currentFocusUri,
                                hideTitleTag = hideTitleTag,
                                accent = accent,
                                highlightStroke = highlightStroke,
                                motion = motion,
                                batchChangedAt = batchChangedAt,
                                onClick = { onPickGame(game) },
                            )
                        }
                    } else {
                        Spacer(Modifier.width(coverWidth))
                    }
                }
            }
            Spacer(Modifier.height(shelfStackHeight))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(titleBandHeight)
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(coverGap),
                verticalAlignment = Alignment.Top,
            ) {
                for (col in 0 until columns) {
                    val game = tier.games.getOrNull(col)
                    if (game != null) key(game.uri) {
                        val isCurrent = game.uri == currentFocusUri
                        val title = remember(game.title, hideTitleTag) {
                            if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
                        }
                        Column(
                            modifier = Modifier
                                .width(coverWidth)
                                .shelfCatalogLeavingText(motion)
                                .shelfBatchArrival(batchChangedAt, col),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = if (isCurrent) BookSerifBold else BookSerif,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = PageTextColor,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clearAndSetSemantics { },
                            )
                            if (isCurrent) {
                                // 选定：书名下垫一小段暖金线
                                Box(
                                    Modifier
                                        .padding(top = 2.dp)
                                        .size(20.dp, 2.dp)
                                        .background(ShelfSelectedGilt, CircleShape),
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.width(coverWidth))
                    }
                }
            }
        }
    }
}

/** 选定的那一本的细金线双框：外一道 1dp、内缩 2.5dp 再一道 0.6dp 的淡金线，合并为单层 `drawWithCache` 绘制。 */
@Composable
private fun ShelfCatalogSelectedFrame(shape: androidx.compose.ui.graphics.Shape) {
    val outerColor = ShelfSelectedGilt.copy(alpha = 0.95f)
    val innerColor = ShelfSelectedGilt.copy(alpha = 0.5f)
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val outerWidth = 1.dp.toPx()
                val inset = 2.5.dp.toPx()
                val innerWidth = 0.6.dp.toPx()
                val outerOutline = shape.createOutline(size, layoutDirection, this)
                val innerSize = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f))
                val innerOutline = shape.createOutline(innerSize, layoutDirection, this)
                val outerStroke = Stroke(width = outerWidth)
                val innerStroke = Stroke(width = innerWidth)
                onDrawBehind {
                    drawOutline(outerOutline, outerColor, style = outerStroke)
                    if (innerSize.width > 0f && innerSize.height > 0f) {
                        withTransform({ translate(inset, inset) }) {
                            drawOutline(innerOutline, innerColor, style = innerStroke)
                        }
                    }
                }
            },
    )
}

/** 封面陈列单本切角精装游戏盒（`onPlaced` 精准测在封面体本身，不含板下标题文字）。 */
@Composable
private fun ShelfCatalogCoverBook(
    game: ScanGame,
    globalIndex: Int,
    rowGridIndex: Int,
    colIndex: Int,
    coverWidth: Dp,
    coverHeight: Dp,
    chamfer: Dp,
    boxShape: androidx.compose.ui.graphics.Shape,
    currentLabel: String,
    isCurrent: Boolean,
    hideTitleTag: Boolean,
    accent: Color,
    highlightStroke: Color,
    motion: ShelfCatalogMotion,
    batchChangedAt: Long,
    onClick: () -> Unit,
) {
    DisposableEffect(motion, game.uri) {
        onDispose { motion.forgetSlot(game.uri, ShelfCatalogLayoutMode.COVERS) }
    }
    val cover by rememberCoverBitmap(game.coverUri, CoverDecodeTier.GRID)
    val reveal = rememberCoverReveal(cover != null, CoverSurface.Flow)
    val engineColor = game.engine.coverColor()
    val actualHeight = remember(game.uri, coverHeight) {
        coverHeight * shelfCatalogCoverHeightFactor(game.uri)
    }
    val title = remember(game.title, hideTitleTag) {
        if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    }

    ShelfCatalogCoverFace(
        game = game,
        globalIndex = globalIndex,
        coverWidth = coverWidth,
        coverHeight = actualHeight,
        isCurrent = isCurrent,
        accent = accent,
        highlightStroke = highlightStroke,
        engineColor = engineColor,
        cover = cover,
        revealModifier = reveal.image(Modifier.fillMaxSize()),
        chamfer = chamfer,
        boxShape = boxShape,
        modifier = Modifier
            .shelfBatchArrival(batchChangedAt, colIndex)
            .then(if (isCurrent) Modifier.offset(y = COVER_FOCUS_LIFT) else Modifier)
            .shelfCatalogSlotVisible(motion, game.uri)
            .shelfCatalogSlotPlaced(motion, game.uri, rowGridIndex, colIndex, ShelfCatalogLayoutMode.COVERS)
            // 退场期间触摸是递给下面书架的，书不再按压反馈、也不再响应点选
            .pressSquish(enabled = !motion.closing, scale = PressSquishDefaults.CardScale)
            .clickable(
                enabled = !motion.closing,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (isCurrent) "$title ($currentLabel)" else title
            },
    )
}

/** 封面陈列实体封面与 3D 飞行替身正面细节（确保入柜/出柜切角与左下角标 1:1 交接）。 */
@Composable
private fun ShelfCatalogCoverFace(
    game: ScanGame,
    globalIndex: Int,
    coverWidth: Dp,
    coverHeight: Dp,
    isCurrent: Boolean,
    accent: Color,
    highlightStroke: Color,
    engineColor: Color,
    cover: ImageBitmap?,
    revealModifier: Modifier,
    chamfer: Dp,
    boxShape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
) {
    val ribColor = engineColor.copy(alpha = 0.72f)
    val ribHighlight = ShelfChamferLight.copy(alpha = 0.45f)
    val currentOuterGilt = ShelfSelectedGilt.copy(alpha = 0.95f)
    val currentInnerGilt = ShelfSelectedGilt.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .width(coverWidth)
            .height(coverHeight)
            .clip(boxShape)
            .background(engineColor),
        contentAlignment = Alignment.Center,
    ) {
        if (cover == null) ShelfCoverPlaceholder(game.engine.displayName, coverWidth)
        cover?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = revealModifier,
            )
        }
        if (isShelfLastPlayed(game)) {
            ShelfCoverRibbon(coverWidth, accent)
        }
        // 将高光/倒角光/左侧装订棱/当前金框合并为一处 drawWithCache，每本立省 5+ 个 LayoutNode
        Spacer(
            Modifier
                .fillMaxSize()
                .shelfBevelOverlay(ShelfGloss, ShelfDepthShade)
                .shelfGlossOverlay(ShelfGloss)
                .shelfChamferGlintsOverlay(chamfer, { false }, ShelfChamferLight, ShelfChamferDim)
                .drawWithCache {
                    val ribW = 3.5.dp.toPx()
                    val creaseW = 0.8.dp.toPx()
                    val outerStroke = Stroke(width = 1.dp.toPx())
                    val inset = 2.5.dp.toPx()
                    val innerStroke = Stroke(width = 0.6.dp.toPx())
                    val outerOutline = if (isCurrent) boxShape.createOutline(size, layoutDirection, this) else null
                    val innerSize = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f))
                    val innerOutline = if (isCurrent && innerSize.width > 0f && innerSize.height > 0f) {
                        boxShape.createOutline(innerSize, layoutDirection, this)
                    } else null
                    onDrawBehind {
                        drawRect(ribColor, topLeft = Offset.Zero, size = Size(ribW, size.height))
                        drawRect(ribHighlight, topLeft = Offset(ribW, 0f), size = Size(creaseW, size.height))
                        if (outerOutline != null) {
                            drawOutline(outerOutline, currentOuterGilt, style = outerStroke)
                            if (innerOutline != null) {
                                withTransform({ translate(inset, inset) }) {
                                    drawOutline(innerOutline, currentInnerGilt, style = innerStroke)
                                }
                            }
                        }
                    }
                },
        )
        // 左下角精致序号标
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 5.dp)
                .clip(AppComponentShape)
                .background(ShelfObiSurface, AppComponentShape)
                .padding(horizontal = 5.dp, vertical = 2.dp)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(Modifier.size(4.5.dp).background(engineColor, CircleShape))
            Text(
                text = String.format(Locale.ROOT, "%02d", globalIndex + 1),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = ShelfObiText,
                maxLines = 1,
            )
        }
    }
}

/**
 * 全员 3D 飞行编队浮层：
 * 按距锚点距离由远及近绘制，锚点/主角永远在最前端，绝不被交叉邻书遮挡。
 */
@Composable
private fun ShelfCatalogTroupeOverlay(
    motion: ShelfCatalogMotion,
    hideTitleTag: Boolean,
) {
    val troupe = motion.activeTroupe
    if (troupe.isEmpty()) return
    val originProvider: () -> Offset = remember(motion) { { motion.overlayOrigin } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .shelfCatalogTroupePresence(motion),
    ) {
        for (flight in troupe) {
            key(flight.game.uri) {
                ShelfCatalogTroupeBook(
                    flight = flight,
                    motion = motion,
                    originProvider = originProvider,
                    hideTitleTag = hideTitleTag,
                )
            }
        }
    }
}

/** 飞行编队封面的 3D 倒角玻璃描边（绘制期按实时朝向选左右倒角轮廓，不触发重组）。 */
private fun Modifier.shelfTroupeFrontGlassBorder(
    shapes: ShelfBoxShapes,
    spineOnRight: () -> Boolean,
): Modifier = if (!AppThemeColors.isGlass) {
    this
} else {
    drawWithContent {
        drawContent()
        val borderShape = if (spineOnRight()) shapes.frontWithSpineRight else shapes.frontWithSpineLeft
        drawOutline(
            outline = borderShape.createOutline(size, layoutDirection, this),
            color = GlassBorder,
            style = Stroke(width = 0.5.dp.toPx()),
        )
    }
}

@Composable
private fun ShelfCatalogTroupeBook(
    flight: ShelfCatalogBookFlight,
    motion: ShelfCatalogMotion,
    originProvider: () -> Offset,
    hideTitleTag: Boolean,
) {
    val game = flight.game
    val density = LocalDensity.current
    val baseHeightPx = flight.shelfOrigin.boxHeightPx.coerceAtLeast(60f)
    val baseCoverWidthPx = flight.shelfOrigin.coverWidthPx.coerceAtLeast(45f)
    val baseSpineWidthPx = flight.shelfOrigin.spineThicknessPx.coerceAtLeast(12f)
    val originScale = flight.shelfOrigin.shelfPoseScale.coerceIn(0.5f, 1.5f)
    val layoutHeightPx = (baseHeightPx / originScale).coerceAtLeast(60f)
    val layoutCoverWidthPx = (baseCoverWidthPx / originScale).coerceAtLeast(45f)
    val layoutSpineWidthPx = (baseSpineWidthPx / originScale).coerceAtLeast(12f)
    val catRectW = flight.catalogRect.width.coerceAtLeast(12f)
    val catRectH = flight.catalogRect.height.coerceAtLeast(12f)
    val containerWidthPx = maxOf(baseCoverWidthPx, layoutCoverWidthPx, catRectW)
    val containerHeightPx = maxOf(baseHeightPx, layoutHeightPx, catRectH)

    val containerW = with(density) { containerWidthPx.toDp() }
    val containerH = with(density) { containerHeightPx.toDp() }
    val layoutCoverW = with(density) { layoutCoverWidthPx.toDp() }
    val layoutSpineW = with(density) { layoutSpineWidthPx.toDp() }
    val layoutH = with(density) { layoutHeightPx.toDp() }
    val catW = with(density) { catRectW.toDp() }
    val catH = with(density) { catRectH.toDp() }

    val chamfer = remember(layoutCoverW) { shelfChamfer(layoutCoverW) }
    val shapes = remember(chamfer) { ShelfBoxShapes(chamfer) }
    val catChamfer = remember(catW) { shelfChamfer(catW) }
    val catShapes = remember(catChamfer) { ShelfBoxShapes(catChamfer) }
    val verticalSpineShape = remember {
        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 1.dp, bottomEnd = 1.dp)
    }
    val engineColor = game.engine.coverColor()
    val accent = MaterialTheme.colorScheme.primary
    val highlightStroke = shelfCatalogSelectedChipBorder(accent)
    val gridCover by rememberCoverBitmap(game.coverUri, CoverDecodeTier.GRID)
    val heroCover by rememberCoverBitmap(
        game.coverUri.takeIf { flight.useHeroCover },
        motion.heroDecodeTier,
    )
    val cover = heroCover ?: gridCover
    val wash = rememberShelfSpineWash(gridCover ?: heroCover)
    val spineTitle = remember(game.title, hideTitleTag) {
        if (hideTitleTag) GameTitleTags.stripAll(game.title) else game.title
    }
    val poseProvider: () -> ShelfCatalogBookPose = remember(motion, flight) {
        { motion.currentBookPose(flight) }
    }

    // 外层控制屏幕底边中心定位；内部 BottomCenter 对齐保证横竖高矮变化及 tilt/lean 严格绕书底中心旋转
    Box(
        modifier = Modifier
            .size(containerW, containerH)
            .shelfCatalogTroupeOuter(
                poseProvider = poseProvider,
                originProvider = originProvider,
                containerWidthPx = containerWidthPx,
                containerHeightPx = containerHeightPx,
            )
            .then(if (flight.fadeInPlace) Modifier.shelfCatalogTroupeAlpha { motion.fadeInPlaceAlpha() } else Modifier),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 中层控制单排 Hero 仰角 tilt 与微侧斜倚 lean，旋转中心与单排 / 书脊斜靠支点严格同源
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shelfCatalogTroupeTilt(
                    poseProvider = poseProvider,
                    containerWidthPx = containerWidthPx,
                    catalogMode = flight.catalogMode,
                    catalogLeanDegrees = flight.catalogLeanDegrees,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // 0. 底壳材质层（封面柔焦底色 + 顶缘引擎色条 + 棱边 + 同步背光压暗）：自由延展宽高不拉伸字形，并在圆角立脊接棒后退场
            Box(
                modifier = Modifier
                    .size(layoutSpineW, layoutH)
                    .shelfCatalogTroupeShell(
                        poseProvider = poseProvider,
                        baseSpineWidthPx = layoutSpineWidthPx,
                        baseHeightPx = layoutHeightPx,
                        isSpinesCatalog = flight.catalogMode == ShelfCatalogLayoutMode.SPINES,
                    )
                    .background(engineColor),
            ) {
                ShelfSpineBase(wash, rememberShelfSpineInk(wash, engineColor))
                Box(Modifier.fillMaxSize().shelfBevelOverlay(ShelfGloss, ShelfDepthShade))
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfCatalogTroupeAlpha(modulate = true) { poseProvider().spineShade }
                        .background(ShelfFaceShade),
                )
            }

            // 1. 单排比例 1:1 书脊字面（严格等比缩放；SPINES 模式平稳接力淡出，与 CatalogVertical 连续交叠、绝无空档闪白）
            Box(
                modifier = Modifier
                    .size(layoutSpineW, layoutH)
                    .shelfCatalogTroupeShelfText(
                        poseProvider = poseProvider,
                        baseHeightPx = layoutHeightPx,
                        isCoverCatalog = flight.catalogMode == ShelfCatalogLayoutMode.COVERS,
                    ),
            ) {
                ShelfSpineFaceContent(
                    title = spineTitle,
                    coverHeight = layoutH,
                    wash = wash,
                    engineColor = engineColor,
                    lastPlayed = isShelfLastPlayed(game),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfCatalogTroupeAlpha(modulate = true) { poseProvider().spineShade }
                        .background(ShelfFaceShade),
                )
            }

            // 2. 书架立排槽位 1:1 精装立脊（SPINES 模式后段柔和接力淡入，与静态列表槽 1:1 像素对齐）
            if (flight.catalogMode == ShelfCatalogLayoutMode.SPINES) {
                ShelfCatalogVerticalSpineFace(
                    title = spineTitle,
                    uri = game.uri,
                    spineWidth = catW,
                    boxHeight = catH,
                    isCurrent = flight.isCurrentInCatalog,
                    lastPlayed = isShelfLastPlayed(game),
                    engineColor = engineColor,
                    wash = wash,
                    spineShape = verticalSpineShape,
                    recessShadeAlpha = if (flight.isCurrentInCatalog) 0f else shelfCatalogSpineRecess(game.uri).shadeAlpha,
                    modifier = Modifier.shelfCatalogTroupeCatalogVertical(
                        poseProvider = poseProvider,
                        catWidthPx = catRectW,
                        catHeightPx = catRectH,
                    ),
                )
            }

            // 3. 3D 封面正面：绕共用装订棱旋转开合（含单排丝带、背光压暗与启动徽章）
            Box(
                modifier = Modifier
                    .size(layoutCoverW, layoutH)
                    .shelfCatalogTroupeFront(
                        poseProvider = poseProvider,
                        shapes = shapes,
                        baseCoverWidthPx = layoutCoverWidthPx,
                        baseHeightPx = layoutHeightPx,
                    )
                    .background(engineColor)
                    .shelfTroupeFrontGlassBorder(shapes) { poseProvider().facingSign < 0f },
                contentAlignment = Alignment.Center,
            ) {
                if (cover == null) {
                    // 占位字一直保持不透明，由盖在上面的封面陈列层（它自带同一份占位字，大小按封面宽度等比）淡入时自然接棒：
                    // 原来这里的字先淡掉、上面那层的字还没淡进来，字在途中先暗一下再亮起，看起来是突然变了一次
                    ShelfCoverPlaceholder(game.engine.displayName, coverWidth = layoutCoverW)
                }
                cover?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isShelfLastPlayed(game)) {
                    ShelfCoverRibbon(layoutCoverW, accent)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfBevelOverlay(ShelfGloss, ShelfDepthShade)
                        .shelfGlossOverlay(ShelfGloss),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfCatalogTroupeAlpha(modulate = true) {
                            ((76f - poseProvider().absYawDeg) / 16f).coerceIn(0f, 1f)
                        }
                        .shelfChamferGlintsOverlay(
                            chamfer = chamfer,
                            leftProvider = { poseProvider().facingSign < 0f },
                            light = ShelfChamferLight,
                            dim = ShelfChamferDim,
                        ),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .shelfCatalogTroupeAlpha(modulate = true) { poseProvider().frontShade }
                        .background(ShelfFaceShade),
                )
                if (flight.shelfOrigin.heroFactor > 0.05f || (flight.takeoffPose?.playBadgeAlpha ?: 0f) > 0f) {
                    Box(Modifier.fillMaxSize()) {
                        ShelfPlayBadge(
                            visibility = { poseProvider().playBadgeAlpha },
                            onClick = null,
                        )
                    }
                }
            }

            // 4. COVERS 模式 1:1 完整封面层（与静态封面槽共享 ShelfCatalogCoverFace，丝带/文字/切角/描边零误差逐像素交接）
            if (flight.catalogMode == ShelfCatalogLayoutMode.COVERS) {
                ShelfCatalogCoverFace(
                    game = game,
                    globalIndex = flight.spineIndex,
                    coverWidth = catW,
                    coverHeight = catH,
                    isCurrent = flight.isCurrentInCatalog,
                    accent = accent,
                    highlightStroke = highlightStroke,
                    engineColor = engineColor,
                    cover = gridCover ?: heroCover,
                    revealModifier = Modifier.fillMaxSize(),
                    chamfer = catChamfer,
                    boxShape = catShapes.frontWithSpineLeft,
                    modifier = Modifier.shelfCatalogTroupeCoverFaceTransform(
                        poseProvider = poseProvider,
                        catCoverWidthPx = catRectW,
                        catHeightPx = catRectH,
                        shapes = catShapes,
                    ),
                )
            }
        }
    }
}

private val DECK_OUTER_H_PAD = 14.dp
private val DECK_OUTER_V_PAD = 6.dp
private val DECK_INNER_H_PAD = 14.dp
private val DECK_INNER_V_PAD = 8.dp
/** `AppSearchField`（Miuix SearchBar）自带的左右外边距。 */
private val SEARCH_FIELD_SIDE_INSET = 12.dp
private val DECK_LAYOUT_TABS_WIDTH = 138.dp
private val VERTICAL_TIER_TOP_ROOM = 10.dp
private val VERTICAL_TIER_PLAQUE_EXTRA = 12.dp
private val SPINE_FOCUS_LIFT = (-3).dp
private val COVER_FOCUS_LIFT = (-4).dp
private val COVER_TIER_TOP_ROOM = 24.dp
private val COVER_TIER_GAP_COMPACT = 8.dp
private val COVER_TIER_GAP_WIDE = 10.dp
private val COVER_TITLE_BAND_HEIGHT = 42.dp
private const val COVER_ASPECT_RATIO = 0.75f
private val TIER_LEDGE_DEPTH = 6.dp
private val TIER_BOARD_EDGE = 3.dp
private val TIER_UNDER_SHADOW = 10.dp

internal data class ShelfCatalogGridCells(
    val columns: Int,
    val cabinetInset: Dp,
    val cellWidth: Dp,
    val gap: Dp,
    val pitchPx: Int,
)

internal fun Density.shelfCatalogGridCells(width: Dp, mode: ShelfCatalogLayoutMode): ShelfCatalogGridCells {
    val compact = width < 460.dp
    val cabinetInset = shelfCatalogCabinetInset(width)
    val cabinetWidth = (width.roundToPx() - 2 * cabinetInset.roundToPx()).coerceAtLeast(0)
    val innerWidth = (cabinetWidth - 2 * (CABINET_STILE_WIDTH + CABINET_INTERIOR_INSET).roundToPx())
        .coerceAtLeast(0)
    if (mode == ShelfCatalogLayoutMode.SPINES) {
        val spineHeight = shelfCatalogSpineHeight(width)
        val baseWidth = shelfCatalogSpineBaseWidth(spineHeight)
        val gap = VERTICAL_SPINE_NOMINAL_GAP
        val columns = shelfCatalogVerticalColumns(width)
        return ShelfCatalogGridCells(
            columns = columns,
            cabinetInset = cabinetInset,
            cellWidth = baseWidth,
            gap = gap,
            pitchPx = (baseWidth.toPx() + gap.toPx()).roundToInt(),
        )
    }
    val gap = if (compact) COVER_TIER_GAP_COMPACT else COVER_TIER_GAP_WIDE
    val gapPx = gap.roundToPx()
    val minWidth = 74.dp
    val maxWidth = 146.dp
    val preferredColumns = shelfCatalogCoverColumns(cabinetWidth.toDp())
    val columns = ((innerWidth + gapPx) / (minWidth.roundToPx().coerceAtLeast(1) + gapPx))
        .coerceIn(1, preferredColumns)
    val cellWidth = ((innerWidth - gapPx * (columns - 1)) / columns).toDp().coerceAtMost(maxWidth)
    return ShelfCatalogGridCells(columns, cabinetInset, cellWidth, gap, cellWidth.roundToPx() + gapPx)
}
