package com.core.rpgmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MZ 会话的启动就绪判定。
 *
 * MZ 的 `index.html` 只有 1 个 `<script src="js/main.js">`，其余脚本由 `main.js` 动态注入，
 * 依次跑 `PluginManager.setup` → `effekseer.initRuntime(wasm)` 才 `SceneManager.run`。
 * 「首帧可见」信号会在这中间就到：加载条消失但游戏仍黑屏解 wasm。
 * 所以要等 `SceneManager._scene` 出现；但这种等待必须有界——超时仍要揭开并记 warn，
 * 不能把启动页永久卡住。
 */
class RpgMakerStartupGateTest {

    @Test
    fun revealsAsSoonAsTheSceneExists() {
        assertEquals(
            RpgMakerStartupGate.Decision.REVEAL,
            RpgMakerStartupGate.decide(elapsedMs = 0L, ready = true),
        )
        assertEquals(
            "已经就绪时不应再看等待时长",
            RpgMakerStartupGate.Decision.REVEAL,
            RpgMakerStartupGate.decide(elapsedMs = RpgMakerStartupGate.TIMEOUT_MS, ready = true),
        )
    }

    @Test
    fun keepsWaitingBeforeTheTimeout() {
        assertEquals(
            RpgMakerStartupGate.Decision.CONTINUE,
            RpgMakerStartupGate.decide(elapsedMs = RpgMakerStartupGate.TIMEOUT_MS - 1L, ready = false),
        )
    }

    @Test
    fun revealsAnywayOnceTheTimeoutElapses() {
        assertEquals(
            RpgMakerStartupGate.Decision.TIMEOUT_REVEAL,
            RpgMakerStartupGate.decide(elapsedMs = RpgMakerStartupGate.TIMEOUT_MS, ready = false),
        )
        assertEquals(
            RpgMakerStartupGate.Decision.TIMEOUT_REVEAL,
            RpgMakerStartupGate.decide(elapsedMs = RpgMakerStartupGate.TIMEOUT_MS * 4, ready = false),
        )
    }

    /** `evaluateJavascript` 回来的是 JSON 字面量（`true` / `"true"` / `null`），三种都要认。 */
    @Test
    fun parsesTheProbeResultFromEvaluateJavascript() {
        assertTrue(RpgMakerStartupGate.isReady("true"))
        assertTrue(RpgMakerStartupGate.isReady("\"true\""))
        assertTrue(RpgMakerStartupGate.isReady(" TRUE "))
        assertFalse(RpgMakerStartupGate.isReady("false"))
        assertFalse(RpgMakerStartupGate.isReady("null"))
        assertFalse(RpgMakerStartupGate.isReady("\"undefined\""))
        assertFalse(RpgMakerStartupGate.isReady(""))
        assertFalse(RpgMakerStartupGate.isReady(null))
        // 脚本自身抛错时 callback 可能回一个对象/错误串，不能当成就绪
        assertFalse(RpgMakerStartupGate.isReady("{}"))
    }

    /** 探针要真的看 MZ 的 SceneManager，而不是恒真——否则轮询退化成「立刻揭开」。 */
    @Test
    fun probeScriptChecksTheMzSceneManager() {
        assertTrue(RpgMakerStartupGate.READY_PROBE_JS.contains("SceneManager"))
        assertTrue(RpgMakerStartupGate.READY_PROBE_JS.contains("_scene"))
    }

    /**
     * `onPageCommitVisible` 早于 `onPageFinished`，而它原来无条件揭开加载页。
     * MZ 必须拒绝这次「抢跑」：否则轮询根本没机会开始，阶段 H 等于没做。
     * 非 MZ 会话必须保持原样（那条路径是既有的首帧体验，不能一起改）。
     */
    @Test
    fun commitVisibleMayRevealImmediatelyOnlyOutsideMzSessions() {
        assertTrue(RpgMakerStartupGate.mayRevealOnCommitVisible(rpgMakerMz = false))
        assertEquals(false, RpgMakerStartupGate.mayRevealOnCommitVisible(rpgMakerMz = true))
    }

    @Test
    fun pollIntervalIsMuchShorterThanTheTimeout() {
        assertTrue(
            "轮询间隔必须远小于超时，否则就绪信号会被拖成一次长等待",
            RpgMakerStartupGate.POLL_INTERVAL_MS * 4 < RpgMakerStartupGate.TIMEOUT_MS,
        )
    }
}