package com.tyranor.next.ui.common.glass

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * AGSL（`RuntimeShader`）的**运行期**可用性探测与熔断。
 *
 * [GlassBottomBarCapabilities.supportsRefraction] 只看 `SDK_INT >= 33`，但「系统版本够」不等于
 * 「这台机器的 AGSL 真能编译」：个别 ROM / 驱动的 Skia 会在构造 `RuntimeShader` 时抛
 * `IllegalArgumentException`。
 *
 * Backdrop 里**依赖 RuntimeShader 的只有两处**（已核对库内引用）：
 * 1. `effects` 作用域里的 `lens()` / `colorFilter()`（由本包的 `effects` lambda 直接调用，
 *    构造点就在我们自己的调用栈里，因此可以用 try/catch 兜住）；
 * 2. `HighlightStyle.*`（高光，**由库的节点在 attach/draw 期间自行构造**，我们无法 catch）。
 * `Shadow` / `InnerShadow` 走的是 RenderEffect，不涉及 AGSL。
 *
 * 因此兜底分三层，合起来才谈得上「AGSL 异常时不崩」：
 * - **挂载前探测**（[isRuntimeShaderUsable]）：任一候选程序编译失败即认为本机 AGSL 不可用，
 *   调用方据此**不挂载透镜档**，并让经典档**不传 Highlight**
 *   （`LiquidGlassNavigationBar(runtimeShaderAvailable = …)`）——回退路径本身也不再依赖 AGSL；
 * - **运行期熔断**（[allowShaderWork] / [onShaderWorkFailed]）：探测通过后库 shader 仍可能失败，
 *   此时把折射整块跳过，而不是让异常冒泡到组合/布局期崩掉主界面；
 * - 结果按进程缓存，成功与失败都只探测一次。
 *
 * 探测程序**必须避开 AGSL 保留字**：`input` 就是保留字，用它会让程序编译失败并抛
 * `IllegalArgumentException: name 'input' is reserved`，于是探测把「程序写错」误判成
 * 「设备不支持」、整档静默退回经典档（真机已踩过一次），因此这里改用 `content` / `tint` 等。
 */
internal object GlassShaderSupport {

    private const val Tag = "GlassShader"

    /**
     * 探测程序集：**全部编译通过**才算本机 AGSL 可用。
     *
     * 候选按库内实际用到的写法逐个加严，避免只测最简程序时漏掉复杂 shader 的失败：
     * 1. 最简 `half4 main(float2)`（无 uniform）；
     * 2. 与库内 `lens()` 同构：`uniform shader` + `eval`；
     * 3. 与库内 `HighlightStyle.Default` 同构：多个 `float2` / `float4` / `float` uniform、
     *    带分支的辅助函数，以及 `normalize` / `sign` / `max` / `length` 等内建函数
     *    （高光由库节点自行构造，是本包唯一无法 catch 的 AGSL 依赖，只能靠探测覆盖）。
     */
    private val ProbePrograms = listOf(
        "half4 main(float2 coord) { return half4(1.0, 1.0, 1.0, 1.0); }",
        """
            uniform shader content;
            uniform float2 offset;
            half4 main(float2 coord) {
                return content.eval(coord + offset);
            }
        """,
        """
            uniform float2 size;
            uniform float4 tint;
            uniform float falloff;
            uniform float angle;

            float pick(float2 coord) {
                if (coord.y <= 0.0) return tint.y;
                else return tint.z;
            }

            half4 main(float2 coord) {
                float2 unit = coord / max(size, float2(1.0));
                float corner = pick(sign(unit) * normalize(max(unit, float2(0.0)) + float2(0.001)));
                float intensity = max(1.0 - falloff * length(unit), 0.0) * corner;
                return half4(intensity, intensity, intensity, 1.0);
            }
        """,
    )

    /**
     * 本机 AGSL 是否可用（进程内只探测一次）。
     *
     * API 33 以下恒为 `false`——低版本不允许构造 `RuntimeShader`，与
     * [GlassBottomBarCapabilities.supportsRefraction] 的判定一致。
     */
    val isRuntimeShaderUsable: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            false
        } else {
            probeAll()
        }
    }

    /** 运行期熔断标志：库 shader 一旦失败，本进程不再尝试。 */
    @Volatile
    private var shaderWorkDisabled = false

    /**
     * 现在是否还应执行库 shader 相关绘制（探测失败或已熔断都为 `false`）。
     *
     * 调用方在 `effects` lambda 里先判它、再用 try/catch 包住 `lens()`——库的
     * `RuntimeShader` 正是在这个调用栈里构造出来的。
     */
    val allowShaderWork: Boolean
        get() = isRuntimeShaderUsable && !shaderWorkDisabled

    /**
     * 库 shader 工作失败时的进程级熔断（只记一次日志）。
     *
     * 抛出点位于 `onAttach → updateEffects`（组合 / 布局期），异常一旦冒泡崩的是**整个主界面**
     * 而不只是底栏，所以在这里就地吞掉，降级为「无折射的玻璃栏」。
     */
    fun onShaderWorkFailed(name: String, error: Throwable) {
        if (!shaderWorkDisabled) {
            shaderWorkDisabled = true
            Log.w(Tag, "AGSL work '$name' failed; skipped for the rest of this process", error)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probeAll(): Boolean {
        for ((index, program) in ProbePrograms.withIndex()) {
            val failure = runCatching { RuntimeShader(program) }.exceptionOrNull()
            if (failure != null) {
                Log.w(Tag, "AGSL probe #${index + 1} failed; the lens style will fall back", failure)
                return false
            }
        }
        return true
    }
}
