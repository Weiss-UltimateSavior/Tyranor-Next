package com.core.rpgmaker

/**
 * RPG Maker MZ 的 Effekseer 闸门（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * 依据（GameNative `EffekseerWasmGate.kt` 的第一手记录）：Chromium WebView < 124 上
 * MZ 实例化 Effekseer WASM + WebAudio 输出，会在 10–30s 后触发 renderer 里确定性的
 * audio CHECK，直接杀进程；屏蔽 `AudioContext`、屏蔽渲染器初始化、不连 destination
 * 都无效，唯一有效的是**阻止 WASM 实例化**。
 *
 * MZ 的 `Graphics._createEffekseerContext` 对失败是容忍的（`try/catch` + `this._app`
 * 判空），`Sprite_Animation` 在 `Graphics._effekseer === null` 时直接不播——所以装桩
 * 的代价只是没有粒子，不会崩。
 *
 * 阈值来自单方记录、未在本仓库设备复现，因此 `auto` 之外必须保留 `on` / `off`
 * 手动逃生口。
 */
internal object RpgEffekseerGate {

    /** `auto` 的放行阈值：WebView 的 Chromium 主版本达到它才允许实例化 Effekseer。 */
    const val MIN_CHROMIUM_MAJOR = 124

    /**
     * 生效值：`on` / `off` 直接生效；其余（含 `auto` 与脏值）按 WebView 主版本判定。
     *
     * 解析不出主版本（非 Chromium WebView、UA 被改写、空串）时按保守侧处理——
     * 宁可少一组粒子，也不要一次确定性闪退。
     */
    fun enabled(mode: String?, chromiumMajor: Int): Boolean = when (mode?.trim()?.lowercase()) {
        "on" -> true
        "off" -> false
        else -> chromiumMajor >= MIN_CHROMIUM_MAJOR
    }

    private val CHROME_SEGMENT = Regex("""(?:^|[\s;(])Chrome/(\d+)""")

    /**
     * 从 WebView 的 UA 里取 Chromium 主版本。
     *
     * 只认 `Chrome/<数字>` 这一种形态：`ChromeLike/130.0` 这类产品名不能被误判成
     * Chromium，否则闸门会被一个不相关的版本号打开。取不到时返回 0。
     */
    fun chromiumMajor(userAgent: String?): Int {
        if (userAgent.isNullOrBlank()) return 0
        val match = CHROME_SEGMENT.find(userAgent) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    /**
     * 会话配置脚本（放进 earlyHtml，`</head>` 之前）。
     *
     * `effekseer` 字段必须无条件写出：桩在页面里读它，字段缺失时桩会静默不生效
     * （表现为「设了 off 还在崩」）。
     *
     * [audioStreaming] 为 null 时不写该字段（MV 会话不读它）；MZ 会话必须显式给出，
     * 否则 `__mz_core_hook.js` 只能按「默认开」处理，用户在设置里关掉也不生效。
     */
    fun sessionConfigScript(
        engine: String,
        version: String?,
        effekseer: Boolean,
        audioStreaming: Boolean? = null,
    ): String = buildString {
        append("<script>window.__tyranorRpg={engine:'").append(engine)
        append("',version:'").append(version ?: "")
        append("',effekseer:").append(effekseer)
        if (audioStreaming != null) append(",audioStreaming:").append(audioStreaming)
        append("};</script>")
    }

    /**
     * Effekseer 桩：在 `</head>` 之前安装，必须早于 `js/libs/effekseer.min.js`。
     *
     * **不能把 `window.effekseer` 置成 `undefined`**：RPG Maker MZ 的 `main.js`
     * 在 `initEffekseerRuntime()` 里**无判空地**调用
     * `effekseer.initRuntime(wasmUrl, onLoad, onError)`，而 `onLoad` 是
     * `SceneManager.run(Scene_Boot)` 的唯一入口（MZ 1.9.0 `main.js:142-151`，本仓库
     * 实测样本即此版本）。全局为 undefined 会在这行直接抛 TypeError，启动链断在
     * `SceneManager.run` 之前——比闪退更难查，因为页面只是黑着。
     *
     * 正确形态是**静默桩**：保留一个带 `initRuntime` 的对象，被调用时按成功路径回调
     * `onLoad`（让游戏照常启动），但 `createContext` 返回 `null`。`Graphics._createEffekseerContext`
     * 对 `createContext` 返回空值是容忍的（`this._effekseer` 为空时 `Sprite_Animation`
     * 直接不播），所以代价只是没有粒子，**且真的不会实例化 WASM**——那才是杀进程的根源。
     *
     * 用 setter 拦截而不是置一次值：`effekseer.min.js` 求值时会给全局赋值，一次性
     * 赋值会被覆盖回去。若该库是 UMD 包装而根本不碰全局，本桩同样成立（全局就是桩）。
     */
    fun effekseerStubScript(): String = """
        <script>
        (function () {
          var cfg = window.__tyranorRpg;
          if (!cfg || cfg.effekseer !== false) return;
          var silent = {
            initRuntime: function (url, onLoad) {
              // 不上传 WASM、不建 context；按成功路径回调，让 main.js 继续到 SceneManager.run
              if (typeof onLoad === "function") {
                try { onLoad(); } catch (e) { /* 游戏自己的回调异常不吞掉后续 */ throw e; }
              }
            },
            createContext: function () { return null; }
          };
          try {
            Object.defineProperty(window, "effekseer", {
              configurable: true,
              get: function () { return silent; },
              set: function () { /* 忽略 effekseer.min.js 的自我赋值：保留静默桩 */ }
            });
          } catch (e) { /* 已不可配置（脚本已求值过）：交给 lateHook 兜底 */ }
        })();
        </script>
    """.trimIndent()
}
