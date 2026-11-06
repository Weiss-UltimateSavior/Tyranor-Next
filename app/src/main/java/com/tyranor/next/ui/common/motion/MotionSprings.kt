package com.tyranor.next.ui.common.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * 全应用动效的弹簧 token（只描述「怎么动」，不涉及任何外观）。
 *
 * 写法参考 SwiftUI 的 `Spring(duration:bounce:)`：
 * - 刚度 k = (2π / duration)²（质量 1），duration 是一个振荡周期；
 * - bounce ≥ 0：阻尼比 = 1 − bounce（0 = 临界阻尼不过冲，越大回弹越多）；
 * - bounce < 0：阻尼比 = 1 / (1 + bounce)（过阻尼，越负越「黏」）。
 *
 * 系统「动画时长缩放」为 0 时 Compose 的弹簧会瞬间完成，因此这里无需单独处理减弱动效。
 */
object MotionSprings {

    /**
     * 全局速度系数：所有「增强」动效的周期统一乘以它（< 1 = 更快）。为易用性整体略微加快约 12%，
     * 只需改这一处即可整体调节节奏。
     */
    const val SPEED: Float = 0.88f

    /** 一个周期 [durationMillis] 对应的弹簧刚度（已乘全局速度系数 [SPEED]）。 */
    fun stiffnessFor(durationMillis: Int): Float {
        val seconds = (durationMillis.coerceAtLeast(1) * SPEED) / 1000.0
        val omega = 2.0 * PI / seconds
        return (omega * omega).toFloat()
    }

    /** 「时长 + 回弹」写弹簧。 */
    fun <T> spring(
        durationMillis: Int = 500,
        bounce: Float = 0f,
        visibilityThreshold: T? = null,
    ): SpringSpec<T> {
        val b = bounce.coerceIn(-0.99f, 0.95f)
        val damping = if (b >= 0f) 1f - b else 1f / (1f + b)
        return spring(
            dampingRatio = damping,
            stiffness = stiffnessFor(durationMillis),
            visibilityThreshold = visibilityThreshold,
        )
    }

    /** 用「响应时间（秒）+ 阻尼比」写弹簧（SwiftUI response / dampingFraction）。 */
    fun <T> response(
        response: Float,
        dampingRatio: Float,
        visibilityThreshold: T? = null,
    ): SpringSpec<T> = spring(
        dampingRatio = dampingRatio,
        stiffness = stiffnessFor((response * 1000f).toInt()),
        visibilityThreshold = visibilityThreshold,
    )

    /** 不回弹，平滑停住。 */
    fun <T> smooth(durationMillis: Int = 500, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(durationMillis, 0f, visibilityThreshold)

    /** 一点点回弹（0.1）。 */
    fun <T> crisp(durationMillis: Int = 300, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(durationMillis, 0.1f, visibilityThreshold)

    /** 利落、带一点回弹（0.15）：内容替换、数字滚动、尺寸变化的默认值。 */
    fun <T> snappy(durationMillis: Int = 400, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(durationMillis, 0.15f, visibilityThreshold)

    /** 明显回弹（0.3）：出现、弹入。 */
    fun <T> bouncy(durationMillis: Int = 500, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(durationMillis, 0.3f, visibilityThreshold)

    /** Q 弹（0.42）：小元素的按压回弹、图标弹一下。 */
    fun <T> jelly(durationMillis: Int = 450, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(durationMillis, 0.42f, visibilityThreshold)

    /** 淡入淡出、模糊这类「效果」通道：临界阻尼，不过冲。 */
    fun <T> effects(durationMillis: Int = 300, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(durationMillis, 0f, visibilityThreshold)

    /**
     * 页面侧推的节奏：响应 0.42s 的临界阻尼弹簧（iOS 侧推手感），用于 Compose 内的整页水平移动。
     */
    fun <T> push(visibilityThreshold: T? = null): SpringSpec<T> =
        response(0.42f, 1f, visibilityThreshold)

    /**
     * [push] 弹簧的缓动近似（三次贝塞尔拟合，480ms 内误差约 1%）。
     * Activity 窗口转场只能用插值器，与 `res/interpolator/page_push.xml` 数值一致。
     */
    val PushEasing: Easing = CubicBezierEasing(0.25f, 0.24f, 0.13f, 1f)

    /** [PushEasing] 对应的时长。 */
    const val PushDurationMillis: Int = 420
}
