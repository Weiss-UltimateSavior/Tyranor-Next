package com.core.rpgmaker

/**
 * RPG Maker MZ 会话的启动就绪闸门（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * MZ 的 `index.html` 只有一个 `<script src="js/main.js">`，其余脚本由 `main.js` 动态注入：
 * `PluginManager.setup` → `effekseer.initRuntime(wasm)` 之后才 `SceneManager.run`。
 * 「首帧可见」在这中间就会到，于是加载条消失、游戏却还在黑屏解 wasm。判定应当等到
 * `SceneManager._scene` 出现；但等待必须有界——超时仍要揭开（并记 warn），
 * 不能因为某个作品把 `SceneManager.run` 挡住就把启动页永久卡死。
 */
internal object RpgMakerStartupGate {

    /** 轮询间隔：远小于 [TIMEOUT_MS]，让就绪信号尽快兑现。 */
    const val POLL_INTERVAL_MS = 120L

    /** 等待上限。超过就揭开并记 warn（不卡死），由日志定位是哪个作品没跑起来。 */
    const val TIMEOUT_MS = 15_000L

    /** 就绪探针：`SceneManager.run` 之后 `_scene` 才有值。能力探测写法，不假设 MZ 版本。 */
    const val READY_PROBE_JS =
        "(function(){try{return !!(window.SceneManager && SceneManager._scene);}catch(e){return false;}})()"

    enum class Decision {
        /** 还没就绪，也未到超时：继续轮询。 */
        CONTINUE,

        /** 已就绪：揭开。 */
        REVEAL,

        /** 超时未就绪：仍要揭开，调用方记一条 warn。 */
        TIMEOUT_REVEAL,
    }

    /**
     * `onPageCommitVisible` 是否可以直接揭开加载页。
     *
     * 它**早于** `onPageFinished` 触发，而它原来无条件揭开——MZ 会话必须先拒绝这次抢跑，
     * 否则轮询根本没机会开始（`initialMainFramePending` 已被置 false），阶段 H 等于没做。
     * 非 MZ 会话保持原样：那条路径是既有的首帧体验，不能一起改。
     */
    fun mayRevealOnCommitVisible(rpgMakerMz: Boolean): Boolean = !rpgMakerMz

    /** 本轮该做什么。就绪优先于时长：超时之后才就绪的作品也应算 REVEAL。 */
    fun decide(elapsedMs: Long, ready: Boolean): Decision = when {
        ready -> Decision.REVEAL
        elapsedMs >= TIMEOUT_MS -> Decision.TIMEOUT_REVEAL
        else -> Decision.CONTINUE
    }

    /**
     * `evaluateJavascript` 的回调值 → 是否就绪。
     *
     * 回调给的是 JSON 字面量：布尔 `true`、字符串 `"true"`、`null`、脚本抛错时的 `{}` 都可能出现。
     * 只有明确为真才算就绪——把不认识的值当成就绪会让闸门退化成「立刻揭开」。
     */
    fun isReady(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        return value.trim('"').trim().equals("true", ignoreCase = true)
    }
}