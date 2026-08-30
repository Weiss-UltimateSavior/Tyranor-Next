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

    // 单张贴图 404 不卡死场景：printLoadingError 降级为警告，
    // 不再置 _loadingCount = -Infinity（服务器端 .png->.rpgmvp 回退已在 Kotlin 侧）
    (function () {
        var pleTimer = setInterval(function () {
            try {
                if (window.Graphics && typeof window.Graphics.printLoadingError === "function" && !window.Graphics.printLoadingError.__tyranorV1Patched) {
                    window.Graphics.printLoadingError = function (url) {
                        console.warn("[nw-polyfill-v1] printLoadingError suppressed for", url);
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
