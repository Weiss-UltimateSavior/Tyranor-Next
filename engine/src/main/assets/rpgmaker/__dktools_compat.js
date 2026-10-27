// Supply missing browser filesystem metadata without editing the game's files.
;(function () {
    "use strict";
    if (typeof DKTools === "undefined" || !DKTools.IO ||
        typeof DKTools.IO._loadStamp !== "function" || DKTools.IO.__tyranorStampCompat) return;
    DKTools.IO.__tyranorStampCompat = true;
    var original = DKTools.IO._loadStamp;
    DKTools.IO._loadStamp = function () {
        if (Utils.isNwjs() || this.mode !== DKTools.IO.MODE_NWJS_STAMP ||
            (this._stamp && Object.keys(this._stamp).length > 0)) return original.apply(this, arguments);
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
                this._stamp = index;
                console.info("[Tyranor] DKTools browser file index enabled");
                return;
            }
        } catch (error) {
            console.warn("[Tyranor] DKTools file index failed: " + error.message);
        }
        return original.apply(this, arguments);
    };
})();
