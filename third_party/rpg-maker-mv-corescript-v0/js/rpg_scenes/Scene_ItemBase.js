//-----------------------------------------------------------------------------
// Scene_ItemBase
//
// The superclass of Scene_Item and Scene_Skill.

function Scene_ItemBase() {
    this.initialize.apply(this, arguments);
}

Scene_ItemBase.prototype = Object.create(Scene_MenuBase.prototype);
Scene_ItemBase.prototype.constructor = Scene_ItemBase;

Scene_ItemBase.prototype.initialize = function() {
    Scene_MenuBase.prototype.initialize.call(this);
};

Scene_ItemBase.prototype.create = function() {
    Scene_MenuBase.prototype.create.call(this);
};

Scene_ItemBase.prototype.createActorWindow = function() {
    this._actorWindow = new Window_MenuActor();
    this._actorWindow.setHandler('ok',     this.onActorOk.bind(this));
    this._actorWindow.setHandler('cancel', this.onActorCancel.bind(this));
    this.addWindow(this._actorWindow);
};

Scene_ItemBase.prototype.item = function() {
    return this._itemWindow.item();
};

Scene_ItemBase.prototype.user = function() {
    return null;
};

Scene_ItemBase.prototype.isCursorLeft = function() {
    return this._itemWindow.index() % 2 === 0;
};

Scene_ItemBase.prototype.showSubWindow = function(window) {
    window.x = this.isCursorLeft() ? Graphics.boxWidth - window.width : 0;
    window.show();
    window.activate();
};

Scene_ItemBase.prototype.hideSubWindow = function(window) {
    window.hide();
    window.deactivate();
    this.activateItemWindow();
};

Scene_ItemBase.prototype.onActorOk = function() {
    if (this.canUse()) {
        this.useItem();
    } else {
        SoundManager.playBuzzer();
    }
};

Scene_ItemBase.prototype.onActorCancel = function() {
    this.hideSubWindow(this._actorWindow);
};
Scene_ItemBase.prototype.action=function(){
    var action = new Game_Action(this.user());
    action.setItemObject(this.item());
    return action;
};

Scene_ItemBase.prototype.determineItem = function() {
    var action = this.action();
    if (action.isForFriend()) {
        this.showSubWindow(this._actorWindow);
        this._actorWindow.selectForItem(this.item());
    } else {
        this.useItem();
        this.activateItemWindow();
    }
};

Scene_ItemBase.prototype.useItem = function() {
    this.playSeForItem();
    this.user().useItem(this.item());
    this.applyItem();
    this.checkCommonEvent();
    this.checkGameover();
    this._actorWindow.refresh();
};

Scene_ItemBase.prototype.activateItemWindow = function() {
    this._itemWindow.refresh();
    this._itemWindow.activate();
};

Scene_ItemBase.prototype.itemTargetActors =function(){
    var action = this.action();
    if (!action.isForFriend()) {
        return [];
    } else if (action.isForAll()) {
        return $gameParty.members();
    } else {
        return [$gameParty.members()[this._actorWindow.index()]];
    }
};

Scene_ItemBase.prototype.canUse = function() {
    var user = this.user();
    if(user){
        return user.canUse(this.item()) && this.isItemEffectsValid();
    }
    return false;
};

Scene_ItemBase.prototype.isItemEffectsValid = function() {
    var action = this.action();
    return this.itemTargetActors().some(function(target) {
        return action.testApply(target);
    }, this);
};

Scene_ItemBase.prototype.applyItem =function(){
    var action = this.action();
    var targets = this.itemTargetActors();
    targets.forEach(function(battler) {
        var repeats = action.numRepeats();
        for (var i = 0; i < repeats; i++) {
            action.apply(battler);                    
        }
    });
    action.applyGlobal();
};

Scene_ItemBase.prototype.checkCommonEvent = function() {
    if ($gameTemp.isCommonEventReserved()) {
        SceneManager.goto(Scene_Map);
    }
};
