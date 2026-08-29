//-----------------------------------------------------------------------------
/**
 * The static class that handles resource loading.
 *
 * @class ResourceHandler
 */
function ResourceHandler() {
    throw new Error('This is a static class');
}

ResourceHandler._reloaders = [];
ResourceHandler._defaultRetryInterval = [500, 1000, 3000];

ResourceHandler.createLoader = function(url, retryMethod, resignMethod, retryInterval) {
    retryInterval = retryInterval || this._defaultRetryInterval;
    var reloaders = this._reloaders;
    var retryCount = 0;
    return function() {
        if (retryCount < retryInterval.length) {
            setTimeout(retryMethod, retryInterval[retryCount]);
            retryCount++;
        } else {
            if (resignMethod) {
                resignMethod();
            }
            if (url) {
                if (reloaders.length === 0) {
                    Graphics.printLoadingError(url);
                    SceneManager.stop();
                }
                reloaders.push(function() {
                    retryCount = 0;
                    retryMethod();
                });
            }
        }
    };
};

ResourceHandler.exists = function() {
    return this._reloaders.length > 0;
};

ResourceHandler.retry = function() {
    if (this._reloaders.length > 0) {
        Graphics.eraseLoadingError();
        SceneManager.resume();
        this._reloaders.forEach(function(reloader) {
            reloader();
        });
        this._reloaders.length = 0;
    }
};