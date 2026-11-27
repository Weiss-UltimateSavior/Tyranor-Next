// Supply missing browser filesystem metadata without editing the game's files.
;(function () {
    "use strict";
    if (typeof DKTools === "undefined" || !DKTools.IO ||
        typeof DKTools.IO._loadStamp !== "function" || DKTools.IO.__tyranorStampCompat) return;
    DKTools.IO.__tyranorStampCompat = true;
    var original = DKTools.IO._loadStamp;
    function isRealNwjs() {
        return !window.__tyranorNwPolyfill && typeof Utils !== "undefined" &&
            typeof Utils.isNwjs === "function" && Utils.isNwjs();
    }
    // DKTools also reads stamp paths directly for directory listings and stats.
    // Keep canonical keys for enumeration, resolving aliases only during reads.
    var MAX_FUZZY_ENTRIES = 4096;
    function compatibleIndex(index) {
        var views = new WeakMap();
        // Match Kotlin/Java's simple upper-then-lower case comparison. Whole-string
        // lowercasing has context/expansion rules (final sigma, dotted I) that differ.
        function caseKey(name) {
            return Array.from(name).map(function (character) {
                var upper = character.toUpperCase();
                if (Array.from(upper).length !== 1) upper = character;
                if (upper === "\u0130") return "i";
                var lower = upper.toLowerCase();
                return Array.from(lower).length === 1 ? lower : upper;
            }).join("");
        }
        var whitespace = /[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/g;
        var trailingWhitespace = /[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/;
        function whitespaceKey(name) { return caseKey(name.replace(whitespace, " ")); }
        function trailingKey(name) {
            var dot = name.lastIndexOf(".");
            return caseKey(dot > 0 ? name.substring(0, dot).replace(trailingWhitespace, "") + name.substring(dot) : name.replace(trailingWhitespace, ""));
        }
        function view(node) {
            if (!node || typeof node !== "object" || Array.isArray(node)) return node;
            if (views.has(node)) return views.get(node);
            var names = Object.keys(node);
            var entryCount = Object.prototype.hasOwnProperty.call(node, "__stats__") ? names.length - 1 : names.length;
            // Returns matched name, undefined for no match, or null when ambiguous.
            function unique(requested, key) {
                var target = key(requested), found;
                for (var i = 0; i < names.length; i++) {
                    if (key(names[i]) !== target) continue;
                    if (found !== undefined) return null;
                    found = names[i];
                }
                return found;
            }
            var proxy = new Proxy(node, { get: function (target, requested) {
                if (typeof requested !== "string" || requested === "__proto__" ||
                    requested === "constructor" || requested === "prototype") return undefined;
                if (Object.prototype.hasOwnProperty.call(target, requested)) return view(target[requested]);
                var name = unique(requested, caseKey);
                if (typeof name === "string") return view(target[name]);
                if (entryCount > MAX_FUZZY_ENTRIES) return undefined;
                name = unique(requested, whitespaceKey);
                if (typeof name === "string") return view(target[name]);
                if (name === null) return undefined;
                name = unique(requested, trailingKey);
                return typeof name === "string" ? view(target[name]) : undefined;
            }});
            views.set(node, proxy);
            return proxy;
        }
        return view(index);
    }
    // DKTools initializes IO synchronously from window.onload. Fetch metadata first,
    // then let the existing load handlers run together, preserving their alias chain.
    // In particular, making _loadStamp itself async would publish IO readiness too early.
    var prefetched, finished = false;
    var mode = DKTools.IO.mode;
    if (mode === undefined && typeof DKToolsParam !== "undefined") {
        mode = DKToolsParam.get("File System", "Mode");
    }
    if (!isRealNwjs() && mode === DKTools.IO.MODE_NWJS_STAMP &&
        (!DKTools.IO._stamp || Object.keys(DKTools.IO._stamp).length === 0)) {
        var resolveReady;
        var ready = new Promise(function (resolve) { resolveReady = resolve; });
        function finish(index) { prefetched = index; finished = true; resolveReady(); }
        function requestIndex() {
            var xhr = new XMLHttpRequest();
            xhr.open("GET", "/__tyranor_internal/dktools-index.json", true);
            xhr.overrideMimeType("application/json");
            xhr.timeout = 15000;
            xhr.onload = function () {
                if (xhr.status === 202) {
                    setTimeout(requestIndex, 100);
                    return;
                }
                try {
                    if (xhr.status !== 200) throw new Error("file index HTTP " + xhr.status);
                    var index = JSON.parse(xhr.responseText);
                    if (index !== null && (!index || Array.isArray(index) || typeof index !== "object")) {
                        throw new Error("invalid file index");
                    }
                    finish(index);
                } catch (error) {
                    console.warn("[Tyranor] DKTools file index failed: " + error.message);
                    finish(null);
                }
            };
            xhr.onerror = xhr.ontimeout = xhr.onabort = function () { finish(null); };
            try { xhr.send(); } catch (error) { finish(null); }
        }
        window.__tyranorDkToolsReady = ready;
        requestIndex();
    }
    DKTools.IO._loadStamp = function () {
        if (isRealNwjs() || this.mode !== DKTools.IO.MODE_NWJS_STAMP) return original.apply(this, arguments);
        if (this.__tyranorGeneratedStamp && this._stamp === this.__tyranorGeneratedStamp) return;
        if (this._stamp && Object.keys(this._stamp).length > 0) return original.apply(this, arguments);
        // null keeps original Stamp.json authoritative, including malformed originals.
        if (finished && prefetched !== null && prefetched !== undefined) {
            this._stamp = compatibleIndex(prefetched);
            this.__tyranorGeneratedStamp = this._stamp;
            console.info("[Tyranor] DKTools browser file index enabled");
            return;
        }
        return original.apply(this, arguments);
    };
})();
