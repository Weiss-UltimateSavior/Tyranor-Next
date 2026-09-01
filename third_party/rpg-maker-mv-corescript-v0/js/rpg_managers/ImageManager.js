//-----------------------------------------------------------------------------
// ImageManager
//
// The static class that loads images, creates bitmap objects and retains them.

function ImageManager() {
    throw new Error('This is a static class');
}

ImageManager.cache = new CacheMap(ImageManager);

ImageManager._imageCache = new ImageCache();
ImageManager._requestQueue = new RequestQueue();
ImageManager._systemReservationId = Utils.generateRuntimeId();

ImageManager._generateCacheKey = function(path, hue){
    return  path + ':' + hue;
};

ImageManager.loadAnimation = function(filename, hue) {
    return this.loadBitmap('img/animations/', filename, hue, true);
};

ImageManager.loadBattleback1 = function(filename, hue) {
    return this.loadBitmap('img/battlebacks1/', filename, hue, true);
};

ImageManager.loadBattleback2 = function(filename, hue) {
    return this.loadBitmap('img/battlebacks2/', filename, hue, true);
};

ImageManager.loadEnemy = function(filename, hue) {
    return this.loadBitmap('img/enemies/', filename, hue, true);
};

ImageManager.loadCharacter = function(filename, hue) {
    return this.loadBitmap('img/characters/', filename, hue, false);
};

ImageManager.loadFace = function(filename, hue) {
    return this.loadBitmap('img/faces/', filename, hue, true);
};

ImageManager.loadParallax = function(filename, hue) {
    return this.loadBitmap('img/parallaxes/', filename, hue, true);
};

ImageManager.loadPicture = function(filename, hue) {
    return this.loadBitmap('img/pictures/', filename, hue, true);
};

ImageManager.loadSvActor = function(filename, hue) {
    return this.loadBitmap('img/sv_actors/', filename, hue, false);
};

ImageManager.loadSvEnemy = function(filename, hue) {
    return this.loadBitmap('img/sv_enemies/', filename, hue, true);
};

ImageManager.loadSystem = function(filename, hue) {
    return this.loadBitmap('img/system/', filename, hue, false);
};

ImageManager.loadTileset = function(filename, hue) {
    return this.loadBitmap('img/tilesets/', filename, hue, false);
};

ImageManager.loadTitle1 = function(filename, hue) {
    return this.loadBitmap('img/titles1/', filename, hue, true);
};

ImageManager.loadTitle2 = function(filename, hue) {
    return this.loadBitmap('img/titles2/', filename, hue, true);
};

ImageManager.loadBitmap = function(folder, filename, hue, smooth) {
    if (filename) {
        var path = folder + encodeURIComponent(filename) + '.png';
        var bitmap = this.loadNormalBitmap(path, hue || 0);
        bitmap.smooth = smooth;
        return bitmap;
    } else {
        return this.loadEmptyBitmap();
    }
};

ImageManager.loadEmptyBitmap = function() {
    var empty = this._imageCache.get('empty');
    if(!empty){
        empty = new Bitmap();
        this._imageCache.add('empty', empty);
        this._imageCache.reserve('empty', empty, this._systemReservationId);
    }

    return empty;
};

ImageManager.loadNormalBitmap = function(path, hue) {
    var key = this._generateCacheKey(path, hue);
    var bitmap = this._imageCache.get(key);
    if (!bitmap) {
        bitmap = Bitmap.load(path);
        this._callCreationHook(bitmap);

        bitmap.addLoadListener(function() {
            bitmap.rotateHue(hue);
        });
        this._imageCache.add(key, bitmap);
    }else if(!bitmap.isReady()){
        bitmap.decode();
    }

    return bitmap;
};

ImageManager.clear = function() {
    this._imageCache = new ImageCache();
};

ImageManager.isReady = function() {
    return this._imageCache.isReady();
};

ImageManager.isObjectCharacter = function(filename) {
    var sign = filename.match(/^[\!\$]+/);
    return sign && sign[0].contains('!');
};

ImageManager.isBigCharacter = function(filename) {
    var sign = filename.match(/^[\!\$]+/);
    return sign && sign[0].contains('$');
};

ImageManager.isZeroParallax = function(filename) {
    return filename.charAt(0) === '!';
};


ImageManager.reserveAnimation = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/animations/', filename, hue, true, reservationId);
};

ImageManager.reserveBattleback1 = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/battlebacks1/', filename, hue, true, reservationId);
};

ImageManager.reserveBattleback2 = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/battlebacks2/', filename, hue, true, reservationId);
};

ImageManager.reserveEnemy = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/enemies/', filename, hue, true, reservationId);
};

ImageManager.reserveCharacter = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/characters/', filename, hue, false, reservationId);
};

ImageManager.reserveFace = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/faces/', filename, hue, true, reservationId);
};

ImageManager.reserveParallax = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/parallaxes/', filename, hue, true, reservationId);
};

ImageManager.reservePicture = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/pictures/', filename, hue, true, reservationId);
};

ImageManager.reserveSvActor = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/sv_actors/', filename, hue, false, reservationId);
};

ImageManager.reserveSvEnemy = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/sv_enemies/', filename, hue, true, reservationId);
};

ImageManager.reserveSystem = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/system/', filename, hue, false, reservationId || this._systemReservationId);
};

ImageManager.reserveTileset = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/tilesets/', filename, hue, false, reservationId);
};

ImageManager.reserveTitle1 = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/titles1/', filename, hue, true, reservationId);
};

ImageManager.reserveTitle2 = function(filename, hue, reservationId) {
    return this.reserveBitmap('img/titles2/', filename, hue, true, reservationId);
};

ImageManager.reserveBitmap = function(folder, filename, hue, smooth, reservationId) {
    if (filename) {
        var path = folder + encodeURIComponent(filename) + '.png';
        var bitmap = this.reserveNormalBitmap(path, hue || 0, reservationId || this._defaultReservationId);
        bitmap.smooth = smooth;
        return bitmap;
    } else {
        return this.loadEmptyBitmap();
    }
};

ImageManager.reserveNormalBitmap = function(path, hue, reservationId){
    var bitmap = this.loadNormalBitmap(path, hue);
    this._imageCache.reserve(this._generateCacheKey(path, hue), bitmap, reservationId);

    return bitmap;
};

ImageManager.releaseReservation = function(reservationId){
    this._imageCache.releaseReservation(reservationId);
};

ImageManager.setDefaultReservationId = function(reservationId){
    this._defaultReservationId = reservationId;
};


ImageManager.requestAnimation = function(filename, hue) {
    return this.requestBitmap('img/animations/', filename, hue, true);
};

ImageManager.requestBattleback1 = function(filename, hue) {
    return this.requestBitmap('img/battlebacks1/', filename, hue, true);
};

ImageManager.requestBattleback2 = function(filename, hue) {
    return this.requestBitmap('img/battlebacks2/', filename, hue, true);
};

ImageManager.requestEnemy = function(filename, hue) {
    return this.requestBitmap('img/enemies/', filename, hue, true);
};

ImageManager.requestCharacter = function(filename, hue) {
    return this.requestBitmap('img/characters/', filename, hue, false);
};

ImageManager.requestFace = function(filename, hue) {
    return this.requestBitmap('img/faces/', filename, hue, true);
};

ImageManager.requestParallax = function(filename, hue) {
    return this.requestBitmap('img/parallaxes/', filename, hue, true);
};

ImageManager.requestPicture = function(filename, hue) {
    return this.requestBitmap('img/pictures/', filename, hue, true);
};

ImageManager.requestSvActor = function(filename, hue) {
    return this.requestBitmap('img/sv_actors/', filename, hue, false);
};

ImageManager.requestSvEnemy = function(filename, hue) {
    return this.requestBitmap('img/sv_enemies/', filename, hue, true);
};

ImageManager.requestSystem = function(filename, hue) {
    return this.requestBitmap('img/system/', filename, hue, false);
};

ImageManager.requestTileset = function(filename, hue) {
    return this.requestBitmap('img/tilesets/', filename, hue, false);
};

ImageManager.requestTitle1 = function(filename, hue) {
    return this.requestBitmap('img/titles1/', filename, hue, true);
};

ImageManager.requestTitle2 = function(filename, hue) {
    return this.requestBitmap('img/titles2/', filename, hue, true);
};

ImageManager.requestBitmap = function(folder, filename, hue, smooth) {
    if (filename) {
        var path = folder + encodeURIComponent(filename) + '.png';
        var bitmap = this.requestNormalBitmap(path, hue || 0);
        bitmap.smooth = smooth;
        return bitmap;
    } else {
        return this.loadEmptyBitmap();
    }
};

ImageManager.requestNormalBitmap = function(path, hue){
    var key = this._generateCacheKey(path, hue);
    var bitmap = this._imageCache.get(key);
    if(!bitmap){
        bitmap = Bitmap.request(path);
        this._callCreationHook(bitmap);

        bitmap.addLoadListener(function(){
            bitmap.rotateHue(hue);
        });
        this._imageCache.add(key, bitmap);
        this._requestQueue.enqueue(key, bitmap);
    }else{
        this._requestQueue.raisePriority(key);
    }

    return bitmap;
};

ImageManager.update = function(){
    this._requestQueue.update();
};

ImageManager.clearRequest = function(){
    this._requestQueue.clear();
};

ImageManager.setCreationHook = function(hook){
    this._creationHook = hook;
};

ImageManager._callCreationHook = function(bitmap){
    if(this._creationHook) this._creationHook(bitmap);
};