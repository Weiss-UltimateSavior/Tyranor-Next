//-----------------------------------------------------------------------------
// Game_SelfSwitches
//
// The game object class for self switches.

function Game_SelfSwitches() {
    this.initialize.apply(this, arguments);
}

Game_SelfSwitches.prototype.initialize = function() {
    this.clear();
};

Game_SelfSwitches.prototype.clear = function() {
    this._data = {};
};

Game_SelfSwitches.prototype.value = function(key) {
    return !!this._data[key];
};

Game_SelfSwitches.prototype.setValue = function(key, value) {
    if (value) {
        this._data[key] = true;
    } else {
        delete this._data[key];
    }
    this.onChange();
};

Game_SelfSwitches.prototype.onChange = function() {
    $gameMap.requestRefresh();
};
