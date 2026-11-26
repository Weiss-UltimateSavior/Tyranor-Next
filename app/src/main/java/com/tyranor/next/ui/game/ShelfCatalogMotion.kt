package com.tyranor.next.ui.game

import android.animation.ValueAnimator
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.Shape
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
import com.tyranor.next.core.game.model.GameLibraryConditions
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.MotionIntent
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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

/** 藏书阁书脊立排每层可容纳书本数（由柜内层宽 ÷ (基准书脊宽 + 基准缝) 决定，书本靠左自然排布、不拉宽铺满）。 */
internal fun shelfCatalogVerticalColumns(widthDp: Dp): Int {
    val cabinetInset = shelfCatalogCabinetInset(widthDp)
    val innerWidth = (widthDp - cabinetInset * 2 - (CABINET_STILE_WIDTH + CABINET_INTERIOR_INSET) * 2)
        .coerceAtLeast(0.dp)
    val spineHeight = shelfCatalogSpineHeight(widthDp)
    val baseWidth = shelfCatalogSpineBaseWidth(spineHeight)
    val pitch = baseWidth + VERTICAL_SPINE_NOMINAL_GAP
    return (innerWidth / pitch).toInt().coerceAtLeast(1)
}

internal fun shelfCatalogCabinetInset(widthDp: Dp): Dp = if (widthDp < 460.dp) 10.dp else 14.dp

internal fun shelfCatalogSpineHeight(widthDp: Dp): Dp {
    val cabinetWidth = (widthDp - shelfCatalogCabinetInset(widthDp) * 2).coerceAtLeast(0.dp)
    return (cabinetWidth * SPINE_HEIGHT_TO_CABINET_RATIO).coerceIn(SPINE_MIN_HEIGHT, SPINE_MAX_HEIGHT)
}

internal fun shelfCatalogSpineBaseWidth(spineHeight: Dp): Dp = spineHeight * SPINE_WIDTH_TO_HEIGHT_RATIO

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

internal enum class ShelfCatalogReflow { NONE, RESET_TOP, KEEP_CENTER }

internal fun shelfCatalogReflow(
    previous: GameLibraryConditions?,
    current: GameLibraryConditions,
    columnsChanged: Boolean,
): ShelfCatalogReflow = when {
    previous == null -> ShelfCatalogReflow.NONE
    previous.engineFilter != current.engineFilter || previous.playedFilter != current.playedFilter ->
        ShelfCatalogReflow.RESET_TOP
    previous.sortMode != current.sortMode || columnsChanged -> ShelfCatalogReflow.KEEP_CENTER
    else -> ShelfCatalogReflow.NONE
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

/** 按 uri（可选混入相邻 uri 与盐值）确定性生成 `[0f, 1f]` 浮点值。 */
private fun uriUnitFloat(primary: String, secondary: String = "", salt: Int = 0): Float {
    var h = FNV1A_OFFSET_BASIS xor salt
    for (i in 0 until primary.length) {
        h = (h xor primary[i].code) * FNV1A_PRIME
    }
    if (secondary.isNotEmpty()) {
        h = (h xor 0xff) * FNV1A_PRIME
        for (i in 0 until secondary.length) {
            h = (h xor secondary[i].code) * FNV1A_PRIME
        }
    }
    h = h xor (h ushr 16)
    h *= MURMUR_C1
    h = h xor (h ushr 13)
    h *= MURMUR_C2
    h = h xor (h ushr 16)
    return ((h ushr 16) and 0xffff) / 65535f
}

/** 竖排每本书高矮起伏比例：与书架主视图 [shelfBookTrait] 同源派生，高矮倾向一致、幅度克制（0.88~1.0）。 */
/** 书脊立排里最矮的书相对最高的书的高度比例（排字按它留空间，见 ShelfCatalogVerticalSpineFace）。 */
internal val SHELF_CATALOG_MIN_SPINE_HEIGHT_FACTOR: Float get() = 1f - SPINE_HEIGHT_VARIANCE

internal fun shelfCatalogSpineHeightFactor(uri: String): Float {
    val trait = shelfBookTrait(uri)
    val t = ((1f - trait.sizeScale) / MAIN_SHELF_SIZE_VARIANCE).coerceIn(0f, 1f)
    return 1f - t * SPINE_HEIGHT_VARIANCE
}

/** 竖排每本书书脊厚薄系数（0.88~1.12，按 uri 确定性派生，与高度独立混洗）。 */
internal fun shelfCatalogSpineThicknessFactor(uri: String): Float {
    val u = uriUnitFloat(uri)
    return SPINE_MIN_THICKNESS_FACTOR + u * (SPINE_MAX_THICKNESS_FACTOR - SPINE_MIN_THICKNESS_FACTOR)
}

/** 竖排相邻两本书之间的缝宽（dp，1.0~4.0dp 按 uri 确定性变化）。 */
internal fun shelfCatalogSpineGapDp(leftUri: String, rightUri: String = ""): Float {
    val u = uriUnitFloat(leftUri, rightUri, SPINE_GAP_SALT)
    return SPINE_MIN_GAP_DP + u * (SPINE_MAX_GAP_DP - SPINE_MIN_GAP_DP)
}

/** 竖排少数书（约 1/5）向内略收一点的绘制微调：书底略抬、整本略暗，让一排书不显得过度整齐划一。 */
internal data class ShelfCatalogSpineRecess(
    val liftDp: Float,
    val shadeAlpha: Float,
) {
    companion object {
        val None = ShelfCatalogSpineRecess(0f, 0f)
    }
}

internal fun shelfCatalogSpineRecess(uri: String): ShelfCatalogSpineRecess {
    val u = uriUnitFloat(uri, salt = SPINE_RECESS_SALT)
    if (u >= SPINE_RECESS_SHARE) return ShelfCatalogSpineRecess.None
    val t = ((SPINE_RECESS_SHARE - u) / SPINE_RECESS_SHARE).coerceIn(0f, 1f)
    return ShelfCatalogSpineRecess(
        liftDp = SPINE_RECESS_MIN_LIFT_DP + t * (SPINE_RECESS_MAX_LIFT_DP - SPINE_RECESS_MIN_LIFT_DP),
        shadeAlpha = SPINE_RECESS_MIN_SHADE + t * (SPINE_RECESS_MAX_SHADE - SPINE_RECESS_MIN_SHADE),
    )
}

/** 封面陈列轻微的高矮差异（与书架主视图同一高矮倾向，幅度克制以保持网格整齐）。 */
internal fun shelfCatalogCoverHeightFactor(uri: String): Float {
    val trait = shelfBookTrait(uri)
    val t = ((1f - trait.sizeScale) / MAIN_SHELF_SIZE_VARIANCE).coerceIn(0f, 1f)
    return 1f - t * COVER_HEIGHT_VARIANCE
}

/** 为藏书阁「书脊立排」单层计算各相邻书本之间的像素缝宽（长度为 `(uris.size - 1).coerceAtLeast(0)`）。 */
internal fun allocateShelfCatalogSpineGapsPx(
    uris: List<String>,
    density: Float,
): List<Int> {
    if (uris.size <= 1 || density <= 0f) return emptyList()
    return List(uris.size - 1) { i ->
        (shelfCatalogSpineGapDp(uris[i], uris[i + 1]) * density).roundToInt().coerceAtLeast(1)
    }
}

/**
 * 为藏书阁「书脊立排」单层分配各本的像素宽度：
 * 每本书保留自然厚度（`baseCellWidthPx * factor`）靠左排布、不为了铺满整层而拉宽，层末空位自然留白；
 * 仅当整层自然宽加缝宽（及未满层末本斜倚余量）超出柜内可用宽度 [innerWidthPx] 时才按比例微收，防止越出柜柱。
 */
internal fun allocateShelfCatalogSpineWidths(
    uris: List<String>,
    maxColumns: Int,
    baseCellWidthPx: Float,
    innerWidthPx: Int,
    gapsSumPx: Int,
    trailingLeanReservePx: Float = 0f,
): List<Int> {
    val n = uris.size
    if (n == 0) return emptyList()
    if (maxColumns <= 0 || innerWidthPx <= 0 || baseCellWidthPx <= 0f) {
        return List(n) { 0 }
    }
    val rawWidths = FloatArray(n) { baseCellWidthPx * shelfCatalogSpineThicknessFactor(uris[it]) }
    val reserve = if (n in 2 until maxColumns) trailingLeanReservePx.coerceAtLeast(0f).roundToInt() else 0
    val budgetPx = (innerWidthPx - gapsSumPx - reserve).coerceAtLeast(n)
    val naturalRounded = IntArray(n) { rawWidths[it].roundToInt().coerceAtLeast(1) }
    if (naturalRounded.sum() <= budgetPx) {
        return naturalRounded.toList()
    }
    val rawSum = rawWidths.sum()
    val scale = if (rawSum > 0f) budgetPx.toFloat() / rawSum else 1f
    val scaled = FloatArray(n) { rawWidths[it] * scale }
    val minPerItem = if (budgetPx >= n) 1 else 0
    val out = IntArray(n)
    var cumFloat = 0f
    var cumInt = 0
    for (i in 0 until n) {
        cumFloat += scaled[i]
        val desiredCum = if (i == n - 1) {
            budgetPx
        } else {
            cumFloat.roundToInt().coerceAtMost(budgetPx - minPerItem * (n - 1 - i))
        }
        val w = (desiredCum - cumInt).coerceAtLeast(minPerItem)
        out[i] = w
        cumInt += w
    }
    return out.toList()
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

/** 竖排末本斜倚时底部中心向右微移的像素量，使书脊顶部刚好轻靠在前一本肩上而底角不插进邻书。 */
internal fun shelfCatalogEndBookLeanShiftPx(
    leanDegrees: Float,
    bookHeightPx: Float,
    bookWidthPx: Float,
): Float {
    if (leanDegrees >= -0.05f || bookHeightPx <= 0f || bookWidthPx <= 0f) return 0f
    val rad = Math.toRadians(abs(leanDegrees).toDouble())
    return (bookHeightPx * sin(rad).toFloat() * 0.72f).coerceAtMost(bookWidthPx * 0.42f)
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

    /** 书架上此刻有手指按着（书架根节点在初始阶段登记，只观察、不消费）。 */
    private var shelfTouching = false

    /** 书架上的长按已成立、在等旧覆盖层收尾后重开（见 `ShelfCatalogSession`）：这时收尾不必再等手指抬起。由宿主置位、重开后复位。 */
    var reopenRequested = false

    private var deferredDismiss: (() -> Unit)? = null

    /**
     * 覆盖层收场结束，要交付收尾（把它从组合里拿掉）。
     *
     * 退场期间触摸已经递给下面的书架，覆盖层节点也在这次触摸的命中路径里：此刻手指还按在书架上就把覆盖层拿掉，
     * Compose 会因为路径里有节点离开组合而取消整条路径——长按攒不到成立、横向拖动半路断掉。所以手指还按着就先挂起，
     * 抬手后再交付；长按已成立、正等着重开（[reopenRequested]）时不用等——这次手势的长按已经触发过了，取消无妨。
     */
    fun dismissWhenShelfReleased(dismiss: () -> Unit) {
        if (shelfTouching && !reopenRequested) deferredDismiss = dismiss else dismiss()
    }

    /** 书架根节点上报手指按下 / 抬起；抬起时交付被挂起的收尾。 */
    fun onShelfTouch(pressed: Boolean) {
        shelfTouching = pressed
        if (!pressed) flushDeferredDismiss()
    }

    /** 交付被挂起的收尾（没有挂起则什么也不做）。 */
    fun flushDeferredDismiss() {
        val dismiss = deferredDismiss ?: return
        deferredDismiss = null
        dismiss()
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
        private set

    var layoutRevision by mutableIntStateOf(0)
        private set

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
    private val registeredTierPeeks = HashSet<ShelfCatalogTierPeekState>()
    private var activePeekingTier: ShelfCatalogTierPeekState? = null

    internal val isSpinePeeking: Boolean
        get() = activePeekingTier != null

    internal fun registerTierPeek(state: ShelfCatalogTierPeekState) {
        registeredTierPeeks.add(state)
    }

    internal fun unregisterTierPeek(state: ShelfCatalogTierPeekState) {
        registeredTierPeeks.remove(state)
        if (activePeekingTier === state) activePeekingTier = null
    }

    internal fun notifyTierPeekActive(state: ShelfCatalogTierPeekState, active: Boolean) {
        if (active) {
            val previous = activePeekingTier
            if (previous != null && previous !== state) {
                previous.resetImmediately()
            }
            activePeekingTier = state
        } else if (activePeekingTier === state) {
            activePeekingTier = null
        }
    }

    /** 立即无动画复位所有层上的书脊翻看状态（关藏书阁 / 切换陈列 / 列表滚动 / 筛选变化前调用）。 */
    internal fun resetSpinePeekImmediately() {
        activePeekingTier = null
        if (registeredTierPeeks.isEmpty()) return
        for (state in registeredTierPeeks.toList()) {
            state.resetImmediately()
        }
    }

    fun interact(): Boolean {
        if (closing) {
            MotionHaptics.perform(MotionHaptics.Kind.Reject)
            return false
        }
        return true
    }

    fun syncCatalogGames(filtered: List<ScanGame>, all: List<ScanGame>) {
        if (filteredGamesSnapshot.isNotEmpty() && filtered != filteredGamesSnapshot) {
            resetSpinePeekImmediately()
        }
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

    /**
     * 组合在 [revision]（陈列的第几次切换）时看到的变形总进度。接力切换（变形途中点了另一边、上一轮放完后接着开下一轮）时，
     * [morphProgress] 在 [beginSwitch] 里同步归零，而界面要下一帧才重组成新一轮：那一帧里还在画的旧组合带着「退场的旧书柜」，
     * 按进度 0 画就是旧面不透明、新面透明——上一轮刚被换下去的封面书柜整块闪出来。陈列已换到更新的一轮时，旧组合就按「已放完」(1) 画，
     * 与它上一帧的样子一致；新组合（revision 相同）读真实进度。
     */
    internal fun morphProgressSeenBy(revision: Int): Float =
        shelfCatalogMorphProgressSeen(revision, layoutRevision, morphProgress.value)

    /** 变形途中又点了另一边：先记下最后选的这一边，等当前这一轮落位后再开新的一轮。 */
    private var pendingLayout by mutableStateOf<ShelfCatalogLayoutMode?>(null)

    /** 分栏上选中的形态：途中点了另一边时立刻跟着手走，不等这一轮放完。 */
    val requestedLayout: ShelfCatalogLayoutMode get() = pendingLayout ?: layoutMode

    private fun finishMorphNow() {
        pendingLayout = null
        morphGeneration++
        activeMorph = emptyList()
        hiddenSlotUris = emptySet()
        layoutAnchorY = null
        morphing = false
    }

    /**
     * 看不见的书在 [mode] 陈列里的位置：按网格规则（第 i 本在第 i / 列数 行、第 i % 列数 列），从最近一行里测得的一本推导出来
     * （书脊立排会还原该层的归一化宽度分配与每本高度、末本斜倚，确保与实际书格位置零偏差）。
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
        var refUri = ""
        var refIndex = 0
        var best = Int.MAX_VALUE
        for ((uri, slot) in measured) {
            val i = indexMap[uri] ?: continue
            val distance = abs(i / grid.columns - row)
            if (distance < best) {
                best = distance
                ref = slot
                refUri = uri
                refIndex = i
            }
        }
        val anchor = ref ?: return null
        val games = filteredGamesSnapshot
        if (grid.maxBookHeightPx <= 0f || index >= games.size || refIndex >= games.size) {
            return anchor.rect.translate(
                (col - refIndex % grid.columns) * grid.pitchX,
                (row - refIndex / grid.columns) * grid.pitchY,
            )
        }
        val refRow = refIndex / grid.columns
        val refCol = refIndex % grid.columns
        fun verticalOffsetPx(uri: String): Float = when {
            uri == currentFocusUri -> grid.focusLiftPx
            mode == ShelfCatalogLayoutMode.SPINES ->
                (-shelfCatalogSpineRecess(uri).liftDp * grid.density).roundToInt().toFloat()
            else -> 0f
        }
        val refBottomFloor = anchor.rect.bottom - verticalOffsetPx(refUri)
        val targetUri = games[index].uri
        val targetBottom = refBottomFloor + (row - refRow) * grid.pitchY + verticalOffsetPx(targetUri)
        if (mode == ShelfCatalogLayoutMode.COVERS) {
            val targetH = (grid.maxBookHeightPx * shelfCatalogCoverHeightFactor(targetUri)).coerceAtLeast(1f)
            val left = anchor.rect.left + (col - refCol) * grid.pitchX
            return Rect(left, targetBottom - targetH, left + anchor.rect.width, targetBottom)
        }
        if (grid.innerWidthPx <= 0 || grid.baseCellWidthPx <= 0f) {
            return anchor.rect.translate(
                (col - refCol) * grid.pitchX,
                (row - refRow) * grid.pitchY,
            )
        }
        val refStart = refRow * grid.columns
        val refEnd = (refStart + grid.columns).coerceAtMost(games.size)
        val refUris = games.subList(refStart, refEnd).map { it.uri }
        val refGaps = allocateShelfCatalogSpineGapsPx(refUris, grid.density)
        val refWidths = allocateShelfCatalogSpineWidths(
            uris = refUris,
            maxColumns = grid.columns,
            baseCellWidthPx = grid.baseCellWidthPx,
            innerWidthPx = grid.innerWidthPx,
            gapsSumPx = refGaps.sum(),
            trailingLeanReservePx = grid.trailingLeanReservePx,
        )
        var refOffsetX = 0f
        for (c in 0 until refCol) {
            refOffsetX += refWidths[c] + refGaps[c]
        }
        val refLeanShift = shelfCatalogEndBookLeanShiftPx(
            leanDegrees = anchor.leanDegrees,
            bookHeightPx = anchor.rect.height,
            bookWidthPx = anchor.rect.width,
        )
        val booksStartX = anchor.rect.left - refLeanShift - refOffsetX

        val targetStart = row * grid.columns
        val targetEnd = (targetStart + grid.columns).coerceAtMost(games.size)
        val targetUris = games.subList(targetStart, targetEnd).map { it.uri }
        val targetGaps = allocateShelfCatalogSpineGapsPx(targetUris, grid.density)
        val targetWidths = allocateShelfCatalogSpineWidths(
            uris = targetUris,
            maxColumns = grid.columns,
            baseCellWidthPx = grid.baseCellWidthPx,
            innerWidthPx = grid.innerWidthPx,
            gapsSumPx = targetGaps.sum(),
            trailingLeanReservePx = grid.trailingLeanReservePx,
        )
        var targetOffsetX = 0f
        for (c in 0 until col) {
            targetOffsetX += targetWidths[c] + targetGaps[c]
        }
        val targetW = targetWidths[col].toFloat().coerceAtLeast(1f)
        val targetH = (grid.maxBookHeightPx * shelfCatalogSpineHeightFactor(targetUri)).coerceAtLeast(1f)
        val targetLean = shelfCatalogEndBookLeanDegrees(
            colIndex = col,
            tierCount = targetUris.size,
            maxColumns = grid.columns,
            isCurrent = targetUri == currentFocusUri,
        )
        val targetLeanShift = shelfCatalogEndBookLeanShiftPx(targetLean, targetH, targetW)
        val targetLeft = booksStartX + targetOffsetX + targetLeanShift
        return Rect(targetLeft, targetBottom - targetH, targetLeft + targetW, targetBottom)
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
        resetSpinePeekImmediately()
        if (morphing) {
            pendingLayout = target.takeIf { it != layoutMode }
            return true
        }
        beginSwitch(target)
        return true
    }

    private fun beginSwitch(target: ShelfCatalogLayoutMode) {
        if (!enhanced || !hasEntered || closing || !ValueAnimator.areAnimatorsEnabled()) {
            settleEntryNow()
            changeLayout(target)
            return
        }
        val fromMode = layoutMode
        val from = snapshotVisibleSlots(mode = fromMode, includePartial = true)
        if (from.isEmpty()) {
            changeLayout(target)
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
            changeLayout(target)
            return
        }
        val generation = ++morphGeneration
        val byUri = games.associateBy { it.uri }
        val flights = ArrayList<ShelfCatalogMorphFlight>(sources.size)
        sources.entries.sortedBy { (_, rect) -> distanceToCenter(rect) }.forEachIndexed { rank, (uri, rect) ->
            val game = byUri[uri] ?: return@forEachIndexed
            flights += ShelfCatalogMorphFlight(
                generation = generation,
                game = game,
                spineIndex = indexByUri[uri] ?: spineIndexOf(uri),
                fromRect = rect,
                fromMode = fromMode,
                toMode = target,
                isCurrent = uri == currentFocusUri,
                delay = (rank * MORPH_STAGGER).coerceAtMost(MORPH_MAX_DELAY),
            )
        }
        // 同一帧里：新陈列上场、旧书从原位换成变形卡片（卡片此刻与旧书完全重合），真正的书槽藏起来
        activeMorph = flights
        hiddenSlotUris = flights.mapTo(HashSet()) { it.game.uri }
        layoutAnchorUri = anchor.key
        layoutAnchorY = anchor.value.rect.center.y - viewport.top
        morphing = true
        changeLayout(target)
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
                        generation = generation,
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

    private fun changeLayout(target: ShelfCatalogLayoutMode) {
        layoutRevision++
        layoutMode = target
    }

    /**
     * 入场还没放完（书还在空中飞、或交接淡出还没完）就被别的操作打断：不再等动画，立刻收敛到入场完成的样子
     * （飞行编队撤掉、书柜各层显现、书架退到位），之后的操作都在稳定的书柜上进行。
     * 切换陈列和开始滑动书墙都走这里；已经入场完、或正在退场时什么也不做。
     */
    private fun settleEntryNow() {
        if (closing || hasEntered) return
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

    /**
     * 入场途中用户开始拖动书墙。飞行编队的落点是进场那一刻量好的屏幕位置，书墙一滚动，真正的书就不在那里了：
     * 编队落在旧位置、被藏起的真书却已滚走，封面会悬在错位的地方、与滚动中的书叠在一起。
     * 所以不等动画，直接收敛入场（与入场未完成时切换陈列同一做法）。
     */
    internal fun settleEntryForScroll() = settleEntryNow()

    /** 某一本变形卡片此刻的位置、大小与自己的进度 t（相对覆盖层左上角；终点还没摆好时停在起点）。 */
    internal fun morphPoseOf(flight: ShelfCatalogMorphFlight): ShelfCatalogMorphPose {
        // 接力切换时 morphProgress 在新一轮开头同步归零，但组合要下一帧才跟上：这一帧里上一轮的卡片还在画，
        // 此时不能按「进度 0」回到上一轮的起点（封面卡片闪一下），它们仍是放完时的样子
        val progress = shelfCatalogMorphProgressSeen(flight.generation, morphGeneration, morphProgress.value)
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
        // 等书格就位的这段里已经被收敛过（用户一进来就拖动书墙、或切换陈列）：不再起飞行编队
        if (closing || hasEntered) return

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
        resetSpinePeekImmediately()
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
                val handoffStart = shelfCatalogHandoffStart(troupe, stage.stageDensity)

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
                            returnFlight.animateTo(1f, CATALOG_PICK_SPRING) { stage.lentAlpha = shelfCatalogHandoffAlpha(value, handoffStart) }
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
        resetSpinePeekImmediately()
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
        val handoffStart = shelfCatalogHandoffStart(troupe, stage.stageDensity)

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
                                    stage.lentAlpha = shelfCatalogHandoffAlpha(handoff, handoffStart)
                                }
                            } else {
                                returnFlight.animateTo(1f, CATALOG_EXIT_SPRING) { stage.lentAlpha = shelfCatalogHandoffAlpha(value, handoffStart) }
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
        resetSpinePeekImmediately()
        closing = true
        transitionJob?.cancel()
        deliver(immediate = true)
    }

    internal fun releaseIfLeaving(event: Lifecycle.Event) {
        if ((event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) && closing) {
            deliver(immediate = true)
        }
    }

    /**
     * 退场收尾：清掉舞台上的借出状态，并把覆盖层交还给宿主拿掉。
     *
     * 默认等书架上的手指抬起再交付（见 [ShelfCatalogStage.dismissWhenShelfReleased]）；应用锁、进后台这类强制关闭
     * 传 [immediate]，不等手指（连已经挂起的收尾也立刻交付）。
     */
    private fun deliver(immediate: Boolean = false) {
        if (delivered) {
            if (immediate) stage.flushDeferredDismiss()
            return
        }
        delivered = true
        exitFocusToken?.let(stage::clearPendingFocus)
        stage.exitInterrupt = null
        stage.release()
        if (immediate) {
            dismissAction?.invoke()
        } else {
            stage.dismissWhenShelfReleased { dismissAction?.invoke() }
        }
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
internal fun shelfCatalogHandoffAlpha(flight: Float, start: Float = CATALOG_HANDOFF_START): Float {
    val x = ((flight - start) / (1f - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * 书架上真正的书开始浮现的飞行进度。固定从 92% 起浮现时，飞得远 / 形变大的书（书脊变封面，宽度要长几百 px）此刻离落点还差一截，
 * 浮现的真书与还在飞的那张封面是两张错开的图，叠出重影，要等弹簧收完才重合。改成按「飞行还剩的像素」定：
 * 剩余距离降到 [residualPx] 以内才开始浮现，最早仍是 92%（近距离的书不变），最晚留 [HANDOFF_MIN_REMAINING] 的行程。
 */
internal fun shelfCatalogHandoffStartFor(travelPx: Float, residualPx: Float): Float {
    if (!(travelPx > 0f)) return CATALOG_HANDOFF_START
    val remaining = (residualPx / travelPx).coerceIn(HANDOFF_MIN_REMAINING, 1f - CATALOG_HANDOFF_START)
    return 1f - remaining
}

/** 这一队飞行里最「远」的一本（位置、高、封面宽的最大变化量，含错峰延迟拉长的部分）决定交接的起点。 */
internal fun shelfCatalogHandoffStart(troupe: List<ShelfCatalogBookFlight>, density: Float): Float {
    var worst = 0f
    for (flight in troupe) {
        if (flight.fadeInPlace) continue
        val from = shelfCatalogBookPose(0f, flight)
        val to = shelfCatalogBookPose(1f, flight)
        // 位置：端点间的直线距离，再加上飞行弧线在终点附近的残余——弧线是 arcLift * sin(π t)，t 接近 1 时约为 arcLift * π * (1 - t)，
        // 与直线剩余同比例收敛，端点很近但弧线较高的飞行（普通关闭的 arcLiftPx）不能当成短行程
        val travel = maxOf(
            hypot(
                from.spineCenterX - to.spineCenterX,
                abs(from.spineCenterY - to.spineCenterY) + PI.toFloat() * flight.arcLiftPx,
            ),
            abs(from.spineHeight - to.spineHeight),
            abs(from.coverWidth - to.coverWidth),
        )
        val span = (1f - flight.delayFraction.coerceIn(0f, 0.4f)).coerceAtLeast(0.1f)
        worst = maxOf(worst, travel / span)
    }
    return shelfCatalogHandoffStartFor(worst, HANDOFF_RESIDUAL_DP * density)
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
/** 真书浮现时飞行的书离落点最多还差多少（dp），再多就是两张错开的图叠在一起。 */
private const val HANDOFF_RESIDUAL_DP = 1.5f
private const val HANDOFF_MIN_REMAINING = 0.003f
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

/** 与 `GameShelfMotion.SIZE_VARIANCE` 同步，保持书架主视图与藏书阁同一本书的高矮倾向一致。 */
private const val MAIN_SHELF_SIZE_VARIANCE = 0.045f
private const val SPINE_HEIGHT_VARIANCE = 0.12f
private const val COVER_HEIGHT_VARIANCE = 0.055f
internal const val SPINE_MIN_THICKNESS_FACTOR = 0.88f
internal const val SPINE_MAX_THICKNESS_FACTOR = 1.12f
internal const val SPINE_WIDTH_TO_HEIGHT_RATIO = 0.177f
internal const val SPINE_MIN_GAP_DP = 1.0f
internal const val SPINE_MAX_GAP_DP = 4.0f
internal val VERTICAL_SPINE_NOMINAL_GAP = 2.5.dp
internal val CABINET_STILE_WIDTH = 3.dp
internal val CABINET_INTERIOR_INSET = 10.dp
internal val SPINE_MIN_HEIGHT = 156.dp
internal val SPINE_MAX_HEIGHT = 192.dp
private const val SPINE_HEIGHT_TO_CABINET_RATIO = 0.48f
private const val SPINE_RECESS_SHARE = 0.20f
private const val SPINE_RECESS_MIN_LIFT_DP = 1.2f
private const val SPINE_RECESS_MAX_LIFT_DP = 2.4f
private const val SPINE_RECESS_MIN_SHADE = 0.06f
private const val SPINE_RECESS_MAX_SHADE = 0.10f
private const val SPINE_GAP_SALT = 0x51a7b319
private const val SPINE_RECESS_SALT = 0x2c9e4d7b
private const val FNV1A_OFFSET_BASIS = -2128831035
private const val FNV1A_PRIME = 16777619
private const val MURMUR_C1 = -2048144789
private const val MURMUR_C2 = -1028477387

/**
 * [disabled] 时把这一层的布局大小报成 0（内容照常按原约束测量、摆在原位、照常绘制）。
 *
 * 只改布局尺寸，**不能**让触摸穿透：命中测试只按「带触摸输入的节点」自己的范围判断，零尺寸的父节点拦不住（也放不过）子孙节点，
 * 里面的可点 / 可滚动控件照样被命中。要让下层页面收到触摸，用 `MainOverlayShareTouches`，并让这一层里的控件自己停止响应。
 */
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
    revision: Int,
    /** 变形途中正在退场的旧形态；不在变形中为 null（直接换：只留新的一面）。 */
    leavingMode: ShelfCatalogLayoutMode?,
    progress: () -> Float,
    blockTouches: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (ShelfCatalogLayoutMode) -> Unit,
) {
    val leaving = leavingMode?.takeIf { it != mode }
    Box(modifier) {
        listOfNotNull(leaving, mode).forEach { wallMode ->
            val isLeaving = wallMode == leaving
            key(if (isLeaving) revision - 1 else revision) {
                Box(
                    Modifier
                        // noHitWhile 必须在 fillMaxSize 之前：它把布局大小报成 0，放在铺满约束的里面，
                        // 约束会把 0 抬回满尺寸、内容反被居中偏移半个屏幕（旧书柜整个错位到右下角）
                        .noHitWhile(isLeaving)
                        .fillMaxSize()
                        .graphicsLayer {
                            // 搁板 / 立柱 / 铭牌是书柜的「底」：与新书柜交叠着慢慢换（交叠处两边都在，底色不断档），
                            // 不是旧的先整块没了、新的再整块冒出来——中间露出一整片光秃秃的页面底色，看起来像背景闪了一下
                            alpha = when {
                                isLeaving -> 1f - smoothStep((progress() - MODE_BODY_OUT_START) / MODE_BODY_OUT_SPAN)
                                leaving != null -> smoothStep((progress() - MODE_BODY_FADE_IN_START) / MODE_BODY_FADE_IN_SPAN)
                                else -> 1f
                            }
                        }
                        .then(
                            if (!isLeaving && blockTouches) {
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
                ) {
                    CompositionLocalProvider(LocalShelfCatalogLeavingWall provides isLeaving) { content(wallMode) }
                }
            }
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

/**
 * 旧组合读到的变形进度：[seenKey] 是这份组合（或这张卡片）所属的那一轮变形，[currentKey] 是当前最新一轮。
 * 两者不同说明新一轮已经开始、进度已被归零，而这份组合还没被重组掉——它仍按上一轮「已放完」(1) 画，而不是跳回上一轮的起点。
 */
internal fun shelfCatalogMorphProgressSeen(seenKey: Int, currentKey: Int, progress: Float): Float =
    if (seenKey != currentKey) 1f else progress

/** 书脊 ↔ 封面变形里的一本书：旧陈列里的屏幕矩形、新陈列里的屏幕矩形（摆好后补上；新陈列里看不见的 [vanish] 淡出）与错峰延迟。 */
@Stable
internal class ShelfCatalogMorphFlight(
    /** 所属的那一轮变形（[ShelfCatalogMotion] 的 morphGeneration）：新一轮开始后，上一轮的卡片只会在「还没被重组掉」的那一帧里出现。 */
    val generation: Int,
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
 * 一种陈列的网格几何（px）：每行的列数、相邻两格的水平基准间距（格宽 + 缝）与相邻两行的垂直间距（一层的高度），
 * 以及「书脊立排」逐层宽度归一化所需参数，供离屏外推与实际书格位置严丝合缝对齐。
 * [grouped] 为真（按引擎分架，章节横梁让行高不规则）时不能按规则外推。
 */
internal class ShelfCatalogGridMetrics(
    val columns: Int,
    val pitchX: Float,
    val pitchY: Float,
    val grouped: Boolean,
    val density: Float = 1f,
    val baseCellWidthPx: Float = 0f,
    val innerWidthPx: Int = 0,
    val maxBookHeightPx: Float = 0f,
    val focusLiftPx: Float = 0f,
    val trailingLeanReservePx: Float = 0f,
)

/**
 * 切换陈列后首屏的滚动位置：让锚点书的中心落在视口里 [anchorY]（距视口顶，px）那么高。
 * 返回（锚点所在的搁板行，带符号的滚动偏移），不是预估的首个可见行。负偏移由 LazyColumn 按前面各行
 * 的实际高度向前测量（含随字体大小变化的章节横梁）；超出列表两端时由列表收回到有效范围。
 */
internal fun shelfCatalogAlignedScroll(
    rows: List<ShelfCatalogRowItem>,
    anchorUri: String?,
    anchorY: Float?,
    cellCenterPx: Float,
    topPaddingPx: Int,
): Pair<Int, Int>? {
    if (anchorUri == null || anchorY == null || !anchorY.isFinite()) return null
    val rowIndex = findShelfCatalogAnchorRow(rows, anchorUri)
    if (rowIndex < 0) return null
    val offset = (topPaddingPx + cellCenterPx - anchorY).roundToInt()
    return rowIndex to if (rowIndex == 0) offset.coerceAtLeast(0) else offset
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

// ---------------------------------------------------------------------------------------------
// 书脊模式「翻看」：长按或横向拖动翻开指下书盒，右侧邻书顺滑让位、整排左移不越界
// ---------------------------------------------------------------------------------------------

/** 藏书阁书脊翻看最终侧转角度（与主书架 hero 侧转角 [HERO_YAW_DEGREES] 一致）。 */
internal val SHELF_CATALOG_PEEK_YAW_DEGREES: Float = HERO_YAW_DEGREES

private const val PEEK_HYSTERESIS_DP = 4f
private const val PEEK_MIN_LATERAL_RATIO = 0.5f
private val PEEK_LONG_PRESS_EDGE_PAD = 4.dp
private const val PEEK_FRONT_FADE_START_DEG = 89.8f
private const val PEEK_FRONT_FADE_SPAN_DEG = 3.5f

internal enum class ShelfCatalogPeekDirection { UNDECIDED, PEEK, SCROLL }

internal class ShelfCatalogPeekGestureTracker(private val slop: Float) {
    private var travel = Offset.Zero
    var canLongPress = true
        private set

    fun cancelLongPress() {
        canLongPress = false
    }

    fun move(delta: Offset, consumed: Boolean): ShelfCatalogPeekDirection {
        travel += delta
        if (consumed) cancelLongPress()
        if (travel.getDistance() <= slop) return ShelfCatalogPeekDirection.UNDECIDED
        cancelLongPress()
        val horizontal = abs(travel.x)
        val vertical = abs(travel.y)
        return when {
            horizontal >= slop * 0.75f && horizontal >= vertical * PEEK_MIN_LATERAL_RATIO -> ShelfCatalogPeekDirection.PEEK
            vertical >= slop * 2f && horizontal < vertical * PEEK_MIN_LATERAL_RATIO -> ShelfCatalogPeekDirection.SCROLL
            else -> ShelfCatalogPeekDirection.UNDECIDED
        }
    }
}

/** 按书高复用主书架封面宽高比（[SHELF_COVER_ASPECT_RATIO]）换算翻开后的封面宽度。 */
internal fun shelfCatalogPeekCoverWidthPx(bookHeightPx: Float): Float =
    (bookHeightPx * SHELF_COVER_ASPECT_RATIO).coerceAtLeast(1f)

/** 翻看开合进度 `openProgress`（0 = 书脊朝前，1 = 翻开至 [SHELF_CATALOG_PEEK_YAW_DEGREES]）对应的盒面转角（度）。 */
internal fun shelfCatalogPeekYawDegrees(openProgress: Float): Float =
    (90f + (SHELF_CATALOG_PEEK_YAW_DEGREES - 90f) * openProgress.coerceAtLeast(0f)).coerceIn(0f, 90f)

/** 翻看时单本书在当前进度下的总水平投影宽度（书脊投影 + 封面投影）。 */
internal fun shelfCatalogPeekProjectedWidthPx(
    spineWidthPx: Float,
    coverWidthPx: Float,
    openProgress: Float,
): Float {
    val safeSpine = spineWidthPx.coerceAtLeast(0f)
    if (openProgress <= 0f) return safeSpine
    val yawRad = Math.toRadians(shelfCatalogPeekYawDegrees(openProgress).toDouble()).toFloat()
    return (safeSpine * sin(yawRad) + coverWidthPx.coerceAtLeast(0f) * cos(yawRad)).coerceAtLeast(safeSpine)
}

/** 翻看时单本书相对静止书脊宽度多占出的水平空间（供右侧邻书右移）。 */
internal fun shelfCatalogPeekExtraWidthPx(
    spineWidthPx: Float,
    coverWidthPx: Float,
    openProgress: Float,
): Float = (shelfCatalogPeekProjectedWidthPx(spineWidthPx, coverWidthPx, openProgress) - spineWidthPx).coerceAtLeast(0f)

/** 翻看时封面正面随转角渐退的背光压暗透明度。 */
internal fun shelfCatalogPeekFrontShade(openProgress: Float): Float {
    val p = openProgress.coerceIn(0f, 1f)
    if (p <= 0f) return 0f
    return FRONT_SHADE * (shelfCatalogPeekYawDegrees(p) / 90f).coerceIn(0f, 1f)
}

/** 翻看时书脊随侧转away渐增的背光压暗透明度。 */
internal fun shelfCatalogPeekSpineShade(openProgress: Float): Float {
    val p = openProgress.coerceIn(0f, 1f)
    if (p <= 0f) return 0f
    return SPINE_SHADE * (1f - (shelfCatalogPeekYawDegrees(p) / 90f).coerceIn(0f, 1f))
}

internal class ShelfCatalogPeekLayout(
    val bookShiftsPx: FloatArray,
    val rowShiftPx: Float,
)

/**
 * 计算一层书脊翻看时各本书的横向位移与整排左移量：
 * - 右侧邻书整体右移左侧展开书多占出的「投影宽度 − 书脊宽度」；
 * - 整排整体左移，保证展开后的封面右缘不超过柜内右边界 [innerWidthPx]。
 */
internal fun computeShelfCatalogPeekLayout(
    widthsPx: List<Int>,
    gapsPx: List<Int>,
    bookHeightsPx: FloatArray,
    openProgresses: FloatArray,
    innerWidthPx: Int,
): ShelfCatalogPeekLayout {
    val n = widthsPx.size
    if (n == 0) return ShelfCatalogPeekLayout(FloatArray(0), 0f)
    val staticRight = FloatArray(n)
    val fullExtras = FloatArray(n)
    val extras = FloatArray(n)
    val cumExtraBefore = FloatArray(n)
    var cursor = 0f
    var runningExtra = 0f
    for (i in 0 until n) {
        val w = widthsPx[i].toFloat().coerceAtLeast(0f)
        staticRight[i] = cursor + w
        val h = bookHeightsPx.getOrElse(i) { 0f }
        val coverW = shelfCatalogPeekCoverWidthPx(h)
        val full = shelfCatalogPeekExtraWidthPx(w, coverW, 1f)
        val p = openProgresses.getOrElse(i) { 0f }
        val extra = if (p > 0f) shelfCatalogPeekExtraWidthPx(w, coverW, p) else 0f
        fullExtras[i] = full
        extras[i] = extra
        cumExtraBefore[i] = runningExtra
        runningExtra += extra
        cursor += w + if (i < gapsPx.size) gapsPx[i].toFloat().coerceAtLeast(0f) else 0f
    }
    var fSum = 0f
    var weightedOverflow = 0f
    val budget = innerWidthPx.toFloat().coerceAtLeast(0f)
    for (i in 0 until n) {
        val full = fullExtras[i]
        val extra = extras[i]
        if (full <= 0f || extra <= 0f) continue
        val f = (extra / full).coerceIn(0f, 1f)
        val needed = (staticRight[i] + cumExtraBefore[i] + full - budget).coerceAtLeast(0f)
        fSum += f
        weightedOverflow += f * needed
    }
    val rowShiftPx = if (fSum > 0f) -(weightedOverflow / maxOf(1f, fSum)) else 0f
    val shifts = FloatArray(n) { i -> rowShiftPx + cumExtraBefore[i] }
    return ShelfCatalogPeekLayout(bookShiftsPx = shifts, rowShiftPx = rowShiftPx)
}

/**
 * 根据「静止排布」中的书格位置把层内横坐标 [fingerX]（相对首本左缘）映射为翻看的书序号；
 * 已经选中 [currentIndex] 时在相邻书格交界处应用 [hysteresisPx] 滞回，滑出左右两端时停在端点那本。
 */
internal fun resolveShelfCatalogPeekBookIndex(
    fingerX: Float,
    widthsPx: List<Int>,
    gapsPx: List<Int>,
    currentIndex: Int,
    hysteresisPx: Float,
): Int {
    val n = widthsPx.size
    if (n <= 0) return -1
    if (n == 1) return 0
    val lefts = FloatArray(n)
    val rights = FloatArray(n)
    var x = 0f
    for (i in 0 until n) {
        lefts[i] = x
        val w = widthsPx[i].toFloat().coerceAtLeast(0f)
        rights[i] = x + w
        x = rights[i] + if (i < gapsPx.size) gapsPx[i].toFloat().coerceAtLeast(0f) else 0f
    }
    fun boundary(i: Int): Float = 0.5f * (rights[i] + lefts[i + 1])
    fun margin(i: Int): Float {
        val minW = minOf(widthsPx[i].toFloat(), widthsPx[i + 1].toFloat()).coerceAtLeast(0f)
        return hysteresisPx.coerceIn(0f, minW * 0.35f)
    }
    if (currentIndex !in 0 until n) {
        for (i in 0 until n - 1) {
            if (fingerX <= boundary(i)) return i
        }
        return n - 1
    }
    if (fingerX <= lefts[0]) return 0
    if (fingerX >= rights[n - 1]) return n - 1
    var idx = currentIndex
    while (idx < n - 1 && fingerX > boundary(idx) + margin(idx)) {
        idx++
    }
    while (idx > 0 && fingerX < boundary(idx - 1) - margin(idx - 1)) {
        idx--
    }
    return idx
}

/** 长按命中判定：只有按在书排范围（不含未满层右侧大块留白）内才触发长按翻看。 */
internal fun hitShelfCatalogSpineIndex(
    fingerX: Float,
    fingerY: Float,
    widthsPx: List<Int>,
    gapsPx: List<Int>,
    bookHeightsPx: FloatArray,
    bookBottomOffsetsPx: IntArray,
    tierBookBottomPx: Float,
    edgePadPx: Float = 0f,
): Int {
    val n = widthsPx.size
    if (n <= 0 || fingerY < 0f || fingerY > tierBookBottomPx) return -1
    var totalWidth = 0f
    for (i in 0 until n) {
        totalWidth += widthsPx[i].toFloat().coerceAtLeast(0f)
        if (i < n - 1 && i < gapsPx.size) totalWidth += gapsPx[i].toFloat().coerceAtLeast(0f)
    }
    if (fingerX < -edgePadPx || fingerX > totalWidth + edgePadPx) return -1
    val index = resolveShelfCatalogPeekBookIndex(
        fingerX = fingerX,
        widthsPx = widthsPx,
        gapsPx = gapsPx,
        currentIndex = -1,
        hysteresisPx = 0f,
    )
    val bottom = tierBookBottomPx + bookBottomOffsetsPx[index]
    val top = bottom - bookHeightsPx[index].roundToInt()
    return if (fingerY in top..bottom) index else -1
}

@Stable
internal class ShelfCatalogTierPeekState(
    private val scope: CoroutineScope,
    private val motion: ShelfCatalogMotion,
    private val widthsPx: List<Int>,
    private val gapsPx: List<Int>,
    private val bookHeightsPx: FloatArray,
    private val bookBottomOffsetsPx: IntArray,
    private val innerWidthPx: Int,
    private val hysteresisPx: Float,
) {
    val bookCount: Int get() = widthsPx.size

    private val openAnims = mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>()
    private val animJobs = HashMap<Int, Job>()

    var activeIndex by mutableIntStateOf(-1)
        private set

    var isPeeking by mutableStateOf(false)
        private set

    private var sessionId by mutableIntStateOf(0)

    private val progressScratch = FloatArray(widthsPx.size)
    private val lastProgresses = FloatArray(widthsPx.size) { Float.NaN }
    private var cachedLayout: ShelfCatalogPeekLayout? = null

    fun isSessionActive(session: Int): Boolean = isPeeking && session == sessionId

    fun isBookMounted(col: Int): Boolean = col in openAnims

    fun progressOf(col: Int): Float = openAnims[col]?.value ?: 0f

    fun bookShiftPx(col: Int): Float {
        if (openAnims.isEmpty() || col !in widthsPx.indices) return 0f
        return currentLayout().bookShiftsPx[col]
    }

    fun extraWidthPx(col: Int): Float {
        val progress = progressOf(col)
        if (progress <= 0f) return 0f
        return shelfCatalogPeekExtraWidthPx(widthsPx[col].toFloat(), shelfCatalogPeekCoverWidthPx(bookHeightsPx[col]), progress)
    }

    private fun currentLayout(): ShelfCatalogPeekLayout {
        val n = widthsPx.size
        var dirty = cachedLayout == null
        for (i in 0 until n) {
            val v = openAnims[i]?.value ?: 0f
            progressScratch[i] = v
            if (v != lastProgresses[i]) dirty = true
        }
        if (!dirty) return cachedLayout!!
        for (i in 0 until n) lastProgresses[i] = progressScratch[i]
        return computeShelfCatalogPeekLayout(
            widthsPx = widthsPx,
            gapsPx = gapsPx,
            bookHeightsPx = bookHeightsPx,
            openProgresses = progressScratch,
            innerWidthPx = innerWidthPx,
        ).also { cachedLayout = it }
    }

    fun resolveInitialIndex(fingerX: Float): Int = resolveShelfCatalogPeekBookIndex(
        fingerX = fingerX,
        widthsPx = widthsPx,
        gapsPx = gapsPx,
        currentIndex = -1,
        hysteresisPx = hysteresisPx,
    )

    fun hitSpineIndex(fingerX: Float, fingerY: Float, tierBookBottomPx: Float, edgePadPx: Float): Int =
        hitShelfCatalogSpineIndex(
            fingerX = fingerX,
            fingerY = fingerY,
            widthsPx = widthsPx,
            gapsPx = gapsPx,
            bookHeightsPx = bookHeightsPx,
            bookBottomOffsetsPx = bookBottomOffsetsPx,
            tierBookBottomPx = tierBookBottomPx,
            edgePadPx = edgePadPx,
        )

    fun beginPeek(index: Int): Int {
        if (index !in widthsPx.indices) return -1
        sessionId++
        isPeeking = true
        motion.notifyTierPeekActive(this, true)
        switchTarget(index, haptic = false)
        return sessionId
    }

    fun updateFingerX(fingerX: Float, session: Int) {
        if (!isSessionActive(session)) return
        val next = resolveShelfCatalogPeekBookIndex(
            fingerX = fingerX,
            widthsPx = widthsPx,
            gapsPx = gapsPx,
            currentIndex = activeIndex,
            hysteresisPx = hysteresisPx,
        )
        if (next in widthsPx.indices && next != activeIndex) {
            switchTarget(next, haptic = true)
        }
    }

    fun endPeek(session: Int) {
        if (session != sessionId || !isPeeking) return
        isPeeking = false
        motion.notifyTierPeekActive(this, false)
        activeIndex = -1
        val instant = !motion.enhanced || !ValueAnimator.areAnimatorsEnabled()
        for (idx in openAnims.keys.toList()) {
            animateBookTo(idx, 0f, instant)
        }
    }

    fun resetImmediately() {
        sessionId++
        isPeeking = false
        activeIndex = -1
        motion.notifyTierPeekActive(this, false)
        for (job in animJobs.values) job.cancel()
        animJobs.clear()
        openAnims.clear()
        cachedLayout = null
        for (i in lastProgresses.indices) lastProgresses[i] = Float.NaN
    }

    private fun switchTarget(target: Int, haptic: Boolean) {
        val prev = activeIndex
        if (target == prev) return
        activeIndex = target
        if (haptic) MotionHaptics.perform(MotionHaptics.Kind.Tick)
        val instant = !motion.enhanced || !ValueAnimator.areAnimatorsEnabled()
        if (prev in widthsPx.indices) {
            animateBookTo(prev, 0f, instant)
        }
        for (existing in openAnims.keys.toList()) {
            if (existing != target && existing != prev) {
                animateBookTo(existing, 0f, instant)
            }
        }
        if (target in widthsPx.indices) {
            animateBookTo(target, 1f, instant)
        }
    }

    private fun animateBookTo(index: Int, target: Float, instant: Boolean) {
        animJobs.remove(index)?.cancel()
        val existing = openAnims[index]
        if (target == 0f && existing == null) return
        val anim = existing ?: Animatable(0f).also { openAnims[index] = it }
        if (instant) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                anim.snapTo(target)
                if (target == 0f && activeIndex != index) {
                    openAnims.remove(index)
                }
            }
            return
        }
        val spec = if (target > 0.5f) {
            MotionTokens.content(0.0005f)
        } else {
            MotionTokens.contentExit(0.0005f)
        }
        animJobs[index] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            anim.animateTo(target, spec)
            animJobs.remove(index)
            if (target == 0f && activeIndex != index) {
                openAnims.remove(index)
            }
        }
    }
}

@Composable
internal fun rememberShelfCatalogTierPeekState(
    motion: ShelfCatalogMotion,
    widthsPx: List<Int>,
    gapsPx: List<Int>,
    bookHeightsPx: FloatArray,
    bookBottomOffsetsPx: IntArray,
    innerWidthPx: Int,
): ShelfCatalogTierPeekState {
    val scope = rememberCoroutineScope()
    val hysteresisPx = with(LocalDensity.current) { PEEK_HYSTERESIS_DP.dp.toPx() }
    val state = remember(motion, widthsPx, gapsPx, bookHeightsPx, bookBottomOffsetsPx, innerWidthPx, hysteresisPx) {
        ShelfCatalogTierPeekState(
            scope = scope,
            motion = motion,
            widthsPx = widthsPx,
            gapsPx = gapsPx,
            bookHeightsPx = bookHeightsPx,
            bookBottomOffsetsPx = bookBottomOffsetsPx,
            innerWidthPx = innerWidthPx,
            hysteresisPx = hysteresisPx,
        )
    }
    DisposableEffect(motion, state) {
        motion.registerTierPeek(state)
        onDispose {
            state.resetImmediately()
            motion.unregisterTierPeek(state)
        }
    }
    return state
}

/**
 * 藏书阁竖排书脊层的翻看手势：
 * - 长按某本书脊成立后立即翻开并消费后续所有触摸（不松手竖向滑动也不再滚动列表）；
 * - 整层横拖或有明显横移的斜拖均可翻看；近竖直拖动仍交由 LazyColumn 滚动；
 * - 松手或取消时立刻全部合回且不触发点选。
 */
internal fun Modifier.shelfCatalogVerticalTierPeekGesture(
    peekState: ShelfCatalogTierPeekState,
    canStart: () -> Boolean,
): Modifier = pointerInput(peekState) {
    val edgePadPx = PEEK_LONG_PRESS_EDGE_PAD.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        MotionIntent.noteTouch()
        if (!canStart()) return@awaitEachGesture
        val tracker = ShelfCatalogPeekGestureTracker(viewConfiguration.touchSlop)
        val pressedIndex = peekState.hitSpineIndex(down.position.x, down.position.y, size.height.toFloat(), edgePadPx)
        if (pressedIndex < 0 || down.isConsumed) tracker.cancelLongPress()
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        var remaining = longPressTimeout
        var lastPos = down.position
        var lockedByDrag = false
        while (true) {
            val event = if (tracker.canLongPress) {
                withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) } ?: break
            } else {
                awaitPointerEvent(PointerEventPass.Initial)
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || event.changes.count { it.pressed } > 1) return@awaitEachGesture
            lastPos = change.position
            when (tracker.move(change.position - change.previousPosition, change.isConsumed)) {
                ShelfCatalogPeekDirection.PEEK -> {
                    change.consume()
                    lockedByDrag = true
                    break
                }
                ShelfCatalogPeekDirection.SCROLL -> return@awaitEachGesture
                ShelfCatalogPeekDirection.UNDECIDED -> Unit
            }
            if (tracker.canLongPress) {
                val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                if (finalEvent.changes.firstOrNull { it.id == down.id }?.isConsumed == true) tracker.cancelLongPress()
                remaining = longPressTimeout - (change.uptimeMillis - down.uptimeMillis)
            }
        }

        if (!canStart()) return@awaitEachGesture
        val initialIndex = if (lockedByDrag) {
            peekState.resolveInitialIndex(lastPos.x)
        } else {
            pressedIndex
        }
        if (initialIndex < 0) return@awaitEachGesture

        MotionIntent.noteTouch()
        if (!lockedByDrag) {
            MotionHaptics.perform(MotionHaptics.Kind.LongPress)
        }
        val session = peekState.beginPeek(initialIndex)
        if (session < 0) return@awaitEachGesture

        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                if (peekState.isSessionActive(session)) {
                    MotionIntent.noteTouch()
                    peekState.updateFingerX(change.position.x, session)
                }
            }
        } finally {
            peekState.endPeek(session)
        }
    }
}

/** 翻看中单本立脊容器的水平让位、消除斜倚与消除内收抬升（在放置锚点内侧生效，绝不污染 [shelfCatalogSlotPlaced]）。 */
internal fun Modifier.shelfCatalogPeekBookTransform(
    peekState: ShelfCatalogTierPeekState,
    colIndex: Int,
    leanDegrees: Float,
    leanShiftPx: Float,
    recessLiftPx: Float,
): Modifier = graphicsLayer {
    val p = peekState.progressOf(colIndex)
    val pClamped = p.coerceIn(0f, 1f)
    val shiftX = peekState.bookShiftPx(colIndex) - leanShiftPx * pClamped
    if (shiftX != 0f) translationX = shiftX
    if (recessLiftPx != 0f && pClamped > 0f) translationY = recessLiftPx * pClamped
    val currentLean = leanDegrees * (1f - pClamped)
    if (abs(currentLean) >= 0.05f) {
        transformOrigin = TransformOrigin(0f, 1f)
        rotationZ = currentLean
    }
}

/** 翻看中书脊面绕左缘转开时的水平压窄（`sin(yaw)` 仿射投影，保持文字清晰）。 */
internal fun Modifier.shelfCatalogPeekSpineTurn(
    peekState: ShelfCatalogTierPeekState,
    colIndex: Int,
): Modifier = graphicsLayer {
    val p = peekState.progressOf(colIndex)
    if (p <= 0f) return@graphicsLayer
    val sinTurn = sin(Math.toRadians(shelfCatalogPeekYawDegrees(p).toDouble())).toFloat()
    transformOrigin = TransformOrigin(0f, 0.5f)
    scaleX = sinTurn
}

/** 翻看中封面正面绕书脊右缘转出（与书脊同源拼盒 + [SHELF_FACE_CAMERA] 透视）。 */
internal fun Modifier.shelfCatalogPeekFrontTurn(
    peekState: ShelfCatalogTierPeekState,
    colIndex: Int,
    spineWidthPx: Float,
    frontShape: Shape,
): Modifier = graphicsLayer {
    val p = peekState.progressOf(colIndex)
    if (p <= 0.001f) {
        alpha = 0f
        return@graphicsLayer
    }
    val yawDeg = shelfCatalogPeekYawDegrees(p)
    val frontAlpha = ((PEEK_FRONT_FADE_START_DEG - yawDeg) / PEEK_FRONT_FADE_SPAN_DEG).coerceIn(0f, 1f)
    if (frontAlpha <= 0.005f) {
        alpha = 0f
        return@graphicsLayer
    }
    val sinTurn = sin(Math.toRadians(yawDeg.toDouble())).toFloat()
    alpha = frontAlpha
    cameraDistance = SHELF_FACE_CAMERA * density
    shape = frontShape
    clip = true
    transformOrigin = TransformOrigin(0f, 0.5f)
    translationX = spineWidthPx * sinTurn
    rotationY = yawDeg
}
