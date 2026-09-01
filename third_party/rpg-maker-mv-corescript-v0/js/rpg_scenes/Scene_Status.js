//-----------------------------------------------------------------------------
// Scene_Status
//
// The scene class of the status screen.

function Scene_Status() {
    this.initialize.apply(this, arguments);
}

Scene_Status.prototype = Object.create(Scene_MenuBase.prototype);
Scene_Status.prototype.constructor = Scene_Status;

Scene_Status.prototype.initialize = function() {
    Scene_MenuBase.prototype.initialize.call(this);
};

Scene_Status.prototype.create = function() {
    Scene_MenuBase.prototype.create.call(this);
    this._statusWindow = new Window_Status();
    this._statusWindow.setHandler('cancel',   this.popScene.bind(this));
    this._statusWindow.setHandler('pagedown', this.nextActor.bind(this));
    this._statusWindow.setHandler('pageup',   this.previousActor.bind(this));
    this._statusWindow.reserveFaceImages();
    this.addWindow(this._statusWindow);
};

Scene_Status.prototype.start = function() {
    Scene_MenuBase.prototype.start.call(this);
    this.refreshActor();
};

Scene_Status.prototype.refreshActor = function() {
    var actor = this.actor();
    this._statusWindow.setActor(actor);
};

Scene_Status.prototype.onActorChange = function() {
    this.refreshActor();
    this._statusWindow.activate();
};
