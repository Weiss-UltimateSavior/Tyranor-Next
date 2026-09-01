Graphics._createRenderer = function() {
    PIXI.dontSayHello = true;
    var width = this._width;
    var height = this._height;
    var options = { view: this._canvas };

    function getUrlParameters(url) {
        if (!url) url = window.location.href;
        var result = {};
        var parts = url.replace(/[?&]+([^=&]+)=([^&]*)/gi, function(m,key,value) {
            result[key] = value;
        });
        return result;
    }

    var param = getUrlParameters();

    if ("android-legacy" in param) {
        console.log("Android loader enabled.");
        console.log("Add options to the PIXI renderer.");

        const AndroidLegacyOption = {
            legacy: true
        };

        for (var optkey in AndroidLegacyOption) {
            options[optkey] = AndroidLegacyOption[optkey];
            console.log(`Option added : ${"$"}{optkey} => ${"$"}{options[optkey]}`);
        }
    } else
        console.log("Android loader has been disabled. (Not a legacy device or running in desktop)");

    try {

    switch (this._rendererType) {
        case 'canvas':
            this._renderer = new PIXI.CanvasRenderer(width, height, options);
            break;
        case 'webgl':
            this._renderer = new PIXI.WebGLRenderer(width, height, options);
            break;
        default:
            this._renderer = PIXI.autoDetectRenderer(width, height, options);
            break;
        }

        if(this._renderer && this._renderer.textureGC)
            this._renderer.textureGC.maxIdle = 1;

        console.log(typeof this._renderer);

    } catch (e) {
        this._renderer = null;
    }
};

StorageManager.saveToWebStorage = function(savefileId, json) {
    var key = this.webStorageKey(savefileId);
    var data = LZString.compressToBase64(json);
    var ok = false;
    try { var r = window.saveDataManager.Save(key, data); ok = (r !== false && r !== 0); } catch (e) { ok = false; }
    // Null/undefined return from bridge means success (void bridge compat); only explicit false/0 fails
    if (!ok) {
        try { localStorage.setItem(key, data); ok = true; } catch (e2) {}
    }
    if (!ok) throw new Error("saveToWebStorage failed: both backends rejected key=" + key);
};

StorageManager.loadFromWebStorage = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    var data = null;
    try { data = window.saveDataManager.Load(key); } catch (e) {}
    if (data == null || data === "") {
        try { data = localStorage.getItem(key); } catch (e2) {}
    }
    if (data == null || data === "") return null;
    var out = LZString.decompressFromBase64(data);
    if (out == null) console.warn("[rpg-save] load decompress null for " + key);
    return out;
};

StorageManager.loadFromWebStorageBackup = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    var data = null;
    try { data = window.saveDataManager.Load(key); } catch (e) {}
    if (data == null || data === "") {
        try { data = localStorage.getItem(key); } catch (e2) {}
    }
    if (data == null || data === "") return null;
    var out2 = LZString.decompressFromBase64(data);
    if (out2 == null) console.warn("[rpg-save] load backup decompress null for " + key);
    return out2;
};

StorageManager.webStorageBackupExists = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    try { if (window.saveDataManager.Exists(key)) return true; } catch (e) {}
    try { return localStorage.getItem(key) != null; } catch (e2) { return false; }
};

StorageManager.removeWebStorage = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    try { window.saveDataManager.Remove(key); } catch (e) {}
    try { localStorage.removeItem(key); } catch (e2) {}
};

StorageManager.backup = function(savefileId) {
    if (!this.exists(savefileId)) return;
    var data = this.load(savefileId);
    if (data == null) return;
    var compressed = LZString.compressToBase64(data);
    var key = this.webStorageKey(savefileId) + "bak";
    var ok = false;
    try { var r2 = window.saveDataManager.Save(key, compressed); ok = (r2 !== false && r2 !== 0); } catch (e) { ok = false; }
    if (!ok) {
        try { localStorage.setItem(key, compressed); ok = true; } catch (e2) {}
    }
    if (!ok) throw new Error("backup failed: both backends rejected key=" + key);
};

StorageManager.cleanBackup = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    try { window.saveDataManager.Remove(key); } catch (e) {}
    try { localStorage.removeItem(key); } catch (e2) {}
};

StorageManager.restoreBackup = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    var data = null;
    try { data = window.saveDataManager.Load(key); } catch (e) {}
    if (data == null || data === "") {
        try { data = localStorage.getItem(key); } catch (e2) {}
    }
    if (!data) return;
    var decompressed = LZString.decompressFromBase64(data);
    if (decompressed == null) {
        console.warn("[rpg-save] restore skipped: backup decompress returned null for " + key);
        return;
    }
    var origKey = this.webStorageKey(savefileId);
    var writeOk = false;
    try { var r3 = window.saveDataManager.Save(origKey, LZString.compressToBase64(decompressed)); writeOk = (r3 !== false && r3 !== 0); } catch (e) { writeOk = false; }
    if (!writeOk) {
        try { localStorage.setItem(origKey, data); writeOk = true; } catch (e2) {}
    }
    if (!writeOk) {
        console.warn("[rpg-save] restore write failed, backup retained for " + key);
        return;
    }
    this.cleanBackup(savefileId);
};

StorageManager.webStorageExists = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    try { if (window.saveDataManager.Exists(key)) return true; } catch (e) {}
    try { return localStorage.getItem(key) != null; } catch (e2) { return false; }
};
Utils.isMobileDevice = function() {return false;};
StorageManager.backupWebStorage = function(savefileId) {
    if (!this.webStorageExists(savefileId)) return;
    var key = this.webStorageKey(savefileId);
    try {
        var data = this.loadFromWebStorage(savefileId);
        var bak = key + "bak";
        var comp = LZString ? LZString.compressToBase64(data) : data;
        try { window.saveDataManager.Save(bak, comp); } catch (e2) { localStorage.setItem(bak, comp); }
    } catch (e) {}
};
StorageManager.restoreWebStorageBackup = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    var bak = key + "bak";
    if (!this.webStorageBackupExists(savefileId)) return;
    try {
        var d = LZString ? LZString.decompressFromBase64(window.saveDataManager.Load(bak)) : window.saveDataManager.Load(bak);
        if (d !== null) window.saveDataManager.Save(key, LZString ? LZString.compressToBase64(d) : d);
        window.saveDataManager.Remove(bak);
    } catch (e) {}
};
StorageManager.cleanWebStorageBackup = function(savefileId) {
    var bak = this.webStorageKey(savefileId) + "bak";
    try { window.saveDataManager.Remove(bak); } catch (e) { try { localStorage.removeItem(bak); } catch (e2) {} }
};
SceneManager.shouldUseCanvasRenderer = function() {return true;};
Graphics._defaultStretchMode = function() {return true;};
document.body.parentNode.style.overflow = "hidden";
