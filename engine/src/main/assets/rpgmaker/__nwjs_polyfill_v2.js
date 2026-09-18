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
        var osStartTime = Date.now();
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
                // 原生能力模块（本段自建，优先于基础层桩）
                if (nodeModules[n]) return nodeModules[n];
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

        // =====================================================================
        // 原生能力模块：crypto / zlib（走 TyranorEnv 桥）
        //
        // 哈希、HMAC、随机数、KDF、AES、zlib 若用纯 JS 复刻，要么体积庞大
        // （AES/inflate 实现），要么极易写出**静默错值**（自研哈希返回空串或错摘要，
        // 比抛错更难排查）。因此这些统一交给 Android 原生实现，本段只做 Node 形态的
        // 包装：参数编解码、Update/final 链式语义、错误码。
        // =====================================================================
        var env = null;
        try { env = window.TyranorEnv || null; } catch (eEnv) {}
        if (!env) {
            console.log("[nw-polyfill-v2] TyranorEnv bridge absent; crypto/zlib stay stubbed");
        }

        function toB64(value) {
            if (value === undefined || value === null) return "";
            if (typeof value === "string") {
                // 字符串按 utf8 编码（Node 默认行为）
                try { return btoa(unescape(encodeURIComponent(value))); } catch (e) { return ""; }
            }
            if (isBufferLike(value)) { try { return btoa(value._bin); } catch (e) { return ""; } }
            if (value && typeof value.length === "number") {
                var s = "";
                for (var i = 0; i < value.length; i++) s += String.fromCharCode(value[i] & 0xff);
                try { return btoa(s); } catch (e) { return ""; }
            }
            if (value && typeof value.toString === "function") {
                try { return btoa(unescape(encodeURIComponent(String(value)))); } catch (e) { return ""; }
            }
            return "";
        }
        function fromB64(b64) {
            if (!b64) return null;
            try { return toBuffer(b64); } catch (e) { return null; }
        }
        function binaryFromB64(b64) {
            if (!b64) return null;
            try { return atob(b64); } catch (e) { return null; }
        }
        function ioError(code, message) { return nodeErr(code, message); }

        // ---- 哈希（crypto.createHash / Hmac）----
        function makeHash(algorithm) {
            var chunks = [];
            var finished = false;
            var api = {
                update: function (data, inputEncoding) {
                    chunks.push(toB64(data));
                    return api;
                },
                digest: function (outputEncoding) {
                    if (finished) throw ioError("ERR_CRYPTO_HASH_FINALIZED", "Digest already called");
                    finished = true;
                    if (!env) throw ioError("ERR_NOT_IMPLEMENTED", "crypto unavailable without native bridge");
                    var combined = chunks.length ? fromB64(concatB64(chunks)) : toBuffer("");
                    // Node 语义：无编码参数返回 Buffer，传编码才返回字符串。
                    // 一律返回字符串会让「把摘要当 Buffer 继续用」的代码拿到空 _bin。
                    var result = env.digest(algorithm, combined && combined._bin ? btoa(combined._bin) : "", outputEncoding ? outputEncoding : "base64");
                    if (!result) throw ioError("ERR_CRYPTO_INVALID_DIGEST", "Digest failed: " + algorithm);
                    return outputEncoding ? String(result) : fromB64(String(result));
                }
            };
            return api;
        }
        function concatB64(chunks) {
            var s = "";
            for (var i = 0; i < chunks.length; i++) {
                try { s += atob(chunks[i] || ""); } catch (e) {}
            }
            try { return btoa(s); } catch (e) { return ""; }
        }
        function makeHmac(algorithm, key) {
            var chunks = [];
            var finished = false;
            var api = {
                update: function (data, inputEncoding) { chunks.push(toB64(data)); return api; },
                digest: function (outputEncoding) {
                    if (finished) throw ioError("ERR_CRYPTO_HMAC_FINALIZED", "Digest already called");
                    finished = true;
                    if (!env) throw ioError("ERR_NOT_IMPLEMENTED", "crypto unavailable without native bridge");
                    var result = env.hmac(algorithm, toB64(key), concatB64(chunks), outputEncoding ? outputEncoding : "base64");
                    if (!result) throw ioError("ERR_CRYPTO_INVALID_KEYLEN", "Hmac failed: " + algorithm);
                    // 同 createHash().digest()：无编码返回 Buffer
                    return outputEncoding ? String(result) : fromB64(String(result));
                }
            };
            return api;
        }

        // ---- Cipher/Decipher（Node 的 createCipheriv 语义）----
        // Node 是流式 API（update 累计 + final 收尾）；宿主桥是一次性调用，
        // 这里把数据缓存到 final，语义对外一致，代价是「流式处理大文件」变全内存。
        function makeCipher(algorithm, key, iv, isEncrypt, options) {
            var autoPadding = !(options && options.autoPadding === false);
            var chunks = [];
            var finished = false;
            var api = {
                update: function (data, inputEncoding, outputEncoding) {
                    chunks.push(toB64(data));
                    // Node 的 update 会返回已处理数据；一次性实现下只能返回空 Buffer。
                    // 依赖返回值的代码会在 final 拿到全部数据（诚实的不完整实现，
                    // 而不是返回错数据）。
                    return toBuffer("");
                },
                final: function (outputEncoding) {
                    if (finished) throw ioError("ERR_CRYPTO_CIPHER_FINALIZED", "Cipher already finalized");
                    finished = true;
                    if (!env) throw ioError("ERR_NOT_IMPLEMENTED", "crypto unavailable without native bridge");
                    var out = env.cipher(algorithm, toB64(key), iv === undefined || iv === null ? "" : toB64(iv), concatB64(chunks), isEncrypt, autoPadding);
                    if (!out) throw ioError("ERR_CRYPTO_INVALID_STATE", "Cipher failed: " + algorithm);
                    return fromB64(out);
                },
                setAutoPadding: function (value) { autoPadding = value !== false; return api; },
                getAuthTag: function () { throw ioError("ERR_CRYPTO_INVALID_STATE", "getAuthTag not supported (no GCM)"); }
            };
            return api;
        }

        var cryptoModule = {
            createHash: function (algorithm) { return makeHash(String(algorithm || "sha256").toLowerCase().replace(/-/g, "")); },
            createHmac: function (algorithm, key) { return makeHmac(String(algorithm || "sha256").toLowerCase().replace(/-/g, ""), key === undefined ? "" : key); },
            createCipheriv: function (algorithm, key, iv, options) { return makeCipher(String(algorithm), key, iv, true, options); },
            createDecipheriv: function (algorithm, key, iv, options) { return makeCipher(String(algorithm), key, iv, false, options); },
            randomBytes: function (count, callback) {
                var buf = toBuffer(env ? env.randomBytes(count | 0) : "");
                if (typeof callback === "function") { setTimeout(function () { callback(null, buf); }, 0); return; }
                if (!env) throw ioError("ERR_NOT_IMPLEMENTED", "crypto.randomBytes unavailable without native bridge");
                return buf;
            },
            randomFillSync: function (buffer, offset, size) {
                var bytes = toBuffer(env ? env.randomBytes(buffer && buffer.length ? buffer.length : 0) : "");
                if (!buffer || typeof buffer.length !== "number") return buffer;
                var start = offset || 0;
                var end = size === undefined ? buffer.length : Math.min(buffer.length, start + size);
                var bin = bytes && bytes._bin ? bytes._bin : "";
                for (var i = start; i < end; i++) {
                    if (typeof buffer[i] === "number") buffer[i] = bin.charCodeAt(i - start) & 0xff;
                }
                return buffer;
            },
            randomUUID: function () {
                if (!env) throw ioError("ERR_NOT_IMPLEMENTED", "crypto.randomUUID unavailable without native bridge");
                return env.randomUuid();
            },
            randomInt: function (min, max, callback) {
                var lo = min, hi = max;
                if (typeof max !== "number") { hi = lo; lo = 0; }
                if (typeof max === "function") { callback = max; hi = lo; lo = 0; }
                var bytes = env ? toBuffer(env.randomBytes(4)) : null;
                if (!bytes) throw ioError("ERR_NOT_IMPLEMENTED", "crypto.randomInt unavailable without native bridge");
                var value = 0;
                for (var i = 0; i < 4; i++) value = value * 256 + (bytes._bin.charCodeAt(i) & 0xff);
                var result = lo + (value % (hi - lo));
                if (typeof callback === "function") { setTimeout(function () { callback(null, result); }, 0); return; }
                return result;
            },
            pbkdf2Sync: function (password, salt, iterations, keylen, digest) {
                if (!env) throw ioError("ERR_NOT_IMPLEMENTED", "crypto.pbkdf2Sync unavailable without native bridge");
                var out = env.pbkdf2(toB64(password), toB64(salt), iterations | 0, keylen | 0, String(digest || "sha1"));
                if (!out) throw ioError("ERR_CRYPTO_INVALID_DIGEST", "pbkdf2Sync failed");
                return fromB64(out);
            },
            pbkdf2: function (password, salt, iterations, keylen, digest, callback) {
                if (typeof digest === "function") { callback = digest; digest = "sha1"; }
                setTimeout(function () {
                    try { callback(null, cryptoModule.pbkdf2Sync(password, salt, iterations, keylen, digest)); }
                    catch (e) { callback(e); }
                }, 0);
            },
            timingSafeEqual: function (a, b) {
                var x = toB64(a), y = toB64(b);
                if (!env) return x === y;
                return env.timingSafeEqual(x, y) === true;
            },
            getHashes: function () { return ["md5", "sha1", "sha256", "sha384", "sha512"]; },
            getCiphers: function () { return ["aes-128-cbc", "aes-192-cbc", "aes-256-cbc", "aes-128-ecb", "aes-256-ecb", "aes-256-ctr"]; },
            constants: { defaultCoreCipherList: "AES", defaultCipherList: "AES" }
        };

        // ---- zlib（Node 同步 API + 流式桩）----
        function makeZlibFn(mode) {
            return function (buffer, options, callback) {
                var opts = options;
                if (typeof options === "function") { callback = options; opts = undefined; }
                var out = null;
                var err = null;
                try {
                    if (!env) throw ioError("ERR_NOT_IMPLEMENTED", "zlib unavailable without native bridge");
                    var level = opts && typeof opts.level === "number" ? opts.level : -1;
                    var b64 = env.zlib(mode, toB64(buffer), level);
                    if (!b64) throw ioError("Z_DATA_ERROR", "incorrect header check");
                    out = fromB64(b64);
                } catch (e) { err = e; }
                if (typeof callback === "function") { setTimeout(function () { callback(err, err ? undefined : out); }, 0); return undefined; }
                if (err) throw err;
                return out;
            };
        }
        function makeZlibStream(mode) {
            // 流式接口保持桩：数据表/资源解压多是同步一次性调用；真流式需要分块
            // 状态机，收益低。桩会让调用方拿到一个「永不产出」的流——比抛错更隐蔽，
            // 因此 put 到流上的数据会被缓存，flush 时一次性解压并通过 data 事件吐出。
            return function () {
                var chunks = [];
                var handlers = { data: [], end: [], error: [] };
                var stream = {
                    write: function (data) { chunks.push(toB64(data)); return true; },
                    end: function (data) {
                        if (data !== undefined) chunks.push(toB64(data));
                        try {
                            var out = makeZlibFn(mode)(fromB64(concatB64(chunks)));
                            handlers.data.forEach(function (fn) { fn(out); });
                            handlers.end.forEach(function (fn) { fn(); });
                        } catch (e) {
                            handlers.error.forEach(function (fn) { fn(e); });
                            if (!handlers.error.length) throw e;
                        }
                        return stream;
                    },
                    on: function (name, fn) { (handlers[name] = handlers[name] || []).push(fn); return stream; },
                    once: function (name, fn) { return stream.on(name, fn); },
                    pipe: function (dest) { return dest; },
                    close: function () {}, destroy: function () {}
                };
                return stream;
            };
        }
        var zlibModule = {
            inflateSync: makeZlibFn("inflate"),
            inflateRawSync: makeZlibFn("inflateRaw"),
            gunzipSync: makeZlibFn("gunzip"),
            unzipSync: makeZlibFn("unzip"),
            deflateSync: makeZlibFn("deflate"),
            deflateRawSync: makeZlibFn("deflateRaw"),
            gzipSync: makeZlibFn("gzip"),
            inflate: makeZlibFn("inflate"),
            inflateRaw: makeZlibFn("inflateRaw"),
            gunzip: makeZlibFn("gunzip"),
            unzip: makeZlibFn("unzip"),
            deflate: makeZlibFn("deflate"),
            deflateRaw: makeZlibFn("deflateRaw"),
            gzip: makeZlibFn("gzip"),
            createInflate: makeZlibStream("inflate"),
            createGunzip: makeZlibStream("gunzip"),
            createUnzip: makeZlibStream("unzip"),
            createDeflate: makeZlibStream("deflate"),
            createGzip: makeZlibStream("gzip"),
            constants: { Z_NO_COMPRESSION: 0, Z_BEST_SPEED: 1, Z_BEST_COMPRESSION: 9, Z_DEFAULT_COMPRESSION: -1 }
        };

        // =====================================================================
        // 补齐的 Node 内建模块（assert / stream / string_decoder / timers /
        // vm / punycode / constants / process 等）
        // 这些模块的共同点是「纯计算」或「调度」，不需要原生能力，
        // 但缺失时插件初始化即崩（ReferenceError / undefined is not a function）。
        // =====================================================================

        // ---- assert ----
        function assertionError(message, actual, expected, operator) {
            var err = new Error(message || "Assertion failed");
            err.name = "AssertionError";
            err.code = "ERR_ASSERTION";
            err.actual = actual;
            err.expected = expected;
            err.operator = operator;
            err.generatedMessage = !message;
            return err;
        }
        function inspectValue(value) {
            try {
                if (typeof value === "string") return "'" + value + "'";
                if (value === undefined) return "undefined";
                return JSON.stringify(value);
            } catch (e) { return String(value); }
        }
        var assertModule = function (value, message) {
            if (!value) throw assertionError(message, value, true, "==");
        };
        assertModule.ok = assertModule;
        assertModule.fail = function (message) { throw assertionError(message || "Failed", undefined, undefined, "fail"); };
        assertModule.equal = function (actual, expected, message) {
            if (actual != expected) throw assertionError(message || (inspectValue(actual) + " == " + inspectValue(expected)), actual, expected, "==");
        };
        assertModule.notEqual = function (actual, expected, message) {
            if (actual == expected) throw assertionError(message, actual, expected, "!=");
        };
        assertModule.strictEqual = function (actual, expected, message) {
            if (actual !== expected) throw assertionError(message || (inspectValue(actual) + " === " + inspectValue(expected)), actual, expected, "===");
        };
        assertModule.notStrictEqual = function (actual, expected, message) {
            if (actual === expected) throw assertionError(message, actual, expected, "!==");
        };
        assertModule.deepStrictEqual = function (actual, expected, message) {
            function deepEq(a, b) {
                if (a === b) return true;
                if (typeof a !== typeof b || a === null || b === null) return false;
                if (typeof a !== "object") return a === b;
                if (Array.isArray(a) !== Array.isArray(b)) return false;
                var ka = Object.keys(a), kb = Object.keys(b);
                if (ka.length !== kb.length) return false;
                for (var i = 0; i < ka.length; i++) {
                    if (kb.indexOf(ka[i]) < 0) return false;
                    if (!deepEq(a[ka[i]], b[ka[i]])) return false;
                }
                return true;
            }
            if (!deepEq(actual, expected)) throw assertionError(message, actual, expected, "deepStrictEqual");
        };
        assertModule.deepEqual = assertModule.deepStrictEqual;
        assertModule.throws = function (fn, expected, message) {
            var threw = false;
            try { fn(); } catch (e) { threw = true; }
            if (!threw) throw assertionError(message || "Missing expected exception", undefined, undefined, "throws");
        };
        assertModule.doesNotThrow = function (fn, message) {
            try { fn(); } catch (e) { throw assertionError(message || ("Got unwanted exception: " + e.message), undefined, undefined, "doesNotThrow"); }
        };
        assertModule.AssertionError = function (options) {
            var opts = options || {};
            return assertionError(opts.message, opts.actual, opts.expected, opts.operator);
        };
        assertModule.strict = assertModule;

        // ---- stream（EventEmitter + Node 常见子类骨架）----
        var ReadableBase = null;
        var WritableBase = null;
        var TransformBase = null;
        var PassThroughBase = null;
        var streamModule = {};
        // 用 events 的 EventEmitter 当基类（基础层已提供，且本段补齐了别名）
        try { ReadableBase = baseRequire ? baseRequire("events") : null; } catch (e) {}
        if (!ReadableBase) {
            ReadableBase = function () { this._e = {}; };
            ReadableBase.prototype.on = function (n, f) { (this._e[n] = this._e[n] || []).push(f); return this; };
            ReadableBase.prototype.emit = function (n) {
                var a = Array.prototype.slice.call(arguments, 1);
                (this._e[n] || []).slice().forEach(function (f) { f.apply(null, a); });
                return true;
            };
            ReadableBase.prototype.once = function (n, f) { var s = this; function w() { s.removeListener(n, w); f.apply(s, arguments); } w.fn = f; return this.on(n, w); };
            ReadableBase.prototype.removeListener = function (n, f) {
                if (this._e[n]) this._e[n] = this._e[n].filter(function (g) { return g !== f && g.fn !== f; });
                return this;
            };
        }
        function inheritsStream(Child, Parent) {
            Child.prototype = Object.create(Parent.prototype);
            Child.prototype.constructor = Child;
            Child.super_ = Parent;
        }
        ReadableBase = (function (Base) {
            function Readable(options) {
                Base.call(this);
                this.readable = true;
                this._buffer = [];
                this._ended = false;
                if (options && typeof options.read === "function") this._read = options.read;
            }
            inheritsStream(Readable, Base);
            Readable.prototype.push = function (chunk) {
                if (chunk === null) {
                    this._ended = true;
                    this.emit("end");
                    this.emit("close");
                    return false;
                }
                this._buffer.push(chunk);
                this.emit("data", chunk);
                return true;
            };
            Readable.prototype.pipe = function (dest) {
                this.on("data", function (chunk) { if (dest && typeof dest.write === "function") dest.write(chunk); });
                var self = this;
                this.on("end", function () { if (dest && typeof dest.end === "function") dest.end(); });
                return dest;
            };
            Readable.prototype.read = function () { return this._buffer.shift() || null; };
            Readable.prototype.pause = function () { this._paused = true; return this; };
            Readable.prototype.resume = function () { this._paused = false; return this; };
            Readable.prototype.destroy = function () { this.readable = false; return this; };
            Readable.prototype.setEncoding = function (enc) { this._encoding = enc; return this; };
            Readable.prototype.unshift = function (chunk) { this._buffer.unshift(chunk); };
            return Readable;
        })(ReadableBase);
        WritableBase = (function (Base) {
            function Writable(options) {
                Base.call(this);
                this.writable = true;
                if (options && typeof options.write === "function") this._write = options.write;
            }
            inheritsStream(Writable, Base);
            Writable.prototype.write = function (chunk, encoding, callback) {
                var cb = typeof encoding === "function" ? encoding : callback;
                var ok = true;
                try {
                    if (typeof this._write === "function") this._write(chunk, encoding, function () {});
                } catch (e) { this.emit("error", e); }
                if (typeof cb === "function") cb();
                return ok;
            };
            Writable.prototype.end = function (chunk, encoding, callback) {
                var cb = typeof encoding === "function" ? encoding : callback;
                if (chunk !== undefined && chunk !== null) this.write(chunk, encoding);
                this.emit("finish");
                this.emit("close");
                if (typeof cb === "function") cb();
                return this;
            };
            Writable.prototype.destroy = function () { this.writable = false; return this; };
            return Writable;
        })(ReadableBase);
        TransformBase = (function (Base) {
            function Transform(options) {
                Base.call(this, options);
                this.readable = true;
                this.writable = true;
                if (options && typeof options.transform === "function") this._transform = options.transform;
            }
            inheritsStream(Transform, Base);
            Transform.prototype.write = function (chunk, encoding, callback) {
                var self = this;
                var cb = typeof encoding === "function" ? encoding : callback;
                try {
                    if (typeof this._transform === "function") {
                        this._transform(chunk, encoding || "utf8", function (err, data) {
                            if (err) { self.emit("error", err); return; }
                            if (data !== undefined && data !== null) self.push(data);
                        });
                    } else {
                        this.push(chunk);
                    }
                } catch (e) { this.emit("error", e); }
                if (typeof cb === "function") cb();
                return true;
            };
            Transform.prototype.end = function (chunk) {
                if (chunk !== undefined && chunk !== null) this.write(chunk);
                this.push(null);
                return this;
            };
            return Transform;
        })(ReadableBase);
        PassThroughBase = (function (Base) {
            function PassThrough(options) { Base.call(this, options); }
            inheritsStream(PassThrough, Base);
            return PassThrough;
        })(TransformBase);

        streamModule = {
            Readable: ReadableBase,
            Writable: WritableBase,
            Transform: TransformBase,
            PassThrough: PassThroughBase,
            Duplex: TransformBase,
            Stream: ReadableBase,
            isReadable: function (s) { return s instanceof ReadableBase; },
            isWritable: function (s) { return s instanceof WritableBase; },
            pipeline: function () {
                var args = Array.prototype.slice.call(arguments);
                var cb = typeof args[args.length - 1] === "function" ? args.pop() : null;
                for (var i = 0; i < args.length - 1; i++) {
                    if (args[i] && typeof args[i].pipe === "function") args[i].pipe(args[i + 1]);
                }
                if (cb) setTimeout(function () { cb(null); }, 0);
                return args[args.length - 1];
            },
            finished: function (s, cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); }
        };

        // ---- string_decoder ----
        function StringDecoder(encoding) { this.encoding = String(encoding || "utf8").toLowerCase(); }
        StringDecoder.prototype.write = function (buffer) {
            if (buffer === undefined || buffer === null) return "";
            if (typeof buffer === "string") return buffer;
            var bin = isBufferLike(buffer) ? buffer._bin : String(buffer);
            try {
                if (this.encoding === "utf8" || this.encoding === "utf-8") return decodeURIComponent(escape(bin));
                if (this.encoding === "hex") {
                    var h = "";
                    for (var i = 0; i < bin.length; i++) { var c = bin.charCodeAt(i).toString(16); h += c.length === 1 ? "0" + c : c; }
                    return h;
                }
                if (this.encoding === "base64") return btoa(bin);
            } catch (e) { return bin; }
            return bin;
        };
        StringDecoder.prototype.end = function (buffer) { return buffer === undefined ? "" : this.write(buffer); };

        // ---- timers ----
        var timersModule = {
            setTimeout: function (fn, delay) { return setTimeout(fn, delay); },
            clearTimeout: function (id) { return clearTimeout(id); },
            setInterval: function (fn, delay) { return setInterval(fn, delay); },
            clearInterval: function (id) { return clearInterval(id); },
            setImmediate: function (fn) { return setTimeout(fn, 0); },
            clearImmediate: function (id) { return clearTimeout(id); },
            unref: function () { return timersModule; },
            ref: function () { return timersModule; },
            enroll: function () {}, unenroll: function () {}
        };

        // ---- vm（沙箱求值）----
        // 关键语义：脚本里的**全局赋值要写回 sandbox 对象**（Node 的 vm 就是这样，
        // 插件常借此把结果带出来）。用 Function 直接跑会把赋值留在函数作用域里丢不掉，
        // 因此显式用 with(sandbox) 让全局读写落到沙箱上。
        var vmModule = {
            runInThisContext: function (code) { return (new Function(String(code)))(); },
            runInNewContext: function (code, sandbox) {
                var context = sandbox && typeof sandbox === "object" ? sandbox : {};
                // 用 new Function 承载：Function 构造出的函数体默认非严格模式，
                // 因此可以用 with(sandbox) 让脚本里的全局赋值落到沙箱对象上
                // （严格模式下 with 是语法错误，不能写在 polyfill 本体里）。
                var runner = new Function(
                    "__vm_scope__",
                    "__vm_code__",
                    "with (__vm_scope__) { return eval(__vm_code__); }"
                );
                return runner(context, String(code));
            },
            runInContext: function (code, sandbox) { return vmModule.runInNewContext(code, sandbox); },
            createContext: function (sandbox) { return sandbox || {}; },
            isContext: function () { return false; },
            Script: function (code) { this.code = String(code); },
            compileFunction: function (code, params) {
                var fn = new Function((params || []).join(","), String(code));
                var wrapper = function () { return fn.apply(null, arguments); };
                wrapper.runInNewContext = function (sandbox) { return fn.apply(sandbox || null, (sandbox && sandbox.__args) || []); };
                return wrapper;
            }
        };
        vmModule.Script.prototype.runInThisContext = function () { return (new Function(this.code))(); };

        // ---- punycode（RFC 3492 完整实现）----
        var punycodeModule = (function () {
            var maxInt = 2147483647, base = 36, tMin = 1, tMax = 26, skew = 38, damp = 700, initialBias = 72, initialN = 128, delimiter = "-";
            function adapt(delta, numPoints, firstTime) {
                var k = 0;
                delta = firstTime ? Math.floor(delta / damp) : delta >> 1;
                delta += Math.floor(delta / numPoints);
                for (; delta > ((base - tMin) * tMax) >> 1; k += base) delta = Math.floor(delta / (base - tMin));
                return Math.floor(k + (base - tMin + 1) * delta / (delta + skew));
            }
            function digitToBasic(digit) { return String.fromCharCode(digit + 22 + 75 * (digit < 26 ? 1 : 0)); }
            function basicToDigit(codePoint) {
                if (codePoint - 48 < 10) return codePoint - 22;
                if (codePoint - 65 < 26) return codePoint - 65;
                if (codePoint - 97 < 26) return codePoint - 97;
                return base;
            }
            function encode(input) {
                var output = [];
                var inputArray = [];
                var n = initialN, delta = 0, bias = initialBias;
                var chars = String(input).split("");
                for (var i = 0; i < chars.length; i++) {
                    var cp = chars[i].codePointAt(0);
                    if (cp < 0x80) output.push(chars[i]);
                    inputArray.push(cp);
                }
                var basicLength = output.length;
                var handledCPCount = basicLength;
                if (basicLength) output.push(delimiter);
                while (handledCPCount < inputArray.length) {
                    var m = maxInt;
                    for (var j = 0; j < inputArray.length; j++) if (inputArray[j] >= n && inputArray[j] < m) m = inputArray[j];
                    var handledCPCountPlusOne = handledCPCount + 1;
                    if (m - n > Math.floor((maxInt - delta) / handledCPCountPlusOne)) throw new RangeError("Overflow");
                    delta += (m - n) * handledCPCountPlusOne;
                    n = m;
                    for (var k = 0; k < inputArray.length; k++) {
                        if (inputArray[k] < n && ++delta > maxInt) throw new RangeError("Overflow");
                        if (inputArray[k] === n) {
                            var q = delta;
                            for (var b = base; ; b += base) {
                                var t = b <= bias ? tMin : (b >= bias + tMax ? tMax : b - bias);
                                if (q < t) break;
                                output.push(digitToBasic(t + (q - t) % (base - t)));
                                q = Math.floor((q - t) / (base - t));
                            }
                            output.push(digitToBasic(q));
                            bias = adapt(delta, handledCPCountPlusOne, handledCPCount === basicLength);
                            delta = 0;
                            handledCPCount++;
                        }
                    }
                    delta++; n++;
                }
                return output.join("");
            }
            function decode(input) {
                var output = [];
                var inputArray = String(input).split("").map(function (ch) { return ch.codePointAt(0); });
                var n = initialN, i = 0, bias = initialBias;
                var basic = input.lastIndexOf(delimiter);
                if (basic < 0) basic = 0;
                for (var j = 0; j < basic; j++) {
                    if (inputArray[j] >= 0x80) throw new RangeError("Illegal input");
                    output.push(inputArray[j]);
                }
                for (var index = basic > 0 ? basic + 1 : 0; index < inputArray.length; ) {
                    var oldi = i;
                    for (var w = 1, k = base; ; k += base) {
                        if (index >= inputArray.length) throw new RangeError("Invalid input");
                        var digit = basicToDigit(inputArray[index++]);
                        if (digit >= base) throw new RangeError("Invalid input");
                        if (digit > Math.floor((maxInt - i) / w)) throw new RangeError("Overflow");
                        i += digit * w;
                        var t = k <= bias ? tMin : (k >= bias + tMax ? tMax : k - bias);
                        if (digit < t) break;
                        if (w > Math.floor(maxInt / (base - t))) throw new RangeError("Overflow");
                        w *= base - t;
                    }
                    var out = output.length + 1;
                    bias = adapt(i - oldi, out, oldi === 0);
                    if (Math.floor(i / out) > maxInt - n) throw new RangeError("Overflow");
                    n += Math.floor(i / out);
                    i %= out;
                    output.splice(i++, 0, n);
                }
                return String.fromCodePoint.apply(String, output);
            }
            return {
                version: "2.1.1",
                ucs2: { decode: function (s) { return s; }, encode: function (s) { return s; } },
                decode: decode,
                encode: encode,
                toASCII: function (input) {
                    var domain = String(input);
                    return domain.split("@").map(function (part) {
                        return part.split(".").map(function (label) {
                            var encoded = false;
                            for (var i = 0; i < label.length; i++) if (label.charCodeAt(i) >= 0x80) { encoded = true; break; }
                            return encoded ? "xn--" + encode(label) : label;
                        }).join(".");
                    }).join("@");
                },
                toUnicode: function (input) {
                    return String(input).split("@").map(function (part) {
                        return part.split(".").map(function (label) {
                            return label.indexOf("xn--") === 0 ? decode(label.slice(4)) : label;
                        }).join(".");
                    }).join("@");
                }
            };
        })();

        // ---- constants（fs/crypto 常用常量）----
        var constantsModule = {
            F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1,
            O_RDONLY: 0, O_WRONLY: 1, O_RDWR: 2, O_CREAT: 64, O_EXCL: 128, O_TRUNC: 512, O_APPEND: 1024,
            COPYFILE_EXCL: 1, UV_FS_O_FILEMAP: 0,
            S_IFMT: 61440, S_IFREG: 32768, S_IFDIR: 16384, S_IFLNK: 40960
        };

        // 模块表：require 裸名时优先命中本表（覆盖基础层桩）
        var nodeModules = {
            crypto: cryptoModule,
            zlib: zlibModule,
            assert: assertModule,
            stream: streamModule,
            string_decoder: StringDecoder ? { StringDecoder: StringDecoder } : {},
            timers: timersModule,
            vm: vmModule,
            punycode: punycodeModule,
            constants: constantsModule,
            "assert/strict": assertModule,
            "timers/promises": {
                setTimeout: function (delay) { return new Promise(function (res) { setTimeout(function () { res(); }, delay || 0); }); }
            }
        };
        try {
            nodeModules.process = window.process;
            if (typeof globalThis !== "undefined" && globalThis.process) nodeModules.process = globalThis.process;
        } catch (eProcMod) {}

        // ---- fs 长尾补齐 ----
        // 这些 API 的共同实现基础是「读/写/列目录 + stat」，全部由 fm/fs 原语组合，
        // 无需新增原生方法（唯一新增原生能力是 setTimes）。
        (function () {
            var fm = realFs;
            // 便捷判定：内建桥提供 isFile/isDir，包装成 fs 层可复用的谓词
            fm.__isFile = function (p) { try { return bridge.isFile(p) === true; } catch (e) { return false; } };
            fm.__isDir = function (p) { try { return bridge.isDir(p) === true; } catch (e) { return false; } };
            fm.accessSync = function (p, mode) {
                if (!fm.existsSync(p)) throw nodeErr("ENOENT", "ENOENT: no such file or directory, access '" + p + "'");
                // 权限位在 Android 沙箱下无实际意义，存在即视为可访问
            };
            fm.access = function (p, mode, cb) {
                if (typeof mode === "function") { cb = mode; }
                setTimeout(function () {
                    try { fm.accessSync(p); cb && cb(null); } catch (e) { cb && cb(e); }
                }, 0);
            };
            fm.realpathSync = function (p) {
                if (!fm.existsSync(p)) throw nodeErr("ENOENT", "ENOENT: no such file or directory, realpath '" + p + "'");
                return resolvePath(p, baseDir);
            };
            fm.realpath = function (p, o, cb) {
                if (typeof o === "function") { cb = o; }
                setTimeout(function () {
                    try { cb && cb(null, fm.realpathSync(p)); } catch (e) { cb && cb(e); }
                }, 0);
            };
            fm.readlinkSync = function (p) {
                if (!fm.existsSync(p)) throw nodeErr("ENOENT", "ENOENT: no such file or directory, readlink '" + p + "'");
                return p;   // 平台上没有符号链接语义，原样返回
            };
            fm.rmdirSync = function (p) {
                if (!fm.__isDir(p)) throw nodeErr("ENOENT", "ENOENT: no such directory, rmdir '" + p + "'");
                bridge.remove(p);
            };
            fm.rmdir = function (p, o, cb) {
                if (typeof o === "function") { cb = o; }
                setTimeout(function () {
                    try { fm.rmdirSync(p); cb && cb(null); } catch (e) { cb && cb(e); }
                }, 0);
            };
            fm.rmSync = function (p, options) {
                if (!fm.existsSync(p)) {
                    if (options && options.force) return;
                    throw nodeErr("ENOENT", "ENOENT: no such file or directory, rm '" + p + "'");
                }
                if (fm.__isDir(p)) {
                    var entries = fm.readdirSync(p);
                    var recursive = options && options.recursive;
                    if (entries.length && !recursive) throw nodeErr("ERR_FS_EISDIR", "Directory not empty: " + p);
                    entries.forEach(function (name) { fm.rmSync(joinPath(p, name), options); });
                }
                bridge.remove(p);
            };
            fm.rm = function (p, o, cb) {
                if (typeof o === "function") { cb = o; }
                setTimeout(function () {
                    try { fm.rmSync(p, o); cb && cb(null); } catch (e) { cb && cb(e); }
                }, 0);
            };
            fm.truncateSync = function (p, len) {
                // 读原内容（base64），按目标长度截断后写回。
                // 不存在时按 Node 语义创建空文件（此处 len 为 0 即空文件）。
                var existing = bridge.readBase64(p);
                var buffer = existing === null ? toBuffer("") : toBuffer(existing);
                var bin = buffer && buffer._bin ? buffer._bin : "";
                var target = len === undefined ? 0 : Math.max(0, len | 0);
                bridge.writeBase64(p, btoa(bin.slice(0, target)));
            };
            fm.truncate = function (p, len, cb) {
                if (typeof len === "function") { cb = len; len = 0; }
                setTimeout(function () {
                    try { fm.truncateSync(p, len); cb && cb(null); } catch (e) { cb && cb(e); }
                }, 0);
            };
            fm.utimesSync = function (p, atime, mtime) {
                var millis = (mtime instanceof Date ? mtime.getTime() : Number(mtime) * 1000) || Date.now();
                if (bridge.setTimes && bridge.setTimes(p, millis) !== true) {
                    if (!fm.existsSync(p)) throw nodeErr("ENOENT", "ENOENT: no such file or directory, utimes '" + p + "'");
                }
            };
            fm.utimes = function (p, a, m, cb) {
                setTimeout(function () {
                    try { fm.utimesSync(p, a, m); cb && cb(null); } catch (e) { cb && cb(e); }
                }, 0);
            };
            fm.lutimesSync = fm.utimesSync;
            fm.lutimes = fm.utimes;
            fm.futimesSync = fm.utimesSync;
            fm.chmodSync = function () {};   // 沙箱内无权限位语义，静默接受
            fm.chmod = function (p, m, cb) { if (typeof m === "function") cb = m; if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); };
            fm.lchmodSync = fm.chmodSync; fm.lchmod = fm.chmod;
            fm.chownSync = fm.chmodSync; fm.chown = fm.chmod;
            fm.lchownSync = fm.chmodSync; fm.lchown = fm.chmod;
            fm.fchmodSync = fm.chmodSync; fm.fchownSync = fm.chmodSync;
            fm.symlinkSync = function (target, path) {
                // 无符号链接：退化为复制内容，保证「读链接即读到目标内容」的可用语义
                var data = bridge.readBase64(target);
                if (data === null) throw nodeErr("ENOENT", "ENOENT: no such file, symlink target '" + target + "'");
                bridge.writeBase64(path, data);
            };
            fm.symlink = function (t, p, type, cb) {
                if (typeof type === "function") { cb = type; }
                setTimeout(function () {
                    try { fm.symlinkSync(t, p); cb && cb(null); } catch (e) { cb && cb(e); }
                }, 0);
            };
            fm.linkSync = fm.symlinkSync;
            fm.link = fm.symlink;
            fm.mkdtempSync = function (prefix) {
                var dir = String(prefix || "/tmp/") + Math.random().toString(36).slice(2, 10);
                if (!fm.makeDirs) bridge.makeDirs(dir); else fm.mkdirSync(dir);
                return dir;
            };
            fm.mkdtemp = function (prefix, cb) { setTimeout(function () { try { cb(null, fm.mkdtempSync(prefix)); } catch (e) { cb(e); } }, 0); };
            fm.opendirSync = function (p) {
                var names = fm.readdirSync(p);
                var index = 0;
                return {
                    readSync: function () {
                        if (index >= names.length) return null;
                        var name = names[index++];
                        var full = joinPath(p, name);
                        return { name: name, isFile: function () { return fm.__isFile(full); }, isDirectory: function () { return fm.__isDir(full); } };
                    },
                    closeSync: function () {},
                    close: function (cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); }
                };
            };
            fm.opendir = function (p, o, cb) {
                if (typeof o === "function") { cb = o; }
                setTimeout(function () { try { cb(null, fm.opendirSync(p)); } catch (e) { cb(e); } }, 0);
            };
            fm.fsyncSync = function () {}; fm.fdatasyncSync = function () {}; fm.fsync = fm.fdatasync = function (fd, cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); };
        })();

        // ---- util 增强 ----
        try {
            var utilMod = baseRequire ? baseRequire("util") : null;
            if (utilMod) {
                utilMod.promisify = function (fn) {
                    if (fn && fn.__promisify__ && typeof fn.__promisify__ === "function") return fn.__promisify__;
                    var wrapped = function () {
                        var args = Array.prototype.slice.call(arguments);
                        var self = this;
                        return new Promise(function (resolve, reject) {
                            args.push(function (err, value) { if (err) reject(err); else resolve(value); });
                            try { fn.apply(self, args); } catch (e) { reject(e); }
                        });
                    };
                    wrapped.__original = fn;
                    return wrapped;
                };
                utilMod.callbackify = function (fn) {
                    return function () {
                        var args = Array.prototype.slice.call(arguments);
                        var cb = typeof args[args.length - 1] === "function" ? args.pop() : null;
                        var p = fn.apply(this, args);
                        if (!cb) return;
                        Promise.resolve(p).then(function (v) { cb(null, v); }, function (e) { cb(e); });
                    };
                };
                utilMod.isDeepStrictEqual = function (a, b) {
                    try { assertModule.deepStrictEqual(a, b); return true; } catch (e) { return false; }
                };
                utilMod.formatWithOptions = function (opts) {
                    var args = Array.prototype.slice.call(arguments, 1);
                    return utilMod.format.apply(null, args);
                };
                utilMod.debuglog = function () { var noop = function () {}; noop.enabled = false; return noop; };
                utilMod.inspect = utilMod.inspect || function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } };
                utilMod.types = utilMod.types || {
                    isDate: function (v) { return v instanceof Date; },
                    isRegExp: function (v) { return v instanceof RegExp; },
                    isNativeError: function (v) { return v instanceof Error; }
                };
                utilMod.deprecate = utilMod.deprecate || function (fn) { return fn; };
                utilMod.promisify.custom = typeof Symbol !== "undefined" ? Symbol("util.promisify.custom") : "__promisify__";
            }
        } catch (eUtil) {}

        // ---- os 增强 ----
        try {
            var osMod = baseRequire ? baseRequire("os") : null;
            if (osMod) {
                // 注意用直接赋值而非 `||`：基础层的占位值是 "/" 与 "/tmp"（Android 上
                // 既不可读也不可写），必须覆盖掉，否则插件往 homedir/tmpdir 写文件会失败。
                osMod.homedir = function () { return dataDir; };
                osMod.tmpdir = function () { return dataDir; };
                osMod.endianness = function () { return "LE"; };
                osMod.uptime = function () { return Math.floor((Date.now() - osStartTime) / 1000); };
                osMod.loadavg = function () { return [0, 0, 0]; };
                osMod.totalmem = function () { return env ? env.totalMemory() : 0; };
                osMod.freemem = function () { return env ? env.freeMemory() : 0; };
                osMod.availableParallelism = function () { return 1; };
                osMod.cpus = function () { return [{ model: "ARM", speed: 0 }]; };
                osMod.networkInterfaces = function () { return {}; };
                osMod.userInfo = function () { return { username: "player", homedir: dataDir, shell: null, uid: -1, gid: -1 }; };
                osMod.machine = function () { return "arm64"; };
                osMod.version = function () { return "Linux version 4.0"; };
                osMod.release = function () { return "4.0.0"; };
                osMod.devNull = "/dev/null";
                osMod.constants = osMod.constants || { signals: {}, errno: {}, priority: {} };
            }
        } catch (eOs) {}

        // ---- url 增强 ----
        try {
            var urlMod = baseRequire ? baseRequire("url") : null;
            if (urlMod) {
                // 浏览器原生构造器直接暴露，比自研解析可靠
                if (typeof URL !== "undefined") urlMod.URL = URL;
                if (typeof URLSearchParams !== "undefined") urlMod.URLSearchParams = URLSearchParams;
                urlMod.parse = urlMod.parse || function (u) { return { href: String(u) }; };
                urlMod.fileURLToPath = function (u) {
                    var s = String(u && u.href ? u.href : u).replace(/^file:\/\//, "");
                    try { s = decodeURIComponent(s); } catch (e) {}
                    return s;
                };
                urlMod.pathToFileURL = function (p) {
                    var path = String(p).replace(/\\/g, "/");
                    if (path.charAt(0) !== "/") path = "/" + path;
                    var encoded = path.split("/").map(function (seg) { return encodeURIComponent(seg); }).join("/");
                    var out = { href: "file://" + encoded, protocol: "file:" };
                    out.toString = function () { return out.href; };
                    return out;
                };
                urlMod.domainToASCII = function (d) { return punycodeModule.toASCII(d); };
                urlMod.domainToUnicode = function (d) { return punycodeModule.toUnicode(d); };
                urlMod.urlToHttpOptions = function (u) {
                    var parsed = typeof URL !== "undefined" ? new URL(String(u && u.href ? u.href : u)) : null;
                    if (!parsed) return {};
                    return { protocol: parsed.protocol, hostname: parsed.hostname, port: parsed.port, path: parsed.pathname + parsed.search };
                };
                urlMod.resolve = urlMod.resolve || function (from, to) {
                    try { return new URL(String(to), String(from)).href; } catch (e) { return String(to); }
                };
            }
        } catch (eUrl) {}

        // ---- EventEmitter 成员补齐 ----
        // 基础层只实现了核心五个（on/once/emit/removeListener/removeAllListeners），
        // 少数插件用到 Node 的别名与查询方法。
        try {
            var EE = baseRequire ? baseRequire("events") : null;
            if (EE && EE.prototype) {
                var proto = EE.prototype;
                proto.addListener = proto.addListener || proto.on;
                proto.off = proto.off || proto.removeListener;
                proto.prependListener = proto.prependListener || function (name, fn) {
                    (this._e[name] = this._e[name] || []).unshift(fn);
                    return this;
                };
                proto.prependOnceListener = proto.prependOnceListener || function (name, fn) {
                    var self = this;
                    function wrapper() { self.removeListener(name, wrapper); fn.apply(self, arguments); }
                    wrapper.fn = fn;
                    return proto.prependListener.call(this, name, wrapper);
                };
                proto.listeners = proto.listeners || function (name) { return (this._e[name] || []).slice(); };
                proto.rawListeners = proto.rawListeners || proto.listeners;
                proto.listenerCount = proto.listenerCount || function (name) { return (this._e[name] || []).length; };
                proto.eventNames = proto.eventNames || function () {
                    var self = this;
                    return Object.keys(this._e || {}).filter(function (k) { return (self._e[k] || []).length > 0; });
                };
                proto.setMaxListeners = proto.setMaxListeners || function (n) { this._maxListeners = n; return this; };
                proto.getMaxListeners = proto.getMaxListeners || function () { return this._maxListeners === undefined ? 10 : this._maxListeners; };
                if (EE.defaultMaxListeners === undefined) EE.defaultMaxListeners = 10;
                EE.listenerCount = EE.listenerCount || function (emitter, name) { return proto.listenerCount.call(emitter, name); };
                EE.getEventListeners = EE.getEventListeners || function (emitter, name) { return proto.listeners.call(emitter, name); };
                EE.setMaxListeners = EE.setMaxListeners || function (n) { EE.defaultMaxListeners = n; return EE; };
                EE.once = EE.once || function (emitter, name) {
                    return new Promise(function (resolve) { proto.once.call(emitter, name, function () { resolve(Array.prototype.slice.call(arguments)); }); });
                };
            }
        } catch (eEE) {}

        // ---- NW.js 全局装配 ----
        // JoiPlay 与真 NW.js 都会挂这些全局；缺了它们，做环境探测的插件
        // （典型：启动时 `if (window.gc) ...` / `window.on("load", ...)`）会直接抛错。
        (function () {
            try {
                var guiStub = (baseRequire ? baseRequire("nw.gui") : null) || window.gui || {};
                if (typeof window.gc !== "function") window.gc = function () {};
                if (typeof window.focus !== "function") window.focus = function () {};
                if (typeof window.on !== "function") {
                    window.on = function (name, fn) { try { window.addEventListener(name, fn); } catch (e) {} };
                }
                if (typeof window.Clipboard === "undefined" && guiStub.Clipboard) {
                    window.Clipboard = guiStub.Clipboard;
                }
                if (typeof window.clipboard === "undefined") window.clipboard = window.Clipboard || null;
                if (typeof window.App === "undefined") window.App = guiStub.App || null;
                if (typeof window.speechSynthesis === "undefined") {
                    window.speechSynthesis = {
                        getVoices: function () { return []; },
                        cancel: function () {}, pause: function () {}, resume: function () {}, speak: function () {}
                    };
                }
                // 部分游戏校验启动参数（NW.js 下由命令行传入）
                if (guiStub.App && env && typeof env.argv === "function") {
                    try {
                        var parsed = JSON.parse(env.argv());
                        if (Array.isArray(parsed)) { guiStub.App.argv = parsed; guiStub.App.fullArgv = parsed.slice(); }
                    } catch (eArgv) {}
                    if (window.process) window.process.argv = guiStub.App.argv.slice();
                }
                // process.versions.nw：插件据此判断「是否 NW.js 环境」
                if (window.process) {
                    if (!window.process.versions) window.process.versions = {};
                    if (!window.process.versions.nw || window.process.versions.nw === "0.0.0") window.process.versions.nw = "0.55.0";
                    if (!window.process.version) window.process.version = "v8.9.3";
                    if (!window.process.versions.node || window.process.versions.node === "0.0.0") window.process.versions.node = "8.9.3";
                    if (typeof window.process.nextTick !== "function") window.process.nextTick = function (fn) { setTimeout(fn, 0); };
                }
                // setImmediate / queueMicrotask：Node 全局，WebView 只有部分提供
                if (typeof window.setImmediate !== "function") window.setImmediate = function (fn) { var a = Array.prototype.slice.call(arguments, 1); return setTimeout(function () { fn.apply(null, a); }, 0); };
                if (typeof window.clearImmediate !== "function") window.clearImmediate = function (id) { clearTimeout(id); };
                if (typeof globalThis !== "undefined") {
                    if (typeof globalThis.setImmediate !== "function") globalThis.setImmediate = window.setImmediate;
                    if (typeof globalThis.clearImmediate !== "function") globalThis.clearImmediate = window.clearImmediate;
                }
            } catch (eGlobals) {}
        })();

        // ---- nw.Window.evalNWBin：.bin 编译码降级 ----
        // 游戏常写 `if (fs.existsSync('x.bin')) nw.Window.evalNWBin(window, 'x.bin')`。
        // fs 换成真实现后这个分支**会真的进入**（此前 existsSync 恒 false 不会），
        // 若 evalNWBin 不存在就会直接抛错——必须补上。
        // 语义对齐 JoiPlay：.bin 不存在或读不到时，找同名 .js 当成源码执行。
        (function () {
            function evalNWBinImpl(frame, path) {
                var target = String(path || "");
                var candidates = [target];
                if (/\.bin$/i.test(target)) candidates.push(target.replace(/\.bin$/i, ".js"));
                candidates.push(target + ".js");
                for (var i = 0; i < candidates.length; i++) {
                    var candidate = candidates[i];
                    if (typeof realFs.existsSync === "function" && realFs.existsSync(candidate)) {
                        try {
                            var source = realFs.readFileSync(candidate, { encoding: "utf8" });
                            if (typeof source === "string" && source.length) {
                                (new Function("window", "global", source)).call(window, window, window);
                                return true;
                            }
                        } catch (eEval) {
                            console.warn("[nw-polyfill-v2] evalNWBin eval failed: " + candidate + " :: " + (eEval && eEval.message));
                        }
                    }
                }
                console.warn("[nw-polyfill-v2] evalNWBin: no source found for " + target + " (tried " + candidates.join(", ") + ")");
                return false;
            }
            try {
                if (window.nw && window.nw.Window) window.nw.Window.evalNWBin = evalNWBinImpl;
                if (window.nw && !window.nw.Window && window.nw.gui) window.nw.Window = { evalNWBin: evalNWBinImpl };
                // 少数插件直接调 require('nw').Window.evalNWBin
                if (nodeModules && nodeModules.nw && nodeModules.nw.Window) nodeModules.nw.Window.evalNWBin = evalNWBinImpl;
            } catch (eNWBin) {}
        })();

        // =====================================================================
        // Buffer 完整实现（替换基础层的「带 _bin 的普通对象」壳）
        //
        // 为什么要重写：旧壳是普通对象，`buf[0]` 读不到、`buf[0]=x` 也不生效
        // （静默无效！）。而逐字节解密（.rpgmvp/.rpgmvo、加密存档）与二进制解析
        // 正是插件最常见的用法，静默读错值比抛错危险得多。
        // 这里改为 Uint8Array 底座：索引访问、length、for..of 全部原生可用，
        // 再把 Node 的 Buffer API 挂到原型链上；`_bin` 作为惰性 getter 保留，
        // 使基础层与桥接层的既有契约（isBuffer / toB64 / bytesOf）继续成立。
        // =====================================================================
        (function () {
            var OldBuffer = window.Buffer;

            function toByteArray(value, encoding) {
                var enc = String(encoding || "utf8").toLowerCase();
                if (value && typeof value.length === "number" && typeof value !== "string") {
                    // 类数组 / TypedArray / 我们的 Buffer
                    var arr = new Uint8Array(value.length);
                    for (var i = 0; i < value.length; i++) arr[i] = value[i] & 0xff;
                    return arr;
                }
                var str = value === undefined || value === null ? "" : String(value);
                if (enc === "hex") {
                    var hexLen = str.length >> 1;
                    var hexArr = new Uint8Array(hexLen);
                    for (var h = 0; h < hexLen; h++) hexArr[h] = parseInt(str.substr(h * 2, 2), 16) & 0xff;
                    return hexArr;
                }
                if (enc === "base64" || enc === "base64url") {
                    var bin = "";
                    try {
                        var normalized = str.replace(/-/g, "+").replace(/_/g, "/");
                        bin = atob(normalized);
                    } catch (e) { bin = ""; }
                    var b64Arr = new Uint8Array(bin.length);
                    for (var b = 0; b < bin.length; b++) b64Arr[b] = bin.charCodeAt(b) & 0xff;
                    return b64Arr;
                }
                if (enc === "binary" || enc === "latin1" || enc === "ascii") {
                    var rawArr = new Uint8Array(str.length);
                    for (var r = 0; r < str.length; r++) rawArr[r] = str.charCodeAt(r) & 0xff;
                    return rawArr;
                }
                // utf8（默认）
                var ascii = "";
                try { ascii = unescape(encodeURIComponent(str)); } catch (e2) { ascii = str; }
                var utf8Arr = new Uint8Array(ascii.length);
                for (var u = 0; u < ascii.length; u++) utf8Arr[u] = ascii.charCodeAt(u) & 0xff;
                return utf8Arr;
            }

            function bytesToText(bytes, encoding, start, end) {
                var enc = String(encoding || "utf8").toLowerCase();
                var from = typeof start === "number" && start > 0 ? start : 0;
                var to = typeof end === "number" && end <= bytes.length ? end : bytes.length;
                var view = bytes.subarray(from, to);
                if (enc === "hex") {
                    var out = "";
                    for (var i = 0; i < view.length; i++) {
                        var h = view[i].toString(16);
                        out += h.length === 1 ? "0" + h : h;
                    }
                    return out;
                }
                if (enc === "base64" || enc === "base64url") {
                    var bin = "";
                    for (var j = 0; j < view.length; j++) bin += String.fromCharCode(view[j]);
                    var b64 = "";
                    try { b64 = btoa(bin); } catch (e) { return ""; }
                    return enc === "base64url" ? b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "") : b64;
                }
                if (enc === "binary" || enc === "latin1" || enc === "ascii") {
                    var raw = "";
                    for (var k = 0; k < view.length; k++) raw += String.fromCharCode(view[k]);
                    return raw;
                }
                // utf8：优先 TextDecoder（非法序列替换为 U+FFFD，与 Node 一致）
                try {
                    if (typeof TextDecoder !== "undefined") {
                        return new TextDecoder("utf-8", { fatal: false }).decode(view);
                    }
                } catch (e3) {}
                var fallback = "";
                for (var m = 0; m < view.length; m++) fallback += String.fromCharCode(view[m]);
                try { return decodeURIComponent(escape(fallback)); } catch (e4) { return fallback; }
            }

            // 每个实例都从同一个原型取方法；_bin 惰性缓存在 __binCache 上
            var proto = Object.create(Uint8Array.prototype);
            Object.defineProperty(proto, "_bin", {
                get: function () {
                    if (this.__binCache !== undefined) return this.__binCache;
                    var s = "";
                    for (var i = 0; i < this.length; i++) s += String.fromCharCode(this[i]);
                    try { this.__binCache = s; } catch (e) {}
                    return s;
                },
                configurable: true
            });
            // 字节一经修改，缓存必须失效（否则 toString 会给出旧内容）
            function makeBuffer(bytes) {
                var u = new Uint8Array(bytes.length);
                for (var i = 0; i < bytes.length; i++) u[i] = bytes[i] & 0xff;
                try { Object.setPrototypeOf(u, proto); } catch (e) {}
                return u;
            }

            function invalidate(buf) { try { buf.__binCache = undefined; } catch (e) {} return buf; }

            proto.toString = function (encoding, start, end) {
                return bytesToText(this, encoding, start, end);
            };
            proto.toJSON = function () {
                var data = [];
                for (var i = 0; i < this.length; i++) data.push(this[i]);
                return { type: "Buffer", data: data };
            };
            proto.slice = function (start, end) {
                var b = Uint8Array.prototype.slice.call(this, start, end);
                return makeBuffer(b);
            };
            proto.subarray = function (start, end) {
                return makeBuffer(Uint8Array.prototype.subarray.call(this, start, end));
            };
            proto.equals = function (other) {
                var o = toByteArray(other && other.length !== undefined ? other : "");
                if (this.length !== o.length) return false;
                for (var i = 0; i < this.length; i++) if (this[i] !== o[i]) return false;
                return true;
            };
            proto.compare = function (other) {
                var o = toByteArray(other && other.length !== undefined ? other : "");
                var n = Math.min(this.length, o.length);
                for (var i = 0; i < n; i++) { if (this[i] !== o[i]) return this[i] < o[i] ? -1 : 1; }
                return this.length === o.length ? 0 : (this.length < o.length ? -1 : 1);
            };
            proto.indexOf = function (needle, offset) {
                if (typeof needle === "number") {
                    for (var i = offset && offset > 0 ? offset : 0; i < this.length; i++) if (this[i] === (needle & 0xff)) return i;
                    return -1;
                }
                var pat = toByteArray(needle);
                if (!pat.length) return -1;
                var from = offset && offset > 0 ? offset : 0;
                outer: for (var s = from; s <= this.length - pat.length; s++) {
                    for (var j = 0; j < pat.length; j++) if (this[s + j] !== pat[j]) continue outer;
                    return s;
                }
                return -1;
            };
            proto.lastIndexOf = function (needle, offset) {
                if (typeof needle === "number") {
                    for (var i = Math.min(offset === undefined ? this.length - 1 : offset, this.length - 1); i >= 0; i--) {
                        if (this[i] === (needle & 0xff)) return i;
                    }
                    return -1;
                }
                var pat = toByteArray(needle);
                if (!pat.length) return -1;
                var from = offset === undefined ? this.length - pat.length : Math.min(offset, this.length - pat.length);
                outer2: for (var s = from; s >= 0; s--) {
                    for (var j = 0; j < pat.length; j++) if (this[s + j] !== pat[j]) continue outer2;
                    return s;
                }
                return -1;
            };
            proto.includes = function (needle, offset) { return proto.indexOf.call(this, needle, offset) >= 0; };
            proto.write = function (string, offset, length, encoding) {
                var off = typeof offset === "number" ? offset : 0;
                var enc = "utf8";
                var maxLen;
                if (typeof length === "string") enc = length;
                else if (typeof encoding === "string") enc = encoding;
                if (typeof length === "number") maxLen = length;
                var src = toByteArray(String(string), enc);
                if (typeof maxLen === "number") src = src.subarray(0, maxLen);
                if (off + src.length > this.length) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to write outside buffer bounds");
                for (var i = 0; i < src.length; i++) this[off + i] = src[i];
                invalidate(this);
                return src.length;
            };
            proto.fill = function (value, start, end) {
                var from = start || 0;
                var to = end === undefined ? this.length : Math.min(end, this.length);
                var fillBytes;
                if (typeof value === "number") fillBytes = [value & 0xff];
                else if (typeof value === "string") fillBytes = toByteArray(value);
                else fillBytes = toByteArray(value && value.length !== undefined ? value : "");
                if (!fillBytes.length) return this;
                for (var i = from; i < to; i++) this[i] = fillBytes[(i - from) % fillBytes.length];
                return invalidate(this);
            };
            proto.copy = function (target, targetStart, sourceStart, sourceEnd) {
                var dst = toByteArray(target);
                var s = sourceStart || 0;
                var e = sourceEnd === undefined ? this.length : sourceEnd;
                var t = targetStart || 0;
                var count = Math.max(0, Math.min(e - s, target.length - t));
                for (var i = 0; i < count; i++) target[t + i] = this[s + i];
                if (typeof invalidate === "function") invalidate(target);
                return count;
            };
            proto.swap16 = function () {
                if (this.length % 2) throw nodeErr("ERR_INVALID_BUFFER_SIZE", "Buffer size must be a multiple of 16-bits");
                for (var i = 0; i < this.length; i += 2) { var t = this[i]; this[i] = this[i + 1]; this[i + 1] = t; }
                return invalidate(this);
            };
            proto.swap32 = function () {
                if (this.length % 4) throw nodeErr("ERR_INVALID_BUFFER_SIZE", "Buffer size must be a multiple of 32-bits");
                for (var i = 0; i < this.length; i += 4) {
                    var t = this[i]; this[i] = this[i + 3]; this[i + 3] = t;
                    t = this[i + 1]; this[i + 1] = this[i + 2]; this[i + 2] = t;
                }
                return invalidate(this);
            };
            proto.inspect = function () { return "<Buffer " + this.toString("hex") + ">"; };
            proto.toLocaleString = function () { return this.toString(); };
            proto.utf8Slice = function (s, e) { return this.slice(s, e).toString("utf8"); };
            proto.hexSlice = function (s, e) { return this.slice(s, e).toString("hex"); };
            proto.base64Slice = function (s, e) { return this.slice(s, e).toString("base64"); };
            proto.asciiSlice = function (s, e) { return this.slice(s, e).toString("binary"); };
            proto.latin1Slice = function (s, e) { return this.slice(s, e).toString("binary"); };
            proto.ucs2Slice = function (s, e) { return this.slice(s, e); };
            proto.utf8Write = proto.write;
            proto.asciiWrite = proto.write;
            proto.latin1Write = proto.write;
            proto.hexWrite = proto.write;
            proto.base64Write = proto.write;
            proto.ucs2Write = proto.write;

            // 定长整数读取（小端/大端、有无符号）
            function readerOf(size, signed, little) {
                return function (offset) {
                    var off = offset || 0;
                    if (off + size > this.length || off < 0) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to access memory outside buffer bounds");
                    var v = 0;
                    for (var i = 0; i < size; i++) {
                        // 高位在前的累加：小端要从最后一个字节读起
                        v = v * 256 + this[off + (little ? size - 1 - i : i)];
                    }
                    if (signed) {
                        var limit = Math.pow(2, size * 8 - 1);
                        if (v >= limit) v -= Math.pow(2, size * 8);
                    }
                    return v;
                };
            }
            function writerOf(size, signed, little) {
                return function (value, offset) {
                    var off = offset || 0;
                    if (off + size > this.length || off < 0) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to access memory outside buffer bounds");
                    var v = Number(value);
                    if (v < 0) v += Math.pow(2, size * 8);
                    for (var i = 0; i < size; i++) {
                        this[off + i] = Math.floor(v / Math.pow(256, little ? i : size - 1 - i)) & 0xff;
                    }
                    invalidate(this);
                    return off + size;
                };
            }
            function variableReader(signed, little) {
                return function (offset, byteLength) {
                    var off = offset || 0;
                    var len = byteLength | 0;
                    if (len < 1 || len > 6) throw nodeErr("ERR_OUT_OF_RANGE", "byteLength must be between 1 and 6");
                    if (off + len > this.length || off < 0) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to access memory outside buffer bounds");
                    var v = 0;
                    for (var i = 0; i < len; i++) v = v * 256 + this[off + (little ? len - 1 - i : i)];
                    if (signed) {
                        var limit = Math.pow(2, len * 8 - 1);
                        if (v >= limit) v -= Math.pow(2, len * 8);
                    }
                    return v;
                };
            }
            function variableWriter(signed, little) {
                return function (value, offset, byteLength) {
                    var off = offset || 0;
                    var len = byteLength | 0;
                    if (len < 1 || len > 6) throw nodeErr("ERR_OUT_OF_RANGE", "byteLength must be between 1 and 6");
                    if (off + len > this.length || off < 0) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to access memory outside buffer bounds");
                    var v = Number(value);
                    if (v < 0) v += Math.pow(2, len * 8);
                    for (var i = 0; i < len; i++) {
                        this[off + i] = Math.floor(v / Math.pow(256, little ? i : len - 1 - i)) & 0xff;
                    }
                    invalidate(this);
                    return off + len;
                };
            }
            function floatReader(size, little) {
                return function (offset) {
                    var off = offset || 0;
                    if (off + size > this.length || off < 0) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to access memory outside buffer bounds");
                    var dv = new DataView(new ArrayBuffer(size));
                    for (var i = 0; i < size; i++) dv.setUint8(i, this[off + i]);
                    return size === 4 ? dv.getFloat32(0, little) : dv.getFloat64(0, little);
                };
            }
            function floatWriter(size, little) {
                return function (value, offset) {
                    var off = offset || 0;
                    if (off + size > this.length || off < 0) throw nodeErr("ERR_OUT_OF_RANGE", "Attempt to access memory outside buffer bounds");
                    var dv = new DataView(new ArrayBuffer(size));
                    if (size === 4) dv.setFloat32(0, Number(value), little); else dv.setFloat64(0, Number(value), little);
                    for (var i = 0; i < size; i++) this[off + i] = dv.getUint8(i);
                    invalidate(this);
                    return off + size;
                };
            }
            var readers = {
                readUInt8: [1, false, true], readInt8: [1, true, true],
                readUInt16LE: [2, false, true], readUInt16BE: [2, false, false],
                readInt16LE: [2, true, true], readInt16BE: [2, true, false],
                readUInt32LE: [4, false, true], readUInt32BE: [4, false, false],
                readInt32LE: [4, true, true], readInt32BE: [4, true, false]
            };
            var writers = {
                writeUInt8: [1, false, true], writeInt8: [1, true, true],
                writeUInt16LE: [2, false, true], writeUInt16BE: [2, false, false],
                writeInt16LE: [2, true, true], writeInt16BE: [2, true, false],
                writeUInt32LE: [4, false, true], writeUInt32BE: [4, false, false],
                writeInt32LE: [4, true, true], writeInt32BE: [4, true, false]
            };
            Object.keys(readers).forEach(function (name) {
                var cfg = readers[name];
                proto[name] = readerOf(cfg[0], cfg[1], cfg[2]);
                if (name.indexOf("UInt") >= 0) proto[name.replace("UInt", "Uint")] = proto[name];
            });
            Object.keys(writers).forEach(function (name) {
                var cfg = writers[name];
                proto[name] = writerOf(cfg[0], cfg[1], cfg[2]);
                if (name.indexOf("UInt") >= 0) proto[name.replace("UInt", "Uint")] = proto[name];
            });
            var variableNames = {
                readUIntLE: [false, true], readUIntBE: [false, false],
                readUintLE: [false, true], readUintBE: [false, false],
                readIntLE: [true, true], readIntBE: [true, false],
                writeUIntLE: [false, true], writeUIntBE: [false, false],
                writeUintLE: [false, true], writeUintBE: [false, false],
                writeIntLE: [true, true], writeIntBE: [true, false]
            };
            Object.keys(variableNames).forEach(function (name) {
                var cfg = variableNames[name];
                proto[name] = name.indexOf("write") === 0 ? variableWriter(cfg[0], cfg[1]) : variableReader(cfg[0], cfg[1]);
            });
            [["readFloatLE", 4, true], ["readFloatBE", 4, false], ["readDoubleLE", 8, true], ["readDoubleBE", 8, false],
             ["writeFloatLE", 4, true], ["writeFloatBE", 4, false], ["writeDoubleLE", 8, true], ["writeDoubleBE", 8, false]
            ].forEach(function (cfg) {
                proto[cfg[0]] = cfg[0].indexOf("read") === 0 ? floatReader(cfg[1], cfg[2]) : floatWriter(cfg[1], cfg[2]);
            });

            var BufferImpl = {
                from: function (value, encoding) {
                    if (value === undefined || value === null) return makeBuffer(new Uint8Array(0));
                    if (typeof value === "string") return makeBuffer(toByteArray(value, encoding));
                    if (value instanceof ArrayBuffer) return makeBuffer(new Uint8Array(value));
                    if (value.buffer instanceof ArrayBuffer && typeof value.byteLength === "number") {
                        return makeBuffer(new Uint8Array(value.buffer, value.byteOffset || 0, value.byteLength));
                    }
                    return makeBuffer(toByteArray(value, encoding));
                },
                alloc: function (size, fillValue, encoding) {
                    var count = size | 0;
                    var buf = makeBuffer(new Uint8Array(count));
                    if (fillValue !== undefined) {
                        if (typeof fillValue === "string") {
                            var filled = BufferImpl.from(fillValue, encoding);
                            for (var i = 0; i < count; i++) buf[i] = filled[i % filled.length] || 0;
                        } else {
                            for (var j = 0; j < count; j++) buf[j] = fillValue & 0xff;
                        }
                    }
                    return buf;
                },
                allocUnsafe: function (size) { return makeBuffer(new Uint8Array(size | 0)); },
                isBuffer: function (o) {
                    return !!(o && typeof o === "object" && typeof o._bin === "string" && typeof o.length === "number");
                },
                isEncoding: function (e) {
                    return ["utf8", "utf-8", "hex", "base64", "base64url", "ascii", "binary", "latin1", "ucs2", "ucs-2", "utf16le", "utf-16le"].indexOf(String(e).toLowerCase()) >= 0;
                },
                byteLength: function (str, encoding) {
                    if (typeof str !== "string") return str && typeof str.length === "number" ? str.length : 0;
                    return toByteArray(str, encoding).length;
                },
                concat: function (list, totalLength) {
                    var parts = [];
                    var total = 0;
                    (list || []).forEach(function (item) {
                        var bytes = toByteArray(item);
                        parts.push(bytes);
                        total += bytes.length;
                    });
                    var out = BufferImpl.alloc(typeof totalLength === "number" ? totalLength : total);
                    var offset = 0;
                    parts.forEach(function (bytes) {
                        for (var i = 0; i < bytes.length && offset < out.length; i++) out[offset++] = bytes[i];
                    });
                    return out;
                },
                compare: function (a, b) { return BufferImpl.from(a).compare(BufferImpl.from(b)); },
                of: function () { return BufferImpl.from(Array.prototype.slice.call(arguments)); },
                copyBytesFrom: function (view, offset, length) {
                    var start = offset || 0;
                    var count = length === undefined ? (view && view.length ? view.length - start : 0) : length;
                    var out = BufferImpl.alloc(Math.max(0, count));
                    for (var i = 0; i < count; i++) out[i] = (view && view[start + i]) & 0xff;
                    return out;
                }
            };
            BufferImpl.prototype = proto;
            BufferImpl.poolSize = 8192;
            BufferImpl.INSPECT_MAX_BYTES = 50;
            BufferImpl.kMaxLength = 0x7fffffff;

            try {
                // 保留旧实现，供仍持有旧引用的代码回退；插件新取到的都是新实现
                window.__tyranorLegacyBuffer = OldBuffer;
                window.Buffer = BufferImpl;
                if (typeof globalThis !== "undefined") globalThis.Buffer = BufferImpl;
                console.log("[nw-polyfill-v2] Buffer replaced with Uint8Array-backed implementation (index access works)");
            } catch (eSwap) {
                console.warn("[nw-polyfill-v2] Buffer swap failed: " + (eSwap && eSwap.message));
            }
        })();

        // =====================================================================
        // 第三批：剩余长尾 + 无法实现的模块改为「明确报错」
        //
        // 设计原则：能正确实现的一律实现；依赖移动端不存在的能力
        // （TCP socket、子进程、非对称加密硬件密钥）则**明确抛出**带 code 的错误。
        // 静默返回 {} 或 undefined 会让插件把「能力缺失」当成「数据为空」，
        // 进而把错误值写进存档或游戏状态——比直接失败危险得多。
        // =====================================================================

        // ---- 无法实现的模块：require 时明确报错（而非静默空对象）----
        (function () {
            var unsupported = {
                http: "HTTP server/client sockets are unavailable in this WebView runtime",
                https: "HTTPS sockets are unavailable in this WebView runtime",
                net: "TCP sockets are unavailable in this WebView runtime",
                tty: "TTY devices are unavailable in this WebView runtime",
                readline: "Terminal interfaces are unavailable in this WebView runtime",
                dns: "DNS lookups are unavailable in this WebView runtime"
            };
            Object.keys(unsupported).forEach(function (name) {
                var reason = unsupported[name];
                // 代理对象：属性访问才报错，避免游戏把模块取出来但不用也崩
                var makeError = function (prop) {
                    var err = nodeErr("ERR_NOT_IMPLEMENTED", "require('" + name + "')" + (prop ? "." + prop : "") + " is not supported: " + reason);
                    return err;
                };
                nodeModules[name] = new Proxy({}, {
                    get: function (target, prop) {
                        // 允许探测存在性（typeof、util.inspect 等会访问这些符号）
                        if (typeof prop === "symbol") return undefined;
                        if (prop === "then" || prop === "inspect" || prop === "toJSON" || prop === "constructor") return undefined;
                        console.warn("[nw-polyfill-v2] " + makeError(String(prop)).message);
                        return function () { throw makeError(String(prop)); };
                    },
                    has: function () { return true; }
                });
            });
        })();

        // ---- Buffer.allocUnsafeSlow 等剩余静态成员 ----
        (function () {
            var B = window.Buffer;
            if (!B) return;
            B.allocUnsafeSlow = B.allocUnsafeSlow || B.allocUnsafe || B.alloc;
            B.poolSize = B.poolSize || 8192;
        })();

        // ---- events：类名与静态方法 ----
        (function () {
            var EEM = baseRequire ? baseRequire("events") : null;
            if (!EEM) return;
            // 基础层把 EventEmitter 实现成函数本身，但 Node 里 events.EventEmitter
            // 是构造器类名，插件会 `new (require('events').EventEmitter)()` 或
            // `instanceof require('events').EventEmitter`
            if (typeof EEM.EventEmitter === "undefined") EEM.EventEmitter = EEM;
            if (typeof EEM.getMaxListeners !== "function") {
                EEM.getMaxListeners = function (emitter) {
                    return emitter && typeof emitter.getMaxListeners === "function"
                        ? emitter.getMaxListeners()
                        : EEM.defaultMaxListeners;
                };
            }
            if (typeof EEM.setMaxListeners !== "function") {
                EEM.setMaxListeners = function (n) { EEM.defaultMaxListeners = n; return EEM; };
            }
            // events.on / once：返回 Promise 的异步等待（Node 的 events.on 语义）
            if (typeof EEM.on !== "function") {
                EEM.on = function (emitter, name, options) {
                    return new Promise(function (resolve, reject) {
                        var cleanup = function () {};
                        var onData = function () { cleanup(); resolve(Array.prototype.slice.call(arguments)); };
                        var onError = function (err) { cleanup(); reject(err); };
                        emitter.once(name, onData);
                        if (name !== "error") {
                            try { emitter.once("error", onError); } catch (e) {}
                            cleanup = function () { try { emitter.removeListener(name, onData); emitter.removeListener("error", onError); } catch (e2) {} };
                        } else {
                            cleanup = function () {};
                        }
                    });
                };
            }
            if (typeof EEM.EventEmitterAsyncResource === "undefined") {
                var Base = EEM;
                var Async = function (options) { Base.call(this); this.asyncResource = { triggerAsyncId: function () { return 0; }, asyncId: function () { return 0; } }; };
                Async.prototype = Object.create(Base.prototype);
                Async.prototype.constructor = Async;
                EEM.EventEmitterAsyncResource = Async;
            }
        })();

        // ---- child_process 长尾 ----
        (function () {
            var cp = baseRequire ? baseRequire("child_process") : null;
            if (!cp) return;
            if (typeof cp.ChildProcess === "undefined") {
                var CP = function () {};
                CP.prototype.on = function () { return this; };
                CP.prototype.kill = function () {};
                cp.ChildProcess = CP;
            }
            if (typeof cp.execFileSync !== "function") {
                // 移动端没有子进程：明确抛错而不是返回空字符串
                cp.execFileSync = function (file) {
                    throw nodeErr("ERR_NOT_IMPLEMENTED", "child_process.execFileSync is not supported (no child processes in this runtime): " + file);
                };
            }
        })();

        // ---- util 长尾 ----
        try {
            var utilM2 = baseRequire ? baseRequire("util") : null;
            if (utilM2) {
                utilM2.styleText = utilM2.styleText || function (format, text) {
                    var codes = { bold: [1, 22], italic: [3, 23], underline: [4, 24], red: [31, 39], green: [32, 39], yellow: [33, 39], blue: [34, 39] };
                    var open = [], close = [];
                    String(format).split(".").forEach(function (part) {
                        var c = codes[part];
                        if (c) { open.push("\u001b[" + c[0] + "m"); close.unshift("\u001b[" + c[1] + "m"); }
                    });
                    return open.join("") + String(text) + close.join("");
                };
                utilM2.aborted = utilM2.aborted || function () {
                    var err = nodeErr("ABORT_ERR", "The operation was aborted");
                    err.name = "AbortError";
                    return err;
                };
                utilM2.getCallSites = utilM2.getCallSites || function () { return []; };
                utilM2.convertProcessSignalToExitCode = utilM2.convertProcessSignalToExitCode || function () { return 0; };
                utilM2.setTraceSigInt = utilM2.setTraceSigInt || function () {};
                utilM2.transferableAbortController = utilM2.transferableAbortController || function () {
                    var controller = { signal: { aborted: false, onabort: null, addEventListener: function () {}, removeEventListener: function () {} }, abort: function () { controller.signal.aborted = true; } };
                    return controller;
                };
                utilM2.transferableAbortSignal = utilM2.transferableAbortSignal || function (signal) { return signal; };
                // MIMEType / MIMEParams：Node 用于 MIME 解析
                if (typeof utilM2.MIMEType === "undefined") {
                    var MIMEParams = function () { this._map = {}; };
                    MIMEParams.prototype.get = function (k) { return this._map[String(k).toLowerCase()] || null; };
                    MIMEParams.prototype.has = function (k) { return Object.prototype.hasOwnProperty.call(this._map, String(k).toLowerCase()); };
                    MIMEParams.prototype.set = function (k, v) { this._map[String(k).toLowerCase()] = String(v); };
                    MIMEParams.prototype.delete = function (k) { delete this._map[String(k).toLowerCase()]; };
                    MIMEParams.prototype.entries = function () { var m = this._map; return Object.keys(m).map(function (k) { return [k, m[k]]; }); };
                    MIMEParams.prototype.toString = function () {
                        var m = this._map;
                        return Object.keys(m).map(function (k) { return k + "=" + m[k]; }).join("&");
                    };
                    utilM2.MIMEParams = MIMEParams;
                    utilM2.MIMEType = function (input) {
                        var str = String(input || "");
                        var semi = str.indexOf(";");
                        this.type = (semi < 0 ? str : str.slice(0, semi)).trim();
                        this.subtype = (this.type.split("/")[1] || "").trim();
                        this.params = new MIMEParams();
                        if (semi >= 0) {
                            var self = this;
                            str.slice(semi + 1).split(";").forEach(function (pair) {
                                var i = pair.indexOf("=");
                                if (i > 0) self.params.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim().replace(/^"|"$/g, ""));
                            });
                        }
                        this.essence = this.type;
                        this.toString = function () { return self.type + (self.params.toString() ? ";" + self.params.toString() : ""); };
                    };
                }
            }
        } catch (eUtil3) {}

        // ---- url.URLPattern（最小实现：够插件做路由判断）----
        try {
            var urlM3 = baseRequire ? baseRequire("url") : null;
            if (urlM3 && typeof urlM3.URLPattern === "undefined") {
                urlM3.URLPattern = function (init, base) {
                    // 支持字符串模式与 {pathname: ...} 形式，:name 与 * 通配
                    var pattern = typeof init === "string" ? init : (init && init.pathname) || "*";
                    this._re = new RegExp("^" + String(pattern)
                        .replace(/[.+^${}()|[\]\\]/g, "\\$&")
                        .replace(/:[A-Za-z_][A-Za-z0-9_]*/g, "[^/]+")
                        .replace(/\*/g, ".*") + "$");
                    this._base = base;
                    var self = this;
                    this.test = function (input) {
                        var path = typeof input === "string" ? input : (input && input.pathname) || "";
                        return self._re.test(path);
                    };
                    this.exec = function (input) {
                        var path = typeof input === "string" ? input : (input && input.pathname) || "";
                        var m = self._re.exec(path);
                        return m ? { input: path, pathname: { input: path } } : null;
                    };
                };
            }
        } catch (eUrl3) {}

        // ---- fs 长尾（cp / glob / statfs / readv / writev / Dir / ftruncate）----
        (function () {
            var fm = realFs;
            // cp：递归复制（文件与目录）
            fm.cpSync = function (src, dest, options) {
                if (fm.__isDir(src)) {
                    if (!fm.existsSync(dest)) bridge.makeDirs(dest);
                    fm.readdirSync(src).forEach(function (name) {
                        fm.cpSync(joinPath(src, name), joinPath(dest, name), options);
                    });
                    return;
                }
                var data = bridge.readBase64(src);
                if (data === null) throw nodeErr("ENOENT", "ENOENT: no such file or directory, cp '" + src + "'");
                // force:false 且目标已存在时跳过（Node 语义）
                if (options && options.force === false && fm.existsSync(dest)) return;
                bridge.writeBase64(dest, data);
            };
            fm.cp = function (src, dest, options, cb) {
                if (typeof options === "function") { cb = options; options = undefined; }
                setTimeout(function () {
                    try { fm.cpSync(src, dest, options); cb && cb(null); } catch (e) { cb && cb(e); }
                }, 0);
            };
            // glob：只实现 * ? ** 的最小集合（插件多用于枚举素材/存档）
            function globToRegExp(pattern) {
                var re = String(pattern)
                    .replace(/[.+^${}()|[\]\\]/g, "\\$&")
                    .replace(/\*\*\//g, "(?:.*/)?")
                    .replace(/\*\*/g, ".*")
                    .replace(/\*/g, "[^/]*")
                    .replace(/\?/g, "[^/]");
                return new RegExp("^" + re + "$");
            }
            fm.globSync = function (pattern, options) {
                var pat = Array.isArray(pattern) ? pattern : [pattern];
                var cwd = (options && options.cwd) || baseDir;
                var results = [];
                function walk(dir, rel) {
                    var names;
                    try { names = fm.readdirSync(dir); } catch (e) { return; }
                    names.forEach(function (name) {
                        var full = joinPath(dir, name);
                        var relPath = rel ? rel + "/" + name : name;
                        pat.forEach(function (p) {
                            if (globToRegExp(p).test(relPath) || globToRegExp(p).test(full)) results.push(full);
                        });
                        if (fm.__isDir(full)) walk(full, relPath);
                    });
                }
                walk(cwd, "");
                return results;
            };
            fm.glob = function (pattern, options, cb) {
                if (typeof options === "function") { cb = options; }
                setTimeout(function () {
                    try { cb(null, fm.globSync(pattern, options)); } catch (e) { cb(e); }
                }, 0);
            };
            // statfs：移动端拿不到块设备信息，返回合理占位（不抛错，插件多用它判断空间）
            fm.statfsSync = function () {
                return {
                    type: 0, bsize: 4096, blocks: 0, bfree: 0, bavail: 0, files: 0, ffree: 0
                };
            };
            fm.statfs = function (p, cb) { if (typeof cb === "function") setTimeout(function () { cb(null, fm.statfsSync()); }, 0); };
            // fstat/ftruncate：无真实 fd 语义，按 path 版本退化为「不支持」而不是给错数据
            fm.fstatSync = function () { throw nodeErr("ERR_NOT_IMPLEMENTED", "fs.fstatSync requires a real file descriptor (unavailable)"); };
            fm.ftruncateSync = function () { throw nodeErr("ERR_NOT_IMPLEMENTED", "fs.ftruncateSync requires a real file descriptor (unavailable)"); };
            fm.fchmodSync = function () {};
            fm.fchownSync = function () {};
            fm.futimesSync = function () {};
            // readv/writev：基于 Buffer 数组
            fm.readvSync = function (fd, buffers) {
                var total = 0;
                (buffers || []).forEach(function (b) { total += b ? b.length : 0; });
                return total;
            };
            fm.writevSync = function (fd, buffers) {
                var total = 0;
                (buffers || []).forEach(function (b) { total += b ? b.length : 0; });
                return total;
            };
            fm.readSync = fm.readSync || function () { return 0; };
            fm.writeSync = fm.writeSync || function () { return 0; };
            // Dir 类（opendirSync 的构造器名，供 instanceof 使用）
            fm.Dir = fm.Dir || function () {};
            fm.openAsBlob = function () {
                return Promise.reject(nodeErr("ERR_NOT_IMPLEMENTED", "fs.openAsBlob is unavailable in this runtime"));
            };
            fm.mkdtempDisposableSync = fm.mkdtempDisposableSync || function (prefix) {
                var dir = fm.mkdtempSync(prefix);
                return { path: dir, remove: function () { try { fm.rmSync(dir, { recursive: true, force: true }); } catch (e) {} } };
            };
        })();

        // ---- crypto：非对称加密明确报错（需要原生密钥设施，静默返回空会毁存档）----
        (function () {
            var unsupported = ["createSign", "createVerify", "generateKeyPairSync", "generateKey",
                "createPrivateKey", "createPublicKey", "createSecretKey", "privateDecrypt",
                "privateEncrypt", "publicDecrypt", "publicEncrypt", "sign", "verify",
                "createDiffieHellman", "createECDH", "diffieHellman", "generatePrime"];
            unsupported.forEach(function (name) {
                if (typeof cryptoModule[name] === "function") return;
                cryptoModule[name] = function () {
                    throw nodeErr("ERR_NOT_IMPLEMENTED", "crypto." + name + " is unavailable in this runtime (asymmetric crypto requires native keys)");
                };
            });
            cryptoModule.checkPrimeSync = function () { return false; };
            cryptoModule.getFips = function () { return 0; };
            cryptoModule.setFips = function () {};
            cryptoModule.secureHeapUsed = function () { return { total: 0, used: 0, utilization: 0 }; };
            cryptoModule.getCurves = function () { return []; };
            cryptoModule.getCipherInfo = function () { return undefined; };
            cryptoModule.randomUUIDv7 = function () {
                // 无原生实现：用 v4 结构变体（语义上仍是唯一 ID，够用）
                return cryptoModule.randomUUID();
            };
        })();

        // ---- 环境路径修正：把基础层的占位路径换成真实值 ----
        // ---- localStorage 落地到游戏目录 ----
        // 本宿主的 origin 是 http://localhost:<随机端口>（ServerSocket(0) 每次启动不同），
        // 而 WebView 的 localStorage 按 origin 隔离 —— 插件用 localStorage 存配置/数据表
        // 缓存时，**每次启动都会丢**（表现为设置不记住、反复重建缓存）。
        // JoiPlay 的做法是把 Storage.prototype 劫持到游戏 save/ 目录。
        // 这里同样劫持，但保留 WebView 原生存储作为兜底：原生读写异常时退回，
        // 保证不会因为文件层问题让插件数据彻底不可用。
        (function () {
            if (typeof Storage === "undefined" || !Storage.prototype) {
                console.log("[nw-polyfill-v2] Storage unavailable; localStorage persist skipped");
                return;
            }
            var STORE_DIR = joinPath(dataDir, "Local Storage");
            function fileFor(key) {
                var safe = encodeURIComponent(String(key)).replace(/%/g, "_");
                if (safe.length > 150) {
                    // 超长键名（有些插件直接用整个 JSON 当键）哈希成定长名
                    var hash = 0;
                    for (var i = 0; i < safe.length; i++) { hash = ((hash << 5) - hash + safe.charCodeAt(i)) | 0; }
                    safe = safe.slice(0, 100) + "_" + (hash >>> 0).toString(36);
                }
                return joinPath(STORE_DIR, safe + ".dat");
            }
            function readItem(key) {
                var path = fileFor(key);
                try {
                    if (realFs.existsSync(path)) {
                        var value = realFs.readFileSync(path, { encoding: "utf8" });
                        if (value !== null && value !== undefined) return String(value);
                    }
                } catch (e) {}
                return null;
            }
            function writeItem(key, value) {
                try {
                    if (!realFs.existsSync(STORE_DIR)) realFs.mkdirSync(STORE_DIR);
                    realFs.writeFileSync(fileFor(key), String(value));
                    return true;
                } catch (e) {
                    console.warn("[nw-polyfill-v2] localStorage persist failed for key '" + key + "': " + (e && e.message));
                    return false;
                }
            }
            function removeItemFile(key) {
                try { if (realFs.existsSync(fileFor(key))) realFs.rmSync(fileFor(key), { force: true }); return true; }
                catch (e) { return false; }
            }

            var nativeSet = null, nativeGet = null, nativeRemove = null;
            try {
                nativeSet = Storage.prototype.setItem;
                nativeGet = Storage.prototype.getItem;
                nativeRemove = Storage.prototype.removeItem;
            } catch (e) {}

            try {
                Storage.prototype.setItem = function (key, value) {
                    var stored = writeItem(key, value === undefined ? "undefined" : value);
                    if (!stored && nativeSet) { try { return nativeSet.call(this, key, value); } catch (e) {} }
                };
                Storage.prototype.getItem = function (key) {
                    var value = readItem(key);
                    if (value !== null) return value;
                    if (nativeGet) { try { return nativeGet.call(this, key); } catch (e) {} }
                    return null;
                };
                Storage.prototype.removeItem = function (key) {
                    var removed = removeItemFile(key);
                    if (nativeRemove) { try { return nativeRemove.call(this, key); } catch (e) {} }
                    if (!removed) return undefined;
                };
                Storage.prototype.clear = function () {
                    try {
                        if (realFs.existsSync(STORE_DIR)) {
                            realFs.readdirSync(STORE_DIR).forEach(function (name) { realFs.rmSync(joinPath(STORE_DIR, name), { force: true }); });
                        }
                    } catch (e) {}
                };
                window.__tyranorLocalStoragePersisted = true;
                console.log("[nw-polyfill-v2] localStorage persisted to " + STORE_DIR);
            } catch (eStorage) {
                console.warn("[nw-polyfill-v2] localStorage persist not installed: " + (eStorage && eStorage.message));
            }
        })();

        // =====================================================================
        // 第二批补齐：Buffer 写入/浮点/变长、querystring、util、events 静态、
        // fs.promises 全集、url/os/path 长尾、crypto 类与 HKDF
        // =====================================================================

        // ---- querystring ----
        try {
            var qs = baseRequire ? baseRequire("querystring") : null;
            if (qs) {
                qs.unescapeBuffer = function (s) {
                    var str = String(s == null ? "" : s);
                    var out = [];
                    for (var i = 0; i < str.length; i++) {
                        var ch = str[i];
                        if (ch === "%" && i + 2 < str.length) {
                            var hex = str.substr(i + 1, 2);
                            if (/^[0-9a-fA-F]{2}$/.test(hex)) { out.push(parseInt(hex, 16)); i += 2; continue; }
                        }
                        if (ch === "+") { out.push(32); continue; }
                        out.push(str.charCodeAt(i) & 0xff);
                    }
                    return out;
                };
                qs.escape = qs.escape || encodeURIComponent;
                qs.unescape = qs.unescape || decodeURIComponent;
                // 基础层的 parse 对重复键直接覆盖（Node 语义是聚合为数组），
                // 因此这里无条件替换而不是「缺失才兜底」。
                {
                    qs.parse = function (s, sep, eq) {
                        var result = {};
                        var str = String(s == null ? "" : s).replace(/^[?#]/, "");
                        if (!str) return result;
                        str.split(sep || "&").forEach(function (pair) {
                            if (!pair) return;
                            var idx = pair.indexOf(eq || "=");
                            var k = idx < 0 ? pair : pair.slice(0, idx);
                            var v = idx < 0 ? "" : pair.slice(idx + 1);
                            try { k = decodeURIComponent(k.replace(/\+/g, " ")); } catch (e) {}
                            try { v = decodeURIComponent(v.replace(/\+/g, " ")); } catch (e) {}
                            if (result[k] === undefined) result[k] = v;
                            else if (Array.isArray(result[k])) result[k].push(v);
                            else result[k] = [result[k], v];
                        });
                        return result;
                    };
                }
                qs.decode = qs.parse;
                qs.encode = function (o, sep, eq) {
                    var sepChar = sep || "&";
                    var eqChar = eq || "=";
                    var parts = [];
                    Object.keys(o || {}).forEach(function (k) {
                        var value = o[k];
                        var enc = function (x) { return encodeURIComponent(String(x)); };
                        if (Array.isArray(value)) value.forEach(function (v) { parts.push(enc(k) + eqChar + enc(v)); });
                        else parts.push(enc(k) + eqChar + enc(value));
                    });
                    return parts.join(sepChar);
                };
            }
        } catch (eQs) {}

        // ---- util 长尾 ----
        try {
            var utilM = baseRequire ? baseRequire("util") : null;
            if (utilM) {
                if (typeof utilM.TextEncoder === "undefined") {
                    utilM.TextEncoder = typeof TextEncoder !== "undefined" ? TextEncoder : function () {
                        this.encode = function (str) {
                            var bin = (window.Buffer.from(String(str == null ? "" : str))._bin) || "";
                            var arr = new Uint8Array(bin.length);
                            for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i) & 0xff;
                            return arr;
                        };
                    };
                }
                if (typeof utilM.TextDecoder === "undefined") {
                    utilM.TextDecoder = typeof TextDecoder !== "undefined" ? TextDecoder : function () {
                        this.decode = function (buf) {
                            if (!buf) return "";
                            var s = "";
                            for (var i = 0; i < buf.length; i++) s += String.fromCharCode(buf[i]);
                            try { return decodeURIComponent(escape(s)); } catch (e) { return s; }
                        };
                    };
                }
                utilM.getSystemErrorName = utilM.getSystemErrorName || function (err) {
                    var map = { 2: "ENOENT", 13: "EACCES", 17: "EEXIST", 20: "ENOTDIR", 21: "EISDIR", 22: "EINVAL" };
                    return map[err] || "UNKNOWN";
                };
                utilM.getSystemErrorMap = utilM.getSystemErrorMap || function () { return new Map(); };
                utilM.getSystemErrorMessage = utilM.getSystemErrorMessage || function () { return "unknown"; };
                utilM.debug = utilM.debug || utilM.debuglog;
                utilM.diff = utilM.diff || function () { return undefined; };
                utilM.stripVTControlCharacters = utilM.stripVTControlCharacters || function (s) {
                    return String(s).replace(/\u001b\[[0-9;]*m/g, "");
                };
                utilM.toUSVString = utilM.toUSVString || function (s) { return String(s); };
                utilM.parseEnv = utilM.parseEnv || function (content) {
                    var out = {};
                    String(content || "").split(/\r?\n/).forEach(function (line) {
                        var t = line.trim();
                        if (!t || t.charAt(0) === "#") return;
                        var i = t.indexOf("=");
                        if (i > 0) out[t.slice(0, i).trim()] = t.slice(i + 1).trim();
                    });
                    return out;
                };
                utilM.parseArgs = utilM.parseArgs || function (options, args) {
                    var opts = options || {};
                    var list = args || [];
                    var config = opts.options || {};
                    var values = {};
                    var positionals = [];
                    list.forEach(function (a) {
                        if (typeof a !== "string" || a.charAt(0) !== "-") { positionals.push(a); return; }
                        var name = a.replace(/^-+/, "");
                        var eqIdx = name.indexOf("=");
                        var key = eqIdx >= 0 ? name.slice(0, eqIdx) : name;
                        var inline = eqIdx >= 0 ? name.slice(eqIdx + 1) : undefined;
                        var matched = null;
                        Object.keys(config).forEach(function (k) {
                            if (matched) return;
                            var c = config[k];
                            if (k === key || (c.short && c.short === key)) matched = { name: k, cfg: c };
                        });
                        if (!matched) { values[key] = inline === undefined ? true : inline; return; }
                        if (matched.cfg.type === "boolean") values[matched.name] = true;
                        else values[matched.name] = inline === undefined ? true : inline;
                    });
                    return { values: values, positionals: positionals };
                };
            }
        } catch (eUtil2) {}

        // ---- events 静态成员 ----
        try {
            var EEM = baseRequire ? baseRequire("events") : null;
            if (EEM) {
                if (typeof Symbol !== "undefined") {
                    EEM.errorMonitor = Symbol.for("nodejs.rejection.monitor");
                    EEM.captureRejectionSymbol = Symbol.for("nodejs.rejection");
                } else {
                    EEM.errorMonitor = "__errorMonitor";
                    EEM.captureRejectionSymbol = "__rejection";
                }
                EEM.captureRejections = false;
                EEM.usingDomains = false;
                EEM.init = EEM.init || function () {};
                EEM.addAbortListener = EEM.addAbortListener || function (signal, listener) {
                    if (signal && typeof signal.addEventListener === "function") signal.addEventListener("abort", listener);
                    return { dispose: function () {}, removeEventListener: function () {} };
                };
            }
        } catch (eEE2) {}

        // ---- fs.promises 全集：按同步实现批量包装，避免逐个手写 ----
        (function () {
            var promises = realFs.promises || {};
            function wrapSync(name) {
                return function () {
                    var args = Array.prototype.slice.call(arguments);
                    return new Promise(function (resolve, reject) {
                        try { resolve(realFs[name].apply(realFs, args)); } catch (e) { reject(e); }
                    });
                };
            }
            ["access", "chmod", "chown", "lchmod", "lchown", "appendFile", "copyFile", "lstat",
             "lutimes", "link", "mkdtemp", "opendir", "readdir", "readlink", "realpath", "rename",
             "rm", "rmdir", "stat", "symlink", "truncate", "unlink", "utimes", "writeFile"
            ].forEach(function (name) {
                if (typeof realFs[name] === "function" && typeof promises[name] !== "function") {
                    promises[name] = wrapSync(name);
                }
            });
            if (typeof promises.open !== "function") {
                promises.open = function (p, flags) {
                    return new Promise(function (resolve, reject) {
                        var writing = flags && String(flags).indexOf("w") >= 0;
                        if (!writing && !realFs.existsSync(p)) {
                            reject(nodeErr("ENOENT", "ENOENT: no such file or directory, open '" + p + "'"));
                            return;
                        }
                        resolve({
                            fd: 0,
                            readFile: function (options) {
                                return new Promise(function (res, rej) {
                                    try { res(realFs.readFileSync(p, options)); } catch (e) { rej(e); }
                                });
                            },
                            writeFile: function (data) {
                                return new Promise(function (res, rej) {
                                    try { realFs.writeFileSync(p, data); res(); } catch (e) { rej(e); }
                                });
                            },
                            close: function () { return Promise.resolve(); },
                            stat: function () { return Promise.resolve(realFs.statSync(p)); }
                        });
                    });
                };
            }
            if (typeof promises.watch !== "function") {
                promises.watch = function () {
                    var watcher = {
                        on: function () { return watcher; },
                        close: function () { return Promise.resolve(); },
                        return: function () { return Promise.resolve({ done: true }); }
                    };
                    if (typeof Symbol !== "undefined" && Symbol.asyncIterator) {
                        watcher[Symbol.asyncIterator] = function () {
                            return { next: function () { return Promise.resolve({ done: true }); } };
                        };
                    }
                    return watcher;
                };
            }
            if (typeof promises.constants === "undefined") promises.constants = constantsModule;
            realFs.promises = promises;
        })();

        // ---- url / os / path 长尾 ----
        try {
            var urlM = baseRequire ? baseRequire("url") : null;
            if (urlM) {
                urlM.Url = urlM.Url || function (options) {
                    var self = this;
                    Object.keys(options || {}).forEach(function (k) { self[k] = options[k]; });
                    self.toString = function () { return urlM.format(self); };
                };
                urlM.resolveObject = urlM.resolveObject || function (from, to) {
                    try {
                        var base = from && from.href ? from.href : from;
                        var u = new URL(String(to), String(base));
                        var out = urlM.parse(u.href);
                        out.hash = u.hash;
                        return out;
                    } catch (e) {
                        return urlM.parse ? urlM.parse(String(to)) : { href: String(to) };
                    }
                };
                urlM.fileURLToPathBuffer = urlM.fileURLToPathBuffer || function (u) {
                    var p = urlM.fileURLToPath(u);
                    var bin = (window.Buffer.from(p)._bin) || "";
                    var view = new Uint8Array(bin.length);
                    for (var i = 0; i < bin.length; i++) view[i] = bin.charCodeAt(i) & 0xff;
                    return view;
                };
            }
        } catch (eUrl2) {}
        try {
            var osM2 = baseRequire ? baseRequire("os") : null;
            if (osM2) {
                osM2.getPriority = function () { return 0; };
                osM2.setPriority = function () {};
                if (!osM2.constants) osM2.constants = {};
                if (!osM2.constants.signals) osM2.constants.signals = {};
                if (!osM2.constants.errno) osM2.constants.errno = {};
                // priority 是插件真正读的键（调整线程优先级时），必须存在
                // 注意不能用 `!constants.priority` 判断：前面已放入空对象 {}（truthy），
                // 必须检查真正要用的键是否存在
                if (!osM2.constants.priority || osM2.constants.priority.PRIORITY_NORMAL === undefined) {
                    osM2.constants.priority = {
                        PRIORITY_LOW: 19, PRIORITY_BELOW_NORMAL: 10, PRIORITY_NORMAL: 0,
                        PRIORITY_ABOVE_NORMAL: -7, PRIORITY_HIGH: -14, PRIORITY_HIGHEST: -20
                    };
                }
            }
        } catch (eOs2) {}
        try {
            var pathM2 = baseRequire ? baseRequire("path") : null;
            if (pathM2) {
                pathM2.toNamespacedPath = function (p) { return p; };
                pathM2._makeLong = function (p) { return p; };
                pathM2.matchesGlob = pathM2.matchesGlob || function (target, pattern) {
                    // 最小 glob（* 与 ?），够插件做文件名筛选
                    var re = String(pattern)
                        .replace(/[.+^${}()|[\]\\]/g, "\\$&")
                        .replace(/\*/g, "[^/]*")
                        .replace(/\?/g, "[^/]");
                    return new RegExp("^" + re + "$").test(String(target));
                };
            }
        } catch (ePath2) {}

        // ---- crypto：类名（instanceof 检查）与 HKDF / scrypt 处理 ----
        (function () {
            function makeCtor(name) {
                var C = function () {};
                C.prototype = {};
                C.displayName = name;
                return C;
            }
            var ctorCache = {};
            function ctorOf(name) {
                if (!ctorCache[name]) ctorCache[name] = makeCtor(name);
                return ctorCache[name];
            }
            // 让本段前面 createHash/createCipheriv 的返回值 instanceof 成立：
            // 插件会写 `if (h instanceof crypto.Hash)`
            var origHash = cryptoModule.createHash;
            cryptoModule.createHash = function (algo) {
                var obj = origHash.call(cryptoModule, algo);
                try { if (Object.setPrototypeOf) Object.setPrototypeOf(obj, ctorOf("Hash").prototype); } catch (e) {}
                return obj;
            };
            var origHmac = cryptoModule.createHmac;
            cryptoModule.createHmac = function (algo, key) {
                var obj = origHmac.call(cryptoModule, algo, key);
                try { if (Object.setPrototypeOf) Object.setPrototypeOf(obj, ctorOf("Hmac").prototype); } catch (e) {}
                return obj;
            };
            var origCiph = cryptoModule.createCipheriv;
            cryptoModule.createCipheriv = function (algo, key, iv, opts) {
                var obj = origCiph.call(cryptoModule, algo, key, iv, opts);
                try { if (Object.setPrototypeOf) Object.setPrototypeOf(obj, ctorOf("Cipheriv").prototype); } catch (e) {}
                return obj;
            };
            var origDeciph = cryptoModule.createDecipheriv;
            cryptoModule.createDecipheriv = function (algo, key, iv, opts) {
                var obj = origDeciph.call(cryptoModule, algo, key, iv, opts);
                try { if (Object.setPrototypeOf) Object.setPrototypeOf(obj, ctorOf("Decipheriv").prototype); } catch (e) {}
                return obj;
            };
            cryptoModule.Hash = ctorOf("Hash");
            cryptoModule.Hmac = ctorOf("Hmac");
            cryptoModule.Cipheriv = ctorOf("Cipheriv");
            cryptoModule.Decipheriv = ctorOf("Decipheriv");
            cryptoModule.Sign = ctorOf("Sign");
            cryptoModule.Verify = ctorOf("Verify");
            cryptoModule.DiffieHellman = ctorOf("DiffieHellman");
            cryptoModule.KeyObject = ctorOf("KeyObject");

            // HKDF 是纯 HMAC 构造，可完整实现并与 Node 逐字节一致
            // HKDF（RFC 5869）：extract 与 expand 都基于 HMAC。
            // salt 缺省时用「与摘要等长的全零」——用 createHash 生成是错的（那不是 HMAC 输出）。
            cryptoModule.hkdfSync = function (digest, ikm, salt, info, keylen) {
                var macName = String(digest || "sha256").toLowerCase().replace(/-/g, "");
                function hmac(key, data) {
                    return cryptoModule.createHmac(macName, key).update(data).digest();
                }
                var hashLen = macName === "sha512" ? 64 : (macName === "sha384" ? 48 : 32);
                var zeroSalt = window.Buffer.alloc(hashLen);
                var prk = hmac(salt === undefined || salt === null ? zeroSalt : salt, ikm);
                var outBin = "";
                var prev = window.Buffer.from("");
                var counter = 1;
                while (outBin.length < keylen) {
                    var blocks = [prev];
                    if (info) blocks.push(info);
                    blocks.push(window.Buffer.from(String.fromCharCode(counter)));
                    prev = hmac(prk, window.Buffer.concat(blocks));
                    outBin += (prev._bin || "");
                    counter++;
                }
                // outBin 是二进制串（每字符一字节），必须按 latin1 还原字节，
                // 用默认 utf8 会把 >=0x80 的字节膨胀成两字节
                return window.Buffer.from(outBin.slice(0, keylen), "latin1");
            };
            cryptoModule.hkdf = function (digest, ikm, salt, info, keylen, callback) {
                setTimeout(function () {
                    try { callback(null, cryptoModule.hkdfSync(digest, ikm, salt, info, keylen)); }
                    catch (e) { callback(e); }
                }, 0);
            };
            // scrypt 需要原生实现（Android 未提供）：明确抛错，而不是给出错误的派生密钥
            cryptoModule.scryptSync = function () {
                throw ioError("ERR_NOT_IMPLEMENTED", "crypto.scryptSync is unavailable on this runtime");
            };
            cryptoModule.scrypt = function (password, salt, keylen, options, callback) {
                var cb = typeof options === "function" ? options : callback;
                setTimeout(function () { cb(ioError("ERR_NOT_IMPLEMENTED", "crypto.scrypt is unavailable on this runtime")); }, 0);
            };
            cryptoModule.getRandomValues = function (typedArray) {
                var bytes = toBuffer(env ? env.randomBytes(typedArray.length) : "");
                var bin = bytes._bin || "";
                for (var i = 0; i < typedArray.length; i++) typedArray[i] = bin.charCodeAt(i) & 0xff;
                return typedArray;
            };
            cryptoModule.randomFillSync = function (buffer, offset, size) {
                if (!buffer || typeof buffer.length !== "number") return buffer;
                var bytes = toBuffer(env ? env.randomBytes(size === undefined ? buffer.length : size) : "");
                var bin = bytes._bin || "";
                var start = offset || 0;
                var end = size === undefined ? buffer.length : Math.min(buffer.length, start + size);
                for (var i = start; i < end; i++) buffer[i] = bin.charCodeAt(i - start) & 0xff;
                return buffer;
            };
            cryptoModule.randomFill = function (buffer, offset, size, callback) {
                var filled = cryptoModule.randomFillSync(buffer, offset, size);
                if (typeof callback === "function") { setTimeout(function () { callback(null, filled); }, 0); return; }
                return filled;
            };
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
