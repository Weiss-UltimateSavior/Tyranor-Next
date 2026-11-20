package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.ui.common.motion.MotionMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import top.yukonga.miuix.kmp.basic.SliderDefaults

@RunWith(Parameterized::class)
class MotionSliderDeviceTest(private val enhanced: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun systemCancelRollsBackDraftWithoutFinishing() = withSlider { fixture ->
        dragToDraft(fixture)
        compose.onNodeWithTag(SLIDER).performTouchInput { cancel() }

        compose.runOnIdle {
            assertEquals(1, fixture.cancelCalls)
            assertEquals(emptyList<Float>(), fixture.commits)
            assertEquals(INITIAL, fixture.committed, 0f)
            assertEquals(INITIAL, fixture.draft.floatValue, 0f)
        }
        assertProgress(INITIAL)
    }

    @Test
    fun normalDragUpCommitsDraftExactlyOnce() = withSlider { fixture ->
        dragToDraft(fixture)
        compose.onNodeWithTag(SLIDER).performTouchInput { up() }

        compose.runOnIdle {
            assertEquals(0, fixture.cancelCalls)
            assertEquals(listOf(DRAGGED), fixture.commits)
            assertEquals(DRAGGED, fixture.committed, 0f)
        }
        assertProgress(DRAGGED)
    }

    private fun dragToDraft(fixture: Fixture) {
        compose.onNodeWithTag(SLIDER).performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, center.y))
        }
        compose.runOnIdle {
            assertEquals(DRAGGED, fixture.draft.floatValue, 0f)
            assertEquals(INITIAL, fixture.committed, 0f)
            assertEquals(emptyList<Float>(), fixture.commits)
            assertEquals(0, fixture.cancelCalls)
        }
        assertProgress(DRAGGED)
    }

    private fun assertProgress(value: Float) {
        compose.onNodeWithTag(SLIDER).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(value, 0f..1f, STEPS),
            ),
        )
    }

    private fun withSlider(test: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = MotionMode.enhanced
        try {
            MotionDeviceAcceptance.require(enhanced)
            val fixture = Fixture()
            compose.setContent {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Slider(
                        value = fixture.draft.floatValue,
                        onValueChange = { fixture.draft.floatValue = it },
                        modifier = Modifier.width(240.dp).testTag(SLIDER),
                        steps = STEPS,
                        hapticEffect = SliderDefaults.SliderHapticEffect.None,
                        onValueChangeFinished = {
                            fixture.committed = fixture.draft.floatValue
                            fixture.commits += fixture.committed
                        },
                        onValueChangeCancelled = {
                            fixture.cancelCalls++
                            fixture.draft.floatValue = fixture.committed
                        },
                    )
                }
            }
            test(fixture)
        } finally {
            instrumentation.runOnMainSync { MotionMode.sync(originalMode) }
        }
    }

    private class Fixture {
        val draft = mutableFloatStateOf(INITIAL)
        var committed = INITIAL
        val commits = mutableListOf<Float>()
        var cancelCalls = 0
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "enhanced={0}")
        fun modes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
        const val SLIDER = "motion-slider"
        const val STEPS = 3
        const val INITIAL = 0.5f
        const val DRAGGED = 0.75f
    }
}
