//-----------------------------------------------------------------------------
// Game_ActionResult
//
// The game object class for a result of a battle action. For convinience, all
// member variables in this class are public.

function Game_ActionResult() {
    this.initialize.apply(this, arguments);
}

Game_ActionResult.prototype.initialize = function() {
    this.clear();
};

Game_ActionResult.prototype.clear = function() {
    this.used = false;
    this.missed = false;
    this.evaded = false;
    this.physical = false;
    this.drain = false;
    this.critical = false;
    this.success = false;
    this.hpAffected = false;
    this.hpDamage = 0;
    this.mpDamage = 0;
    this.tpDamage = 0;
    this.addedStates = [];
    this.removedStates = [];
    this.addedBuffs = [];
    this.addedDebuffs = [];
    this.removedBuffs = [];
};

Game_ActionResult.prototype.addedStateObjects = function() {
    return this.addedStates.map(function(id) {
        return $dataStates[id];
    });
};

Game_ActionResult.prototype.removedStateObjects = function() {
    return this.removedStates.map(function(id) {
        return $dataStates[id];
    });
};

Game_ActionResult.prototype.isStatusAffected = function() {
    return (this.addedStates.length > 0 || this.removedStates.length > 0 ||
            this.addedBuffs.length > 0 || this.addedDebuffs.length > 0 ||
            this.removedBuffs.length > 0);
};

Game_ActionResult.prototype.isHit = function() {
    return this.used && !this.missed && !this.evaded;
};

Game_ActionResult.prototype.isStateAdded = function(stateId) {
    return this.addedStates.contains(stateId);
};

Game_ActionResult.prototype.pushAddedState = function(stateId) {
    if (!this.isStateAdded(stateId)) {
        this.addedStates.push(stateId);
    }
};

Game_ActionResult.prototype.isStateRemoved = function(stateId) {
    return this.removedStates.contains(stateId);
};

Game_ActionResult.prototype.pushRemovedState = function(stateId) {
    if (!this.isStateRemoved(stateId)) {
        this.removedStates.push(stateId);
    }
};

Game_ActionResult.prototype.isBuffAdded = function(paramId) {
    return this.addedBuffs.contains(paramId);
};

Game_ActionResult.prototype.pushAddedBuff = function(paramId) {
    if (!this.isBuffAdded(paramId)) {
        this.addedBuffs.push(paramId);
    }
};

Game_ActionResult.prototype.isDebuffAdded = function(paramId) {
    return this.addedDebuffs.contains(paramId);
};

Game_ActionResult.prototype.pushAddedDebuff = function(paramId) {
    if (!this.isDebuffAdded(paramId)) {
        this.addedDebuffs.push(paramId);
    }
};

Game_ActionResult.prototype.isBuffRemoved = function(paramId) {
    return this.removedBuffs.contains(paramId);
};

Game_ActionResult.prototype.pushRemovedBuff = function(paramId) {
    if (!this.isBuffRemoved(paramId)) {
        this.removedBuffs.push(paramId);
    }
};
