// MV v1 专属兜底 — 仅在 rpgMakerVersion=v1 时由 TyranoActivity 拼接到 __nwjs_polyfill.js 之后注入
// v0 会话不加载本文件，行为与历史版本完全一致
(function () {
    "use strict";
    if (window.__tyranorNwPolyfillV1) return;
    window.__tyranorNwPolyfillV1 = true;

    // KELYEP_DragonBones / FilterController: Object.create(undefined) 在 1.6.1 核心下抛
    // "Object prototype may only be an Object or null"。仅拦 undefined，
    // Object.create(null) 的合法无原型字典放行
    var origCreate = Object.create;
    if (!origCreate.__tyranorV1Patched) {
        Object.create = function (proto, props) {
            if (proto === undefined) {
                console.warn("[nw-polyfill-v1] Object.create(undefined) suppressed, fallback to {}");
                return props ? origCreate.call(this, {}, props) : {};
            }
            return origCreate.call(this, proto, props);
        };
        Object.create.__tyranorV1Patched = true;
    }

    // rpg_core.js:3281 exitFullscreen 在文档未激活时抛 "Document not active"
    try {
        var docProto = typeof Document !== "undefined" && Document.prototype;
        if (docProto && typeof docProto.exitFullscreen === "function" && !docProto.exitFullscreen.__tyranorV1Patched) {
            var _origExit = docProto.exitFullscreen;
            docProto.exitFullscreen = function () {
                try {
                    if (!document.fullscreenElement && !document.webkitFullscreenElement) return Promise.resolve();
                    return _origExit.apply(this, arguments);
                } catch (e) { return Promise.resolve(); }
            };
            docProto.exitFullscreen.__tyranorV1Patched = true;
        }
    } catch (e) {}

    // 单张贴图 404 不卡死场景：不沿用原版 _loadingCount = -Infinity（会永久冻结
    // 加载界面），降级为非阻塞提示 + Retry 按钮——Retry 走 ResourceHandler.retry()
    // 重载失败资源（服务器端 .png->.rpgmvp 回退已在 Kotlin 侧，绝大多数 404 不会
    // 走到这里），保留用户可见的自救入口（PR review 意见）
    (function () {
        var pleTimer = setInterval(function () {
            try {
                if (window.Graphics && typeof window.Graphics.printLoadingError === "function" && !window.Graphics.printLoadingError.__tyranorV1Patched) {
                    window.Graphics.printLoadingError = function (url) {
                        console.warn("[nw-polyfill-v1] Failed to load: " + url);
                        try {
                            if (document.getElementById("tyranorRetryEntry")) return;
                            var box = document.createElement("div");
                            box.id = "tyranorRetryEntry";
                            box.style.cssText = "position:fixed;left:0;right:0;bottom:10%;text-align:center;z-index:99999;font:16px sans-serif;";
                            var btn = document.createElement("button");
                            btn.textContent = "Retry";
                            btn.style.cssText = "padding:10px 28px;font-size:16px;cursor:pointer;background:#000;color:#fff;border:1px solid #888;border-radius:6px;";
                            btn.addEventListener("click", function () {
                                try {
                                    if (window.ResourceHandler && typeof window.ResourceHandler.retry === "function") { window.ResourceHandler.retry(); return; }
                                } catch (eR) {}
                                try { window.location.reload(); } catch (eL) {}
                            });
                            box.appendChild(btn);
                            (document.body || document.documentElement).appendChild(box);
                        } catch (eUi) {}
                    };
                    window.Graphics.printLoadingError.__tyranorV1Patched = true;
                    clearInterval(pleTimer);
                }
            } catch (e) {}
        }, 200);
        setTimeout(function () { try { clearInterval(pleTimer); } catch (e) {} }, 8000);
    })();

    console.log("[nw-polyfill-v1] installed (Object.create/exitFullscreen/printLoadingError)");
})();
