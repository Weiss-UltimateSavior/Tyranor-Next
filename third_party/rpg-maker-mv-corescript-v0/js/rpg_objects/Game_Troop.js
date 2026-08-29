//-----------------------------------------------------------------------------
// Game_Troop
//
// The game object class for a troop and the battle-related data.

function Game_Troop() {
    this.initialize.apply(this, arguments);
}

Game_Troop.prototype = Object.create(Game_Unit.prototype);
Game_Troop.prototype.constructor = Game_Troop;

Game_Troop.LETTER_TABLE_HALF = [
    ' A',' B',' C',' D',' E',' F',' G',' H',' I',' J',' K',' L',' M',
    ' N',' O',' P',' Q',' R',' S',' T',' U',' V',' W',' X',' Y',' Z'
];
Game_Troop.LETTER_TABLE_FULL = [
    'Ａ','Ｂ','Ｃ','Ｄ','Ｅ','Ｆ','Ｇ','Ｈ','Ｉ','Ｊ','Ｋ','Ｌ','Ｍ',
    'Ｎ','Ｏ','Ｐ','Ｑ','Ｒ','Ｓ','Ｔ','Ｕ','Ｖ','Ｗ','Ｘ','Ｙ','Ｚ'
];

Game_Troop.prototype.initialize = function() {
    Game_Unit.prototype.initialize.call(this);
    this._interpreter = new Game_Interpreter();
    this.clear();
};

Game_Troop.prototype.isEventRunning = function() {
    return this._interpreter.isRunning();
};

Game_Troop.prototype.updateInterpreter = function() {
    this._interpreter.update();
};

Game_Troop.prototype.turnCount = function() {
    return this._turnCount;
};

Game_Troop.prototype.members = function() {
    return this._enemies;
};

Game_Troop.prototype.clear = function() {
    this._interpreter.clear();
    this._troopId = 0;
    this._eventFlags = {};
    this._enemies = [];
    this._turnCount = 0;
    this._namesCount = {};
};

Game_Troop.prototype.troop = function() {
    return $dataTroops[this._troopId];
};

Game_Troop.prototype.setup = function(troopId) {
    this.clear();
    this._troopId = troopId;
    this._enemies = [];
    this.troop().members.forEach(function(member) {
        if ($dataEnemies[member.enemyId]) {
            var enemyId = member.enemyId;
            var x = member.x;
            var y = member.y;
            var enemy = new Game_Enemy(enemyId, x, y);
            if (member.hidden) {
                enemy.hide();
            }
            this._enemies.push(enemy);
        }
    }, this);
    this.makeUniqueNames();
};

Game_Troop.prototype.makeUniqueNames = function() {
    var table = this.letterTable();
    this.members().forEach(function(enemy) {
        if (enemy.isAlive() && enemy.isLetterEmpty()) {
            var name = enemy.originalName();
            var n = this._namesCount[name] || 0;
            enemy.setLetter(table[n % table.length]);
            this._namesCount[name] = n + 1;
        }
    }, this);
    this.members().forEach(function(enemy) {
        var name = enemy.originalName();
        if (this._namesCount[name] >= 2) {
            enemy.setPlural(true);
        }
    }, this);
};

Game_Troop.prototype.letterTable = function() {
    return $gameSystem.isCJK() ? Game_Troop.LETTER_TABLE_FULL :
            Game_Troop.LETTER_TABLE_HALF;
};

Game_Troop.prototype.enemyNames = function() {
    var names = [];
    this.members().forEach(function(enemy) {
        var name = enemy.originalName();
        if (enemy.isAlive() && !names.contains(name)) {
            names.push(name);
        }
    });
    return names;
};

Game_Troop.prototype.meetsConditions = function(page) {
    var c = page.conditions;
    if (!c.turnEnding && !c.turnValid && !c.enemyValid &&
            !c.actorValid && !c.switchValid) {
        return false;  // Conditions not set
    }
    if (c.turnEnding) {
        if (!BattleManager.isTurnEnd()) {
            return false;
        }
    }
    if (c.turnValid) {
        var n = this._turnCount;
        var a = c.turnA;
        var b = c.turnB;
        if ((b === 0 && n !== a)) {
            return false;
        }
        if ((b > 0 && (n < 1 || n < a || n % b !== a % b))) {
            return false;
        }
    }
    if (c.enemyValid) {
        var enemy = $gameTroop.members()[c.enemyIndex];
        if (!enemy || enemy.hpRate() * 100 > c.enemyHp) {
            return false;
        }
    }
    if (c.actorValid) {
        var actor = $gameActors.actor(c.actorId);
        if (!actor || actor.hpRate() * 100 > c.actorHp) {
            return false;
        }
    }
    if (c.switchValid) {
        if (!$gameSwitches.value(c.switchId)) {
            return false;
        }
    }
    return true;
};

Game_Troop.prototype.setupBattleEvent = function() {
    if (!this._interpreter.isRunning()) {
        if (this._interpreter.setupReservedCommonEvent()) {
            return;
        }
        var pages = this.troop().pages;
        for (var i = 0; i < pages.length; i++) {
            var page = pages[i];
            if (this.meetsConditions(page) && !this._eventFlags[i]) {
                this._interpreter.setup(page.list);
                this._interpreter.setEventInfo({ eventType: 'battle_event', troopId: this._troopId, page: i + 1 });
                if (page.span <= 1) {
                    this._eventFlags[i] = true;
                }
                break;
            }
        }
    }
};

Game_Troop.prototype.increaseTurn = function() {
    var pages = this.troop().pages;
    for (var i = 0; i < pages.length; i++) {
        var page = pages[i];
        if (page.span === 1) {
            this._eventFlags[i] = false;
        }
    }
    this._turnCount++;
};

Game_Troop.prototype.expTotal = function() {
    return this.deadMembers().reduce(function(r, enemy) {
        return r + enemy.exp();
    }, 0);
};

Game_Troop.prototype.goldTotal = function() {
    return this.deadMembers().reduce(function(r, enemy) {
        return r + enemy.gold();
    }, 0) * this.goldRate();
};

Game_Troop.prototype.goldRate = function() {
    return $gameParty.hasGoldDouble() ? 2 : 1;
};

Game_Troop.prototype.makeDropItems = function() {
    return this.deadMembers().reduce(function(r, enemy) {
        return r.concat(enemy.makeDropItems());
    }, []);
};
