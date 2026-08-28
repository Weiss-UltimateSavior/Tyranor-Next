//-----------------------------------------------------------------------------
// Game_Variables
//
// The game object class for variables.

function Game_Variables() {
    this.initialize.apply(this, arguments);
}

Game_Variables.prototype.initialize = function() {
    this.clear();
};

Game_Variables.prototype.clear = function() {
    this._data = [];
};

Game_Variables.prototype.value = function(variableId) {
    return this._data[variableId] || 0;
};

Game_Variables.prototype.setValue = function(variableId, value) {
    if (variableId > 0 && variableId < $dataSystem.variables.length) {
        if (typeof value === 'number') {
            value = Math.floor(value);
        }
        this._data[variableId] = value;
        this.onChange();
    }
};

Game_Variables.prototype.onChange = function() {
    $gameMap.requestRefresh();
};
