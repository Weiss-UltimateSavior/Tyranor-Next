package com.core.rpgmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MZ 的 Effekseer 闸门：阈值判定、UA 解析与 sessionConfig 拼装。
 *
 * 依据（GameNative `EffekseerWasmGate.kt` 的第一手记录）：Chromium WebView < 124 上
 * MZ 实例化 Effekseer WASM + WebAudio 输出，会在 10–30s 后触发 renderer 里确定性的
 * audio CHECK 直接杀进程；屏蔽 AudioContext / 渲染器初始化都不管用，唯一有效的是
 * 阻止 WASM 实例化。阈值只有单方记录、未在本仓库设备复现，所以 `auto` 之外必须有
 * `on` / `off` 手动逃生口。
 */
class RpgEffekseerGateTest {

    @Test
    fun autoModeDecidesOnTheChromiumMajorVersion() {
        assertTrue(RpgEffekseerGate.enabled("auto", chromiumMajor = 124))
        assertTrue(RpgEffekseerGate.enabled("auto", chromiumMajor = 140))
        assertEquals(false, RpgEffekseerGate.enabled("auto", chromiumMajor = 123))
        assertEquals(false, RpgEffekseerGate.enabled("auto", chromiumMajor = 99))
        // 解析不出主版本（非 Chromium WebView / UA 被改写）时按保守侧处理
        assertEquals(false, RpgEffekseerGate.enabled("auto", chromiumMajor = 0))
    }

    @Test
    fun explicitModesWinOverTheVersionProbe() {
        assertTrue(RpgEffekseerGate.enabled("on", chromiumMajor = 0))
        assertTrue(RpgEffekseerGate.enabled("on", chromiumMajor = 99))
        assertEquals(false, RpgEffekseerGate.enabled("off", chromiumMajor = 200))
    }

    /** 未知模式（脏的持久化值）按 auto 处理，而不是「不装闸门」。 */
    @Test
    fun unknownModeFallsBackToAuto() {
        assertEquals(RpgEffekseerGate.enabled("auto", chromiumMajor = 100), RpgEffekseerGate.enabled("bogus", chromiumMajor = 100))
        assertEquals(RpgEffekseerGate.enabled("auto", chromiumMajor = 130), RpgEffekseerGate.enabled("", chromiumMajor = 130))
        assertEquals(RpgEffekseerGate.enabled("auto", chromiumMajor = 130), RpgEffekseerGate.enabled(null, chromiumMajor = 130))
    }

    @Test
    fun parsesTheChromiumMajorFromAWebViewUserAgent() {
        assertEquals(
            124,
            RpgEffekseerGate.chromiumMajor(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Version/4.0 Chrome/124.0.6367.82 Mobile Safari/537.36;tyranoplayer-android-1.0",
            ),
        )
        assertEquals(
            99,
            RpgEffekseerGate.chromiumMajor(
                "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Version/4.0 Chrome/99.0.4844.88 Mobile Safari/537.36",
            ),
        )
        // 没有 Chrome 段：返回 0，绝不能瞎猜一个通过阈值的数
        assertEquals(0, RpgEffekseerGate.chromiumMajor("Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36"))
        assertEquals(0, RpgEffekseerGate.chromiumMajor(null))
        assertEquals(0, RpgEffekseerGate.chromiumMajor(""))
        // 尾部带版本号的其他产品名不能被误当成 Chrome
        assertEquals(0, RpgEffekseerGate.chromiumMajor("Mozilla/5.0 ChromeLike/130.0"))
    }

    /**
     * 会话配置必须携带该字段：Effekseer 桩在 earlyHtml 里读它，字段缺失时桩会静默
     * 不生效（表现为「设了 off 还在崩」）。
     */
    @Test
    fun sessionConfigCarriesTheEffekseerFlag() {
        val config = RpgEffekseerGate.sessionConfigScript(engine = "MZ", version = "v2", effekseer = false)
        assertTrue(config, config.contains("window.__tyranorRpg="))
        assertTrue(config, config.contains("engine:'MZ'"))
        assertTrue(config, config.contains("version:'v2'"))
        assertTrue(config, config.contains("effekseer:false"))

        val enabled = RpgEffekseerGate.sessionConfigScript(engine = "MV", version = "v2", effekseer = true)
        assertTrue(enabled, enabled.contains("effekseer:true"))
        assertTrue(enabled, enabled.contains("engine:'MV'"))
    }

    /** 版本值原样透传设置值；缺失时给空串（与 v0 兜底会话的历史形态一致）。 */
    @Test
    fun sessionConfigHandlesAMissingVersion() {
        val config = RpgEffekseerGate.sessionConfigScript(engine = "MZ", version = null, effekseer = true)
        assertTrue(config, config.contains("version:''"))
    }

    /**
     * MZ 的音频开关必须由主进程下发的值写进会话配置：字段缺失时
     * `__mz_core_hook.js` 只能按「默认开」处理，用户在设置里关掉也不生效。
     * MV 会话不写该字段（它不读，写了只会让会话配置看起来像有额外语义）。
     */
    @Test
    fun sessionConfigCarriesTheAudioStreamingFlagOnlyWhenGiven() {
        val mz = RpgEffekseerGate.sessionConfigScript("MZ", "v2", effekseer = true, audioStreaming = false)
        assertTrue(mz, mz.contains("audioStreaming:false"))
        val mv = RpgEffekseerGate.sessionConfigScript("MV", "v2", effekseer = true)
        assertTrue("MV 不应携带 MZ 专有字段", !mv.contains("audioStreaming"))
    }

    /**
     * 桩必须做成 setter 拦截，而不是置一次 undefined：`effekseer.min.js` 求值时会把
     * `window.effekseer` 赋成对象，一次性赋值会被库覆盖回去。
     */
    @Test
    fun stubScriptInterceptsAssignmentToTheGlobal() {
        val stub = RpgEffekseerGate.effekseerStubScript()
        assertTrue(stub, stub.contains("defineProperty"))
        assertTrue(stub, stub.contains("\"effekseer\""))
        assertTrue("必须带 setter，否则库的自我赋值会把桩覆盖掉", stub.contains("set:"))
        // 只有会话标记为 false 时才安装：标记缺失/为真时必须原样放行
        assertTrue(stub, stub.contains("__tyranorRpg"))
        assertTrue(stub, stub.contains("cfg.effekseer !== false"))
        assertTrue("不满足条件要早退，不能继续装桩", stub.contains("return;"))
    }

    /**
     * lateHook 的第二道保险由**资产本身**承担（`rpgmaker/mz/__mz_hook.js`），不是这里。
     *
     * 曾经在 Kotlin 里也有一份等价实现 `lateHookFallbackScript()`，但它从未被调用——
     * 两份实现只有一份出货，测试却同时断言两份，制造了虚假覆盖。现在只保留资产那一份，
     * 它的行为由 `RpgMakerMzAssetsTest`（静态契约）与 JS harness 场景 9b（真执行）覆盖。
     */
    @Test
    fun thereIsExactlyOneLateHookFallbackImplementation() {
        val source = java.io.File("src/main/java/com/core/rpgmaker/RpgEffekseerGate.kt").readText()
        // 只检查**代码**，KDoc 里提到该 API 是正当的说明
        val codeOnly = source.lines().filterNot { it.trimStart().startsWith("*") }.joinToString("\n")
        assertTrue(
            "Kotlin 侧不应再有 _createEffekseerContext 的兜底实现（真正的在 __mz_hook.js）",
            !codeOnly.contains("_createEffekseerContext"),
        )
    }
}