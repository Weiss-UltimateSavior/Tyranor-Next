//-----------------------------------------------------------------------------
// Game_Timer
//
// The game object class for the timer.

function Game_Timer() {
    this.initialize.apply(this, arguments);
}

Game_Timer.prototype.initialize = function() {
    this._frames = 0;
    this._working = false;
};

Game_Timer.prototype.update = function(sceneActive) {
    if (sceneActive && this._working && this._frames > 0) {
        this._frames--;
        if (this._frames === 0) {
            this.onExpire();
        }
    }
};

Game_Timer.prototype.start = function(count) {
    this._frames = count;
    this._working = true;
};

Game_Timer.prototype.stop = function() {
    this._working = false;
};

Game_Timer.prototype.isWorking = function() {
    return this._working;
};

Game_Timer.prototype.seconds = function() {
    return Math.floor(this._frames / 60);
};

Game_Timer.prototype.onExpire = function() {
    BattleManager.abort();
};
