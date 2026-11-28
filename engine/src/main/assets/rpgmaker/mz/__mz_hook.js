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

    // MZ 的 SceneManager.isGameActive 用 window.top.document.hasFocus()；WebView
    // 里恒为 true，钉死可以避免 iframe 边界下的判定差异。
    if (window.SceneManager) {
        SceneManager.isGameActive = function () {
            return true;
        };
    }

    var cfg = window.__tyranorRpg || {};

    // Effekseer 闸门的第二道保险。earlyHtml 里的 setter 拦截是主力（必须早于
    // effekseer.min.js），这里兜住「全局已存在且不可重定义」等漏网情况。
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