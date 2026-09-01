//-----------------------------------------------------------------------------
// Window_ActorCommand
//
// The window for selecting an actor's action on the battle screen.

function Window_ActorCommand() {
    this.initialize.apply(this, arguments);
}

Window_ActorCommand.prototype = Object.create(Window_Command.prototype);
Window_ActorCommand.prototype.constructor = Window_ActorCommand;

Window_ActorCommand.prototype.initialize = function() {
    var y = Graphics.boxHeight - this.windowHeight();
    Window_Command.prototype.initialize.call(this, 0, y);
    this.openness = 0;
    this.deactivate();
    this._actor = null;
};

Window_ActorCommand.prototype.windowWidth = function() {
    return 192;
};

Window_ActorCommand.prototype.numVisibleRows = function() {
    return 4;
};

Window_ActorCommand.prototype.makeCommandList = function() {
    if (this._actor) {
        this.addAttackCommand();
        this.addSkillCommands();
        this.addGuardCommand();
        this.addItemCommand();
    }
};

Window_ActorCommand.prototype.addAttackCommand = function() {
    this.addCommand(TextManager.attack, 'attack', this._actor.canAttack());
};

Window_ActorCommand.prototype.addSkillCommands = function() {
    var skillTypes = this._actor.addedSkillTypes();
    skillTypes.sort(function(a, b) {
        return a - b;
    });
    skillTypes.forEach(function(stypeId) {
        var name = $dataSystem.skillTypes[stypeId];
        this.addCommand(name, 'skill', true, stypeId);
    }, this);
};

Window_ActorCommand.prototype.addGuardCommand = function() {
    this.addCommand(TextManager.guard, 'guard', this._actor.canGuard());
};

Window_ActorCommand.prototype.addItemCommand = function() {
    this.addCommand(TextManager.item, 'item');
};

Window_ActorCommand.prototype.setup = function(actor) {
    this._actor = actor;
    this.clearCommandList();
    this.makeCommandList();
    this.refresh();
    this.selectLast();
    this.activate();
    this.open();
};

Window_ActorCommand.prototype.processOk = function() {
    if (this._actor) {
        if (ConfigManager.commandRemember) {
            this._actor.setLastCommandSymbol(this.currentSymbol());
        } else {
            this._actor.setLastCommandSymbol('');
        }
    }
    Window_Command.prototype.processOk.call(this);
};

Window_ActorCommand.prototype.selectLast = function() {
    this.select(0);
    if (this._actor && ConfigManager.commandRemember) {
        var symbol = this._actor.lastCommandSymbol();
        this.selectSymbol(symbol);
        if (symbol === 'skill') {
            var skill = this._actor.lastBattleSkill();
            if (skill) {
                this.selectExt(skill.stypeId);
            }
        }
    }
};
