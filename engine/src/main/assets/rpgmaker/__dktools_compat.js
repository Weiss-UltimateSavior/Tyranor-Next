// Supply missing browser filesystem metadata without editing the game's files.
;(function () {
    "use strict";
    if (typeof DKTools === "undefined" || !DKTools.IO ||
        typeof DKTools.IO._loadStamp !== "function" || DKTools.IO.__tyranorStampCompat) return;
    DKTools.IO.__tyranorStampCompat = true;
    var original = DKTools.IO._loadStamp;
    // DKTools also reads stamp paths directly for directory listings and stats.
    // Keep canonical keys for enumeration, resolving aliases only during reads.
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
        function whitespaceKey(name) { return name.replace(whitespace, " ").toLowerCase(); }
        function trailingKey(name) {
            var dot = name.lastIndexOf(".");
            var trim = /[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/;
            return (dot > 0 ? name.substring(0, dot).replace(trim, "") + name.substring(dot) : name.replace(trim, "")).toLowerCase();
        }
        function view(node) {
            if (!node || typeof node !== "object" || Array.isArray(node)) return node;
            if (views.has(node)) return views.get(node);
            var names = Object.keys(node);
            function unique(requested, key) {
                var target = key(requested), found;
                for (var i = 0; i < names.length; i++) {
                    if (key(names[i]) !== target) continue;
                    if (found !== undefined) return undefined;
                    found = names[i];
                }
                return found;
            }
            var proxy = new Proxy(node, { get: function (target, requested) {
                if (typeof requested !== "string" || requested === "__proto__" ||
                    requested === "constructor" || requested === "prototype") return undefined;
                var name = Object.prototype.hasOwnProperty.call(target, requested) ? requested :
                    unique(requested, caseKey);
                if (name === undefined) name = unique(requested, whitespaceKey);
                if (name === undefined) name = unique(requested, trailingKey);
                return name === undefined ? undefined : view(target[name]);
            }});
            views.set(node, proxy);
            return proxy;
        }
        return view(index);
    }
    DKTools.IO._loadStamp = function () {
        if (Utils.isNwjs() || this.mode !== DKTools.IO.MODE_NWJS_STAMP) return original.apply(this, arguments);
        if (this.__tyranorGeneratedStamp && this._stamp === this.__tyranorGeneratedStamp) return;
        if (this._stamp && Object.keys(this._stamp).length > 0) return original.apply(this, arguments);
        try {
            var xhr = new XMLHttpRequest();
            xhr.open("GET", "__tyranor_internal/dktools-index.json", false);
            xhr.overrideMimeType("application/json");
            xhr.send();
            if (xhr.status !== 200) throw new Error("file index HTTP " + xhr.status);
            var index = JSON.parse(xhr.responseText);
            // null means an original Stamp.json exists; let DKTools load/validate it.
            if (index !== null) {
                if (!index || Array.isArray(index) || typeof index !== "object") throw new Error("invalid file index");
                this._stamp = compatibleIndex(index);
                this.__tyranorGeneratedStamp = this._stamp;
                console.info("[Tyranor] DKTools browser file index enabled");
                return;
            }
        } catch (error) {
            console.warn("[Tyranor] DKTools file index failed: " + error.message);
        }
        return original.apply(this, arguments);
    };
})();
