package com.tyranor.next.ui.game

import android.animation.ValueAnimator
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.animateFloatAsState
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.AbsoluteCutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.MotionIntent
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSprings
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.pagePosition
import com.tyranor.next.ui.common.motion.pagerBouncySnap
import com.tyranor.next.ui.common.motion.rememberPagerSnapFling
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * 游戏页「书架」卡片风格的滚动几何。
 *
 * 每个游戏是一只立在搁板上的盒子：正面是封面，侧面是书脊。离书架中心的偏移 d（单位：本，正 = 在右侧）
 * 决定盒子的宽度与朝向：d = 0 时正面朝外（侧转一点露出左侧书脊），|d| ≥ 1 时只露出书脊和一条封面边。
 * 排布先定「盒子在屏幕上该有多宽」，再反解出朝向，所以相邻两盒在转动途中不会互相穿插。
 * 所有读数都在布局 / 图层 / 绘制块里读取翻页位置，滚动途中不触发重组；停稳时外观只由位置决定，
 * 「普通」动效档下几何本身照常生效（这是排列方式，不是额外动效），只少了甩动侧倾。
 */

/**
 * 一次测量下的书架几何（全部为 px）。
 *
 * @param coverWidth 正面（封面）宽度
 * @param coverHeight 盒子高度
 * @param spineDepth 书脊（盒子厚度）
 * @param gap 相邻书脊之间的缝
 * @param restGap 停稳时居中盒子与两侧书脊之间额外留出的呼吸位
 */
@Immutable
internal class ShelfGeometry(
    val coverWidth: Float,
    val coverHeight: Float,
    val spineDepth: Float,
    val gap: Float,
    restGap: Float,
    /** 书脊朝外时盒子转过的角度（弧度）。游戏很少时转得浅一些，两侧也露出封面。 */
    val maxTurn: Float = SHELF_SPINE_TURN,
    /** 正中那一盒并不完全正对，而是侧转这么多（弧度）露出左侧书脊——一眼看得出是有厚度的盒子。 */
    val heroYaw: Float = HERO_YAW,
) {
    /** 书脊朝外时一只盒子占的可见宽度：书脊 + 一条斜着的封面边。 */
    val spineVisible: Float = visibleWidth(maxTurn)

    /** 远处书脊的排布间距。 */
    val pitch: Float = spineVisible + gap

    /** 停稳时正中那一盒与第一本书脊的中心距：书脊缝 + 呼吸位，与转动过程无关。 */
    private val restDistance: Float = coverWidth / 2f + spineVisible / 2f + gap + restGap

    /** 正中那一盒、远处书脊在屏幕上的真实宽度（含抽出放大 / 两侧缩小，不含每本书的高矮差异——那只会更窄）。 */
    private val heroWidth: Float = visibleWidth(heroYaw) * shelfPoseScale(0f)
    private val spineWidth: Float = spineVisible * shelfPoseScale(1f)

    /**
     * 盒子外缘（远离正中的那条边）离书架中心的距离沿一条直线随书脊行一起移动，[reachSlack] 是正中那一盒停稳时
     * 外缘比这条直线收进去的量；离开正中时按 (1 - u)² 收掉，|d| = 1 处与书脊行同速衔接。
     */
    private val reachSlack: Float = restDistance + spineWidth / 2f - pitch - heroWidth / 2f

    /** 正面 + 书脊的投影宽度写成 R·cos(θ - β)，用来由宽度反解朝向。 */
    private val faceRadius: Float = kotlin.math.hypot(coverWidth, spineDepth)
    private val bevel: Float = kotlin.math.atan2(spineDepth, coverWidth)

    /**
     * 左侧的书转到正中时书脊要从右侧换到左侧：书脊朝外的角度够深时，先转到书脊正对屏幕（封面侧立成一条线）的那一刻换边，
     * 再把封面从书脊右侧转出来，与右侧的书对称、宽度一路只收不胀；转得浅（书少、两侧也露封面）时直接经过正对换边更短。
     */
    private val leftTurnsViaSpine: Boolean = maxTurn > HALF_PI - heroYaw

    /** 转过 [turn] 时盒子在屏幕上的可见宽度（正面投影 + 书脊投影）。 */
    fun visibleWidth(turn: Float): Float = coverWidth * cos(turn) + spineDepth * sin(turn)

    /**
     * 偏移 [offset]（本）的盒子在屏幕上应有的宽度（排布用，含姿态缩放）：从正中那一盒到书脊按 smoothstep 收窄，
     * 两端变化率都为零。左右两侧对称，且 d 与 d - 1 两盒的宽度之和恒等于「正中一盒 + 一本书脊」，
     * 所以翻页途中相邻两盒之间的缝不会被挤没。
     */
    fun boxWidth(offset: Float): Float =
        heroWidth - (heroWidth - spineWidth) * smoothstep(min(abs(offset), 1f))

    /** 偏移 [offset] 的盒子排布用的半宽：一本以内见 [boxWidth]，再远是书脊。 */
    fun halfWidth(offset: Float): Float =
        if (abs(offset) <= 1f) boxWidth(offset) / 2f else spineWidth / 2f

    /**
     * 盒子实际的朝向（弧度，带方向）：正 = 书脊在左。右侧的书由 [boxWidth] 反解，正中是 [heroYaw]（露出左侧书脊）、
     * 一本以外是 [maxTurn]；左侧的书换边见 [leftTurnsViaSpine]。换边都发生在两种画法看起来完全一样的那一刻
     * （书脊正对或封面正对），看不到跳变。
     */
    fun yaw(offset: Float): Float {
        val u = min(abs(offset), 1f)
        val right = rightYaw(u)
        if (offset >= 0f) return right
        if (leftTurnsViaSpine) {
            val progress = ((right - heroYaw) / (maxTurn - heroYaw).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
            val angle = heroYaw + (PI_F - maxTurn - heroYaw) * progress
            return if (angle <= HALF_PI) angle else angle - PI_F
        }
        return right * (1f - 2f * smoothstep(u / LEFT_FLIP_RANGE))
    }

    private fun rightYaw(u: Float): Float {
        val projected = boxWidth(u) / shelfPoseScale(u)
        val c = (projected / faceRadius.coerceAtLeast(1e-4f)).coerceIn(cos(maxTurn - bevel), cos(heroYaw - bevel))
        return (bevel + kotlin.math.acos(c)).coerceIn(heroYaw, maxTurn)
    }

    /** 偏移 [offset]（本）的盒子中心离书架中心的水平距离。 */
    fun centerX(offset: Float): Float {
        val distance = abs(offset)
        val x = if (distance <= 1f) {
            val inward = 1f - distance
            restDistance + spineWidth / 2f - pitch * inward - reachSlack * inward * inward - boxWidth(distance) / 2f
        } else {
            restDistance + (distance - 1f) * pitch
        }
        return if (offset < 0f) -x else x
    }

    /** 拖过一页的距离按正中到相邻盒的实际中心距（含呼吸位），且不小于远处书脊的间距。 */
    val dragPitch: Float get() = maxOf(pitch, centerX(1f))
}

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private val PI_F = kotlin.math.PI.toFloat()
private val HALF_PI = PI_F / 2f

/**
 * 整排书的水平平移（px）：一排书（含两端书立）放得进 [viewport] 时整排居中；放不下时，翻到两端就让第一本 / 最后一本
 * 贴着边，而不是把当前那盒硬放在正中、另一侧空出半屏。各情形在交界处取值相同，滚动时连续变化。
 *
 * @param endPad 第一本 / 最后一本外侧留给书立和边距的宽度
 */
internal fun ShelfGeometry.rowShift(
    position: Float,
    count: Int,
    viewport: Float,
    endPad: Float,
    anchor: Float = 0f,
    visibleStart: Float = -viewport / 2f,
    visibleEnd: Float = viewport / 2f,
): Float {
    if (count <= 0 || viewport <= 0f || visibleEnd <= visibleStart) return 0f
    val first = -position
    val last = count - 1 - position
    val left = centerX(first) - halfWidth(first) - endPad
    val right = centerX(last) + halfWidth(last) + endPad
    if (right - left <= visibleEnd - visibleStart) return (visibleStart + visibleEnd) / 2f - (left + right) / 2f
    // 放不下：当前那盒停在 [anchor]（斜轨上偏左的「近处」），两端不留空。
    // 可见范围按斜轨透视反算（近处的书被放大、往外挪，按投影前的屏幕宽度贴边会把第一本推出画面）
    return when {
        left + anchor > visibleStart -> visibleStart - left
        right + anchor < visibleEnd -> visibleEnd - right
        else -> anchor
    }
}

/**
 * 斜轨：整排书立在一条斜着伸进画面深处的搁板上——左前方近、右后方远。
 * 把排布用的水平位置 X（相对书架中心，px）按真实透视投影：越往右越远，盒子越小、脚下的搁板线越高、彼此越挤；
 * 翻过去的书往左前方滑出画面。投影只改绘制与布局位置，书本身的转角 / 抽出姿态照旧。
 *
 * @param viewport 书架宽度
 * @param eyeHeight 视线高出搁板线多少（搁板线朝地平线收拢的幅度）
 */
@Immutable
internal class ShelfRail(viewport: Float, private val eyeHeight: Float) {
    private val cos = cos(RAIL_YAW)
    private val sin = sin(RAIL_YAW)
    private val safeViewport = viewport.coerceAtLeast(0f)
    private val camera = safeViewport * RAIL_CAMERA

    /** 斜轨已放平时恒为 true，可跳过全量 `shelfRailPose` / `shelfRailFog` 渲染层而保持 100% 等价。 */
    val isFlat: Boolean = abs(sin) < 1e-6f

    /** 位置 [x] 处的透视缩放（近大远小），夹在合理范围内，免得近处的书大到出画。 */
    fun scale(x: Float): Float {
        if (camera <= 0f) return 1f
        val denom = (camera + x * sin).coerceAtLeast(1f)
        return (camera / denom).coerceIn(RAIL_MIN_SCALE, RAIL_MAX_SCALE)
    }

    /** 位置 [x] 投影到屏幕上的横坐标（相对书架中心）。 */
    fun screenX(x: Float): Float = x * cos * scale(x)

    /** 位置 [x] 处搁板线相对原搁板线的竖直位移（远处上移、近处下移）。 */
    fun lift(x: Float): Float = eyeHeight * (scale(x) - 1f)

    /** [screenX] 的反算：屏幕上横坐标 [x]（相对书架中心）对应斜轨上的位置（未计缩放上下限）。 */
    fun railX(x: Float): Float {
        if (camera <= 0f) return x
        val denom = (cos * camera - x * sin).coerceAtLeast(1f)
        return x * camera / denom
    }
}

/**
 * 整排书（含两端外侧 [endPad]）停在各本时最宽的宽度（px），用来判断书不多时能不能整排放进屏幕。
 * 只看停稳位置：翻页途中两盒同时半转会短暂多占一点宽度，由 [rowShift] 的贴边平移兜住，不必为此把书转得更侧。
 */
internal fun ShelfGeometry.maxRowWidth(count: Int, endPad: Float): Float {
    if (count <= 0) return 0f
    var widest = 0f
    var position = 0f
    while (position <= count - 1f) {
        val first = -position
        val last = count - 1 - position
        val left = centerX(first) - halfWidth(first)
        val right = centerX(last) + halfWidth(last)
        widest = maxOf(widest, right - left + 2f * endPad)
        position += 1f
    }
    return widest
}

/** 第 [page] 本相对书架中心的偏移（本）：正 = 在右侧。 */
private fun PagerState.shelfOffset(page: Int): Float = page - pagePosition()

/**
 * 书架上的一格：Pager 按 [slotPitch]（手指拖动的「一本」距离）等距排布，这里把盒子挪到书架几何给出的位置
 * （再加整排平移 [rowShift]），并按离当前位置远近排层级（当前那盒压在最上面）。子项按自己的可见宽度测量。
 *
 * 拖动距离与视觉间距分开：书脊只有几十 dp 宽，若拖过一本书脊就翻一本，轻轻一碰就滑到下一个游戏；
 * 拖动按 [slotPitch]（约半个封面宽）计，翻页需要更明确的手势，书架本身的排布不变。
 */
internal fun Modifier.shelfSlot(
    state: PagerState,
    page: Int,
    geometry: ShelfGeometry,
    slotPitch: Float,
    rowShift: () -> Float,
    rail: ShelfRail,
    viewport: Float = 0f,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val slotWidth = constraints.maxWidth
        layout(slotWidth, placeable.height) {
            val offset = state.shelfOffset(page)
            val railX = geometry.centerX(offset) + rowShift()
            val screenX = rail.screenX(railX)
            if (viewport > 0f && abs(screenX) > viewport * 0.5f + geometry.coverWidth * 0.85f) {
                return@layout
            }
            val shift = screenX - offset * slotPitch
            val x = (slotWidth - placeable.width) / 2f + shift
            val depth = (-railX / geometry.pitch).coerceIn(-MAX_Z_DEPTH, MAX_Z_DEPTH)
            placeable.place(x.roundToInt(), 0, zIndex = depth + HERO_Z * shelfHeroness(offset))
        }
    }

/**
 * 书架单排活跃局部区范围（单位：本，须严格大于 [REFLECTION_RANGE]、[HERO_RANGE]、[FRONT_CLIP_RANGE] 与 `1f`）。
 * 远离该范围的远处书脊共用夹断后的偏移量，避免每帧触发无效的宽度重测与图层/倒影重录。
 */
internal const val SHELF_ACTIVE_ZONE = 2.0f
internal const val SHELF_ACTIVE_ZONE_CLAMP = 2.5f

/**
 * 斜轨上的透视：整格（盒子、倒影、接触阴影）以搁板线为基准按近大远小缩放，并随搁板线上下移动。
 * 放在 [shelfSlot] 之后、倒影之前；[floorY] 是本格内的搁板线高度。
 */
internal fun Modifier.shelfRailPose(
    state: PagerState,
    page: Int,
    geometry: ShelfGeometry,
    rowShift: () -> Float,
    rail: ShelfRail,
    floorY: Float,
): Modifier = if (rail.isFlat) {
    this
} else {
    graphicsLayer {
        val x = geometry.centerX(state.shelfOffset(page)) + rowShift()
        val scale = rail.scale(x)
        transformOrigin = TransformOrigin(0.5f, (floorY / size.height).coerceIn(0f, 1f))
        scaleX = scale
        scaleY = scale
        translationY = rail.lift(x)
    }
}

/**
 * 每盒游戏自己的「个性」：由 uri 稳定推出。盒子高矮略有不同，约四分之一的书在架上微微歪着（转到正中时扶正），
 * 让一排书像是被人一本本放上去的，而不是复制出来的。
 */
@Immutable
internal class ShelfBookTrait(val sizeScale: Float, val restLeanDegrees: Float)

internal fun shelfBookTrait(uri: String): ShelfBookTrait {
    // 混洗 hashCode：只差末尾几个字符的路径也能得到分散的值
    var h = uri.hashCode()
    h = h xor (h ushr 16)
    h *= 0x45d9f3b
    h = h xor (h ushr 16)
    val size = 1f - SIZE_VARIANCE * ((h and 0xff) / 255f)
    val lean = when ((h ushr 8) and 0x7) {
        0 -> REST_LEAN_DEGREES
        1 -> -REST_LEAN_DEGREES
        else -> 0f
    }
    return ShelfBookTrait(size, lean)
}

/**
 * 盒子在书架上的整体姿态（只做二维变换，命中测试随之一起移动）：
 * 居中的盒子从搁板上微微抬起、扶正；离开中心后落回、略缩小（远一点），并恢复自己的高矮与歪角；
 * 甩动时整排书顺势向后歪一下，停下回正。
 */
internal fun Modifier.shelfBoxPose(
    state: PagerState,
    offsetProvider: () -> Float,
    liftPx: Float,
    velocity: State<Float>,
    trait: ShelfBookTrait,
): Modifier = graphicsLayer {
    val offset = offsetProvider()
    val t = shelfSideness(offset)
    val hero = shelfHeroness(offset)
    transformOrigin = TransformOrigin(0.5f, 1f)
    cameraDistance = POSE_CAMERA_DISTANCE * density
    // 越接近正中越像被从书架上抽出来：抬起、略放大、顶部朝外微倾；离开时原路放回
    val scale = shelfPoseScale(offset) * (1f - (1f - trait.sizeScale) * t)
    scaleX = scale
    scaleY = scale
    translationY = -liftPx * hero
    rotationX = HERO_TILT_DEGREES * hero
    // 甩到头（第一本 / 最后一本）时 Pager 已经停住，速度估计还要衰减一阵：那时不再侧倾，免得书歪着停半秒才回正
    val v = velocity.value
    val lean = if (abs(v) > 0.05f && state.isScrollInProgress &&
        (if (v > 0f) state.canScrollForward else state.canScrollBackward)
    ) {
        (v / LEAN_FULL_SPEED).coerceIn(-1f, 1f)
    } else {
        0f
    }
    rotationZ = lean * LEAN_DEGREES + trait.restLeanDegrees * t
}

/**
 * 「被选中」程度：正中为 1，离开 [HERO_RANGE] 本后为 0，中间用 smoothstep 缓入缓出——
 * 抽出 / 放回的过程完全跟着手指位置走，不在停稳后另起一段动画。
 */
internal fun shelfHeroness(offset: Float): Float {
    val x = (1f - abs(offset) / HERO_RANGE).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 离开正中的程度：正中 0，一本以外 1，用 smoothstep 过渡（到书脊行时变化率归零，与书脊行的姿态平滑衔接）。 */
internal fun shelfSideness(offset: Float): Float = smoothstep(min(abs(offset), 1f))

/**
 * 盒子的姿态缩放（不含每本书的高矮差异）：离开正中略缩小（往后退一点），被抽出时略放大。
 * [ShelfGeometry] 按它算盒子在屏幕上的真实宽度，[shelfBoxPose] 按它画，两边必须是同一个函数。
 */
internal fun shelfPoseScale(offset: Float): Float =
    (1f - SIDE_SHRINK * shelfSideness(offset)) * (1f + HERO_SCALE * shelfHeroness(offset))

/**
 * 盒子的可见宽度：内部按完整封面尺寸测量正面与书脊，对外只报告当前转角下的投影宽度，
 * 让点击区域与看到的盒子一致（不会点到被遮住的邻居）。远离正中超过 1 本时可见宽度恒定，零重复重测。
 */
internal fun Modifier.shelfVisibleWidth(
    geometry: ShelfGeometry,
    visibleWidthPx: () -> Int,
): Modifier = layout { measurable, _ ->
    val width = geometry.coverWidth.roundToInt()
    val height = geometry.coverHeight.roundToInt()
    val placeable = measurable.measure(Constraints.fixed(width, height))
    val visible = visibleWidthPx()
    layout(visible, height) {
        placeable.place(((visible - width) / 2f).roundToInt(), 0)
    }
}

/**
 * 书脊面：宽 [ShelfGeometry.spineDepth]，与正面共用一条棱。盒子在右侧时书脊在左（朝向书架中心），反之在右；
 * 正对时书脊侧立成一条线，直接不画。
 *
 * 只改图层属性（位移 / 转角 / 透明度），书脊内容只录制一次；背光压暗另用 [shelfFaceShade] 覆盖层。
 * 书脊是直角、不裁切：它是这本书的装订边，和封面是一体的，切了角就像两块拼起来的（倒角只在封面开口那一侧）；
 * 书脊内容也恰好铺满自身，用不着裁切。
 */
internal fun Modifier.shelfSpineFace(
    offsetProvider: () -> Float,
    geometry: ShelfGeometry,
): Modifier = graphicsLayer {
    val yaw = geometry.yaw(offsetProvider())
    val turn = abs(yaw)
    val edge = sharedEdge(geometry, yaw, turn)
    // 横向压缩到书脊投影宽度（仿射），不用透视旋转：书脊停稳时几乎正对屏幕，透视看不出来，
    // 而透视矩阵下的文字没法走字形缓存、要逐字按路径绘制，十几本书脊每帧会把 GPU 拖慢一大截。
    scaleX = sin(turn)
    if (yaw >= 0f) {
        translationX = edge - geometry.spineDepth
        transformOrigin = TransformOrigin(1f, 0.5f)
    } else {
        translationX = edge
        transformOrigin = TransformOrigin(0f, 0.5f)
    }
    alpha = if (turn < HIDDEN_SPINE_TURN) 0f else 1f
}

/**
 * 正面（封面）：绕与书脊共用的那条棱转开。同样只改图层属性；压暗与反光由覆盖层负责。
 * 倒角裁切只在接近正对时开启：转开后只露一条封面边，看不出倒角，而 3D 转动下的路径裁切在渲染线程上很贵。
 */
internal fun Modifier.shelfFrontFace(
    offsetProvider: () -> Float,
    geometry: ShelfGeometry,
    shapes: ShelfBoxShapes,
): Modifier = graphicsLayer {
    val offset = offsetProvider()
    val yaw = geometry.yaw(offset)
    val turn = abs(yaw)
    val edge = sharedEdge(geometry, yaw, turn)
    cameraDistance = SHELF_FACE_CAMERA * density
    // 与书脊相接的那条棱是直角，外侧两角是倒角：封面和书脊是同一个盒子的两个面
    shape = if (yaw >= 0f) shapes.frontWithSpineLeft else shapes.frontWithSpineRight
    clip = abs(offset) < FRONT_CLIP_RANGE
    val degrees = Math.toDegrees(turn.toDouble()).toFloat()
    if (yaw >= 0f) {
        translationX = edge
        transformOrigin = TransformOrigin(0f, 0.5f)
        rotationY = degrees
    } else {
        translationX = edge - geometry.coverWidth
        transformOrigin = TransformOrigin(1f, 0.5f)
        rotationY = -degrees
    }
}

/**
 * 封面的裁切形状：只有封面开口那一侧（离书脊最远的竖边）上下两个角斜切成倒角，像精装书 / 游戏盒的切角；
 * 与书脊相接那一侧是直角——书脊是同一本书的装订边。书脊在左 / 在右各一套，转向时随书脊换边切换
 * （换边时书脊正好侧立成一条线，看不出切换）。左右按屏幕绝对方向（几何本身就是按绝对坐标算的）。
 */
@Immutable
internal class ShelfBoxShapes(chamfer: Dp) {
    val frontWithSpineLeft: Shape = AbsoluteCutCornerShape(
        topLeft = 0.dp, topRight = chamfer, bottomRight = chamfer, bottomLeft = 0.dp,
    )
    val frontWithSpineRight: Shape = AbsoluteCutCornerShape(
        topLeft = chamfer, topRight = 0.dp, bottomRight = 0.dp, bottomLeft = chamfer,
    )
}

/**
 * 封面倒角高光覆盖层：静态录制的切角高光（见 `drawShelfChamferGlints`）画在右侧（书脊在左时的开口那一侧），
 * 书脊换到右边时整层水平镜像过去（只改图层属性，不重录）。封面离开正中、不再裁出倒角（[shelfFrontFace]）时
 * 高光一起隐去，免得在直角上浮着几道斜线。
 */
internal fun Modifier.shelfChamferGlint(offsetProvider: () -> Float, geometry: ShelfGeometry): Modifier =
    graphicsLayer {
        val offset = offsetProvider()
        scaleX = if (geometry.yaw(offset) >= 0f) 1f else -1f
        alpha = if (abs(offset) >= FRONT_CLIP_RANGE) 0f else 1f
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/**
 * 空气透视：离得越远（斜轨上越小）的书蒙上越厚一层与背景同色的雾，近处清晰、远处淡开。
 * 覆盖层只改透明度（直接调制，不开离屏缓冲）。
 */
internal fun Modifier.shelfRailFog(
    state: PagerState,
    page: Int,
    geometry: ShelfGeometry,
    rowShift: () -> Float,
    rail: ShelfRail,
): Modifier = if (rail.isFlat) {
    this
} else {
    graphicsLayer {
        val scale = rail.scale(geometry.centerX(state.shelfOffset(page)) + rowShift())
        alpha = (RAIL_FOG * ((1f - scale) / (1f - RAIL_MIN_SCALE))).coerceIn(0f, RAIL_FOG)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/**
 * 面的背光压暗覆盖层（铺满所在的面，自身底色为压暗色）：正面转得越侧越暗，书脊越朝外越亮。
 * 进度只写进本层的透明度，滚动时不重录任何内容。
 */
internal fun Modifier.shelfFaceShade(
    offsetProvider: () -> Float,
    geometry: ShelfGeometry,
    spine: Boolean,
): Modifier = graphicsLayer {
    alpha = geometry.faceShade(offsetProvider(), spine)
    // 覆盖层只有一块纯色，直接按透明度调制即可；默认策略在半透明时会为每一层开离屏缓冲
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/**
 * 偏移 [offset] 的盒子正面 / 书脊背光压暗的浓度：正面转得越侧越暗，书脊越朝外越亮。
 * 左侧的书换边时会转过书脊朝外的角度、直到书脊正对，按书脊朝外算。翻开的书起飞时照着它画。
 */
internal fun ShelfGeometry.faceShade(offset: Float, spine: Boolean): Float {
    val t = (abs(yaw(offset)) / maxTurn).coerceAtMost(1f)
    return if (spine) SPINE_SHADE * (1f - t) else FRONT_SHADE * t
}

/**
 * 封面反光覆盖层：一道静止录制的斜向高光带，正对时停在左上，转开时整层顺着转动方向滑过封面
 * （位移写在图层属性里）。反光自己裁在封面这块矩形里：离正中远的面为了省开销不裁切（见 shelfFrontFace），
 * 平移出去的那截高光会从侧转的书旁边漏出来，像一块半透明的板子。
 */
internal fun Modifier.shelfFrontGloss(offsetProvider: () -> Float, gloss: Color): Modifier =
    clipToBounds().graphicsLayer {
        translationX = size.width * GLOSS_TRAVEL * offsetProvider().coerceIn(-1f, 1f)
    }.shelfGlossOverlay(gloss)

/** 缓存封面斜向高光画刷，避免绘制阶段重复分配线性渐变。 */
internal fun Modifier.shelfGlossOverlay(gloss: Color): Modifier = drawWithCache {
    val center = size.width * GLOSS_REST
    val band = size.width * GLOSS_WIDTH
    val brush = Brush.linearGradient(
        0f to Color.Transparent,
        0.5f to gloss,
        1f to Color.Transparent,
        start = Offset(center - band, 0f),
        end = Offset(center + band, size.height * GLOSS_SLANT),
    )
    onDrawBehind { drawRect(brush) }
}

/** 封面反光带停在正对位置时的样子（书架正中那一盒、翻开的书起飞时的封面共用）。 */
internal fun DrawScope.drawShelfGloss(gloss: Color) {
    val center = size.width * GLOSS_REST
    val band = size.width * GLOSS_WIDTH
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to gloss,
            1f to Color.Transparent,
            start = Offset(center - band, 0f),
            end = Offset(center + band, size.height * GLOSS_SLANT),
        ),
    )
}

/** 正面与书脊共用的那条棱在盒子内部坐标里的横坐标（内部宽 = 封面宽，盒子可见部分居中）。 */
private fun sharedEdge(geometry: ShelfGeometry, yaw: Float, turn: Float): Float {
    val center = geometry.coverWidth / 2f
    val half = geometry.visibleWidth(turn) / 2f
    val spine = geometry.spineDepth * sin(turn)
    return if (yaw >= 0f) center - half + spine else center + half - spine
}

/**
 * 搁板上的倒影与接触阴影：在盒子下方镜像绘制一份内容并向下淡出；盒子抬起时倒影随之离开搁板。
 * 放在盒子姿态（抬起 / 缩放）之外，以搁板线 [floorY] 为镜面。
 *
 * 倒影要一个离屏层做渐隐遮罩，只给当前那盒和左右相邻的书画（离开 [REFLECTION_RANGE] 本以外的书脊倒影
 * 本来就细得看不出，逐本开离屏层会让渲染线程每帧多花好几毫秒），并随距离淡出，不会突然消失。
 */
internal fun Modifier.shelfReflection(
    offsetProvider: () -> Float,
    floorY: Float,
    bookHeight: Float,
    liftPx: Float,
    reflectionHeight: Float,
    reflectionAlpha: Float,
    shadow: Color,
    bodyAlpha: () -> Float = { 1f },
    groundAlpha: () -> Float = { 1f },
): Modifier = drawWithCache {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val elevation = ElevationShadow()
    val bodyPaint = Paint()
    val reflectionPaint = Paint()
    val bodyBounds = Rect(0f, 0f, width, height)
    val bleed = REFLECTION_MASK_BLEED * density
    val maskStartY = floorY
    val maskEndY = floorY + reflectionHeight
    val maskTopLeft = Offset(-bleed, maskStartY - bleed)
    val maskSize = Size(width + bleed * 2f, reflectionHeight + bleed * 2f)
    val fullReflectionBounds = Rect(0f, floorY, width, maskEndY)
    val contactRadiusX = width * CONTACT_SPREAD / 2f
    val contactRadiusY = reflectionHeight * SHADOW_HEIGHT_RATIO / 2f
    val contactCenter = Offset(width / 2f, floorY)
    val contactScaleY = if (contactRadiusX > 0.5f) contactRadiusY / contactRadiusX else 1f
    val contactBrush = if (contactRadiusX > 0.5f && contactRadiusY > 0.5f && reflectionHeight > 0f) {
        Brush.radialGradient(
            0f to shadow,
            0.55f to shadow.copy(alpha = shadow.alpha * 0.6f),
            1f to Color.Transparent,
            center = contactCenter,
            radius = contactRadiusX,
        )
    } else {
        null
    }
    val maskBrush = if (reflectionHeight > 0f) {
        Brush.verticalGradient(
            0f to Color.Black,
            1f to Color.Transparent,
            startY = maskStartY,
            endY = maskEndY,
        )
    } else {
        null
    }
    val blurRadius = ELEVATION_BLUR * density
    val elevShift = ELEVATION_SHIFT * density
    val elevDrop = ELEVATION_DROP * density
    val elevBaseW = width * 0.84f
    val elevBaseH = bookHeight * (1f + HERO_SCALE)

    onDrawWithContent {
        val bAlpha = bodyAlpha().coerceIn(0f, 1f)
        val gAlpha = groundAlpha().coerceIn(0f, 1f)
        if (bAlpha <= 0.001f && gAlpha <= 0.001f) return@onDrawWithContent
        val offset = offsetProvider()
        val hero = shelfHeroness(offset)
        // 被抽出来的那一盒悬在背板前：身后一团向右下方散开的模糊投影，随书离开/落回平滑淡出渐显
        if (hero > 0.02f && gAlpha > 0.005f) {
            val h = bookHeight * (1f + HERO_SCALE * hero)
            val dx = elevShift * hero
            val shadowLeft = width * 0.08f + dx
            val shadowRight = width * 0.92f + dx
            if (shadowRight > shadowLeft && blurRadius > 0.5f) {
                elevation.draw(
                    this,
                    left = shadowLeft,
                    top = floorY - h - liftPx * hero + elevDrop,
                    right = shadowRight,
                    bottom = floorY - liftPx * hero * 0.4f,
                    radius = blurRadius,
                    color = shadow.copy(alpha = (shadow.alpha * ELEVATION_STRENGTH * hero * gAlpha).coerceAtMost(1f)),
                    baseW = elevBaseW,
                    baseH = elevBaseH,
                )
            }
        }
        // 接触阴影：书脚压在搁板线上的一道软影，渐变按椭圆压扁、边缘完全淡出，看不出轮廓。
        val contact = 1f - hero
        if (contactBrush != null && contact > 0.01f && gAlpha > 0.005f) {
            withTransform({ scale(1f, contactScaleY, pivot = contactCenter) }) {
                drawCircle(
                    brush = contactBrush,
                    radius = contactRadiusX,
                    center = contactCenter,
                    alpha = (contact * gAlpha).coerceIn(0f, 1f),
                )
            }
        }
        if (bAlpha >= 0.999f) {
            drawContent()
        } else if (bAlpha > 0.001f) {
            bodyPaint.alpha = bAlpha
            drawContext.canvas.saveLayer(bodyBounds, bodyPaint)
            drawContent()
            drawContext.canvas.restore()
        }
        val nearness = (1f - (abs(offset) - 1f) / (REFLECTION_RANGE - 1f)).coerceIn(0f, 1f)
        val alpha = reflectionAlpha * nearness * gAlpha
        if (maskBrush == null || alpha <= 0.01f) return@onDrawWithContent
        // 固定使用完整倒影离屏尺寸，避免每帧动态变更 Bounds 引发 Skia 反复创建与驱逐 Scratch RenderTarget
        drawContext.canvas.saveLayer(fullReflectionBounds, reflectionPaint)
        withTransform({ scale(1f, -1f, pivot = contactCenter) }) {
            this@onDrawWithContent.drawContent()
        }
        drawRect(
            brush = maskBrush,
            topLeft = maskTopLeft,
            size = maskSize,
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.DstIn,
        )
        drawContext.canvas.restore()
    }
}

private object ElevationShadowCache {
    private const val SCALE = 0.25f
    private const val MAX_ENTRIES = 4

    class Entry(
        val bitmap: android.graphics.Bitmap,
        val baseW: Float,
        val baseH: Float,
        val padPx: Float,
    )

    private data class Key(
        val opaqueRgb: Int,
        val baseWQuant: Int,
        val baseHQuant: Int,
        val blurQuant: Int,
    )

    private val cache = object : LinkedHashMap<Key, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > MAX_ENTRIES
    }

    fun get(opaqueRgb: Int, baseW: Float, baseH: Float, blurRadius: Float): Entry? {
        if (baseW <= 0.5f || baseH <= 0.5f || blurRadius <= 0.5f) return null
        val wPx = maxOf(1, (baseW * SCALE).roundToInt())
        val hPx = maxOf(1, (baseH * SCALE).roundToInt())
        val bQuant = (blurRadius * 2f).roundToInt().coerceAtLeast(1)
        val key = Key(opaqueRgb, wPx, hPx, bQuant)
        synchronized(cache) {
            cache[key]?.let { if (!it.bitmap.isRecycled) return it }
        }
        val scaledBlur = (blurRadius * SCALE).coerceAtLeast(0.5f)
        val pad = ceil(blurRadius * 2.5f * SCALE).toInt().coerceAtLeast(2)
        val bmp = android.graphics.Bitmap.createBitmap(wPx + pad * 2, hPx + pad * 2, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = opaqueRgb
            maskFilter = android.graphics.BlurMaskFilter(scaledBlur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val scaledCorner = (blurRadius * 0.6f) * SCALE
        canvas.drawRoundRect(
            pad.toFloat(),
            pad.toFloat(),
            (pad + wPx).toFloat(),
            (pad + hPx).toFloat(),
            scaledCorner,
            scaledCorner,
            p,
        )
        val entry = Entry(
            bitmap = bmp,
            baseW = wPx / SCALE,
            baseH = hPx / SCALE,
            padPx = pad / SCALE,
        )
        synchronized(cache) {
            val existing = cache[key]
            if (existing != null && !existing.bitmap.isRecycled) return existing
            cache[key] = entry
        }
        return entry
    }
}

/**
 * 悬浮投影的画笔：共享 1/4 分辨率预模糊高斯阴影位图并按目标框缩放直绘，
 * 彻底消除逐帧变化高度引起的 CPU BlurMaskFilter 重算与 SW Path Mask 纹理分配。
 */
private class ElevationShadow {
    private val paint = android.graphics.Paint(
        android.graphics.Paint.ANTI_ALIAS_FLAG or
            android.graphics.Paint.FILTER_BITMAP_FLAG or
            android.graphics.Paint.DITHER_FLAG,
    )
    private val dstRect = android.graphics.RectF()
    private var cachedKeyRgb = 0
    private var cachedBaseW = -1f
    private var cachedBaseH = -1f
    private var cachedRadius = -1f
    private var cachedEntry: ElevationShadowCache.Entry? = null

    fun draw(
        scope: DrawScope,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        color: Color,
        baseW: Float,
        baseH: Float,
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P || bottom <= top || right <= left) return
        val a = (color.alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        if (a <= 0) return
        val opaqueRgb = color.toArgb() or (0xFF shl 24)
        val current = cachedEntry
        val entry = if (
            current != null &&
            !current.bitmap.isRecycled &&
            cachedKeyRgb == opaqueRgb &&
            cachedBaseW == baseW &&
            cachedBaseH == baseH &&
            cachedRadius == radius
        ) {
            current
        } else {
            cachedKeyRgb = opaqueRgb
            cachedBaseW = baseW
            cachedBaseH = baseH
            cachedRadius = radius
            ElevationShadowCache.get(opaqueRgb, baseW, baseH, radius).also { cachedEntry = it }
        } ?: return
        val sx = (right - left) / entry.baseW
        val sy = (bottom - top) / entry.baseH
        dstRect.set(
            left - entry.padPx * sx,
            top - entry.padPx * sy,
            right + entry.padPx * sx,
            bottom + entry.padPx * sy,
        )
        paint.alpha = a
        scope.drawContext.canvas.nativeCanvas.drawBitmap(entry.bitmap, null, dstRect, paint)
    }
}

/** 书柜各部分的配色（中性光学色来自 Color.kt，强调色来自主题）。 */
@Immutable
internal class ShelfCaseColors(
    val wall: Color,
    val wallShade: Color,
    val board: Color,
    val boardEdge: Color,
    val rim: Color,
    val glow: Color,
)

/**
 * 零 per-frame 分配的书柜搁板修饰符（静态路径与 4 枚背景渐变画刷随尺寸/主题预构建，
 * 每帧仅偏移预缓存的白光单位着色器并通过 [ColorFilter.tint] 调制发光色）。
 */
internal fun Modifier.shelfCase(
    positionProvider: () -> Float,
    shiftProvider: () -> Float,
    rail: ShelfRail,
    floorY: Float,
    depth: Float,
    edge: Float,
    bookHeight: Float,
    colors: ShelfCaseColors,
    glowProvider: (Float) -> Color,
): Modifier = drawWithCache {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f || bookHeight <= 0f) {
        return@drawWithCache onDrawBehind { }
    }
    val farX = width * 1.6f
    val leftScale = rail.scale(-farX)
    val rightScale = rail.scale(farX)
    val leftX = width / 2f + rail.screenX(-farX)
    val rightX = width / 2f + rail.screenX(farX)
    val leftFloor = floorY + rail.lift(-farX)
    val rightFloor = floorY + rail.lift(farX)
    val spanX = rightX - leftX
    val invSpan = if (abs(spanX) < 1e-4f) 0f else 1f / spanX

    val glowCenter = Offset(width / 2f, floorY - bookHeight * 0.45f)
    val glowRadius = width * WALL_GLOW_RADIUS
    val wallBrush = if (glowRadius > 0.5f) {
        Brush.radialGradient(
            0f to colors.wall,
            0.55f to colors.wall.copy(alpha = colors.wall.alpha * 0.5f),
            1f to colors.wall.copy(alpha = 0f),
            center = glowCenter,
            radius = glowRadius,
        )
    } else null

    val aoCenterX = width / 2f
    val aoFloor = if (invSpan == 0f) leftFloor else leftFloor + (rightFloor - leftFloor) * ((aoCenterX - leftX) * invSpan)
    val aoCenter = Offset(aoCenterX, aoFloor)
    val aoRadius = width * AO_RADIUS
    val slantDegrees = Math.toDegrees(kotlin.math.atan2((rightFloor - leftFloor).toDouble(), spanX.toDouble())).toFloat()
    val aoBrush = if (aoRadius > 0.5f) {
        Brush.radialGradient(
            0f to colors.wallShade,
            1f to colors.wallShade.copy(alpha = 0f),
            center = aoCenter,
            radius = aoRadius,
        )
    } else null

    val haloRadius = bookHeight * 0.75f
    val haloUnitBrush = if (haloRadius > 0.5f) {
        Brush.radialGradient(
            0f to Color.White,
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = haloRadius,
        )
    } else null

    val boardTopPath = Path().apply {
        moveTo(leftX, leftFloor)
        lineTo(rightX, rightFloor)
        lineTo(rightX, rightFloor + depth * rightScale)
        lineTo(leftX, leftFloor + depth * leftScale)
        close()
    }
    val boardTopBrush = Brush.verticalGradient(
        0f to colors.board.copy(alpha = colors.board.alpha * 0.7f),
        1f to colors.board,
        startY = minOf(leftFloor, rightFloor),
        endY = maxOf(leftFloor + depth * leftScale, rightFloor + depth * rightScale),
    )

    val poolRadius = width * POOL_RADIUS
    val poolScaleY = if (poolRadius > 0.5f && depth > 0.5f) depth * 0.5f / poolRadius else 0f
    val poolUnitBrush = if (poolRadius > 0.5f && depth > 0.5f) {
        Brush.radialGradient(
            0f to Color.White,
            0.5f to Color.White.copy(alpha = 0.55f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = poolRadius,
        )
    } else null

    val leftFront = leftFloor + depth * leftScale
    val rightFront = rightFloor + depth * rightScale
    val boardEdgePath = Path().apply {
        moveTo(leftX, leftFront)
        lineTo(rightX, rightFront)
        lineTo(rightX, rightFront + edge * rightScale)
        lineTo(leftX, leftFront + edge * leftScale)
        close()
    }
    val under = BOARD_UNDER_SHADOW * density
    val underPath = Path().apply {
        moveTo(leftX, leftFront + edge * leftScale)
        lineTo(rightX, rightFront + edge * rightScale)
        lineTo(rightX, rightFront + edge * rightScale + under * rightScale)
        lineTo(leftX, leftFront + edge * leftScale + under * leftScale)
        close()
    }
    val underBrush = Brush.verticalGradient(
        0f to colors.wallShade,
        1f to Color.Transparent,
        startY = minOf(leftFront, rightFront) + edge,
        endY = maxOf(leftFront, rightFront) + edge + under * leftScale,
    )
    val frontRimStroke = 1.2f * density
    val backRimColor = colors.rim.copy(alpha = colors.rim.alpha * 0.6f)
    val backRimStroke = 1f * density

    var lastGlowRgb = Int.MIN_VALUE
    var lastGlowFilter: ColorFilter? = null

    onDrawBehind {
        val position = positionProvider()
        val shift = shiftProvider()
        val glow = glowProvider(position)
        val glowRgb = glow.toArgb() or (0xFF shl 24)
        if (glowRgb != lastGlowRgb || lastGlowFilter == null) {
            lastGlowRgb = glowRgb
            lastGlowFilter = ColorFilter.tint(Color(glowRgb), BlendMode.Modulate)
        }
        val glowFilter = lastGlowFilter

        if (wallBrush != null) {
            withTransform({ scale(1f, WALL_GLOW_SQUASH, pivot = glowCenter) }) {
                drawCircle(wallBrush, radius = glowRadius, center = glowCenter)
            }
        }
        if (aoBrush != null) {
            withTransform({
                rotate(slantDegrees, pivot = aoCenter)
                scale(1f, AO_SQUASH, pivot = aoCenter)
            }) {
                drawCircle(aoBrush, radius = aoRadius, center = aoCenter)
            }
        }
        val settled = 1f - 2f * abs(position - position.roundToInt())
        val heroX = width / 2f + rail.screenX(shift)
        val heroFloor = if (invSpan == 0f) leftFloor else leftFloor + (rightFloor - leftFloor) * ((heroX - leftX) * invSpan)
        if (haloUnitBrush != null) {
            val haloAlpha = (glow.alpha * 0.55f * settled).coerceIn(0f, 1f)
            if (haloAlpha > 0.002f) {
                val hy = heroFloor - bookHeight * 0.5f
                withTransform({ translate(heroX, hy) }) {
                    drawCircle(haloUnitBrush, radius = haloRadius, center = Offset.Zero, alpha = haloAlpha, colorFilter = glowFilter)
                }
            }
        }
        drawPath(boardTopPath, boardTopBrush)
        if (poolUnitBrush != null) {
            val poolAlpha = (glow.alpha * POOL_STRENGTH * (0.45f + 0.55f * settled)).coerceIn(0f, 1f)
            if (poolAlpha > 0.002f) {
                val py = heroFloor + depth * 0.5f
                withTransform({
                    translate(heroX, py)
                    scale(1f, poolScaleY, pivot = Offset.Zero)
                }) {
                    drawCircle(poolUnitBrush, radius = poolRadius, center = Offset.Zero, alpha = poolAlpha, colorFilter = glowFilter)
                }
            }
        }
        drawPath(boardEdgePath, colors.boardEdge)
        drawLine(colors.rim, Offset(leftX, leftFront), Offset(rightX, rightFront), strokeWidth = frontRimStroke)
        drawPath(underPath, underBrush)
        drawLine(backRimColor, Offset(leftX, leftFloor), Offset(rightX, rightFloor), strokeWidth = backRimStroke)
    }
}

/**
 * 书柜：书后面一块浅色背板（顶部压一道上层隔板投下的阴影），书脚下是有厚度的玻璃搁板——
 * 板面承接倒影、前沿一道深色边、板下一层软影；正中那一盒身后的背板上亮起一圈柔光，脚下一汪光，
 * 颜色是 [glow]（页面传入当前封面的主色，翻页途中在相邻两本之间过渡），越接近停稳越亮。全部按翻页位置在绘制阶段计算。
 *
 * @param floorY 搁板线（板面后缘）
 * @param depth 板面深度（倒影区）
 * @param edge 板前沿厚度
 * @param bookHeight 盒子高度（用来放背板上的光晕）
 */
internal fun DrawScope.drawShelfCase(
    position: Float,
    shift: Float,
    rail: ShelfRail,
    floorY: Float,
    depth: Float,
    edge: Float,
    bookHeight: Float,
    colors: ShelfCaseColors,
    glow: Color = colors.glow,
) {
    val width = size.width
    if (width <= 0f || size.height <= 0f || bookHeight <= 0f) return
    // 斜轨：搁板两端（画面外）投影后的端点；透视下直线仍是直线，按两端连线画出斜着的板面 / 板沿
    val farX = width * 1.6f
    val leftScale = rail.scale(-farX)
    val rightScale = rail.scale(farX)
    val leftX = width / 2f + rail.screenX(-farX)
    val rightX = width / 2f + rail.screenX(farX)
    val leftFloor = floorY + rail.lift(-farX)
    val rightFloor = floorY + rail.lift(farX)
    val spanX = rightX - leftX
    fun floorAt(x: Float): Float = if (abs(spanX) < 1e-4f) leftFloor else leftFloor + (rightFloor - leftFloor) * ((x - leftX) / spanX)
    fun scaleAt(x: Float): Float = if (abs(spanX) < 1e-4f) leftScale else leftScale + (rightScale - leftScale) * ((x - leftX) / spanX)
    // 背后一团椭圆柔光：书排在一片微微发亮的空间前，四周自然淡出，没有任何直边
    val glowCenter = Offset(width / 2f, floorY - bookHeight * 0.45f)
    val glowRadius = width * WALL_GLOW_RADIUS
    if (glowRadius > 0.5f) {
        withTransform({ scale(1f, WALL_GLOW_SQUASH, pivot = glowCenter) }) {
            drawCircle(
                Brush.radialGradient(
                    0f to colors.wall,
                    0.55f to colors.wall.copy(alpha = colors.wall.alpha * 0.5f),
                    1f to colors.wall.copy(alpha = 0f),
                    center = glowCenter,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = glowCenter,
            )
        }
    }
    // 书脚与搁板相接处一条两头淡出的暗带（环境光遮蔽）：书是「立在」板上，不是贴在背景上
    val aoCenter = Offset(width / 2f, floorAt(width / 2f))
    val aoRadius = width * AO_RADIUS
    val slantDegrees = Math.toDegrees(kotlin.math.atan2((rightFloor - leftFloor).toDouble(), (rightX - leftX).toDouble())).toFloat()
    if (aoRadius > 0.5f) {
        withTransform({
            rotate(slantDegrees, pivot = aoCenter)
            scale(1f, AO_SQUASH, pivot = aoCenter)
        }) {
            drawCircle(
                Brush.radialGradient(
                    0f to colors.wallShade,
                    1f to colors.wallShade.copy(alpha = 0f),
                    center = aoCenter,
                    radius = aoRadius,
                ),
                radius = aoRadius,
                center = aoCenter,
            )
        }
    }
    // 正中那一盒身后的光晕
    val settled = 1f - 2f * abs(position - position.roundToInt())
    val heroX = width / 2f + rail.screenX(shift)
    val haloRadius = bookHeight * 0.75f
    if (haloRadius > 0.5f) {
        drawCircle(
            Brush.radialGradient(
                0f to glow.copy(alpha = glow.alpha * 0.55f * settled),
                1f to glow.copy(alpha = 0f),
                center = Offset(heroX, floorAt(heroX) - bookHeight * 0.5f),
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = Offset(heroX, floorAt(heroX) - bookHeight * 0.5f),
        )
    }
    // 板面：斜着的玻璃板顶面（后缘 = 搁板线，前沿按各处透视缩放的深度往下），从后往前逐渐受光
    val boardTop = Path().apply {
        moveTo(leftX, leftFloor)
        lineTo(rightX, rightFloor)
        lineTo(rightX, rightFloor + depth * rightScale)
        lineTo(leftX, leftFloor + depth * leftScale)
        close()
    }
    drawPath(
        boardTop,
        Brush.verticalGradient(
            0f to colors.board.copy(alpha = colors.board.alpha * 0.7f),
            1f to colors.board,
            startY = minOf(leftFloor, rightFloor),
            endY = maxOf(leftFloor + depth * leftScale, rightFloor + depth * rightScale),
        ),
    )
    // 脚下一汪光（封面的主色）：只铺在板面上、横向拉长，渐变按椭圆压扁、边缘完全淡出——
    // 不越过搁板线，也没有上下两道硬边，不会在书脚下圈出一块圆盘
    val glowAlpha = glow.alpha * POOL_STRENGTH * (0.45f + 0.55f * settled)
    val poolCenter = Offset(heroX, floorAt(heroX) + depth * 0.5f)
    val poolRadius = width * POOL_RADIUS
    if (poolRadius > 0.5f && depth > 0.5f) {
        withTransform({ scale(1f, depth * 0.5f / poolRadius, pivot = poolCenter) }) {
            drawCircle(
                Brush.radialGradient(
                    0f to glow.copy(alpha = glowAlpha),
                    0.5f to glow.copy(alpha = glowAlpha * 0.55f),
                    1f to glow.copy(alpha = 0f),
                    center = poolCenter,
                    radius = poolRadius,
                ),
                radius = poolRadius,
                center = poolCenter,
            )
        }
    }
    // 板前沿：一道有厚度的深色边（同样斜着、近厚远薄），上缘一线高光
    val leftFront = leftFloor + depth * leftScale
    val rightFront = rightFloor + depth * rightScale
    drawPath(
        Path().apply {
            moveTo(leftX, leftFront)
            lineTo(rightX, rightFront)
            lineTo(rightX, rightFront + edge * rightScale)
            lineTo(leftX, leftFront + edge * leftScale)
            close()
        },
        colors.boardEdge,
    )
    drawLine(colors.rim, Offset(leftX, leftFront), Offset(rightX, rightFront), strokeWidth = 1.2f * density)
    // 板下软影
    val under = BOARD_UNDER_SHADOW * density
    drawPath(
        Path().apply {
            moveTo(leftX, leftFront + edge * leftScale)
            lineTo(rightX, rightFront + edge * rightScale)
            lineTo(rightX, rightFront + edge * rightScale + under * rightScale)
            lineTo(leftX, leftFront + edge * leftScale + under * leftScale)
            close()
        },
        Brush.verticalGradient(
            0f to colors.wallShade,
            1f to Color.Transparent,
            startY = minOf(leftFront, rightFront) + edge,
            endY = maxOf(leftFront, rightFront) + edge + under * leftScale,
        ),
    )
    // 后缘发丝线
    drawLine(colors.rim.copy(alpha = colors.rim.alpha * 0.6f), Offset(leftX, leftFloor), Offset(rightX, rightFloor), strokeWidth = 1f * density)
}

/** 搁板前沿以下（前沿厚度 + 板下软影）占用的高度（dp）。 */
internal const val SHELF_BOARD_EDGE = 5f
internal const val SHELF_BELOW_BOARD = SHELF_BOARD_EDGE + 16f

/** 底部控制条两端橡皮筋视觉拉出位移（渐近阻力曲线，严格有界于 ±[maxPullPx] 内）。 */
internal fun shelfScrubberRubberPull(rawOffsetPx: Float, maxPullPx: Float): Float {
    val d = abs(rawOffsetPx)
    if (d == 0f || maxPullPx <= 0f) return 0f
    return (1f - 1f / (d * SCRUBBER_RUBBER_COEFF / maxPullPx + 1f)) * maxPullPx * sign(rawOffsetPx)
}

/** 进度轨道两端留白内，[position]（本）对应的横坐标（线性夹断，不越出 [inset, width - inset]）。 */
internal fun shelfRulerX(position: Float, count: Int, width: Float, inset: Float): Float {
    if (count <= 1) return width / 2f
    return inset + (width - 2f * inset) * (position / (count - 1)).coerceIn(0f, 1f)
}

/**
 * 进度轨道视觉横坐标：区间 [0, count - 1] 内与 [shelfRulerX] 一致，两端点靠低阻尼飞行过冲或边缘拉扯时
 * 叠加橡皮筋柔性缓震与 [rubberPx]，并严格限制在 `[inset - maxOverpullPx, width - inset + maxOverpullPx]` 内。
 */
internal fun shelfRulerVisualX(
    position: Float,
    count: Int,
    width: Float,
    inset: Float,
    rubberPx: Float = 0f,
    maxOverpullPx: Float = 0f,
): Float {
    if (count <= 1) return width / 2f
    val left = inset
    val right = width - inset
    val maxPull = maxOverpullPx.coerceAtLeast(0f)
    val rawX = left + (right - left) * (position / (count - 1))
    val baseX = when {
        rawX < left -> left + shelfScrubberRubberPull(rawX - left, maxPull)
        rawX > right -> right + shelfScrubberRubberPull(rawX - right, maxPull)
        else -> rawX
    }
    return (baseX + rubberPx).coerceIn(left - maxPull, right + maxPull)
}

/** 旋钮的缩放：只有按住时整体鼓起一点（正圆放大，不拉伸、不压扁），松手回到 1。 */
internal fun shelfKnobScale(lift: Float, liftScale: Float): Float =
    (1f + liftScale * lift).coerceIn(0.9f, 1.5f)

/** 进度轨道上横坐标 [x] 对应的连续位置（本）。 */
internal fun shelfRulerPosition(x: Float, count: Int, width: Float, inset: Float): Float {
    if (count <= 1) return 0f
    val usable = (width - 2f * inset).coerceAtLeast(1f)
    return ((x - inset) / usable).coerceIn(0f, 1f) * (count - 1)
}

/**
 * 进度轨道修饰符：基于 `drawWithCache` 缓存两端渐隐遮罩画刷与复用路径/点位数组，
 * 滑动期间每帧零 `Path` / `Brush` / `Stroke` 分配。
 */
internal fun Modifier.shelfTrack(
    count: Int,
    insetProvider: (Float) -> Float,
    ink: Color,
    presenceProvider: () -> Float,
    positionProvider: () -> Float,
    knobGapProvider: () -> Float,
    knobXProvider: (position: Float, width: Float, inset: Float) -> Float,
    lineScaleProvider: () -> Float,
    agitationProvider: () -> Float,
): Modifier = drawWithCache {
    if (count <= 1 || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawBehind { }
    }
    val width = size.width
    val y = size.height / 2f
    val inset = insetProvider(width)
    val lineStart = inset - TRACK_OVERHANG * density
    val lineEnd = (width - inset) + TRACK_OVERHANG * density
    val fade = TRACK_END_FADE * density
    val span = (lineEnd - lineStart).coerceAtLeast(1f)
    val stop = (fade / span).coerceIn(0f, 0.5f)
    val passedMaskBrush = Brush.horizontalGradient(
        0f to Color.Transparent,
        stop to ink.copy(alpha = ink.alpha * TRACK_PASSED_ALPHA),
        1f - stop to ink.copy(alpha = ink.alpha * TRACK_PASSED_ALPHA),
        1f to Color.Transparent,
        startX = lineStart,
        endX = lineEnd,
    )
    val restMaskBrush = Brush.horizontalGradient(
        0f to Color.Transparent,
        stop to ink.copy(alpha = ink.alpha * TRACK_REST_ALPHA),
        1f - stop to ink.copy(alpha = ink.alpha * TRACK_REST_ALPHA),
        1f to Color.Transparent,
        startX = lineStart,
        endX = lineEnd,
    )

    val usableWidth = (width - 2f * inset).coerceAtLeast(1f)
    val spacing = usableWidth / (count - 1)
    val minSpacing = TRACK_MIN_DOT_SPACING * density
    val drawDots = spacing >= minSpacing
    val step = if (drawDots) 1 else if (spacing * 10f >= minSpacing) 10 else TRACK_MAJOR_EVERY
    val markStart = if (drawDots) 0 else step
    val markCount = if (drawDots) count else ((count - 1) / step).coerceAtLeast(0)
    val markXs = FloatArray(markCount)
    val markFades = FloatArray(markCount)
    val markHalfHeights = if (drawDots) FloatArray(0) else FloatArray(markCount)
    for (idx in 0 until markCount) {
        val i = markStart + idx * step
        val dx = inset + usableWidth * (i.toFloat() / (count - 1)).coerceIn(0f, 1f)
        markXs[idx] = dx
        markFades[idx] = (min(dx - lineStart, lineEnd - dx) / fade).coerceIn(0f, 1f)
        if (!drawDots) {
            markHalfHeights[idx] = (if (i % TRACK_MAJOR_EVERY == 0) TRACK_MAJOR_TICK else TRACK_MINOR_TICK) * density
        }
    }

    val path = Path()
    val dotRadius = TRACK_DOT_RADIUS * density
    val tickStroke = TRACK_TICK_WIDTH * density
    val bounceHeight = TRACK_BOUNCE_HEIGHT * density
    val bounceReach = TRACK_BOUNCE_REACH * density
    val bounceStep = TRACK_BOUNCE_STEP * density
    val basePassedMarkAlpha = ink.alpha * TRACK_PASSED_MARK_ALPHA
    val baseRestMarkAlpha = ink.alpha * TRACK_REST_MARK_ALPHA
    val baseLinePx = TRACK_LINE_WIDTH * density

    onDrawBehind {
        val presence = presenceProvider().coerceIn(0f, 1f)
        if (presence <= 0.002f) return@onDrawBehind
        val position = positionProvider()
        val knobGap = knobGapProvider()
        val knobX = knobXProvider(position, width, inset)
        val lineScale = lineScaleProvider().coerceIn(0.85f, 1.5f)
        val agitation = agitationProvider()
        val stroke = baseLinePx * lineScale
        val passedEnd = knobX - knobGap
        val restStart = knobX + knobGap

        fun bump(x: Float): Float {
            if (agitation == 0f) return 0f
            val d = (x - knobX) / bounceReach
            return -agitation * bounceHeight * kotlin.math.exp(-d * d)
        }

        fun drawSegment(from: Float, to: Float, brush: Brush) {
            if (agitation == 0f) {
                drawLine(brush, Offset(from, y), Offset(to, y), strokeWidth = stroke, cap = StrokeCap.Round, alpha = presence)
                return
            }
            path.rewind()
            var x = from
            path.moveTo(x, y + bump(x))
            while (x < to) {
                x = min(x + bounceStep, to)
                path.lineTo(x, y + bump(x))
            }
            drawPath(
                path = path,
                brush = brush,
                alpha = presence,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }

        if (passedEnd > lineStart) drawSegment(lineStart, passedEnd, passedMaskBrush)
        if (restStart < lineEnd) drawSegment(restStart, lineEnd, restMaskBrush)

        for (idx in 0 until markCount) {
            val dx = markXs[idx]
            if (abs(dx - knobX) < knobGap) continue
            val i = markStart + idx * step
            val a = (if (i <= position) basePassedMarkAlpha else baseRestMarkAlpha) * presence * markFades[idx]
            if (a <= 0.002f) continue
            val color = ink.copy(alpha = a)
            val cy = y + bump(dx)
            if (drawDots) {
                drawCircle(color, radius = dotRadius, center = Offset(dx, cy))
            } else {
                val half = markHalfHeights[idx]
                drawLine(color, Offset(dx, cy - half), Offset(dx, cy + half), strokeWidth = tickStroke, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * 进度轨道（似有似无）：一根很细的半透明发丝线，两端渐隐进背景，旋钮两侧断开一小段；翻过的那段略深一点。
 * 书不多时每本一颗小点；多了改成每 10 本一道竖直短刻度、每 50 本一道稍长的（太密时只留长刻度）。
 * 刻度同样两端渐隐，被旋钮盖住的不画。[ink] 是中性墨色（浅色底上压淡的黑、深色底上白），
 * [presence] 是整体浓度：书架静止时很淡，滑动 / 拖动时浮现（见 [rememberTrackPresence]）。
 * 旋钮由页面另画在轨道之上，[knobGap] 是旋钮中心到两侧断口的距离。
 */
internal fun DrawScope.drawShelfTrack(
    position: Float,
    count: Int,
    inset: Float,
    ink: Color,
    presence: Float,
    knobGap: Float,
    knobXOverride: Float? = null,
    lineScale: Float = 1f,
    agitation: Float = 0f,
) {
    if (count <= 1) return
    val y = size.height / 2f
    val left = inset
    val right = size.width - inset
    val knobX = knobXOverride ?: shelfRulerX(position, count, size.width, inset)
    val lineStart = left - TRACK_OVERHANG * density
    val lineEnd = right + TRACK_OVERHANG * density
    val fade = TRACK_END_FADE * density
    fun tint(alpha: Float) = ink.copy(alpha = ink.alpha * alpha * presence)
    // 两端渐隐：按到线头的距离算浓度（线和刻度用同一条曲线，接得上）
    fun endFade(x: Float) = (min(x - lineStart, lineEnd - x) / fade).coerceIn(0f, 1f)
    val stop = (fade / (lineEnd - lineStart)).coerceIn(0f, 0.5f)
    fun lineBrush(color: Color) = Brush.horizontalGradient(
        0f to color.copy(alpha = 0f),
        stop to color,
        1f - stop to color,
        1f to color.copy(alpha = 0f),
        startX = lineStart,
        endX = lineEnd,
    )
    val stroke = TRACK_LINE_WIDTH * lineScale.coerceIn(0.85f, 1.5f) * density
    val passedEnd = knobX - knobGap
    val restStart = knobX + knobGap
    // Q 弹：旋钮一动，它附近的线与刻度跟着弹起一个缓缓扩散的小丘（高度随 [agitation] 弹簧摆动，松手后还会反向晃一两下）；
    // 旋钮自己也随同一个量上下浮一点（见 ShelfTrackKnob），所以线头始终接在旋钮上
    val bounceHeight = TRACK_BOUNCE_HEIGHT * density
    val bounceReach = TRACK_BOUNCE_REACH * density
    fun bump(x: Float): Float {
        if (agitation == 0f) return 0f
        val d = (x - knobX) / bounceReach
        return -agitation * bounceHeight * kotlin.math.exp(-d * d)
    }
    fun strokeLine(from: Float, to: Float, brush: Brush) {
        if (agitation == 0f) {
            drawLine(brush, Offset(from, y), Offset(to, y), strokeWidth = stroke, cap = StrokeCap.Round)
            return
        }
        val path = Path()
        val step = TRACK_BOUNCE_STEP * density
        var x = from
        path.moveTo(x, y + bump(x))
        while (x < to) {
            x = min(x + step, to)
            path.lineTo(x, y + bump(x))
        }
        drawPath(
            path,
            brush,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
    if (passedEnd > lineStart) strokeLine(lineStart, passedEnd, lineBrush(tint(TRACK_PASSED_ALPHA)))
    if (restStart < lineEnd) strokeLine(restStart, lineEnd, lineBrush(tint(TRACK_REST_ALPHA)))
    val passedMark = tint(TRACK_PASSED_MARK_ALPHA)
    val restMark = tint(TRACK_REST_MARK_ALPHA)
    // 第 [i] 本处刻度的颜色；被旋钮盖住时返回 null（不画）
    fun markColor(i: Int, x: Float): Color? {
        if (abs(x - knobX) < knobGap) return null
        val base = if (i <= position) passedMark else restMark
        return base.copy(alpha = base.alpha * endFade(x))
    }
    val spacing = (right - left) / (count - 1)
    val minSpacing = TRACK_MIN_DOT_SPACING * density
    if (spacing >= minSpacing) {
        // 书不多：每本一颗小点
        val dot = TRACK_DOT_RADIUS * density
        for (i in 0 until count) {
            val dx = shelfRulerX(i.toFloat(), count, size.width, inset)
            val color = markColor(i, dx) ?: continue
            drawCircle(color, radius = dot, center = Offset(dx, y + bump(dx)))
        }
        return
    }
    // 书很多：每本一颗点挤成一条线，改成每 10 本一道竖直短刻度、每 50 本一道稍长的（太密时只留长刻度）
    val step = if (spacing * 10f >= minSpacing) 10 else TRACK_MAJOR_EVERY
    var i = step
    while (i < count) {
        val dx = shelfRulerX(i.toFloat(), count, size.width, inset)
        val color = markColor(i, dx)
        if (color != null) {
            val half = (if (i % TRACK_MAJOR_EVERY == 0) TRACK_MAJOR_TICK else TRACK_MINOR_TICK) * density
            val lifted = y + bump(dx)
            drawLine(
                color,
                Offset(dx, lifted - half),
                Offset(dx, lifted + half),
                strokeWidth = TRACK_TICK_WIDTH * density,
                cap = StrokeCap.Round,
            )
        }
        i += step
    }
}

/**
 * 点按进度轨道跳转：书架从当前位置**连续**滑到目标那一本（一条减速曲线，到站带一下 Q 弹，见 [ShelfGlideFling.glideTo]），
 * 近处利落、远处也一口气滑到，不再「先瞬间跳到目标前几本、再播最后一小段」；旋钮始终跟着书架的位置走（[position]），
 * 所以旋钮与书架同步滑过，不另飞一遍。[active] 期间翻页触觉静音（这一下只在点按时震一次），信息行直接锁向目标（[targetPage]），
 * 途中掠过的中间页不闪。连点时后一次接管前一次，从当前所在处接着滑；手指一碰书架或轨道就中止。
 * 「普通」档书架直接用默认的翻页补间。
 */
@Stable
internal class ShelfKnobSeek {
    private var generation = 0
    private var job: Job? = null
    var active by mutableStateOf(false)
        private set
    var targetPage by mutableStateOf<Int?>(null)
        private set

    /** 旋钮现在该画在哪一本：就是书架的连续位置 [pager]。 */
    fun position(pager: Float): Float = pager

    /** 立刻中止当前点按跳转（例如用户直接上手拖动进度轨道或书架），交回实时跟随。 */
    fun cancel() {
        generation++
        job?.cancel()
        job = null
        active = false
        targetPage = null
    }

    suspend fun seek(state: PagerState, fling: ShelfGlideFling?, target: Int) {
        val last = state.pageCount - 1
        if (last < 0) return
        val clamped = target.coerceIn(0, last)
        val gen = ++generation
        val previous = job
        job = currentCoroutineContext()[Job]
        targetPage = clamped
        previous?.cancel()
        active = true
        try {
            if (fling != null && MotionMode.enhanced) {
                state.scroll(MutatePriority.UserInput) { with(fling) { glideTo(clamped) } }
            } else {
                state.animateScrollToPage(clamped)
            }
        } finally {
            if (gen == generation) {
                job = null
                active = false
                targetPage = null
            }
        }
    }
}

internal enum class ShelfInfoUpdateMode {
    Seek,
    Dragging,
    Settled,
}

internal data class ShelfInfoResolution(
    val page: Int,
    val mode: ShelfInfoUpdateMode,
)

/**
 * 信息行（ADV）的候选页：点轨道预览目标页；手指拖动或停稳时考虑当前页；惯性和 Q 弹途中没有候选页。
 */
internal fun resolveShelfInfoPage(
    currentPage: Int,
    seekTarget: Int?,
    pageCount: Int,
    isScrollInProgress: Boolean,
    isDragged: Boolean,
): ShelfInfoResolution? {
    val last = (pageCount - 1).coerceAtLeast(0)
    return when {
        seekTarget != null -> ShelfInfoResolution(seekTarget.coerceIn(0, last), ShelfInfoUpdateMode.Seek)
        isDragged -> ShelfInfoResolution(currentPage.coerceIn(0, last), ShelfInfoUpdateMode.Dragging)
        isScrollInProgress -> null
        else -> ShelfInfoResolution(currentPage.coerceIn(0, last), ShelfInfoUpdateMode.Settled)
    }
}

private const val INFO_DRAG_DWELL_MILLIS = 150L
private const val INFO_MIN_UPDATE_INTERVAL_MILLIS = 120L

/** 只在候选页或阶段变化时重置等待；停稳与拖动共用最短间隔，显式跳转可立即预览。 */
internal class ShelfInfoPage(initialPage: Int) {
    var shown by mutableIntStateOf(initialPage)
        private set

    suspend fun follow(
        nowMillis: () -> Long = SystemClock::uptimeMillis,
        candidate: () -> ShelfInfoResolution?,
    ) {
        var lastUpdateAt: Long? = null
        snapshotFlow(candidate).collectLatest { next ->
            if (next == null || next.page == shown) return@collectLatest
            if (next.mode != ShelfInfoUpdateMode.Seek) {
                val dwell = if (next.mode == ShelfInfoUpdateMode.Dragging) INFO_DRAG_DWELL_MILLIS else 0L
                val remaining = lastUpdateAt?.let { INFO_MIN_UPDATE_INTERVAL_MILLIS - (nowMillis() - it) } ?: 0L
                delay(maxOf(dwell, remaining))
            }
            if (candidate() != next) return@collectLatest
            shown = next.page
            lastUpdateAt = nowMillis()
        }
    }
}

@Composable
internal fun rememberShelfInfoPage(pagerState: PagerState, knobSeek: ShelfKnobSeek, scrubbing: State<Boolean>): Int {
    val info = remember(pagerState) { ShelfInfoPage(pagerState.currentPage) }
    val dragged = pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged, knobSeek) {
        snapshotFlow { dragged.value }.collect { if (it && knobSeek.active) knobSeek.cancel() }
    }
    LaunchedEffect(pagerState, knobSeek, dragged, scrubbing) {
        info.follow {
            resolveShelfInfoPage(
                currentPage = pagerState.currentPage,
                seekTarget = knobSeek.targetPage,
                pageCount = pagerState.pageCount,
                isScrollInProgress = pagerState.isScrollInProgress,
                isDragged = dragged.value || scrubbing.value,
            )
        }
    }
    val last = (pagerState.pageCount - 1).coerceAtLeast(0)
    return (knobSeek.targetPage ?: info.shown).coerceIn(0, last)
}

@Composable
internal fun rememberShelfKnobSeek(): ShelfKnobSeek = remember { ShelfKnobSeek() }

/** 进度轨道拖动松手后，书架吸附至最近整数页（增强档沿用 Q 弹吸附弹簧）。 */
internal suspend fun PagerState.settleScrub(page: Int) {
    if (MotionMode.enhanced) animateScrollToPage(page, animationSpec = pagerBouncySnap(0.5f))
    else animateScrollToPage(page)
}

/**
 * 底部滚动控制条的 Q 弹控制器：
 * - 旋钮一动（拖动、点按、跟着书架滑行），轨道上的线与刻度在它附近弹起一个小丘，高度由一根欠阻尼弹簧
 *   （[railBounce]）驱动：越快越高，停下后还会反向晃一两下再落定；旋钮自己随同一个量上下浮一点，线头始终接在旋钮上；
 * - 两端橡皮筋拉出与松手 Q 弹回位；
 * - 拖动气泡随水平速度柔和微倾。
 * 旋钮本身不再形变（始终是正圆，按住时只整体鼓起一点）。普通档零额外弹簧，零重组
 * （所有动画仅驱动各自 [Animatable]，在布局 / 图层 / 绘制阶段直接读取）。
 */
@Stable
internal class ShelfScrubberMotion(private val scope: CoroutineScope) {
    private val railBounceState = mutableFloatStateOf(0f)
    private val rubberState = mutableFloatStateOf(0f)
    private val bubbleTiltState = mutableFloatStateOf(0f)

    private var rubberJob: Job? = null
    private var tiltJob: Job? = null
    private var dragIdleJob: Job? = null

    /** 轨道的弹起量：0 为静止，正数向上弹、越过 0 就是反向晃。 */
    val railBounce: Float get() = if (MotionMode.enhanced) railBounceState.floatValue else 0f
    val rubberPx: Float get() = if (MotionMode.enhanced) rubberState.floatValue else 0f
    val bubbleTiltDegrees: Float get() = if (MotionMode.enhanced) bubbleTiltState.floatValue else 0f

    /**
     * 单协程驱动旋钮移动引起的轨道 Q 弹（零每帧协程取消与重建，闲置时零功耗挂起）：
     * 与旧 `Animatable(RAIL_SPRING)` + `delay(70ms)` 回零行为数值同源。
     */
    suspend fun trackKnob(pagerState: PagerState, knobSeek: ShelfKnobSeek, count: Int) {
        if (!MotionMode.enhanced || !ValueAnimator.areAnimatorsEnabled()) {
            railBounceState.floatValue = 0f
            return
        }
        val spec = FloatSpringSpec(dampingRatio = 0.34f, stiffness = 380f)
        val wakeSignal = Channel<Unit>(Channel.CONFLATED)
        var lastMoveEventNanos = 0L
        var pendingTarget = 0f
        var active = false

        coroutineScope {
            launch {
                var lastPos = Float.NaN
                var lastSampleNanos = 0L
                snapshotFlow { knobSeek.position(pagerState.pagePosition()) }.collect { position ->
                    val now = System.nanoTime()
                    if (!lastPos.isNaN() && count > 1) {
                        val seconds = ((now - lastSampleNanos) / 1e9f).coerceIn(0.004f, 0.12f)
                        val fps = (position - lastPos) / (count - 1) / seconds
                        val fraction = (abs(fps) / RAIL_FULL_SPEED).coerceIn(0f, 1f)
                        pendingTarget = fraction * RAIL_MAX_BOUNCE
                        lastMoveEventNanos = now
                        if (!active) {
                            active = true
                            wakeSignal.trySend(Unit)
                        }
                    }
                    lastPos = position
                    lastSampleNanos = now
                }
            }

            while (true) {
                wakeSignal.receive()
                var animVal = railBounceState.floatValue
                var animVel = 0f
                var segTarget = pendingTarget
                var segInitVal = animVal
                var segInitVel = animVel
                var segStartNanos = withFrameNanos { it }
                var segDurationNanos = if (segTarget == 0f) spec.getDurationNanos(segInitVal, 0f, segInitVel) else Long.MAX_VALUE
                var resting = false
                while (true) {
                    val frameNanos = withFrameNanos { it }
                    val wallNow = System.nanoTime()
                    val idleNanos = wallNow - lastMoveEventNanos
                    val wantTarget = if (idleNanos >= RAIL_REST_MILLIS * 1_000_000L) {
                        resting = true
                        0f
                    } else {
                        resting = false
                        pendingTarget
                    }
                    val playNanos = (frameNanos - segStartNanos).coerceAtLeast(0L)
                    animVal = spec.getValueFromNanos(playNanos, segInitVal, segTarget, segInitVel)
                    animVel = spec.getVelocityFromNanos(playNanos, segInitVal, segTarget, segInitVel)
                    val needRetarget = (wantTarget == 0f && segTarget != 0f) || abs(wantTarget - segTarget) > 0.15f
                    if (needRetarget) {
                        segInitVal = animVal
                        segInitVel = animVel
                        segTarget = wantTarget
                        segStartNanos = frameNanos
                        segDurationNanos = if (wantTarget == 0f) spec.getDurationNanos(segInitVal, 0f, segInitVel) else Long.MAX_VALUE
                    } else if (resting && segTarget == 0f && playNanos >= segDurationNanos) {
                        railBounceState.floatValue = 0f
                        active = false
                        break
                    }
                    railBounceState.floatValue = animVal
                }
            }
        }
    }

    fun onDragStart() {
        if (!MotionMode.enhanced) return
        dragIdleJob?.cancel()
        if (rubberState.floatValue != 0f) {
            rubberJob?.cancel()
            val start = rubberState.floatValue
            rubberJob = scope.launch {
                animate(start, 0f, animationSpec = MotionSprings.response(0.30f, 0.72f, 0.1f)) { value, _ ->
                    rubberState.floatValue = value
                }
                rubberState.floatValue = 0f
            }
        }
    }

    fun onDragMotion(dxPx: Float, dtSeconds: Float, density: Float) {
        if (!MotionMode.enhanced) return
        val dt = dtSeconds.coerceIn(0.004f, 0.08f)
        val velocity = dxPx / dt
        val speedDp = (velocity / density.coerceAtLeast(0.1f)) / SCRUBBER_TILT_FULL_SPEED
        val targetTilt = speedDp.coerceIn(-1f, 1f) * SCRUBBER_BUBBLE_MAX_TILT
        tiltJob?.cancel()
        bubbleTiltState.floatValue += (targetTilt - bubbleTiltState.floatValue) * 0.35f
        dragIdleJob?.cancel()
        dragIdleJob = scope.launch {
            delay(SCRUBBER_DRAG_IDLE_MILLIS)
            tiltJob?.cancel()
            val start = bubbleTiltState.floatValue
            tiltJob = scope.launch {
                animate(start, 0f, animationSpec = MotionSprings.response(0.38f, 0.46f, 0.05f)) { value, _ ->
                    bubbleTiltState.floatValue = value
                }
                bubbleTiltState.floatValue = 0f
            }
        }
    }

    fun onEdgeOverpull(rawOverPx: Float, maxPullPx: Float) {
        if (!MotionMode.enhanced) return
        if (abs(rawOverPx) > 0.5f && maxPullPx > 0f) {
            rubberJob?.cancel()
            rubberState.floatValue = shelfScrubberRubberPull(rawOverPx, maxPullPx)
        } else if (rubberState.floatValue != 0f) {
            rubberJob?.cancel()
            val start = rubberState.floatValue
            rubberJob = scope.launch {
                animate(start, 0f, animationSpec = MotionSprings.response(0.38f, 0.46f, 0.1f)) { value, _ ->
                    rubberState.floatValue = value
                }
                rubberState.floatValue = 0f
            }
        }
    }

    fun onDragEnd() {
        if (!MotionMode.enhanced) return
        dragIdleJob?.cancel()
        tiltJob?.cancel()
        val startTilt = bubbleTiltState.floatValue
        if (abs(startTilt) > 0.01f) {
            tiltJob = scope.launch {
                animate(startTilt, 0f, animationSpec = MotionSprings.response(0.42f, 0.46f, 0.05f)) { value, _ ->
                    bubbleTiltState.floatValue = value
                }
                bubbleTiltState.floatValue = 0f
            }
        } else {
            bubbleTiltState.floatValue = 0f
        }
        val startRubber = rubberState.floatValue
        if (startRubber != 0f) {
            rubberJob?.cancel()
            rubberJob = scope.launch {
                animate(startRubber, 0f, animationSpec = MotionSprings.response(0.42f, 0.42f, 0.1f)) { value, _ ->
                    rubberState.floatValue = value
                }
                rubberState.floatValue = 0f
            }
        }
    }

    /** 点按轨道：把拉出去的橡皮筋先收回来（轨道的弹起跟着旋钮的移动走，不另起）。 */
    fun onTapSeek() {
        if (!MotionMode.enhanced) return
        dragIdleJob?.cancel()
        val startRubber = rubberState.floatValue
        if (startRubber != 0f) {
            rubberJob?.cancel()
            rubberJob = scope.launch {
                animate(startRubber, 0f, animationSpec = MotionSprings.response(0.36f, 0.48f, 0.1f)) { value, _ ->
                    rubberState.floatValue = value
                }
                rubberState.floatValue = 0f
            }
        }
    }
}

/** 延迟卸载拖动气泡（与退场动效对齐），平时不订阅书架滚动。 */
@Composable
internal fun rememberShelfScrubberBubbleActive(scrubbing: Boolean): State<Boolean> {
    val active = remember { mutableStateOf(scrubbing) }
    LaunchedEffect(scrubbing) {
        if (scrubbing) {
            active.value = true
        } else {
            delay(SCRUBBER_BUBBLE_UNMOUNT_MS)
            active.value = false
        }
    }
    return active
}

private const val SCRUBBER_BUBBLE_UNMOUNT_MS = 320L

@Composable
internal fun rememberShelfScrubberMotion(): ShelfScrubberMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { ShelfScrubberMotion(scope) }
}

/**
 * 书架的甩动惯性（增强档）：按松手速度算出自然滑行能走多远，就近落到某一本上，再用一条「起步正好是手指速度、
 * 越滑越慢」的减速曲线一口气滑过去——不限本数，甩得越猛滑得越远。到站时还留着一点速度，交给 Q 弹吸附弹簧
 * （[pagerBouncySnap]）从停位冲过去一点再收回来：落定时也有一下 Q 弹，冲过的量随甩动力度变化、有上限，
 * 交接时速度连续、不会先停住再被拽一下。
 * 曾经用 Pager 默认的吸附甩动并限 6 本：猛甩时惯性在第 6 本被硬掐掉；惯性停在两本之间时还要先停住、
 * 再被弹簧拽到下一本，手感一顿一顿的。
 *
 * 滑行终点越过第一本 / 最后一本时照原来的惯性滑过去，撞到头的那一刻把剩下的速度交给两头的橡皮筋
 * （[ShelfRubberBand]），整排书冲出去一点再弹回来。松手速度太小（算不上甩）时按位置就近吸附，沿用 Q 弹吸附弹簧。
 *
 * 曲线 x(s) = D·(1 − (1 − s)^p)，s = t / T：起步速度 = D·p / T。默认 p = 3、T = 3·D / v（与系统列表惯性的时长接近）；
 * T 被夹住时改 p（p = v·T / D），起步速度始终等于手指速度。
 */
@Stable
internal class ShelfGlideFling(
    private val state: PagerState,
    private val decay: DecayAnimationSpec<Float>,
    private val minVelocity: Float,
    private val density: Float,
) : TargetedFlingBehavior {
    /**
     * 上一次甩动要落到的那一本：落定（含 Q 弹）完成前被下一次手指打断时留着。紧接着的下一次甩动，只要书架离它不到
     * [GLIDE_LANDING_EPS] 本，就把它当成「现在就在这一本上」：Q 弹冲过停位那一点点时反方向甩，不会因为「离那一本只差
     * 零点几」而被当成只翻零点几本（甩了没动）。
     */
    private var landing: Int? = null

    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val pageSize = (state.layoutInfo.pageSize + state.layoutInfo.pageSpacing).toFloat()
        val last = state.pageCount - 1
        if (pageSize <= 0f || last < 0) return initialVelocity
        val position = state.pagePosition()
        val anchor = landing?.takeIf { abs(position - it) < GLIDE_LANDING_EPS }?.toFloat() ?: position
        if (abs(initialVelocity) < minVelocity) {
            val nearest = position.roundToInt().coerceIn(0, last)
            landing = nearest
            snap(nearest, initialVelocity, pageSize, onRemainingDistanceUpdated)
            landing = null
            return 0f
        }
        val reach = decay.calculateTargetValue(0f, initialVelocity) * shelfGlideReach(abs(initialVelocity) / density)
        val target = shelfGlideTarget(anchor, reach / pageSize, last)
        if (target == null) {
            // 一路滑到头：照原惯性滑，撞到边界就停下，余速交给橡皮筋；若剩得太短直接收拢到边位，免得 glide(stopAt > reach)
            val edge = if (initialVelocity > 0f) last else 0
            val toEdge = (edge - position) * pageSize
            if (abs(toEdge) < 0.5f || sign(toEdge) != sign(initialVelocity)) return initialVelocity
            landing = edge
            if (abs(reach) < abs(toEdge)) {
                snap(edge, initialVelocity, pageSize, onRemainingDistanceUpdated)
                landing = null
                return 0f
            }
            return glide(reach, initialVelocity, toEdge, 0f, onRemainingDistanceUpdated).also { landing = null }
        }
        landing = target
        val distance = (target - position) * pageSize
        if (abs(distance) >= 0.5f) {
            // 到站速度：随甩动力度变化，再按「每秒多少本」封顶（Q 弹冲过停位的量 ≈ 到站速度的 4%，最多约 0.06 本）
            val arrival = min(abs(initialVelocity) * GLIDE_ARRIVAL_SHARE, pageSize * GLIDE_ARRIVAL_MAX_PAGES)
            val landing = glide(distance, initialVelocity, distance, arrival, onRemainingDistanceUpdated)
            settle(target, landing, pageSize, onRemainingDistanceUpdated)
        }
        // 收掉逐帧累加的亚像素误差，停在整本上
        val residue = (target - state.pagePosition()) * pageSize
        if (abs(residue) >= 0.5f) scrollBy(residue)
        onRemainingDistanceUpdated(0f)
        landing = null
        return 0f
    }

    /**
     * 按 [distance] 规划减速曲线（到站速度 [arrival]）、滑到 [stopAt]（同号，|stopAt| ≤ |distance|）为止，
     * 返回停下那一刻的速度：撞到头时是撞上去的速度，滑完全程时是到站速度。
     */
    private suspend fun ScrollScope.glide(
        distance: Float,
        velocity: Float,
        stopAt: Float,
        arrival: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val curve = ShelfGlideCurve(abs(distance), abs(velocity), arrival)
        val direction = sign(distance)
        val limit = min(abs(stopAt), curve.distance)
        var traveled = 0f
        var previous = Long.MIN_VALUE
        var elapsed = 0L
        onRemainingDistanceUpdated(limit * direction)
        while (true) {
            val frame = withFrameNanos { it }
            // 松手后的第一帧就该往前走：起点往前挪半帧，不让画面在松手那一帧原地停一下；
            // 之后每帧最多推进 [GLIDE_MAX_STEP_NANOS]：书架一滑起来合成很重，偶尔掉一两帧，曲线按真实时间走就会一帧里
            // 蹿过好几本（看上去是跳页），限住步长，掉帧时只是慢一点点，位置始终连续
            elapsed += if (previous == Long.MIN_VALUE) GLIDE_LEAD_NANOS else min(frame - previous, GLIDE_MAX_STEP_NANOS)
            previous = frame
            val seconds = elapsed / 1e9f
            val next = min(curve.offsetAt(seconds), limit)
            if (next > traveled) scrollBy((next - traveled) * direction)
            traveled = next
            onRemainingDistanceUpdated((limit - traveled) * direction)
            if (traveled >= limit) return direction * curve.velocityAt(seconds)
        }
    }

    /**
     * 滑到站后的 Q 弹：已经停在 [target] 上、还带着到站速度 [velocity]，交给 Q 弹吸附弹簧冲过去一点再收回来。
     * 冲过的那点要是越过了第一本 / 最后一本，由两头的橡皮筋接住（和吸附弹簧冲过头一样）。
     */
    private suspend fun ScrollScope.settle(
        target: Int,
        velocity: Float,
        pageSize: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ) {
        if (abs(velocity) < 1f) return
        val start = (state.pagePosition() - target) * pageSize
        var previous = start
        animate(start, 0f, velocity, pagerBouncySnap(0.5f)) { value, _ ->
            scrollBy(value - previous)
            previous = value
            onRemainingDistanceUpdated(-value)
        }
    }

    /**
     * 点按进度轨道：从当前位置连续滑到 [target] 那一本。起步就是满速、越滑越慢，时长随距离缓缓增长（近的 0.3 秒多一点，
     * 一百本也只要约 0.9 秒），到站留一点速度交给 Q 弹吸附弹簧冲过去一点再收回；落在整本上。
     */
    suspend fun ScrollScope.glideTo(target: Int) {
        val pageSize = (state.layoutInfo.pageSize + state.layoutInfo.pageSpacing).toFloat()
        val last = state.pageCount - 1
        if (pageSize <= 0f || last < 0) return
        val goal = target.coerceIn(0, last)
        val distance = (goal - state.pagePosition()) * pageSize
        if (abs(distance) < 0.5f) return
        val pages = abs(distance) / pageSize
        val seconds = (SEEK_BASE_SECONDS + SEEK_LOG_SECONDS * kotlin.math.ln(1f + pages)).coerceAtMost(SEEK_MAX_SECONDS)
        // x(s) = D·(1 − (1 − s)^p) 的起步速度 = D·p / T
        val speed = abs(distance) * GLIDE_POWER / seconds
        val arrival = min(speed * SEEK_ARRIVAL_SHARE, pageSize * GLIDE_ARRIVAL_MAX_PAGES)
        val landing = glide(distance, sign(distance) * speed, distance, arrival) { }
        settle(goal, landing, pageSize) { }
        val residue = (goal - state.pagePosition()) * pageSize
        if (abs(residue) >= 0.5f) scrollBy(residue)
    }

    /** 算不上甩：按位置就近吸附到 [target]，Q 弹弹簧带着手指速度收过去。 */
    private suspend fun ScrollScope.snap(
        target: Int,
        velocity: Float,
        pageSize: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ) {
        val distance = (target - state.pagePosition()) * pageSize
        if (abs(distance) < 0.5f && abs(velocity) < 1f) return
        var previous = 0f
        animate(0f, distance, velocity, pagerBouncySnap(0.5f)) { value, _ ->
            scrollBy(value - previous)
            previous = value
            onRemainingDistanceUpdated(distance - value)
        }
    }
}

/**
 * 滑行距离相对系统惯性（样条衰减）的倍数，随松手速度（dp/s）变化：轻滑、中等力度只走得比系统惯性近得多
 * （配合 [shelfGlideTarget] 的「一本」区间，这一大段力度都只翻一本），用力甩才放开到 [GLIDE_REACH]，滑得远、能一路到头。
 * 两段之间平滑过渡，总滑行距离随速度单调增加。
 * 曾经不分轻重一律按 1.25 倍：书架上一本要拖过约半个封面宽，系统惯性按像素算本来就滑得远，轻轻一滑就过去两三本；
 * 只收短轻滑那一端时，又只剩很窄的一段力度是「一本」：再轻一点翻不过去，稍用点力就过去两本。
 */
internal fun shelfGlideReach(speedDp: Float): Float {
    val t = ((speedDp - GLIDE_LIGHT_SPEED) / (GLIDE_STRONG_SPEED - GLIDE_LIGHT_SPEED)).coerceIn(0f, 1f)
    return GLIDE_LIGHT_REACH + (GLIDE_REACH - GLIDE_LIGHT_REACH) * t * t * (3f - 2f * t)
}

/**
 * 甩动该停在哪一本。只要算得上甩（松手速度过了 [GLIDE_MIN_VELOCITY]），至少往甩的方向翻一本；
 * 自然滑行 [travelPages] 本（带符号）不到 [GLIDE_ONE_BOOK_TRAVEL] 时就只翻这一本——不管松手前手指已经拖过了多少，
 * 轻滑、中等力度都稳稳落在下一本上。滑得更远才按滑行终点就近取整；终点到了或越过第一本 / 最后一本（[last]）时返回 null：
 * 一路滑到头，交给撞边的处理。
 */
internal fun shelfGlideTarget(position: Float, travelPages: Float, last: Int): Int? {
    val forward = travelPages > 0f
    val next = if (forward) kotlin.math.floor(position).toInt() + 1 else kotlin.math.ceil(position).toInt() - 1
    if (abs(travelPages) < GLIDE_ONE_BOOK_TRAVEL && next in 0..last) return next
    val end = position + travelPages
    if (if (forward) end >= last else end <= 0f) return null
    val nearest = end.roundToInt()
    return (if (forward) maxOf(nearest, next) else minOf(nearest, next)).coerceIn(0, last)
}

/**
 * 甩动的减速曲线：走完 [distance]（px，正数），起步速度等于松手速度 [speed]（px/s，正数），到站时剩下 [arrival]（px/s）。
 * x(s) = a·T·s + (D − a·T)·(1 − (1 − s)^p)，s = t / T，a 为到站速度：前一项是匀速走的那份，后一项是减速的那份。
 * 起步速度 = a + (D − a·T)·p / T。默认 p = 3、T = 3·D / (v + 2a)；T 被夹住时反算 p（不低于 [GLIDE_MIN_POWER]），
 * 再由 p 反推 T，保证起步速度始终等于手指速度。到站速度不超过起步速度的一半、也不超过「一半路程匀速走完」的速度。
 */
internal class ShelfGlideCurve(val distance: Float, speed: Float, arrival: Float = 0f) {
    /** 到站速度（px/s）。 */
    val arrival: Float

    /** 总时长（秒）。 */
    val seconds: Float
    private val power: Float

    init {
        val d = distance.coerceAtLeast(1e-3f)
        val v = speed.coerceAtLeast(1f)
        var a = arrival.coerceIn(0f, v * 0.5f)
        val clamped = (GLIDE_POWER * d / (v + (GLIDE_POWER - 1f) * a)).coerceIn(GLIDE_MIN_SECONDS, GLIDE_MAX_SECONDS)
        a = min(a, 0.5f * d / clamped)
        val p = ((v - a) * clamped / (d - a * clamped)).coerceAtLeast(GLIDE_MIN_POWER)
        this.arrival = a
        power = p
        seconds = p * d / (v + (p - 1f) * a)
    }

    /** 第 [t] 秒走过的距离。 */
    fun offsetAt(t: Float): Float {
        val s = (t / seconds).coerceIn(0f, 1f)
        val cruise = arrival * seconds
        return cruise * s + (distance - cruise) * (1f - (1f - s).pow(power))
    }

    /** 第 [t] 秒的速度（px/s）。 */
    fun velocityAt(t: Float): Float {
        val s = (t / seconds).coerceIn(0f, 1f)
        return arrival + (distance - arrival * seconds) * power / seconds * (1f - s).pow(power - 1f)
    }
}

/** 书架的甩动：增强档按手指速度一路滑行（[ShelfGlideFling]），普通档沿用 Pager 默认吸附、不限本数。 */
@Composable
internal fun rememberShelfFling(state: PagerState): TargetedFlingBehavior {
    if (!MotionMode.enhanced || !ValueAnimator.areAnimatorsEnabled()) {
        return rememberPagerSnapFling(state, maxFlingPages = state.pageCount.coerceAtLeast(1))
    }
    val decay = rememberSplineBasedDecay<Float>()
    val density = LocalDensity.current
    return remember(state, decay, density) {
        ShelfGlideFling(state, decay, with(density) { GLIDE_MIN_VELOCITY.dp.toPx() }, density.density)
    }
}

/**
 * 搜索 / 筛选换了一批书：新出现的书不是一下子冒出来，而是从搁板上「立起来」——自下而上浮现（透明度 + 抬升 + 放大，带一点弹性），
 * 离正中（或离这一行行首）越远越晚一拍，一批书依次落座。书架与藏书阁共用。
 *
 * 只对「列表换批之后新组合出来的书」播放：[changedAt] 是列表换批的时刻（见 `rememberCoverBatchChange`，首次组合为 0），
 * 换批后 [SHELF_ARRIVAL_WINDOW_MILLIS] 内新组合的书才播；换批前就在、位置没变的书不重播，之后因滚动才组合的书直接显示。
 * [rank] 是这本书在这一批里的先后（0 = 最先）。「普通」档不播。
 */
@Composable
internal fun Modifier.shelfBatchArrival(changedAt: Long, rank: Int): Modifier {
    val enhanced = MotionMode.enhanced
    val arrives = enhanced && changedAt != 0L && SystemClock.uptimeMillis() - changedAt < SHELF_ARRIVAL_WINDOW_MILLIS
    val arrival = remember { Animatable(if (arrives) 0f else 1f) }
    LaunchedEffect(arrival) {
        if (arrival.value < 1f) {
            delay(rank.coerceIn(0, SHELF_ARRIVAL_MAX_RANK) * SHELF_ARRIVAL_STAGGER_MILLIS)
            arrival.animateTo(1f, SHELF_ARRIVAL)
        }
    }
    return if (!enhanced) this else graphicsLayer {
        val a = arrival.value
        if (a != 1f) {
            alpha = a.coerceIn(0f, 1f)
            val s = 0.86f + 0.14f * a
            scaleX = s
            scaleY = s
            translationY = (1f - a) * SHELF_ARRIVAL_RISE_DP * density
            // 以底边为轴放大：书是立在搁板上的，从板面上长起来
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    }
}

/** 一批书从换批起这么久之内新组合的才播到达动效。 */
internal const val SHELF_ARRIVAL_WINDOW_MILLIS = 600L
private const val SHELF_ARRIVAL_MAX_RANK = 5
private const val SHELF_ARRIVAL_STAGGER_MILLIS = 38L
private const val SHELF_ARRIVAL_RISE_DP = 20f
private val SHELF_ARRIVAL = MotionSprings.response<Float>(0.46f, 0.74f, 0.001f)

/**
 * 抽到正中的那一盒换高清封面：高清图解出来后叠在网格档那张上面淡入，不是一帧换掉（换图的那一下会闪、清晰度突变）；
 * [startOpaque]（下面没有网格档封面可叠）时直接不透明。[key] 变化（换了一张图）重新淡入。
 */
@Composable
internal fun Modifier.shelfHeroCoverFade(key: Any, startOpaque: Boolean): Modifier {
    val fade = remember(key) { Animatable(if (startOpaque) 1f else 0f) }
    LaunchedEffect(key) { fade.animateTo(1f, tween(HERO_COVER_FADE_MILLIS)) }
    return graphicsLayer { alpha = fade.value }
}

private const val HERO_COVER_FADE_MILLIS = 140

/** 点按进度轨道要用的连续滑行（增强档）；普通档为 null，直接用 Pager 的翻页补间。 */
@Composable
internal fun rememberShelfGlide(state: PagerState): ShelfGlideFling? {
    if (!MotionMode.enhanced || !ValueAnimator.areAnimatorsEnabled()) return null
    val decay = rememberSplineBasedDecay<Float>()
    val density = LocalDensity.current
    return remember(state, decay, density) {
        ShelfGlideFling(state, decay, with(density) { GLIDE_MIN_VELOCITY.dp.toPx() }, density.density)
    }
}

/**
 * 书架两头的橡皮筋：翻到第一本 / 最后一本还往外拉时，整排书带着阻力跟出去一点（越拉越拉不动），松手后 Q 弹回原位；
 * 甩到头、或吸附弹簧冲过头被边界挡住的那一段，也化成同样的「冲出去一点再弹回来」，而不是硬生生停住。
 * 位移只写在图层里（不重组、不重新布局）。
 *
 * 记账必须对称：越界的那段按**原始距离**（手指 / 惯性实际多走的像素）累计在 [overshoot] 里，画面位移由它按阻力曲线
 * 换算；往回走时先按原始距离一比一还回去，剩下的才交给书架翻页。曾经按「乘过阻力的位移」累计、收回时却按原始距离
 * 抵消——收回到一半位移就归零了，剩下那截被当成真实滚动交给书架，书架停在两页之间，过一会儿自己滑到第二本 /
 * 倒数第二本（真机录屏复现：甩到第一本 → 弹回 → 自己翻到 02）。
 */
@Stable
internal class ShelfRubberBand(private val maxPull: Float, private val scope: CoroutineScope) : OverscrollEffect {
    /** 越界累计的原始距离（px，带符号）。 */
    private var overshoot by mutableFloatStateOf(0f)

    /** 越界正在自己弹回（撞头后的弹回被新的手指打断，或往里甩的那一刻）：这期间往里的位移直接交给书架，不再先「还」越界量。 */
    private var recoveryJob: Job? = null

    /**
     * 撞头后弹回的一段越界量是「虚」的（原始距离按阻力曲线换算，撞得越狠越大，可达两本多），不是手指拉出来的。
     * 弹回被新的手指打断时若把它冻在那儿，下一次往里的拖动与甩动就要先「还」完这一大段，书架一动不动、
     * 甩动的前几帧被吃掉、最后停在差几百像素的位置再猛地补上一截——快速来回甩书架时表现为反应迟钝、停得不对、跳一下。
     * 所以被打断或往里甩时，越界让它自己用一条很快的弹簧弹回，位移照常交给书架。
     */
    private fun startRecovery() {
        if (overshoot == 0f) return
        recoveryJob?.cancel()
        val start = pull()
        if (start == 0f) {
            overshoot = 0f
            return
        }
        recoveryJob = scope.launch {
            animate(
                initialValue = start,
                targetValue = 0f,
                animationSpec = MotionSprings.response(RUBBER_RECOVER_RESPONSE, 1f, 0.5f),
            ) { value, _ ->
                overshoot = if (sign(value) == sign(start)) unpull(value) else 0f
            }
            overshoot = 0f
        }
    }

    private fun stopRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    /** 画面上整排书的位移：原始距离越大越拉不动，渐近 [maxPull]。 */
    private fun pull(): Float {
        val d = abs(overshoot)
        if (d == 0f) return 0f
        return (1f - 1f / (d * RUBBER_COEFFICIENT / maxPull + 1f)) * maxPull * sign(overshoot)
    }

    override val isInProgress: Boolean
        get() = overshoot != 0f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val dx = delta.x
        if (recoveryJob?.isActive == true && dx != 0f) {
            // 弹回途中：往里的位移直接交给书架（越界自己在弹回）；又往外拉就停下弹回，由手指接着拉
            if (sign(dx) != sign(overshoot)) return performScroll(delta)
            stopRecovery()
        }
        // 往回走：先把越界的那段按原始距离还回去，剩下的再交给书架
        if (overshoot != 0f && dx != 0f && sign(dx) != sign(overshoot)) {
            val back = if (abs(dx) >= abs(overshoot)) -overshoot else dx
            overshoot += back
            val rest = performScroll(Offset(dx - back, delta.y))
            return Offset(back + rest.x, rest.y)
        }
        val consumed = performScroll(delta)
        val left = dx - consumed.x
        if (abs(left) < 0.5f) return consumed
        overshoot += left
        return Offset(dx, consumed.y)
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        // 注意 performFling 返回的是「被书架用掉的」速度，剩下的 = 总速度 − 用掉的。曾经把返回值当成剩余速度：
        // 每次甩动停稳后都会再拿整个甩动速度推一次橡皮筋，书架在中间也会猛冲出去一截再弹回，像跳页、像抽搐。
        // 拉出去时松手、且没有朝书架里甩：书架本身就停在第一本 / 最后一本上，先让它不带速度地吸附，再带着手指的速度弹回。
        // 拉出去后又朝书架里甩：照常把速度交给书架——甩动的头几帧会先把拉出去的那段收回来（见 applyToScroll），
        // 再接着翻，最后的吸附按书架实际位置算，这一甩不会被吞掉，也不会停在两页之间。
        val outward = overshoot != 0f &&
            (abs(velocity.x) < RUBBER_MIN_VELOCITY || sign(velocity.x) == sign(overshoot))
        // 往里甩：越界让它自己弹回，书架一开始就往里走（不再用甩动的头几帧去「还」越界量）
        if (overshoot != 0f && !outward) startRecovery()
        val left = if (outward) {
            performFling(Velocity.Zero)
            velocity
        } else {
            velocity - performFling(velocity)
        }
        recoveryJob?.join()
        val start = pull()
        if (start == 0f && abs(left.x) < RUBBER_MIN_VELOCITY) return
        // 一路滑到头撞上的（之前没拉出去）：余速多带一些、弹簧软一点，像撞上书立被轻轻推回来；
        // 冲出去的最远距离按临界阻尼弹簧的峰值（v / (e·ω)）封顶，再猛也不会拉满卡住
        val impact = !outward && start == 0f
        val initialVelocity = if (impact) {
            val omega = 2f * PI_F / (RUBBER_IMPACT_RESPONSE * MotionSprings.SPEED)
            val cap = maxPull * RUBBER_IMPACT_MAX_FRACTION * kotlin.math.E.toFloat() * omega
            (left.x * RUBBER_IMPACT_SHARE).coerceIn(-cap, cap)
        } else {
            left.x * RUBBER_VELOCITY_SHARE
        }
        // 弹回在「画面位移」上做：越界原始距离可能很大（甩到头时惯性全压进来），直接对它做弹簧，画面会拉满卡一阵
        // 再猛地弹回；这里从当前看到的位移起弹，每帧再换算回原始距离（甩动已结束，没有书架的滚动需要它还回去）。
        // 弹回不越过原位（临界阻尼）：越过去就成了「往另一头拉出去」，被下一次手指打断时会留下一段方向相反的越界量，
        // 整排书歪在一边、后面的拖动先被它吃掉——快速连续甩动时偶发的「跳页 / 没停正」就是它。
        try {
            animate(
                initialValue = start,
                targetValue = 0f,
                initialVelocity = initialVelocity,
                animationSpec = if (impact) {
                    MotionSprings.response(RUBBER_IMPACT_RESPONSE, 1f, 0.5f)
                } else {
                    MotionTokens.releaseCalm(0.5f)
                },
            ) { value, _ ->
                overshoot = if (start == 0f || sign(value) == sign(start)) unpull(value) else 0f
            }
        } finally {
            // 正常结束时精确归零；被新的手指打断时让剩下的越界自己弹回（见 [startRecovery]），不冻在那儿
            if (abs(pull()) < 0.5f) overshoot = 0f else startRecovery()
        }
    }

    /** [pull] 的反算：画面位移 [display] 对应的原始越界距离。 */
    private fun unpull(display: Float): Float {
        val fraction = (abs(display) / maxPull).coerceAtMost(RUBBER_MAX_FRACTION)
        if (fraction == 0f) return 0f
        return (1f / (1f - fraction) - 1f) * maxPull / RUBBER_COEFFICIENT * sign(display)
    }

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0) { translationX = pull() }
            }
        }
    }
}

/**
 * 书架上手指一直按着，就一直算「手指引起的」：触觉门面只认最近 1.5 秒内的触摸，而全局宿主只在按下的那一刻记一次，
 * 按住进度轨道（或在书架上慢慢拖）超过 1.5 秒后，越过每一本的轻点都会被当成「不是手指引起的」而丢掉。
 * 这里在按住期间的每个指针事件上续一次（只观察、不消费）。
 */
internal fun Modifier.shelfTouchIntent(onTouchDown: () -> Unit = {}): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.changedToDown() }) onTouchDown()
            if (event.changes.any { it.pressed }) MotionIntent.noteTouch()
        }
    }
}

/** 书架的越界效果：增强档是两头的橡皮筋，普通档保持系统默认。 */
@Composable
internal fun rememberShelfOverscroll(): OverscrollEffect? {
    if (!MotionMode.enhanced) return rememberOverscrollEffect()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(density, scope) { ShelfRubberBand(with(density) { RUBBER_MAX_PULL.dp.toPx() }, scope) }
}

/**
 * 精细拖动：手指从按下处往上移得越远，拖动越慢（1、1/2、1/4、1/10），一百多本的书架上也能停准。
 * 返回速度倍率。
 */
internal fun shelfScrubSpeed(upDistancePx: Float, density: Float): Float {
    val dp = upDistancePx / density
    return when {
        dp < SCRUB_SLOW_1 -> 1f
        dp < SCRUB_SLOW_2 -> 0.5f
        dp < SCRUB_SLOW_3 -> 0.25f
        else -> 0.1f
    }
}

/**
 * 进度轨道的浓度（[TRACK_IDLE_PRESENCE]..1）：书架静止时淡到似有似无；一滑动、一拖动就浮现，
 * 停下后再等一会儿才慢慢淡回去（连着翻几下时不会一闪一闪）。「普通」档恒为 1。
 */
@Composable
internal fun rememberTrackPresence(active: Boolean): State<Float> {
    // 「普通」档不做浮现动画：轨道始终是完整浓度
    if (!MotionMode.enhanced) return remember { mutableFloatStateOf(1f) }
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            awake = true
        } else {
            delay(TRACK_REST_DELAY_MILLIS)
            awake = false
        }
    }
    return androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (awake) 1f else TRACK_IDLE_PRESENCE,
        animationSpec = if (awake) MotionTokens.fadeIn(0.001f) else MotionTokens.value(0.001f),
        label = "shelfTrackPresence",
    )
}

/** 旋钮的不透明度：跟着轨道浓度走，但下限高得多（它是当前那一盒的封面，静止时也要认得出来）。 */
internal fun trackKnobAlpha(presence: Float): Float {
    val awake = ((presence - TRACK_IDLE_PRESENCE) / (1f - TRACK_IDLE_PRESENCE)).coerceIn(0f, 1f)
    return KNOB_IDLE_ALPHA + (1f - KNOB_IDLE_ALPHA) * awake
}

/**
 * 进度轨道旋钮被按住时的放大量（0 = 松开，1 = 按住）：按下 Q 弹地鼓起，松手轻弹回落。「普通」档不放大。
 */
@Composable
internal fun rememberTrackKnobLift(pressed: Boolean): State<Float> =
    androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && MotionMode.enhanced) 1f else 0f,
        animationSpec = if (pressed) {
            MotionSprings.response(0.34f, 0.46f, 0.001f)
        } else {
            MotionSprings.response(0.42f, 0.42f, 0.001f)
        },
        label = "shelfKnobLift",
    )

/**
 * 打字机：ADV 对话框的台词一个字一个字地出来（约 35ms 一字，整句最长 0.9 秒）；换一句从头再打。
 * 「普通」档直接显示整句。返回当前应显示的前缀长度，只在字数变化时触发重组。
 */
@Composable
internal fun rememberTypewriterLength(text: String): Int {
    if (!MotionMode.enhanced) return text.length
    val shown = remember(text) { Animatable(0f) }
    LaunchedEffect(text) {
        val millis = (text.length * TYPE_MILLIS_PER_CHAR).coerceAtMost(TYPE_MAX_MILLIS)
        shown.animateTo(text.length.toFloat(), tween(millis, easing = LinearEasing))
    }
    val length by remember(shown) { derivedStateOf { shown.value.toInt() } }
    return length.coerceIn(0, text.length)
}

/** 书脊朝外时盒子转过的角度：留一条封面边，看得出是立体的盒子。 */
internal val SHELF_SPINE_TURN = Math.toRadians(86.0).toFloat()

/** 转角小于这个值时书脊几乎侧立成一条线，直接不画。 */
private const val HIDDEN_SPINE_TURN = 0.02f

/** 透视相机距离（× density）：足够远，转动时盒子不夸张变形。 */
internal const val SHELF_FACE_CAMERA = 28f

/** 离开中心后盒子缩小的比例（往后退一点）。 */
private const val SIDE_SHRINK = 0.1f

/** 被选中的那一盒：影响范围（本）、放大比例、顶部外倾角度（度）、姿态层的透视距离（× density）。 */
private const val HERO_RANGE = 0.85f
private const val HERO_SCALE = 0.07f
internal const val HERO_TILT_DEGREES = 5f
internal const val POSE_CAMERA_DISTANCE = 22f

/**
 * 斜轨：偏转角（越大越斜）、透视相机距离（× 书架宽）、视线高出搁板线（× 盒高）、近处 / 远处缩放上下限。
 * 按用户要求不再倾斜：偏转角为 0 时整套投影是恒等的（不缩放、不抬升、不平移），书排成一条水平直线；
 * 投影接口保留，各处照常调用。
 */
private const val RAIL_YAW = 0f
private const val RAIL_CAMERA = 1.4f
internal const val RAIL_EYE = 0.9f
private const val RAIL_MIN_SCALE = 0.62f
private const val RAIL_MAX_SCALE = 1.12f

/** 抽出来的那一盒在屏幕上最多放大到封面宽度的多少倍（抽出放大；书架放平后没有近处放大），用来选封面解码档位。 */
internal const val SHELF_HERO_MAX_ZOOM = 1f + HERO_SCALE

/** 空气透视：最远处的雾有多浓。 */
private const val RAIL_FOG = 0.24f

/** 当前那盒的停靠位置（相对书架中心，× 书架宽；负 = 偏左）。书架放平后停在正中。 */
internal const val RAIL_ANCHOR = 0f

/** 正中那一盒侧转露出书脊的角度。 */
internal val HERO_YAW = Math.toRadians(16.0).toFloat()

/** 转得浅时左侧的书经过正对换边：在离正中这么多本以内转完（这一段封面几乎正对，宽度变化很小）。 */
private const val LEFT_FLIP_RANGE = 0.3f


/** 悬浮投影：模糊半径、向右偏移、向下错开（dp）与浓度倍数。 */
private const val ELEVATION_BLUR = 16f
private const val ELEVATION_SHIFT = 10f
private const val ELEVATION_DROP = 14f
private const val ELEVATION_STRENGTH = 1.6f

/** 书后柔光：半径（相对宽度）与纵向压扁比例；书脚暗带：半径（相对宽度）与纵向压扁比例。 */
private const val WALL_GLOW_RADIUS = 0.75f
private const val WALL_GLOW_SQUASH = 0.62f
private const val AO_RADIUS = 0.62f
private const val AO_SQUASH = 0.09f

/** 背板：高度（相对盒子高度）、底部暗角高度（dp）；搁板下软影高度（dp）。 */
private const val BOARD_UNDER_SHADOW = 16f

/** 两侧盒子正面 / 书脊背光时最多压暗多少。 */
internal const val FRONT_SHADE = 0.42f
internal const val SPINE_SHADE = 0.5f

/** 反光带：正对时的位置、随转动滑过的距离、宽度与倾斜（均为封面宽 / 高的比例）。 */
private const val GLOSS_REST = 0.18f
private const val GLOSS_TRAVEL = 0.55f
private const val GLOSS_WIDTH = 0.32f
private const val GLOSS_SLANT = 0.55f

/** 甩动侧倾：速度达到这么多本 / 秒时倾斜满格。 */
private const val LEAN_FULL_SPEED = 8f
private const val LEAN_DEGREES = 3.5f

/** 层级只按最近的若干本区分；被抽出来的那一盒额外抬高的层级。 */
private const val MAX_Z_DEPTH = 50f
private const val HERO_Z = 200f

/** 正面保持倒角裁切的范围（离当前位置多少本以内）。 */
private const val FRONT_CLIP_RANGE = 0.75f

/** 画倒影的范围（离当前位置多少本以内；1 本以内满强度，之后线性淡出）。 */
private const val REFLECTION_RANGE = 1.8f

/** 倒影遮罩向离屏层外多盖出的宽度（dp）。 */
private const val REFLECTION_MASK_BLEED = 2f

/** 接触阴影高度相对倒影高度的比例，宽度相对盒子宽度的比例。 */
private const val SHADOW_HEIGHT_RATIO = 0.42f
private const val CONTACT_SPREAD = 1.1f

/** 正中那一盒脚下那汪光：横向半径（× 书架宽）与浓度（× 光晕色本身的透明度）。 */
private const val POOL_RADIUS = 0.4f
private const val POOL_STRENGTH = 0.8f

/** 进度轨道上圆点之间的最小间距（dp），更密时改画刻度（点挨得太近会连成一串）。 */
private const val TRACK_MIN_DOT_SPACING = 16f

/** 进度轨道：线宽、两端伸出旋钮行程外的长度、两端渐隐的长度（dp）。 */
private const val TRACK_LINE_WIDTH = 2f
private const val TRACK_OVERHANG = 14f
private const val TRACK_END_FADE = 30f

/** 进度轨道各部分的浓度（再乘墨色本身的透明度与在场感）：未翻到 / 翻过的线，未翻到 / 翻过的刻度。 */
private const val TRACK_REST_ALPHA = 0.14f
private const val TRACK_PASSED_ALPHA = 0.26f
private const val TRACK_REST_MARK_ALPHA = 0.24f
private const val TRACK_PASSED_MARK_ALPHA = 0.44f

/** 刻度：小点半径、短 / 长刻度的半长、刻度线宽（dp），以及每多少本一道长刻度。 */
private const val TRACK_DOT_RADIUS = 1.6f
private const val TRACK_MINOR_TICK = 3f
private const val TRACK_MAJOR_TICK = 5f
private const val TRACK_TICK_WIDTH = 1f
private const val TRACK_MAJOR_EVERY = 50

/** 书架静止时轨道的浓度、停下后多久开始淡回去；旋钮静止时的不透明度。 */
private const val TRACK_IDLE_PRESENCE = 0.5f
private const val TRACK_REST_DELAY_MILLIS = 900L
private const val KNOB_IDLE_ALPHA = 0.82f

/** 点按轨道跳转：离当前超过这么多本就先瞬间跳近，最后用弹簧滑完的本数；飞行弹簧与果冻 Q 弹参数。 */
private const val SEEK_BASE_SECONDS = 0.34f
private const val SEEK_LOG_SECONDS = 0.12f
private const val SEEK_MAX_SECONDS = 0.95f
private const val SEEK_ARRIVAL_SHARE = 0.10f
private const val SCRUBBER_RUBBER_COEFF = 0.55f
internal const val SCRUBBER_MAX_RUBBER_DP = 14f
private const val SCRUBBER_TILT_FULL_SPEED = 1600f
private const val SCRUBBER_BUBBLE_MAX_TILT = 6f
private const val TRACK_BOUNCE_HEIGHT = 1f
private const val TRACK_BOUNCE_REACH = 58f
private const val TRACK_BOUNCE_STEP = 5f
private const val RAIL_FULL_SPEED = 1.1f
private const val RAIL_MAX_BOUNCE = 6f
private const val SCRUBBER_DRAG_IDLE_MILLIS = 50L

/**
 * 橡皮筋：画面上最多能拉出去多远（dp）；阻力曲线系数（与 iOS 相同的 0.55：原始距离 = 最大位移时画面只走出约三成）；
 * 松手弹回时带上的手指速度比例，以及甩到头时余速小于这个值（px/s）就不再弹。
 */
private const val RUBBER_MAX_PULL = 140f
private const val RUBBER_RECOVER_RESPONSE = 0.22f
private const val RUBBER_COEFFICIENT = 0.55f
private const val RUBBER_VELOCITY_SHARE = 0.3f

/** 反算原始距离时画面位移最多按最大位移的这个比例算（再往上原始距离趋于无穷）。 */
private const val RUBBER_MAX_FRACTION = 0.98f
private const val RUBBER_MIN_VELOCITY = 50f

/**
 * 一路滑到头撞上时：带上余速的比例、弹回弹簧的响应时间（秒，临界阻尼，比松手弹回软一点），
 * 冲出去的最远距离（占最大位移的比例）。
 */
private const val RUBBER_IMPACT_SHARE = 0.8f
private const val RUBBER_IMPACT_RESPONSE = 0.42f
private const val RUBBER_IMPACT_MAX_FRACTION = 0.4f

/**
 * 甩动惯性：用力甩时滑行距离相对系统惯性（样条衰减）的倍数，轻滑时的倍数，以及从轻到重过渡的松手速度区间（dp/s）；
 * 自然滑行不到这么多本时只翻一本；减速曲线的幂（3 = 时长与系统惯性接近）与时长上下限（秒）；时长被夹住后幂次的下限；
 * 起点前移量（纳秒，约半帧）。按这台 3.5x 的手机算：约 2000dp/s 以下都只翻一本，2400dp/s 三本，3000dp/s 约八本，
 * 4000dp/s 二十来本。
 */
private const val GLIDE_REACH = 1.25f
private const val GLIDE_LIGHT_REACH = 0.25f
private const val GLIDE_LIGHT_SPEED = 2000f
private const val GLIDE_STRONG_SPEED = 4000f
private const val GLIDE_ONE_BOOK_TRAVEL = 1.5f
private const val GLIDE_POWER = 3f
private const val GLIDE_MIN_SECONDS = 0.16f
private const val GLIDE_MAX_SECONDS = 2.4f
private const val GLIDE_MIN_POWER = 1.5f

/**
 * 甩动落定的 Q 弹：到站速度占松手速度的比例、到站速度上限（本 / 秒）。吸附弹簧（阻尼比 0.7）从停位带着速度 a 出发，
 * 冲过的最远距离约 0.04·a 秒：上限 1.5 本 / 秒 ≈ 冲过 0.06 本，与慢慢松手时的 Q 弹吸附同一个量级。
 */
private const val GLIDE_ARRIVAL_SHARE = 0.25f
private const val GLIDE_ARRIVAL_MAX_PAGES = 1.5f
private const val GLIDE_LEAD_NANOS = 8_000_000L
private const val GLIDE_MAX_STEP_NANOS = 34_000_000L
private const val GLIDE_LANDING_EPS = 0.14f

/**
 * 松手速度（dp/s）过了这个值就算「甩」，至少翻一本；更慢的松手按位置就近吸附（拖过半本才翻）。
 * 比 Pager 默认的 400dp/s 低：书架上一本要拖过约半个封面宽，很轻的一滑既拖不过半本、也到不了 400，会被吸回原位。
 */
private const val GLIDE_MIN_VELOCITY = 200f

/** 精细拖动：手指上移超过这些距离（dp）时依次降到 1/2、1/4、1/10 速。 */
private const val SCRUB_SLOW_1 = 48f
private const val SCRUB_SLOW_2 = 110f
private const val SCRUB_SLOW_3 = 180f

/** 一排书两端留出的余量（dp）：整排平移贴边时让第一本 / 最后一本不顶着屏幕边。 */
internal const val BOOKEND_WIDTH = 9f
internal const val BOOKEND_CLEARANCE = 8f

/** 每盒书的个性：高矮差异上限、歪着的书的角度。 */
private const val SIZE_VARIANCE = 0.045f
private const val REST_LEAN_DEGREES = 1.3f

/** 打字机：每字时长与整句上限（毫秒）。 */
private const val TYPE_MILLIS_PER_CHAR = 22
private const val TYPE_MAX_MILLIS = 450
private const val RAIL_REST_MILLIS = 70L

/**
 * 书架各层的全局入场次序（复用宿主 Tab 的入场节拍）：
 * 搁板与正中主书盒同步升起，两侧书脊按距正中距离逐本跟上，随后是下方 ADV 信息卡片与底部进度轨道。
 */
internal const val SHELF_CASE_ENTRANCE_INDEX = 1
internal const val SHELF_INFO_ENTRANCE_INDEX = 4
internal const val SHELF_SCRUBBER_ENTRANCE_INDEX = 5

internal fun shelfBookEntranceIndex(page: Int, settledPage: Int): Int =
    SHELF_CASE_ENTRANCE_INDEX + abs(page - settledPage)

/**
 * 「我的书架」卡的出现进度（0 = 没有，1 = 完整出现）：窗口宽度越过「放得下两张卡」的阈值时（平板旋转、分屏）卡片滑出 / 淡出，
 * 对话框同步让出 / 收回宽度，不是一下子冒出来、对话框一下子变窄。「普通」档直接到位。
 */
@Composable
internal fun rememberShelfSummaryReveal(wide: Boolean): State<Float> {
    if (!MotionMode.enhanced) return rememberUpdatedState(if (wide) 1f else 0f)
    return animateFloatAsState(
        targetValue = if (wide) 1f else 0f,
        animationSpec = MotionSprings.response(0.36f, 0.92f, 0.001f),
        label = "shelfSummaryReveal",
    )
}
