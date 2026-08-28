/*:
 * @plugindesc Show memory usage.
 * @author RM CoreScript team
 *
 * @help
 * This plugin shows memory related usages.
 * usedSize: the total size of the image actually being used in the game now.
 *           If this value exceeds about 50MPix, some mobile devices may crash.
 * cachedSize: cached size in MPixel
 * cacheLimit: the upper limit of cached size (It doesn't matter if cachedSize exceeds just a little)
 * totalCount: count of cached Bitmaps
 * requestCount: count of requested Bitmaps
 * reservedCount: count of reserved Bitmaps
 *
 * All number should be stable.
 * If you find that It increases gradually after reaching limit, Please report to team!!
 *
 * @param Max Pixels In MPix
 * @desc decide the value of "cacheLimit" displayed in the upper right
 * @default 10
 *
 */

/*:ja
 * @plugindesc メモリ使用量を表示します。
 * @author RM CoreScript team
 *
 * @help
 * メモリ使用量を表示します。
 * usedSize: 今ゲーム中で実際に使用されている画像の合計サイズです。
 * 　　　　　この値が50MPix辺りを越えると、一部モバイルデバイスがクラッシュする可能性があります。
 * cachedSize: キャッシュされている使用量です。単位はMPixelです。
 * cacheLimit: キャッシュできる上限値です。cachedSizeの方が上回ることもありますが、少しだけなら正常です。
 * totalCount: キャッシュされているBitmapの数です
 * requestCount: 先行読み込みの数です
 * reservedCount: 予約の数です
 *
 * もしプラグインパラメータのリミットを超えてもずっと数値が上がり
 * 続ける場合は、教えていただけると幸いです。
 *
 * @param Max Pixels In MPix
 * @desc 右上に表示されているcacheLimitの値を決めます
 * @default 10
 *
 */

(function(){
    var parameters = PluginManager.parameters('Debug_ReportMemory');
    var pixels = toNumber(parameters['Max Pixels In MPix'], 10);
    var communityPixels = PluginManager.parameters('Community_Basic')['cacheLimit'];

    if (communityPixels) {
        pixels = toNumber(communityPixels, 10);
        console.log('Community_Basic plugin has been installed, so it overwrites the setting value of cacheLimit. ' +
        'cacheLimit: ' + pixels + 'MPix');
    }

    ImageCache.limit = pixels * 1000 * 1000;

    var div = document.createElement('div');
    div.style.backgroundColor = 'AliceBlue';
    div.style.position = 'fixed';
    div.style.right = 0;
    div.style.top = 0;
    document.body.appendChild(div);

    function toNumber(str, def) {
        return isNaN(str) ? def : +(str || def);
    }

    function toMPix(size) {
        return Math.floor(size / 1000) / 1000 + 'MPix';
    }

    function usedSize(item, bitmaps) {
        var totalSize = 0;
        if (item && item.bitmap && !bitmaps.contains(item.bitmap)) {
            totalSize += item.bitmap.width * item.bitmap.height;
            bitmaps.push(item.bitmap);
        }
        if (item && item.children) {
            totalSize += item.children.reduce(function(sum, child) {
                return sum + usedSize(child, bitmaps);
            }, 0);
        }
        return totalSize;
    }

    function updateInfo(){
        var content = 'usedSize: ' + toMPix(usedSize(SceneManager._scene, [])) + '<br>';
        content += 'cachedSize: ' + toMPix(ImageManager._imageCache._getSize()) + '<br>';
        content += '(cacheLimit: ' + pixels + 'MPix)<br>';
        content += 'totalCount: ' + ImageManager._imageCache._countBitmap() + '<br>';
        content += 'requestCount: ' + ImageManager._imageCache._countRequest() + '<br>';
        content += 'reservedCount: ' + ImageManager._imageCache._countReserved() + '<br>';

        if (div.innerHTML !== content) div.innerHTML = content;
        div.style.zIndex = 11;
    }

    var ImageManager_update = ImageManager.update;
    ImageManager.update = function(){
        ImageManager_update.call(this);
        updateInfo();
    };

    ImageCache.prototype._getSize = function(){
        var totalSize = 0;
        var items = this._items;

        Object.keys(items).forEach(function(key){
            var bitmap = items[key].bitmap;
            totalSize += bitmap.width * bitmap.height;
        });

        return totalSize;
    };

    ImageCache.prototype._countBitmap = function(){
        return Object.keys(this._items).length;
    };

    ImageCache.prototype._countRequest = function(){
        var items = this._items;

        return Object.keys(this._items)
            .map(function(key){
                return items[key].bitmap;
            }).filter(function(bitmap){
                return bitmap.isRequestOnly();
            }).length;
    };

    ImageCache.prototype._countReserved = function(){
        var items = this._items;

        return Object.keys(this._items)
            .map(function(key){
                return items[key].reservationId;
            }).filter(function(id){
                return id;
            }).length;
    };
})();