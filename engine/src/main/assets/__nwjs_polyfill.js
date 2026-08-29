// NW.js / Node 兼容层 — 让 MV/MZ 在 WebView 下完整模拟 nw.js 环境
// v0/v1 均在 TyranoActivity 的 hook 之前注入（earlyHook，插入到 </head> 前）
// 目标：彻底消除 ReferenceError: require is not defined / Buffer / process / nw.gui 等，
//       覆盖官方引擎与任意第三方插件可能用到的 Node API。
(function () {
    "use strict";
    if (window.__tyranorNwPolyfill) return;
    window.__tyranorNwPolyfill = true;

    // ──────────────────────────────────────────────
    // 0) 全局别名
    // ──────────────────────────────────────────────
    try { window.global = window; window.global.global = window; } catch (e) {}
    try { if (typeof globalThis !== "undefined") { globalThis.global = window; } } catch (e) {}

    // ──────────────────────────────────────────────
    // 1) Utils.isNwjs 钉死为 false（轮询覆盖，处理 rpg_core.js 后定义的情况）
    // ──────────────────────────────────────────────
    function pinIsNwjs() {
        try {
            if (window.Utils && typeof window.Utils.isNwjs === "function" && window.Utils.isNwjs.toString().indexOf("return false") === -1) {
                window.Utils.isNwjs = function () { return false; };
            }
            // 额外的 isLocalMode 门控也钉死
            if (window.StorageManager && typeof window.StorageManager.isLocalMode === "function") {
                // 不直接覆盖，StorageManager.isLocalMode 内部调用 isNwjs，已间接为 false
            }
        } catch (e) {}
    }
    pinIsNwjs();
    var pinTimer = setInterval(pinIsNwjs, 100);
    setTimeout(function () { clearInterval(pinTimer); pinIsNwjs(); }, 5000);
    window.addEventListener("load", pinIsNwjs);
    // 拦截后续对 Utils 的定义（rpg_core.js 的 function Utils(){} 会触发）
    try {
        var _Utils = window.Utils;
        Object.defineProperty(window, "Utils", {
            configurable: true,
            get: function () { return _Utils; },
            set: function (v) {
                _Utils = v;
                if (v && typeof v.isNwjs === "function") {
                    try { v.isNwjs = function () { return false; }; } catch (e2) {}
                }
            }
        });
    } catch (e) {}

    // ──────────────────────────────────────────────
    // 2) process 存根（MV 核心与插件常用）
    // ──────────────────────────────────────────────
    if (typeof window.process === "undefined") window.process = {};
    var proc = window.process;
    try {
        if (!proc.mainModule) proc.mainModule = { filename: "/game/www/index.html", loaded: true };
        if (!proc.platform) proc.platform = "linux";
        if (!proc.arch) proc.arch = "x64";
        if (!proc.versions) proc.versions = {};
        if (!proc.versions.nw) proc.versions.nw = "0.0.0";
        if (!proc.versions.node) proc.versions.node = "0.0.0";
        if (!proc.env) proc.env = {};
        if (!proc.argv) proc.argv = [];
        if (!proc.execPath) proc.execPath = "/game/nw";
        if (!proc.title) proc.title = "browser";
        proc.browser = true;
        if (typeof proc.cwd !== "function") proc.cwd = function () { return "/"; };
        if (typeof proc.chdir !== "function") proc.chdir = function () { throw new Error("process.chdir is not supported"); };
        if (typeof proc.nextTick !== "function") proc.nextTick = function (fn) { setTimeout(fn, 0); };
        var noop = function () {};
        ["on", "addListener", "once", "off", "removeListener", "removeAllListeners", "emit", "prependListener"].forEach(function (k) {
            if (typeof proc[k] !== "function") proc[k] = noop;
        });
        if (typeof proc.binding !== "function") proc.binding = function () { throw new Error("process.binding is not supported"); };
        if (typeof proc.umask !== "function") proc.umask = function () { return 0; };
        if (typeof proc.hrtime !== "function") proc.hrtime = function () { return [0, 0]; };
        if (typeof proc.uptime !== "function") proc.uptime = function () { return 0; };
    } catch (e) {}
    try { if (typeof globalThis !== "undefined" && !globalThis.process) globalThis.process = proc; } catch (e) {}

    // __dirname / __filename
    try { if (typeof window.__dirname === "undefined") window.__dirname = "/"; } catch (e) {}
    try { if (typeof window.__filename === "undefined") window.__filename = "/game/www/index.html"; } catch (e) {}

    // ──────────────────────────────────────────────
    // 3) Buffer 完整兼容（MV 水印脚本与部分插件用 Buffer.from）
    // ──────────────────────────────────────────────
    if (typeof window.Buffer === "undefined") {
        (function () {
            function _toBinary(str) { try { return atob(str); } catch (e) { return str; } }
            function _fromBinary(bin, enc) {
                if (enc === "utf8" || enc === "utf-8") {
                    try { return decodeURIComponent(escape(bin)); } catch (e2) { return bin; }
                }
                if (enc === "hex") {
                    var h = ""; for (var i = 0; i < bin.length; i++) { var c = bin.charCodeAt(i).toString(16); h += c.length === 1 ? "0" + c : c; } return h;
                }
                return bin;
            }
            var B = {
                from: function (input, encoding) {
                    // 支持 string + encoding, 或 Uint8Array
                    if (typeof input === "string") {
                        if (encoding === "base64") {
                            var bin = _toBinary(input);
                            return {
                                toString: function (enc) { return _fromBinary(bin, enc); },
                                length: bin.length,
                                _bin: bin
                            };
                        }
                        if (encoding === "hex") {
                            var bin2 = ""; for (var i = 0; i < input.length; i += 2) bin2 += String.fromCharCode(parseInt(input.substr(i, 2), 16)); 
                            return { toString: function (enc) { return _fromBinary(bin2, enc); }, length: bin2.length, _bin: bin2 };
                        }
                        return {
                            toString: function (enc) {
                                if (!enc || enc === "utf8" || enc === "utf-8") return input;
                                if (enc === "base64") try { return btoa(unescape(encodeURIComponent(input))); } catch (e) { return ""; }
                                return input;
                            },
                            length: input.length,
                            _bin: input
                        };
                    }
                    if (input && typeof input.length === "number") {
                        var s = ""; for (var i = 0; i < input.length; i++) s += String.fromCharCode(input[i]);
                        return { toString: function (enc) { return _fromBinary(s, enc); }, length: s.length, _bin: s };
                    }
                    return { toString: function () { return String(input); }, length: 0, _bin: "" };
                },
                alloc: function (size, fill, enc) {
                    var s = ""; var ch = fill ? String(fill)[0] : "\0";
                    for (var i = 0; i < size; i++) s += ch;
                    return B.from(s, enc);
                },
                allocUnsafe: function (size) { return B.alloc(size); },
                allocUnsafeSlow: function (size) { return B.alloc(size); },
                isBuffer: function () { return false; },
                isEncoding: function (e) { return ["utf8","utf-8","base64","hex","ascii","binary","latin1"].indexOf(e) >= 0; },
                byteLength: function (str, enc) {
                    if (enc === "base64") try { return atob(str).length; } catch (e) { return str.length; }
                    return String(str).length;
                },
                concat: function (list, totalLen) {
                    var s = ""; list.forEach(function (b) { s += (b && b._bin) ? b._bin : (b ? String(b) : ""); });
                    return B.from(s);
                }
            };
            window.Buffer = B;
            try { if (typeof globalThis !== "undefined") globalThis.Buffer = B; } catch (e) {}
        })();
    }

    // ──────────────────────────────────────────────
    // 4) 模块存根
    // ──────────────────────────────────────────────
    var fsStub = {
        existsSync: function () { return false; },
        exists: function (p, cb) { if (typeof cb === "function") setTimeout(function(){ cb(false); }, 0); },
        mkdirSync: function () {},
        mkdir: function (p, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function(){ cb(null); }, 0); },
        writeFileSync: function () {},
        writeFile: function (p, d, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function(){ cb(null); }, 0); },
        appendFileSync: function () {},
        appendFile: function (p, d, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function(){ cb(null); }, 0); },
        readFileSync: function () { return ""; },
        readFile: function (p, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function(){ cb(null, ""); }, 0); return ""; },
        unlinkSync: function () {},
        unlink: function (p, cb) { if (typeof cb === "function") setTimeout(function(){ cb(null); }, 0); },
        openSync: function () { return 0; },
        open: function (p, f, m, cb) { if (typeof m === "function") cb = m; if (typeof cb === "function") setTimeout(function(){ cb(null, 0); }, 0); },
        closeSync: function () {},
        close: function (fd, cb) { if (typeof cb === "function") setTimeout(function(){ cb(null); }, 0); },
        readSync: function () { return 0; },
        writeSync: function () { return 0; },
        readdirSync: function () { return []; },
        readdir: function (p, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function(){ cb(null, []); }, 0); },
        statSync: function () { return { isFile: function(){return false;}, isDirectory:function(){return false;}, isSymbolicLink:function(){return false;}, size:0, mtime:new Date(0)}; },
        lstatSync: function () { return { isFile: function(){return false;}, isDirectory:function(){return false;}, isSymbolicLink:function(){return false;}, size:0, mtime:new Date(0)}; },
        fstatSync: function () { return { isFile: function(){return false;}, isDirectory:function(){return false;}, size:0 }; },
        createReadStream: function () { return { on:function(){return this;}, once:function(){return this;}, pipe:function(){return this;}, read:function(){}, close:function(){} }; },
        createWriteStream: function () { return { on:function(){return this;}, once:function(){return this;}, write:function(){}, end:function(){}, close:function(){} }; },
        watch: function(){ return { close:function(){}, on:function(){return this;}}; },
        watchFile: function(){}, unwatchFile: function(){},
        renameSync: function(){}, rename: function(p,q,cb){ if(typeof cb==="function") setTimeout(function(){cb(null);},0); },
        copyFileSync: function(){}, copyFile: function(p,q,cb){ if(typeof cb==="function") setTimeout(function(){cb(null);},0); },
        chmodSync: function(){}, chownSync: function(){},
        promises: {
            readFile: function(){ return Promise.resolve(""); },
            writeFile: function(){ return Promise.resolve(); },
            mkdir: function(){ return Promise.resolve(); },
            readdir: function(){ return Promise.resolve([]); },
            stat: function(){ return Promise.resolve({ isFile:function(){return false;}, isDirectory:function(){return false;}}); },
            unlink: function(){ return Promise.resolve(); }
        }
    };

    var pathStub = {
        dirname: function (p) { if(!p) return "."; var i=Math.max(p.lastIndexOf("/"),p.lastIndexOf("\\")); return i>=0 ? (p.slice(0,i)||"/") : "."; },
        basename: function (p, ext) { if(!p) return ""; var s=p.split("/").pop().split("\\").pop(); if(ext&&s.endsWith(ext)) s=s.slice(0,-ext.length); return s; },
        extname: function (p) { var b=p.split("/").pop().split("\\").pop(); var d=b.lastIndexOf("."); return d>=0?b.slice(d):""; },
        join: function () { var a=Array.prototype.slice.call(arguments).filter(Boolean); return a.join("/").replace(/\/+/g,"/"); },
        resolve: function () { var a=Array.prototype.slice.call(arguments).filter(Boolean); var s=a.join("/").replace(/\/+/g,"/"); if(s[0]!=="/") s="/"+s; return s; },
        normalize: function (p) { return p ? p.replace(/\/+/g,"/").replace(/\/\.\//g,"/") : "."; },
        relative: function (from,to) { return to; },
        isAbsolute: function (p) { return p && (p[0]==="/" || /^[a-zA-Z]:[\\/]/.test(p)); },
        parse: function (p) { var b=pathStub.basename(p); var e=pathStub.extname(p); var d=pathStub.dirname(p); return {root:"/",dir:d,base:b,ext:e,name:b.slice(0,b.length-e.length)}; },
        format: function (o) { return (o.dir?o.dir+"/":"")+(o.name||"")+(o.ext||""); },
        sep: "/", delimiter: ":", posix: null, win32: null
    };
    pathStub.posix = pathStub; pathStub.win32 = pathStub;

    var osStub = {
        platform: function(){ return "linux"; },
        arch: function(){ return "x64"; },
        type: function(){ return "Linux"; },
        release: function(){ return "0.0.0"; },
        homedir: function(){ return "/"; },
        tmpdir: function(){ return "/tmp"; },
        hostname: function(){ return "localhost"; },
        cpus: function(){ return []; },
        totalmem: function(){ return 0; },
        freemem: function(){ return 0; },
        EOL: "\n"
    };

    var utilStub = {
        inherits: function(ctor,superCtor){ ctor.super_=superCtor; ctor.prototype=Object.create(superCtor.prototype,{constructor:{value:ctor}}); },
        format: function(f){ var a=Array.prototype.slice.call(arguments,1); var i=0; return String(f).replace(/%[sdj%]/g,function(x){ if(x==="%%")return "%"; if(i>=a.length) return x; switch(x){case"%s":return String(a[i++]);case"%d":return Number(a[i++]);case"%j":try{return JSON.stringify(a[i++]);}catch(e){return "[Circular]";}default:return x;}}); if(a.length>i) return f+" "+a.slice(i).join(" "); return f; },
        inspect: function(o){ try{return JSON.stringify(o);}catch(e){return String(o);} },
        isArray: Array.isArray,
        isString: function(x){return typeof x==="string";},
        deprecate: function(fn){return fn;}
    };

    var eventsStub = function EventEmitter(){ this._e={}; };
    eventsStub.prototype.on=function(e,fn){(this._e[e]=this._e[e]||[]).push(fn);return this;};
    eventsStub.prototype.once=function(e,fn){var s=this; function w(){s.removeListener(e,w); fn.apply(s,arguments);} w.fn=fn; this.on(e,w); return this;};
    eventsStub.prototype.emit=function(e){var a=Array.prototype.slice.call(arguments,1); var h=this._e[e]; if(h) h.slice().forEach(function(f){f.apply(null,a);}); return true;};
    eventsStub.prototype.removeListener=function(e,fn){var h=this._e[e]; if(h) this._e[e]=h.filter(function(f){return f!==fn&&f.fn!==fn;}); return this;};
    eventsStub.prototype.removeAllListeners=function(e){ if(e) delete this._e[e]; else this._e={}; return this;};

    var childProcessStub = {
        exec: function(cmd,opts,cb){ if(typeof opts==="function") cb=opts; if(typeof cb==="function") setTimeout(function(){cb(null,"","");},0); return { on:function(){return this;}, kill:function(){} }; },
        execSync: function(){ return ""; },
        execFile: function(f,a,o,cb){ if(typeof a==="function") cb=a; else if(typeof o==="function") cb=o; if(typeof cb==="function") setTimeout(function(){cb(null,"","");},0); return {on:function(){return this;}}; },
        spawn: function(){ return { on:function(){return this;}, once:function(){return this;}, stdout:{on:function(){return this;}}, stderr:{on:function(){return this;}}, kill:function(){}, pid:0 }; },
        spawnSync: function(){ return { status:0, stdout:"", stderr:"", pid:0 }; },
        fork: function(){ return childProcessStub.spawn(); }
    };

    var cryptoStub = {
        randomBytes: function(n){ var s=""; for(var i=0;i<n;i++) s+=String.fromCharCode(Math.floor(Math.random()*256)); return window.Buffer ? window.Buffer.from(s,"binary") : s; },
        createHash: function(){ return { update:function(){return this;}, digest:function(){return "";}}; },
        createHmac: function(){ return { update:function(){return this;}, digest:function(){return "";}}; }
    };

    var urlStub = {
        parse: function(u){ try{ var a=document.createElement("a"); a.href=u; return { protocol:a.protocol, host:a.host, hostname:a.hostname, port:a.port, pathname:a.pathname, search:a.search, hash:a.hash, href:a.href }; }catch(e){ return { href:u }; } },
        format: function(o){ return o.href||""; },
        resolve: function(f,t){ try{ return new URL(t,f).href; }catch(e){ return t; } }
    };

    var querystringStub = {
        parse: function(s){ var o={}; if(!s) return o; s.replace(/^\?/,"").split("&").forEach(function(p){ var kv=p.split("="); if(kv[0]) o[decodeURIComponent(kv[0])]=decodeURIComponent(kv[1]||"");}); return o; },
        stringify: function(o){ return Object.keys(o).map(function(k){return encodeURIComponent(k)+"="+encodeURIComponent(o[k]);}).join("&"); },
        escape: encodeURIComponent, unescape: decodeURIComponent
    };

    var nwGuiStub = (function(){
        var winStub = {
            close: function(){}, showDevTools: function(){}, focus: function(){}, blur:function(){},
            moveBy: function(){}, resizeBy: function(){}, moveTo:function(){}, resizeTo:function(){},
            setMinimumSize:function(){}, setMaximumSize:function(){}, setResizable:function(){},
            setAlwaysOnTop:function(){}, enterFullscreen:function(){}, leaveFullscreen:function(){},
            maximize:function(){}, unmaximize:function(){}, minimize:function(){}, restore:function(){},
            show:function(){}, hide:function(){}, reload:function(){ location.reload(); },
            reloadIgnoringCache:function(){ location.reload(); },
            x:0, y:0, width:816, height:624, title:"", menu:null, isFullscreen:false
        };
        function Menu(opt){ this.type=(opt&&opt.type)||"contextmenu"; this.items=[]; }
        Menu.prototype.append=function(i){ this.items.push(i); };
        Menu.prototype.insert=function(i,p){ this.items.splice(p,0,i); };
        Menu.prototype.remove=function(i){ var idx=this.items.indexOf(i); if(idx>=0) this.items.splice(idx,1); };
        Menu.prototype.removeAt=function(i){ this.items.splice(i,1); };
        Menu.prototype.createMacBuiltin=function(){};
        Menu.prototype.popup=function(){};
        function MenuItem(opt){ this.label=(opt&&opt.label)||""; this.type=(opt&&opt.type)||"normal"; this.click=opt&&opt.click; this.enabled=true; this.submenu=opt&&opt.submenu; }
        var clipboardStub = { set:function(){}, get:function(){return "";}, clear:function(){} };
        var shellStub = { openExternal:function(url){ try{ window.open(url,"_blank"); }catch(e){} }, openItem:function(){}, showItemInFolder:function(){} };
        var screenStub = { Init:function(){}, screens:[], chooseDesktopMedia:function(a,cb){ if(typeof cb==="function") cb("");} };
        return {
            Window: { get: function(){ return winStub; }, open: function(){ return winStub; } },
            Menu: Menu, MenuItem: MenuItem,
            Clipboard: { get: function(){ return clipboardStub; } },
            Shell: shellStub, Screen: screenStub,
            App: { argv:[], fullArgv:[], manifest:{}, dataPath:"/tmp", clearCache:function(){}, closeAllWindows:function(){}, quit:function(){}, on:function(){}, removeAllListeners:function(){} }
        };
    })();

    var moduleCache = {};
    function resolveRequire(name) {
        if (name === "fs") return fsStub;
        if (name === "path") return pathStub;
        if (name === "os") return osStub;
        if (name === "util") return utilStub;
        if (name === "events") return eventsStub;
        if (name === "child_process") return childProcessStub;
        if (name === "crypto") return cryptoStub;
        if (name === "url") return urlStub;
        if (name === "querystring") return querystringStub;
        if (name === "nw.gui") return nwGuiStub;
        if (name === "buffer") return { Buffer: window.Buffer };
        // 未知模块返回空对象，避免 ReferenceError
        if (!moduleCache[name]) moduleCache[name] = {};
        return moduleCache[name];
    }

    // 注入全局 require
    if (typeof window.require === "undefined") {
        window.require = function (name) { return resolveRequire(name); };
    } else {
        var _origRequire = window.require;
        window.require = function (name) {
            try { var r = _origRequire(name); if (r) return r; } catch (e) {}
            return resolveRequire(name);
        };
    }
    if (typeof window.require.resolve !== "function") window.require.resolve = function (x) { return x; };
    if (typeof window.require.cache === "undefined") window.require.cache = {};
    // module / exports
    if (typeof window.module === "undefined") window.module = { exports: {} };
    if (typeof window.exports === "undefined") window.exports = window.module.exports;
    // nw 全局
    if (typeof window.nw === "undefined") window.nw = nwGuiStub;
    if (typeof window.gui === "undefined") window.gui = nwGuiStub;
    try { if (typeof globalThis !== "undefined" && !globalThis.require) globalThis.require = window.require; } catch(e){}
    try { if (typeof globalThis !== "undefined" && !globalThis.nw) globalThis.nw = window.nw; } catch(e){}
    try { if (typeof globalThis !== "undefined" && !globalThis.Buffer) globalThis.Buffer = window.Buffer; } catch(e){}

    // ──────────────────────────────────────────────
    // 4b) MV/MZ Window 兼容：MZ 插件在 MV 引擎中会引用 Window_StatusBase 等
    //    黑屏日志: ReferenceError: Window_StatusBase is not defined
    //    第二个错: Graphics._createFPSMeter 相关 style 访问
    // ──────────────────────────────────────────────
    (function ensureWindowCompat(){
        function defineIfMissing(name, fallback){
            if (typeof window[name] !== "undefined") return;
            try { window[name] = fallback; } catch(e){}
            try { if (typeof globalThis !== "undefined" && !globalThis[name]) globalThis[name]=fallback; } catch(e){}
        }
        // MZ 独有而 MV 没有的基类，指向 MV 已有的近似基类
        try {
            if (typeof window.Window_StatusBase === "undefined" && typeof window.Window_Status !== "undefined") {
                window.Window_StatusBase = window.Window_Status;
            }
            // 若 MV 侧两者皆无，则给空构造函数避免 ReferenceError，后续 Scene 加载前再被覆盖也安全
            if (typeof window.Window_StatusBase === "undefined") {
                window.Window_StatusBase = function(){ if (typeof window.Window_Selectable !== "undefined") return window.Window_Selectable.apply(this, arguments); };
                try { window.Window_StatusBase.prototype = Object.create((window.Window_Selectable||function(){}).prototype); } catch(e){}
            }
            // 其它常见 MZ 插件依赖的空壳，缺失时给占位，避免级联报错
            defineIfMissing("Window_SkillStatus", window.Window_StatusBase || window.Window_Selectable || function(){});
            defineIfMissing("Window_EquipStatus", window.Window_StatusBase || window.Window_Selectable || function(){});
            defineIfMissing("Window_ShopStatus", window.Window_StatusBase || window.Window_Selectable || function(){});
        } catch(e){}
        // 由于插件脚本在 rpg_windows.js 之前加载，上面的 fallback 可能仍为空，延时二次兜底
        setTimeout(function(){
            try {
                if (typeof window.Window_StatusBase === "undefined" && typeof window.Window_Status !== "undefined") {
                    window.Window_StatusBase = window.Window_Status;
                }
            } catch(e){}
        }, 800);
    })();

    // ──────────────────────────────────────────────
    // 5) Game_Interpreter.eval 兜底（水印脚本等）
    // ──────────────────────────────────────────────
    function patchInterpreter() {
        try {
            if (window.Game_Interpreter && window.Game_Interpreter.prototype && !window.Game_Interpreter.prototype.__tyranorPatched) {
                var proto = window.Game_Interpreter.prototype;
                var orig355 = proto.command355;
                if (typeof orig355 === "function") {
                    proto.command355 = function () {
                        try { return orig355.apply(this, arguments); }
                        catch (e) { console.warn("[nw-polyfill] command355 suppressed:", e && e.message); return true; }
                    };
                }
                var orig356 = proto.command356;
                if (typeof orig356 === "function") {
                    proto.command356 = function () {
                        try { return orig356.apply(this, arguments); }
                        catch (e) { console.warn("[nw-polyfill] command356 suppressed:", e && e.message); return true; }
                    };
                }
                proto.__tyranorPatched = true;
            }
        } catch (e) {}
    }
    // 延迟打补丁，等待 rpg_objects.js 加载
    var patchTimer = setInterval(patchInterpreter, 300);
    setTimeout(function(){ clearInterval(patchTimer); patchInterpreter(); }, 8000);
    window.addEventListener("load", patchInterpreter);

    // FPSMeter / Graphics 404 容忍：v1 overlay 或旧插件可能触发的 rpg_core.js:2116 style 访问，提前给 document 兜底
    try {
        if (typeof window.FPSMeter === "undefined") {
            window.FPSMeter = function(){ this.hide=function(){}; this.show=function(){}; this.tickStart=function(){}; this.tick=function(){}; };
        }
    } catch(e){}

    // Black screen second error: rpg_core.js:2116 style access before DOM ready
    // Guard Graphics._centerElement / _createFPSMeter etc
    try {
        var _origCenter = window.Graphics && window.Graphics._centerElement;
        // Patch after Graphics is defined - poll
        var gcTimer = setInterval(function(){
            try {
                if (window.Graphics) {
                    clearInterval(gcTimer);
                    // wrap _centerElement to tolerate undefined element
                    if (typeof window.Graphics._centerElement === "function") {
                        var _orig = window.Graphics._centerElement;
                        window.Graphics._centerElement = function(el){
                            if (!el || !el.style) return;
                            try { return _orig.call(this, el); } catch(e){ console.warn("[polyfill] _centerElement suppressed:", e.message); }
                        };
                    }
                    if (typeof window.Graphics._createFPSMeter === "function") {
                        var _origFps = window.Graphics._createFPSMeter;
                        window.Graphics._createFPSMeter = function(){
                            try { return _origFps.apply(this, arguments); } catch(e){ console.warn("[polyfill] _createFPSMeter suppressed:", e.message); }
                        };
                    }
                }
            } catch(e){}
        }, 200);
        setTimeout(function(){ try{ clearInterval(gcTimer);}catch(e){}}, 6000);
    } catch(e){}

    // Ensure _progressElement guards are active even for v0 (game's own rpg_core.js)
    try {
        var _origSetup = window.Graphics && window.Graphics._setupProgress;
        // Poll for Graphics and patch _hideProgress/_showProgress regardless of v1/v0
        var pgTimer = setInterval(function(){
            try {
                if (window.Graphics && window.Graphics._hideProgress && !window.Graphics._hideProgress.__patched) {
                    var oh = window.Graphics._hideProgress;
                    window.Graphics._hideProgress = function(){ if(!this._progressElement || !this._progressElement.style) return; return oh.apply(this, arguments); };
                    window.Graphics._hideProgress.__patched = true;
                }
                if (window.Graphics && window.Graphics._showProgress && !window.Graphics._showProgress.__patched) {
                    var os = window.Graphics._showProgress;
                    window.Graphics._showProgress = function(){ if(!this._progressElement || !this._progressElement.style) return; return os.apply(this, arguments); };
                    window.Graphics._showProgress.__patched = true;
                }
                if (window.Graphics && window.Graphics._updateProgress && !window.Graphics._updateProgress.__patched) {
                    var ou = window.Graphics._updateProgress;
                    window.Graphics._updateProgress = function(){ if(!this._progressElement || !this._progressElement.style) return; return ou.apply(this, arguments); };
                    window.Graphics._updateProgress.__patched = true;
                }
            } catch(e){}
            if (window.Graphics && window.Graphics._hideProgress && window.Graphics._hideProgress.__patched) {
                clearInterval(pgTimer);
            }
        }, 200);
        setTimeout(function(){ try{ clearInterval(pgTimer);}catch(e){}}, 8000);
    } catch(e){}

    console.log("[nw-polyfill] full installed (fs/path/os/util/events/child_process/crypto/url/nw.gui/Buffer/process)");
})();
