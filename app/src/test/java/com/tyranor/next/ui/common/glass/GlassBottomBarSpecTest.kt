package com.tyranor.next.ui.common.glass

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增强档底栏参数契约的纯 JVM 测试：钉住「参考默认值」与宽度夹取规则，
 * 避免后续调参时悄悄改变参考实现的既定参数（分析报告 §6.3）或窄屏行为（报告 §9）。
 */
class GlassBottomBarSpecTest {

    private val spec = GlassBottomBarSpec.Default

    @Test
    fun naturalBarWidth_usesReferenceSlotWidthPlusInnerPadding() {
        // 参考单槽 76dp + 左右各 4dp 内边距（报告 §8.5 公式 N·w + 8dp）
        assertEquals(312.dp, spec.naturalBarWidth(4))
        assertEquals(388.dp, spec.naturalBarWidth(5))
        assertEquals(84.dp, spec.naturalBarWidth(1))
    }

    @Test
    fun clampedBarWidth_keepsNaturalWidthWhenWindowIsWideEnough() {
        // 360dp 窗口减左右各 16dp 留白 = 328dp > 312dp，因此保持自然宽度居中收窄
        assertEquals(312.dp, spec.clampedBarWidth(tabsCount = 4, windowWidth = 360.dp))
    }

    @Test
    fun clampedBarWidth_shrinksWithNarrowWindowsInsteadOfOverflowing() {
        // 320dp 窗口：可用 288dp，必须收窄（报告 §9 窄屏风险）
        assertEquals(288.dp, spec.clampedBarWidth(tabsCount = 4, windowWidth = 320.dp))
    }

    @Test
    fun clampedBarWidth_neverGoesNegativeOnTinyWindows() {
        assertEquals(0.dp, spec.clampedBarWidth(tabsCount = 4, windowWidth = 16.dp))
        assertEquals(0.dp, spec.clampedBarWidth(tabsCount = 4, windowWidth = 0.dp))
    }

    @Test
    fun minRenderableBarWidth_isTwiceTheInnerPadding() {
        assertEquals(8.dp, spec.minRenderableBarWidth)
    }

    @Test
    fun referenceOpticalAndMotionDefaults_arePinned() {
        // 这些数值来自分析报告 §6.3 的参考实现默认档；改动即为偏离参考，必须在文档 §6 记录
        assertEquals(64.dp, spec.barHeight)
        assertEquals(4.dp, spec.barInnerPadding)
        assertEquals(56.dp, spec.lensHeight)
        assertEquals(76.dp, spec.tabMinWidth)
        assertEquals(8.dp, spec.blurRadius)
        assertEquals(24.dp, spec.barLensRadius)
        assertEquals(10.dp, spec.lensRefractionHeight)
        assertEquals(14.dp, spec.lensRefractionAmount)
        assertEquals(0.40f, spec.surfaceAlpha, 1e-6f)
        assertEquals(78f / 56f, spec.pressedScale, 1e-6f)
        assertEquals(1.2f, spec.iconScaleOnPress, 1e-6f)
        assertEquals(0.10f, spec.staticLensCoverAlpha, 1e-6f)
        assertEquals(0.03f, spec.pressedLensCoverAlpha, 1e-6f)
    }

    @Test
    fun fourTabAdaptationSpans_arePinnedToTheFiveSlotReference() {
        // 四项适配（文档 §6 D4）：速度归一化跨度与释放阈值固定为五项参考值，
        // 而不是本应用四项时的 3 / 0.075
        assertEquals(4f, spec.velocityNormalizationSpan, 1e-6f)
        assertEquals(0.10f, spec.releaseThreshold, 1e-6f)
    }

    @Test
    fun enhancementOnlyDefaults_arePinned() {
        // 本方案新增/调整过的参数同样钉值：改动即偏离当前设计，必须在文档 §6 记录
        assertEquals(0.18f, spec.barHighlightAlpha, 1e-6f)
        assertEquals(0.05f, spec.barPressScaleDeltaMax, 1e-6f)
        assertEquals(0.06f, spec.pressVeilAlpha, 1e-6f)
        assertEquals(0.12f, spec.pressGlowAlpha, 1e-6f)
    }

    @Test
    fun clampedBarWidth_stretchesOnWideScreens() {
        // 平板/横屏：拉伸铺满可用宽度（与项目原有液态玻璃导航一致），不再缩成居中短栏
        assertEquals(768.dp, spec.clampedBarWidth(tabsCount = 4, windowWidth = 800.dp, stretch = true))
        // 手机：保持参考单槽宽度居中收窄
        assertEquals(312.dp, spec.clampedBarWidth(tabsCount = 4, windowWidth = 800.dp, stretch = false))
    }

    @Test
    fun lightModeReadabilityDefaults_arePinned() {
        // 浅色档可读性适配（文档 §6 D15）：浅底上必须比深色档更实、描边更明确
        assertEquals(0.40f, spec.surfaceAlpha, 1e-6f)
        assertEquals(0.62f, spec.surfaceAlphaLight, 1e-6f)
        assertEquals(0.18f, spec.barHighlightAlpha, 1e-6f)
        assertEquals(0.42f, spec.barHighlightAlphaLight, 1e-6f)
        assertEquals(0.10f, spec.edgeStrokeAlpha, 1e-6f)
        assertEquals(0.5.dp, spec.edgeStrokeWidth)
        assertTrue("浅色档表面必须比深色档更实", spec.surfaceAlphaLight > spec.surfaceAlpha)
    }
}
