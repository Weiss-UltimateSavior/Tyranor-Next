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

    /**
     * 拒绝抢跑后，`onPageFinished` 成了 MZ **唯一**的揭开触发点。若页面加载根本没完成
     * （挂起的子资源、解析期死循环的插件、卡住的本地请求），`onPageFinished` 永远不来，
     * 15s 超时也就永远不开始——加载页会永久停住，正是这道闸门要避免的失败模式。
     *
     * 因此超时看门狗必须从**页面开始加载**就布防，而不是只在 `onPageFinished` 里布防。
     */
    @Test
    fun watchdogIsArmedFromPageStartNotOnlyFromPageFinished() {
        assertTrue(
            "MZ 的看门狗必须在 onPageStarted 就布防",
            RpgMakerStartupGate.armsWatchdogOnPageStart(rpgMakerMz = true),
        )
        assertEquals(
            "非 MZ 会话保留 onPageCommitVisible 的即时揭开，不需要额外看门狗",
            false,
            RpgMakerStartupGate.armsWatchdogOnPageStart(rpgMakerMz = false),
        )
    }

    /**
     * 超时用单调时钟：墙钟在 15s 窗口里被回拨会让 elapsed 变负，`decide` 一直返回
     * CONTINUE，防卡死的保证静默失效（仓库里 playtime 也刻意避开墙钟）。
     */
    @Test
    fun timeoutUsesAMonotonicClockContract() {
        // 单调时钟单调不减：回拨场景下 elapsed 不会变负
        assertEquals(0L, RpgMakerStartupGate.saturatingElapsed(startedAtMs = 1_000L, nowMs = 1_000L))
        assertEquals(5_000L, RpgMakerStartupGate.saturatingElapsed(startedAtMs = 1_000L, nowMs = 6_000L))
        // 即便真的拿到「倒退」的读数，也不能让剩余时间变多
        assertEquals(0L, RpgMakerStartupGate.saturatingElapsed(startedAtMs = 6_000L, nowMs = 1_000L))
    }

    @Test
    fun pollIntervalIsMuchShorterThanTheTimeout() {
        assertTrue(
            "轮询间隔必须远小于超时，否则就绪信号会被拖成一次长等待",
            RpgMakerStartupGate.POLL_INTERVAL_MS * 4 < RpgMakerStartupGate.TIMEOUT_MS,
        )
    }
}