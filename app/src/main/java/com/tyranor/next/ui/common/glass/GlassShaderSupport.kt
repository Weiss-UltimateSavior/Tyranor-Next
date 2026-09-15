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
 *   （`LiquidGlassNavigationBar(highlightAvailable = …)`，取值用 [highlightAllowed]——
 *   **不能**直接用 [isRuntimeShaderUsable]，它在 API 33 以下恒为 false，会连带抹掉 Android 12
 *   经典档本来正常的高光）——回退路径本身也不再依赖 AGSL；
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
     * 3. 覆盖库内 `HighlightStyle.Default` 用到的构造与内建函数：多个 `float2` / `float4` /
     *    `float` uniform、带分支的辅助函数，以及 `max` / `min` / `abs` / `sign` / `normalize` /
     *    `length` / `dot` / `pow` / `cos` / `sin`。**这不是逐字复制库内程序**，只是把同一类写法
     *    （向量运算 + 分支 + 常用内建）都过一遍编译器——高光由库节点自行构造，是本包唯一无法
     *    catch 的 AGSL 依赖，只能靠探测尽量覆盖。
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
                float2 dir = normalize(max(unit, float2(0.0)) + float2(0.001));
                float corner = pick(sign(unit) * dir);
                float wave = abs(cos(angle) * dir.x) + abs(sin(angle) * dir.y);
                float shaped = pow(max(dot(dir, dir), 0.0), 0.5);
                float intensity = max(1.0 - falloff * length(unit), 0.0) * min(corner, wave + shaped);
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
     * 库的 `Highlight`（高光）在本机能否安全传下去：本机可用且运行期未熔断。
     *
     * **不要**拿 [isRuntimeShaderUsable] 直接 gate 高光——它在 API 33 以下恒为 `false`
     * （那里根本没有 `RuntimeShader`），而库的 `HighlightStyle.Default` 在 31–32 走的是
     * 「描边 + `BlurMaskFilter`」的非 shader 路径，本来就有可见高光、也不会崩。用它去 gate
     * 会把 Android 12 经典档的高光白白抹掉（第五轮跟进审核抓到的回归）。
     */
    val highlightAllowed: Boolean
        get() = highlightAllowedFor(
            sdkInt = Build.VERSION.SDK_INT,
            runtimeShaderUsable = isRuntimeShaderUsable && !shaderWorkDisabled,
        )

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

/**
 * 纯函数：给定 SDK 级别与「本机 AGSL 是否可用」，能否安全地把 `Highlight` 传给库。
 *
 * **API 33 以下恒为 `true`**：那里没有 `RuntimeShader`，库的 `HighlightStyle.Default` 走
 * 「描边 + `BlurMaskFilter`」路径，高光本来就有、也不会崩；只有 33+ 且探测失败才需要避让。
 * 抽成纯函数是为了能单测——第五轮跟进审核抓到的回归正是「拿 `isRuntimeShaderUsable` 直接
 * gate 高光」，那会让 Android 12 经典档的高光被无条件抹掉。
 */
internal fun highlightAllowedFor(sdkInt: Int, runtimeShaderUsable: Boolean): Boolean =
    sdkInt < Build.VERSION_CODES.TIRAMISU || runtimeShaderUsable
