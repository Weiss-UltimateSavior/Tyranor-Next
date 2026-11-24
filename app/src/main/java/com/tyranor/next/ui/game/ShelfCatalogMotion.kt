package com.tyranor.next.ui.game

import android.animation.ValueAnimator
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * 书架全景「藏书阁」的立体几何、双形态排架（书脊 / 封面）、全员同步入柜/出柜编队动效。
 *
 * - 长按单排书架任意游戏盒进入藏书阁：屏幕内全部可见游戏盒组成飞行编队——「书脊」模式下大封面沿
 *   3D 装订轴顺滑合拢转正书脊、两侧书脊同步脱离斜轨，分波次滑入立体藏书柜对应槽位；「封面」模式下
 *   大封面摆正落架，两侧立脊空中 3D 翻开封面陈列于书柜搁板；
 * - 飞行替身拆分「底壳伸缩」与「1:1 等比非重叠字形交接」（单排书脊比例先淡出后再淡入藏书阁书脊比例），
 *   彻底杜绝拉伸压扁圆点与中段重字双影，也消除落位刹那描边闪跳；
 * - 进场零首帧透出（所有背景/边框在首帧前均保持透明）、退场严格限定单排视口内（不越界遮挡左侧导航栏）、
 *   落稳后再触发全壁封面色带解码。
 */

/** 藏书阁两种陈列形态：书脊立排（默认）/ 封面陈列。 */
internal enum class ShelfCatalogLayoutMode {
    SPINES,
    COVERS,
}

/** 书架竖排书脊每层书本容量（窄屏 6 本 ~ 平板横屏 21 本，呈现修长紧致的精装立脊节奏）。 */
internal fun shelfCatalogVerticalColumns(widthDp: Dp): Int = when {
    widthDp < 330.dp -> 6
    widthDp < 430.dp -> 8
    widthDp < 560.dp -> 10
    widthDp < 720.dp -> 12
    widthDp < 960.dp -> 15
    widthDp < 1200.dp -> 18
    else -> 21
}

/** 藏书阁封面陈列每层书本容量（窄屏 3 本 ~ 平板横屏 8 本）。 */
internal fun shelfCatalogCoverColumns(widthDp: Dp): Int = when {
    widthDp < 480.dp -> 3
    widthDp < 580.dp -> 4
    widthDp < 740.dp -> 5
    widthDp < 980.dp -> 6
    widthDp < 1200.dp -> 7
    else -> 8
}

/** 藏书阁列表行模型。 */
internal sealed interface ShelfCatalogRowItem {
    val key: String

    data class SectionHeader(
        override val key: String,
        val engine: EngineType,
        val count: Int,
    ) : ShelfCatalogRowItem

    data class VerticalTier(
        override val key: String,
        val tierIndex: Int,
        val games: List<ScanGame>,
        val startIndex: Int,
        val rowGridIndex: Int,
    ) : ShelfCatalogRowItem

    data class CoverTier(
        override val key: String,
        val tierIndex: Int,
        val games: List<ScanGame>,
        val startIndex: Int,
        val rowGridIndex: Int,
    ) : ShelfCatalogRowItem
}

/** 共用的按引擎分架/平铺切分构建器，避免竖排书脊与封面陈列重复维护分段逻辑。 */
private inline fun buildTieredCatalogRows(
    games: List<ScanGame>,
    columns: Int,
    groupByEngine: Boolean,
    sectionPrefix: String,
    tierPrefix: String,
    crossinline createTier: (key: String, tierIndex: Int, slice: List<ScanGame>, startIndex: Int, rowGridIndex: Int) -> ShelfCatalogRowItem,
): List<ShelfCatalogRowItem> {
    if (games.isEmpty()) return emptyList()
    val cols = columns.coerceAtLeast(1)
    val rows = ArrayList<ShelfCatalogRowItem>(games.size / cols + 4)
    val multipleEngines = groupByEngine && games.mapTo(HashSet()) { it.engine }.size >= 2
    if (!multipleEngines) {
        var idx = 0
        var tier = 0
        while (idx < games.size) {
            val end = (idx + cols).coerceAtMost(games.size)
            val slice = games.subList(idx, end)
            rows += createTier("${tierPrefix}_${tier}_${slice.first().uri}", tier, slice, idx, tier)
            tier++
            idx = end
        }
        return rows
    }
    var globalIndex = 0
    var tier = 0
    var sectionStart = 0
    while (sectionStart < games.size) {
        val engine = games[sectionStart].engine
        var sectionEnd = sectionStart + 1
        while (sectionEnd < games.size && games[sectionEnd].engine == engine) {
            sectionEnd++
        }
        rows += ShelfCatalogRowItem.SectionHeader(
            key = "${sectionPrefix}_${engine.name}_$sectionStart",
            engine = engine,
            count = sectionEnd - sectionStart,
        )
        var cursor = sectionStart
        while (cursor < sectionEnd) {
            val end = (cursor + cols).coerceAtMost(sectionEnd)
            val slice = games.subList(cursor, end)
            rows += createTier(
                "${tierPrefix}_${engine.name}_${tier}_${slice.first().uri}",
                tier,
                slice,
                globalIndex,
                tier,
            )
            globalIndex += slice.size
            tier++
            cursor = end
        }
        sectionStart = sectionEnd
    }
    return rows
}

/** 按竖排书脊方式切分藏书阁层级。 */
internal fun buildShelfCatalogVerticalRows(
    games: List<ScanGame>,
    columns: Int,
    groupByEngine: Boolean,
): List<ShelfCatalogRowItem> = buildTieredCatalogRows(
    games = games,
    columns = columns,
    groupByEngine = groupByEngine,
    sectionPrefix = "vsec",
    tierPrefix = "vtier",
) { key, tierIndex, slice, startIndex, rowGridIndex ->
    ShelfCatalogRowItem.VerticalTier(key, tierIndex, slice, startIndex, rowGridIndex)
}

/** 按封面陈列方式切分藏书阁搁板层级。 */
internal fun buildShelfCatalogCoverRows(
    games: List<ScanGame>,
    columns: Int,
    groupByEngine: Boolean,
): List<ShelfCatalogRowItem> = buildTieredCatalogRows(
    games = games,
    columns = columns,
    groupByEngine = groupByEngine,
    sectionPrefix = "csec",
    tierPrefix = "ctier",
) { key, tierIndex, slice, startIndex, rowGridIndex ->
    ShelfCatalogRowItem.CoverTier(key, tierIndex, slice, startIndex, rowGridIndex)
}

/**
 * 藏书阁首屏 / 换批后要对准的那一本：[uri] 仍在结果里就用它，否则取最近玩过的一本，再否则第一本
 * （与书架的 [shelfFocusPage] 同一口径）。结果为空返回 null。
 */
internal fun shelfCatalogFocusUri(games: List<ScanGame>, uri: String?): String? =
    games.getOrNull(shelfFocusPage(games, uri))?.uri

/** 定位包含 [uri] 的搁板行下标（若无匹配则返回 -1）。 */
internal fun findShelfCatalogAnchorRow(rows: List<ShelfCatalogRowItem>, uri: String?): Int {
    if (uri.isNullOrBlank()) return -1
    return rows.indexOfFirst { row ->
        when (row) {
            is ShelfCatalogRowItem.VerticalTier -> row.games.any { it.uri == uri }
            is ShelfCatalogRowItem.CoverTier -> row.games.any { it.uri == uri }
            is ShelfCatalogRowItem.SectionHeader -> false
        }
    }
}

/**
 * 计算书架打开时的首屏滚动起始行：
 * - 若锚点行紧跟在引擎章节横梁之后，从该横梁起始，保证章节标题与锚点行完整置于顶部视野；
 * - 若视口可舒适容纳 3 层及以上，向上多露 1 层让锚点居中、上下邻书皆可参与飞入编队；
 * - 矮视口（≤ 2 层）直接从锚点行起始，绝不把被长按的主角挤出视口下沿。
 */
internal fun shelfCatalogInitialRow(
    rows: List<ShelfCatalogRowItem>,
    anchorUri: String?,
    visibleTiersHint: Int,
): Int {
    val anchor = findShelfCatalogAnchorRow(rows, anchorUri)
    if (anchor <= 0) return 0
    if (rows[anchor - 1] is ShelfCatalogRowItem.SectionHeader) return anchor - 1
    return if (visibleTiersHint >= 3) (anchor - 1).coerceAtLeast(0) else anchor
}

/** 定位包含 [uri] 的书格在网格中的逻辑行列坐标 `(rowGridIndex, colIndex)`。 */
internal fun findShelfCatalogGridCoord(rows: List<ShelfCatalogRowItem>, uri: String?): Pair<Int, Int>? {
    if (uri.isNullOrBlank()) return null
    for (row in rows) {
        when (row) {
            is ShelfCatalogRowItem.VerticalTier -> {
                val col = row.games.indexOfFirst { it.uri == uri }
                if (col >= 0) return row.rowGridIndex to col
            }
            is ShelfCatalogRowItem.CoverTier -> {
                val col = row.games.indexOfFirst { it.uri == uri }
                if (col >= 0) return row.rowGridIndex to col
            }
            is ShelfCatalogRowItem.SectionHeader -> Unit
        }
    }
    return null
}

/** 周围书格径向波纹进度。 */
internal fun shelfCatalogCascadeProgress(master: Float, gridDistance: Float): Float {
    val m = master.coerceIn(0f, 1f)
    if (m <= 0f) return 0f
    if (m >= 1f) return 1f
    val delay = (gridDistance.coerceAtLeast(0f) * CATALOG_CASCADE_STEP).coerceAtMost(CATALOG_CASCADE_MAX_DELAY)
    val span = (1f - delay).coerceAtLeast(0.32f)
    val t = ((m - delay) / span).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 竖排每本书高矮起伏比例。 */
internal fun shelfCatalogSpineHeightFactor(uri: String): Float {
    val trait = shelfBookTrait(uri)
    val t = ((1f - trait.sizeScale) / 0.065f).coerceIn(0f, 1f)
    return 1f - t * 0.11f
}

/** 竖排未满层末尾那本书自然向左靠在前一本肩上的斜倚角度（度），右下角微抬不刺穿搁板。 */
internal fun shelfCatalogEndBookLeanDegrees(
    colIndex: Int,
    tierCount: Int,
    maxColumns: Int,
    isCurrent: Boolean,
): Float {
    if (isCurrent || tierCount <= 1 || tierCount >= maxColumns || colIndex != tierCount - 1) return 0f
    return -6.5f
}

/**
 * 校验藏书阁列表中的书格是否处于 [ShelfCatalogWall] 可视视口之内，防止在列表滚动或靠后锚点处
 * 仅底部文字露出一丝、本体已被裁切在顶部检索台下方的书进入全屏飞行编队而浮现于检索栏上方。
 */
internal fun isShelfCatalogSlotInViewport(
    slotRect: Rect,
    viewport: Rect,
    allowPartial: Boolean = false,
    tolerancePx: Float = 6f,
): Boolean {
    if (viewport.width <= 0f || viewport.height <= 0f) return true
    if (slotRect.width <= 0f || slotRect.height <= 0f) return false
    if (!allowPartial) {
        return slotRect.top >= viewport.top - tolerancePx &&
            slotRect.bottom <= viewport.bottom + tolerancePx &&
            slotRect.left >= viewport.left - tolerancePx &&
            slotRect.right <= viewport.right + tolerancePx
    }
    val overlapY = (min(slotRect.bottom, viewport.bottom) - maxOf(slotRect.top, viewport.top)).coerceAtLeast(0f)
    return overlapY >= slotRect.height * 0.35f &&
        slotRect.top >= viewport.top - slotRect.height * 0.45f
}

/** 藏书阁单本书盒从单排书架测得的未沉降完整几何信息。 */
internal class ShelfCatalogBookOrigin(
    val uri: String,
    val pageIndex: Int,
    val frontRect: Rect,
    val spineRect: Rect,
    val spineThicknessPx: Float,
    val boxHeightPx: Float,
    val coverWidthPx: Float,
    val yawDegrees: Float,
    val tiltDegrees: Float,
    val tint: Color,
    val isHero: Boolean,
    val leanDegrees: Float = 0f,
    val frontShade: Float = 0f,
    val spineShade: Float = 0f,
    val heroFactor: Float = if (isHero) 1f else 0f,
    val shelfPoseScale: Float = 1f,
)

/**
 * 与单排书架 [ShelfBox] 完全同源的闭式几何投影，直接算出任意槽位书盒在未退缩舞台坐标系中的精确屏上位置、
 * 缩放尺寸、转角、斜倚角与明暗强度（彻底规避在 3D `graphicsLayer` 内侧采样 `localToScreen` 产生的透视漂移）。
 */
internal fun shelfCatalogBookOriginFromGeometry(
    uri: String,
    pageIndex: Int,
    offset: Float,
    geometry: ShelfGeometry,
    rowShiftPx: Float,
    rail: ShelfRail,
    pagerZeroCenterX: Float,
    floorYScreen: Float,
    liftPx: Float,
    trait: ShelfBookTrait,
    tint: Color,
    stageOverflowPx: Float = 0f,
): ShelfCatalogBookOrigin {
    val railX = geometry.centerX(offset) + rowShiftPx
    val railScale = rail.scale(railX)
    val railLift = rail.lift(railX)
    val t = shelfSideness(offset)
    val hero = shelfHeroness(offset)
    val poseScale = shelfPoseScale(offset) * (1f - (1f - trait.sizeScale) * t)
    val totalScale = poseScale * railScale

    val yawRad = geometry.yaw(offset)
    val yawDeg = Math.toDegrees(yawRad.toDouble()).toFloat()
    val turn = abs(yawRad)
    val facingSign = if (yawRad >= 0f) 1f else -1f

    val visibleW = geometry.visibleWidth(turn)
    val spineVisW = geometry.spineDepth * sin(turn)
    val boxCenterX = pagerZeroCenterX + rail.screenX(railX)
    val sCenterX = boxCenterX - facingSign * 0.5f * (visibleW - spineVisW) * totalScale
    val bookBottomY = floorYScreen + railLift - liftPx * hero * railScale + totalScale * (stageOverflowPx * 0.5f)

    val boxHeightPx = (geometry.coverHeight * totalScale).coerceAtLeast(1f)
    val coverWidthPx = (geometry.coverWidth * totalScale).coerceAtLeast(1f)
    val spineThicknessPx = (geometry.spineDepth * totalScale).coerceAtLeast(1f)
    val sCenterY = bookBottomY - 0.5f * boxHeightPx

    val spineVisScaled = (spineThicknessPx * sin(turn)).coerceAtLeast(1f)
    val spineRect = Rect(
        sCenterX - 0.5f * spineVisScaled,
        bookBottomY - boxHeightPx,
        sCenterX + 0.5f * spineVisScaled,
        bookBottomY,
    )
    val frontCenterX = sCenterX + facingSign * (0.5f * spineVisScaled + 0.5f * coverWidthPx * cos(turn))
    val frontRect = Rect(
        frontCenterX - 0.5f * coverWidthPx,
        bookBottomY - boxHeightPx,
        frontCenterX + 0.5f * coverWidthPx,
        bookBottomY,
    )
    return ShelfCatalogBookOrigin(
        uri = uri,
        pageIndex = pageIndex,
        frontRect = frontRect,
        spineRect = spineRect,
        spineThicknessPx = spineThicknessPx,
        boxHeightPx = boxHeightPx,
        coverWidthPx = coverWidthPx,
        yawDegrees = yawDeg,
        tiltDegrees = HERO_TILT_DEGREES * hero,
        tint = tint,
        isHero = abs(offset) < 0.35f,
        leanDegrees = trait.restLeanDegrees * t,
        frontShade = geometry.faceShade(offset, spine = false),
        spineShade = geometry.faceShade(offset, spine = true),
        heroFactor = hero,
        shelfPoseScale = totalScale,
    )
}

private class ShelfCatalogSlotAnchor(
    var pageIndex: Int = 0,
    var frontCoords: LayoutCoordinates? = null,
    var spineCoords: LayoutCoordinates? = null,
    var yawRadiansProvider: () -> Float = { 0f },
    var tiltDegreesProvider: () -> Float = { 0f },
    var scaleProvider: () -> Float = { -1f },
    var leanDegreesProvider: () -> Float = { 0f },
    var frontShadeProvider: () -> Float = { 0f },
    var spineShadeProvider: () -> Float = { 0f },
    var heroFactorProvider: () -> Float = { 0f },
    var tintProvider: () -> Color = { Color.Unspecified },
)

/**
 * 单排书架与藏书阁全景之间的共享元素坐标台与交接舞台（由 `GameLibraryContent` 持有）。
 */
@Stable
internal class ShelfCatalogStage {
    private val anchors = HashMap<String, ShelfCatalogSlotAnchor>()
    private var stageCoords: LayoutCoordinates? = null

    var recedeProgress by mutableFloatStateOf(0f)
        internal set

    var lentUris by mutableStateOf<Set<String>>(emptySet())
        internal set

    var lentAlpha by mutableFloatStateOf(1f)
        internal set

    var pendingFocusUri by mutableStateOf<String?>(null)
        private set

    /** 藏书阁正在合上（退场）时，书架一被手指碰到就调它：立刻把退场收尾，不让手指等动画（由 [ShelfCatalogMotion] 登记）。 */
    internal var exitInterrupt: (() -> Unit)? = null

    fun interruptExit() {
        exitInterrupt?.invoke()
    }

    var focusRequestToken by mutableIntStateOf(0)
        private set

    var settledFocusToken by mutableIntStateOf(0)
        private set

    internal var stageDensity = 3f
    internal var heroDecodeTier: CoverDecodeTier = CoverDecodeTier.GRID
    private var stageGeometryOriginProvider: ((Offset, IntSize, String?) -> List<ShelfCatalogBookOrigin>)? = null

    internal fun bindStageGeometry(
        heroTier: CoverDecodeTier,
        provider: (Offset, IntSize, String?) -> List<ShelfCatalogBookOrigin>,
    ) {
        heroDecodeTier = heroTier
        stageGeometryOriginProvider = provider
    }

    internal fun unbindStageGeometry(
        provider: (Offset, IntSize, String?) -> List<ShelfCatalogBookOrigin>,
    ) {
        if (stageGeometryOriginProvider === provider) {
            stageGeometryOriginProvider = null
        }
    }

    internal fun placeStage(coordinates: LayoutCoordinates, density: Float) {
        stageCoords = coordinates
        if (density > 0f) stageDensity = density
    }

    fun stageSize(): IntSize? = stageCoords?.takeIf { it.isAttached }?.size

    internal fun placeSpine(uri: String, coordinates: LayoutCoordinates) {
        val slot = anchors.getOrPut(uri) { ShelfCatalogSlotAnchor() }
        slot.spineCoords = coordinates
    }

    internal fun placeFront(
        uri: String,
        pageIndex: Int,
        coordinates: LayoutCoordinates,
        yawRadiansProvider: () -> Float,
        tiltDegreesProvider: () -> Float,
        scaleProvider: () -> Float,
        leanDegreesProvider: () -> Float,
        frontShadeProvider: () -> Float,
        spineShadeProvider: () -> Float,
        heroFactorProvider: () -> Float,
        tintProvider: () -> Color,
    ) {
        val slot = anchors.getOrPut(uri) { ShelfCatalogSlotAnchor() }
        slot.pageIndex = pageIndex
        slot.frontCoords = coordinates
        slot.yawRadiansProvider = yawRadiansProvider
        slot.tiltDegreesProvider = tiltDegreesProvider
        slot.scaleProvider = scaleProvider
        slot.leanDegreesProvider = leanDegreesProvider
        slot.frontShadeProvider = frontShadeProvider
        slot.spineShadeProvider = spineShadeProvider
        slot.heroFactorProvider = heroFactorProvider
        slot.tintProvider = tintProvider
    }

    internal fun forget(uri: String) {
        anchors.remove(uri)
    }

    fun requestFocus(uri: String): Int {
        pendingFocusUri = uri
        return ++focusRequestToken
    }

    fun markFocusAligned(token: Int, uri: String): Boolean {
        if (token != focusRequestToken || pendingFocusUri != uri) return false
        settledFocusToken = token
        pendingFocusUri = null
        return true
    }

    fun clearPendingFocus(token: Int = focusRequestToken) {
        if (token == focusRequestToken) pendingFocusUri = null
    }

    fun release() {
        lentUris = emptySet()
        lentAlpha = 1f
        recedeProgress = 0f
    }

    private fun unscaleScreenPoint(pt: Offset): Offset {
        val r = recedeProgress.coerceIn(0f, 1f)
        if (r <= 0.001f) return pt
        val stage = stageCoords?.takeIf { it.isAttached } ?: return pt
        if (stage.size.width <= 0 || stage.size.height <= 0) return pt
        val stageScale = stageRecedeScale(r)
        val pivotLocal = Offset(stage.size.width * 0.5f, stage.size.height * STAGE_RECEDE_PIVOT_Y)
        // onPlaced 挂在 graphicsLayer 之前，pivotScreen 即未平移未缩放前的真实锚点坐标
        val pivotScreen = stage.localToScreen(pivotLocal)
        val stageShiftY = STAGE_RECEDE_SHIFT_DP * stageDensity * r
        val realPivotX = pivotScreen.x
        val realPivotY = pivotScreen.y
        return Offset(
            x = realPivotX + (pt.x - realPivotX) / stageScale,
            y = realPivotY + (pt.y - stageShiftY - realPivotY) / stageScale,
        )
    }

    private fun measureSlotOrigin(uri: String, slot: ShelfCatalogSlotAnchor): ShelfCatalogBookOrigin? {
        val front = slot.frontCoords?.takeIf { it.isAttached } ?: return null
        val spine = slot.spineCoords?.takeIf { it.isAttached } ?: return null
        if (front.size.width <= 0 || front.size.height <= 0 || spine.size.width <= 0 || spine.size.height <= 0) {
            return null
        }
        val yawDeg = Math.toDegrees(slot.yawRadiansProvider().toDouble()).toFloat()
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        val tiltDeg = slot.tiltDegreesProvider()
        val leanDeg = slot.leanDegreesProvider()
        val frontShade = slot.frontShadeProvider()
        val spineShade = slot.spineShadeProvider()
        val heroFactor = slot.heroFactorProvider()
        val facingSign = if (yawDeg >= 0f) 1f else -1f
        val providedScale = slot.scaleProvider()

        val coverWidthPx: Float
        val boxHeightPx: Float
        val spineThicknessPx: Float
        val sCenterX: Float
        val sCenterY: Float

        if (providedScale > 0f) {
            // 直接锚定正面与书脊共用的底缘转轴交点（落在 rotationX / rotationY 交线上，零透视畸变）
            val hingeLocalX = if (facingSign >= 0f) 0f else front.size.width.toFloat()
            val hingeBottom = unscaleScreenPoint(front.localToScreen(Offset(hingeLocalX, front.size.height.toFloat())))
            boxHeightPx = (front.size.height.toFloat() * providedScale).coerceAtLeast(1f)
            coverWidthPx = (front.size.width.toFloat() * providedScale).coerceAtLeast(1f)
            spineThicknessPx = (spine.size.width.toFloat() * providedScale).coerceAtLeast(1f)
            val spineVisHalf = 0.5f * spineThicknessPx * abs(sin(yawRad))
            sCenterX = hingeBottom.x - facingSign * spineVisHalf
            sCenterY = hingeBottom.y - 0.5f * boxHeightPx
        } else {
            val r = recedeProgress.coerceIn(0f, 1f)
            val stageScale = stageRecedeScale(r)
            val fTopL = front.localToScreen(Offset.Zero)
            val fBotL = front.localToScreen(Offset(0f, front.size.height.toFloat()))
            val fTopR = front.localToScreen(Offset(front.size.width.toFloat(), 0f))
            val fBotR = front.localToScreen(Offset(front.size.width.toFloat(), front.size.height.toFloat()))
            val fHeight = ((((fBotL.y - fTopL.y) + (fBotR.y - fTopR.y)) * 0.5f).coerceAtLeast(1f)) / stageScale
            coverWidthPx = fHeight * (front.size.width.toFloat() / front.size.height.toFloat())
            val sTopM = unscaleScreenPoint(spine.localToScreen(Offset(spine.size.width * 0.5f, 0f)))
            val sBotM = unscaleScreenPoint(spine.localToScreen(Offset(spine.size.width * 0.5f, spine.size.height.toFloat())))
            val sCenter = unscaleScreenPoint(spine.localToScreen(Offset(spine.size.width * 0.5f, spine.size.height * 0.5f)))
            boxHeightPx = (sBotM.y - sTopM.y).coerceAtLeast(1f)
            spineThicknessPx = boxHeightPx * (spine.size.width.toFloat() / spine.size.height.toFloat())
            sCenterX = sCenter.x
            sCenterY = sCenter.y
        }
        val rawSpineVisW = (spineThicknessPx * abs(sin(yawRad))).coerceAtLeast(1f)
        val spineRect = Rect(
            sCenterX - rawSpineVisW * 0.5f,
            sCenterY - boxHeightPx * 0.5f,
            sCenterX + rawSpineVisW * 0.5f,
            sCenterY + boxHeightPx * 0.5f,
        )
        val fCenterX = sCenterX + facingSign * (0.5f * rawSpineVisW + 0.5f * coverWidthPx * cos(yawRad))
        val frontRect = Rect(
            fCenterX - coverWidthPx * 0.5f,
            sCenterY - boxHeightPx * 0.5f,
            fCenterX + coverWidthPx * 0.5f,
            sCenterY + boxHeightPx * 0.5f,
        )
        val isHero = abs(yawDeg - HERO_YAW_DEGREES) < 8f
        return ShelfCatalogBookOrigin(
            uri = uri,
            pageIndex = slot.pageIndex,
            frontRect = frontRect,
            spineRect = spineRect,
            spineThicknessPx = spineThicknessPx,
            boxHeightPx = boxHeightPx,
            coverWidthPx = coverWidthPx,
            yawDegrees = yawDeg,
            tiltDegrees = tiltDeg,
            tint = slot.tintProvider(),
            isHero = isHero,
            leanDegrees = leanDeg,
            frontShade = frontShade,
            spineShade = spineShade,
            heroFactor = heroFactor,
            shelfPoseScale = providedScale.takeIf { it > 0f } ?: 1f,
        )
    }

    fun origin(uri: String): ShelfCatalogBookOrigin? {
        val stage = stageCoords?.takeIf { it.isAttached }
        val provider = stageGeometryOriginProvider
        if (stage != null && provider != null && stage.size.width > 0 && stage.size.height > 0) {
            val topLeft = stage.localToScreen(Offset.Zero)
            provider(topLeft, stage.size, uri).firstOrNull()?.let { return it }
        }
        return anchors[uri]?.let { measureSlotOrigin(uri, it) }
    }

    /**
     * 读取当前严格落在单排书架内容区视口内的全部可见书盒，绝不囊括越出左边界压住侧边导航栏的书页，按页码升序返回。
     */
    fun visibleOrigins(): List<ShelfCatalogBookOrigin> {
        val stage = stageCoords?.takeIf { it.isAttached } ?: return emptyList()
        if (stage.size.width <= 0 || stage.size.height <= 0) return emptyList()
        val stageTopLeft = stage.localToScreen(Offset.Zero)
        val stageLeftScreen = stageTopLeft.x
        val stageRightScreen = stageTopLeft.x + stage.size.width.toFloat()
        val provider = stageGeometryOriginProvider
        val candidates = if (provider != null) {
            provider(stageTopLeft, stage.size, null)
        } else {
            val measuredList = ArrayList<ShelfCatalogBookOrigin>(anchors.size)
            for ((uri, slot) in anchors) {
                measureSlotOrigin(uri, slot)?.let { measuredList += it }
            }
            measuredList
        }
        val list = ArrayList<ShelfCatalogBookOrigin>(12)
        for (measured in candidates) {
            // 仅收集书脊实体完全在书架左右净边界之内的书（主角除外），避免飞出侧栏外越界绘在导航轨上方
            val spineInside = measured.spineRect.left >= stageLeftScreen - 4f &&
                measured.spineRect.right <= stageRightScreen + 4f
            if (spineInside || measured.isHero) {
                list += measured
            }
        }
        list.sortBy { it.pageIndex }
        return list
    }

}

/** 飞行编队中单本书盒的起止契约（含目标书格陈列模式、末书斜倚角、当前高亮态及绘制层级优先级）。 */
internal class ShelfCatalogBookFlight(
    val game: ScanGame,
    val spineIndex: Int,
    val shelfOrigin: ShelfCatalogBookOrigin,
    val catalogRect: Rect,
    val catalogMode: ShelfCatalogLayoutMode,
    val delayFraction: Float,
    val arcLiftPx: Float,
    val returningToShelf: Boolean,
    val isCurrentInCatalog: Boolean,
    val zPriority: Float,
    val catalogLeanDegrees: Float = 0f,
    val takeoffPose: ShelfCatalogBookPose? = null,
    val useHeroCover: Boolean = shelfOrigin.isHero,
    val fadeInPlace: Boolean = false,
) {
    /** 同帧 `troupeFlight` 下跨 6 个修饰符复用姿态计算，避免重复三角计算与对象分配。 */
    internal var cachedRawProgress: Float = Float.NaN
    internal var cachedPose: ShelfCatalogBookPose? = null
    internal val landingPose: ShelfCatalogBookPose? =
        if (takeoffPose != null && !fadeInPlace) shelfCatalogPathPose(1f, this) else null

    fun fadeFrom(rawProgress: Float): ShelfCatalogBookFlight = ShelfCatalogBookFlight(
        game = game,
        spineIndex = spineIndex,
        shelfOrigin = shelfOrigin,
        catalogRect = catalogRect,
        catalogMode = catalogMode,
        delayFraction = 0f,
        arcLiftPx = 0f,
        returningToShelf = true,
        isCurrentInCatalog = isCurrentInCatalog,
        zPriority = zPriority,
        catalogLeanDegrees = catalogLeanDegrees,
        takeoffPose = shelfCatalogBookPose(rawProgress, this),
        useHeroCover = useHeroCover,
        fadeInPlace = true,
    )
}

/** 编队中单本书在任意进度帧的精确三维投影姿态（包含光学阴影、倾角、启动徽章与文字/装饰交接比例）。 */
internal data class ShelfCatalogBookPose(
    val spineCenterX: Float,
    val spineCenterY: Float,
    val spineWidth: Float,
    val spineHeight: Float,
    val coverWidth: Float,
    val absYawDeg: Float,
    val facingSign: Float,
    val tiltDegrees: Float,
    val frontAlpha: Float,
    /** `SPINES` 模式表示单排→书架槽位书脊字形切换；`COVERS` 模式表示左装订边、序号角标与当前框淡入比例。 */
    val catalogMix: Float,
    val leanDegrees: Float = 0f,
    val frontShade: Float = 0f,
    val spineShade: Float = 0f,
    val playBadgeAlpha: Float = 0f,
)

private fun catalogSmoothstep(x: Float): Float {
    val s = x.coerceIn(0f, 1f)
    return s * s * (3f - 2f * s)
}

/**
 * 纯函数插值单本飞行书盒的三维状态、光学明暗与视觉交接比例：
 * - `SPINES`：主角合书转正成竖排立脊、邻书顺滑滑入书脊槽位（含末本斜倚角平滑过渡），返回时 3D 翻开封面；
 * - `COVERS`：主角封面摆正归架、邻书先略微抬出后再 3D 翻开封面落至搁板（左侧邻书经 90° 正对换边，
 *   确保封面均为左装订右倒角零跳变），点选返回时选中者转露侧脊、邻书封面空中合卷收为立脊并恢复背光压暗。
 */
internal fun shelfCatalogBookPose(
    rawProgress: Float,
    flight: ShelfCatalogBookFlight,
): ShelfCatalogBookPose {
    if (flight.fadeInPlace) return checkNotNull(flight.takeoffPose)
    val delay = flight.delayFraction.coerceIn(0f, 0.4f)
    val span = (1f - delay).coerceAtLeast(0.1f)
    val t = ((rawProgress.coerceIn(0f, 1f) - delay) / span).coerceIn(0f, 1f)
    val start = flight.takeoffPose
    val end = flight.landingPose
    if (start != null && end != null) return shelfCatalogRetargetPose(t, flight, start, end)
    return shelfCatalogPathPose(t, flight)
}

private fun shelfCatalogPathPose(t: Float, flight: ShelfCatalogBookFlight): ShelfCatalogBookPose {
    val origin = flight.shelfOrigin
    val coverAspect = (origin.coverWidthPx / origin.boxHeightPx.coerceAtLeast(1f)).coerceIn(0.55f, 0.9f)
    val startAbsYaw = abs(origin.yawDegrees).coerceIn(0f, 90f)
    val originFacing = if (origin.yawDegrees >= 0f) 1f else -1f

    if (flight.catalogMode == ShelfCatalogLayoutMode.SPINES) {
        val catSpineW = flight.catalogRect.width.coerceAtLeast(1f)
        val catSpineH = flight.catalogRect.height.coerceAtLeast(1f)
        val arc = sin(PI.toFloat() * t)
        if (!flight.returningToShelf) {
            val cx = origin.spineRect.center.x + (flight.catalogRect.center.x - origin.spineRect.center.x) * t
            val cy = origin.spineRect.center.y + (flight.catalogRect.center.y - origin.spineRect.center.y) * t - flight.arcLiftPx * arc
            val sw = (origin.spineThicknessPx + (catSpineW - origin.spineThicknessPx) * t).coerceAtLeast(1f)
            val sh = (origin.boxHeightPx + (catSpineH - origin.boxHeightPx) * t).coerceAtLeast(1f)
            val foldPhase = (t / 0.72f).coerceIn(0f, 1f)
            val smoothFold = foldPhase * foldPhase * (3f - 2f * foldPhase)
            val absYaw = startAbsYaw + (90f - startAbsYaw) * smoothFold
            val tilt = origin.tiltDegrees * (1f - smoothFold)
            val lean = origin.leanDegrees + (flight.catalogLeanDegrees - origin.leanDegrees) * smoothFold
            val fShade = origin.frontShade * (1f - smoothFold)
            val sShade = origin.spineShade * (1f - smoothFold)
            val badgeAlpha = origin.heroFactor * (1f - catalogSmoothstep(t / 0.25f))
            val frontAlpha = ((89.8f - absYaw) / 3.5f).coerceIn(0f, 1f)
            val catalogMix = ((t - 0.20f) / 0.60f).coerceIn(0f, 1f)
            return ShelfCatalogBookPose(
                spineCenterX = cx,
                spineCenterY = cy,
                spineWidth = sw,
                spineHeight = sh,
                coverWidth = sh * coverAspect,
                absYawDeg = absYaw,
                facingSign = originFacing,
                tiltDegrees = tilt,
                frontAlpha = frontAlpha,
                catalogMix = catalogMix,
                leanDegrees = lean,
                frontShade = fShade,
                spineShade = sShade,
                playBadgeAlpha = badgeAlpha,
            )
        } else {
            val cx = flight.catalogRect.center.x + (origin.spineRect.center.x - flight.catalogRect.center.x) * t
            val cy = flight.catalogRect.center.y + (origin.spineRect.center.y - flight.catalogRect.center.y) * t - flight.arcLiftPx * arc
            val sw = (catSpineW + (origin.spineThicknessPx - catSpineW) * t).coerceAtLeast(1f)
            val sh = (catSpineH + (origin.boxHeightPx - catSpineH) * t).coerceAtLeast(1f)
            val unfoldPhase = ((t - 0.08f) / 0.88f).coerceIn(0f, 1f)
            val smoothUnfold = unfoldPhase * unfoldPhase * (3f - 2f * unfoldPhase)
            val absYaw = 90f + (startAbsYaw - 90f) * smoothUnfold
            val tilt = origin.tiltDegrees * smoothUnfold
            val lean = flight.catalogLeanDegrees + (origin.leanDegrees - flight.catalogLeanDegrees) * smoothUnfold
            val fShade = origin.frontShade * smoothUnfold
            val sShade = origin.spineShade * smoothUnfold
            val badgeAlpha = origin.heroFactor * catalogSmoothstep((t - 0.72f) / 0.28f)
            val frontAlpha = ((89.8f - absYaw) / 3.5f).coerceIn(0f, 1f)
            val catalogMix = 1f - ((t - 0.14f) / 0.60f).coerceIn(0f, 1f)
            return ShelfCatalogBookPose(
                spineCenterX = cx,
                spineCenterY = cy,
                spineWidth = sw,
                spineHeight = sh,
                coverWidth = sh * coverAspect,
                absYawDeg = absYaw,
                facingSign = originFacing,
                tiltDegrees = tilt,
                frontAlpha = frontAlpha,
                catalogMix = catalogMix,
                leanDegrees = lean,
                frontShade = fShade,
                spineShade = sShade,
                playBadgeAlpha = badgeAlpha,
            )
        }
    }

    // COVERS 模式：封面陈列槽位始终为正对 `0°`、左书脊右倒角，因此 `facingSign == +1f` 时封面中心即 `spineCenterX + 0.5 * coverW`
    val catCoverW = flight.catalogRect.width.coerceAtLeast(1f)
    val catCoverH = flight.catalogRect.height.coerceAtLeast(1f)
    val catSpineThick = (catCoverW * 0.14f).coerceAtLeast(1f)
    val coverAnchorSpineX = flight.catalogRect.center.x - 0.5f * catCoverW
    val coverAnchorSpineY = flight.catalogRect.center.y
    val arc = sin(PI.toFloat() * t)

    // 计算 `u ∈ [0, 1]`（0 = 单排斜轨姿态，1 = 书柜封面正立姿态）下的连续无跳变 `absYaw` 与 `facingSign`
    fun coverUnfoldYawAndSign(u: Float): Pair<Float, Float> {
        if (originFacing >= 0f) {
            return (startAbsYaw * (1f - u)) to 1f
        }
        val preTurn = (90f - startAbsYaw).coerceAtLeast(0f)
        val totalSpan = preTurn + 90f
        val theta = u * totalSpan
        return if (theta <= preTurn) {
            (startAbsYaw + theta) to -1f
        } else {
            (90f - (theta - preTurn)).coerceAtLeast(0f) to 1f
        }
    }

    if (!flight.returningToShelf) {
        val sizeT = catalogSmoothstep(t)
        val cx = origin.spineRect.center.x + (coverAnchorSpineX - origin.spineRect.center.x) * t
        val cy = origin.spineRect.center.y + (coverAnchorSpineY - origin.spineRect.center.y) * t - flight.arcLiftPx * arc
        val sw = (origin.spineThicknessPx + (catSpineThick - origin.spineThicknessPx) * sizeT).coerceAtLeast(1f)
        val sh = (origin.boxHeightPx + (catCoverH - origin.boxHeightPx) * sizeT).coerceAtLeast(1f)
        val coverW = (origin.coverWidthPx + (catCoverW - origin.coverWidthPx) * sizeT).coerceAtLeast(1f)
        val unfoldPhase = if (origin.isHero) {
            (t / 0.72f).coerceIn(0f, 1f)
        } else {
            ((t - 0.16f) / 0.74f).coerceIn(0f, 1f)
        }
        val smoothUnfold = unfoldPhase * unfoldPhase * (3f - 2f * unfoldPhase)
        val (absYaw, facingSign) = coverUnfoldYawAndSign(smoothUnfold)
        val tilt = origin.tiltDegrees * (1f - smoothUnfold)
        val lean = origin.leanDegrees * (1f - smoothUnfold)
        val fShade = origin.frontShade * (1f - smoothUnfold)
        val sShade = origin.spineShade * (1f - smoothUnfold)
        val badgeAlpha = origin.heroFactor * (1f - catalogSmoothstep(t / 0.25f))
        val frontAlpha = ((89.8f - absYaw) / 3.5f).coerceIn(0f, 1f)
        val catalogMix = ((t - 0.30f) / 0.62f).coerceIn(0f, 1f)
        return ShelfCatalogBookPose(
            spineCenterX = cx,
            spineCenterY = cy,
            spineWidth = sw,
            spineHeight = sh,
            coverWidth = coverW,
            absYawDeg = absYaw,
            facingSign = facingSign,
            tiltDegrees = tilt,
            frontAlpha = frontAlpha,
            catalogMix = catalogMix,
            leanDegrees = lean,
            frontShade = fShade,
            spineShade = sShade,
            playBadgeAlpha = badgeAlpha,
        )
    } else {
        val sizeT = catalogSmoothstep(((t - 0.08f) / 0.92f).coerceIn(0f, 1f))
        val cx = coverAnchorSpineX + (origin.spineRect.center.x - coverAnchorSpineX) * t
        val cy = coverAnchorSpineY + (origin.spineRect.center.y - coverAnchorSpineY) * t - flight.arcLiftPx * arc
        val sw = (catSpineThick + (origin.spineThicknessPx - catSpineThick) * sizeT).coerceAtLeast(1f)
        val sh = (catCoverH + (origin.boxHeightPx - catCoverH) * sizeT).coerceAtLeast(1f)
        val coverW = (catCoverW + (origin.coverWidthPx - catCoverW) * sizeT).coerceAtLeast(1f)
        val foldPhase = (t / 0.72f).coerceIn(0f, 1f)
        val smoothFold = foldPhase * foldPhase * (3f - 2f * foldPhase)
        val (absYaw, facingSign) = coverUnfoldYawAndSign(1f - smoothFold)
        val tilt = origin.tiltDegrees * smoothFold
        val lean = origin.leanDegrees * smoothFold
        val fShade = origin.frontShade * smoothFold
        val sShade = origin.spineShade * smoothFold
        val badgeAlpha = origin.heroFactor * catalogSmoothstep((t - 0.72f) / 0.28f)
        val frontAlpha = ((89.8f - absYaw) / 3.5f).coerceIn(0f, 1f)
        val catalogMix = 1f - ((t - 0.10f) / 0.48f).coerceIn(0f, 1f)
        return ShelfCatalogBookPose(
            spineCenterX = cx,
            spineCenterY = cy,
            spineWidth = sw,
            spineHeight = sh,
            coverWidth = coverW,
            absYawDeg = absYaw,
            facingSign = facingSign,
            tiltDegrees = tilt,
            frontAlpha = frontAlpha,
            catalogMix = catalogMix,
            leanDegrees = lean,
            frontShade = fShade,
            spineShade = sShade,
            playBadgeAlpha = badgeAlpha,
        )
    }
}

private fun shelfCatalogRetargetPose(
    progress: Float,
    flight: ShelfCatalogBookFlight,
    start: ShelfCatalogBookPose,
    end: ShelfCatalogBookPose,
): ShelfCatalogBookPose {
    if (progress <= 0f) return start
    if (progress >= 1f) return end
    val spines = flight.catalogMode == ShelfCatalogLayoutMode.SPINES
    val turn = catalogSmoothstep(if (spines) (progress - 0.08f) / 0.88f else progress / 0.72f)
    val size = if (spines) progress else catalogSmoothstep((progress - 0.08f) / 0.92f)
    val startYaw = if (start.facingSign >= 0f) start.absYawDeg else 180f - start.absYawDeg
    val endYaw = if (end.facingSign >= 0f) end.absYawDeg else 180f - end.absYawDeg
    val yaw = lerpCatalog(startYaw, endYaw, turn)
    val absYaw = if (yaw <= 90f) yaw else 180f - yaw
    val catalogMix = (if (spines) (progress - 0.14f) / 0.60f else (progress - 0.10f) / 0.48f).coerceIn(0f, 1f)
    val badgeMix = if (start.playBadgeAlpha > end.playBadgeAlpha) {
        catalogSmoothstep(progress / 0.25f)
    } else {
        catalogSmoothstep((progress - 0.72f) / 0.28f)
    }
    return ShelfCatalogBookPose(
        spineCenterX = lerpCatalog(start.spineCenterX, end.spineCenterX, progress),
        spineCenterY = lerpCatalog(start.spineCenterY, end.spineCenterY, progress) - flight.arcLiftPx * sin(PI.toFloat() * progress),
        spineWidth = lerpCatalog(start.spineWidth, end.spineWidth, size),
        spineHeight = lerpCatalog(start.spineHeight, end.spineHeight, size),
        coverWidth = lerpCatalog(start.coverWidth, end.coverWidth, size),
        absYawDeg = absYaw,
        facingSign = if (yaw == 90f) start.facingSign else if (yaw < 90f) 1f else -1f,
        tiltDegrees = lerpCatalog(start.tiltDegrees, end.tiltDegrees, turn),
        frontAlpha = ((89.8f - absYaw) / 3.5f).coerceIn(0f, 1f),
        catalogMix = lerpCatalog(start.catalogMix, end.catalogMix, catalogMix),
        leanDegrees = lerpCatalog(start.leanDegrees, end.leanDegrees, turn),
        frontShade = lerpCatalog(start.frontShade, end.frontShade, turn),
        spineShade = lerpCatalog(start.spineShade, end.spineShade, turn),
        playBadgeAlpha = lerpCatalog(start.playBadgeAlpha, end.playBadgeAlpha, badgeMix),
    )
}

private fun lerpCatalog(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

private class CatalogSlotPlacement(
    val rowGridIndex: Int,
    val colIndex: Int,
    val layoutMode: ShelfCatalogLayoutMode,
    val coords: LayoutCoordinates,
    val leanDegrees: Float,
)

private class CatalogSlotSnapshot(
    val rect: Rect,
    val layoutMode: ShelfCatalogLayoutMode,
    val rowGridIndex: Int,
    val colIndex: Int,
    val leanDegrees: Float,
)

/**
 * 藏书阁全景状态机与镜头运动控制器。
 */
@Stable
internal class ShelfCatalogMotion(
    private val scope: CoroutineScope,
    val enhanced: Boolean,
    private val stage: ShelfCatalogStage,
    private val initialAnchorUri: String?,
    initialLayoutMode: ShelfCatalogLayoutMode = ShelfCatalogLayoutMode.SPINES,
) {
    val progress = Animatable(if (enhanced) 0f else 1f)
    private val troupeFlight = Animatable(0f)
    private val returnFlight = Animatable(0f)
    val troupePresence = Animatable(1f)
    val pullOffset = Animatable(0f)

    internal val heroDecodeTier: CoverDecodeTier get() = stage.heroDecodeTier

    var layoutMode by mutableStateOf(initialLayoutMode)

    var hasEntered by mutableStateOf(!enhanced)
        private set

    var activeTroupe by mutableStateOf<List<ShelfCatalogBookFlight>>(emptyList())
        private set

    private val initialShelfOrigins: List<ShelfCatalogBookOrigin> = if (enhanced) stage.visibleOrigins() else emptyList()
    private val initialShelfSize = stage.stageSize()

    var hiddenSlotUris by mutableStateOf(
        if (enhanced) {
            (listOfNotNull(initialAnchorUri) + initialShelfOrigins.map { it.uri }).toSet()
        } else {
            emptySet()
        },
    )
        private set

    var anchorCoord by mutableStateOf<Pair<Int, Int>?>(null)
        internal set

    var overlayOrigin by mutableStateOf(Offset.Zero)

    var wallViewport by mutableStateOf(Rect.Zero)

    var closing by mutableStateOf(false)
        private set

    internal var dismissAction: (() -> Unit)? = null
    internal var currentAnchorGame: ScanGame? = null
    internal var currentFocusUri: String? = initialAnchorUri
    internal var allGamesSnapshot: List<ScanGame> = emptyList()
    internal var filteredGamesSnapshot: List<ScanGame> = emptyList()

    private var delivered = false
    private var interrupting = false
    private var transitionJob: Job? = null
    private var exitFocusToken: Int? = null
    private var reversingEntry = false
    /** 书格登记按（书、陈列方式）分开记：切换陈列时新旧两面书柜同时在组合，同一本书在两面里各有自己的位置，互不覆盖。 */
    private data class SlotKey(val uri: String, val mode: ShelfCatalogLayoutMode)

    private val slotPlacements = HashMap<SlotKey, CatalogSlotPlacement>()
    private var slotPlacementVersion by mutableIntStateOf(0)
    private val tierPlacements = HashMap<Pair<ShelfCatalogLayoutMode, Int>, LayoutCoordinates>()
    private val indexLookup = HashMap<String, Int>()

    fun interact(): Boolean {
        if (closing) {
            MotionHaptics.perform(MotionHaptics.Kind.Reject)
            return false
        }
        return true
    }

    fun syncCatalogGames(filtered: List<ScanGame>, all: List<ScanGame>) {
        filteredGamesSnapshot = filtered
        allGamesSnapshot = all
        indexLookup.clear()
        for (i in filtered.indices) {
            indexLookup[filtered[i].uri] = i
        }
    }

    private fun gameByUri(uri: String): ScanGame? =
        filteredGamesSnapshot.firstOrNull { it.uri == uri } ?: allGamesSnapshot.firstOrNull { it.uri == uri }

    private fun spineIndexOf(uri: String): Int =
        indexLookup[uri] ?: allGamesSnapshot.indexOfFirst { it.uri == uri }.coerceAtLeast(0)

    fun placeTier(layoutMode: ShelfCatalogLayoutMode, rowGridIndex: Int, coords: LayoutCoordinates) {
        tierPlacements[layoutMode to rowGridIndex] = coords
    }

    fun placeSlot(
        uri: String,
        rowGridIndex: Int,
        colIndex: Int,
        layoutMode: ShelfCatalogLayoutMode,
        coords: LayoutCoordinates,
        leanDegrees: Float = 0f,
    ) {
        val previous = slotPlacements.put(
            SlotKey(uri, layoutMode),
            CatalogSlotPlacement(rowGridIndex, colIndex, layoutMode, coords, leanDegrees),
        )
        if (previous?.coords !== coords) slotPlacementVersion++
        if (uri == initialAnchorUri && anchorCoord == null) {
            anchorCoord = rowGridIndex to colIndex
        }
    }

    fun forgetSlot(uri: String, layoutMode: ShelfCatalogLayoutMode) {
        if (slotPlacements.remove(SlotKey(uri, layoutMode)) != null) slotPlacementVersion++
    }

    private fun snapshotVisibleSlots(
        allowPartialUri: String? = null,
        mode: ShelfCatalogLayoutMode = layoutMode,
        /** 只露出一部分的书也算（陈列切换的变形要带上它们，不然它们要等变形放完才冒出来）。 */
        includePartial: Boolean = false,
        viewport: Rect = wallViewport,
    ): Map<String, CatalogSlotSnapshot> {
        val map = HashMap<String, CatalogSlotSnapshot>(slotPlacements.size)
        val density = stage.stageDensity
        for ((key, p) in slotPlacements) {
            if (key.mode != mode) continue
            val uri = key.uri
            val coords = p.coords.takeIf { it.isAttached } ?: continue
            if (coords.size.width <= 0 || coords.size.height <= 0) continue
            var tl = coords.localToScreen(Offset.Zero)
            var br = coords.localToScreen(Offset(coords.size.width.toFloat(), coords.size.height.toFloat()))
            // 剥除所在书架层入场缩放（0.95 -> 1）与纵向位移，始终得到 progress = 1f 时的精确静态槽位矩形
            val tierCoords = tierPlacements[p.layoutMode to p.rowGridIndex]?.takeIf { it.isAttached }
            if (tierCoords != null && tierCoords.size.width > 0 && tierCoords.size.height > 0) {
                val tx = shelfCatalogTierTransform(this, p.rowGridIndex, density)
                if (tx.scale > 0.01f && (tx.scale != 1f || tx.translationY != 0f)) {
                    val center = tierCoords.localToScreen(
                        Offset(tierCoords.size.width * 0.5f, tierCoords.size.height * 0.5f),
                    )
                    tl = Offset(
                        x = center.x + (tl.x - center.x) / tx.scale,
                        y = center.y + (tl.y - tx.translationY - center.y) / tx.scale,
                    )
                    br = Offset(
                        x = center.x + (br.x - center.x) / tx.scale,
                        y = center.y + (br.y - tx.translationY - center.y) / tx.scale,
                    )
                }
            }
            val rect = Rect(
                min(tl.x, br.x),
                min(tl.y, br.y),
                maxOf(tl.x, br.x).coerceAtLeast(min(tl.x, br.x) + 1f),
                maxOf(tl.y, br.y).coerceAtLeast(min(tl.y, br.y) + 1f),
            )
            val visible = if (includePartial) {
                // 与视口有交叠就算（露出一小条也带上），变形卡片在视口里裁切
                viewport.width <= 0f || viewport.height <= 0f || rect.overlaps(viewport)
            } else {
                isShelfCatalogSlotInViewport(rect, viewport, allowPartial = (uri == allowPartialUri))
            }
            if (!visible) continue
            map[uri] = CatalogSlotSnapshot(rect, p.layoutMode, p.rowGridIndex, p.colIndex, p.leanDegrees)
        }
        return map
    }

    /** 书脊 ↔ 封面逐本变形的总进度（0 → 1）；每本书按自己的错峰延迟换算成各自的进度，见 [morphPoseOf]。 */
    val morphProgress = Animatable(0f)

    /** 正在变形的书：每本带着旧陈列里的屏幕矩形，新陈列摆好后补上终点矩形。 */
    var activeMorph by mutableStateOf<List<ShelfCatalogMorphFlight>>(emptyList())
        private set

    /** 变形进行中：书柜不接触摸（终点已按当时的位置算好，这时滚动会让卡片与书柜错位）。 */
    var morphing by mutableStateOf(false)
        private set

    /** 切换后新书柜首屏对准的那一本：取旧书柜视口正中最近的一本，让两种陈列里能看见的书尽量重合。 */
    var layoutAnchorUri by mutableStateOf<String?>(null)
        private set

    /**
     * 锚点那一本在旧书柜里的中心距视口顶的纵向位置（px）：新书柜首屏让锚点书落在同一高度（能落的话），
     * 切换时它几乎不用挪，周围的书围着它展开 / 收拢，不会整排上蹿下跳。
     */
    var layoutAnchorY by mutableStateOf<Float?>(null)
        private set

    private val gridMetrics = HashMap<ShelfCatalogLayoutMode, ShelfCatalogGridMetrics>()

    /** 书柜报告自己这一种陈列的网格几何（组合时更新）：变形里给「看不见的书」按规则外推位置用。 */
    fun reportGrid(mode: ShelfCatalogLayoutMode, metrics: ShelfCatalogGridMetrics) {
        gridMetrics[mode] = metrics
    }

    private var morphGeneration = 0

    /** 变形途中又点了另一边：先记下最后选的这一边，等当前这一轮落位后再开新的一轮。 */
    private var pendingLayout by mutableStateOf<ShelfCatalogLayoutMode?>(null)

    /** 分栏上选中的形态：途中点了另一边时立刻跟着手走，不等这一轮放完。 */
    val requestedLayout: ShelfCatalogLayoutMode get() = pendingLayout ?: layoutMode

    private fun finishMorphNow() {
        pendingLayout = null
        morphGeneration++
        activeMorph = emptyList()
        hiddenSlotUris = emptySet()
        morphing = false
    }

    /**
     * 看不见的书在 [mode] 陈列里的位置：按网格规则（第 i 本在第 i / 列数 行、第 i % 列数 列），从最近一行里测得的一本平移推出来。
     * 按引擎分架（有章节横梁，行高不规则）或没有参照时给不出来，返回 null。
     */
    private fun extrapolateRect(
        measured: Map<String, CatalogSlotSnapshot>,
        mode: ShelfCatalogLayoutMode,
        index: Int,
        indexMap: Map<String, Int> = indexLookup,
    ): Rect? {
        val grid = gridMetrics[mode] ?: return null
        if (grid.grouped || grid.columns <= 0 || index < 0) return null
        val row = index / grid.columns
        val col = index % grid.columns
        var ref: CatalogSlotSnapshot? = null
        var refIndex = 0
        var best = Int.MAX_VALUE
        for ((uri, slot) in measured) {
            val i = indexMap[uri] ?: continue
            val distance = kotlin.math.abs(i / grid.columns - row)
            if (distance < best) {
                best = distance
                ref = slot
                refIndex = i
            }
        }
        val anchor = ref ?: return null
        return anchor.rect.translate(
            (col - refIndex % grid.columns) * grid.pitchX,
            (row - refIndex / grid.columns) * grid.pitchY,
        )
    }

    /**
     * 切换「书脊 | 封面」陈列：每一本书不是淡入淡出，而是从它在旧陈列里的位置与大小，一路变形到它在新陈列里的位置与大小
     * （书脊的面渐渐换成封面的面，反之亦然）；离视口正中近的书先动、远的晚一点跟上，一条弹簧管全程。
     *
     * - 两边**只要露出一点**的书都参与（露一半、只露一条的也算）；看不见的书按网格规则外推出位置，从视口外飞进来 / 飞出去，
     *   不再「等变形放完才冒出来」；
     * - 新书柜首屏让旧视口正中那一本落在同一高度（[layoutAnchorY]），它几乎不动，周围的书围着它展开 / 收拢；
     * - 搁板、立柱这些「书柜本体」不参与变形：旧的一面在变形一开始就快速淡去，新的一面在书快落位时才浮现，
     *   中间只有书在空中变形，不会书还没到、搁板先摆好了。
     *
     * 切换途中再点另一边：当前这一轮照常飞完落位，再接着开最后选的那一边（卡片不会从半空消失）。藏书阁还没入场完时先收敛入场态直接切换到目标形态；正在退场或「普通」档时直接换。
     */
    fun switchLayout(target: ShelfCatalogLayoutMode): Boolean {
        if (target == requestedLayout) return false
        if (morphing) {
            pendingLayout = target.takeIf { it != layoutMode }
            return true
        }
        beginSwitch(target)
        return true
    }

    private fun beginSwitch(target: ShelfCatalogLayoutMode) {
        if (!enhanced || !hasEntered || closing || !ValueAnimator.areAnimatorsEnabled()) {
            if (!closing && !hasEntered) {
                transitionJob?.cancel()
                transitionJob = null
                activeTroupe = emptyList()
                hiddenSlotUris = emptySet()
                stage.lentUris = emptySet()
                stage.lentAlpha = 1f
                hasEntered = true
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    progress.snapTo(1f)
                    stage.recedeProgress = 1f
                    troupePresence.snapTo(0f)
                }
            }
            layoutMode = target
            return
        }
        val fromMode = layoutMode
        val from = snapshotVisibleSlots(mode = fromMode, includePartial = true)
        if (from.isEmpty()) {
            layoutMode = target
            return
        }
        val viewport = wallViewport
        val cx = (viewport.left + viewport.right) / 2f
        val cy = (viewport.top + viewport.bottom) / 2f
        fun distanceToCenter(rect: Rect): Double =
            kotlin.math.hypot((rect.center.x - cx).toDouble(), (rect.center.y - cy).toDouble())

        // 锚点：旧视口里离正中最近的一本（优先完整看得见的）
        val anchor = from.entries.minByOrNull { (_, slot) ->
            distanceToCenter(slot.rect) + if (slot.rect.top >= viewport.top && slot.rect.bottom <= viewport.bottom) 0.0 else 1.0e6
        } ?: return
        layoutAnchorUri = anchor.key
        layoutAnchorY = anchor.value.rect.center.y - viewport.top

        val games = filteredGamesSnapshot
        val indexByUri = HashMap<String, Int>(games.size).apply {
            games.forEachIndexed { idx, g -> put(g.uri, idx) }
        }
        // 旧陈列里每本书的起点：测得的位置，没组合出来的按网格外推；只留与视口有交叠的
        val sources = HashMap<String, Rect>()
        for ((i, game) in games.withIndex()) {
            val rect = from[game.uri]?.rect ?: extrapolateRect(from, fromMode, i, indexByUri) ?: continue
            if (viewport.width <= 0f || rect.overlaps(viewport)) sources[game.uri] = rect
        }
        if (sources.isEmpty()) {
            layoutMode = target
            return
        }
        val byUri = games.associateBy { it.uri }
        val flights = ArrayList<ShelfCatalogMorphFlight>(sources.size)
        sources.entries.sortedBy { (_, rect) -> distanceToCenter(rect) }.forEachIndexed { rank, (uri, rect) ->
            val game = byUri[uri] ?: return@forEachIndexed
            flights += ShelfCatalogMorphFlight(
                game = game,
                spineIndex = indexByUri[uri] ?: spineIndexOf(uri),
                fromRect = rect,
                fromMode = fromMode,
                toMode = target,
                isCurrent = uri == currentFocusUri,
                delay = (rank * MORPH_STAGGER).coerceAtMost(MORPH_MAX_DELAY),
            )
        }
        val generation = ++morphGeneration
        // 同一帧里：新陈列上场、旧书从原位换成变形卡片（卡片此刻与旧书完全重合），真正的书槽藏起来
        activeMorph = flights
        hiddenSlotUris = flights.mapTo(HashSet()) { it.game.uri }
        morphing = true
        layoutMode = target
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var landed = false
            try {
                morphProgress.snapTo(0f)
                // 等新陈列把可见的书摆好（最多约 12 帧），再多等两帧让位置稳住
                var waited = 0
                while (waited < MORPH_WAIT_FRAMES &&
                    slotPlacements.none { (key, p) -> key.mode == target && p.coords.isAttached }
                ) {
                    withFrameNanos { }
                    waited++
                }
                repeat(2) { withFrameNanos { } }
                if (generation != morphGeneration) return@launch
                val targetViewport = wallViewport
                val to = snapshotVisibleSlots(mode = target, includePartial = true, viewport = targetViewport)
                for (flight in flights) {
                    val index = indexByUri[flight.game.uri] ?: -1
                    val dst = to[flight.game.uri]?.rect ?: extrapolateRect(to, target, index, indexByUri)
                    if (dst != null) flight.toRect = dst else flight.vanish = true
                }
                // 旧陈列里看不见、新陈列里看得见的书：从旧陈列外推的位置飞进来；外推不出来的就在自己的落点上浮现
                val arrivals = ArrayList<ShelfCatalogMorphFlight>()
                val arrivalRects = HashMap<String, Rect>()
                for ((i, game) in games.withIndex()) {
                    if (game.uri in sources) continue
                    val dst = to[game.uri]?.rect ?: extrapolateRect(to, target, i, indexByUri) ?: continue
                    if (targetViewport.width > 0f && !dst.overlaps(targetViewport)) continue
                    arrivalRects[game.uri] = dst
                }
                arrivalRects.entries.sortedBy { (_, rect) -> distanceToCenter(rect) }.forEachIndexed { rank, (uri, dst) ->
                    val game = byUri[uri] ?: return@forEachIndexed
                    val src = extrapolateRect(from, fromMode, indexByUri[uri] ?: -1, indexByUri)
                    arrivals += ShelfCatalogMorphFlight(
                        game = game,
                        spineIndex = indexByUri[uri] ?: spineIndexOf(uri),
                        fromRect = src ?: dst,
                        fromMode = fromMode,
                        toMode = target,
                        isCurrent = uri == currentFocusUri,
                        delay = (rank * MORPH_STAGGER).coerceAtMost(MORPH_MAX_DELAY),
                        appear = src == null,
                    ).also { it.toRect = dst }
                }
                if (arrivals.isNotEmpty()) {
                    hiddenSlotUris = hiddenSlotUris + arrivals.map { it.game.uri }
                    activeMorph = flights + arrivals
                }
                // 新加的卡片先组合、摆好再起步：组合落在起步之后，那一帧会拖得很长，弹簧按真实时间走，头一段就被吃掉
                // （表现为卡片「一闪就到位」，看不到变形的过程）
                repeat(MORPH_WARMUP_FRAMES) { withFrameNanos { } }
                if (generation != morphGeneration) return@launch
                morphProgress.animateTo(1f, MORPH_SPRING)
                landed = true
            } finally {
                withContext(NonCancellable) {
                    if (generation == morphGeneration) {
                        activeMorph = emptyList()
                        hiddenSlotUris = emptySet()
                        morphing = false
                        layoutAnchorY = null
                        val next = pendingLayout
                        pendingLayout = null
                        if (landed && next != null && next != layoutMode && !closing) beginSwitch(next)
                    }
                }
            }
        }
    }

    /** 某一本变形卡片此刻的位置、大小与自己的进度 t（相对覆盖层左上角；终点还没摆好时停在起点）。 */
    internal fun morphPoseOf(flight: ShelfCatalogMorphFlight): ShelfCatalogMorphPose {
        val progress = morphProgress.value
        val origin = overlayOrigin
        val hasTarget = flight.toRect != null
        val cached = flight.cachedPose
        if (cached != null &&
            flight.cachedProgress == progress &&
            flight.cachedOriginX == origin.x &&
            flight.cachedOriginY == origin.y &&
            flight.cachedHasTarget == hasTarget
        ) {
            return cached
        }
        val t = ((progress - flight.delay) / (1f - flight.delay)).coerceAtLeast(0f)
        val from = flight.fromRect
        val to = flight.toRect ?: from
        val k = if (hasTarget) t else 0f
        val arc = -kotlin.math.min(from.height, to.height) * MORPH_ARC * kotlin.math.sin(Math.PI * t.coerceIn(0f, 1f)).toFloat()
        return ShelfCatalogMorphPose(
            left = from.left + (to.left - from.left) * k - origin.x,
            top = from.top + (to.top - from.top) * k + arc - origin.y,
            width = from.width + (to.width - from.width) * k,
            height = from.height + (to.height - from.height) * k,
            t = t,
        ).also {
            flight.cachedProgress = progress
            flight.cachedOriginX = origin.x
            flight.cachedOriginY = origin.y
            flight.cachedHasTarget = hasTarget
            flight.cachedPose = it
        }
    }

    private fun enteringTroupe(): List<ShelfCatalogBookFlight> =
        if (!hasEntered && hiddenSlotUris.isNotEmpty()) activeTroupe else emptyList()

    /**
     * 退场（合上 / 点选）途中被手指打断：书架上的书立刻快速落到位，覆盖层随即收走，不再等整段动画放完——
     * 手指碰到的就是下面的书架（退场期间覆盖层不挡触摸）。收尾用一条很快的弹簧接着当前位置走完，书不会闪现或跳位。
     */
    internal fun interruptExit() {
        if (!closing || delivered || interrupting || !enhanced) return
        interrupting = true
        exitFocusToken?.let(stage::clearPendingFocus)
        val running = transitionJob
        transitionJob = scope.launch {
            try {
                running?.cancelAndJoin()
                coroutineScope {
                    launch {
                        progress.animateTo(0f, CATALOG_INTERRUPT_SPRING) { stage.recedeProgress = value }
                    }
                    if (activeTroupe.isNotEmpty()) {
                        val entryInterrupted = activeTroupe.any { !it.returningToShelf }
                        launch {
                            if (entryInterrupted && !reversingEntry) {
                                troupePresence.animateTo(0f, tween(CATALOG_INTERRUPT_HANDOFF_MS))
                            } else if (reversingEntry) {
                                troupeFlight.animateTo(0f, CATALOG_INTERRUPT_SPRING)
                            } else {
                                returnFlight.animateTo(1f, CATALOG_INTERRUPT_SPRING)
                            }
                        }
                    }
                }
                if (activeTroupe.isNotEmpty()) {
                    Animatable(stage.lentAlpha).animateTo(1f, tween(CATALOG_INTERRUPT_HANDOFF_MS, easing = FastOutSlowInEasing)) {
                        stage.lentAlpha = value
                    }
                    stage.lentUris = emptySet()
                    hiddenSlotUris = emptySet()
                    troupePresence.snapTo(0f)
                    activeTroupe = emptyList()
                }
            } finally {
                withContext(NonCancellable) { deliver() }
            }
        }
    }

    internal suspend fun enter() {
        stage.exitInterrupt = { interruptExit() }
        if (!enhanced || !ValueAnimator.areAnimatorsEnabled()) {
            hiddenSlotUris = emptySet()
            hasEntered = true
            progress.snapTo(1f)
            stage.recedeProgress = 1f
            return
        }
        val origins = initialShelfOrigins.ifEmpty { stage.visibleOrigins() }
        val heroUri = initialAnchorUri ?: origins.firstOrNull { it.isHero }?.uri ?: origins.firstOrNull()?.uri
        val heroOrigin = heroUri?.let { u -> origins.firstOrNull { it.uri == u } }
        withTimeoutOrNull(ENTER_WAIT_SLOT_MS) {
            snapshotFlow { isEnterSlotReady(heroUri) }.first { it }
        }
        if (closing) return

        val visibleSlots = snapshotVisibleSlots()
        val heroSlot = heroUri?.let { visibleSlots[it] }
        val anchorPageIdx = heroOrigin?.pageIndex ?: 0
        val catalogFocusUri = currentFocusUri ?: initialAnchorUri

        val troupe = ArrayList<ShelfCatalogBookFlight>(origins.size)
        for (o in origins) {
            val slot = visibleSlots[o.uri] ?: continue
            val game = gameByUri(o.uri) ?: continue
            val distPages = abs(o.pageIndex - anchorPageIdx)
            val delay = if (o.uri == heroUri) 0f else (distPages * CATALOG_FLIGHT_STAGGER).coerceAtMost(CATALOG_FLIGHT_MAX_DELAY)
            val sameRow = heroSlot != null && slot.rowGridIndex == heroSlot.rowGridIndex
            val lift = when {
                o.uri == heroUri -> (o.boxHeightPx * 0.06f).coerceAtLeast(16f)
                sameRow -> (o.boxHeightPx * 0.025f).coerceAtLeast(6f)
                else -> (o.boxHeightPx * 0.05f).coerceAtLeast(12f)
            }
            val zPrio = 100f - distPages.toFloat() + if (o.uri == heroUri) 50f else 0f
            troupe += ShelfCatalogBookFlight(
                game = game,
                spineIndex = spineIndexOf(o.uri),
                shelfOrigin = o,
                catalogRect = slot.rect,
                catalogMode = slot.layoutMode,
                delayFraction = delay,
                arcLiftPx = lift,
                returningToShelf = false,
                isCurrentInCatalog = o.uri == catalogFocusUri,
                zPriority = zPrio,
                catalogLeanDegrees = slot.leanDegrees,
            )
        }
        // 按优先级升序排序：最远的后绘、靠近者居中、主角压轴顶层绘制
        troupe.sortBy { it.zPriority }

        val troupeUris = troupe.map { it.game.uri }.toSet()
        transitionJob?.cancel()
        transitionJob = scope.launch {
            if (troupe.isNotEmpty()) {
                hiddenSlotUris = troupeUris
                activeTroupe = troupe
                troupePresence.snapTo(1f)
                troupeFlight.snapTo(0f)
                // 等待 2 帧完成组合提交与首帧 t=0 光栅化上传并盖住单排书盒，再隐藏原槽位并开跑弹簧，彻底杜绝首帧卡顿吃掉动画
                withFrameNanos { }
                if (closing) return@launch
                stage.lentAlpha = 0f
                stage.lentUris = troupeUris
                withFrameNanos { }
                if (closing) return@launch
            } else {
                hiddenSlotUris = emptySet()
            }
            coroutineScope {
                launch {
                    progress.animateTo(1f, CATALOG_ENTER_SPRING) {
                        stage.recedeProgress = value
                    }
                }
                if (activeTroupe.isNotEmpty()) {
                    launch {
                        troupeFlight.animateTo(1f, CATALOG_FLIGHT_ENTER_SPRING)
                    }
                }
            }
            if (!closing && activeTroupe.isNotEmpty()) {
                hiddenSlotUris = emptySet()
                troupePresence.animateTo(0f, tween(CATALOG_HANDOFF_MS, easing = FastOutSlowInEasing))
                if (!closing) activeTroupe = emptyList()
            }
            if (!closing) {
                hasEntered = true
            }
        }
    }

    private fun isEnterSlotReady(heroUri: String?): Boolean {
        if (wallViewport.height <= 0f) return false
        slotPlacementVersion
        val mode = layoutMode
        return if (heroUri != null) {
            slotPlacements[SlotKey(heroUri, mode)]?.coords?.isAttached == true
        } else {
            slotPlacements.any { (key, placement) -> key.mode == mode && placement.coords.isAttached }
        }
    }

    fun pickAndClose(game: ScanGame, onFocusGame: (ScanGame) -> Int) {
        if (closing) return
        if (morphing) finishMorphNow()
        closing = true
        if (game.uri !in indexLookup) {
            dismissImmediately()
            return
        }
        if (!enhanced || !ValueAnimator.areAnimatorsEnabled()) {
            onFocusGame(game)
            deliver()
            return
        }
        val prePickFocusUri = currentFocusUri
        val takeoffs = enteringTroupe().associateBy { it.game.uri }
        val takeoffProgress = troupeFlight.value
        val visibleSlots = snapshotVisibleSlots(allowPartialUri = game.uri)
        val pickedSlot = visibleSlots[game.uri]
        if (pickedSlot != null) anchorCoord = pickedSlot.rowGridIndex to pickedSlot.colIndex
        val focusToken = onFocusGame(game)
        exitFocusToken = focusToken.takeIf { it > 0 }

        transitionJob?.cancel()
        transitionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                stage.lentAlpha = 0f
                stage.lentUris = stage.lentUris + takeoffs.keys + game.uri
                val aligned = focusToken > 0 && (
                    withTimeoutOrNull(EXIT_WAIT_ALIGN_MS) {
                        snapshotFlow { stage.pendingFocusUri != game.uri || stage.focusRequestToken != focusToken }
                            .first { it }
                        stage.focusRequestToken == focusToken && stage.settledFocusToken == focusToken
                    } == true
                )
                stage.clearPendingFocus(focusToken)
                val heroTargetOrigin = if (aligned) {
                    withTimeoutOrNull(EXIT_WAIT_ORIGIN_MS) {
                        snapshotFlow { stage.origin(game.uri)?.takeIf { it.uri == game.uri && it.isHero } }
                            .first { it != null }
                    }
                } else {
                    null
                }
                val troupe = ArrayList<ShelfCatalogBookFlight>()
                val pickedTakeoff = takeoffs[game.uri]
                if ((pickedSlot != null || pickedTakeoff != null) && heroTargetOrigin != null) {
                    val heroPageIdx = heroTargetOrigin.pageIndex
                    val targets = stage.visibleOrigins().filter { it.uri != game.uri } + heroTargetOrigin
                    for (targetOrigin in targets) {
                        val uri = targetOrigin.uri
                        val picked = uri == game.uri
                        val takeoff = takeoffs[uri]
                        val srcSlot = visibleSlots[uri]
                        val catalogRect = takeoff?.catalogRect ?: srcSlot?.rect ?: continue
                        val catalogMode = takeoff?.catalogMode ?: srcSlot?.layoutMode ?: continue
                        val catalogLean = takeoff?.catalogLeanDegrees ?: srcSlot?.leanDegrees ?: 0f
                        val flightGame = if (picked) game else gameByUri(uri) ?: takeoff?.game ?: continue
                        val distPages = abs(targetOrigin.pageIndex - heroPageIdx)
                        val crossTier = srcSlot != null && pickedSlot != null && srcSlot.rowGridIndex != pickedSlot.rowGridIndex
                        val delay = if (picked || crossTier) 0f else (distPages * CATALOG_PICK_STAGGER).coerceAtMost(CATALOG_FLIGHT_MAX_DELAY)
                        val arcLift = when {
                            picked -> (targetOrigin.boxHeightPx * 0.08f).coerceAtLeast(18f)
                            crossTier -> (targetOrigin.boxHeightPx * 0.11f).coerceAtLeast(22f)
                            else -> (targetOrigin.boxHeightPx * 0.03f).coerceAtLeast(6f)
                        }
                        troupe += ShelfCatalogBookFlight(
                            game = flightGame,
                            spineIndex = takeoff?.spineIndex ?: spineIndexOf(uri),
                            shelfOrigin = targetOrigin,
                            catalogRect = catalogRect,
                            catalogMode = catalogMode,
                            delayFraction = delay,
                            arcLiftPx = arcLift,
                            returningToShelf = true,
                            isCurrentInCatalog = takeoff?.isCurrentInCatalog ?: (uri == prePickFocusUri),
                            zPriority = if (picked) 200f else 100f - distPages,
                            catalogLeanDegrees = catalogLean,
                            takeoffPose = takeoff?.let { shelfCatalogBookPose(takeoffProgress, it) },
                            useHeroCover = targetOrigin.isHero || takeoff?.useHeroCover == true,
                        )
                    }
                }
                val landingUris = troupe.mapTo(HashSet()) { it.game.uri }
                for ((uri, takeoff) in takeoffs) {
                    if (uri !in landingUris) troupe += takeoff.fadeFrom(takeoffProgress)
                }
                troupe.sortBy { it.zPriority }

                if (troupe.isNotEmpty()) {
                    troupePresence.snapTo(1f)
                    activeTroupe = troupe
                    if (takeoffs.isNotEmpty()) hiddenSlotUris = landingUris + takeoffs.keys
                    // 等待 2 帧完成组合与首帧光栅化纹理上传，再隐去书架静态槽并同步启动弹簧
                    withFrameNanos { }
                    hiddenSlotUris = landingUris + takeoffs.keys
                    stage.lentAlpha = 0f
                    stage.lentUris = landingUris
                    withFrameNanos { }
                } else {
                    activeTroupe = emptyList()
                    hiddenSlotUris = emptySet()
                    stage.lentUris = emptySet()
                    stage.lentAlpha = 1f
                }

                coroutineScope {
                    launch {
                        progress.animateTo(0f, CATALOG_PICK_SPRING) {
                            stage.recedeProgress = value
                        }
                    }
                    if (activeTroupe.isNotEmpty()) {
                        launch {
                            // 书落位的最后一小段里，书架上真正的书（连同投影、倒影、书脊上的烫线与菱饰）就开始浮现，
                            // 与飞行的书交叠着交接，不是飞完了才一下子冒出来
                            returnFlight.animateTo(1f, CATALOG_PICK_SPRING) { stage.lentAlpha = shelfCatalogHandoffAlpha(value) }
                        }
                    }
                }
                if (activeTroupe.isNotEmpty()) {
                    Animatable(stage.lentAlpha).animateTo(1f, tween(CATALOG_HANDOFF_MS, easing = FastOutSlowInEasing)) {
                        stage.lentAlpha = value
                    }
                    stage.lentUris = emptySet()
                    troupePresence.animateTo(0f, tween(CATALOG_EXIT_FADE_MS, easing = LinearEasing))
                    activeTroupe = emptyList()
                }
            } finally {
                if (!interrupting) withContext(NonCancellable) { deliver() }
            }
        }
    }

    fun close(anchorGame: ScanGame?) {
        if (closing) return
        if (morphing) finishMorphNow()
        closing = true
        if (!enhanced || !ValueAnimator.areAnimatorsEnabled()) {
            deliver()
            return
        }
        val incoming = enteringTroupe()
        reversingEntry = incoming.isNotEmpty() && initialShelfSize != null && initialShelfSize == stage.stageSize()
        val reverseFrom = troupeFlight.value.coerceIn(0f, 1f)
        val visibleSlots = snapshotVisibleSlots()
        val anchorUri = anchorGame?.uri
        anchorUri?.let { visibleSlots[it] }?.let { anchorCoord = it.rowGridIndex to it.colIndex }
        val visibleTargets = stage.visibleOrigins()
        val troupe = ArrayList<ShelfCatalogBookFlight>(visibleTargets.size)
        val anchorPageIdx = visibleTargets.firstOrNull { it.uri == anchorUri }?.pageIndex
            ?: visibleTargets.firstOrNull { it.isHero }?.pageIndex ?: 0

        val anchorSlot = anchorUri?.let { visibleSlots[it] }
        for (targetOrigin in if (incoming.isNotEmpty()) emptyList() else visibleTargets) {
            val srcSlot = visibleSlots[targetOrigin.uri] ?: continue
            val g = gameByUri(targetOrigin.uri) ?: continue
            val dist = abs(targetOrigin.pageIndex - anchorPageIdx)
            val crossTier = anchorSlot != null && srcSlot.rowGridIndex != anchorSlot.rowGridIndex
            val delay = when {
                targetOrigin.uri == anchorUri || crossTier -> 0f
                else -> (dist * CATALOG_PICK_STAGGER).coerceAtMost(CATALOG_FLIGHT_MAX_DELAY)
            }
            val arcLift = if (crossTier) {
                (targetOrigin.boxHeightPx * 0.11f).coerceAtLeast(22f)
            } else {
                (targetOrigin.boxHeightPx * 0.05f).coerceAtLeast(10f)
            }
            val zPrio = 100f - dist.toFloat() + if (targetOrigin.uri == anchorUri) 50f else 0f
            troupe += ShelfCatalogBookFlight(
                game = g,
                spineIndex = spineIndexOf(targetOrigin.uri),
                shelfOrigin = targetOrigin,
                catalogRect = srcSlot.rect,
                catalogMode = srcSlot.layoutMode,
                delayFraction = delay,
                arcLiftPx = arcLift,
                returningToShelf = true,
                isCurrentInCatalog = targetOrigin.uri == anchorUri,
                zPriority = zPrio,
                catalogLeanDegrees = srcSlot.leanDegrees,
            )
        }
        if (reversingEntry) {
            troupe.addAll(incoming)
        } else if (incoming.isNotEmpty()) {
            val targets = visibleTargets.associateBy { it.uri }
            for (flight in incoming) {
                val target = targets[flight.game.uri]
                troupe += if (target == null) flight.fadeFrom(reverseFrom) else ShelfCatalogBookFlight(
                    game = flight.game,
                    spineIndex = flight.spineIndex,
                    shelfOrigin = target,
                    catalogRect = flight.catalogRect,
                    catalogMode = flight.catalogMode,
                    delayFraction = 0f,
                    arcLiftPx = flight.arcLiftPx,
                    returningToShelf = true,
                    isCurrentInCatalog = flight.isCurrentInCatalog,
                    zPriority = flight.zPriority,
                    catalogLeanDegrees = flight.catalogLeanDegrees,
                    takeoffPose = shelfCatalogBookPose(reverseFrom, flight),
                    useHeroCover = target.isHero || flight.useHeroCover,
                )
            }
        }
        troupe.sortBy { it.zPriority }

        transitionJob?.cancel()
        transitionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (troupe.isNotEmpty()) {
                    val troupeUris = troupe.map { it.game.uri }.toSet()
                    activeTroupe = troupe
                    troupePresence.snapTo(1f)
                    // 等待 2 帧完成组合与首帧光栅化纹理上传，再隐去书架静态槽并同步启动退场弹簧
                    withFrameNanos { }
                    stage.lentAlpha = 0f
                    stage.lentUris = troupe.filterNot { it.fadeInPlace }.mapTo(HashSet()) { it.game.uri }
                    hiddenSlotUris = troupeUris
                    withFrameNanos { }
                } else {
                    activeTroupe = emptyList()
                    hiddenSlotUris = emptySet()
                    stage.lentUris = emptySet()
                    stage.lentAlpha = 1f
                }
                coroutineScope {
                    launch {
                        progress.animateTo(0f, CATALOG_EXIT_SPRING) {
                            stage.recedeProgress = value
                        }
                    }
                    if (activeTroupe.isNotEmpty()) {
                        launch {
                            if (reversingEntry) {
                                troupeFlight.animateTo(0f, CATALOG_EXIT_SPRING) {
                                    val handoff = if (reverseFrom > 0f) 1f - value / reverseFrom else 1f
                                    stage.lentAlpha = shelfCatalogHandoffAlpha(handoff)
                                }
                            } else {
                                returnFlight.animateTo(1f, CATALOG_EXIT_SPRING) { stage.lentAlpha = shelfCatalogHandoffAlpha(value) }
                            }
                        }
                    }
                }
                if (activeTroupe.isNotEmpty()) {
                    Animatable(stage.lentAlpha).animateTo(1f, tween(CATALOG_HANDOFF_MS, easing = FastOutSlowInEasing)) {
                        stage.lentAlpha = value
                    }
                    stage.lentUris = emptySet()
                    troupePresence.animateTo(0f, tween(CATALOG_EXIT_FADE_MS, easing = LinearEasing))
                    activeTroupe = emptyList()
                }
            } finally {
                if (!interrupting) withContext(NonCancellable) { deliver() }
            }
        }
    }

    fun dismissImmediately() {
        closing = true
        transitionJob?.cancel()
        deliver()
    }

    internal fun releaseIfLeaving(event: Lifecycle.Event) {
        if ((event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) && closing) {
            deliver()
        }
    }

    private fun deliver() {
        if (delivered) return
        delivered = true
        exitFocusToken?.let(stage::clearPendingFocus)
        stage.exitInterrupt = null
        stage.release()
        dismissAction?.invoke()
    }

    internal fun currentBookPose(flight: ShelfCatalogBookFlight): ShelfCatalogBookPose {
        val raw = if (flight.returningToShelf) returnFlight.value else troupeFlight.value
        val cached = flight.cachedPose
        if (cached != null && flight.cachedRawProgress == raw) return cached
        return shelfCatalogBookPose(rawProgress = raw, flight = flight).also {
            flight.cachedRawProgress = raw
            flight.cachedPose = it
        }
    }

    internal fun fadeInPlaceAlpha(): Float = 1f - catalogSmoothstep(returnFlight.value)
}

@Composable
internal fun rememberShelfCatalogMotion(
    stage: ShelfCatalogStage,
    anchorGame: ScanGame?,
    initialLayoutMode: ShelfCatalogLayoutMode,
    onDismiss: () -> Unit,
): ShelfCatalogMotion {
    val enhanced = MotionMode.enhanced
    val scope = rememberCoroutineScope()
    val motion = remember(stage, enhanced) {
        ShelfCatalogMotion(scope, enhanced, stage, anchorGame?.uri, initialLayoutMode).apply {
            currentAnchorGame = anchorGame
        }
    }
    motion.currentAnchorGame = anchorGame
    motion.dismissAction = onDismiss
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, motion) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                motion.releaseIfLeaving(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("ShelfCatalogMotion", "Catalog dismiss callback failed on $event", failure)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            stage.release()
        }
    }
    return motion
}

// ---------------------------------------------------------------------------------------------
// 具名图层与坐标登记修饰符
// ---------------------------------------------------------------------------------------------

internal fun Modifier.shelfCatalogStageRecede(stage: ShelfCatalogStage?, density: Float): Modifier =
    if (stage == null) this else this
        .onPlaced { stage.placeStage(it, density) }
        .graphicsLayer {
            val r = stage.recedeProgress.coerceIn(0f, 1f)
            if (r <= 0.001f) return@graphicsLayer
            val scale = stageRecedeScale(r)
            transformOrigin = TransformOrigin(0.5f, STAGE_RECEDE_PIVOT_Y)
            scaleX = scale
            scaleY = scale
            translationY = STAGE_RECEDE_SHIFT_DP * density * r
            alpha = (1f - 0.94f * r).coerceIn(0f, 1f)
        }

internal fun Modifier.shelfCatalogChromeRecede(stage: ShelfCatalogStage?): Modifier =
    if (stage == null) this else graphicsLayer {
        val r = stage.recedeProgress.coerceIn(0f, 1f)
        if (r <= 0.001f) return@graphicsLayer
        val scale = 1f - 0.045f * r
        transformOrigin = TransformOrigin(0.5f, 0.5f)
        scaleX = scale
        scaleY = scale
        translationY = CHROME_RECEDE_SHIFT_DP * density * r
        alpha = (1f - r * 1.15f).coerceIn(0f, 1f)
    }

internal fun Modifier.shelfCatalogSpine(stage: ShelfCatalogStage?, uri: String): Modifier =
    if (stage == null) this else onPlaced { coords -> stage.placeSpine(uri, coords) }

internal fun Modifier.shelfCatalogFront(
    stage: ShelfCatalogStage?,
    uri: String,
    pageIndex: Int,
    yawRadiansProvider: () -> Float,
    tiltDegreesProvider: () -> Float,
    scaleProvider: () -> Float = { -1f },
    leanDegreesProvider: () -> Float = { 0f },
    frontShadeProvider: () -> Float = { 0f },
    spineShadeProvider: () -> Float = { 0f },
    heroFactorProvider: () -> Float = { 0f },
    tintProvider: () -> Color,
): Modifier = if (stage == null) this else onPlaced { coords ->
    stage.placeFront(
        uri = uri,
        pageIndex = pageIndex,
        coordinates = coords,
        yawRadiansProvider = yawRadiansProvider,
        tiltDegreesProvider = tiltDegreesProvider,
        scaleProvider = scaleProvider,
        leanDegreesProvider = leanDegreesProvider,
        frontShadeProvider = frontShadeProvider,
        spineShadeProvider = spineShadeProvider,
        heroFactorProvider = heroFactorProvider,
        tintProvider = tintProvider,
    )
}

internal fun Modifier.shelfCatalogBackdropAlpha(motion: ShelfCatalogMotion): Modifier = graphicsLayer {
    alpha = if (motion.enhanced) motion.progress.value.coerceIn(0f, 1f) else 1f
}

internal fun Modifier.shelfCatalogHeaderEntrance(motion: ShelfCatalogMotion): Modifier = graphicsLayer {
    if (!motion.enhanced) return@graphicsLayer
    val p = motion.progress.value.coerceIn(0f, 1f)
    val pull = motion.pullOffset.value
    if (p <= 0.0001f) {
        alpha = 0f
        return@graphicsLayer
    }
    val hProg = ((p - 0.08f) / 0.92f).coerceIn(0f, 1f)
    alpha = hProg
    translationY = -(1f - hProg) * HEADER_ENTRANCE_SHIFT_DP * density + pull * 0.55f
    val s = 0.96f + 0.04f * hProg
    scaleX = s
    scaleY = s
    transformOrigin = TransformOrigin(0.5f, 0f)
}

internal fun Modifier.shelfCatalogWallPull(motion: ShelfCatalogMotion): Modifier = graphicsLayer {
    val pull = motion.pullOffset.value
    if (pull > 0.5f) {
        translationY = pull
        val s = 1f - (pull / 2400f).coerceIn(0f, 0.04f)
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}

/** 计算书柜非编队背景墙的显隐进度：退场前半程利落淡出，让飞行书盒后半程纯净流畅。 */
private fun catalogRevealDelayed(motion: ShelfCatalogMotion, rawProgress: Float): Float =
    if (motion.closing) {
        ((rawProgress - 0.42f) / 0.58f).coerceIn(0f, 1f)
    } else {
        ((rawProgress - EARLY_CASCADE_GATE) / (1f - EARLY_CASCADE_GATE)).coerceIn(0f, 1f)
    }

private data class CatalogTierTransform(
    val alpha: Float,
    val scale: Float,
    val translationY: Float,
)

private val TIER_VISIBLE = CatalogTierTransform(1f, 1f, 0f)
private val TIER_HIDDEN = CatalogTierTransform(0f, 1f, 0f)

private fun shelfCatalogTierTransform(
    motion: ShelfCatalogMotion,
    rowGridIndex: Int,
    density: Float,
): CatalogTierTransform {
    if (!motion.enhanced) return TIER_VISIBLE
    val p = motion.progress.value.coerceIn(0f, 1f)
    if (p <= 0.0001f) return TIER_HIDDEN
    if (!motion.closing && p >= 0.999f) return TIER_VISIBLE
    val delayed = catalogRevealDelayed(motion, p)
    if (motion.closing && delayed <= 0.001f) return TIER_HIDDEN
    val anchorRow = motion.anchorCoord?.first ?: rowGridIndex
    val dist = abs(rowGridIndex - anchorRow).toFloat() * 0.75f
    val t = shelfCatalogCascadeProgress(delayed, dist)
    return CatalogTierTransform(
        alpha = t,
        // 各层只在原位淡入、微微放大到位，不再朝锚点那一层挤拢再散开：挤拢时相邻两层的封面互相压在一起，像显示错乱
        scale = 0.97f + 0.03f * t,
        translationY = 0f,
    )
}

internal fun Modifier.shelfCatalogLedgeEntrance(
    motion: ShelfCatalogMotion,
    rowGridIndex: Int,
    layoutMode: ShelfCatalogLayoutMode = motion.layoutMode,
): Modifier = this
    .onPlaced { motion.placeTier(layoutMode, rowGridIndex, it) }
    .graphicsLayer {
        val tx = shelfCatalogTierTransform(motion, rowGridIndex, density)
        alpha = tx.alpha
        if (tx.scale != 1f) {
            scaleX = tx.scale
            scaleY = tx.scale
        }
        if (tx.translationY != 0f) {
            translationY = tx.translationY
        }
    }

internal fun Modifier.shelfCatalogSectionEntrance(
    motion: ShelfCatalogMotion,
    tierHint: Int,
): Modifier = graphicsLayer {
    if (!motion.enhanced) return@graphicsLayer
    val p = motion.progress.value.coerceIn(0f, 1f)
    if (p <= 0.0001f) {
        alpha = 0f
        return@graphicsLayer
    }
    if (!motion.closing && p >= 0.999f) return@graphicsLayer
    val delayed = catalogRevealDelayed(motion, p)
    if (motion.closing && delayed <= 0.001f) {
        alpha = 0f
        return@graphicsLayer
    }
    val t = shelfCatalogCascadeProgress(delayed, tierHint.toFloat() * 0.55f)
    alpha = t
    translationY = (1f - t) * 10.dp.toPx()
}

internal fun Modifier.shelfCatalogWallViewport(motion: ShelfCatalogMotion): Modifier = onPlaced { coords ->
    if (!coords.isAttached || coords.size.width <= 0 || coords.size.height <= 0) {
        motion.wallViewport = Rect.Zero
        return@onPlaced
    }
    val tl = coords.localToScreen(Offset.Zero)
    val br = coords.localToScreen(Offset(coords.size.width.toFloat(), coords.size.height.toFloat()))
    motion.wallViewport = Rect(
        min(tl.x, br.x),
        min(tl.y, br.y),
        maxOf(tl.x, br.x),
        maxOf(tl.y, br.y),
    )
}

internal fun Modifier.shelfCatalogSlotVisible(motion: ShelfCatalogMotion, uri: String): Modifier = graphicsLayer {
    if (uri in motion.hiddenSlotUris) alpha = 0f
}

internal fun Modifier.shelfCatalogSlotPlaced(
    motion: ShelfCatalogMotion,
    uri: String,
    rowGridIndex: Int,
    colIndex: Int,
    layoutMode: ShelfCatalogLayoutMode,
    leanDegrees: Float = 0f,
): Modifier = onPlaced { coords ->
    motion.placeSlot(uri, rowGridIndex, colIndex, layoutMode, coords, leanDegrees)
}

internal fun Modifier.shelfCatalogBookLean(leanDegrees: Float): Modifier =
    if (abs(leanDegrees) < 0.05f) this else graphicsLayer {
        transformOrigin = TransformOrigin(0f, 1f)
        rotationZ = leanDegrees
    }

@Composable
internal fun rememberShelfCatalogPullToDismiss(
    motion: ShelfCatalogMotion,
    anchorGame: ScanGame?,
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val thresholdPx = CATALOG_PULL_DISMISS_DP * density
    val maxPullPx = CATALOG_PULL_MAX_DP * density
    return remember(motion, anchorGame, thresholdPx, maxPullPx) {
        Modifier.pointerInput(motion, anchorGame) {
            var dragged = 0f
            detectVerticalDragGestures(
                onDragStart = { dragged = motion.pullOffset.value },
                onVerticalDrag = { _, dragAmount ->
                    if (!motion.closing) {
                        dragged = (dragged + dragAmount).coerceAtLeast(0f)
                        val resisted = (1f - 1f / (dragged * 0.55f / maxPullPx + 1f)) * maxPullPx
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            motion.pullOffset.snapTo(resisted)
                        }
                    }
                },
                onDragEnd = {
                    if (dragged >= thresholdPx && !motion.closing) {
                        motion.close(anchorGame)
                    } else {
                        scope.launch {
                            motion.pullOffset.animateTo(0f, MotionTokens.release(0.5f))
                        }
                    }
                },
                onDragCancel = {
                    scope.launch {
                        motion.pullOffset.animateTo(0f, MotionTokens.releaseCalm(0.5f))
                    }
                },
            )
        }
    }
}

internal fun Modifier.shelfCatalogTroupePresence(motion: ShelfCatalogMotion): Modifier = graphicsLayer {
    alpha = motion.troupePresence.value.coerceIn(0f, 1f)
}

/** 从动态姿态度数平滑调制飞行子层透明度（`modulate = true` 时直接调制颜色透明度、不开离屏缓冲）。 */
internal fun Modifier.shelfCatalogTroupeAlpha(
    modulate: Boolean = false,
    alphaProvider: () -> Float,
): Modifier = graphicsLayer {
    alpha = alphaProvider().coerceIn(0f, 1f)
    if (modulate) compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** 飞行单本书外层屏幕底边中心定位（以书底中心为基准，兼容高矮不一的书脊与封面形态，底边严丝合缝）。 */
internal fun Modifier.shelfCatalogTroupeOuter(
    poseProvider: () -> ShelfCatalogBookPose,
    originProvider: () -> Offset,
    containerWidthPx: Float,
    containerHeightPx: Float,
): Modifier = graphicsLayer {
    val pose = poseProvider()
    val origin = originProvider()
    transformOrigin = TransformOrigin(0.5f, 1f)
    translationX = (pose.spineCenterX - origin.x) - containerWidthPx * 0.5f
    translationY = (pose.spineCenterY + 0.5f * pose.spineHeight - origin.y) - containerHeightPx
}

/** 飞行单本书中层同轴仰倾与微侧斜倚（严格绕与单排书架 / 藏书阁末本相同的视觉底边支点旋转）。 */
internal fun Modifier.shelfCatalogTroupeTilt(
    poseProvider: () -> ShelfCatalogBookPose,
    containerWidthPx: Float,
    catalogMode: ShelfCatalogLayoutMode,
    catalogLeanDegrees: Float,
): Modifier = graphicsLayer {
    val pose = poseProvider()
    val turnRad = Math.toRadians(pose.absYawDeg.toDouble()).toFloat()
    val shelfCenterOffsetPx = pose.facingSign * 0.5f * pose.coverWidth * cos(turnRad)
    val spineLeanMix = if (catalogMode == ShelfCatalogLayoutMode.SPINES && abs(catalogLeanDegrees) > 0.05f) {
        catalogSmoothstep(pose.catalogMix)
    } else {
        0f
    }
    val spineCornerOffsetPx = -0.5f * pose.spineWidth
    val pivotOffsetPx = shelfCenterOffsetPx * (1f - spineLeanMix) + spineCornerOffsetPx * spineLeanMix
    val pivotFractionX = if (containerWidthPx > 0f) {
        (0.5f + pivotOffsetPx / containerWidthPx).coerceIn(-1f, 2f)
    } else {
        0.5f
    }
    transformOrigin = TransformOrigin(pivotFractionX, 1f)
    rotationX = pose.tiltDegrees
    rotationZ = pose.leanDegrees
    cameraDistance = POSE_CAMERA_DISTANCE * density
}

/** 书脊渐变与棱边底壳：随书脊实时投影宽高自然延展（锚定底边），在圆角书脊完全接棒后平滑退出防止露角。 */
internal fun Modifier.shelfCatalogTroupeShell(
    poseProvider: () -> ShelfCatalogBookPose,
    baseSpineWidthPx: Float,
    baseHeightPx: Float,
    isSpinesCatalog: Boolean = false,
): Modifier = graphicsLayer {
    if (baseSpineWidthPx <= 0f || baseHeightPx <= 0f) return@graphicsLayer
    val pose = poseProvider()
    val sinTurn = sin(Math.toRadians(pose.absYawDeg.toDouble())).toFloat()
    val fade = if (isSpinesCatalog) {
        1f - catalogSmoothstep((pose.catalogMix - 0.72f) / 0.22f)
    } else {
        1f
    }
    if (sinTurn < 0.015f || fade <= 0.005f) {
        alpha = 0f
        return@graphicsLayer
    }
    alpha = fade
    transformOrigin = TransformOrigin(0.5f, 1f)
    scaleX = (pose.spineWidth / baseSpineWidthPx) * sinTurn
    scaleY = pose.spineHeight / baseHeightPx
}

/**
 * 单排书架比例 1:1 书脊排版层：采用一致的纵横比缩放（仅沿横轴乘 `sinTurn` 随侧转自然收窄），字形与圆点永不压扁。
 * - `SPINES` 模式下在 `catalogMix ∈ [0.18, 0.52]` 内平滑淡出，与 `CatalogVertical` 交叠接力，杜绝中段空字断档；
 * - `COVERS` 模式下保持清晰跟随书脊翻转，直到 `absYaw -> 0°` 自然隐去。
 */
internal fun Modifier.shelfCatalogTroupeShelfText(
    poseProvider: () -> ShelfCatalogBookPose,
    baseHeightPx: Float,
    isCoverCatalog: Boolean,
): Modifier = graphicsLayer {
    if (baseHeightPx <= 0f) return@graphicsLayer
    val pose = poseProvider()
    val a = if (isCoverCatalog) {
        1f
    } else {
        1f - catalogSmoothstep((pose.catalogMix - 0.18f) / 0.34f)
    }
    val sinTurn = sin(Math.toRadians(pose.absYawDeg.toDouble())).toFloat()
    if (a <= 0.005f || sinTurn < 0.015f) {
        alpha = 0f
        return@graphicsLayer
    }
    val uniformScale = pose.spineHeight / baseHeightPx
    alpha = a
    transformOrigin = TransformOrigin(0.5f, 1f)
    scaleX = uniformScale * sinTurn
    scaleY = uniformScale
}

/** 书架立排槽位比例层：在 `catalogMix ∈ [0.38, 0.72]` 内柔和接力淡入，与单排书脊交叠无缝衔接、与静态列表槽 1:1 像素对齐。 */
internal fun Modifier.shelfCatalogTroupeCatalogVertical(
    poseProvider: () -> ShelfCatalogBookPose,
    catWidthPx: Float,
    catHeightPx: Float,
): Modifier = graphicsLayer {
    if (catWidthPx <= 0f || catHeightPx <= 0f) return@graphicsLayer
    val pose = poseProvider()
    val a = catalogSmoothstep((pose.catalogMix - 0.38f) / 0.34f)
    val sinTurn = sin(Math.toRadians(pose.absYawDeg.toDouble())).toFloat()
    if (a <= 0.005f || sinTurn < 0.015f) {
        alpha = 0f
        return@graphicsLayer
    }
    alpha = a
    transformOrigin = TransformOrigin(0.5f, 1f)
    scaleX = (pose.spineWidth / catWidthPx) * sinTurn
    scaleY = pose.spineHeight / catHeightPx
}

/** 封面陈列模式 1:1 完整封面层（绕与封面相同的装订棱做 3D 翻合，与静态封面槽 1:1 逐像素对齐）。 */
internal fun Modifier.shelfCatalogTroupeCoverFaceTransform(
    poseProvider: () -> ShelfCatalogBookPose,
    catCoverWidthPx: Float,
    catHeightPx: Float,
    shapes: ShelfBoxShapes,
): Modifier = graphicsLayer {
    if (catCoverWidthPx <= 0f || catHeightPx <= 0f) return@graphicsLayer
    val pose = poseProvider()
    val coverMix = catalogSmoothstep((pose.catalogMix - 0.25f) / 0.45f)
    val a = (pose.frontAlpha * coverMix).coerceIn(0f, 1f)
    if (a <= 0.005f) {
        alpha = 0f
        return@graphicsLayer
    }
    val turnRad = Math.toRadians(pose.absYawDeg.toDouble()).toFloat()
    val sinTurn = sin(turnRad)
    alpha = a
    cameraDistance = SHELF_FACE_CAMERA * density
    scaleY = pose.spineHeight / catHeightPx
    scaleX = pose.coverWidth / catCoverWidthPx
    translationY = 0.5f * (catHeightPx - pose.spineHeight)
    if (pose.facingSign >= 0f) {
        shape = shapes.frontWithSpineLeft
        clip = true
        val seamLocalX = catCoverWidthPx * 0.5f + 0.5f * pose.spineWidth * sinTurn
        transformOrigin = TransformOrigin(0f, 0.5f)
        translationX = seamLocalX
        rotationY = pose.absYawDeg
    } else {
        shape = shapes.frontWithSpineRight
        clip = true
        val seamLocalX = catCoverWidthPx * 0.5f - 0.5f * pose.spineWidth * sinTurn
        transformOrigin = TransformOrigin(1f, 0.5f)
        translationX = seamLocalX - catCoverWidthPx
        rotationY = -pose.absYawDeg
    }
}

/** 飞行单本书 3D 封面层：紧贴共用装订棱随 `absYawDeg` 开合（以垂直中线为 3D 灭点并严格锚定底边）。 */
internal fun Modifier.shelfCatalogTroupeFront(
    poseProvider: () -> ShelfCatalogBookPose,
    shapes: ShelfBoxShapes,
    baseCoverWidthPx: Float,
    baseHeightPx: Float,
): Modifier = graphicsLayer {
    if (baseCoverWidthPx <= 0f || baseHeightPx <= 0f) return@graphicsLayer
    val pose = poseProvider()
    if (pose.frontAlpha <= 0.005f) {
        alpha = 0f
        return@graphicsLayer
    }
    val turnRad = Math.toRadians(pose.absYawDeg.toDouble()).toFloat()
    val sinTurn = sin(turnRad)
    alpha = pose.frontAlpha
    cameraDistance = SHELF_FACE_CAMERA * density
    scaleY = pose.spineHeight / baseHeightPx
    scaleX = pose.coverWidth / baseCoverWidthPx
    translationY = 0.5f * (baseHeightPx - pose.spineHeight)
    if (pose.facingSign >= 0f) {
        shape = shapes.frontWithSpineLeft
        clip = true
        val seamLocalX = baseCoverWidthPx * 0.5f + 0.5f * pose.spineWidth * sinTurn
        transformOrigin = TransformOrigin(0f, 0.5f)
        translationX = seamLocalX
        rotationY = pose.absYawDeg
    } else {
        shape = shapes.frontWithSpineRight
        clip = true
        val seamLocalX = baseCoverWidthPx * 0.5f - 0.5f * pose.spineWidth * sinTurn
        transformOrigin = TransformOrigin(1f, 0.5f)
        translationX = seamLocalX - baseCoverWidthPx
        rotationY = -pose.absYawDeg
    }
}

/** 退场飞行走到 [flight]（0..1，弹簧可能略超过 1）时，书架上真正的书该有多显现。 */
internal fun shelfCatalogHandoffAlpha(flight: Float): Float {
    val x = ((flight - CATALOG_HANDOFF_START) / (1f - CATALOG_HANDOFF_START)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun stageRecedeScale(progress: Float): Float = 1f - STAGE_RECEDE_SCALE_DROP * progress

private const val STAGE_RECEDE_SCALE_DROP = 0.07f
private const val STAGE_RECEDE_PIVOT_Y = 0.55f
private const val STAGE_RECEDE_SHIFT_DP = 18f
private const val CHROME_RECEDE_SHIFT_DP = 24f
private const val HEADER_ENTRANCE_SHIFT_DP = 20f
private const val EARLY_CASCADE_GATE = 0.08f
private const val CATALOG_CASCADE_STEP = 0.036f
private const val CATALOG_CASCADE_MAX_DELAY = 0.24f
private const val CATALOG_FLIGHT_STAGGER = 0.024f
private const val CATALOG_PICK_STAGGER = 0.028f
private const val CATALOG_FLIGHT_MAX_DELAY = 0.18f
private const val CATALOG_PULL_DISMISS_DP = 110f
private const val CATALOG_PULL_MAX_DP = 130f

private const val ENTER_WAIT_SLOT_MS = 140L
private const val EXIT_WAIT_ALIGN_MS = 180L
private const val EXIT_WAIT_ORIGIN_MS = 120L
/** 飞行的书走到全程的这一比例之后，书架上真正的书开始浮现（到 1 时完全显现）。 */
private const val CATALOG_HANDOFF_START = 0.92f
private const val CATALOG_HANDOFF_MS = 80
private const val CATALOG_EXIT_FADE_MS = 140

private val CATALOG_ENTER_SPRING = MotionSprings.response<Float>(0.68f, 0.88f, 0.0005f)
private val CATALOG_FLIGHT_ENTER_SPRING = MotionSprings.response<Float>(0.72f, 0.88f, 0.0005f)
private val CATALOG_PICK_SPRING = MotionSprings.response<Float>(0.64f, 0.90f, 0.0005f)
private val CATALOG_EXIT_SPRING = MotionSprings.response<Float>(0.58f, 0.90f, 0.0005f)
private const val MORPH_STAGGER = 0.012f
private const val MORPH_MAX_DELAY = 0.2f
private const val MORPH_ARC = 0.05f
private const val MORPH_WAIT_FRAMES = 12
private const val MORPH_WARMUP_FRAMES = 3
private val MORPH_SPRING = MotionSprings.response<Float>(0.72f, 0.92f, 0.0005f)
private val CATALOG_INTERRUPT_SPRING = MotionSprings.response<Float>(0.16f, 1f, 0.0005f)
private const val CATALOG_INTERRUPT_HANDOFF_MS = 40

/** [disabled] 时把这一层的布局大小报成 0（内容照常按原约束测量、摆在原位、照常绘制，只是命中测试落空），触摸穿透到下层。 */
internal fun Modifier.noHitWhile(disabled: Boolean): Modifier = if (!disabled) this else layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(0, 0) { placeable.place(0, 0) }
}

/**
 * 「书脊 | 封面」两种陈列方式之间的切换里**书柜本体**（搁板 / 立柱 / 铭牌 / 板下书名）的交接。书不在这里：
 * 每一本书由逐本变形的卡片接管（见 [ShelfCatalogMotion.switchLayout]），卡片与书柜本体共用 [progress] 这一个时钟——
 * 旧书柜的「底」（搁板 / 立柱 / 铭牌）与新书柜交叠着慢慢换（一边淡去一边浮现，底色不断档；以前旧的先整块没了、新的再整块冒出来，
 * 中间露出光秃秃的页面底色，像背景闪了一下），跟着书走的板下书名则在变形一开始就淡去（见 [shelfCatalogLeavingText]）。
 *
 * [leavingMode] 非空期间新旧两面叠着、旧面不接触摸；变形结束（或根本没有变形：「普通」档、藏书阁还没入场完）就只剩新的一面。
 * 单一形态时没有任何额外开销。
 */
@Composable
internal fun ShelfCatalogModeSwitcher(
    mode: ShelfCatalogLayoutMode,
    /** 变形途中正在退场的旧形态；不在变形中为 null（直接换：只留新的一面）。 */
    leavingMode: ShelfCatalogLayoutMode?,
    progress: () -> Float,
    blockTouches: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (ShelfCatalogLayoutMode) -> Unit,
) {
    val leaving = leavingMode?.takeIf { it != mode }
    val current = mode
    Box(modifier) {
        leaving?.let { old ->
            key(old) {
                Box(
                    Modifier
                        // noHitWhile 必须在 fillMaxSize 之前：它把布局大小报成 0，放在铺满约束的里面，
                        // 约束会把 0 抬回满尺寸、内容反被居中偏移半个屏幕（旧书柜整个错位到右下角）
                        .noHitWhile(true)
                        .fillMaxSize()
                        .graphicsLayer {
                            // 搁板 / 立柱 / 铭牌是书柜的「底」：与新书柜交叠着慢慢换（交叠处两边都在，底色不断档），
                            // 不是旧的先整块没了、新的再整块冒出来——中间露出一整片光秃秃的页面底色，看起来像背景闪了一下
                            alpha = 1f - smoothStep((progress() - MODE_BODY_OUT_START) / MODE_BODY_OUT_SPAN)
                        },
                ) {
                    // 板下的书名是跟着书走的字：书一飞走就不该还留在原地，仍然快速淡去
                    CompositionLocalProvider(LocalShelfCatalogLeavingWall provides true) { content(old) }
                }
            }
        }
        key(current) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (leaving == null) return@graphicsLayer
                        // 新书柜与旧书柜交叠着浮现（见上）：书还在空中时底已经在了，书落位时它也就位
                        alpha = smoothStep((progress() - MODE_BODY_FADE_IN_START) / MODE_BODY_FADE_IN_SPAN)
                    }
                    .then(
                        if (blockTouches) {
                            // 变形途中不接触摸：卡片的终点是按当时书柜位置算好的
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) { content(current) }
        }
    }
}

/** 旧书柜的底从进度 [MODE_BODY_OUT_START] 起、用 [MODE_BODY_OUT_SPAN] 淡去；新书柜的底从 [MODE_BODY_FADE_IN_START] 起、用 [MODE_BODY_FADE_IN_SPAN] 浮现（两段交叠）。 */
private const val MODE_BODY_OUT_START = 0.2f
private const val MODE_BODY_OUT_SPAN = 0.5f
private const val MODE_BODY_FADE_IN_START = 0.1f
private const val MODE_BODY_FADE_IN_SPAN = 0.5f

/** 旧书柜里「跟着书走」的字（封面陈列板下的书名）淡去的进度长度。 */
private const val MODE_TEXT_FADE_OUT = 0.26f

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 当前这面书柜是不是正在退场的旧面（切换陈列的变形途中）。 */
internal val LocalShelfCatalogLeavingWall = androidx.compose.runtime.compositionLocalOf { false }

/** 退场中的旧书柜里跟着书走的字（板下书名）：变形一开始就快速淡去；不在退场中不改任何东西。 */
@Composable
internal fun Modifier.shelfCatalogLeavingText(motion: ShelfCatalogMotion): Modifier {
    if (!LocalShelfCatalogLeavingWall.current) return this
    return graphicsLayer { alpha = (1f - motion.morphProgress.value / MODE_TEXT_FADE_OUT).coerceIn(0f, 1f) }
}

/** 书脊 ↔ 封面变形里的一本书：旧陈列里的屏幕矩形、新陈列里的屏幕矩形（摆好后补上；新陈列里看不见的 [vanish] 淡出）与错峰延迟。 */
@Stable
internal class ShelfCatalogMorphFlight(
    val game: ScanGame,
    val spineIndex: Int,
    val fromRect: Rect,
    val fromMode: ShelfCatalogLayoutMode,
    val toMode: ShelfCatalogLayoutMode,
    val isCurrent: Boolean,
    val delay: Float,
    /** 旧陈列里没有它的位置（也外推不出来）：不从旧面变过来，在自己的落点上浮现。 */
    val appear: Boolean = false,
) {
    var toRect by mutableStateOf<Rect?>(null)
    var vanish by mutableStateOf(false)
    /** 同帧新旧双面共用 `morphPoseOf` 计算结果。 */
    internal var cachedProgress: Float = Float.NaN
    internal var cachedOriginX: Float = Float.NaN
    internal var cachedOriginY: Float = Float.NaN
    internal var cachedHasTarget: Boolean = false
    internal var cachedPose: ShelfCatalogMorphPose? = null
}

/**
 * 一种陈列的网格几何（px）：每行的列数、相邻两格的水平间距（格宽 + 缝）与相邻两行的垂直间距（一层的高度）。
 * [grouped] 为真（按引擎分架，章节横梁让行高不规则）时不能按规则外推。
 */
internal class ShelfCatalogGridMetrics(
    val columns: Int,
    val pitchX: Float,
    val pitchY: Float,
    val grouped: Boolean,
)

/**
 * 切换陈列后首屏的滚动位置：让锚点书的中心落在视口里 [anchorY]（距视口顶，px）那么高。
 * 返回（首个可见层的下标，该层被卷走的偏移）；不能按规则算时（分架有章节横梁、没有锚点）返回 null，退回按行定位。
 * [pitchPx] 是一层的高度，[cellCenterPx] 是书格中心距一层顶部的距离。越过两端时由列表自己收回到有效范围。
 */
internal fun shelfCatalogAlignedScroll(
    rows: List<ShelfCatalogRowItem>,
    anchorUri: String?,
    anchorY: Float?,
    pitchPx: Float,
    cellCenterPx: Float,
): Pair<Int, Int>? {
    if (anchorUri == null || anchorY == null || pitchPx <= 0f || rows.isEmpty()) return null
    if (rows.any { it is ShelfCatalogRowItem.SectionHeader }) return null
    val rowIndex = findShelfCatalogAnchorRow(rows, anchorUri)
    if (rowIndex < 0) return null
    val scroll = (rowIndex * pitchPx + cellCenterPx - anchorY).coerceAtLeast(0f)
    val index = kotlin.math.floor(scroll / pitchPx).toInt().coerceIn(0, rows.lastIndex)
    val offset = (scroll - index * pitchPx).toInt().coerceAtLeast(0)
    return index to offset
}

/** 变形卡片此刻的位置与大小（相对覆盖层）和这一本自己的进度 [t]。 */
internal class ShelfCatalogMorphPose(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val t: Float,
)

/**
 * 变形卡片的一个面：按这一本此刻的位置与大小把面（以它自己的自然大小 [natural] 排版）平移 + 缩放过去；
 * 旧陈列的面（[incoming] = false）随进度淡出、新陈列的面淡入，两面在中段交叠；新陈列里看不见的书（vanish）只淡出。
 */
internal fun Modifier.morphCard(
    motion: ShelfCatalogMotion,
    flight: ShelfCatalogMorphFlight,
    natural: () -> androidx.compose.ui.geometry.Size,
    incoming: Boolean,
): Modifier = this.graphicsLayer {
    val pose = motion.morphPoseOf(flight)
    val size = natural()
    transformOrigin = TransformOrigin(0f, 0f)
    translationX = pose.left
    translationY = pose.top
    scaleX = if (size.width > 0f) pose.width / size.width else 1f
    scaleY = if (size.height > 0f) pose.height / size.height else 1f
    val mix = ((pose.t - MORPH_MIX_START) / MORPH_MIX_SPAN).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    alpha = when {
        flight.vanish -> if (incoming) 0f else (1f - ((pose.t) / MORPH_VANISH_SPAN).coerceIn(0f, 1f))
        // 旧陈列里没有它的位置：只有新面，在落点上渐渐浮现
        flight.appear -> if (incoming) ((pose.t) / MORPH_VANISH_SPAN).coerceIn(0f, 1f) else 0f
        incoming -> mix
        else -> 1f - mix
    }
}

private const val MORPH_MIX_START = 0.22f
private const val MORPH_MIX_SPAN = 0.5f
private const val MORPH_VANISH_SPAN = 0.45f
