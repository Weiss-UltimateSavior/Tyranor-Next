//-----------------------------------------------------------------------------
// Game_System
//
// The game object class for the system data.

function Game_System() {
    this.initialize.apply(this, arguments);
}

Game_System.prototype.initialize = function() {
    this._saveEnabled = true;
    this._menuEnabled = true;
    this._encounterEnabled = true;
    this._formationEnabled = true;
    this._battleCount = 0;
    this._winCount = 0;
    this._escapeCount = 0;
    this._saveCount = 0;
    this._versionId = 0;
    this._framesOnSave = 0;
    this._sceneFramesOnSave = 0;
    this._bgmOnSave = null;
    this._bgsOnSave = null;
    this._windowTone = null;
    this._battleBgm = null;
    this._victoryMe = null;
    this._defeatMe = null;
    this._savedBgm = null;
    this._walkingBgm = null;
};

Game_System.prototype.isJapanese = function() {
    return $dataSystem.locale.match(/^ja/);
};

Game_System.prototype.isChinese = function() {
    return $dataSystem.locale.match(/^zh/);
};

Game_System.prototype.isKorean = function() {
    return $dataSystem.locale.match(/^ko/);
};

Game_System.prototype.isCJK = function() {
    return $dataSystem.locale.match(/^(ja|zh|ko)/);
};

Game_System.prototype.isRussian = function() {
    return $dataSystem.locale.match(/^ru/);
};

Game_System.prototype.isSideView = function() {
    return $dataSystem.optSideView;
};

Game_System.prototype.isSaveEnabled = function() {
    return this._saveEnabled;
};

Game_System.prototype.disableSave = function() {
    this._saveEnabled = false;
};

Game_System.prototype.enableSave = function() {
    this._saveEnabled = true;
};

Game_System.prototype.isMenuEnabled = function() {
    return this._menuEnabled;
};

Game_System.prototype.disableMenu = function() {
    this._menuEnabled = false;
};

Game_System.prototype.enableMenu = function() {
    this._menuEnabled = true;
};

Game_System.prototype.isEncounterEnabled = function() {
    return this._encounterEnabled;
};

Game_System.prototype.disableEncounter = function() {
    this._encounterEnabled = false;
};

Game_System.prototype.enableEncounter = function() {
    this._encounterEnabled = true;
};

Game_System.prototype.isFormationEnabled = function() {
    return this._formationEnabled;
};

Game_System.prototype.disableFormation = function() {
    this._formationEnabled = false;
};

Game_System.prototype.enableFormation = function() {
    this._formationEnabled = true;
};

Game_System.prototype.battleCount = function() {
    return this._battleCount;
};

Game_System.prototype.winCount = function() {
    return this._winCount;
};

Game_System.prototype.escapeCount = function() {
    return this._escapeCount;
};

Game_System.prototype.saveCount = function() {
    return this._saveCount;
};

Game_System.prototype.versionId = function() {
    return this._versionId;
};

Game_System.prototype.windowTone = function() {
    return this._windowTone || $dataSystem.windowTone;
};

Game_System.prototype.setWindowTone = function(value) {
    this._windowTone = value;
};

Game_System.prototype.battleBgm = function() {
    return this._battleBgm || $dataSystem.battleBgm;
};

Game_System.prototype.setBattleBgm = function(value) {
    this._battleBgm = value;
};

Game_System.prototype.victoryMe = function() {
    return this._victoryMe || $dataSystem.victoryMe;
};

Game_System.prototype.setVictoryMe = function(value) {
    this._victoryMe = value;
};

Game_System.prototype.defeatMe = function() {
    return this._defeatMe || $dataSystem.defeatMe;
};

Game_System.prototype.setDefeatMe = function(value) {
    this._defeatMe = value;
};

Game_System.prototype.onBattleStart = function() {
    this._battleCount++;
};

Game_System.prototype.onBattleWin = function() {
    this._winCount++;
};

Game_System.prototype.onBattleEscape = function() {
    this._escapeCount++;
};

Game_System.prototype.onBeforeSave = function() {
    this._saveCount++;
    this._versionId = $dataSystem.versionId;
    this._framesOnSave = Graphics.frameCount;
    this._sceneFramesOnSave = SceneManager.frameCount();
    this._bgmOnSave = AudioManager.saveBgm();
    this._bgsOnSave = AudioManager.saveBgs();
};

Game_System.prototype.onAfterLoad = function() {
    Graphics.frameCount = this._framesOnSave;
    SceneManager.setFrameCount(this._sceneFramesOnSave || this._framesOnSave);
    AudioManager.playBgm(this._bgmOnSave);
    AudioManager.playBgs(this._bgsOnSave);
};

Game_System.prototype.playtime = function() {
    return Math.floor(SceneManager.frameCount() / 60);
};

Game_System.prototype.playtimeText = function() {
    var hour = Math.floor(this.playtime() / 60 / 60);
    var min = Math.floor(this.playtime() / 60) % 60;
    var sec = this.playtime() % 60;
    return hour.padZero(2) + ':' + min.padZero(2) + ':' + sec.padZero(2);
};

Game_System.prototype.saveBgm = function() {
    this._savedBgm = AudioManager.saveBgm();
};

Game_System.prototype.replayBgm = function() {
    if (this._savedBgm) {
        AudioManager.replayBgm(this._savedBgm);
    }
};

Game_System.prototype.saveWalkingBgm = function() {
    this._walkingBgm = AudioManager.saveBgm();
};

Game_System.prototype.replayWalkingBgm = function() {
    if (this._walkingBgm) {
        AudioManager.playBgm(this._walkingBgm);
    }
};

Game_System.prototype.saveWalkingBgm2 = function() {
	this._walkingBgm = $dataMap.bgm;
};
