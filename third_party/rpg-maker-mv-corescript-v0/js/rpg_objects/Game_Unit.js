//-----------------------------------------------------------------------------
// Game_Unit
//
// The superclass of Game_Party and Game_Troop.

function Game_Unit() {
    this.initialize.apply(this, arguments);
}

Game_Unit.prototype.initialize = function() {
    this._inBattle = false;
};

Game_Unit.prototype.inBattle = function() {
    return this._inBattle;
};

Game_Unit.prototype.members = function() {
    return [];
};

Game_Unit.prototype.aliveMembers = function() {
    return this.members().filter(function(member) {
        return member.isAlive();
    });
};

Game_Unit.prototype.deadMembers = function() {
    return this.members().filter(function(member) {
        return member.isDead();
    });
};

Game_Unit.prototype.movableMembers = function() {
    return this.members().filter(function(member) {
        return member.canMove();
    });
};

Game_Unit.prototype.clearActions = function() {
    return this.members().forEach(function(member) {
        return member.clearActions();
    });
};

Game_Unit.prototype.agility = function() {
    var members = this.members();
    if (members.length === 0) {
        return 1;
    }
    var sum = members.reduce(function(r, member) {
        return r + member.agi;
    }, 0);
    return sum / members.length;
};

Game_Unit.prototype.tgrSum = function() {
    return this.aliveMembers().reduce(function(r, member) {
        return r + member.tgr;
    }, 0);
};

Game_Unit.prototype.randomTarget = function() {
    var tgrRand = Math.random() * this.tgrSum();
    var target = null;
    this.aliveMembers().forEach(function(member) {
        tgrRand -= member.tgr;
        if (tgrRand <= 0 && !target) {
            target = member;
        }
    });
    return target;
};

Game_Unit.prototype.randomDeadTarget = function() {
    var members = this.deadMembers();
    if (members.length === 0) {
        return null;
    }
    return members[Math.floor(Math.random() * members.length)];
};

Game_Unit.prototype.smoothTarget = function(index) {
    if (index < 0) {
        index = 0;
    }
    var member = this.members()[index];
    return (member && member.isAlive()) ? member : this.aliveMembers()[0];
};

Game_Unit.prototype.smoothDeadTarget = function(index) {
    if (index < 0) {
        index = 0;
    }
    var member = this.members()[index];
    return (member && member.isDead()) ? member : this.deadMembers()[0];
};

Game_Unit.prototype.clearResults = function() {
    this.members().forEach(function(member) {
        member.clearResult();
    });
};

Game_Unit.prototype.onBattleStart = function() {
    this.members().forEach(function(member) {
        member.onBattleStart();
    });
    this._inBattle = true;
};

Game_Unit.prototype.onBattleEnd = function() {
    this._inBattle = false;
    this.members().forEach(function(member) {
        member.onBattleEnd();
    });
};

Game_Unit.prototype.makeActions = function() {
    this.members().forEach(function(member) {
        member.makeActions();
    });
};

Game_Unit.prototype.select = function(activeMember) {
    this.members().forEach(function(member) {
        if (member === activeMember) {
            member.select();
        } else {
            member.deselect();
        }
    });
};

Game_Unit.prototype.isAllDead = function() {
    return this.aliveMembers().length === 0;
};

Game_Unit.prototype.substituteBattler = function() {
    var members = this.members();
    for (var i = 0; i < members.length; i++) {
        if (members[i].isSubstitute()) {
            return members[i];
        }
    }
};
