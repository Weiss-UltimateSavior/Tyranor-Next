//-----------------------------------------------------------------------------
// Game_Temp
//
// The game object class for temporary data that is not included in save data.

function Game_Temp() {
    this.initialize.apply(this, arguments);
}

Game_Temp.prototype.initialize = function() {
    this._isPlaytest = Utils.isOptionValid('test');
    this._commonEventId = 0;
    this._destinationX = null;
    this._destinationY = null;
};

Game_Temp.prototype.isPlaytest = function() {
    return this._isPlaytest;
};

Game_Temp.prototype.reserveCommonEvent = function(commonEventId) {
    this._commonEventId = commonEventId;
};

Game_Temp.prototype.clearCommonEvent = function() {
    this._commonEventId = 0;
};

Game_Temp.prototype.isCommonEventReserved = function() {
    return this._commonEventId > 0;
};

Game_Temp.prototype.reservedCommonEvent = function() {
    return $dataCommonEvents[this._commonEventId];
};

Game_Temp.prototype.reservedCommonEventId = function() {
    return this._commonEventId;
};

Game_Temp.prototype.setDestination = function(x, y) {
    this._destinationX = x;
    this._destinationY = y;
};

Game_Temp.prototype.clearDestination = function() {
    this._destinationX = null;
    this._destinationY = null;
};

Game_Temp.prototype.isDestinationValid = function() {
    return this._destinationX !== null;
};

Game_Temp.prototype.destinationX = function() {
    return this._destinationX;
};

Game_Temp.prototype.destinationY = function() {
    return this._destinationY;
};
