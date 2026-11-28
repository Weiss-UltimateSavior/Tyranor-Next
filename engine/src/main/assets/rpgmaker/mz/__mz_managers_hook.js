// 追加到 js/rmmz_managers.js 末尾（由 RpgMakerLocalHttpServer 的 scriptAppends 拼接）。
//
// 存档方式**不动**：引擎侧继续存明文 JSON 走 saveDataManager 桥。与 PC/JoiPlay 的
// 标准格式（zlib 容器 deflate 流）之间的转码放在转换层（RpgSaveFormat.encodeMzPayload /
// decodeMzPayload），只在导出/导入那一刻发生——见 docs/RPGMaker-MZ-移植实施方案.md 阶段 B。
(function () {
    "use strict";

    if (!window.saveDataManager) return;

    StorageManager.saveObject = function (saveName, object) {
        return this.objectToJson(object)
            .then(function (json) {
                return window.saveDataManager.Save(saveName, json);
            });
    };

    StorageManager.loadObject = function (saveName) {
        return new Promise(function (resolve, reject) {
            var data = window.saveDataManager.Load(saveName);
            if (data) {
                resolve(data);
            } else {
                reject(new Error("Savefile not found"));
            }
        }).then(function (json) {
            return StorageManager.jsonToObject(json);
        });
    };

    StorageManager.exists = function (saveName) {
        return window.saveDataManager.Exists(saveName);
    };

    // MZ 原实现走 window.top.document.hasFocus()；WebView 里恒为 true，钉死避免 iframe 边界差异。
    if (window.SceneManager) {
        SceneManager.isGameActive = function () {
            return true;
        };
    }
})();