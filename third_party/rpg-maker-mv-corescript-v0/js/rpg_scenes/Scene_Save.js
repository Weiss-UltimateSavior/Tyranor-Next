//-----------------------------------------------------------------------------
// Scene_Save
//
// The scene class of the save screen.

function Scene_Save() {
    this.initialize.apply(this, arguments);
}

Scene_Save.prototype = Object.create(Scene_File.prototype);
Scene_Save.prototype.constructor = Scene_Save;

Scene_Save.prototype.initialize = function() {
    Scene_File.prototype.initialize.call(this);
};

Scene_Save.prototype.mode = function() {
    return 'save';
};

Scene_Save.prototype.helpWindowText = function() {
    return TextManager.saveMessage;
};

Scene_Save.prototype.firstSavefileIndex = function() {
    return DataManager.lastAccessedSavefileId() - 1;
};

Scene_Save.prototype.onSavefileOk = function() {
    if (DataManager.isAutoSaveFileId(this.savefileId())) {
        this.onSaveFailure();
        return;
    }
    Scene_File.prototype.onSavefileOk.call(this);
    $gameSystem.onBeforeSave();
    if (DataManager.saveGame(this.savefileId())) {
        this.onSaveSuccess();
    } else {
        this.onSaveFailure();
    }
};

Scene_Save.prototype.onSaveSuccess = function() {
    SoundManager.playSave();
	StorageManager.cleanBackup(this.savefileId());
    this.popScene();
};

Scene_Save.prototype.onSaveFailure = function() {
    SoundManager.playBuzzer();
    this.activateListWindow();
};
