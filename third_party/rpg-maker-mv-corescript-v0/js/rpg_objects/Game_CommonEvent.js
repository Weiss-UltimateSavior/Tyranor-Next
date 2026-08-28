//-----------------------------------------------------------------------------
// Game_CommonEvent
//
// The game object class for a common event. It contains functionality for
// running parallel process events.

function Game_CommonEvent() {
    this.initialize.apply(this, arguments);
}

Game_CommonEvent.prototype.initialize = function(commonEventId) {
    this._commonEventId = commonEventId;
    this.refresh();
};

Game_CommonEvent.prototype.event = function() {
    return $dataCommonEvents[this._commonEventId];
};

Game_CommonEvent.prototype.list = function() {
    return this.event().list;
};

Game_CommonEvent.prototype.refresh = function() {
    if (this.isActive()) {
        if (!this._interpreter) {
            this._interpreter = new Game_Interpreter();
        }
    } else {
        this._interpreter = null;
    }
};

Game_CommonEvent.prototype.isActive = function() {
    var event = this.event();
    return event.trigger === 2 && $gameSwitches.value(event.switchId);
};

Game_CommonEvent.prototype.update = function() {
    if (this._interpreter) {
        if (!this._interpreter.isRunning()) {
            this._interpreter.setup(this.list());
            this._interpreter.setEventInfo({ eventType: 'common_event', commonEventId: this._commonEventId });
        }
        this._interpreter.update();
    }
};
