// NW.js / Node 兼容层 — 让 MV/MZ 在 WebView 下完整模拟环境
// v0/v1 均在 earlyHook 阶段注入到 </head> 前
(function () {
    "use strict";
    if (window.__tyranorNwPolyfill) return;
    window.__tyranorNwPolyfill = true;

    try { window.global = window; window.global.global = window; } catch (e) {}
    try { if (typeof globalThis !== "undefined") globalThis.global = window; } catch (e) {}

    function pinIsNwjs() {
        try {
            if (window.Utils && typeof window.Utils.isNwjs === "function" && window.Utils.isNwjs() !== false) {
                var src = "";
                try { src = window.Utils.isNwjs.toString(); } catch (e2) {}
                if (src.indexOf("return false") === -1) window.Utils.isNwjs = function () { return false; };
            }
            if (window.StorageManager && typeof window.StorageManager.isLocalMode === "function") {
                try {
                    if (window.StorageManager.isLocalMode() !== false) window.StorageManager.isLocalMode = function () { return false; };
                } catch (e3) {}
            }
        } catch (e) {}
    }
    pinIsNwjs();
    var pinTimer = setInterval(function(){ pinIsNwjs(); if (window.Utils && window.Utils.isNwjs && window.Utils.isNwjs() === false && window.StorageManager && window.StorageManager.isLocalMode && window.StorageManager.isLocalMode() === false) { try{ clearInterval(pinTimer); }catch(e){} } }, 100);
    window.addEventListener("load", function(){ pinIsNwjs(); setTimeout(function(){ try{ clearInterval(pinTimer);}catch(e){}}, 3000); });
    window.addEventListener("pagehide", function () { try { clearInterval(pinTimer); } catch (e) {} });

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
    try { if (typeof window.__dirname === "undefined") window.__dirname = "/"; } catch (e) {}
    try { if (typeof window.__filename === "undefined") window.__filename = "/game/www/index.html"; } catch (e) {}

    if (typeof window.Buffer === "undefined") {
        (function () {
            function _toBinary(str) {
                try { return atob(str); } catch (e) { console.warn("[nw-polyfill] Buffer.from base64 decode failed"); return ""; }
            }
            function _fromBinary(bin, enc) {
                if (enc === "utf8" || enc === "utf-8") {
                    try {
                        if (typeof TextDecoder !== "undefined") return new TextDecoder("utf-8", {fatal:false}).decode(Uint8Array.from(bin, function(c){ return c.charCodeAt(0); }));
                    } catch (e2a) {}
                    try { return decodeURIComponent(escape(bin)); } catch (e2) { return bin; }
                }
                if (enc === "hex") {
                    var h = ""; for (var i = 0; i < bin.length; i++) { var c = bin.charCodeAt(i).toString(16); h += c.length === 1 ? "0" + c : c; } return h;
                }
                return bin;
            }
            var B = {
                from: function (input, encoding) {
                    if (typeof input === "string") {
                        if (encoding === "base64") {
                            var bin = _toBinary(input);
                            return { toString: function (enc) { return _fromBinary(bin, enc); }, length: bin.length, _bin: bin };
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
                            length: input.length, _bin: input
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
                isEncoding: function (e) { return ["utf8", "utf-8", "base64", "hex", "ascii", "binary", "latin1"].indexOf(e) >= 0; },
                byteLength: function (str, enc) {
                    if (enc === "base64") try { return atob(str).length; } catch (e) { return 0; }
                    return String(str).length;
                },
                concat: function (list) {
                    var s = ""; list.forEach(function (b) { s += (b && b._bin) ? b._bin : (b ? String(b) : ""); });
                    return B.from(s);
                }
            };
            window.Buffer = B;
            try { if (typeof globalThis !== "undefined") globalThis.Buffer = B; } catch (e) {}
        })();
    }

    var warnedFsWrite = false;
    function warnFsOnce(msg) {
        if (warnedFsWrite) return;
        warnedFsWrite = true;
        console.warn("[nw-polyfill] fs stub: " + msg + " (no-op, game may silently lose file-backed feature)");
    }
    var fsStub = {
        existsSync: function () { return false; },
        exists: function (p, cb) { if (typeof cb === "function") setTimeout(function () { cb(false); }, 0); },
        mkdirSync: function () { warnFsOnce("mkdirSync ignored"); },
        mkdir: function (p, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
        writeFileSync: function () { warnFsOnce("writeFileSync ignored"); },
        writeFile: function (p, d, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
        appendFileSync: function () { warnFsOnce("appendFileSync ignored"); },
        appendFile: function (p, d, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
        readFileSync: function () { warnFsOnce("readFileSync returns empty"); return ""; },
        readFile: function (p, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function () { cb(null, ""); }, 0); return ""; },
        unlinkSync: function () {},
        unlink: function (p, cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
        openSync: function () { return 0; },
        open: function (p, f, m, cb) { if (typeof m === "function") cb = m; if (typeof cb === "function") setTimeout(function () { cb(null, 0); }, 0); },
        closeSync: function () {},
        close: function (fd, cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
        readSync: function () { return 0; },
        writeSync: function () { return 0; },
        readdirSync: function () { return []; },
        readdir: function (p, o, cb) { if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function () { cb(null, []); }, 0); },
        statSync: function () { return { isFile: function () { return false; }, isDirectory: function () { return false; }, isSymbolicLink: function () { return false; }, size: 0, mtime: new Date(0) }; },
        lstatSync: function () { return { isFile: function () { return false; }, isDirectory: function () { return false; }, isSymbolicLink: function () { return false; }, size: 0, mtime: new Date(0) }; },
        fstatSync: function () { return { isFile: function () { return false; }, isDirectory: function () { return false; }, size: 0 }; },
        createReadStream: function () { return { on: function () { return this; }, once: function () { return this; }, pipe: function () { return this; }, read: function () {}, close: function () {} }; },
        createWriteStream: function () { return { on: function () { return this; }, once: function () { return this; }, write: function () {}, end: function () {}, close: function () {} }; },
        watch: function () { return { close: function () {}, on: function () { return this; } }; },
        watchFile: function () {}, unwatchFile: function () {},
        renameSync: function () {}, rename: function (p, q, cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
        copyFileSync: function () {}, copyFile: function (p, q, cb) { if (typeof cb === "function") setTimeout(function () { cb(null); }, 0); },
        chmodSync: function () {}, chownSync: function () {},
        promises: {
            readFile: function () { return Promise.resolve(""); },
            writeFile: function () { return Promise.resolve(); },
            mkdir: function () { return Promise.resolve(); },
            readdir: function () { return Promise.resolve([]); },
            stat: function () { return Promise.resolve({ isFile: function () { return false; }, isDirectory: function () { return false; } }); },
            unlink: function () { return Promise.resolve(); }
        }
    };

    var pathStub = {
        dirname: function (p) { if (!p) return "."; var i = Math.max(p.lastIndexOf("/"), p.lastIndexOf("\\")); return i >= 0 ? (p.slice(0, i) || "/") : "."; },
        basename: function (p, ext) { if (!p) return ""; var s = p.split("/").pop().split("\\").pop(); if (ext && s.endsWith(ext)) s = s.slice(0, -ext.length); return s; },
        extname: function (p) { var b = p.split("/").pop().split("\\").pop(); var d = b.lastIndexOf("."); return d >= 0 ? b.slice(d) : ""; },
        join: function () { var a = Array.prototype.slice.call(arguments).filter(Boolean); return a.join("/").replace(/\/+/g, "/"); },
        resolve: function () { var a = Array.prototype.slice.call(arguments).filter(Boolean); var s = a.join("/").replace(/\/+/g, "/"); if (s[0] !== "/") s = "/" + s; return s; },
        normalize: function (p) { return p ? p.replace(/\/+/g, "/").replace(/\/\.\//g, "/") : "."; },
        relative: function (from, to) { return to; },
        isAbsolute: function (p) { return p && (p[0] === "/" || /^[a-zA-Z]:[\\/]/.test(p)); },
        parse: function (p) { var b = pathStub.basename(p); var e = pathStub.extname(p); var d = pathStub.dirname(p); return { root: "/", dir: d, base: b, ext: e, name: b.slice(0, b.length - e.length) }; },
        format: function (o) { return (o.dir ? o.dir + "/" : "") + (o.name || "") + (o.ext || ""); },
        sep: "/", delimiter: ":", posix: null, win32: null
    };
    pathStub.posix = pathStub; pathStub.win32 = pathStub;

    var osStub = {
        platform: function () { return "linux"; }, arch: function () { return "x64"; }, type: function () { return "Linux"; },
        release: function () { return "0.0.0"; }, homedir: function () { return "/"; }, tmpdir: function () { return "/tmp"; },
        hostname: function () { return "localhost"; }, cpus: function () { return []; }, totalmem: function () { return 0; }, freemem: function () { return 0; }, EOL: "\n"
    };
    var utilStub = {
        inherits: function (ctor, superCtor) { ctor.super_ = superCtor; ctor.prototype = Object.create(superCtor.prototype, { constructor: { value: ctor } }); },
        format: function (f) { var a = Array.prototype.slice.call(arguments, 1); var i = 0; return String(f).replace(/%[sdj%]/g, function (x) { if (x === "%%") return "%"; if (i >= a.length) return x; switch (x) { case "%s": return String(a[i++]); case "%d": return Number(a[i++]); case "%j": try { return JSON.stringify(a[i++]); } catch (e) { return "[Circular]"; } default: return x; } }); if (a.length > i) return f + " " + a.slice(i).join(" "); return f; },
        inspect: function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } },
        isArray: Array.isArray, isString: function (x) { return typeof x === "string"; }, deprecate: function (fn) { return fn; }
    };
    var eventsStub = function EventEmitter() { this._e = {}; };
    eventsStub.prototype.on = function (e, fn) { (this._e[e] = this._e[e] || []).push(fn); return this; };
    eventsStub.prototype.once = function (e, fn) { var s = this; function w() { s.removeListener(e, w); fn.apply(s, arguments); } w.fn = fn; this.on(e, w); return this; };
    eventsStub.prototype.emit = function (e) { var a = Array.prototype.slice.call(arguments, 1); var h = this._e[e]; if (h) h.slice().forEach(function (f) { f.apply(null, a); }); return true; };
    eventsStub.prototype.removeListener = function (e, fn) { var h = this._e[e]; if (h) this._e[e] = h.filter(function (f) { return f !== fn && f.fn !== fn; }); return this; };
    eventsStub.prototype.removeAllListeners = function (e) { if (e) delete this._e[e]; else this._e = {}; return this; };
    var childProcessStub = {
        exec: function (cmd, opts, cb) { if (typeof opts === "function") cb = opts; if (typeof cb === "function") setTimeout(function () { cb(null, "", ""); }, 0); return { on: function () { return this; }, kill: function () {} }; },
        execSync: function () { return ""; },
        execFile: function (f, a, o, cb) { if (typeof a === "function") cb = a; else if (typeof o === "function") cb = o; if (typeof cb === "function") setTimeout(function () { cb(null, "", ""); }, 0); return { on: function () { return this; } }; },
        spawn: function () { return { on: function () { return this; }, once: function () { return this; }, stdout: { on: function () { return this; } }, stderr: { on: function () { return this; } }, kill: function () {}, pid: 0 }; },
        spawnSync: function () { return { status: 0, stdout: "", stderr: "", pid: 0 }; }, fork: function () { return childProcessStub.spawn(); }
    };
    var cryptoStub = {
        randomBytes: function (n) { var s = ""; for (var i = 0; i < n; i++) s += String.fromCharCode(Math.floor(Math.random() * 256)); return window.Buffer ? window.Buffer.from(s, "binary") : s; },
        createHash: function () { return { update: function () { return this; }, digest: function () { return ""; } }; },
        createHmac: function () { return { update: function () { return this; }, digest: function () { return ""; } }; }
    };
    var urlStub = {
        parse: function (u) { try { var a = document.createElement("a"); a.href = u; return { protocol: a.protocol, host: a.host, hostname: a.hostname, port: a.port, pathname: a.pathname, search: a.search, hash: a.hash, href: a.href }; } catch (e) { return { href: u }; } },
        format: function (o) { return o.href || ""; }, resolve: function (f, t) { try { return new URL(t, f).href; } catch (e) { return t; } }
    };
    var querystringStub = {
        parse: function (s) { var o = {}; if (!s) return o; s.replace(/^\?/, "").split("&").forEach(function (p) { var kv = p.split("="); if (kv[0]) o[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || ""); }); return o; },
        stringify: function (o) { return Object.keys(o).map(function (k) { return encodeURIComponent(k) + "=" + encodeURIComponent(o[k]); }).join("&"); },
        escape: encodeURIComponent, unescape: decodeURIComponent
    };
    var nwGuiStub = (function () {
        var winStub = {
            close: function () {}, showDevTools: function () {}, focus: function () {}, blur: function () {},
            moveBy: function () {}, resizeBy: function () {}, moveTo: function () {}, resizeTo: function () {},
            setMinimumSize: function () {}, setMaximumSize: function () {}, setResizable: function () {},
            setAlwaysOnTop: function () {}, enterFullscreen: function () {}, leaveFullscreen: function () {},
            maximize: function () {}, unmaximize: function () {}, minimize: function () {}, restore: function () {},
            show: function () {}, hide: function () {}, reload: function () { location.reload(); }, reloadIgnoringCache: function () { location.reload(); },
            x: 0, y: 0, width: 816, height: 624, title: "", menu: null, isFullscreen: false
        };
        function Menu(opt) { this.type = (opt && opt.type) || "contextmenu"; this.items = []; }
        Menu.prototype.append = function (i) { this.items.push(i); }; Menu.prototype.insert = function (i, p) { this.items.splice(p, 0, i); };
        Menu.prototype.remove = function (i) { var idx = this.items.indexOf(i); if (idx >= 0) this.items.splice(idx, 1); };
        Menu.prototype.removeAt = function (i) { this.items.splice(i, 1); }; Menu.prototype.createMacBuiltin = function () {}; Menu.prototype.popup = function () {};
        function MenuItem(opt) { this.label = (opt && opt.label) || ""; this.type = (opt && opt.type) || "normal"; this.click = opt && opt.click; this.enabled = true; this.submenu = opt && opt.submenu; }
        var clipboardStub = { set: function () {}, get: function () { return ""; }, clear: function () {} };
        var shellStub = { openExternal: function (url) { try { window.open(url, "_blank"); } catch (e) {} }, openItem: function () {}, showItemInFolder: function () {} };
        var screenStub = { Init: function () {}, screens: [], chooseDesktopMedia: function (a, cb) { if (typeof cb === "function") cb(""); } };
        return {
            Window: { get: function () { return winStub; }, open: function () { return winStub; } },
            Menu: Menu, MenuItem: MenuItem, Clipboard: { get: function () { return clipboardStub; } },
            Shell: shellStub, Screen: screenStub,
            App: { argv: [], fullArgv: [], manifest: {}, dataPath: "/tmp", clearCache: function () {}, closeAllWindows: function () {}, quit: function () {}, on: function () {}, removeAllListeners: function () {} }
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
        if (!moduleCache[name]) moduleCache[name] = {};
        return moduleCache[name];
    }
    if (typeof window.require === "undefined") {
        window.require = function (name) { return resolveRequire(name); };
    } else {
        var _origRequire = window.require;
        window.require = function (name) { try { var r = _origRequire(name); if (r) return r; } catch (e) {} return resolveRequire(name); };
    }
    if (typeof window.require.resolve !== "function") window.require.resolve = function (x) { return x; };
    if (typeof window.require.cache === "undefined") window.require.cache = {};
    if (typeof window.module === "undefined") window.module = { exports: {} };
    if (typeof window.exports === "undefined") window.exports = window.module.exports;
    if (typeof window.nw === "undefined") window.nw = nwGuiStub;
    if (typeof window.gui === "undefined") window.gui = nwGuiStub;
    try { if (typeof globalThis !== "undefined" && !globalThis.require) globalThis.require = window.require; } catch (e) {}
    try { if (typeof globalThis !== "undefined" && !globalThis.nw) globalThis.nw = window.nw; } catch (e) {}
    try { if (typeof globalThis !== "undefined" && !globalThis.Buffer) globalThis.Buffer = window.Buffer; } catch (e) {}

    // ---- lodash/underscore 全局 `_` 兜底 ----
    // 部分游戏插件（如 DKTools 老版本）依赖全局 _（lodash），NW.js 桌面环境由
    // 游戏自带 lodash 或 DKTools 内嵌 UMD 提供；WebView 下这些文件缺失/未加载时
    // 插件初始化即 ReferenceError（`_ is not defined`），后续插件级联崩溃。
    // 这里注入最小 lodash 兼容层：仅当全局 _ 未定义时生效（不覆盖游戏自带的）。
    if (typeof window._ === "undefined") {
        (function () {
            function isArrayLike(o) { return o && typeof o.length === "number" && typeof o !== "function"; }
            function isObject(o) { var t = typeof o; return o && (t === "object" || t === "function"); }
            function keys(o) { var r = []; for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) r.push(k); return r; }
            function iteratee(f) {
                if (typeof f === "function") return f;
                if (f == null) return function (o) { return o; };
                if (typeof f === "string") return function (o) { return o == null ? undefined : o[f]; };
                if (typeof f === "object") return function (o) { if (o == null) return false; for (var pk in f) if (Object.prototype.hasOwnProperty.call(f, pk) && o[pk] !== f[pk]) return false; return true; };
                return function (o) { return o === f; };
            }
            function forEach(c, f, ctx) {
                if (!c) return c;
                f = iteratee(f);
                if (isArrayLike(c)) { for (var i = 0; i < c.length; i++) if (f.call(ctx, c[i], i, c) === false) break; }
                else { var ks = keys(c); for (var j = 0; j < ks.length; j++) if (f.call(ctx, c[ks[j]], ks[j], c) === false) break; }
                return c;
            }
            function baseGet(o, path) {
                var parts = typeof path === "string" ? path.split(".") : path;
                var cur = o;
                for (var i = 0; cur != null && i < parts.length; i++) {
                    var p = parts[i];
                    var m = /^\[(\d+)\]$/.exec(p);
                    if (m) p = m[1];
                    cur = cur[p];
                }
                return cur;
            }
            function baseSet(o, path, v) {
                var parts = typeof path === "string" ? path.split(".") : path;
                var cur = o;
                for (var i = 0; i < parts.length - 1; i++) {
                    var p = parts[i];
                    if (!isObject(cur[p])) cur[p] = {};
                    cur = cur[p];
                }
                cur[parts[parts.length - 1]] = v;
                return o;
            }
            var _ = {
                VERSION: "4.17.21-tyranor-min",
                forEach: forEach, each: forEach,
                map: function (c, f) { var r = []; forEach(c, function (v, k, o) { r.push(iteratee(f)(v, k, o)); }); return r; },
                collect: function (c, f) { return _.map(c, f); },
                filter: function (c, f) { var r = []; forEach(c, function (v, k, o) { if (iteratee(f)(v, k, o)) r.push(v); }); return r; },
                find: function (c, f) { var hit; forEach(c, function (v, k, o) { if (iteratee(f)(v, k, o)) { hit = v; return false; } }); return hit; },
                findIndex: function (c, f) { var idx = -1; forEach(c, function (v, k) { if (iteratee(f)(v, k)) { idx = k; return false; } }); return idx; },
                some: function (c, f) { return _.find(c, f) !== undefined; },
                any: function (c, f) { return _.some(c, f); },
                every: function (c, f) { var all = true; forEach(c, function (v, k, o) { if (!iteratee(f)(v, k, o)) { all = false; return false; } }); return all; },
                all: function (c, f) { return _.every(c, f); },
                includes: function (c, v) { if (isArrayLike(c)) { for (var i = 0; i < c.length; i++) if (c[i] === v) return true; return false; } if (c) { for (var k in c) if (c[k] === v) return true; } return false; },
                contains: function (c, v) { return _.includes(c, v); },
                indexOf: function (c, v) { if (!isArrayLike(c)) return -1; for (var i = 0; i < c.length; i++) if (c[i] === v) return i; return -1; },
                last: function (c) { return isArrayLike(c) && c.length ? c[c.length - 1] : undefined; },
                first: function (c) { return isArrayLike(c) && c.length ? c[0] : undefined; },
                head: function (c) { return _.first(c); },
                keys: keys,
                values: function (o) { return keys(o).map(function (k) { return o[k]; }); },
                size: function (c) { return !c ? 0 : isArrayLike(c) ? c.length : keys(c).length; },
                isEmpty: function (c) { return _.size(c) === 0; },
                clone: function (o) { if (!isObject(o)) return o; if (Array.isArray(o)) return o.slice(); var r = {}; for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) r[k] = o[k]; return r; },
                cloneDeep: function (o) { if (!isObject(o)) return o; if (Array.isArray(o)) return o.map(function (v) { return _.cloneDeep(v); }); var r = {}; keys(o).forEach(function (k) { r[k] = _.cloneDeep(o[k]); }); return r; },
                extend: function (t) { for (var i = 1; i < arguments.length; i++) { var s = arguments[i]; if (s) for (var k in s) if (Object.prototype.hasOwnProperty.call(s, k)) t[k] = s[k]; } return t; },
                assign: function () { return _.extend.apply(null, arguments); },
                merge: function (t, s) { if (!isObject(s)) return t; for (var k in s) { if (!Object.prototype.hasOwnProperty.call(s, k)) continue; if (isObject(s[k]) && !Array.isArray(s[k]) && isObject(t[k]) && !Array.isArray(t[k])) _.merge(t[k], s[k]); else t[k] = _.cloneDeep(s[k]); } return t; },
                get: function (o, path, def) { var v = baseGet(o, path); return v === undefined ? def : v; },
                set: function (o, path, v) { return o ? baseSet(o, path, v) : o; },
                has: function (o, path) { if (!o) return false; var parts = typeof path === "string" ? path.split(".") : path; var cur = o; for (var i = 0; i < parts.length; i++) { if (cur == null || !Object.prototype.hasOwnProperty.call(cur, parts[i])) return false; cur = cur[parts[i]]; } return true; },
                defaults: function (o) { for (var i = 1; i < arguments.length; i++) { var s = arguments[i]; for (var k in s) if (Object.prototype.hasOwnProperty.call(s, k) && o[k] === undefined) o[k] = s[k]; } return o; },
                times: function (n, f) { var r = []; for (var i = 0; i < n; i++) r.push(f(i)); return r; },
                defaultTo: function (v, def) { return (v == null || v !== v) ? def : v; },
                escape: function (str) { return String(str == null ? "" : str).replace(/[&<>"'`]/g, function (ch) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;", "`": "&#96;" }[ch]; }); },
                inRange: function (n, start, end) { if (end == null) { end = start; start = 0; } if (start > end) { var t = start; start = end; end = t; } return n >= start && n < end; },
                isEqual: function (a, b) { if (a === b) return true; if (typeof a !== typeof b) return false; if (a && b && typeof a === "object") { if (Array.isArray(a) !== Array.isArray(b)) return false; var ka = Object.keys(a), kb = Object.keys(b); if (ka.length !== kb.length) return false; return ka.every(function (k) { return _.isEqual(a[k], b[k]); }); } return a !== a && b !== b; },
                pull: function (arr) { if (!Array.isArray(arr)) return arr; var remove = Array.prototype.slice.call(arguments, 1); for (var i = arr.length - 1; i >= 0; i--) if (remove.indexOf(arr[i]) >= 0) arr.splice(i, 1); return arr; },
                range: function (a, b, step) { if (b == null) { b = a; a = 0; } step = step || 1; var r = []; if (step > 0) for (var i = a; i < b; i += step) r.push(i); else for (var j = a; j > b; j += step) r.push(j); return r; },
                isObject: isObject,
                isString: function (o) { return typeof o === "string" || o instanceof String; },
                isNumber: function (o) { return typeof o === "number" || o instanceof Number; },
                isBoolean: function (o) { return o === true || o === false || o instanceof Boolean; },
                isFunction: function (o) { return typeof o === "function"; },
                isArray: Array.isArray,
                isUndefined: function (o) { return o === undefined; },
                isNull: function (o) { return o === null; },
                isNil: function (o) { return o == null; },
                toArray: function (c) { if (isArrayLike(c)) return Array.prototype.slice.call(c); return keys(c || {}).map(function (k) { return c[k]; }); },
                debounce: function (f, wait) { var t; return function () { var args = arguments, self = this; clearTimeout(t); t = setTimeout(function () { f.apply(self, args); }, wait); }; },
                throttle: function (f, wait) { var last = 0; return function () { var now = Date.now(); if (now - last >= wait) { last = now; return f.apply(this, arguments); } }; },
                once: function (f) { var done, r; return function () { if (!done) { done = true; r = f.apply(this, arguments); } return r; }; },
                noop: function () {},
                identity: function (o) { return o; },
            };
            window._ = _;
            try { if (typeof globalThis !== "undefined" && !globalThis._) globalThis._ = _; } catch (e) {}
            try { console.log("[nw-polyfill] lodash minimal `_` installed (was undefined)"); } catch (e2) {}
        })();
    }

    (function ensureWindowCompat() {
        var pendingStub = false;
        function tryResolve() {
            try {
                var hasBase = typeof window.Window_StatusBase !== "undefined" && window.Window_StatusBase && !window.Window_StatusBase.__tyranorStub;
                var isStub = window.Window_StatusBase && window.Window_StatusBase.__tyranorStub;
                if (!hasBase) {
                    if (typeof window.Window_Status !== "undefined") {
                        if (isStub) {
                            try { window.Window_StatusBase = window.Window_Status; window.Window_StatusBase.__tyranorWasStub = true; } catch (e) {}
                        } else if (typeof window.Window_StatusBase === "undefined") {
                            window.Window_StatusBase = window.Window_Status;
                        }
                        pendingStub = false;
                    } else if (typeof window.Window_Selectable !== "undefined" && typeof window.Window_StatusBase === "undefined") {
                        var F = function () { return window.Window_Selectable.apply(this, arguments); };
                        F.prototype = Object.create(window.Window_Selectable.prototype);
                        F.__tyranorStub = true;
                        window.Window_StatusBase = F;
                        pendingStub = true;
                    } else if (isStub && typeof window.Window_Status !== "undefined") {
                        window.Window_StatusBase = window.Window_Status;
                        pendingStub = false;
                    }
                }
                if (typeof window.Window_SkillStatus === "undefined" && window.Window_StatusBase) window.Window_SkillStatus = window.Window_StatusBase;
                if (typeof window.Window_EquipStatus === "undefined" && window.Window_StatusBase) window.Window_EquipStatus = window.Window_StatusBase;
                if (typeof window.Window_ShopStatus === "undefined" && window.Window_StatusBase) window.Window_ShopStatus = window.Window_StatusBase;
            } catch (e) {}
        }
        tryResolve();
        var compatTimer = setInterval(function () {
            tryResolve();
            if (typeof window.Window_StatusBase !== "undefined" && !window.Window_StatusBase.__tyranorStub && typeof window.Window_Status !== "undefined") {
                clearInterval(compatTimer);
            }
        }, 100);
        setTimeout(function () { try { clearInterval(compatTimer); } catch (e) {} tryResolve(); }, 8000);
    })();

    function patchInterpreter() {
        try {
            if (window.Game_Interpreter && window.Game_Interpreter.prototype && !window.Game_Interpreter.prototype.__tyranorPatched) {
                var proto = window.Game_Interpreter.prototype;
                var orig355 = proto.command355;
                if (typeof orig355 === "function") {
                    proto.command355 = function () { try { return orig355.apply(this, arguments); } catch (e) { console.warn("[nw-polyfill] command355 suppressed:", e && e.message); return true; } };
                }
                var orig356 = proto.command356;
                if (typeof orig356 === "function") {
                    proto.command356 = function () { try { return orig356.apply(this, arguments); } catch (e) { console.warn("[nw-polyfill] command356 suppressed:", e && e.message); return true; } };
                }
                proto.__tyranorPatched = true;
            }
        } catch (e) {}
    }
    var patchTimer = setInterval(patchInterpreter, 300);
    setTimeout(function () { clearInterval(patchTimer); patchInterpreter(); }, 8000);
    window.addEventListener("load", patchInterpreter);

    try { if (typeof window.FPSMeter === "undefined") window.FPSMeter = function () { this.hide = function () {}; this.show = function () {}; this.tickStart = function () {}; this.tick = function () {}; }; } catch (e) {}

    (function () {
        var gcTimer = setInterval(function () {
            try {
                if (window.Graphics) {
                    if (typeof window.Graphics._centerElement === "function" && !window.Graphics._centerElement.__tyranorPatched) {
                        var _orig = window.Graphics._centerElement;
                        window.Graphics._centerElement = function (el) { if (!el || !el.style) return; try { return _orig.call(this, el); } catch (e) { console.warn("[nw-polyfill] _centerElement suppressed:", e.message); } };
                        window.Graphics._centerElement.__tyranorPatched = true;
                    }
                    if (typeof window.Graphics._createFPSMeter === "function" && !window.Graphics._createFPSMeter.__tyranorPatched) {
                        var _origFps = window.Graphics._createFPSMeter;
                        window.Graphics._createFPSMeter = function () { try { return _origFps.apply(this, arguments); } catch (e) { console.warn("[nw-polyfill] _createFPSMeter suppressed:", e.message); } };
                        window.Graphics._createFPSMeter.__tyranorPatched = true;
                    }
                    if (window.Graphics._centerElement && window.Graphics._centerElement.__tyranorPatched) clearInterval(gcTimer);
                }
            } catch (e) {}
        }, 200);
        setTimeout(function () { try { clearInterval(gcTimer); } catch (e) {} }, 6000);
    })();

    (function () {
        var pgTimer = setInterval(function () {
            try {
                if (window.Graphics) {
                    if (window.Graphics._hideProgress && !window.Graphics._hideProgress.__tyranorPatched) {
                        var oh = window.Graphics._hideProgress;
                        window.Graphics._hideProgress = function () { if (!this._progressElement || !this._progressElement.style) return; return oh.apply(this, arguments); };
                        window.Graphics._hideProgress.__tyranorPatched = true;
                    }
                    if (window.Graphics._showProgress && !window.Graphics._showProgress.__tyranorPatched) {
                        var os = window.Graphics._showProgress;
                        window.Graphics._showProgress = function () { if (!this._progressElement || !this._progressElement.style) return; return os.apply(this, arguments); };
                        window.Graphics._showProgress.__tyranorPatched = true;
                    }
                    if (window.Graphics._updateProgress && !window.Graphics._updateProgress.__tyranorPatched) {
                        var ou = window.Graphics._updateProgress;
                        window.Graphics._updateProgress = function () { if (!this._progressElement || !this._progressElement.style) return; return ou.apply(this, arguments); };
                        window.Graphics._updateProgress.__tyranorPatched = true;
                    }
                    if (window.Graphics._updateProgressCount && !window.Graphics._updateProgressCount.__tyranorPatched) {
                        var oc = window.Graphics._updateProgressCount;
                        window.Graphics._updateProgressCount = function () { if (!this._progressElement || !this._progressElement.style || !this._filledBarElement || !this._filledBarElement.style) return; return oc.apply(this, arguments); };
                        window.Graphics._updateProgressCount.__tyranorPatched = true;
                    }
                }
            } catch (e) {}
            if (window.Graphics && window.Graphics._hideProgress && window.Graphics._hideProgress.__tyranorPatched) clearInterval(pgTimer);
        }, 200);
        setTimeout(function () { try { clearInterval(pgTimer); } catch (e) {} }, 8000);
    })();

    try {
        if (typeof window.makeVideoPlayableInline === "undefined") {
            window.makeVideoPlayableInline = function (video) { try { if (video) { video.setAttribute("playsinline", ""); video.setAttribute("webkit-playsinline", ""); } } catch (e) {} };
        }
    } catch (e) {}

    console.log("[nw-polyfill] full installed (fs/path/os/util/events/child_process/crypto/url/nw.gui/Buffer/process)");
})();
