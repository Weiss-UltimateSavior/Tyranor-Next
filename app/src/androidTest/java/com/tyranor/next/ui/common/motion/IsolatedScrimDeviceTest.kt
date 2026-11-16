package com.tyranor.next.ui.common.motion

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.zIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.AppearanceStyle
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBackground
import com.tyranor.next.ui.common.MotionDeviceAcceptance
import com.tyranor.next.ui.common.PageTransitionBackground
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IsolatedScrimDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pixelsAndPageRecordingDuringPop() {
        MotionDeviceAcceptance.require(enhanced = null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val originalStyle = AppSettingsStore.getAppearanceStyle(ctx)
        val originalTheme = AppSettingsStore.getThemeMode(ctx)
        val originalMotion = compose.runOnIdle { MotionMode.enhanced }
        val depth = PageDepth()
        val cloudMoved = mutableStateOf(false)
        // Top slot: two independent original compositions and the actual candidate at the
        // same screen pixels. Bottom slot: original at the old test's second location.
        val selected = mutableIntStateOf(2)
        val records = List(4) { AtomicInteger() }
        val taps = AtomicInteger()
        fun theme(style: AppearanceStyle, mode: String) = compose.runOnIdle {
            AppSettingsStore.setAppearanceStyle(ctx, style)
            AppSettingsStore.setThemeMode(ctx, mode)
            AppThemeColors.refresh(ctx)
        }
        fun capture(tile: Int): Pair<IntArray, IntArray> {
            // Only change a layer property. Do not tick the frame clock while multiplexing
            // the slot: the three real DefaultPageBackgroundLayer clocks must stay frozen.
            compose.runOnIdle {
                selected.intValue = tile
                Snapshot.sendApplyNotifications()
            }
            compose.waitForIdle()
            val image = compose.onNodeWithTag("pair").captureToImage()
            assertEquals(W + 8, image.width)
            assertEquals(H * 2, image.height)
            val marker = IntArray(1)
            image.readPixels(marker, W + 2, 2, 1, 1)
            assertEquals("wrong recording visible in capture", markers[tile].toArgb(), marker[0])
            return IntArray(W * H).also { image.readPixels(it, 0, 0, W, H) } to
                IntArray(W * H).also { image.readPixels(it, 0, H, W, H) }
        }
        fun pixels(): IntArray {
            compose.mainClock.advanceTimeByFrame()
            val time = compose.mainClock.currentTime
            val (ref, lower) = capture(0)
            val (control, lower2) = capture(1)
            val (candidate, lower3) = capture(2)
            val label = "${AppThemeColors.appearanceStyle} dark=${AppThemeColors.isDark} p=${depth.progress.value} moved=${cloudMoved.value} t=$time"
            fun delta(a: IntArray, b: IntArray): String {
                val first = a.indices.firstOrNull { a[it] != b[it] } ?: return "0"
                return "${a.indices.count { a[it] != b[it] }} first=$first(${first % W},${first / W}) ${a[first].toUInt().toString(16)}/${b[first].toUInt().toString(16)}"
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "\nScrim $label old/old-offset=${delta(ref, lower)} old/old-same=${delta(ref, control)} old/new-same=${delta(ref, candidate)}\n")
            })
            assertEquals("clock advanced: $label", time, compose.mainClock.currentTime)
            assertArrayEquals("lower control drifted: $label", lower, lower2)
            assertArrayEquals("lower control drifted: $label", lower, lower3)
            assertArrayEquals("original/original at identical window coordinates: $label", ref, control)
            assertArrayEquals("original/candidate at identical window coordinates: $label", ref, candidate)
            assertEquals("notice above scrim", Color.Magenta.toArgb(), ref[2 * W + 2])
            return candidate
        }
        fun snap(p: Float): IntArray {
            compose.runOnIdle { runBlocking { depth.progress.snapTo(p) } }
            return pixels()
        }
        try {
            compose.mainClock.autoAdvance = false
            theme(AppearanceStyle.RETRO_GLASS, AppSettingsStore.THEME_MODE_DARK)
            compose.runOnIdle { MotionMode.sync(true) }
            compose.setContent {
                MaterialTheme(colorScheme = if (AppThemeColors.isDark) darkColorScheme() else lightColorScheme()) {
                    val density = LocalDensity.current
                    Column(Modifier.size(with(density) { (W + 8).toDp() }, with(density) { (2 * H).toDp() }).testTag("pair")) {
                        val slot = Modifier.size(with(density) { W.toDp() }, with(density) { H.toDp() })
                        repeat(2) { row ->
                          Box(Modifier.size(with(density) { (W + 8).toDp() }, with(density) { H.toDp() })) {
                            for (tile in if (row == 0) 0..2 else 3..3) {
                              Box(Modifier.fillMaxSize().graphicsLayer {
                                  alpha = if (tile == 3 || selected.intValue == tile) 1f else 0f
                              }) {
                               Box(slot) {
                                val probe = Modifier.drawWithContent {
                                    records[tile].incrementAndGet()
                                    drawContent()
                                }
                                val scene: @Composable () -> Unit = {
                                    Scene({ cloudMoved.value }, "page-$tile") { taps.incrementAndGet() }
                                }
                                if (tile != 2) {
                                    val max = if (AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop) 0.45f else 0.24f
                                    GlassBackground(probe.drawWithContent {
                                        drawContent()
                                        val a = depth.progress.value
                                        if (a > 0.001f) drawRect(Color.Black.copy(alpha = max * a))
                                    }, scene)
                                } else {
                                    PageTransitionBackground(depth, probe, scene)
                                }
                                // Models the independent sibling slot of NoticeHost, above the page.
                                Spacer(Modifier.fillMaxSize().drawBehind {
                                    drawRect(Color.Magenta, size = Size(8f, 8f))
                                })
                               }
                               // Outside the entire comparison rectangle; proves which tile
                               // actually reached the screen after each visibility switch.
                               Spacer(Modifier.fillMaxSize().drawBehind {
                                   drawRect(markers[tile], Offset(W.toFloat(), 0f), Size(8f, 8f))
                               })
                              }
                            }
                          }
                        }
                    }
                }
            }

            val base = snap(0f)
            val oldCount = records[3].get()
            val newCount = records[2].get()
            assertTrue(oldCount > 0 && newCount > 0)
            val covered = snap(1f)
            // Red foreground has a high child zIndex; it must still be below the page scrim.
            assertNotEquals(base[64 * W + 16], covered[64 * W + 16])
            for (p in listOf(0.75f, 0.50f, 0.13f, 0.0011f, 0.001f, 0f)) snap(p)
            assertTrue("positive control: original page boundary re-records", records[3].get() > oldCount)
            assertEquals("scrim alone must not re-record the page boundary", newCount, records[2].get())
            assertArrayEquals(base, pixels())

            snap(0.66f)
            var lightCard = 0
            for ((style, mode) in listOf(
                AppearanceStyle.DEFAULT to AppSettingsStore.THEME_MODE_LIGHT,
                AppearanceStyle.DEFAULT to AppSettingsStore.THEME_MODE_DARK,
                AppearanceStyle.MILK_GLASS to AppSettingsStore.THEME_MODE_LIGHT,
            )) {
                theme(style, mode)
                val before = pixels()
                val card = before[64 * W + 16]
                if (style == AppearanceStyle.DEFAULT && mode == AppSettingsStore.THEME_MODE_LIGHT) {
                    lightCard = card
                } else {
                    assertNotEquals("new theme's dark-backdrop dim amount", lightCard, card)
                }
                compose.runOnIdle { cloudMoved.value = !cloudMoved.value }
                val after = pixels()
                // The upper sky strip changes and its sampled image must change on the glass
                // above the opaque red card. A frozen backdrop could otherwise pass pair equality.
                assertNotEquals("sky", before[38 * W + 30], after[38 * W + 30])
                assertNotEquals("live sky sampled through glass", before[64 * W + 76], after[64 * W + 76])
                assertEquals("solid card outside sample", before[64 * W + 16], after[64 * W + 16])
            }
            compose.onNodeWithTag("page-2").assertHasClickAction().performTouchInput { click() }
            compose.runOnIdle { assertEquals("tap passes through the empty overlay", 1, taps.get()) }
            val atRest = snap(0f)
            compose.runOnIdle { MotionMode.sync(false) }
            assertArrayEquals("ordinary mode at rest", atRest, pixels())
        } finally {
            theme(originalStyle, originalTheme)
            compose.runOnIdle { MotionMode.sync(originalMotion) }
            compose.mainClock.autoAdvance = true
        }
    }

    @Composable
    private fun Scene(moved: () -> Boolean, tag: String, click: () -> Unit) {
        val backdrop = rememberLayerBackdrop()
        val d = LocalDensity.current
        // Unpainted areas expose the real GlassBackground (including default moving particles).
        Box(Modifier.fillMaxSize().testTag(tag).clickable(interactionSource = null, indication = null, onClick = click)) {
            Spacer(Modifier.matchParentSize().layerBackdrop(backdrop).drawBehind {
                drawRect(Color(0xff467ebd), Offset(0f, 28f), Size(size.width, 60f))
                drawRect(Color(0xffd1e8fc), Offset(if (moved()) 10f else 94f, 28f), Size(80f, 60f))
            })
            Spacer(Modifier.matchParentSize().zIndex(4f).drawBehind {
                drawRect(Color.Red, Offset(0f, 52f), Size(size.width, 28f))
            })
            Box(Modifier.align(androidx.compose.ui.Alignment.Center).zIndex(5f)
                .size(with(d) { 44.toDp() }, with(d) { 20.toDp() })
                .drawBackdrop(backdrop = backdrop, shape = { RectangleShape }, effects = { blur(2f) },
                    highlight = null, shadow = null))
        }
    }

    private companion object {
        val markers = listOf(Color.Red, Color.Green, Color.Blue, Color.White)
        const val W = 160
        const val H = 128
    }
}
