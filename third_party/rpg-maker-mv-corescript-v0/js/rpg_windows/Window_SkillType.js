//-----------------------------------------------------------------------------
// Window_SkillType
//
// The window for selecting a skill type on the skill screen.

function Window_SkillType() {
    this.initialize.apply(this, arguments);
}

Window_SkillType.prototype = Object.create(Window_Command.prototype);
Window_SkillType.prototype.constructor = Window_SkillType;

Window_SkillType.prototype.initialize = function(x, y) {
    Window_Command.prototype.initialize.call(this, x, y);
    this._actor = null;
};

Window_SkillType.prototype.windowWidth = function() {
    return 240;
};

Window_SkillType.prototype.setActor = function(actor) {
    if (this._actor !== actor) {
        this._actor = actor;
        this.refresh();
        this.selectLast();
    }
};

Window_SkillType.prototype.numVisibleRows = function() {
    return 4;
};

Window_SkillType.prototype.makeCommandList = function() {
    if (this._actor) {
        var skillTypes = this._actor.addedSkillTypes();
        skillTypes.sort(function(a, b) {
            return a - b;
        });
        skillTypes.forEach(function(stypeId) {
            var name = $dataSystem.skillTypes[stypeId];
            this.addCommand(name, 'skill', true, stypeId);
        }, this);
    }
};

Window_SkillType.prototype.update = function() {
    Window_Command.prototype.update.call(this);
    if (this._skillWindow) {
        this._skillWindow.setStypeId(this.currentExt());
    }
};

Window_SkillType.prototype.setSkillWindow = function(skillWindow) {
    this._skillWindow = skillWindow;
};

Window_SkillType.prototype.selectLast = function() {
    var skill = this._actor.lastMenuSkill();
    if (skill) {
        this.selectExt(skill.stypeId);
    } else {
        this.select(0);
    }
};
