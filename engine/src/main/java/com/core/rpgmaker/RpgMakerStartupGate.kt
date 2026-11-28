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

    /**
     * 就绪探针。
     *
     * **必须同时满足三点，只判 `_scene` 会提前揭开**：
     * `SceneManager.goto()` 在**构造**场景时就给 `_scene` 赋值，而 `Scene_Boot.create()`
     * （真正发起 data 目录 JSON 请求、加载数据库）在那之后才跑。只判 `_scene` 会在数据库
     * 一个字节都没加载、图片一张都没取时就判「就绪」——真机表现是黑屏或残缺画面
     * （まもの博物館：`mz scene ready after 700ms` 之后一个 data 请求都没有，随后
     * 图像上传报 `texImage2D: width or height out of range`）。
     *
     * 1. `SceneManager._scene` 存在；
     * 2. `DataManager.isDatabaseLoaded()` 为真——这是**唯一可靠**的「数据库已就绪」信号，
     *    它逐个检查 `_databaseFiles` 是否已挂到 `window`，正是 `Scene_Boot.create()` 的产物；
     * 3. 渲染器已建立：MZ 用 `Graphics._app`（`PIXI.Application`），**没有** `_renderer`
     *    （`_renderer` 是 MV/PIXI 4 的字段）。两者都探测，兼容 MV 侧复用。
     *
     * **不能用 `Graphics.frameCount`**：MZ 的 `frameCount` 只在 `Graphics.initialize` 里
     * 置 0、之后从不递增（实测 rmmz_core.js v1.1.0 只有 `this.frameCount = 0`），拿它
     * 作「主循环已跑帧」的判据会恒为 false，把每次启动都拖到 15s 超时。
     *
     * 全部按能力探测写，不假设 MZ 版本号。
     */
    const val READY_PROBE_JS =
        "(function(){try{" +
            "if(!window.SceneManager||!SceneManager._scene)return false;" +
            "if(!window.DataManager||typeof DataManager.isDatabaseLoaded!=='function')return false;" +
            "if(!DataManager.isDatabaseLoaded())return false;" +
            "if(!window.Graphics)return false;" +
            "if(!Graphics._app&&!Graphics._renderer)return false;" +
            "return true;" +
            "}catch(e){return false;}})()"

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

    /**
     * 是否在 `onPageStarted` 就布防超时看门狗。
     *
     * MZ 拒绝 `onPageCommitVisible` 的抢跑之后，`onPageFinished` 成了唯一能启动轮询的
     * 地方；页面加载若根本没完成（挂起的子资源、解析期死循环的插件、卡住的本地请求），
     * `onPageFinished` 永远不来，[TIMEOUT_MS] 也就永远不开始，加载页会永久停住。
     * 所以 MZ 必须在页面开始加载时就布防，而不是等 `onPageFinished`。
     *
     * 非 MZ 会话保留 `onPageCommitVisible` 的即时揭开，不需要额外看门狗。
     */
    fun armsWatchdogOnPageStart(rpgMakerMz: Boolean): Boolean = rpgMakerMz

    /**
     * 已等待时长（毫秒），对负值截断为 0。
     *
     * 调用方必须传单调时钟读数（`SystemClock.elapsedRealtime`）：墙钟在 [TIMEOUT_MS]
     * 窗口里被回拨会让差值变负，[decide] 于是永远返回 CONTINUE，防卡死的保证静默失效
     * （仓库里 playtime 也刻意避开墙钟）。截断是对「万一仍拿到倒退读数」的最后一道防线。
     */
    fun saturatingElapsed(startedAtMs: Long, nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)

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
