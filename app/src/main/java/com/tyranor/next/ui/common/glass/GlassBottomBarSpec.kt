package com.tyranor.next.ui.common.glass

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「液态玻璃增强」底栏的光学 / 运动 / 尺寸参数契约（单一来源）。
 *
 * 参数取自分析报告《Tyranor-Next × Legado：液态玻璃底栏对比与高保真复刻方案》§6.3 的
 * Legado 源值（legado-with-MD3 `FloatingBottomBar.kt` /
 * `ThemeSettings.kt`，commit fb01a76ebbbca41423e2c4c00080cc0861239fbd）：
 * 全部为默认档（blur 8dp / 表面 alpha 40% / 栏体 lens 24dp），不随用户壁纸或主题改写。
 *
 * 与源实现的显式差异（四项适配，报告 §8.5 要求逐项记录，不得声称“源码一字未改”）：
 * - [velocityNormalizationSpan] / [releaseThreshold] 固定为**五项参考布局**的取值（4 与 0.10）；
 *   源公式使用 `valueRange` 跨度（四项时为 3 与 0.075），改用参考跨度以保持参考手感。
 * - [tabMinWidth] 与 [hostHorizontalPadding] 让栏体按参考单槽宽度居中收窄（报告 §8.1）；
 *   可用宽度不足时由组件夹取，不强行溢出屏幕。
 * - 组件不启用 `chromaticAberration`，也不新增震动（源实现无这两项调用，报告 §6.2 / §6.5）。
 */
@Immutable
data class GlassBottomBarSpec(
    // ---- 几何（源值：栏高 64dp / 内边距 4dp / 透镜 56dp / tab 最小宽 76dp）----
    val barHeight: Dp = 64.dp,
    val barInnerPadding: Dp = 4.dp,
    val lensHeight: Dp = 56.dp,
    val tabMinWidth: Dp = 76.dp,
    val hostHorizontalPadding: Dp = 16.dp,
    val hostBottomPadding: Dp = 12.dp,
    val iconSize: Dp = 26.dp,
    // ---- 光学（源默认：blur 8dp / 表面 alpha 40% / 栏体 lens 24dp / 透镜 10dp×p、14dp×p）----
    val blurRadius: Dp = 8.dp,
    val surfaceAlpha: Float = 0.40f,
    val barLensRadius: Dp = 24.dp,
    val lensRefractionHeight: Dp = 10.dp,
    val lensRefractionAmount: Dp = 14.dp,
    val lensInnerShadowRadius: Dp = 8.dp,
    /**
     * 栏体边缘高光强度（乘在 `Highlight.Default` 的 50% 白上）。
     *
     * 源实现为 `Highlight.Default.copy(alpha = 1f)`（即 50% 白 + `BlendMode.Plus`），
     * 在栏体两端圆弧处会形成一条明显的亮边，观感是「深（栏外）→ 浅（亮边）→ 深（栏内）」。
     * 用户确认改为「深 → 微浅 → 深」，故降到 0.18（约 9% 白，差异 D12）。
     */
    val barHighlightAlpha: Float = 0.18f,
    /** 静止透镜覆盖：浅色黑 10%、深色白 10%，乘 (1 − p)。 */
    val staticLensCoverAlpha: Float = 0.10f,
    /** 按压透镜覆盖：黑 3% × p。 */
    val pressedLensCoverAlpha: Float = 0.03f,
    // ---- 运动（源值：按压缩放 78/56、副本图标 1→1.2、整栏 16dp/栏宽、横向跟随 ±4dp）----
    val pressedScale: Float = 78f / 56f,
    val iconScaleOnPress: Float = 1.2f,
    val barPressScaleDelta: Dp = 16.dp,
    val panelOffsetMax: Dp = 4.dp,
    /** 速度归一化跨度：源公式为 `N − 1`，此处固定为五项参考值 4（四项适配参数）。 */
    val velocityNormalizationSpan: Float = 4f,
    /** 释放等待阈值（索引单位）：源公式为 `(N − 1) × 0.025`，五项为 0.10（四项适配参数）。 */
    val releaseThreshold: Float = 0.10f,
    val visibilityThreshold: Float = 0.001f,
) {
    /** 栏体宽度：N 个参考单槽 + 左右内边距（报告 §8.5 公式 `N·w + 8dp`）。 */
    fun naturalBarWidth(tabsCount: Int): Dp =
        tabMinWidth * tabsCount + barInnerPadding * 2

    companion object {
        /** 默认档：报告 §6.3 的 Legado 默认参数原值。 */
        val Default: GlassBottomBarSpec = GlassBottomBarSpec()
    }
}

/**
 * 平台能力标记（报告 §8.9：能力与“用户是否开启”必须分开表达）：
 * - [supportsBlur]：Android 12+（API 31）具备 RenderEffect 实时模糊；
 * - [supportsRefraction]：Android 13+（API 33）才具备 RuntimeShader 折射（lens）与交互高光。
 *
 * 低版本一律按降级档渲染，且**不得**描述为“已实现完整折射”（报告附录 B）。
 */
@Immutable
data class GlassBottomBarCapabilities(
    val supportsBlur: Boolean,
    val supportsRefraction: Boolean,
) {
    companion object {
        /** 当前设备能力快照（进程内固定，惰性求值，避免每次默认参数求值都新建对象）。 */
        val current: GlassBottomBarCapabilities by lazy {
            GlassBottomBarCapabilities(
                supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                supportsRefraction = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            )
        }
    }
}
