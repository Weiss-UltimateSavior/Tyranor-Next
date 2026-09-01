//-----------------------------------------------------------------------------
// SceneManager
//
// The static class that manages scene transitions.

function SceneManager() {
    throw new Error('This is a static class');
}

/*
 * Gets the current time in ms without on iOS Safari.
 * @private
 */
SceneManager._getTimeInMsWithoutMobileSafari = function() {
    return performance.now();
};

SceneManager._scene             = null;
SceneManager._nextScene         = null;
SceneManager._stack             = [];
SceneManager._stopped           = false;
SceneManager._sceneStarted      = false;
SceneManager._exiting           = false;
SceneManager._previousClass     = null;
SceneManager._backgroundBitmap  = null;
SceneManager._screenWidth       = 816;
SceneManager._screenHeight      = 624;
SceneManager._boxWidth          = 816;
SceneManager._boxHeight         = 624;
SceneManager._deltaTime = 1.0 / 60.0;
if (!Utils.isMobileSafari()) SceneManager._currentTime = SceneManager._getTimeInMsWithoutMobileSafari();
SceneManager._accumulator = 0.0;
SceneManager._frameCount = 0;

SceneManager.run = function(sceneClass) {
    try {
        this.initialize();
        this.goto(sceneClass);
        this.requestUpdate();
    } catch (e) {
        this.catchException(e);
    }
};

SceneManager.initialize = function() {
    this.initProgressWatcher();
    this.initGraphics();
    this.checkFileAccess();
    this.initAudio();
    this.initInput();
    this.initNwjs();
    this.checkPluginErrors();
    this.setupErrorHandlers();
};

SceneManager.initProgressWatcher = function(){
    ProgressWatcher.initialize();
};

SceneManager.initGraphics = function() {
    var type = this.preferableRendererType();
    Graphics.initialize(this._screenWidth, this._screenHeight, type);
    Graphics.boxWidth = this._boxWidth;
    Graphics.boxHeight = this._boxHeight;
    Graphics.setLoadingImage('img/system/Loading.png');
    if (Utils.isOptionValid('showfps')) {
        Graphics.showFps();
    }
    if (type === 'webgl') {
        this.checkWebGL();
    }
};

SceneManager.preferableRendererType = function() {
    if (Utils.isOptionValid('canvas')) {
        return 'canvas';
    } else if (Utils.isOptionValid('webgl')) {
        return 'webgl';
    } else {
        return 'auto';
    }
};

SceneManager.shouldUseCanvasRenderer = function() {
    return Utils.isMobileDevice();
};

SceneManager.checkWebGL = function() {
    if (!Graphics.hasWebGL()) {
        throw new Error('Your browser does not support WebGL.');
    }
};

SceneManager.checkFileAccess = function() {
    if (!Utils.canReadGameFiles()) {
        throw new Error('Your browser does not allow to read local files.');
    }
};

SceneManager.initAudio = function() {
    var noAudio = Utils.isOptionValid('noaudio');
    if (!WebAudio.initialize(noAudio) && !noAudio) {
        throw new Error('Your browser does not support Web Audio API.');
    }
};

SceneManager.initInput = function() {
    Input.initialize();
    TouchInput.initialize();
};

SceneManager.initNwjs = function() {
    if (Utils.isNwjs()) {
        var gui = require('nw.gui');
        var win = gui.Window.get();
        if (process.platform === 'darwin' && !win.menu) {
            var menubar = new gui.Menu({ type: 'menubar' });
            var option = { hideEdit: true, hideWindow: true };
            menubar.createMacBuiltin('Game', option);
            win.menu = menubar;
        }
    }
};

SceneManager.checkPluginErrors = function() {
    PluginManager.checkErrors();
};

SceneManager.setupErrorHandlers = function() {
    window.addEventListener('error', this.onError.bind(this));
    document.addEventListener('keydown', this.onKeyDown.bind(this));
};

SceneManager.frameCount = function() {
    return this._frameCount;
};

SceneManager.setFrameCount = function(frameCount) {
    this._frameCount = frameCount;
};

SceneManager.resetFrameCount = function() {
    this._frameCount = 0;
};

SceneManager.requestUpdate = function() {
    if (!this._stopped) {
        requestAnimationFrame(this.update.bind(this));
    }
};

SceneManager.update = function() {
    try {
        this.tickStart();
        if (Utils.isMobileSafari()) {
            this.updateInputData();
        }
        this.updateManagers();
        this.updateMain();
        this.tickEnd();
    } catch (e) {
        this.catchException(e);
    }
};

SceneManager.terminate = function() {
    window.close();
};

SceneManager.onError = function(e) {
    console.error(e.message);
    if (e.filename || e.lineno) {
        console.error(e.filename, e.lineno);
        try {
            this.stop();
            Graphics.printError('Error', e.message);
            AudioManager.stopAll();
        } catch (e2) {
        }
    }
};

SceneManager.onKeyDown = function(event) {
    if (!event.ctrlKey && !event.altKey) {
        switch (event.keyCode) {
        case 116:   // F5
            if (Utils.isNwjs()) {
                location.reload();
            }
            break;
        case 119:   // F8
            if (Utils.isNwjs() && Utils.isOptionValid('test')) {
                require('nw.gui').Window.get().showDevTools();
            }
            break;
        }
    }
};

SceneManager.catchException = function(e) {
    if (e instanceof Error) {
        Graphics.printError(e.name, e.message);
        Graphics.printErrorDetail(e);
        console.error(e.stack);
    } else {
        Graphics.printError('UnknownError', e);
    }
    AudioManager.stopAll();
    this.stop();
};

SceneManager.tickStart = function() {
    Graphics.tickStart();
};

SceneManager.tickEnd = function() {
    Graphics.tickEnd();
};

SceneManager.updateInputData = function() {
    Input.update();
    TouchInput.update();
};

SceneManager.updateMain = function() {
    if (Utils.isMobileSafari()) {
        this.changeScene();
        this.updateScene();
    } else {
        var newTime = this._getTimeInMsWithoutMobileSafari();
        if (this._currentTime === undefined) { this._currentTime = newTime; }
        var fTime = (newTime - this._currentTime) / 1000;
        if (fTime > 0.25) { fTime = 0.25; }
        this._currentTime = newTime;
        this._accumulator += fTime;
        while (this._accumulator >= this._deltaTime) {
            this.updateInputData();
            this.changeScene();
            this.updateScene();
            this._accumulator -= this._deltaTime;
        }
    }
    this.renderScene();
    this.requestUpdate();
};

SceneManager.updateManagers = function() {
    ImageManager.update();
};

SceneManager.changeScene = function() {
    if (this.isSceneChanging() && !this.isCurrentSceneBusy()) {
        if (this._scene) {
            this._scene.terminate();
            this._scene.detachReservation();
            this._previousClass = this._scene.constructor;
        }
        this._scene = this._nextScene;
        if (this._scene) {
            this._scene.attachReservation();
            this._scene.create();
            this._nextScene = null;
            this._sceneStarted = false;
            this.onSceneCreate();
        }
        if (this._exiting) {
            this.terminate();
        }
    }
};

SceneManager.updateScene = function() {
    if (this._scene) {
        if (!this._sceneStarted && this._scene.isReady()) {
            this._scene.start();
            this._sceneStarted = true;
            this.onSceneStart();
        }
        if (this.isCurrentSceneStarted()) {
            this.updateFrameCount();
            this._scene.update();
        }
    }
};

SceneManager.renderScene = function() {
    if (this.isCurrentSceneStarted()) {
        Graphics.render(this._scene);
    } else if (this._scene) {
        this.onSceneLoading();
    }
};

SceneManager.updateFrameCount = function() {
    this._frameCount++;
};

SceneManager.onSceneCreate = function() {
    Graphics.startLoading();
};

SceneManager.onSceneStart = function() {
    Graphics.endLoading();
};

SceneManager.onSceneLoading = function() {
    Graphics.updateLoading();
};

SceneManager.isSceneChanging = function() {
    return this._exiting || !!this._nextScene;
};

SceneManager.isCurrentSceneBusy = function() {
    return this._scene && this._scene.isBusy();
};

SceneManager.isCurrentSceneStarted = function() {
    return this._scene && this._sceneStarted;
};

SceneManager.isNextScene = function(sceneClass) {
    return this._nextScene && this._nextScene.constructor === sceneClass;
};

SceneManager.isPreviousScene = function(sceneClass) {
    return this._previousClass === sceneClass;
};

SceneManager.goto = function(sceneClass) {
    if (sceneClass) {
        this._nextScene = new sceneClass();
    }
    if (this._scene) {
        this._scene.stop();
    }
};

SceneManager.push = function(sceneClass) {
    this._stack.push(this._scene.constructor);
    this.goto(sceneClass);
};

SceneManager.pop = function() {
    if (this._stack.length > 0) {
        this.goto(this._stack.pop());
    } else {
        this.exit();
    }
};

SceneManager.exit = function() {
    this.goto(null);
    this._exiting = true;
};

SceneManager.clearStack = function() {
    this._stack = [];
};

SceneManager.stop = function() {
    this._stopped = true;
};

SceneManager.prepareNextScene = function() {
    this._nextScene.prepare.apply(this._nextScene, arguments);
};

SceneManager.snap = function() {
    return Bitmap.snap(this._scene);
};

SceneManager.snapForBackground = function() {
    this._backgroundBitmap = this.snap();
    this._backgroundBitmap.blur();
};

SceneManager.backgroundBitmap = function() {
    return this._backgroundBitmap;
};

SceneManager.resume = function() {
    this._stopped = false;
    this.requestUpdate();
    if (!Utils.isMobileSafari()) {
        this._currentTime = this._getTimeInMsWithoutMobileSafari();
        this._accumulator = 0;
    }
};
