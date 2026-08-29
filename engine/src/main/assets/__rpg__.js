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
    window.saveDataManager.Save(key, data);
};

StorageManager.loadFromWebStorage = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    return LZString.decompressFromBase64(window.saveDataManager.Load(key));
};

StorageManager.loadFromWebStorageBackup = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    return LZString.decompressFromBase64(window.saveDataManager.Load(key));
};

StorageManager.webStorageBackupExists = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    return window.saveDataManager.Exists(key);
};

StorageManager.webStorageExists = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    return window.saveDataManager.Exists(key);
};
Utils.isMobileDevice = function() {return false;};
StorageManager.removeWebStorage = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    try { window.saveDataManager.Remove(key); } catch (e) { try { localStorage.removeItem(key); } catch (e2) {} }
};
StorageManager.webStorageBackupExists = StorageManager.webStorageBackupExists || function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    try { return window.saveDataManager.Exists(key); } catch (e) { return false; }
};
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
