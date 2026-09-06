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
        // 设置页"PIXI 兼容渲染"开关：强制 Canvas 渲染器 + PIXI legacy 选项。
        // 此前仅设置 legacy 选项、渲染器仍由 autoDetect 决定，开关对渲染路径无效
        window.__tyranorUseLegacyCanvas = true;
        this._rendererType = "canvas";
        console.log("Android legacy renderer enabled.");
        const AndroidLegacyOption = {
            legacy: true
        };
        for (var optkey in AndroidLegacyOption) {
            options[optkey] = AndroidLegacyOption[optkey];
            console.log(`Option added : ${optkey} => ${options[optkey]}`);
        }
    } else {
        window.__tyranorUseLegacyCanvas = false;
        console.log("Android loader has been disabled. (Not a legacy device or running in desktop)");
    }

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

// PC 版 MV 的本地存档文件名是 global.bin / fileN.bin / config.bin（globalId/title 校验
// 也能对上），而 webStorageKey 产出的键（桥经 RpgMakerStorage.resolveFile 对含空格/
// 非 ASCII 键会哈希成 key_sha256.bin）永远读不到 PC 存档（标题页"继续"选项消失）。
// 键名形态有两种（读路径都要兜底；写路径只写游戏自己请求的键，不双写）：
//   原版 MV：'RPG Global' / 'RPG FileN' / 'RPG Config'
//   YEP_SaveCore：'RPG <游戏标题> Global' / 'RPG <游戏标题> FileN' / 'RPG <游戏标题> Config'
StorageManager.pcLocalSaveKey = function(key) {
    if (typeof key !== "string") return null;
    // 前缀必须是 "RPG "（键整体不含路径分隔符/控制字符——桥侧 resolveFile 也会再校验）
    if (!/^RPG /.test(key) || /[\u0000-\u001f/]/.test(key)) return null;
    var m = /Global$/.exec(key);
    if (m) return "global";
    m = /Config$/.exec(key);
    if (m) return "config";
    m = /File(\d+)$/.exec(key);
    if (m) return "file" + Number(m[1]);
    return null;
};
StorageManager.loadFromPcLocalSave = function(key) {
    var pcKey = this.pcLocalSaveKey(key);
    if (!pcKey || !window.saveDataManager) return null;
    try {
        var data = window.saveDataManager.Load(pcKey);
        return (data == null || data === "") ? null : data;
    } catch (e) { return null; }
};

StorageManager.saveToWebStorage = function(savefileId, json) {
    var key = this.webStorageKey(savefileId);
    var data = LZString.compressToBase64(json);
    var ok = false;
    try { var r = window.saveDataManager.Save(key, data); ok = (r === true); } catch (e) { ok = false; }
    // 桥明确返回 Boolean（RpgMakerStorage.write 原子写结果）；非 true 一律视为失败并回退 localStorage
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
        // PC 移植存档兜底：savedata/global.bin 等小写键名
        data = this.loadFromPcLocalSave(key);
    }
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
    try { var r2 = window.saveDataManager.Save(key, compressed); ok = (r2 === true); } catch (e) { ok = false; }
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
    try { var r3 = window.saveDataManager.Save(origKey, LZString.compressToBase64(decompressed)); writeOk = (r3 === true); } catch (e) { writeOk = false; }
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
    // PC 移植存档兜底：global.bin / fileN.bin
    var pcKey = this.pcLocalSaveKey(key);
    if (pcKey) {
        try { if (window.saveDataManager.Exists(pcKey)) return true; } catch (e3) {}
    }
    try { return localStorage.getItem(key) != null; } catch (e2) { return false; }
};
Utils.isMobileDevice = function() {return false;};
StorageManager.backupWebStorage = function(savefileId) {
    if (!this.webStorageExists(savefileId)) return;
    var key = this.webStorageKey(savefileId);
    try {
        var data = this.loadFromWebStorage(savefileId);
        // 读取失败（null/空串）时不得用空备份覆盖已有有效备份（PR review 意见）
        if (data == null || data === "") return;
        var bak = key + "bak";
        var comp = LZString ? LZString.compressToBase64(data) : data;
        var saved = false;
        try { saved = window.saveDataManager.Save(bak, comp) === true; } catch (e2) {}
        if (!saved) { try { localStorage.setItem(bak, comp); } catch (e3) {} }
    } catch (e) {}
};
StorageManager.restoreWebStorageBackup = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    var bak = key + "bak";
    if (!this.webStorageBackupExists(savefileId)) return;
    try {
        var d = LZString ? LZString.decompressFromBase64(window.saveDataManager.Load(bak)) : window.saveDataManager.Load(bak);
        if (!d) return;
        // 仅在恢复写回成功后才删除备份；失败保留备份避免存档丢失（PR review 意见）。
        // 桥与 localStorage 两个回退路径必须写入同一压缩形态：loadFromWebStorage 读取
        // 后统一 decompress，回退写原文会被解压成 null 造成存档不可读（PR review 意见）
        var payload = LZString ? LZString.compressToBase64(d) : d;
        var writeOk = false;
        try { writeOk = window.saveDataManager.Save(key, payload) === true; } catch (e2) {}
        if (!writeOk) { try { localStorage.setItem(key, payload); writeOk = true; } catch (e3) {} }
        if (writeOk) {
            try { window.saveDataManager.Remove(bak); } catch (e4) {}
            try { localStorage.removeItem(bak); } catch (e5) {}
        }
    } catch (e) {}
};
StorageManager.cleanWebStorageBackup = function(savefileId) {
    var bak = this.webStorageKey(savefileId) + "bak";
    try { window.saveDataManager.Remove(bak); } catch (e) { try { localStorage.removeItem(bak); } catch (e2) {} }
};
// MV 1.6.1 核心自身不调用此函数（渲染器由 autoDetectRenderer 决定），但插件可能读取；
// 返回与 _createRenderer 实际选择一致的结果（legacy 开关 = Canvas）
SceneManager.shouldUseCanvasRenderer = function() { return window.__tyranorUseLegacyCanvas === true; };
Graphics._defaultStretchMode = function() {return true;};
if (document.documentElement) document.documentElement.style.overflow = "hidden";
