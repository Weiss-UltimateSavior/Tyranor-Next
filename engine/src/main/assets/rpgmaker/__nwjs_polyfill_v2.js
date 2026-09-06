// v2-only compat - ported from JoiPlay globals.js/webgl.js/overrides.json
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

    // ---- JoiPlay joiSaveAs + screen.orientation helpers (backend-agnostic fallbacks) ----
    try {
        if (typeof window.joiSaveAs !== "function") {
            window.joiSaveAs = function (blob, type, path) {
                try {
                    var reader = new FileReader();
                    reader.readAsDataURL(blob);
                    reader.onloadend = function () {
                        var base64data = reader.result;
                        try { if (typeof window.NWJSApi !== "undefined" && NWJSApi.saveBlob) { NWJSApi.saveBlob(base64data, path); return; } } catch (e) {}
                        try { if (typeof window.saveDataManager !== "undefined" && typeof window.saveDataManager.Save === "function") { window.saveDataManager.Save(path, base64data); } } catch (e2) {}
                    };
                } catch (e3) {}
            };
        }
        if (typeof window.screen === "undefined") window.screen = {};
        if (typeof window.screen.orientation === "undefined") window.screen.orientation = {};
        if (typeof window.screen.orientation.lock !== "function") { window.screen.orientation.lock = function () {}; }
        if (typeof window.screen.orientation.unlock !== "function") { window.screen.orientation.unlock = function () {}; }
    } catch (e) {}

    // ---- WebGL1-on-WebGL2 shim (verbatim port of JoiPlay webgl.js, no Java bridge dependency) ----
    // 修复：Tyranor 无 NWJSApi，JoiPlay 原版靠 isTranspileEnabled() 门控；
    // 无门控裸奔会导致 isTranspiling 全局泄漏 + getQueryParameter 未定义 +
    // bindTexture 强制改参数，所有 v2 游戏开局卡死。此处加等效门控：
    // 仅当 WebGL1 上下文不存在且 NWJSApi 提供 transpile 能力时才劫持，
    // 否则整段跳过保持原生 WebGL 行为。
    (function () {
        var hasWebGL2Canvas;
        try { hasWebGL2Canvas = !!(document.createElement("canvas").getContext("webgl2")); } catch (e) { hasWebGL2Canvas = false; }
        if (!hasWebGL2Canvas) return;
        var hasJoiTranspile = (typeof window.NWJSApi !== "undefined" &&
            typeof NWJSApi.isTranspileEnabled === "function" && NWJSApi.isTranspileEnabled()) ||
            (typeof window.NWJSApi !== "undefined" && typeof NWJSApi.transpileToGLSL3 === "function");
        // Tyranor 无 NWJSApi：不劫持，保持原生 WebGL2 路径（Pixi 4.0.3 直接可用）
        if (!hasJoiTranspile) return;

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
        
        const joiCanvasGetContext = HTMLCanvasElement.prototype.getContext;
        HTMLCanvasElement.prototype.getContext = function(contextType, contextAttributes = null){
            if(((contextType === "webgl") || (contextType === "experimental-webgl")) && window.hasWebGL2){
                console.log("WebGL1 context is requested. Returning WebGL2 context instead.")
                window.isTranspiling = true;
                return joiCanvasGetContext.apply(this,["webgl2", contextAttributes]);
            }
        
            window.isTranspiling = false;
        
            return joiCanvasGetContext.apply(this,[contextType, contextAttributes]);
        }
        
        const joiCreateShader = WebGL2RenderingContext.prototype.createShader;
        WebGL2RenderingContext.prototype.createShader = function(stype){
            if(!window.isTranspiling) return joiCreateShader.apply(this,[stype]);
        
            var shader = joiCreateShader.apply(this,[stype]);
            shader.type = stype;
            return shader;
        }
        
        const joiShaderSource = WebGL2RenderingContext.prototype.shaderSource;
        WebGL2RenderingContext.prototype.shaderSource = function(shader, source){
            if(!window.isTranspiling) return joiShaderSource.apply(this, [shader, source]);
        
                // JoiPlay shader transpile path (preserved verbatim when NWJSApi is present)
                try {
                    if (typeof window.NWJSApi !== "undefined" && typeof NWJSApi.transpileToGLSL3 === "function") {
                        return joiShaderSource.apply(this, [shader, NWJSApi.transpileToGLSL3(source, shader.type == WebGL2RenderingContext.FRAGMENT_SHADER)]);
                    }
                } catch (e2) {}
                return joiShaderSource.apply(this, [shader, source]);
        }
        
        const joiGetExtension = WebGL2RenderingContext.prototype.getExtension;
        WebGL2RenderingContext.prototype.getExtension = function(name){
            if(!window.isTranspiling) return joiGetExtension.apply(this, [name]);
        
            switch(name){
                case "OES_vertex_array_object":
                case "ANGLE_instanced_arrays":
                case "WEBGL_draw_buffers":
                    return new WebGLDummyExtension(this);
                    break;
                case "WEBGL_color_buffer_float":
                case "OES_texture_half_float":
                    return joiGetExtension.apply(this, ["EXT_color_buffer_float"]);
                    break;
                case "EXT_disjoint_timer_query":
                    var ext = joiGetExtension.apply(this, ["EXT_disjoint_timer_query_webgl2"]);
                    var cpext = {
                        ...ext,
                        getQueryObject: function(...args){
                            // 原 JoiPlay 调用未定义的 getQueryParameter；改为经 ext.getQueryParameter 转发
                            try { if (ext && typeof ext.getQueryParameter === "function") return ext.getQueryParameter.apply(ext, args); } catch (e4) {}
                            return null;
                        }
                    }
                    return cpext;
                    break;
                default:
                    return joiGetExtension.apply(this, [name]);
                    break;
            }
        }
        
        const joiBindTexture1 = WebGLRenderingContext.prototype.bindTexture;
        WebGLRenderingContext.prototype.bindTexture = function(target, texture){
            joiBindTexture1.apply(this, [target, texture]);
        
            this.texParameteri(target, this.TEXTURE_MAG_FILTER, this.NEAREST);
            this.texParameteri(target, this.TEXTURE_MIN_FILTER, this.NEAREST);
            this.texParameteri(target, this.TEXTURE_WRAP_S, this.CLAMP_TO_EDGE);
            this.texParameteri(target, this.TEXTURE_WRAP_T, this.CLAMP_TO_EDGE);
        }
    })();

    // ---- overrides table (verbatim JoiPlay overrides.json) ----
    try { window.__tyranorJoiOverrides = [
        {
                "string": "$plugins[0].name!=='FOSSIL'",
                "override": "false"
        },
        {
                "string": "Yanfly.Util.displayError(",
                "override": "console.log("
        },
        {
                "string": "fmt.match(/<(?:WordWrap)>/i)",
                "override": "false"
        },
        {
                "string": "this._createFPSMeter();",
                "override": "try{this._createFPSMeter();}catch(e){}"
        },
        {
                "string": "if (ConfigManager._lastSaveIndex[1] != null) {",
                "override": "if (ConfigManager._lastSaveIndex != null || ConfigManager._lastSaveIndex[1] != null) {"
        }
]; } catch (e3) {}


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

    function rehydrateTree(value, depth) {
        if (!value || typeof value !== "object") return value;
        if (depth > 60) return value; // JsonEx.maxDepth=100，防御性限制
        if (Array.isArray(value)) {
            for (var i = 0; i < value.length; i++) {
                if (value[i] && typeof value[i] === "object") value[i] = rehydrateTree(value[i], depth + 1);
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
            } else if (!value.__tyranorAtWarned) {
                value.__tyranorAtWarned = true;
                try { console.warn("[v2-diag] JsonEx rehydrate: no ctor for @" + at); } catch (e2) {}
            }
        }
        for (var k in value) {
            if (value.hasOwnProperty(k)) {
                var child = value[k];
                if (child && typeof child === "object") value[k] = rehydrateTree(child, depth + 1);
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
                try { console.warn("[v2-diag] JsonEx16 @r dangling: " + node["@r"]); } catch (eR) {}
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
                try { console.log("[v2-diag] JsonEx engineHandles16=" + engineHandles16); } catch (eLog) {}
                var origParse = window.JsonEx.parse;
                window.JsonEx.parse = function (json) {
                    // 存档原文诊断：_vehicles 节点原文（确认存档内容形态）
                    try {
                        if (typeof json === "string") {
                            var vi = json.indexOf("_vehicles");
                            if (vi >= 0) {
                                console.log("[v2-diag] save json _vehicles: " + json.substr(vi, 260));
                            }
                        }
                    } catch (eDiag) {}
                    var result;
                    if (engineHandles16) {
                        // 1.6+/MZ：原生 parse（JSON.parse + _decode 全流程由引擎完成）
                        result = origParse.call(this, json);
                    } else {
                        // 1.3.x：JSON.parse → 1.6 标记转换 → 引擎 _decode（单参签名）
                        var tree = JSON.parse(json);
                        try { tree = convertJsonEx16To13(tree, {}, 0); } catch (eCv) {}
                        result = window.JsonEx._decode(tree);
                    }
                    try { rehydrateTree(result, 0); } catch (eRe) {}
                    return result;
                };
                window.JsonEx.parse.__tyranorV2Patched = true;
                clearInterval(parseTimer);
            } catch (e) {}
        }, 200);
        setTimeout(function () { try { clearInterval(parseTimer); } catch (e) {} }, 10000);
    })();

    // ---- repairGameObjects：毒存档数据丢失的字段/槽位兜底 ----
    // 仅处理 rehydrate 无法恢复的问题（null 槽位、缺失字段）；
    // 原型恢复已全部由 rehydrateTree 在 JsonEx.parse 出口完成，此处不再重复。
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
            // 队伍成员字段兜底（Sprite_Character 渲染队友时读 _opacity）
            if (typeof $gameParty !== "undefined" && $gameParty && typeof $gameParty.members === "function") {
                try {
                    var partyMembers = $gameParty.members();
                    if (partyMembers && typeof partyMembers.forEach === "function") {
                        for (var pm = 0; pm < partyMembers.length; pm++) {
                            ensureCharacterDefaults(partyMembers[pm]);
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
                        $gameMap._vehicles = [];
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
                                    try { console.warn("[v2-diag] new Game_Vehicle('" + vhTypes[vti] + "') failed: " + (eVhNew && eVhNew.message)); } catch (eVhLog2) {}
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
                    $gameMap._vehicles.length = 3;
                } catch (eAir3) {}
                try { console.log("[v2-diag] vehicles repaired: " + vehStates.join(",") +
                    " airship=" + (typeof $gameMap._vehicles[2]) + "/shadowX=" +
                    (typeof ($gameMap._vehicles[2] && $gameMap._vehicles[2].shadowX))); } catch (eVhLog) {}
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
            // $gameParty._actors / $gameActors._data 缺失兜底（毒存档）
            if (typeof $gameParty !== "undefined" && $gameParty && (!$gameParty._actors || typeof $gameParty._actors.filter !== "function")) {
                try { $gameParty._actors = []; } catch (ePa) {}
            }
            if (typeof $gameActors !== "undefined" && $gameActors && (!$gameActors._data || typeof $gameActors._data.filter !== "function")) {
                try { $gameActors._data = []; } catch (eAc) {}
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

    // ---- loadGame 诊断 hook（验证期保留，问题闭环后可移除）----
    (function () {
        var loadDiagTimer = setInterval(function () {
            try {
                if (typeof window.DataManager === "undefined" || typeof window.DataManager.loadGame !== "function") return;
                if (DataManager.loadGame.__tyranorV2Diag) { clearInterval(loadDiagTimer); return; }
                var origLoadGame = DataManager.loadGame;
                DataManager.loadGame = function (savefileId) {
                    console.log("[v2-diag] loadGame enter savefileId=" + savefileId);
                    try {
                        var ret = origLoadGame.call(this, savefileId);
                        repairGameObjects();
                        console.log("[v2-diag] loadGame exit ret=" + ret +
                            " player.isTransferring=" + (typeof $gamePlayer !== "undefined" && $gamePlayer && typeof $gamePlayer.isTransferring === "function" ? "fn" : "MISSING") +
                            " map.mapId=" + (typeof $gameMap !== "undefined" && $gameMap && typeof $gameMap.mapId === "function" ? $gameMap.mapId() : "MISSING") +
                            " vehicles=" + (typeof $gameMap !== "undefined" && $gameMap && $gameMap._vehicles && typeof $gameMap._vehicles.forEach === "function" ? "ok" : "BROKEN") +
                            " followers=" + (typeof $gamePlayer !== "undefined" && $gamePlayer && $gamePlayer._followers && typeof $gamePlayer._followers.reverseEach === "function" ? "ok" : "BROKEN"));
                        return ret;
                    } catch (e) {
                        console.error("[v2-diag] loadGame threw", e && e.stack ? e.stack : e);
                        throw e;
                    }
                };
                DataManager.loadGame.__tyranorV2Diag = true;
                clearInterval(loadDiagTimer);
            } catch (e) {}
        }, 200);
        setTimeout(function () { try { clearInterval(loadDiagTimer); } catch (e) {} }, 10000);
    })();

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

    console.log("[nw-polyfill-v2] JoiPlay compat installed (webgl shims + overrides + joiSaveAs + json rehydrate)");
})();
