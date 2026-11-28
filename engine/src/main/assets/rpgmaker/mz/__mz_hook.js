// RPG Maker MZ 会话的 lateHook（由 RpgMakerActivity 在 </body> 前内联注入）。
// 取代 assets/__rmmz__.js：那份仍被 v0 路径（TyranoActivity）使用，不要改它。
//
// 本文件的求值时刻：HTML 解析期的 </body> 之前。此时 js/main.js 还没把
// rmmz_core.js 插进来（MZ 的 index.html 只直接引用 main.js），所以**任何依赖
// Graphics / SceneManager 已存在的代码都不能直接写在顶层**——要么只在事件里跑，
// 要么按能力探测 + 有界重试等待。
(function () {
    "use strict";

    document.body.parentNode.style.overflow = "hidden";

    // 注意：`SceneManager.isGameActive` 的覆写**不在这里**。本文件在 </body> 前求值，
    // 那时 rmmz_core.js 还没被 main.js 插入、SceneManager 是 undefined，写在这里的
    // 分支永远不会执行。真正生效的那份在 __mz_managers_hook.js（追加到
    // rmmz_managers.js 末尾，此时 SceneManager 已存在）。

    var cfg = window.__tyranorRpg || {};

    // Effekseer 闸门的第二道保险。earlyHtml 里的 setter 拦截是主力（必须早于
    // effekseer.min.js），这里兜住「全局已存在且不可重定义」等漏网情况。
    //
    // 注意语义：**不是**把 window.effekseer 抹掉。MZ 的 main.js 在
    // initEffekseerRuntime() 里无判空地调 effekseer.initRuntime(wasm, onLoad, onError)，
    // 而 onLoad 是进入 SceneManager.run 的唯一入口（实测样本 MZ 1.9.0 main.js:142-151）。
    // 全局为 undefined 会直接 TypeError、启动断在黑屏。所以桩要保留 initRuntime 并
    // 回调 onLoad，只让 createContext 返回空——Graphics 对空 context 是容忍的
    // （Sprite_Animation 直接不播），且不会实例化 WASM。
    //
    // Graphics 尚未定义时必须有界等待：MZ 的 rmmz_core.js 由 main.js 动态插入，
    // 到这里可能还没求值。
    if (cfg.effekseer === false) {
        var attempts = 0;
        var maxAttempts = 100;
        var installEffekseerStub = function () {
            attempts++;
            var g = window.Graphics;
            if (!g) {
                if (attempts < maxAttempts) setTimeout(installEffekseerStub, 50);
                return;
            }
            // 能力探测而非版本号：MZ 1.2+ 若改名/改调用点，保持原实现即可
            // （只是少了这层保险，不引入新的崩溃面）。
            if (typeof g._createEffekseerContext !== "function") return;
            g._createEffekseerContext = function () {
                this._effekseer = null;
            };
        };
        installEffekseerStub();
    }
})();
