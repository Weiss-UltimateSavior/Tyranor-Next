package com.tyranor.next.ui.common.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「液态玻璃 · 透镜」底栏的光学 / 运动 / 尺寸参数契约（单一来源）。
 *
 * 数值取自分析报告《Tyranor-Next × Legado：液态玻璃底栏对比与高保真复刻方案》§6.3 记录的
 * 参考实现默认档（blur 8dp / 表面 alpha 40% / 栏体透镜 24dp），全部保持默认值，
 * 不随用户壁纸或主题改写。**代码为本项目独立实现**，只采用参考实现中不受版权保护的
 * 参数与算法；实现差异逐条记录在 `docs/液态玻璃增强计划方案.md` §6。
 *
 * 四项适配（同上文档 §6 D4，报告 §8.5 要求逐项记录）：
 * [releaseThreshold] 固定为**五项参考布局**的取值（0.10），而不是本应用四项时的 `0.075`，
 * 以保持参考手感。
 *
 * **不含速度形变参数**：参考实现那套 `scaleX / (1 − clamp(v/10 × 0.75))` 有方向性
 * （索引增大被横向拉长、减小则相反），会造成「点左边正常扩大、点右边左右拉伸」的不统一观感，
 * 已按用户要求整条移除——形变只保留「手指按压放大」。
 */
@Immutable
data class GlassBottomBarSpec(
    // ---- 几何（参考值：栏高 64dp / 内边距 4dp / 透镜 56dp / 单槽最小宽 76dp）----
    val barHeight: Dp = 64.dp,
    val barInnerPadding: Dp = 4.dp,
    val lensHeight: Dp = 56.dp,
    val tabMinWidth: Dp = 76.dp,
    val hostHorizontalPadding: Dp = 16.dp,
    val hostBottomPadding: Dp = 12.dp,
    val iconSize: Dp = 26.dp,
    // ---- 光学（参考默认：blur 8dp / 表面 alpha 40% / 栏体透镜 24dp / 透镜 10dp×p、14dp×p）----
    /**
     * 栏体模糊半径（浅色 / 深色分档）。
     *
     * 方案给的起点为浅色 18dp / 深色 16dp，实测会把背景糊成一块平板、观感「毫无玻璃感」。
     * 按用户实测目标逐步下调：方案起点 18/16dp → 9dp → 6dp → 4dp → **3dp**（全局）。
     * 低模糊不仅更通透，也让图标边缘保留高频对比——色散（RGB 采样偏移）才有东西可分离。
     */
    val barBlurRadiusLight: Dp = 3.dp,
    val barBlurRadiusDark: Dp = 3.dp,
    /**
     * 深色档表面不透明度：烟黑遮罩，压低亮度但**必须仍能透出背景内容**。
     *
     * 首版按方案取 0.52，实测把模糊内容压没了、观感退化成一块平板（用户实测反馈），
     * 收到「轻微遮罩」档 0.34。
     */
    val barSurfaceAlphaDark: Float = 0.34f,
    /**
     * 浅色档表面不透明度：高透乳白。
     *
     * 原 0.62 偏实（像实心白胶囊），方案目标为「背景颜色与分区仍可辨认」；
     * 实测目标为「轻微浅色遮罩」→ 0.34（与深色档同档位，两侧观感一致）。
     */
    val barSurfaceAlphaLight: Float = 0.34f,
    /** 栏体 colorControls：亮度 / 对比度 / 饱和度（浅深成对，替代固定 vibrancy 1.5）。 */
    val barBrightnessLight: Float = 0.03f,
    val barBrightnessDark: Float = -0.03f,
    val barContrastLight: Float = 0.96f,
    val barContrastDark: Float = 0.88f,
    val barSaturationLight: Float = 1.18f,
    val barSaturationDark: Float = 1.10f,
    /**
     * 栏体边缘高光强度（浅 / 深）。
     *
     * 浅色档**不能太高**：高光画在最外侧几 dp，而色散彩虹带也在边缘——0.70 会把彩虹带盖住
     * （用户实测「浅色模式色散像没了」）。收到 0.42 后亮边仍在，但不遮色散。
     */

    val barHighlightAlphaLight: Float = 0.42f,
    val barHighlightAlphaDark: Float = 0.30f,
    /**
     * 栏体内阴影（**浅色档专属**）：上下边缘那圈「玻璃厚度」。
     *
     * 浅色档栏体是白底 + 白色高光，白光压白底等于看不见——参考图里上下边缘之所以有效果，
     * 靠的是贴着边缘一道柔和的内暗边。深色档不需要（深底本身有对比），因此 alpha 为 0。
     */
    val barInnerShadowRadiusLight: Dp = 10.dp,
    val barInnerShadowAlphaLight: Float = 0.12f,
    /** 浅色档栏体发丝描边不透明度（比经典档略强，用于勾出上下轮廓）。 */
    val barEdgeStrokeAlphaLight: Float = 0.14f,
    /**
     * 栏体内部折射（液态玻璃质感）。
     *
     * 方案文档曾要求「A 层默认取消整栏 lens」以消除横向涂抹；但用户实测目标明确要求
     * 「轻微模糊 + 轻微遮罩 + **内部折射**」，因此保留一档**克制**的整栏折射：
     * 高度取 [barLensHeight]、位移取 [barLensAmount]：既要看得见（首版 3dp 实测「折射完全丢失」），
     * 又不能过大（24dp 会整栏横向涂抹）。当前 12dp/8dp 用于形成上下边缘那条连贯彩虹带。
     */
    val barLensHeight: Dp = 12.dp,
    val barLensAmount: Dp = 8.dp,
    /**
     * 栏体是否启用色散（**开启**）。
     *
     * 目标观感（用户提供的参考图）：上下边缘有一条**连贯、自然**的彩虹带。
     * 色散必须挂在**整条栏体**上——栏体又宽又矮，上下边缘是长直边，色散沿边连续铺开；
     * 反之挂在滑块那颗小胶囊上只会得到上下两段孤立圆弧、外加左右两端各一道，
     * 观感是「异常彩虹纹」（用户实测反馈）。
     */
    val barLensChromatic: Boolean = true,
    // ---- 移动透镜：静止端点与按压端点分离（方案 §静止与按压光学分离）----
    /**
     * 静止态折射：未按压也有可见曲面。
     *
     * 滑块**不做色散**（见 [GlassBottomBarSpec.movingLensChromatic]）：它只有一槽宽，
     * 色散在它身上会断成上下两段弧并波及左右两端，与参考图不符。
     */
    val restRefractionHeight: Dp = 6.dp,
    val restRefractionAmount: Dp = 8.dp,
    /**
     * **浅色档专属**的色散增强系数（深色档恒为 1，不受影响）。
     *
     * 浅底上图标的明暗对比本来就低，再叠一层浅色遮罩后，色散（RGB 采样偏移）几乎没有可分离的
     * 细节——实测「浅色档滑块压到别的图标时看不到彩色分离、上下色散带也太淡」。
     * 因此浅色档把**栏体**折射的高度与位移整体放大这一档，深色档完全不动。
     *
     * **只作用于栏体（上下色散带），不作用于移动滑块**：滑块只有一槽宽，放大后图标会被拉出
     * 明显的彩色拖影（用户实测「用力过猛」），滑块保持基准折射量即可。
     */
    val lightDispersionBoost: Float = 1.4f,
    /**
     * 移动滑块是否启用色散：**开启**。
     *
     * 用户要求「滑块碰到左右图标时必须有色散」（参考图二：被覆盖图标的边缘有彩边）。
     * 与栏体那条「上下边缘的连贯长带」分工不同：
     * - 栏体（又宽又矮的长直边）→ 上下连贯彩虹带，见 [barLensChromatic]；
     * - 滑块（一槽宽小胶囊）→ 图标附近局部的色散。
     * 两者都开不会互相打断——前提是滑块的折射量保持在本档位（6/8 → 按压 10/12），
     * 早前一版把滑块拉到 9/11 → 14/16 时弧线过粗，才会出现「怪且中间断」的观感。
     */
    val movingLensChromatic: Boolean = true,
    /** 按压态折射：加厚。 */
    val pressedRefractionHeight: Dp = 10.dp,
    val pressedRefractionAmount: Dp = 12.dp,
    /** 静止 / 按压的透镜高光。 */
    val lensHighlightAlphaRest: Float = 0.28f,
    val lensHighlightAlphaPressed: Float = 0.80f,
    /** 静止 / 按压的内阴影（半径 / 不透明度）。 */
    val lensInnerShadowRadiusRest: Dp = 2.dp,
    val lensInnerShadowRadiusPressed: Dp = 8.dp,
    val lensInnerShadowAlphaRest: Float = 0.18f,
    val lensInnerShadowAlphaPressed: Float = 0.75f,
    /** 静止 / 按压的外投影：轻外投影，不主导玻璃感。 */
    val lensShadowAlphaRest: Float = 0.12f,
    val lensShadowAlphaPressed: Float = 0.45f,
    /** 静止透镜覆盖：浅色黑 / 深色白 5%，乘 (1 − p)。 */
    val staticLensCoverAlpha: Float = 0.05f,
    /** 按压透镜覆盖：黑 2% × p。 */
    val pressedLensCoverAlpha: Float = 0.02f,
    /** 按压光斑整体提亮：纯白 6% × p。 */
    val pressVeilAlpha: Float = 0.06f,
    /** 按压光斑柔光：纯白 12% × p。 */
    val pressGlowAlpha: Float = 0.12f,
    // ---- 运动（参考值：按压缩放 78/56、副本图标 1→1.2、整栏 16dp/栏宽、横向跟随 ±4dp）----
    /**
     * 按压体积（横 / 纵分档，方案 §运动参数与时序）。
     *
     * 旧的单一 `pressedScale = 78/56` 展开为两轴独立值，便于分别调「膨胀观感」。
     */
    val pressedScaleX: Float = 78f / 56f,
    val pressedScaleY: Float = 78f / 56f,
    /** 赴按动力学：远距按下以正常尺寸高速抵达手指（约 50–80ms 覆盖最远三槽）。 */
    val pressJumpStiffness: Float = 6_000f,
    val pressJumpDampingRatio: Float = 1.0f,
    /** 跟手动力学：到位后的连续跟手。 */
    val pressTrackingStiffness: Float = 2_400f,
    val pressTrackingDampingRatio: Float = 1.0f,
    /** 到位阈值（索引单位）：进入该距离才把材质推到按压态。 */
    val pressArriveThreshold: Float = 0.08f,
    /**
     * 按在滑块**以外**时，按住多久才把滑块「抓」到手指下（毫秒）。
     *
     * 这不是 Compose 的长按判定，也不参与任何反馈时机：触觉与选择仍即时发生。
     * 它只决定「滑块要不要跑过来」——
     * - **轻点**（未到阈值就松手）→ 完全走 PR81 的路径：`settleAt(pulse = true)`，
     *   起胀与滑行同时开始的单相运动，最顺；
     * - **按住不放**或**开始拖动** → 立刻把滑块抓到手指出（用户要求的「按下即跟随」）。
     */
    val grabDelayMillis: Long = 110L,
    val iconScaleOnPress: Float = 1.2f,
    val barPressScaleDelta: Dp = 16.dp,
    /** 整栏按压缩放增量的上限，避免极窄窗口下 `1 + 16dp/宽度` 被放大成畸形尺寸。 */
    val barPressScaleDeltaMax: Float = 0.05f,
    /**
     * 判定按压「已经看得见」的压力阈值。
     *
     * `settleAt(pulse = true)` 在目标本来就已到位时，若第一帧就把压力目标改回 0，
     * 这一下按压等于没发生；因此收货时机要等到压力涨过该阈值之后（见 §6 D20）。
     */
    val pulseVisibleThreshold: Float = 0.5f,
    /**
     * 透镜视觉宽度上限（见 §6 D19）。
     *
     * 手机上一个槽位就是 76dp，透镜即整槽；但平板/横屏拉伸后单槽会被拉到 200dp 以上，
     * 若透镜仍等于整槽，按压时会膨胀成一块巨大胶囊（实测违和感强烈）。原版液态玻璃导航的
     * 焦点胶囊始终是固定 76dp，这里同样以**参考单槽宽度**封顶：手机不受影响，宽屏下透镜
     * 变成居中的 76dp 胶囊，与图标大小成比例。
     */
    val lensMaxWidth: Dp = 76.dp,
    /**
     * 单槽最小可点宽度（方案 §窄窗边界）：低于此值时不渲染透镜档，
     * 否则会出现「每槽十几 dp 点不中」的伪可用状态。
     */
    val minTabWidth: Dp = 24.dp,
    val panelOffsetMax: Dp = 4.dp,
    /** 释放等待阈值（索引单位）：参考公式为 `(N − 1) × 0.025`，五项为 0.10（四项适配参数）。 */
    val releaseThreshold: Float = 0.10f,
    val visibilityThreshold: Float = 0.001f,
    // ---- 整栏归位（原先散落在组件里的字面量，收口到这里）----
    /** 拖动松手后整栏归位弹簧（阻尼比 / 刚度 / 阈值）。 */
    val panelRecenterDamping: Float = 1f,
    val panelRecenterStiffness: Float = 300f,
    val panelRecenterThreshold: Float = 0.5f,
) {
    /** 自然宽度：N 个参考单槽 + 左右内边距（报告 §8.5 公式 `N·w + 8dp`）。 */
    fun naturalBarWidth(tabsCount: Int): Dp =
        tabMinWidth * tabsCount + barInnerPadding * 2

    /**
     * 给定窗口宽度下组件是否会真的渲染。
     *
     * 宿主据此决定要不要预留底部留白：两侧读同一份契约，窗口极窄时不会出现
     * 「组件不渲染、却仍空出一条」。等价于组件内部的
     * `clampedBarWidth(...) > minRenderableBarWidth`（自然宽度 ≥ 84dp 恒大于 8dp，
     * 因此两种模式都退化为「可用宽度是否够」）。
     */
    fun canRender(windowWidth: Dp, tabsCount: Int = DefaultTabsForHostInset): Boolean {
        val available = (windowWidth - hostHorizontalPadding * 2).coerceAtLeast(0.dp)
        if (available <= minRenderableBarWidth) return false
        if (tabsCount <= 0) return false
        // 与组件侧 canRenderLens 同一套规则（单槽最小可点宽度），避免宿主留白与是否渲染不一致
        val perTab = (available - barInnerPadding * 2) / tabsCount
        return perTab >= minTabWidth
    }

    /**
     * 宿主需要为底栏预留的底部留白（不含系统导航栏 inset）。
     *
     * 由组件对外暴露，宿主不必自己把 [barHeight] 与 [hostBottomPadding] 相加（避免两处漂移）。
     */
    fun hostBottomInset(): Dp = barHeight + hostBottomPadding

    /**
     * 实际渲染宽度（纯函数，便于单元测试）：
     * - 手机（[stretch] = false）：自然宽度与可用宽度取小，保持参考单槽比例并居中收窄；
     * - 平板等宽屏（[stretch] = true）：拉伸铺满可用宽度，与原版「液态玻璃」导航一致，
     *   避免在宽屏上缩成一条居中的短栏。
     * 两种情况都不会超过可用宽度（报告 §9 窄屏风险）。
     */
    fun clampedBarWidth(tabsCount: Int, windowWidth: Dp, stretch: Boolean = false): Dp {
        val available = (windowWidth - hostHorizontalPadding * 2).coerceAtLeast(0.dp)
        return if (stretch) available else minOf(naturalBarWidth(tabsCount), available)
    }

    /** 可渲染下限：比这更窄时连左右内边距都放不下，调用方应放弃渲染而不是留一条残片。 */
    val minRenderableBarWidth: Dp
        get() = barInnerPadding * 2

    /**
     * 指针 x → 连续索引（纯函数，便于 RTL / 边界单测）。
     *
     * 采用**固定栏体坐标**，不把 panelShift 反向写回命中计算，避免「视觉装饰 ↔ 指针目标」形成反馈环。
     * [barWidthPx] 为整栏像素宽，[paddingPx] 为左右内边距，[tabWidthPx] 为单槽宽；
     * [isLtr] 为假时左右镜像。
     */
    fun pointerXToIndex(
        x: Float,
        barWidthPx: Float,
        paddingPx: Float,
        tabWidthPx: Float,
        tabsCount: Int,
        isLtr: Boolean,
    ): Float {
        if (tabWidthPx <= 0f || tabsCount <= 0) return 0f
        val fromStart = if (isLtr) x else barWidthPx - x
        return ((fromStart - paddingPx) / tabWidthPx - 0.5f)
            .coerceIn(0f, (tabsCount - 1).toFloat())
    }

    /**
     * 按点是否落在滑块矩形内（纯函数，便于 LTR/RTL 与边界单测）。
     *
     * 只判水平方向：栏体高度方向刻意宽松，方便按到边缘。
     */
    fun isInsideLens(
        x: Float,
        barWidthPx: Float,
        paddingPx: Float,
        tabWidthPx: Float,
        lensIndex: Float,
        lensMaxWidthPx: Float,
        isLtr: Boolean,
    ): Boolean {
        if (tabWidthPx <= 0f) return false
        val lensHalfPx = minOf(tabWidthPx, lensMaxWidthPx) / 2f
        val fromStart = if (isLtr) x else barWidthPx - x
        val lensCenter = paddingPx + (lensIndex + 0.5f) * tabWidthPx
        return kotlin.math.abs(fromStart - lensCenter) <= lensHalfPx
    }

    /**
     * 该栏宽下是否真的能渲染透镜（基于最终像素并要求单槽可点）。
     *
     * 与 `clampedBarWidth`/`minRenderableBarWidth` 同源：dp 转 px 后可能正好只剩左右内边距，
     * 这时 tabWidthPx = 0——组件不画透镜，宿主也就不该留白。
     */
    fun canRenderLens(
        barWidthPx: Float,
        paddingPx: Float,
        tabsCount: Int,
        minTabWidthPx: Float,
    ): Boolean {
        if (tabsCount <= 0 || !barWidthPx.isFinite() || !paddingPx.isFinite()) return false
        val contentWidth = barWidthPx - paddingPx * 2
        if (contentWidth <= 0f) return false
        return contentWidth / tabsCount >= minTabWidthPx
    }

    companion object {
        /** 宿主留白判定使用的槽位数：Tyranor 底栏固定四项。 */
        const val DefaultTabsForHostInset = 4

        /** 默认档：报告 §6.3 记录的参考默认参数原值。 */
        val Default: GlassBottomBarSpec = GlassBottomBarSpec()
    }
}

/**
 * 平台能力标记（报告 §8.9：能力与「用户是否开启」必须分开表达）：
 * - [supportsBlur]：Android 12+（API 31）具备 RenderEffect 实时模糊；
 * - [supportsRefraction]：Android 13+（API 33）才具备 RuntimeShader 折射（lens）与交互高光。
 *
 * 低版本一律按降级档渲染，且**不得**描述为「已实现完整折射」（报告附录 B）。
 */
@Immutable
data class GlassBottomBarCapabilities(
    val supportsBlur: Boolean,
    val supportsRefraction: Boolean,
) {
    companion object {
        /** 按 SDK 级别推导能力；纯函数，便于单元测试与未来注入。 */
        fun of(sdkInt: Int): GlassBottomBarCapabilities =
            GlassBottomBarCapabilities(
                supportsBlur = sdkInt >= Build.VERSION_CODES.S,
                supportsRefraction = sdkInt >= Build.VERSION_CODES.TIRAMISU,
            )

        /** 当前设备能力快照（进程内固定，惰性求值）。 */
        val current: GlassBottomBarCapabilities by lazy { of(Build.VERSION.SDK_INT) }
    }
}

/**
 * 参数兜底：非有限值回退到 [fallback]，有限值夹取到 `[min, max]`。
 *
 * 公共组件接受外部传入的参数契约，不能让 NaN/±Inf/0 这类值把帧循环钉死
 * （收敛判定永不成立）、让 `graphicsLayer` 变换变成 NaN，或让整栏缩放归零。
 */
internal fun Float.safeMotionValue(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback
