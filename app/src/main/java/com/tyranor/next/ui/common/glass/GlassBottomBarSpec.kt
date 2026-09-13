package com.tyranor.next.ui.common.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「液态玻璃增强」底栏的光学 / 运动 / 尺寸参数契约（单一来源）。
 *
 * 数值取自分析报告《Tyranor-Next × Legado：液态玻璃底栏对比与高保真复刻方案》§6.3 记录的
 * 参考实现默认档（blur 8dp / 表面 alpha 40% / 栏体透镜 24dp），全部保持默认值，
 * 不随用户壁纸或主题改写。**代码为本项目独立实现**，只采用参考实现中不受版权保护的
 * 参数与算法；实现差异逐条记录在 `docs/液态玻璃增强计划方案.md` §6。
 *
 * 四项适配（同上文档 §6 D4，报告 §8.5 要求逐项记录）：
 * [velocityNormalizationSpan] 与 [releaseThreshold] 固定为**五项参考布局**的取值（4 与 0.10），
 * 而不是本应用四项时的 `N − 1 = 3` 与 `0.075`，以保持参考手感。
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
    val blurRadius: Dp = 8.dp,
    /** 深色档表面不透明度（参考默认档）。 */
    val surfaceAlpha: Float = 0.40f,
    /**
     * 浅色档表面不透明度。
     *
     * 浅色页面底色本身很浅，40% 白叠上去几乎与背景同色（只有底栏后面有内容时才看得见轮廓）。
     * 这里按报告 §8.10 的「增强可读性」策略提高覆盖强度：既让栏体成为可辨认的一层，
     * 又保留「透出模糊内容」的玻璃质感。深色档不受影响（仍为 0.40）。
     */
    val surfaceAlphaLight: Float = 0.62f,
    val barLensRadius: Dp = 24.dp,
    val lensRefractionHeight: Dp = 10.dp,
    val lensRefractionAmount: Dp = 14.dp,
    val lensInnerShadowRadius: Dp = 8.dp,
    /** 深色档栏体边缘高光强度（乘在 Highlight.Default 的 50% 白上）；参考值为 1f，本实现降到「微浅」。 */
    val barHighlightAlpha: Float = 0.18f,
    /** 浅色档边缘高光强度：浅底上白光只有更强才能形成玻璃亮边。 */
    val barHighlightAlphaLight: Float = 0.42f,
    /** 浅色档栏体发丝描边不透明度（暗色描边，浅底上勾勒轮廓）。 */
    val edgeStrokeAlpha: Float = 0.10f,
    /** 发丝描边宽度。 */
    val edgeStrokeWidth: Dp = 0.5.dp,
    /** 静止透镜覆盖：浅色黑 / 深色白 10%，乘 (1 − p)。 */
    val staticLensCoverAlpha: Float = 0.10f,
    /** 按压透镜覆盖：黑 3% × p。 */
    val pressedLensCoverAlpha: Float = 0.03f,
    /** 按压光斑整体提亮：纯白 6% × p。 */
    val pressVeilAlpha: Float = 0.06f,
    /** 按压光斑柔光：纯白 12% × p。 */
    val pressGlowAlpha: Float = 0.12f,
    // ---- 运动（参考值：按压缩放 78/56、副本图标 1→1.2、整栏 16dp/栏宽、横向跟随 ±4dp）----
    val pressedScale: Float = 78f / 56f,
    val iconScaleOnPress: Float = 1.2f,
    val barPressScaleDelta: Dp = 16.dp,
    /** 整栏按压缩放增量的上限，避免极窄窗口下 `1 + 16dp/宽度` 被放大成畸形尺寸。 */
    val barPressScaleDeltaMax: Float = 0.05f,
    val panelOffsetMax: Dp = 4.dp,
    /** 速度归一化跨度：参考公式为 `N − 1`，此处固定为五项参考值 4（四项适配参数）。 */
    val velocityNormalizationSpan: Float = 4f,
    /** 释放等待阈值（索引单位）：参考公式为 `(N − 1) × 0.025`，五项为 0.10（四项适配参数）。 */
    val releaseThreshold: Float = 0.10f,
    val visibilityThreshold: Float = 0.001f,
) {
    /** 自然宽度：N 个参考单槽 + 左右内边距（报告 §8.5 公式 `N·w + 8dp`）。 */
    fun naturalBarWidth(tabsCount: Int): Dp =
        tabMinWidth * tabsCount + barInnerPadding * 2

    /**
     * 实际渲染宽度：自然宽度与可用窗口宽度取小，保证窗口再窄也不溢出（报告 §9 窄屏风险）。
     * 纯函数，便于单元测试。
     */
    fun clampedBarWidth(tabsCount: Int, windowWidth: Dp): Dp {
        val available = (windowWidth - hostHorizontalPadding * 2).coerceAtLeast(0.dp)
        return minOf(naturalBarWidth(tabsCount), available)
    }

    /** 可渲染下限：比这更窄时连左右内边距都放不下，调用方应放弃渲染而不是留一条残片。 */
    val minRenderableBarWidth: Dp
        get() = barInnerPadding * 2

    companion object {
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
