// v2-only compat - WebGL1-on-WebGL2 shim / save helpers / screen orientation fallbacks
// Injected via TyranoActivity only when rpgMakerVersion=v2 (MV/MZ), after __nwjs_polyfill.js
(function () {
    "use strict";
    if (window.__tyranorNwPolyfillV2) return;
    window.__tyranorNwPolyfillV2 = true;

    // 黑屏定位探针：确认注入的 earlyHook 是否在 WebView 中实际执行
    // （若此日志缺失 → HTML 未解析到注入点/服务端或 WebView 层问题）
    try { console.log("[v2] polyfill executing, window.nw=" + (typeof window.nw) + " doc.readyState=" + (document.readyState || "?")); } catch (eProbe) {}

    // 全局错误捕获：把 match/clamp 等读档错误的堆栈打到 console
    // （WebView console 会经 onConsoleMessage 落 logcat，可定位精确文件:行号）
    (function () {
        function reportError(e, source, lineno, colno) {
            try {
                var msg = (e && e.message) ? e.message : String(e);
                var stack = (e && e.stack) ? String(e.stack) : "";
                console.error("[v2-err] " + msg + " @ " + (source || "?") + ":" + (lineno || "?") + ":" + (colno || "?"));
                if (stack) { try { console.error("[v2-err-stack] " + stack.split("\n").slice(0, 8).join(" | ")); } catch (e2) {} }
            } catch (e3) {}
        }
        try {
            var origOnerror = window.onerror;
            window.onerror = function (msg, source, lineno, colno, error) {
                try { reportError(error || msg, source, lineno, colno); } catch (e) {}
                if (typeof origOnerror === "function") { try { return origOnerror.apply(this, arguments); } catch (e4) {} }
                return false;
            };
            if (typeof window.addEventListener === "function") {
                window.addEventListener("error", function (ev) {
                    try { reportError(ev && ev.error, ev && ev.filename, ev && ev.lineno, ev && ev.colno); } catch (e) {}
                }, true);
            }
        } catch (e5) {}
    })();

    // ---- screen.orientation helpers (backend-agnostic fallbacks) ----
    try {
        if (typeof window.screen === "undefined") window.screen = {};
        if (typeof window.screen.orientation === "undefined") window.screen.orientation = {};
        if (typeof window.screen.orientation.lock !== "function") { window.screen.orientation.lock = function () {}; }
        if (typeof window.screen.orientation.unlock !== "function") { window.screen.orientation.unlock = function () {}; }
    } catch (e) {}

    // ---- WebGL1-on-WebGL2 shim (no Java bridge dependency) ----
    // 修复：本宿主无 NWJSApi，原实现靠 isTranspileEnabled() 门控；
    // 无门控裸奔会导致 isTranspiling 全局泄漏 + getQueryParameter 未定义 +
    // bindTexture 强制改参数，所有 v2 游戏开局卡死。此处加等效门控：
    // 仅当 WebGL1 上下文不存在且 NWJSApi 提供 transpile 能力时才劫持，
    // 否则整段跳过保持原生 WebGL 行为。
    (function () {
        var hasWebGL2Canvas;
        try { hasWebGL2Canvas = !!(document.createElement("canvas").getContext("webgl2")); } catch (e) { hasWebGL2Canvas = false; }
        // 原实现在 webgl.js 顶部设置 window.hasWebGL2，移植时曾遗漏该赋值，
        // 导致 getContext 补丁的 hasWebGL2 门控永不生效
        window.hasWebGL2 = hasWebGL2Canvas;
        if (!hasWebGL2Canvas) return;
        var hasTranspile = (typeof window.NWJSApi !== "undefined" &&
            typeof NWJSApi.isTranspileEnabled === "function" && NWJSApi.isTranspileEnabled()) ||
            (typeof window.NWJSApi !== "undefined" && typeof NWJSApi.transpileToGLSL3 === "function");
        // Tyranor 无 NWJSApi：不劫持，保持原生 WebGL2 路径（Pixi 4.0.3 直接可用）
        if (!hasTranspile) return;

        function WebGLDummyExtension(gl) {
            this.gl = gl;
            this.createVertexArrayOES = function(){
                return this.gl.createVertexArray();
            };
            this.deleteVertexArrayOES = function(arrayObject){
                return this.gl.deleteVertexArray(arrayObject);
            };
            this.isVertexArrayOES = function(arrayObject){
                return this.gl.isVertexArray(arrayObject);
            };
            this.bindVertexArrayOES = function(arrayObject){
                return this.gl.bindVertexArray(arrayObject);
            };
            this.VERTEX_ATTRIB_ARRAY_DIVISOR_ANGLE = this.gl.VERTEX_ATTRIB_ARRAY_DIVISOR;
            this.drawArraysInstancedANGLE = function(...args){
                return this.gl.drawArraysInstanced(args);
            }
            this.drawElementsInstancedANGLE = function(...args){
                return this.gl.drawElementsInstanced(args);
            }
            this.vertexAttribDivisorANGLE = function(...args){
                return this.gl.vertexAttribDivisor(args);
            }
            this.vertexAttribDivisorANGLE = function(...args){
                return this.gl.vertexAttribDivisor(args);
            }
            this.COLOR_ATTACHMENT0_WEBGL = this.gl.COLOR_ATTACHMENT0;
            this.COLOR_ATTACHMENT1_WEBGL = this.gl.COLOR_ATTACHMENT1;
            this.COLOR_ATTACHMENT2_WEBGL = this.gl.COLOR_ATTACHMENT2;
            this.COLOR_ATTACHMENT3_WEBGL = this.gl.COLOR_ATTACHMENT3;
            this.COLOR_ATTACHMENT4_WEBGL = this.gl.COLOR_ATTACHMENT4;
            this.COLOR_ATTACHMENT5_WEBGL = this.gl.COLOR_ATTACHMENT5;
            this.COLOR_ATTACHMENT6_WEBGL = this.gl.COLOR_ATTACHMENT6;
            this.COLOR_ATTACHMENT7_WEBGL = this.gl.COLOR_ATTACHMENT7;
            this.COLOR_ATTACHMENT8_WEBGL = this.gl.COLOR_ATTACHMENT8;
            this.COLOR_ATTACHMENT9_WEBGL = this.gl.COLOR_ATTACHMENT9;
            this.COLOR_ATTACHMENT10_WEBGL = this.gl.COLOR_ATTACHMENT10;
            this.COLOR_ATTACHMENT11_WEBGL = this.gl.COLOR_ATTACHMENT11;
            this.COLOR_ATTACHMENT12_WEBGL = this.gl.COLOR_ATTACHMENT12;
            this.COLOR_ATTACHMENT13_WEBGL = this.gl.COLOR_ATTACHMENT13;
            this.COLOR_ATTACHMENT14_WEBGL = this.gl.COLOR_ATTACHMENT14;
            this.COLOR_ATTACHMENT15_WEBGL = this.gl.COLOR_ATTACHMENT15;
        
            this.DRAW_BUFFER0_WEBGL = this.gl.DRAW_BUFFER0;
            this.DRAW_BUFFER1_WEBGL = this.gl.DRAW_BUFFER1;
            this.DRAW_BUFFER2_WEBGL = this.gl.DRAW_BUFFER2;
            this.DRAW_BUFFER3_WEBGL = this.gl.DRAW_BUFFER3;
            this.DRAW_BUFFER4_WEBGL = this.gl.DRAW_BUFFER4;
            this.DRAW_BUFFER5_WEBGL = this.gl.DRAW_BUFFER5;
            this.DRAW_BUFFER6_WEBGL = this.gl.DRAW_BUFFER6;
            this.DRAW_BUFFER7_WEBGL = this.gl.DRAW_BUFFER7;
            this.DRAW_BUFFER8_WEBGL = this.gl.DRAW_BUFFER8;
            this.DRAW_BUFFER9_WEBGL = this.gl.DRAW_BUFFER9;
            this.DRAW_BUFFER10_WEBGL = this.gl.DRAW_BUFFER10;
            this.DRAW_BUFFER11_WEBGL = this.gl.DRAW_BUFFER11;
            this.DRAW_BUFFER12_WEBGL = this.gl.DRAW_BUFFER12;
            this.DRAW_BUFFER13_WEBGL = this.gl.DRAW_BUFFER13;
            this.DRAW_BUFFER14_WEBGL = this.gl.DRAW_BUFFER14;
            this.DRAW_BUFFER15_WEBGL = this.gl.DRAW_BUFFER15;
        
            this.MAX_COLOR_ATTACHMENTS_WEBGL = this.gl.MAX_COLOR_ATTACHMENTS;
            this.MAX_DRAW_BUFFERS_WEBGL = this.gl.MAX_DRAW_BUFFERS;
        
            this.drawBuffersWEBGL = function(...args){
                return this.gl.drawBuffers(args);
            }
        }
        
        const baseCanvasGetContext = HTMLCanvasElement.prototype.getContext;
        HTMLCanvasElement.prototype.getContext = function(contextType, contextAttributes = null){
            if(((contextType === "webgl") || (contextType === "experimental-webgl")) && window.hasWebGL2){
                console.log("WebGL1 context is requested. Returning WebGL2 context instead.")
                window.isTranspiling = true;
                return baseCanvasGetContext.apply(this,["webgl2", contextAttributes]);
            }
        
            window.isTranspiling = false;
        
            return baseCanvasGetContext.apply(this,[contextType, contextAttributes]);
        }
        
        const baseCreateShader = WebGL2RenderingContext.prototype.createShader;
        WebGL2RenderingContext.prototype.createShader = function(stype){
            if(!window.isTranspiling) return baseCreateShader.apply(this,[stype]);
        
            var shader = baseCreateShader.apply(this,[stype]);
            shader.type = stype;
            return shader;
        }
        
        const baseShaderSource = WebGL2RenderingContext.prototype.shaderSource;
        WebGL2RenderingContext.prototype.shaderSource = function(shader, source){
            if(!window.isTranspiling) return baseShaderSource.apply(this, [shader, source]);
        
                // shader transpile path (preserved verbatim when host provides NWJSApi)
                try {
                    if (typeof window.NWJSApi !== "undefined" && typeof NWJSApi.transpileToGLSL3 === "function") {
                        return baseShaderSource.apply(this, [shader, NWJSApi.transpileToGLSL3(source, shader.type == WebGL2RenderingContext.FRAGMENT_SHADER)]);
                    }
                } catch (e2) {}
                return baseShaderSource.apply(this, [shader, source]);
        }
        
        const baseGetExtension = WebGL2RenderingContext.prototype.getExtension;
        WebGL2RenderingContext.prototype.getExtension = function(name){
            if(!window.isTranspiling) return baseGetExtension.apply(this, [name]);
        
            switch(name){
                case "OES_vertex_array_object":
                case "ANGLE_instanced_arrays":
                case "WEBGL_draw_buffers":
                    return new WebGLDummyExtension(this);
                    break;
                case "WEBGL_color_buffer_float":
                case "OES_texture_half_float":
                    return baseGetExtension.apply(this, ["EXT_color_buffer_float"]);
                    break;
                case "EXT_disjoint_timer_query":
                    var ext = baseGetExtension.apply(this, ["EXT_disjoint_timer_query_webgl2"]);
                    var cpext = {
                        ...ext,
                        getQueryObject: function(...args){
                            // 原实现调用未定义的 getQueryParameter；改为经 ext.getQueryParameter 转发
                            try { if (ext && typeof ext.getQueryParameter === "function") return ext.getQueryParameter.apply(ext, args); } catch (e4) {}
                            return null;
                        }
                    }
                    return cpext;
                    break;
                default:
                    return baseGetExtension.apply(this, [name]);
                    break;
            }
        }
        
        const baseBindTexture1 = WebGLRenderingContext.prototype.bindTexture;
        WebGLRenderingContext.prototype.bindTexture = function(target, texture){
            baseBindTexture1.apply(this, [target, texture]);
        
            this.texParameteri(target, this.TEXTURE_MAG_FILTER, this.NEAREST);
            this.texParameteri(target, this.TEXTURE_MIN_FILTER, this.NEAREST);
            this.texParameteri(target, this.TEXTURE_WRAP_S, this.CLAMP_TO_EDGE);
            this.texParameteri(target, this.TEXTURE_WRAP_T, this.CLAMP_TO_EDGE);
        }
    })();

    // ---- overrides table（外部 runtime 的游戏专用改写表，本文件未移植） ----
    // 注：改写表未随本文件移植——polyfill 内没有
    // 消费方（无任何代码读取改写表并执行替换），保留死表只制造 review 噪音；
    // 如后续需要，应先实现改写引擎再按需恢复（历史版本见 git）。


    // =====================================================================
    // 存档反序列化修复（本质方案）
    //
    // 历史背景：此前 17 个 commit 沿"哪里崩补哪里"路线打了大量 per-class
    // 症状补丁（events/vehicles/followers/actors/tone/updateShadow/...），
    // 其中多个补丁自身还引入了新 bug（无限递归、poller 误停、null 赋值）。
    // 本地用游戏自带 JsonEx 1.3.4 做全链路复现后确认：
    //   1. JsonEx 1.3.4 编解码本身健全——数组保持真数组（JSON.parse 产物），
    //      带 @ 标记的对象只要 window["@值"] 能查到构造器，原型即正确恢复；
    //   2. 之前观察到的"原型丢失/plain object"只有一个来源：decode 时
    //      window["@值"] 查不到构造器（或存档数据本身缺失）；
    //   3. 毒存档（坏状态下保存）的 null 槽位是数据丢失，无法恢复，只能重建。
    //
    // 因此本节只保留两类逻辑：
    //   A. JsonEx.parse 出口单点 rehydrate——覆盖所有对象的原型恢复（本质）；
    //   B. repairGameObjects——毒存档数据丢失的字段/槽位兜底（数据重建）。
    // =====================================================================

    // 已告警节点去重用外部 WeakSet：标记写在存档对象上会被 JsonEx.stringify
    // 序列化进用户存档（PR review 意见），外部集合不污染数据
    var rehydrateWarnedAt = new WeakSet();
    function rehydrateTree(value, depth, seen) {
        if (!value || typeof value !== "object") return value;
        if (depth > 60) return value; // JsonEx.maxDepth=100，防御性限制
        if (!seen) seen = new WeakSet();
        // 共享引用与循环引用只处理一次：无 visited 集时最坏呈指数级重复遍历，
        // 大存档读档会长时间阻塞主线程（PR review 意见）
        if (seen.has(value)) return value;
        seen.add(value);
        if (Array.isArray(value)) {
            for (var i = 0; i < value.length; i++) {
                if (value[i] && typeof value[i] === "object") value[i] = rehydrateTree(value[i], depth + 1, seen);
            }
            return value;
        }
        var at = value["@"];
        if (typeof at === "string") {
            var ctor = window[at];
            if (ctor) {
                if (!(value instanceof ctor)) {
                    try { Object.setPrototypeOf(value, ctor.prototype); } catch (e) {}
                }
            } else if (!rehydrateWarnedAt.has(value)) {
                rehydrateWarnedAt.add(value);
                try { console.warn("[nw-polyfill-v2] JsonEx rehydrate: no ctor for @" + at); } catch (e2) {}
            }
        }
        for (var k in value) {
            if (value.hasOwnProperty(k)) {
                var child = value[k];
                if (child && typeof child === "object") value[k] = rehydrateTree(child, depth + 1, seen);
            }
        }
        return value;
    }

    // MV 1.6 JsonEx 标记 → 1.3.4 结构转换（本质修复的核心）。
    // 本类游戏引擎为 MV 1.3.4（JsonEx 只认 @），但存量存档来自 1.6 引擎：
    // @a=数组包装、@c=对象 identity、@r=循环引用回指。1.3.4 的 _decode 不认识
    // 这些标记，导致所有数组解成 {@c,@a} plain object（_events.filter 崩）、
    // 引用对象解成 {@r} 空壳（player.isTransferring 崩）。
    // 转换规则：{@c,@a:[...]} → 拆出数组并注册 idMap；{@r:id} → 回指 idMap；
    // 普通 @ 构造器标记原样保留，交由游戏 _decode 恢复原型。
    function convertJsonEx16To13(node, idMap, depth) {
        if (!node || typeof node !== "object") return node;
        if (depth > 80) return node; // JsonEx.maxDepth=100，防御性限制
        if (Array.isArray(node)) {
            for (var i = 0; i < node.length; i++) {
                node[i] = convertJsonEx16To13(node[i], idMap, depth + 1);
            }
            return node;
        }
        // @r 回指：返回已解码对象（引用必须在 @c 注册后出现，JSON 顺序保证）
        if (typeof node["@r"] !== "undefined") {
            var ref = idMap[node["@r"]];
            if (ref === undefined) {
                try { console.warn("[nw-polyfill-v2] JsonEx16 @r dangling: " + node["@r"]); } catch (eR) {}
                return null;
            }
            return ref;
        }
        // @a 数组包装：拆包（数组本体直接取用，children 递归转换）
        var wrapped = Object.prototype.hasOwnProperty.call(node, "@a") && Array.isArray(node["@a"]);
        var result = wrapped ? node["@a"] : {};
        var cid = node["@c"];
        if (typeof cid === "number") idMap[cid] = result; // 先注册再递归，支持自引用/循环
        if (typeof node["@"] === "string") result["@"] = node["@"];
        if (wrapped) {
            for (var wi = 0; wi < result.length; wi++) {
                result[wi] = convertJsonEx16To13(result[wi], idMap, depth + 1);
            }
            return result;
        }
        for (var k in node) {
            if (!node.hasOwnProperty(k)) continue;
            if (k === "@c" || k === "@a" || k === "@r" || k === "@") continue;
            if (k === "__proto__" || k === "constructor" || k === "prototype") continue; // 原型污染防御
            var child = node[k];
            result[k] = (child && typeof child === "object") ? convertJsonEx16To13(child, idMap, depth + 1) : child;
        }
        return result;
    }

    // JsonEx.parse hook：按引擎能力分流。
    // MV 1.6+/MZ 的 _decode 原生处理 @c/@a/@r 标记（循环引用/数组包装），
    // 必须走引擎原生 parse——convert 拆标记反而会破坏其预期结构
    // （1.6 游戏运行时 makeDeepCopy 即崩，rpg_core.js:9080）。
    // 仅 MV 1.3.x（_decode 只认 @）需要先 convert 再交 _decode。
    (function () {
        var parseTimer = setInterval(function () {
            try {
                if (typeof window.JsonEx === "undefined" || typeof window.JsonEx.parse !== "function" ||
                    typeof window.JsonEx._decode !== "function") return;
                if (window.JsonEx.parse.__tyranorV2Patched) { clearInterval(parseTimer); return; }
                // 引擎能力检测：_decode 源码含 @c/@a → 原生支持 1.6 标记
                var engineHandles16 = false;
                try {
                    var decodeSrc = String(window.JsonEx._decode);
                    engineHandles16 = decodeSrc.indexOf("@c") >= 0 || decodeSrc.indexOf("@a") >= 0;
                } catch (eSrc) {}
                var origParse = window.JsonEx.parse;
                window.JsonEx.parse = function (json) {
                    var result;
                    if (engineHandles16) {
                        // 1.6+/MZ：原生 parse（JSON.parse + _decode 全流程由引擎完成）
                        result = origParse.call(this, json);
                    } else {
                        // 1.3.x：优先走 origParse（游戏插件可能已覆写 JsonEx.parse 做
                        // 存档加密/压缩/字段迁移，绕过会丢失这些加工，PR review 意见）；
                        // 结果残留 1.6 标记（@c/@a/@r）时才回退 convert + _decode 路径
                        try { result = origParse.call(this, json); } catch (eOrig) { result = undefined; }
                        var needsConvert = true;
                        if (result && typeof result === "object") {
                            try { needsConvert = JSON.stringify(result).indexOf('"@') >= 0; } catch (eJ) { needsConvert = true; }
                        }
                        if (needsConvert) {
                            var tree = JSON.parse(json);
                            try { tree = convertJsonEx16To13(tree, {}, 0); } catch (eCv) {}
                            result = window.JsonEx._decode(tree);
                        }
                    }
                    try { rehydrateTree(result, 0); } catch (eRe) {}
                    return result;
                };
                window.JsonEx.parse.__tyranorV2Patched = true;
                clearInterval(parseTimer);
            } catch (e) {}
        }, 200);
        setTimeout(function () {
            try {
                // 慢设备上游戏脚本可能晚于 10s 就绪：超时不再静默停止（PR review 意见）
                if (!(window.JsonEx && window.JsonEx.parse && window.JsonEx.parse.__tyranorV2Patched)) {
                    console.warn("[nw-polyfill-v2] JsonEx.parse patch not installed within 10s; 1.3.x save conversion disabled");
                }
                clearInterval(parseTimer);
            } catch (e) {}
        }, 10000);
    })();

    // ---- repairGameObjects：毒存档数据丢失的字段/槽位兜底 ----
    // 仅处理 rehydrate 无法恢复的问题（null 槽位、缺失字段）；
    // 原型恢复已全部由 rehydrateTree 在 JsonEx.parse 出口完成，此处不再重复。
    function ensureActorRenderDefaults(obj) {
        // Game_Actor 渲染兜底：仅补 Sprite_Character 绘制立绘必需的字段
        if (!obj) return;
        try {
            if (obj._characterName === undefined || obj._characterName === null) { obj._characterName = ""; }
            if (obj._characterIndex === undefined || obj._characterIndex === null) { obj._characterIndex = 0; }
        } catch (e) {}
    }

    // 1.3.x 转换失败时 1.6 标记数组会保持 {@c,@a:[...]} 包装形态——数据完整仅未拆包，
    // 必须先拆包而不是当作数据丢失清空重建（PR review Critical 意见）
    function coerceToArray(v) {
        if (Array.isArray(v)) return v;
        if (v && typeof v === "object" && Array.isArray(v["@a"])) return v["@a"];
        return null;
    }

    function ensureCharacterDefaults(obj) {
        if (!obj) return;
        try {
            if (obj._opacity === undefined || obj._opacity === null) { obj._opacity = 255; }
            if (obj._blendMode === undefined || obj._blendMode === null) { obj._blendMode = 0; }
            if (obj._bushDepth === undefined || obj._bushDepth === null) { obj._bushDepth = 0; }
            if (obj._characterName === undefined || obj._characterName === null) { obj._characterName = ""; }
            if (obj._characterIndex === undefined || obj._characterIndex === null) { obj._characterIndex = 0; }
            if (obj._tileId === undefined || obj._tileId === null) { obj._tileId = 0; }
            if (obj._direction === undefined || obj._direction === null) { obj._direction = 2; }
            if (obj._pattern === undefined || obj._pattern === null) { obj._pattern = 1; }
            if (obj._priorityType === undefined || obj._priorityType === null) { obj._priorityType = 1; }
            if (obj._walkAnime === undefined || obj._walkAnime === null) { obj._walkAnime = true; }
            if (obj._stepAnime === undefined || obj._stepAnime === null) { obj._stepAnime = false; }
            if (obj._directionFix === undefined || obj._directionFix === null) { obj._directionFix = false; }
            if (obj._through === undefined || obj._through === null) { obj._through = false; }
            if (obj._transparent === undefined || obj._transparent === null) { obj._transparent = false; }
            if (obj._moveSpeed === undefined || obj._moveSpeed === null) { obj._moveSpeed = 4; }
            if (obj._moveFrequency === undefined || obj._moveFrequency === null) { obj._moveFrequency = 6; }
            if (obj._animationId === undefined || obj._animationId === null) { obj._animationId = 0; }
            if (obj._balloonId === undefined || obj._balloonId === null) { obj._balloonId = 0; }
            if (obj._animationPlaying === undefined || obj._animationPlaying === null) { obj._animationPlaying = false; }
            if (obj._balloonPlaying === undefined || obj._balloonPlaying === null) { obj._balloonPlaying = false; }
            if (obj._animationCount === undefined || obj._animationCount === null) { obj._animationCount = 0; }
            if (obj._stopCount === undefined || obj._stopCount === null) { obj._stopCount = 0; }
            if (obj._jumpCount === undefined || obj._jumpCount === null) { obj._jumpCount = 0; }
            if (obj._jumpPeak === undefined || obj._jumpPeak === null) { obj._jumpPeak = 0; }
            if (obj._movementSuccess === undefined || obj._movementSuccess === null) { obj._movementSuccess = true; }
        } catch (e) {}
    }

    function repairGameObjects() {
        try {
            if (typeof $gamePlayer === "undefined" || !$gamePlayer) return;
            ensureCharacterDefaults($gamePlayer);
            // _followers 整体缺失（毒存档）→ 重建（Game_Followers 构造器补齐 3 个 follower）
            if (!$gamePlayer._followers && typeof window.Game_Followers !== "undefined") {
                try { $gamePlayer._followers = new window.Game_Followers(); } catch (eFollow) {}
            }
            if ($gamePlayer._followers && $gamePlayer._followers._data) {
                try {
                    for (var fi = 0; fi < $gamePlayer._followers._data.length; fi++) {
                        var flw = $gamePlayer._followers._data[fi];
                        if (!flw && typeof window.Game_Follower !== "undefined") {
                            // follower 槽位为 null（毒存档）→ 重建
                            try { $gamePlayer._followers._data[fi] = new window.Game_Follower(fi); } catch (eF2) {}
                        }
                        ensureCharacterDefaults($gamePlayer._followers._data[fi]);
                    }
                } catch (e5) {}
            }
            // 队伍成员字段兜底：$gameParty.members() 返回 Game_Actor（非 Game_Character
            // 子类），完整 ensureCharacterDefaults 会注入约 20 个不属于它的移动字段并被
            // JsonEx.stringify 写进存档（PR review 意见）；渲染只需立绘两个字段
            if (typeof $gameParty !== "undefined" && $gameParty && typeof $gameParty.members === "function") {
                try {
                    var partyMembers = $gameParty.members();
                    if (partyMembers && typeof partyMembers.forEach === "function") {
                        for (var pm = 0; pm < partyMembers.length; pm++) {
                            ensureActorRenderDefaults(partyMembers[pm]);
                        }
                    }
                } catch (ePm) {}
            }
            if (typeof $gameMap !== "undefined" && $gameMap) {
                // 地图内事件字段兜底
                if ($gameMap._events) {
                    try {
                        for (var ei = 0; ei < $gameMap._events.length; ei++) {
                            ensureCharacterDefaults($gameMap._events[ei]);
                        }
                    } catch (eEv2) {}
                }
                // _vehicles 三槽兜底（毒存档 null 槽位是数据丢失，只能重建）
                var vehStates = [];
                try {
                    if (!$gameMap._vehicles || typeof $gameMap._vehicles.forEach !== "function") {
                        var coercedVeh = coerceToArray($gameMap._vehicles);
                        $gameMap._vehicles = coercedVeh != null ? coercedVeh : [];
                    }
                    var vhTypes = ["boat", "ship", "airship"];
                    for (var vti = 0; vti < 3; vti++) {
                        var vhCur = $gameMap._vehicles[vti];
                        if (!vhCur || typeof vhCur.isTransparent !== "function") {
                            var rebuilt = null;
                            if (vhCur && typeof window.Game_Vehicle !== "undefined") {
                                try {
                                    Object.setPrototypeOf(vhCur, window.Game_Vehicle.prototype);
                                    rebuilt = vhCur;
                                } catch (eVhProto) { rebuilt = null; }
                            }
                            if (!rebuilt && typeof window.Game_Vehicle !== "undefined" && typeof $dataSystem !== "undefined" && $dataSystem) {
                                try {
                                    rebuilt = new window.Game_Vehicle(vhTypes[vti]);
                                } catch (eVhNew) {
                                    rebuilt = null;
                                    try { console.warn("[nw-polyfill-v2] new Game_Vehicle('" + vhTypes[vti] + "') failed: " + (eVhNew && eVhNew.message)); } catch (eVhLog2) {}
                                }
                            }
                            if (!rebuilt && typeof window.Game_Vehicle !== "undefined") {
                                try {
                                    rebuilt = Object.create(window.Game_Vehicle.prototype);
                                    if (typeof rebuilt.initMembers === "function") {
                                        try { rebuilt.initMembers(); } catch (eInit) {}
                                    }
                                    rebuilt._type = vhTypes[vti];
                                } catch (eVhMan) { rebuilt = null; }
                            }
                            if (rebuilt) {
                                if (typeof rebuilt.setMapId === "function" && typeof $gameMap.mapId === "function") {
                                    try { rebuilt.setMapId($gameMap.mapId()); } catch (eVhMap) {}
                                }
                                $gameMap._vehicles[vti] = rebuilt;
                            }
                        } else if (vhCur._type === undefined || vhCur._type === null || vhCur._type === "") {
                            try { vhCur._type = vhTypes[vti]; } catch (eVhType) {}
                        }
                        var vhFinal = $gameMap._vehicles[vti];
                        vehStates.push(vhFinal && typeof vhFinal.shadowX === "function" ? "ok" : "BAD");
                    }
                    // 插件可能追加自定义载具槽位：只补齐到 3，不做截断（PR review 意见）
                    if ($gameMap._vehicles.length < 3) { $gameMap._vehicles.length = 3; }
                } catch (eAir3) {}

            }
            // $gameScreen 关键字段兜底（_flashColor 缺失 → flashColor()[3] undefined →
            // ScreenSprite.opacity setter 里 value.clamp 崩）
            if (typeof $gameScreen !== "undefined" && $gameScreen) {
                try {
                    if (!Array.isArray($gameScreen._flashColor) || $gameScreen._flashColor.length < 4) {
                        $gameScreen._flashColor = [0, 0, 0, 0];
                    }
                    if ($gameScreen._brightness === undefined || $gameScreen._brightness === null) { $gameScreen._brightness = 255; }
                    if ($gameScreen._tone === undefined || $gameScreen._tone === null || typeof $gameScreen._tone.clone !== "function") { $gameScreen._tone = [0, 0, 0, 0]; }
                    if ($gameScreen._pictures === undefined || $gameScreen._pictures === null) { $gameScreen._pictures = []; }
                    if ($gameScreen._shakePower === undefined || $gameScreen._shakePower === null) { $gameScreen._shakePower = 0; }
                    if ($gameScreen._shakeDuration === undefined || $gameScreen._shakeDuration === null) { $gameScreen._shakeDuration = 0; }
                    if ($gameScreen._shakeDirection === undefined || $gameScreen._shakeDirection === null) { $gameScreen._shakeDirection = 1; }
                    if ($gameScreen._zoomX === undefined || $gameScreen._zoomX === null) { $gameScreen._zoomX = 0; }
                    if ($gameScreen._zoomY === undefined || $gameScreen._zoomY === null) { $gameScreen._zoomY = 0; }
                    if ($gameScreen._zoomScale === undefined || $gameScreen._zoomScale === null) { $gameScreen._zoomScale = 1; }
                    if ($gameScreen._weatherType === undefined || $gameScreen._weatherType === null) { $gameScreen._weatherType = "none"; }
                    if ($gameScreen._weatherPower === undefined || $gameScreen._weatherPower === null) { $gameScreen._weatherPower = 0; }
                } catch (eScr) {}
            }
            // $gameParty._actors / $gameActors._data 缺失兜底（毒存档）；
            // {@c,@a} 包装形态先拆包，确实无法恢复才重建为空数组并告警（PR review 意见）
            if (typeof $gameParty !== "undefined" && $gameParty) {
                var coercedActors = coerceToArray($gameParty._actors);
                if (coercedActors != null) {
                    $gameParty._actors = coercedActors;
                } else if (!$gameParty._actors || typeof $gameParty._actors.filter !== "function") {
                    try { console.warn("[nw-polyfill-v2] $gameParty._actors unrecoverable, rebuild as empty"); $gameParty._actors = []; } catch (ePa) {}
                }
            }
            if (typeof $gameActors !== "undefined" && $gameActors) {
                var coercedData = coerceToArray($gameActors._data);
                if (coercedData != null) {
                    $gameActors._data = coercedData;
                } else if (!$gameActors._data || typeof $gameActors._data.filter !== "function") {
                    try { console.warn("[nw-polyfill-v2] $gameActors._data unrecoverable, rebuild as empty"); $gameActors._data = []; } catch (eAc) {}
                }
            }
            // locale 兜底（Game_System.isJapanese 等 .match 防御）
            if (typeof $dataSystem !== "undefined" && $dataSystem && typeof $dataSystem.locale !== "string") {
                try { $dataSystem.locale = "en"; } catch (eLocale) {}
            }
            if (typeof $gameSystem !== "undefined" && $gameSystem && typeof $gameSystem.locale !== "string") {
                try { $gameSystem.locale = "en"; } catch (eSys) {}
            }
        } catch (e) {}
    }


    // extractSaveContents hook：出口同步 repairGameObjects（loadGame 出口之外的第二调用点）
    (function () {
        var timer = setInterval(function () {
            try {
                if (typeof window.DataManager === "undefined" || typeof window.DataManager.extractSaveContents !== "function") return;
                if (DataManager.extractSaveContents.__tyranorV2Patched) { clearInterval(timer); return; }
                var orig = DataManager.extractSaveContents;
                DataManager.extractSaveContents = function (contents) {
                    var result = orig.call(this, contents);
                    try { repairGameObjects(); } catch (eR) {}
                    return result;
                };
                DataManager.extractSaveContents.__tyranorV2Patched = true;
                clearInterval(timer);
            } catch (e4) {}
        }, 200);
        setTimeout(function () { try { clearInterval(timer); } catch (e) {} }, 10000);
    })();

    // ---- 引擎方法参数兜底（毒存档字段的最后防线，成本一次性）----
    (function () {
        var patchTimer = setInterval(function () {
            try {
                if (typeof window.Game_CharacterBase !== "undefined" &&
                    typeof window.Game_CharacterBase.prototype.characterName === "function" &&
                    !window.Game_CharacterBase.prototype.characterName.__tyranorV2Patched) {
                    var origCN = window.Game_CharacterBase.prototype.characterName;
                    window.Game_CharacterBase.prototype.characterName = function () {
                        try {
                            var v = origCN.call(this);
                            return (v === undefined || v === null) ? "" : v;
                        } catch (e) { return ""; }
                    };
                    window.Game_CharacterBase.prototype.characterName.__tyranorV2Patched = true;
                }
                if (typeof window.Game_Actor !== "undefined" &&
                    typeof window.Game_Actor.prototype.characterName === "function" &&
                    !window.Game_Actor.prototype.characterName.__tyranorV2Patched) {
                    var origActorCN = window.Game_Actor.prototype.characterName;
                    window.Game_Actor.prototype.characterName = function () {
                        try {
                            var v = origActorCN.call(this);
                            return (v === undefined || v === null) ? "" : v;
                        } catch (e) { return ""; }
                    };
                    window.Game_Actor.prototype.characterName.__tyranorV2Patched = true;
                }
                if (typeof window.Game_CharacterBase !== "undefined" &&
                    typeof window.Game_CharacterBase.prototype.isTransparent === "function" &&
                    !window.Game_CharacterBase.prototype.isTransparent.__tyranorV2Patched) {
                    var origTransp = window.Game_CharacterBase.prototype.isTransparent;
                    window.Game_CharacterBase.prototype.isTransparent = function () {
                        try {
                            var v = origTransp.call(this);
                            return v === undefined || v === null ? false : v;
                        } catch (e) { return false; }
                    };
                    window.Game_CharacterBase.prototype.isTransparent.__tyranorV2Patched = true;
                }
                if (typeof window.ImageManager !== "undefined") {
                    if (typeof window.ImageManager.isBigCharacter === "function" && !window.ImageManager.isBigCharacter.__tyranorV2Patched) {
                        var origBig = window.ImageManager.isBigCharacter;
                        window.ImageManager.isBigCharacter = function (filename) {
                            if (typeof filename !== "string") return false;
                            try { return origBig.call(this, filename); } catch (e) { return false; }
                        };
                        window.ImageManager.isBigCharacter.__tyranorV2Patched = true;
                    }
                    if (typeof window.ImageManager.isObjectCharacter === "function" && !window.ImageManager.isObjectCharacter.__tyranorV2Patched) {
                        var origObj = window.ImageManager.isObjectCharacter;
                        window.ImageManager.isObjectCharacter = function (filename) {
                            if (typeof filename !== "string") return false;
                            try { return origObj.call(this, filename); } catch (e) { return false; }
                        };
                        window.ImageManager.isObjectCharacter.__tyranorV2Patched = true;
                    }
                }
                // Sprite/ScreenSprite opacity setter 兜底：value 非有限数字 → 0
                // （$gameScreen._flashColor[3] 或角色 _opacity 经毒存档后可能 undefined，
                // setter 里 value.clamp(0,255) 崩）
                function guardOpacitySetter(proto, tag) {
                    try {
                        if (proto.__tyranorOpacityGuarded) return;
                        var desc = Object.getOwnPropertyDescriptor(proto, "opacity");
                        if (!desc) return;
                        var origSet = desc.set;
                        var newDesc = {
                            get: desc.get,
                            set: function (value) {
                                try {
                                    if (typeof value !== "number" || isNaN(value)) value = 0;
                                    if (origSet) { return origSet.call(this, value); }
                                    this.alpha = value.clamp(0, 255) / 255;
                                } catch (e) {
                                    try { this.alpha = 0; } catch (e2) {}
                                }
                            },
                            configurable: true,
                        };
                        Object.defineProperty(proto, "opacity", newDesc);
                        proto.__tyranorOpacityGuarded = true;
                        if (tag) { try { console.log("[v2] opacity guard installed: " + tag); } catch (e3) {} }
                    } catch (e) {}
                }
                if (typeof window.Sprite !== "undefined" && window.Sprite.prototype) {
                    guardOpacitySetter(window.Sprite.prototype, "Sprite");
                }
                if (typeof window.ScreenSprite !== "undefined" && window.ScreenSprite.prototype) {
                    guardOpacitySetter(window.ScreenSprite.prototype, "ScreenSprite");
                }
                // 停止条件：所有类必须存在且已补丁。
                // 不能把 "typeof X === undefined" 当作"已完成"——polyfill 在游戏脚本
                // 加载前执行，首轮 tick 时所有类都是 undefined，误停会导致补丁永不安装。
                if (window.Game_CharacterBase && window.Game_CharacterBase.prototype.characterName && window.Game_CharacterBase.prototype.characterName.__tyranorV2Patched &&
                    window.Game_Actor && window.Game_Actor.prototype.characterName && window.Game_Actor.prototype.characterName.__tyranorV2Patched &&
                    window.ImageManager && window.ImageManager.isBigCharacter && window.ImageManager.isBigCharacter.__tyranorV2Patched) {
                    clearInterval(patchTimer);
                }
            } catch (e) {}
        }, 200);
        setTimeout(function () { try { clearInterval(patchTimer); } catch (e) {} }, 10000);
    })();

    // =====================================================================
    // 真文件系统接管（仅 v2 会话）
    //
    // 背景：基础兼容层把 fs 桩成**静默**空实现（existsSync 恒 false、readFileSync
    // 恒 ""、readdirSync 恒 []），且不产生任何日志。插件最典型的写法
    //     if (fs.existsSync(p)) table = JSON.parse(fs.readFileSync(p));
    // 在第一道门就落空：数据表没加载 → 后续查表得到 undefined → 被画进游戏文本
    // （实测现象：对话名字渲染成 `xxx[001undefined]`），而日志里查不到任何线索。
    //
    // 语义对齐 JoiPlay 的原生桥：fs 真读写游戏目录、__dirname 指向网页根、
    // nw.gui.App.dataPath 指向游戏目录下 AppData、require 能加载游戏目录内的
    // 自己模块（相对/绝对路径 + .js/.json + 目录 index）。宿主在 v2 会话注册了
    // window.TyranorFs 时本段生效；未注册（v0/v1）保持原空实现，行为不变。
    // =====================================================================
    (function () {
        var bridge = null;
        try { bridge = window.TyranorFs || null; } catch (e0) {}
        if (!bridge) {
            console.log("[nw-polyfill-v2] TyranorFs bridge absent; fs stays stubbed");
            return;
        }

        var baseDir = "";
        var dataDir = "";
        try { baseDir = String(bridge.baseDir() || ""); } catch (e1) {}
        try { dataDir = String(bridge.dataDir() || ""); } catch (e2) {}
        // 基础兼容层的 require：内建模块桩（path/os/util/...）由它提供。
        // 必须在任何包装之前捕获，否则拿到的是本段自己装的实现而形成自引用。
        var baseRequire = null;
        try { baseRequire = window.require; } catch (eBase) {}

        // ---- 路径与文本编解码小工具 ----
        function isBufferLike(v) { return !!(v && typeof v === "object" && typeof v._bin === "string"); }
        function toBuffer(b64) {
            try { return window.Buffer ? window.Buffer.from(b64 || "", "base64") : b64; } catch (e) { return b64; }
        }
        function bufferToBase64(buf) {
            if (isBufferLike(buf)) {
                try { return btoa(buf._bin); } catch (e) { return ""; }
            }
            return null;
        }
        function joinPath() {
            var a = Array.prototype.slice.call(arguments).filter(function (x) { return x !== undefined && x !== null && x !== ""; });
            return normalizeSlashes(a.join("/"));
        }
        // 折叠空段与 "." 段；保留 ".."（越界与否交给原生桥 canonical 校验）
        function normalizeSlashes(raw) {
            var s = String(raw === undefined || raw === null ? "" : raw).replace(/\\/g, "/");
            var out = [];
            s.split("/").forEach(function (seg) { if (seg !== "" && seg !== ".") out.push(seg); });
            var prefix = s.charAt(0) === "/" ? "/" : "";
            return prefix + out.join("/");
        }
        function dirOf(p) {
            var s = normalizeSlashes(String(p || ""));
            if (!s) return ".";
            var i = s.lastIndexOf("/");
            if (i > 0) return s.slice(0, i);
            if (i === 0) return "/";
            return ".";
        }
        function resolvePath(p, fromDir) {
            var s = String(p === undefined || p === null ? "" : p).replace(/\\/g, "/");
            if (!s) return normalizeSlashes(baseDir);
            if (/^[a-zA-Z]:\//.test(s) || s.charAt(0) === "/") return normalizeSlashes(s);
            return joinPath(fromDir || baseDir, s);
        }
        function nodeErr(code, msg) {
            var e = new Error(msg);
            e.code = code;
            return e;
        }

        // ---- fs：真读写 ----
        // Node 的编码参数有两种形态：字符串（'utf8'）或 options 对象（{encoding:'utf8'}）。
        // 插件两种写法都常见，必须统一解析——只认字符串会让 options 形态走进
        // 「无编码」分支返回 Buffer，拼进字符串即得到 "ãã..." 这类乱码。
        function pickEncoding(arg, fallback) {
            if (typeof arg === "string") return arg;
            if (arg && typeof arg === "object" && typeof arg.encoding === "string") return arg.encoding;
            return fallback;
        }
        function readTextOrThrow(p) {
            var v = bridge.readText(p);
            if (v === null || v === undefined) throw nodeErr("ENOENT", "ENOENT: no such file, readFileSync '" + p + "'");
            return v;
        }
        function readBufferOrThrow(p) {
            var b64 = bridge.readBase64(p);
            if (b64 === null || b64 === undefined) throw nodeErr("ENOENT", "ENOENT: no such file, readFileSync '" + p + "'");
            return toBuffer(b64);
        }
        function statObject(p) {
            var raw = bridge.stat(p);
            if (!raw) return { isFile: function () { return false; }, isDirectory: function () { return false; }, isSymbolicLink: function () { return false; }, size: 0, mtime: new Date(0) };
            var o = {};
            try { o = JSON.parse(raw); } catch (e) { o = {}; }
            var isF = !!o.file, isD = !!o.dir;
            return {
                isFile: function () { return isF; },
                isDirectory: function () { return isD; },
                isSymbolicLink: function () { return false; },
                size: o.size || 0,
                mtime: new Date(o.mtime || 0),
                mtimeMs: o.mtime || 0
            };
        }

        var realFs = {
            existsSync: function (p) { try { return bridge.exists(p) === true; } catch (e) { return false; } },
            exists: function (p, cb) { if (typeof cb === "function") setTimeout(function () { cb(realFs.existsSync(p)); }, 0); },
            readFileSync: function (p, enc) {
                // Node 语义：无编码 → Buffer；有编码 → 字符串（与 JoiPlay 的 "\b\b\b" 抛错等价）
                var encoding = pickEncoding(enc, undefined);
                if (encoding === undefined || encoding === null || encoding === "") return readBufferOrThrow(p);
                var e = String(encoding).toLowerCase();
                if (e === "utf8" || e === "utf-8") return readTextOrThrow(p);
                var soft = readBufferOrThrow(p);
                if (e === "base64") return bufferToBase64(soft);
                return soft.toString(encoding);
            },
            readFile: function (p, o, cb) {
                if (typeof o === "function") { cb = o; o = undefined; }
                if (typeof cb === "function") setTimeout(function () {
                    try { cb(null, realFs.readFileSync(p, o)); }
                    catch (err) { cb(err); }
                }, 0);
                return undefined;
            },
            writeFileSync: function (p, data, enc) {
                var b64 = bufferToBase64(data);
                if (b64 !== null) { bridge.writeBase64(p, b64); return; }
                var encoding = pickEncoding(enc, "utf8");
                var e = String(encoding || "utf8").toLowerCase();
                // 非 utf8 文本编码：先按该编码转字节再落盘，避免写出与 Node 不同码点的文件
                if (e === "utf8" || e === "utf-8" || e === "ascii" || e === "binary" || e === "latin1") {
                    bridge.writeText(p, typeof data === "string" ? data : String(data));
                } else {
                    var tmp = window.Buffer ? window.Buffer.from(String(data), e) : null;
                    if (tmp && typeof tmp._bin === "string") {
                        try { bridge.writeBase64(p, btoa(tmp._bin)); } catch (e2) { bridge.writeText(p, String(data)); }
                    } else {
                        bridge.writeText(p, String(data));
                    }
                }
            },
            writeFile: function (p, data, o, cb) {
                if (typeof o === "function") { cb = o; }
                if (typeof cb === "function") setTimeout(function () {
                    try { realFs.writeFileSync(p, data); cb(null); } catch (err) { cb(err); }
                }, 0);
            },
            appendFileSync: function (p, data, enc) {
                var prev = "";
                try { prev = realFs.existsSync(p) ? readTextOrThrow(p) : ""; } catch (e) { prev = ""; }
                var add = isBufferLike(data) ? data.toString("utf8") : String(data);
                bridge.writeText(p, prev + add);
            },
            appendFile: function (p, data, o, cb) {
                if (typeof o === "function") { cb = o; }
                if (typeof cb === "function") setTimeout(function () {
                    try { realFs.appendFileSync(p, data); cb(null); } catch (err) { cb(err); }
                }, 0);
            },
            readdirSync: function (p) {
                var raw = bridge.readdir(p);
                try { return JSON.parse(raw || "[]"); } catch (e) { return []; }
            },
            readdir: function (p, o, cb) {
                if (typeof o === "function") { cb = o; }
                if (typeof cb === "function") setTimeout(function () { cb(null, realFs.readdirSync(p)); }, 0);
            },
            mkdirSync: function (p) { bridge.makeDirs(p); },
            mkdir: function (p, o, cb) {
                if (typeof o === "function") { cb = o; }
                if (typeof cb === "function") setTimeout(function () { try { bridge.makeDirs(p); cb(null); } catch (err) { cb(err); } }, 0);
            },
            unlinkSync: function (p) { bridge.remove(p); },
            unlink: function (p, cb) { if (typeof cb === "function") setTimeout(function () { try { bridge.remove(p); cb(null); } catch (err) { cb(err); } }, 0); },
            statSync: function (p) { return statObject(p); },
            lstatSync: function (p) { return statObject(p); },
            fstatSync: function (p) { return statObject(p); },
            stat: function (p, cb) { if (typeof cb === "function") setTimeout(function () { cb(null, statObject(p)); }, 0); },
            lstat: function (p, cb) { if (typeof cb === "function") setTimeout(function () { cb(null, statObject(p)); }, 0); },
            realpathSync: function (p) { return resolvePath(p, baseDir); },
            renameSync: function (from, to) {
                var b64 = bridge.readBase64(from);
                if (b64 === null) throw nodeErr("ENOENT", "ENOENT: no such file, renameSync '" + from + "'");
                bridge.writeBase64(to, b64);
                bridge.remove(from);
            },
            rename: function (from, to, cb) { if (typeof cb === "function") setTimeout(function () { try { realFs.renameSync(from, to); cb(null); } catch (e) { cb(e); } }, 0); },
            copyFileSync: function (from, to) { var b64 = bridge.readBase64(from); if (b64 === null) throw nodeErr("ENOENT", "ENOENT: no such file, copyFileSync '" + from + "'"); bridge.writeBase64(to, b64); },
            copyFile: function (from, to, cb) { if (typeof cb === "function") setTimeout(function () { try { realFs.copyFileSync(from, to); cb(null); } catch (e) { cb(e); } }, 0); },
            chmodSync: function () {}, chownSync: function () {},
            readlinkSync: function (p) { return p; },
            truncateSync: function (p) { try { bridge.writeText(p, ""); } catch (e) {} },
            // 流式接口保持桩：数据表类插件几乎不用，真实现成本高收益低
            createReadStream: function () { return { on: function () { return this; }, once: function () { return this; }, pipe: function () { return this; }, read: function () {}, close: function () {} }; },
            createWriteStream: function () { return { on: function () { return this; }, once: function () { return this; }, write: function () {}, end: function () {}, close: function () {} }; },
            watch: function () { return { close: function () {}, on: function () { return this; } }; },
            watchFile: function () {}, unwatchFile: function () {},
            openSync: function () { return 0; },
            open: function (p, f, m, cb) { if (typeof m === "function") { cb = m; } if (typeof cb === "function") setTimeout(function () { cb(null, 0); }, 0); },
            closeSync: function () {}, close: function (fd, cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
            readSync: function () { return 0; }, writeSync: function () { return 0; },
            promises: {
                readFile: function (p, enc) { return new Promise(function (res, rej) { try { res(realFs.readFileSync(p, enc)); } catch (e) { rej(e); } }); },
                writeFile: function (p, d) { return new Promise(function (res, rej) { try { realFs.writeFileSync(p, d); res(); } catch (e) { rej(e); } }); },
                appendFile: function (p, d) { return new Promise(function (res, rej) { try { realFs.appendFileSync(p, d); res(); } catch (e) { rej(e); } }); },
                readdir: function (p) { return new Promise(function (res) { res(realFs.readdirSync(p)); }); },
                mkdir: function (p) { return new Promise(function (res) { bridge.makeDirs(p); res(); }); },
                unlink: function (p) { return new Promise(function (res, rej) { try { bridge.remove(p); res(); } catch (e) { rej(e); } }); },
                stat: function (p) { return new Promise(function (res) { res(statObject(p)); }); },
                copyFile: function (a, b) { return new Promise(function (res, rej) { try { realFs.copyFileSync(a, b); res(); } catch (e) { rej(e); } }); }
            }
        };

        // ---- path 语义修正（属同一族：文件定位会用到，且原桩静默给出错误结果）----
        // 原桩 relative() 直接返回 to、normalize() 不折叠 ".."，插件据此拼出的路径会错位。
        // 这里补齐 POSIX 语义（Node 行为），不改动其他成员。
        (function () {
            var pathMod = null;
            try { pathMod = baseRequire ? baseRequire("path") : null; } catch (e) {}
            if (!pathMod) return;
            function segments(p) {
                var s = String(p === undefined || p === null ? "" : p).replace(/\\/g, "/");
                var abs = s.charAt(0) === "/";
                var out = [];
                s.split("/").forEach(function (part) {
                    if (part === "" || part === ".") return;
                    if (part === "..") { if (out.length && out[out.length - 1] !== "..") out.pop(); else if (!abs) out.push(".."); return; }
                    out.push(part);
                });
                return { abs: abs, parts: out };
            }
            pathMod.normalize = function (p) {
                var seg = segments(p);
                var body = seg.parts.join("/");
                if (seg.abs) return "/" + body;
                return body || ".";
            };
            pathMod.resolve = function () {
                var args = Array.prototype.slice.call(arguments).filter(function (x) { return x !== undefined && x !== null && x !== ""; });
                var acc = "";
                for (var i = args.length - 1; i >= 0; i--) {
                    var s = String(args[i]).replace(/\\/g, "/");
                    if (!s) continue;
                    acc = acc ? (s.replace(/\/+$/, "") + "/" + acc) : s;
                    if (s.charAt(0) === "/") { acc = "/" + acc.replace(/^\/+/, ""); break; }
                }
                if (acc.charAt(0) !== "/") acc = joinPath(baseDir, acc);
                return pathMod.normalize(acc);
            };
            pathMod.relative = function (from, to) {
                var a = segments(pathMod.resolve(from));
                var b = segments(pathMod.resolve(to));
                if (a.abs !== b.abs) return pathMod.resolve(to);
                var i = 0;
                while (i < a.parts.length && i < b.parts.length && a.parts[i] === b.parts[i]) i++;
                var up = [];
                for (var j = i; j < a.parts.length; j++) up.push("..");
                var down = b.parts.slice(i);
                var rel = up.concat(down).join("/");
                return rel || "";
            };
            pathMod.dirname = function (p) {
                var s = String(p === undefined || p === null ? "" : p).replace(/\\/g, "/");
                if (!s) return ".";
                s = s.replace(/\/+$/, "");
                if (!s) return "/";
                var i = s.lastIndexOf("/");
                if (i < 0) return ".";
                if (i === 0) return "/";
                return s.slice(0, i);
            };
            pathMod.isAbsolute = function (p) {
                var s = String(p === undefined || p === null ? "" : p);
                return s.charAt(0) === "/" || /^[a-zA-Z]:[\\/]/.test(s);
            };
            pathMod.join = function () {
                var a = Array.prototype.slice.call(arguments).filter(function (x) { return x !== undefined && x !== null && x !== ""; });
                if (!a.length) return ".";
                return pathMod.normalize(a.join("/"));
            };
        })();

        // ---- 模块加载（require）：能加载游戏目录内的自己模块 ----
        var moduleCache = {};
        var dirStack = [baseDir];
        function currentDir() { return dirStack.length ? dirStack[dirStack.length - 1] : baseDir; }
        function stripBom(s) { return s && s.charCodeAt(0) === 0xFEFF ? s.slice(1) : s; }
        function tryLoad(absPath) {
            return bridge.isFile(absPath) === true;
        }
        // 解析候选：精确 → .js → .json → .cjs → 目录 index
        function resolveModulePath(spec, fromDir) {
            var abs = resolvePath(spec, fromDir);
            var cands = [abs, abs + ".js", abs + ".json", abs + ".cjs",
                joinPath(abs, "index.js"), joinPath(abs, "index.json")];
            for (var i = 0; i < cands.length; i++) { if (tryLoad(cands[i])) return cands[i]; }
            return null;
        }
        function loadModule(absPath) {
            if (moduleCache[absPath]) return moduleCache[absPath].exports;
            var code = readTextOrThrow(absPath);
            var mod = { exports: {}, id: absPath, filename: absPath, loaded: false, parent: null, children: [] };
            moduleCache[absPath] = mod;  // 先入缓存，支持循环依赖（与 Node 一致）
            var dir = dirOf(absPath);
            dirStack.push(dir);
            try {
                if (/\.json$/i.test(absPath)) {
                    mod.exports = JSON.parse(stripBom(code));
                } else {
                    var fn = new Function("exports", "require", "module", "__filename", "__dirname", stripBom(code));
                    fn(mod.exports, makeRequire(dir), mod, absPath, dir);
                }
                mod.loaded = true;
            } catch (e) {
                delete moduleCache[absPath];  // 加载失败回滚，下次可重试
                console.warn("[nw-polyfill-v2] require failed: " + absPath + " :: " + (e && e.message));
                throw e;
            } finally {
                dirStack.pop();
            }
            return mod.exports;
        }
        function makeRequire(fromDir) {
            var req = function (name) {
                var n = String(name);
                if (n === "fs") return realFs;
                // 相对/绝对路径 → 真读游戏目录
                if (n.charAt(0) === "." || n.charAt(0) === "/" || /^[a-zA-Z]:[\\/]/.test(n)) {
                    var abs = resolveModulePath(n, fromDir);
                    if (abs) return loadModule(abs);
                    console.warn("[nw-polyfill-v2] require: module not found in game dir: " + n + " (from " + fromDir + ")");
                    return {};
                }
                // 裸模块名交给内建桩（baseRequire）；未知名的告警在外层包装统一处理
                if (baseRequire && baseRequire !== req) {
                    try { return baseRequire(n); } catch (e) {}
                }
                return {};
            };
            req.resolve = function (name) {
                var abs = resolveModulePath(String(name), fromDir);
                return abs || String(name);
            };
            req.cache = moduleCache;
            return req;
        }

        var KNOWN_BUILTINS = ["path", "os", "util", "events", "child_process", "crypto",
            "url", "querystring", "nw.gui", "buffer", "nw", "gui", "http", "https", "zlib", "stream"];
        try { window.require = makeRequire(baseDir); } catch (e3) {}
        try { if (typeof globalThis !== "undefined") globalThis.require = window.require; } catch (e4) {}
        // 未知裸模块名：基础层会静默返回 {}，这里显式记录，避免问题再次无声无息
        try {
            var wrappedRequire = window.require;
            window.require = function (name) {
                var n = String(name);
                var isPathLike = n.charAt(0) === "." || n.charAt(0) === "/" || /^[a-zA-Z]:[\\/]/.test(n);
                if (!isPathLike && n !== "fs" && KNOWN_BUILTINS.indexOf(n) < 0) {
                    console.warn("[nw-polyfill-v2] require: '" + n + "' is not a game module nor a builtin; stubbed as {}");
                }
                return wrappedRequire(n);
            };
            window.require.resolve = wrappedRequire.resolve;
            window.require.cache = wrappedRequire.cache;
            if (typeof globalThis !== "undefined") globalThis.require = window.require;
        } catch (e5) {}

        // ---- Buffer 实例方法补齐 ----
        // 基础层的 Buffer 是「带 _bin 的普通对象」，只有 toString/length；
        // 而 fs 现在返回真 Buffer，插件常见的二进制解析（readUInt8/readInt32LE/slice/
        // indexOf/equals 等）与序列化（toJSON）全是 undefined，一调用即崩。
        // 这里在保持 _bin 契约（isBuffer 依赖它）的前提下补齐常用实例方法。
        (function () {
            var B = window.Buffer;
            if (!B || B.__tyranorBufferPatched) return;
            B.__tyranorBufferPatched = true;

            function bytesOf(buf) {
                var s = buf && typeof buf._bin === "string" ? buf._bin : "";
                var out = [];
                for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 0xff);
                return out;
            }
            function wrap(bytes) {
                var s = "";
                for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i] & 0xff);
                return B.from(s);
            }
            function attach(obj) {
                obj._tBytes = null;
                obj.slice = function (start, end) {
                    var b = bytesOf(this);
                    var n = b.length;
                    var a = start === undefined ? 0 : (start < 0 ? Math.max(n + start, 0) : Math.min(start, n));
                    var z = end === undefined ? n : (end < 0 ? Math.max(n + end, 0) : Math.min(end, n));
                    if (z < a) z = a;
                    return wrap(b.slice(a, z));
                };
                obj.subarray = function (a, b) { return this.slice(a, b); };
                obj.toJSON = function () { return { type: "Buffer", data: bytesOf(this) }; };
                obj.equals = function (other) {
                    var a = bytesOf(this), c = bytesOf(other);
                    if (a.length !== c.length) return false;
                    for (var i = 0; i < a.length; i++) if (a[i] !== c[i]) return false;
                    return true;
                };
                obj.compare = function (other) {
                    var a = bytesOf(this), c = bytesOf(other);
                    var n = Math.min(a.length, c.length);
                    for (var i = 0; i < n; i++) { if (a[i] !== c[i]) return a[i] < c[i] ? -1 : 1; }
                    return a.length === c.length ? 0 : (a.length < c.length ? -1 : 1);
                };
                obj.indexOf = function (needle, offset) {
                    var hay = bytesOf(this);
                    var pat;
                    if (typeof needle === "number") {
                        pat = [needle & 0xff];               // Node 允许传字节值
                    } else if (typeof needle === "string") {
                        pat = String(needle).split("").map(function (ch) { return ch.charCodeAt(0) & 0xff; });
                    } else {
                        pat = bytesOf(needle);
                    }
                    if (!pat.length) return -1;
                    var from = offset && offset > 0 ? offset : 0;
                    outer: for (var i = from; i <= hay.length - pat.length; i++) {
                        for (var j = 0; j < pat.length; j++) if (hay[i + j] !== pat[j]) continue outer;
                        return i;
                    }
                    return -1;
                };
                obj.includes = function (needle, offset) { return this.indexOf(needle, offset) >= 0; };
                // 定长读取：LE/BE 与无符号/有符号，覆盖 RPG Maker 插件常见的二进制解析
                // （小端=最低有效字节在前，故按 256^i 加权；大端则依次左移）
                function reader(size, signed, little) {
                    return function (offset) {
                        var b = bytesOf(this);
                        var off = offset || 0;
                        if (off + size > b.length || off < 0) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to access memory outside buffer bounds");
                        var v = 0;
                        for (var i = 0; i < size; i++) {
                            var idx = little ? (size - 1 - i) : i;
                            v = v * 256 + b[off + idx];
                        }
                        if (signed) {
                            var limit = Math.pow(2, size * 8 - 1);
                            if (v >= limit) v -= Math.pow(2, size * 8);
                        }
                        return v;
                    };
                }
                var readers = { readUInt8: [1, false, true], readInt8: [1, true, true],
                    readUInt16LE: [2, false, true], readUInt16BE: [2, false, false],
                    readInt16LE: [2, true, true], readInt16BE: [2, true, false],
                    readUInt32LE: [4, false, true], readUInt32BE: [4, false, false],
                    readInt32LE: [4, true, true], readInt32BE: [4, true, false] };
                Object.keys(readers).forEach(function (name) {
                    var cfg = readers[name];
                    obj[name] = reader(cfg[0], cfg[1], cfg[2]);
                    // Node 的别名写法（UInt 与 Uint 并存）
                    if (name.indexOf("UInt") >= 0) obj[name.replace("UInt", "Uint")] = obj[name];
                });
                return obj;
            }

            var origFrom = B.from;
            B.from = function () { return attach(origFrom.apply(B, arguments)); };
            var origAlloc = B.alloc;
            B.alloc = function () { return attach(origAlloc.apply(B, arguments)); };
            B.allocUnsafe = B.alloc;
            B.allocUnsafeSlow = B.alloc;
            // 静态方法补齐
            B.isBuffer = B.isBuffer || function (o) { return !!(o && typeof o === "object" && typeof o._bin === "string"); };
            B.compare = function (a, b) { return B.from(a).compare(B.from(b)); };
            // 兼容 base64 文本 → Buffer
            try {
                if (!window.__tyranorBufferSelfTest) {
                    window.__tyranorBufferSelfTest = true;
                    var probe = B.from("abc");
                    if (typeof probe.readUInt8 !== "function") {
                        console.warn("[nw-polyfill-v2] Buffer patch did not take effect");
                    }
                }
            } catch (eProbe) {}
        })();

        // ---- 环境路径：__dirname / process / nw.gui.App.dataPath ----
        try { window.__dirname = baseDir; } catch (e6) {}
        try { window.__filename = joinPath(baseDir, "index.html"); } catch (e7) {}
        try { if (window.process) {
            window.process.cwd = function () { return baseDir; };
            if (!window.process.mainModule) window.process.mainModule = {};
            window.process.mainModule.filename = joinPath(baseDir, "index.html");
        } } catch (e8) {}
        try {
            // 基础层只把 nw.gui 桩挂在 window.gui 上，而插件普遍写 `nw.gui.App.dataPath`
            // （NW.js 里 nw 模块带 .gui 成员）。这里补齐别名并统一指向游戏目录下的 AppData，
            // 让 require('nw.gui')、window.gui、window.nw.gui 三处取到同一对象。
            var guiStub = null;
            try { guiStub = (baseRequire ? baseRequire("nw.gui") : null) || window.gui || null; } catch (eGui) {}
            if (guiStub) {
                if (window.nw && !window.nw.gui) { try { window.nw.gui = guiStub; } catch (eAlias) {} }
                if (window.nw && window.nw.gui && window.nw.gui.App) window.nw.gui.App.dataPath = dataDir;
                if (window.gui && window.gui.App) window.gui.App.dataPath = dataDir;
                if (guiStub.App) guiStub.App.dataPath = dataDir;
            }
        } catch (e9) {}

        // 暴露给排查用：确认插件读到的是真实路径
        try {
            window.__tyranorFsState = { baseDir: baseDir, dataDir: dataDir, real: true };
            console.log("[nw-polyfill-v2] real fs bridge installed (__dirname=" + baseDir + ", dataPath=" + dataDir + ")");
        } catch (e10) {}
    })();

    console.log("[nw-polyfill-v2] compat installed (webgl shims + screen orientation + json rehydrate)");
})();
