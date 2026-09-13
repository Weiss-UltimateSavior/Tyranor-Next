package com.tyranor.next.ui.common.glass

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 交互高光：按下时在玻璃表面叠加一团跟随手指的径向光斑（RuntimeShader 实现）。
 *
 * 移植自 legado-with-MD3 `app/src/main/java/io/legado/app/ui/animation/InteractiveHighlight.kt`
 * （commit fb01a76ebbbca41423e2c4c00080cc0861239fbd），参数保持源值（报告 §6.3）：
 * 按压弹簧 `0.5f / 300f / 0.001f`、位置弹簧 `0.5f / 300f`、底色白 6%×p、
 * 光斑白 12%×p、半径 `size.minDimension × 1.2`，两者都以 `BlendMode.Plus` 叠加。
 *
 * 仅在 Android 13+（API 33）创建；低版本由调用方传入 `null`，不得实例化本类
 * （构造期会创建 `android.graphics.RuntimeShader`）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {

    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    @SuppressLint("NewApi")
    private val shader = RuntimeShader(
        """
        uniform float2 size;
        layout(color) uniform half4 color;
        uniform float radius;
        uniform float2 position;

        half4 main(float2 coord) {
            float dist = distance(coord, position);
            float intensity = smoothstep(radius, radius * 0.5, dist);
            return color * intensity;
        }
        """,
    )

    val modifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgressAnimation.value
            if (progress > 0f) {
                drawRect(
                    Color.White.copy(0.06f * progress),
                    blendMode = BlendMode.Plus,
                )
                shader.apply {
                    val currentPosition = position(size, positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.12f * progress).toArgb())
                    setFloatUniform("radius", size.minDimension * 1.2f)
                    setFloatUniform(
                        "position",
                        currentPosition.x.fastCoerceIn(0f, size.width),
                        currentPosition.y.fastCoerceIn(0f, size.height),
                    )
                }
                drawRect(
                    ShaderBrush(shader),
                    blendMode = BlendMode.Plus,
                )
            }

            drawContent()
        }

    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            inspectDragGestures(
                onDragStart = { down ->
                    startPosition = down.position
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                        launch { positionAnimation.snapTo(startPosition) }
                    }
                },
                onDragEnd = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
                onDragCancel = {
                    animationScope.launch {
                        launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                        launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                    }
                },
            ) { change, _ ->
                animationScope.launch { positionAnimation.snapTo(change.position) }
            }
        }
}
