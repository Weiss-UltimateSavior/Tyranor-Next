/*:
 * @plugindesc Fail to load images, audio, movies and map data.
 * @author RM CoreScript team
 *
 * @param failImage
 * @desc Probability of image loading failure (0-1)
 * @default 0.5
 *
 * @param failAudio
 * @desc Probability of audio loading failure (0-1)
 * @default 0.5
 *
 * @param failMovie
 * @desc Probability of movie loading failure (0-1)
 * @default 0.5
 *
 * @param failMapData
 * @desc Probability of map data loading failure (0-1)
 * @default 0.5
 */

/*:ja
 * @plugindesc 画像や音声、動画やマップデータの読み込みに失敗します。
 * @author RM CoreScript team
 *
 * @param failImage
 * @desc 画像の読み込みに失敗する確率 (0-1)
 * @default 0.5
 *
 * @param failAudio
 * @desc 音声の読み込みに失敗する確率 (0-1)
 * @default 0.5
 *
 * @param failMovie
 * @desc 動画の読み込みに失敗する確率 (0-1)
 * @default 0.5
 *
 * @param failMapData
 * @desc マップデータ読み込みに失敗する確率 (0-1)
 * @default 0.5
 */

(function() {
    function toNumber(str, def) {
        return isNaN(str) ? def : +(str || def);
    }

    var parameters = PluginManager.parameters('Debug_FailLoading');
    var failImage = toNumber(parameters['failImage'], 0.5);
    var failAudio = toNumber(parameters['failAudio'], 0.5);
    var failMovie = toNumber(parameters['failMovie'], 0.5);
    var failMapData = toNumber(parameters['failMapData'], 0.5);

    var _Bitmap_onLoad = Bitmap.prototype._onLoad;
    Bitmap.prototype._onLoad = function() {
        if (Math.random() < failImage) {
            this._errorListener();
        } else {
            _Bitmap_onLoad.apply(this, arguments);
        }
    };

    WebAudio.prototype._load = function(url) {
        if (WebAudio._context) {
            var xhr = new XMLHttpRequest();
            if(Decrypter.hasEncryptedAudio) url = Decrypter.extToEncryptExt(url);
            xhr.open('GET', url);
            xhr.responseType = 'arraybuffer';
            xhr.onload = function() {
                if (Math.random() < failAudio) {
                    xhr.onerror();
                } else if (xhr.status < 400) {
                    this._onXhrLoad(xhr);
                }
            }.bind(this);
            xhr.onerror = this._loader;
            xhr.send();
        }
    };

    var _Graphics_onVideoLoad = Graphics._onVideoLoad;
    Graphics._onVideoLoad = function() {
        if (Math.random() < failMovie) {
            this._video.onerror();
        } else {
            _Graphics_onVideoLoad.apply(this, arguments);
        }
    };

    DataManager.loadDataFile = function(name, src) {
        var xhr = new XMLHttpRequest();
        var url = 'data/' + src;
        xhr.open('GET', url);
        xhr.overrideMimeType('application/json');
        xhr.onload = function() {
            if (name === '$dataMap' && Math.random() < failMapData) {
                xhr.onerror();
            } else if (xhr.status < 400) {
                window[name] = JSON.parse(xhr.responseText);
                DataManager.onLoad(window[name]);
            }
        };
        xhr.onerror = this._mapLoader || function() {
            DataManager._errorUrl = DataManager._errorUrl || url;
        };
        window[name] = null;
        xhr.send();
    };
})();