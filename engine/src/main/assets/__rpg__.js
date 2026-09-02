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
SceneManager.shouldUseCanvasRenderer = function() {return true;};
Graphics._defaultStretchMode = function() {return true;};
document.body.parentNode.style.overflow = "hidden";
