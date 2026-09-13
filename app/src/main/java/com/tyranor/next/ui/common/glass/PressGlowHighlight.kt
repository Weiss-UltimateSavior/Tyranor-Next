package com.tyranor.next.ui.common.glass

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.util.fastCoerceIn

/**
 * 按压光斑（本项目独立实现，非移植代码）。
 *
 * 按住玻璃时，在栏面叠一层「整体微亮 + 跟随选中槽位的一团柔光」，让按压有实体感：
 * - 底层：纯白 6% × 按压进度，`BlendMode.Plus`；
 * - 光斑：纯白 12% × 按压进度，半径 = 短边 × 1.2，用 AGSL 做径向衰减。
 *
 * 与参考实现的差异（记录于 docs/液态玻璃增强计划方案.md §6 D14）：
 * 光斑中心直接取**透镜当前位置**（由索引 + 槽宽 + 整栏偏移算出），不单独维护一套跟手的
 * 位置动画——参考实现里的位置动画在其装配方式下并未参与绘制，留着只是每帧多一次协程。
 * 亮度仍由一条**比体积略慢**的独立弹簧（300 / 0.5）驱动，与参考观感一致。
 *
 * 仅在 Android 13+（API 33）构造：构造期即创建 `android.graphics.RuntimeShader`。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PressGlowHighlight(
    private val progress: () -> Float,
    private val center: (size: Size) -> Offset,
    private val veilAlpha: Float,
    private val glowAlpha: Float,
    private val tint: Color,
) {
    // 着色器编译/构造失败（个别 ROM 的 AGSL 实现异常）不能让整个主界面崩溃：失败即静默降级为无光斑
    @SuppressLint("NewApi")
    private val shader: RuntimeShader? = runCatching {
        RuntimeShader(
            """
            layout(color) uniform half4 color;
            uniform float radius;
            uniform float2 position;

            half4 main(float2 coord) {
                float d = distance(coord, position);
                float falloff = 1.0 - smoothstep(radius * 0.5, radius, d);
                return color * falloff;
            }
            """,
        )
    }.getOrNull()

    /** Brush 与 shader 一起缓存：`drawWithContent` 每帧都会执行，不能在里面新建对象。 */
    private val brush: Brush? = shader?.let { ShaderBrush(it) }

    val modifier: Modifier = Modifier.drawWithContent {
        val p = progress()
        val activeShader = shader
        val activeBrush = brush
        // shader 不可用（构造失败）时整块跳过，只剩内容绘制，不抛异常
        if (p > 0f && activeShader != null && activeBrush != null) {
            drawRect(tint.copy(alpha = veilAlpha * p), blendMode = BlendMode.Plus)
            val spot = center(size)
            activeShader.apply {
                setColorUniform("color", tint.copy(alpha = glowAlpha * p).toArgb())
                setFloatUniform("radius", (size.minDimension * 1.2f).coerceAtLeast(MinRadiusPx))
                setFloatUniform(
                    "position",
                    spot.x.finiteIn(0f, size.width),
                    spot.y.finiteIn(0f, size.height),
                )
            }
            drawRect(activeBrush, blendMode = BlendMode.Plus)
        }
        drawContent()
    }

    private companion object {
        /** 退化尺寸下 smoothstep 的半径下限，避免 0/0 得到 NaN 着色。 */
        const val MinRadiusPx = 1f
    }
}

/** NaN / ±Inf 一律回落到 0，再夹到区间内：着色器 uniform 不接受非有限值。 */
private fun Float.finiteIn(min: Float, max: Float): Float =
    if (isFinite()) fastCoerceIn(min, max) else 0f
