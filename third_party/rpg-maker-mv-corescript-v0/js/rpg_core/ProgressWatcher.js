function ProgressWatcher(){
    throw new Error('This is a static class');
}

ProgressWatcher.initialize = function(){
    this.clearProgress();
    ImageManager.setCreationHook(this._bitmapListener.bind(this));
    AudioManager.setCreationHook(this._audioListener.bind(this));
};

ProgressWatcher._bitmapListener = function(bitmap){
    this._countLoading++;
    bitmap.addLoadListener(function(){
        this._countLoaded++;
        this._progressListener(this._countLoaded, this._countLoading);
    }.bind(this));
};

ProgressWatcher._audioListener = function(audio){
    this._countLoading++;
    audio.addLoadListener(function(){
        this._countLoaded++;
        this._progressListener(this._countLoaded, this._countLoading);
    }.bind(this));
};

ProgressWatcher.setProgressListener = function(progressListener){
    this._progressListener = progressListener;
};

ProgressWatcher.clearProgress = function(){
    this._countLoading = 0;
    this._countLoaded = 0;
};

ProgressWatcher.truncateProgress = function(){
    if(this._countLoaded){
        this._countLoading -= this._countLoaded;
        this._countLoaded = 0;
    }
};