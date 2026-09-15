package com.tyranor.next.ui.common.glass

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * AGSL（`RuntimeShader`）的**运行期**可用性探测。
 *
 * [GlassBottomBarCapabilities.supportsRefraction] 只看 `SDK_INT >= 33`，但「系统版本够」不等于
 * 「这台机器的 AGSL 真能编译」：个别 ROM / 驱动的 Skia 会在构造 `RuntimeShader` 时抛
 * `IllegalArgumentException`。
 *
 * Backdrop 的 `lens()` / `Highlight` / `InnerShadow` 全部在
 * `RuntimeShaderCacheImpl.getOrPut { RuntimeShader(...) }` 里构造，**没有任何兜底**，且抛出点在
 * `onAttach → updateEffects`（组合 / 布局期）——一旦发生，崩的是整个主界面而不只是底栏。
 * 光斑那条自建 shader 已有进程级熔断（见 `PressGlowHighlight`），但护不住 Backdrop 内部的 shader。
 *
 * 因此这里在**挂载透镜档之前**先探测一次：构造一个最小 AGSL 程序，失败即认定本机不可用，
 * 调用方（`MainScreen` / `glassNavBottomInset`）据此退回「液态玻璃 · 经典」档，
 * 保证用户看到的是能用的经典档而不是崩溃。结果按进程缓存（成功与失败都只探测一次）。
 *
 * 探测程序采用 Android 官方 `RuntimeShader` 文档的示例写法，保证它**本身**合法——
 * 避免把「探测程序写错」误判成「设备不支持」而让所有机器都退回经典档。
 */
internal object GlassShaderSupport {

    private const val Tag = "GlassShader"

    /**
     * 探测程序：与库自身 shader（`lens()` / `Highlight`）同构的最小程序。
     *
     * **uniform 名不能随便起**：`input` 是 AGSL 保留字，用它会让程序编译失败并抛出
     * `IllegalArgumentException: name 'input' is reserved`——探测就会把「程序写错」误判成
     * 「设备不支持」，把整档静默退回经典档（真机上已踩过这一次）。这里用 `content` /
     * `offset`，与库内 `uniform shader content;`、`uniform float2 offset;` 的用法一致。
     */
    private const val ProbeProgram = """
        uniform shader content;
        uniform float2 offset;
        half4 main(float2 coord) {
            return content.eval(coord + offset);
        }
    """

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
            probe()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probe(): Boolean =
        runCatching { RuntimeShader(ProbeProgram) }
            .onFailure { error ->
                Log.w(Tag, "AGSL unavailable; the lens style will fall back to the classic bar", error)
            }
            .isSuccess
}
