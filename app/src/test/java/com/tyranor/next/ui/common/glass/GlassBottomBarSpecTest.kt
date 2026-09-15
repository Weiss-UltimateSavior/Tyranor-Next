package com.tyranor.next.ui.common.glass

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 透镜档底栏参数契约的纯 JVM 测试：钉住「参考默认值」与宽度夹取规则，
 * 避免后续调参时悄悄改变已固化的既定参数或窄屏行为。
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
    fun referenceGeometryDefaults_arePinned() {
        // 几何沿用既定默认档；光学已改为浅深分档（见下一个用例）
        assertEquals(64.dp, spec.barHeight)
        assertEquals(4.dp, spec.barInnerPadding)
        assertEquals(56.dp, spec.lensHeight)
        assertEquals(76.dp, spec.tabMinWidth)
        assertEquals(1.2f, spec.iconScaleOnPress, 1e-6f)
        assertEquals(78f / 56f, spec.pressedScaleX, 1e-6f)
        assertEquals(78f / 56f, spec.pressedScaleY, 1e-6f)
    }

    @Test
    fun translucentBarMaterial_lightAndDarkPairs_arePinned() {
        // 改造方案 §栏体参数：浅色高透乳白 / 深色烟黑，成对切换；改值必须在文档 §6 记录
        // 实测目标：轻微模糊（3dp，全局）+ 轻微遮罩（0.34）+ 可见的内部折射
        assertEquals(3.dp, spec.barBlurRadiusLight)
        assertEquals(3.dp, spec.barBlurRadiusDark)
        assertEquals(0.34f, spec.barSurfaceAlphaLight, 1e-6f)
        assertEquals(0.34f, spec.barSurfaceAlphaDark, 1e-6f)
        assertEquals(12.dp, spec.barLensHeight)
        assertEquals(8.dp, spec.barLensAmount)
        assertTrue("栏体做色散：上下边缘的连贯彩虹带", spec.barLensChromatic)
        // 浅色档专属增强（只作用于栏体的上下色散带；滑块保持基准折射量）
        assertEquals(1.4f, spec.lightDispersionBoost, 1e-6f)
        // 浅色档上下边缘的「玻璃厚度」：内阴影 + 稍强发丝边
        assertEquals(10.dp, spec.barInnerShadowRadiusLight)
        assertEquals(0.12f, spec.barInnerShadowAlphaLight, 1e-6f)
        assertEquals(0.14f, spec.barEdgeStrokeAlphaLight, 1e-6f)
        assertEquals(0.03f, spec.barBrightnessLight, 1e-6f)
        assertEquals(-0.03f, spec.barBrightnessDark, 1e-6f)
        assertEquals(0.96f, spec.barContrastLight, 1e-6f)
        assertEquals(0.88f, spec.barContrastDark, 1e-6f)
        assertEquals(1.18f, spec.barSaturationLight, 1e-6f)
        assertEquals(1.10f, spec.barSaturationDark, 1e-6f)
        // 浅色档高光必须低到不遮住边缘色散带（曾取 0.70，实测把色散洗掉）
        assertEquals(0.42f, spec.barHighlightAlphaLight, 1e-6f)
        assertEquals(0.30f, spec.barHighlightAlphaDark, 1e-6f)
        assertTrue("栏体应保留克制的内部折射（液态玻璃质感）", spec.barLensHeight > 0.dp)
    }

    @Test
    fun movingLensRestAndPressedEndpoints_arePinned() {
        // 方案 §静止与按压光学分离：未按压也有真实折射与轻虹彩
        assertEquals(6.dp, spec.restRefractionHeight)
        assertEquals(8.dp, spec.restRefractionAmount)
        assertEquals(10.dp, spec.pressedRefractionHeight)
        assertEquals(12.dp, spec.pressedRefractionAmount)
        assertEquals(0.28f, spec.lensHighlightAlphaRest, 1e-6f)
        assertEquals(0.80f, spec.lensHighlightAlphaPressed, 1e-6f)
        assertEquals(2.dp, spec.lensInnerShadowRadiusRest)
        assertEquals(8.dp, spec.lensInnerShadowRadiusPressed)
        assertEquals(0.18f, spec.lensInnerShadowAlphaRest, 1e-6f)
        assertEquals(0.75f, spec.lensInnerShadowAlphaPressed, 1e-6f)
        assertEquals(0.12f, spec.lensShadowAlphaRest, 1e-6f)
        assertEquals(0.45f, spec.lensShadowAlphaPressed, 1e-6f)
        assertTrue("滑块必须有色散：碰到左右图标时要有彩边", spec.movingLensChromatic)
        assertEquals(0.05f, spec.staticLensCoverAlpha, 1e-6f)
        assertEquals(0.02f, spec.pressedLensCoverAlpha, 1e-6f)
    }

    @Test
    fun pressMotionDynamics_arePinned() {
        // 方案 §运动参数与时序：赴按 6000/1.0、跟手 2400/1.0、到位阈值 0.08 index
        assertEquals(6_000f, spec.pressJumpStiffness, 1e-3f)
        assertEquals(1.0f, spec.pressJumpDampingRatio, 1e-3f)
        assertEquals(2_400f, spec.pressTrackingStiffness, 1e-3f)
        assertEquals(1.0f, spec.pressTrackingDampingRatio, 1e-3f)
        assertEquals(0.08f, spec.pressArriveThreshold, 1e-6f)
    }

    @Test
    fun fourTabAdaptationSpans_arePinnedToTheFiveSlotReference() {
        // 四项适配（文档 §6 D4）：释放阈值固定为五项参考值 0.10，而不是本应用四项时的 0.075。
        // （速度归一化跨度随速度形变一起移除，见 §6 D29。）
        assertEquals(0.10f, spec.releaseThreshold, 1e-6f)
    }

    @Test
    fun enhancementOnlyDefaults_arePinned() {
        // 本方案新增/调整过的参数同样钉值：改动即偏离当前设计，必须在文档 §6 记录
        assertEquals(0.30f, spec.barHighlightAlphaDark, 1e-6f)
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
    fun canRender_matchesTheComponentsEarlyReturn() {
        // 可用宽度 = 窗口 − 左右留白；≤ 最小可渲染宽度时不渲染（宿主因此也不留白）
        assertEquals(false, spec.canRender(40.dp))
        assertEquals(false, spec.canRender(16.dp))
        assertEquals(true, spec.canRender(360.dp))
        assertEquals(true, spec.canRender(800.dp))
    }

    @Test
    fun pulseVisibleThreshold_isPinned() {
        // 近目标按压的可见性判定阈值（见 §6 D20）
        assertEquals(0.5f, spec.pulseVisibleThreshold, 1e-6f)
    }

    @Test
    fun lensWidthCap_matchesReferenceSlotWidth() {
        // 宽屏拉伸后单槽会变宽，透镜视觉宽度以参考单槽宽封顶（手机单槽＝76dp，等于不设限）
        assertEquals(76.dp, spec.lensMaxWidth)
        assertEquals(spec.tabMinWidth, spec.lensMaxWidth)
    }

    @Test
    fun pointerXToIndex_mapsEdgesAndCenterExactly() {
        // 四槽、单槽 100px、左右内边距 4px：槽心 = padding + (i + 0.5) × tab
        val bar = 408f
        val pad = 4f
        val tab = 100f
        fun at(x: Float, ltr: Boolean = true) =
            spec.pointerXToIndex(x, bar, pad, tab, tabsCount = 4, isLtr = ltr)
        // 每槽中心 → 整数索引
        assertEquals(0f, at(pad + 50f), 1e-4f)
        assertEquals(1f, at(pad + 150f), 1e-4f)
        assertEquals(3f, at(pad + 350f), 1e-4f)
        // 槽边界（半槽处）→ 半整数
        assertEquals(0.5f, at(pad + 100f), 1e-4f)
        // 越界夹取
        assertEquals(0f, at(-500f), 1e-4f)
        assertEquals(3f, at(9999f), 1e-4f)
        // RTL 完全镜像
        assertEquals(at(pad + 150f), at(bar - (pad + 150f), ltr = false), 1e-4f)
        // tabWidth 非法时退回 0，不产生 NaN
        assertEquals(0f, spec.pointerXToIndex(50f, bar, pad, 0f, 4, true), 1e-6f)
    }

    @Test
    fun isInsideLens_coversEdgesAndRtl() {
        // 四槽、单槽 100px、内边距 4px、滑块 100px 宽（=整槽）
        val bar = 408f
        val pad = 4f
        val tab = 100f
        fun hit(x: Float, index: Float, ltr: Boolean = true) =
            spec.isInsideLens(x, bar, pad, tab, index, lensMaxWidthPx = 100f, isLtr = ltr)
        // 槽 2 中心 = 4 + 2.5×100 = 254
        assertEquals(true, hit(254f, 2f))
        assertEquals(true, hit(204f, 2f))   // 左边界
        assertEquals(true, hit(304f, 2f))   // 右边界
        assertEquals(false, hit(203f, 2f))  // 左边界外
        assertEquals(false, hit(305f, 2f))  // 右边界外
        // 滑块比槽窄（宽屏 76px 滑块 / 200px 槽）：只在中心 ±38 内命中
        assertEquals(true, spec.isInsideLens(100f, 808f, 4f, 200f, 0f, 76f, true))
        // 中心 = 4 + 0.5×200 = 104，半宽 = 76/2 = 38 → 145 已在滑块外
        assertEquals(false, spec.isInsideLens(145f, 808f, 4f, 200f, 0f, 76f, true))
        // RTL 完全镜像
        assertEquals(hit(254f, 2f), hit(bar - 254f, 2f, ltr = false))
        // 非法槽宽不得命中（也不得抛异常）
        assertEquals(false, hit(254f, 2f).let { spec.isInsideLens(254f, bar, pad, 0f, 2f, 100f, true) })
    }

    @Test
    fun canRenderLens_requiresUsableSlotWidth() {
        // 40dp 与 40.1dp 级别：像素上只剩内边距时不渲染（否则宿主会留出错误空白）
        // 单槽最小可点宽度 24px：四槽需要 content ≥ 96px
        assertEquals(false, spec.canRenderLens(8f, 4f, tabsCount = 4, minTabWidthPx = 24f))
        assertEquals(false, spec.canRenderLens(8.1f, 4f, tabsCount = 4, minTabWidthPx = 24f))
        assertEquals(false, spec.canRenderLens(100f, 4f, tabsCount = 4, minTabWidthPx = 24f))
        assertEquals(true, spec.canRenderLens(312f, 4f, tabsCount = 4, minTabWidthPx = 24f))
    }

    @Test
    fun grabDelay_isClampedSoTheGrabTimerCannotStall() {
        // 抓取延时按文档夹在 0…2000ms：脏 spec 不能让滑块立刻被抓走或长时间不跟手
        assertEquals(0L, spec.copy(grabDelayMillis = -5L).safeGrabDelayMillis)
        assertEquals(2_000L, spec.copy(grabDelayMillis = 9_999L).safeGrabDelayMillis)
        assertEquals(110L, spec.copy(grabDelayMillis = 110L).safeGrabDelayMillis)
        assertEquals(2_000L, GlassBottomBarSpec.MaxGrabDelayMillis)
    }

    @Test
    fun lightModeReadabilityDefaults_arePinned() {
        // 浅色档可读性（文档 §6 D15/D25）：浅色描边用 theme 共享常量，栏体材料见上面的分档用例
        assertEquals(0.5.dp, com.tyranor.next.theme.GlassEdgeStrokeWidth)
        assertEquals(0.10f, com.tyranor.next.theme.GlassEdgeStrokeAlpha, 1e-6f)
        assertTrue("浅色档高光必须比深色档强", spec.barHighlightAlphaLight > spec.barHighlightAlphaDark)
    }
}
