package com.tyranor.next.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import org.junit.Assert.assertTrue
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
    fun verticalScrollTakeoverDoesNotFinish() = withSlider(scrollableParent = true) { fixture ->
        compose.onNodeWithTag(SLIDER).performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 80f), delayMillis = 32)
            moveTo(center - Offset(0f, 160f), delayMillis = 32)
            up()
        }

        compose.runOnIdle {
            assertTrue("父级必须实际滚动", fixture.scrollState.value > 0)
            // 普通档的 Miuix 横拖未开始，没有取消回调。
            assertEquals(if (enhanced) 1 else 0, fixture.cancelCalls)
            assertEquals(emptyList<Float>(), fixture.commits)
            assertEquals(INITIAL, fixture.committed, 0f)
            assertEquals(INITIAL, fixture.draft.floatValue, 0f)
        }
    }

    /**
     * 横拖已经产生草稿后，上层容器在 Initial 阶段接手整段手势（位移与抬手都消费）。
     * 增强档由滑条自己的状态机判定；普通档靠旁听 Miuix 的结束回调，看到的是「已消费的抬手」，
     * 同样回滚。这里两种档位都必须给一次取消，不得把接手当成正常抬手提交。
     */
    @Test
    fun consumedDragAfterHorizontalMoveRollsBackDraft() = withSlider { fixture ->
        dragToDraft(fixture)
        compose.runOnIdle {
            fixture.consumeMoves.value = true
            fixture.consumeUp.value = true
        }
        compose.onNodeWithTag(SLIDER).performTouchInput {
            moveBy(Offset(12f, 0f))
            up()
        }
        compose.runOnIdle {
            assertEquals(1, fixture.cancelCalls)
            assertEquals(emptyList<Float>(), fixture.commits)
            assertEquals(INITIAL, fixture.committed, 0f)
            assertEquals(INITIAL, fixture.draft.floatValue, 0f)
        }
    }

    @Test
    fun disposingActiveGestureWithoutCancellationCallbackOnlyCleansUp() = checkDisposal(false)

    @Test
    fun disposingActiveGestureWithCancellationCallbackOnlyCleansUp() = checkDisposal(true)

    private fun checkDisposal(withCancellationCallback: Boolean) = withSlider(withCancellationCallback) { fixture ->
        dragToDraft(fixture)
        compose.runOnIdle { fixture.visible.value = false }
        compose.onNodeWithTag(SLIDER).assertDoesNotExist()

        compose.runOnIdle {
            // 节点移除会先收到 Compose 合成的手势取消；只允许显式回滚一次，绝不提交。
            assertTrue(fixture.cancelCalls in 0..(if (withCancellationCallback) 1 else 0))
            assertEquals(emptyList<Float>(), fixture.commits)
            assertEquals(INITIAL, fixture.committed, 0f)
            assertEquals(if (fixture.cancelCalls == 0) DRAGGED else INITIAL, fixture.draft.floatValue, 0f)
        }
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

    /**
     * 连续档（`steps = 0`，与应用设置页两个背景图滑条一致）同样要在取消时回滚草稿。
     * 连续值取决于滑条拇指半径，不断言具体读数，只钉住「有草稿、未提交、取消后归位」。
     */
    @Test
    fun continuousSliderSystemCancelRollsBackWithoutFinishing() = withSlider(steps = 0) { fixture ->
        compose.onNodeWithTag(SLIDER).performTouchInput {
            down(center)
            moveTo(Offset(width * 0.8f, center.y))
        }
        compose.runOnIdle {
            assertTrue("连续档拖动后草稿必须已改变", fixture.draft.floatValue != INITIAL)
            assertEquals(INITIAL, fixture.committed, 0f)
            assertEquals(emptyList<Float>(), fixture.commits)
        }

        compose.onNodeWithTag(SLIDER).performTouchInput { cancel() }
        compose.runOnIdle {
            assertEquals(1, fixture.cancelCalls)
            assertEquals(emptyList<Float>(), fixture.commits)
            assertEquals(INITIAL, fixture.committed, 0f)
            assertEquals(INITIAL, fixture.draft.floatValue, 0f)
        }
        assertProgress(INITIAL, steps = 0)
    }

    @Test
    fun systemCancelWithoutCancellationCallbackDoesNotFinishEnhancedSlider() = withSlider(
        withCancellationCallback = false,
    ) { fixture ->
        dragToDraft(fixture)
        compose.onNodeWithTag(SLIDER).performTouchInput { cancel() }

        compose.runOnIdle {
            if (enhanced) {
                assertEquals(emptyList<Float>(), fixture.commits)
                assertEquals(INITIAL, fixture.committed, 0f)
                assertEquals(DRAGGED, fixture.draft.floatValue, 0f)
            } else {
                assertEquals(listOf(DRAGGED), fixture.commits)
                assertEquals(DRAGGED, fixture.committed, 0f)
                assertEquals(DRAGGED, fixture.draft.floatValue, 0f)
            }
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

    private fun assertProgress(value: Float, steps: Int = STEPS) {
        compose.onNodeWithTag(SLIDER).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(value, 0f..1f, steps),
            ),
        )
    }

    private fun withSlider(
        withCancellationCallback: Boolean = true,
        scrollableParent: Boolean = false,
        steps: Int = STEPS,
        test: (Fixture) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = MotionMode.enhanced
        try {
            MotionDeviceAcceptance.require(enhanced)
            val fixture = Fixture()
            compose.setContent {
                Box(
                    Modifier.fillMaxSize().testTag(HOST).pointerInput(fixture) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (fixture.consumeMoves.value) {
                                    event.changes.filter { it.pressed && it.previousPressed }.forEach { it.consume() }
                                }
                                if (fixture.consumeUp.value) {
                                    event.changes.filter { it.previousPressed && !it.pressed }.forEach { it.consume() }
                                }
                            }
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.height(320.dp).then(
                            if (scrollableParent) Modifier.verticalScroll(fixture.scrollState) else Modifier,
                        ).padding(top = 96.dp),
                    ) {
                        if (fixture.visible.value) {
                            Slider(
                                value = fixture.draft.floatValue,
                                onValueChange = { fixture.draft.floatValue = it },
                                modifier = Modifier.width(240.dp).testTag(SLIDER),
                                steps = steps,
                                hapticEffect = SliderDefaults.SliderHapticEffect.None,
                                onValueChangeFinished = {
                                    fixture.committed = fixture.draft.floatValue
                                    fixture.commits += fixture.committed
                                },
                                onValueChangeCancelled = if (withCancellationCallback) {
                                    {
                                        fixture.cancelCalls++
                                        fixture.draft.floatValue = fixture.committed
                                    }
                                } else null,
                            )
                        }
                        if (scrollableParent) Spacer(Modifier.height(1200.dp))
                    }
                }
            }
            test(fixture)
        } finally {
            instrumentation.runOnMainSync { MotionMode.sync(originalMode) }
        }
    }

    private class Fixture {
        val draft = mutableFloatStateOf(INITIAL)
        val visible = mutableStateOf(true)
        val consumeMoves = mutableStateOf(false)
        val consumeUp = mutableStateOf(false)
        val scrollState = ScrollState(0)
        var committed = INITIAL
        val commits = mutableListOf<Float>()
        var cancelCalls = 0
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "enhanced={0}")
        fun modes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
        const val SLIDER = "motion-slider"
        const val HOST = "motion-slider-host"
        const val STEPS = 3
        const val INITIAL = 0.5f
        const val DRAGGED = 0.75f
    }
}
