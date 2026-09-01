function ImageCache(){
    this.initialize.apply(this, arguments);
}

ImageCache.limit = 10 * 1000 * 1000;

ImageCache.prototype.initialize = function(){
    this._items = {};
};

ImageCache.prototype.add = function(key, value){
    this._items[key] = {
        bitmap: value,
        touch: Date.now(),
        key: key
    };

    this._truncateCache();
};

ImageCache.prototype.get = function(key){
    if(this._items[key]){
        var item = this._items[key];
        item.touch = Date.now();
        return item.bitmap;
    }

    return null;
};

ImageCache.prototype.reserve = function(key, value, reservationId){
    if(!this._items[key]){
        this._items[key] = {
            bitmap: value,
            touch: Date.now(),
            key: key
        };
    }

    this._items[key].reservationId = reservationId;
};

ImageCache.prototype.releaseReservation = function(reservationId){
    var items = this._items;

    Object.keys(items)
        .map(function(key){return items[key];})
        .forEach(function(item){
            if(item.reservationId === reservationId){
                delete item.reservationId;
            }
        });
};

ImageCache.prototype._truncateCache = function(){
    var items = this._items;
    var sizeLeft = ImageCache.limit;

    Object.keys(items).map(function(key){
        return items[key];
    }).sort(function(a, b){
        return b.touch - a.touch;
    }).forEach(function(item){
        if(sizeLeft > 0 || this._mustBeHeld(item)){
            var bitmap = item.bitmap;
            sizeLeft -= bitmap.width * bitmap.height;
        }else{
            delete items[item.key];
        }
    }.bind(this));
};

ImageCache.prototype._mustBeHeld = function(item){
    // request only is weak so It's purgeable
    if(item.bitmap.isRequestOnly()) return false;
    // reserved item must be held
    if(item.reservationId) return true;
    // not ready bitmap must be held (because of checking isReady())
    if(!item.bitmap.isReady()) return true;
    // then the item may purgeable
    return false;
};

ImageCache.prototype.isReady = function(){
    var items = this._items;
    return !Object.keys(items).some(function(key){
        return !items[key].bitmap.isRequestOnly() && !items[key].bitmap.isReady();
    });
};

ImageCache.prototype.getErrorBitmap = function(){
    var items = this._items;
    var bitmap = null;
    if(Object.keys(items).some(function(key){
            if(items[key].bitmap.isError()){
                bitmap = items[key].bitmap;
                return true;
            }
            return false;
        })) {
        return bitmap;
    }

    return null;
};