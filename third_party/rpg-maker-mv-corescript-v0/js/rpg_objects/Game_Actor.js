//-----------------------------------------------------------------------------
// Game_Actor
//
// The game object class for an actor.

function Game_Actor() {
    this.initialize.apply(this, arguments);
}

Game_Actor.prototype = Object.create(Game_Battler.prototype);
Game_Actor.prototype.constructor = Game_Actor;

Object.defineProperty(Game_Actor.prototype, 'level', {
    get: function() {
        return this._level;
    },
    configurable: true
});

Game_Actor.prototype.initialize = function(actorId) {
    Game_Battler.prototype.initialize.call(this);
    this.setup(actorId);
};

Game_Actor.prototype.initMembers = function() {
    Game_Battler.prototype.initMembers.call(this);
    this._actorId = 0;
    this._name = '';
    this._nickname = '';
    this._classId = 0;
    this._level = 0;
    this._characterName = '';
    this._characterIndex = 0;
    this._faceName = '';
    this._faceIndex = 0;
    this._battlerName = '';
    this._exp = {};
    this._skills = [];
    this._equips = [];
    this._actionInputIndex = 0;
    this._lastMenuSkill = new Game_Item();
    this._lastBattleSkill  = new Game_Item();
    this._lastCommandSymbol = '';
};

Game_Actor.prototype.setup = function(actorId) {
    var actor = $dataActors[actorId];
    this._actorId = actorId;
    this._name = actor.name;
    this._nickname = actor.nickname;
    this._profile = actor.profile;
    this._classId = actor.classId;
    this._level = actor.initialLevel;
    this.initImages();
    this.initExp();
    this.initSkills();
    this.initEquips(actor.equips);
    this.clearParamPlus();
    this.recoverAll();
};

Game_Actor.prototype.actorId = function() {
    return this._actorId;
};

Game_Actor.prototype.actor = function() {
    return $dataActors[this._actorId];
};

Game_Actor.prototype.name = function() {
    return this._name;
};

Game_Actor.prototype.setName = function(name) {
    this._name = name;
};

Game_Actor.prototype.nickname = function() {
    return this._nickname;
};

Game_Actor.prototype.setNickname = function(nickname) {
    this._nickname = nickname;
};

Game_Actor.prototype.profile = function() {
    return this._profile;
};

Game_Actor.prototype.setProfile = function(profile) {
    this._profile = profile;
};

Game_Actor.prototype.characterName = function() {
    return this._characterName;
};

Game_Actor.prototype.characterIndex = function() {
    return this._characterIndex;
};

Game_Actor.prototype.faceName = function() {
    return this._faceName;
};

Game_Actor.prototype.faceIndex = function() {
    return this._faceIndex;
};

Game_Actor.prototype.battlerName = function() {
    return this._battlerName;
};

Game_Actor.prototype.clearStates = function() {
    Game_Battler.prototype.clearStates.call(this);
    this._stateSteps = {};
};

Game_Actor.prototype.eraseState = function(stateId) {
    Game_Battler.prototype.eraseState.call(this, stateId);
    delete this._stateSteps[stateId];
};

Game_Actor.prototype.resetStateCounts = function(stateId) {
    Game_Battler.prototype.resetStateCounts.call(this, stateId);
    this._stateSteps[stateId] = $dataStates[stateId].stepsToRemove;
};

Game_Actor.prototype.initImages = function() {
    var actor = this.actor();
    this._characterName = actor.characterName;
    this._characterIndex = actor.characterIndex;
    this._faceName = actor.faceName;
    this._faceIndex = actor.faceIndex;
    this._battlerName = actor.battlerName;
};

Game_Actor.prototype.expForLevel = function(level) {
    var c = this.currentClass();
    var basis = c.expParams[0];
    var extra = c.expParams[1];
    var acc_a = c.expParams[2];
    var acc_b = c.expParams[3];
    return Math.round(basis*(Math.pow(level-1, 0.9+acc_a/250))*level*
            (level+1)/(6+Math.pow(level,2)/50/acc_b)+(level-1)*extra);
};

Game_Actor.prototype.initExp = function() {
    this._exp[this._classId] = this.currentLevelExp();
};

Game_Actor.prototype.currentExp = function() {
    return this._exp[this._classId];
};

Game_Actor.prototype.currentLevelExp = function() {
    return this.expForLevel(this._level);
};

Game_Actor.prototype.nextLevelExp = function() {
    return this.expForLevel(this._level + 1);
};

Game_Actor.prototype.nextRequiredExp = function() {
    return this.nextLevelExp() - this.currentExp();
};

Game_Actor.prototype.maxLevel = function() {
    return this.actor().maxLevel;
};

Game_Actor.prototype.isMaxLevel = function() {
    return this._level >= this.maxLevel();
};

Game_Actor.prototype.initSkills = function() {
    this._skills = [];
    this.currentClass().learnings.forEach(function(learning) {
        if (learning.level <= this._level) {
            this.learnSkill(learning.skillId);
        }
    }, this);
};

Game_Actor.prototype.initEquips = function(equips) {
    var slots = this.equipSlots();
    var maxSlots = slots.length;
    this._equips = [];
    for (var i = 0; i < maxSlots; i++) {
        this._equips[i] = new Game_Item();
    }
    for (var j = 0; j < equips.length; j++) {
        if (j < maxSlots) {
            this._equips[j].setEquip(slots[j] === 1, equips[j]);
        }
    }
    this.releaseUnequippableItems(true);
    this.refresh();
};

Game_Actor.prototype.equipSlots = function() {
    var slots = [];
    for (var i = 1; i < $dataSystem.equipTypes.length; i++) {
        slots.push(i);
    }
    if (slots.length >= 2 && this.isDualWield()) {
        slots[1] = 1;
    }
    return slots;
};

Game_Actor.prototype.equips = function() {
    return this._equips.map(function(item) {
        return item.object();
    });
};

Game_Actor.prototype.weapons = function() {
    return this.equips().filter(function(item) {
        return item && DataManager.isWeapon(item);
    });
};

Game_Actor.prototype.armors = function() {
    return this.equips().filter(function(item) {
        return item && DataManager.isArmor(item);
    });
};

Game_Actor.prototype.hasWeapon = function(weapon) {
    return this.weapons().contains(weapon);
};

Game_Actor.prototype.hasArmor = function(armor) {
    return this.armors().contains(armor);
};

Game_Actor.prototype.isEquipChangeOk = function(slotId) {
    return (!this.isEquipTypeLocked(this.equipSlots()[slotId]) &&
            !this.isEquipTypeSealed(this.equipSlots()[slotId]));
};

Game_Actor.prototype.changeEquip = function(slotId, item) {
    if (this.tradeItemWithParty(item, this.equips()[slotId]) &&
            (!item || this.equipSlots()[slotId] === item.etypeId)) {
        this._equips[slotId].setObject(item);
        this.refresh();
    }
};

Game_Actor.prototype.forceChangeEquip = function(slotId, item) {
    this._equips[slotId].setObject(item);
    this.releaseUnequippableItems(true);
    this.refresh();
};

Game_Actor.prototype.tradeItemWithParty = function(newItem, oldItem) {
    if (newItem && !$gameParty.hasItem(newItem)) {
        return false;
    } else {
        $gameParty.gainItem(oldItem, 1);
        $gameParty.loseItem(newItem, 1);
        return true;
    }
};

Game_Actor.prototype.changeEquipById = function(etypeId, itemId) {
    var slotId = etypeId - 1;
    if (this.equipSlots()[slotId] === 1) {
        this.changeEquip(slotId, $dataWeapons[itemId]);
    } else {
        this.changeEquip(slotId, $dataArmors[itemId]);
    }
};

Game_Actor.prototype.isEquipped = function(item) {
    return this.equips().contains(item);
};

Game_Actor.prototype.discardEquip = function(item) {
    var slotId = this.equips().indexOf(item);
    if (slotId >= 0) {
        this._equips[slotId].setObject(null);
    }
};

Game_Actor.prototype.releaseUnequippableItems = function(forcing) {
    for (;;) {
        var slots = this.equipSlots();
        var equips = this.equips();
        var changed = false;
        for (var i = 0; i < equips.length; i++) {
            var item = equips[i];
            if (item && (!this.canEquip(item) || item.etypeId !== slots[i])) {
                if (!forcing) {
                    this.tradeItemWithParty(null, item);
                }
                this._equips[i].setObject(null);
                changed = true;
            }
        }
        if (!changed) {
            break;
        }
    }
};

Game_Actor.prototype.clearEquipments = function() {
    var maxSlots = this.equipSlots().length;
    for (var i = 0; i < maxSlots; i++) {
        if (this.isEquipChangeOk(i)) {
            this.changeEquip(i, null);
        }
    }
};

Game_Actor.prototype.optimizeEquipments = function() {
    var maxSlots = this.equipSlots().length;
    this.clearEquipments();
    for (var i = 0; i < maxSlots; i++) {
        if (this.isEquipChangeOk(i)) {
            this.changeEquip(i, this.bestEquipItem(i));
        }
    }
};

Game_Actor.prototype.bestEquipItem = function(slotId) {
    var etypeId = this.equipSlots()[slotId];
    var items = $gameParty.equipItems().filter(function(item) {
        return item.etypeId === etypeId && this.canEquip(item);
    }, this);
    var bestItem = null;
    var bestPerformance = -1000;
    for (var i = 0; i < items.length; i++) {
        var performance = this.calcEquipItemPerformance(items[i]);
        if (performance > bestPerformance) {
            bestPerformance = performance;
            bestItem = items[i];
        }
    }
    return bestItem;
};

Game_Actor.prototype.calcEquipItemPerformance = function(item) {
    return item.params.reduce(function(a, b) {
        return a + b;
    });
};

Game_Actor.prototype.isSkillWtypeOk = function(skill) {
    var wtypeId1 = skill.requiredWtypeId1;
    var wtypeId2 = skill.requiredWtypeId2;
    if ((wtypeId1 === 0 && wtypeId2 === 0) ||
            (wtypeId1 > 0 && this.isWtypeEquipped(wtypeId1)) ||
            (wtypeId2 > 0 && this.isWtypeEquipped(wtypeId2))) {
        return true;
    } else {
        return false;
    }
};

Game_Actor.prototype.isWtypeEquipped = function(wtypeId) {
    return this.weapons().some(function(weapon) {
        return weapon.wtypeId === wtypeId;
    });
};

Game_Actor.prototype.refresh = function() {
    this.releaseUnequippableItems(false);
    Game_Battler.prototype.refresh.call(this);
};

Game_Actor.prototype.isActor = function() {
    return true;
};

Game_Actor.prototype.friendsUnit = function() {
    return $gameParty;
};

Game_Actor.prototype.opponentsUnit = function() {
    return $gameTroop;
};

Game_Actor.prototype.index = function() {
    return $gameParty.members().indexOf(this);
};

Game_Actor.prototype.isBattleMember = function() {
    return $gameParty.battleMembers().contains(this);
};

Game_Actor.prototype.isFormationChangeOk = function() {
    return true;
};

Game_Actor.prototype.currentClass = function() {
    return $dataClasses[this._classId];
};

Game_Actor.prototype.isClass = function(gameClass) {
    return gameClass && this._classId === gameClass.id;
};

Game_Actor.prototype.skills = function() {
    var list = [];
    this._skills.concat(this.addedSkills()).forEach(function(id) {
        if (!list.contains($dataSkills[id])) {
            list.push($dataSkills[id]);
        }
    });
    return list;
};

Game_Actor.prototype.usableSkills = function() {
    return this.skills().filter(function(skill) {
        return this.canUse(skill);
    }, this);
};

Game_Actor.prototype.traitObjects = function() {
    var objects = Game_Battler.prototype.traitObjects.call(this);
    objects = objects.concat([this.actor(), this.currentClass()]);
    var equips = this.equips();
    for (var i = 0; i < equips.length; i++) {
        var item = equips[i];
        if (item) {
            objects.push(item);
        }
    }
    return objects;
};

Game_Actor.prototype.attackElements = function() {
    var set = Game_Battler.prototype.attackElements.call(this);
    if (this.hasNoWeapons() && !set.contains(this.bareHandsElementId())) {
        set.push(this.bareHandsElementId());
    }
    return set;
};

Game_Actor.prototype.hasNoWeapons = function() {
    return this.weapons().length === 0;
};

Game_Actor.prototype.bareHandsElementId = function() {
    return 1;
};

Game_Actor.prototype.paramMax = function(paramId) {
    if (paramId === 0) {
        return 9999;    // MHP
    }
    return Game_Battler.prototype.paramMax.call(this, paramId);
};

Game_Actor.prototype.paramBase = function(paramId) {
    return this.currentClass().params[paramId][this._level];
};

Game_Actor.prototype.paramPlus = function(paramId) {
    var value = Game_Battler.prototype.paramPlus.call(this, paramId);
    var equips = this.equips();
    for (var i = 0; i < equips.length; i++) {
        var item = equips[i];
        if (item) {
            value += item.params[paramId];
        }
    }
    return value;
};

Game_Actor.prototype.attackAnimationId1 = function() {
    if (this.hasNoWeapons()) {
        return this.bareHandsAnimationId();
    } else {
        var weapons = this.weapons();
        return weapons[0] ? weapons[0].animationId : 0;
    }
};

Game_Actor.prototype.attackAnimationId2 = function() {
    var weapons = this.weapons();
    return weapons[1] ? weapons[1].animationId : 0;
};

Game_Actor.prototype.bareHandsAnimationId = function() {
    return 1;
};

Game_Actor.prototype.changeExp = function(exp, show) {
    this._exp[this._classId] = Math.max(exp, 0);
    var lastLevel = this._level;
    var lastSkills = this.skills();
    while (!this.isMaxLevel() && this.currentExp() >= this.nextLevelExp()) {
        this.levelUp();
    }
    while (this.currentExp() < this.currentLevelExp()) {
        this.levelDown();
    }
    if (show && this._level > lastLevel) {
        this.displayLevelUp(this.findNewSkills(lastSkills));
    }
    this.refresh();
};

Game_Actor.prototype.levelUp = function() {
    this._level++;
    this.currentClass().learnings.forEach(function(learning) {
        if (learning.level === this._level) {
            this.learnSkill(learning.skillId);
        }
    }, this);
};

Game_Actor.prototype.levelDown = function() {
    this._level--;
};

Game_Actor.prototype.findNewSkills = function(lastSkills) {
    var newSkills = this.skills();
    for (var i = 0; i < lastSkills.length; i++) {
        var index = newSkills.indexOf(lastSkills[i]);
        if (index >= 0) {
            newSkills.splice(index, 1);
        }
    }
    return newSkills;
};

Game_Actor.prototype.displayLevelUp = function(newSkills) {
    var text = TextManager.levelUp.format(this._name, TextManager.level, this._level);
    $gameMessage.newPage();
    $gameMessage.add(text);
    newSkills.forEach(function(skill) {
        $gameMessage.add(TextManager.obtainSkill.format(skill.name));
    });
};

Game_Actor.prototype.gainExp = function(exp) {
    var newExp = this.currentExp() + Math.round(exp * this.finalExpRate());
    this.changeExp(newExp, this.shouldDisplayLevelUp());
};

Game_Actor.prototype.finalExpRate = function() {
    return this.exr * (this.isBattleMember() ? 1 : this.benchMembersExpRate());
};

Game_Actor.prototype.benchMembersExpRate = function() {
    return $dataSystem.optExtraExp ? 1 : 0;
};

Game_Actor.prototype.shouldDisplayLevelUp = function() {
    return true;
};

Game_Actor.prototype.changeLevel = function(level, show) {
    level = level.clamp(1, this.maxLevel());
    this.changeExp(this.expForLevel(level), show);
};

Game_Actor.prototype.learnSkill = function(skillId) {
    if (!this.isLearnedSkill(skillId)) {
        this._skills.push(skillId);
        this._skills.sort(function(a, b) {
            return a - b;
        });
    }
};

Game_Actor.prototype.forgetSkill = function(skillId) {
    var index = this._skills.indexOf(skillId);
    if (index >= 0) {
        this._skills.splice(index, 1);
    }
};

Game_Actor.prototype.isLearnedSkill = function(skillId) {
    return this._skills.contains(skillId);
};

Game_Actor.prototype.hasSkill = function(skillId) {
    return this.skills().contains($dataSkills[skillId]);
};

Game_Actor.prototype.changeClass = function(classId, keepExp) {
    if (keepExp) {
        this._exp[classId] = this.currentExp();
    }
    this._classId = classId;
    this.changeExp(this._exp[this._classId] || 0, false);
    this.refresh();
};

Game_Actor.prototype.setCharacterImage = function(characterName, characterIndex) {
    this._characterName = characterName;
    this._characterIndex = characterIndex;
};

Game_Actor.prototype.setFaceImage = function(faceName, faceIndex) {
    this._faceName = faceName;
    this._faceIndex = faceIndex;
};

Game_Actor.prototype.setBattlerImage = function(battlerName) {
    this._battlerName = battlerName;
};

Game_Actor.prototype.isSpriteVisible = function() {
    return $gameSystem.isSideView();
};

Game_Actor.prototype.startAnimation = function(animationId, mirror, delay) {
    mirror = !mirror;
    Game_Battler.prototype.startAnimation.call(this, animationId, mirror, delay);
};

Game_Actor.prototype.performActionStart = function(action) {
    Game_Battler.prototype.performActionStart.call(this, action);
};

Game_Actor.prototype.performAction = function(action) {
    Game_Battler.prototype.performAction.call(this, action);
    if (action.isAttack()) {
        this.performAttack();
    } else if (action.isGuard()) {
        this.requestMotion('guard');
    } else if (action.isMagicSkill()) {
        this.requestMotion('spell');
    } else if (action.isSkill()) {
        this.requestMotion('skill');
    } else if (action.isItem()) {
        this.requestMotion('item');
    }
};

Game_Actor.prototype.performActionEnd = function() {
    Game_Battler.prototype.performActionEnd.call(this);
};

Game_Actor.prototype.performAttack = function() {
    var weapons = this.weapons();
    var wtypeId = weapons[0] ? weapons[0].wtypeId : 0;
    var attackMotion = $dataSystem.attackMotions[wtypeId];
    if (attackMotion) {
        if (attackMotion.type === 0) {
            this.requestMotion('thrust');
        } else if (attackMotion.type === 1) {
            this.requestMotion('swing');
        } else if (attackMotion.type === 2) {
            this.requestMotion('missile');
        }
        this.startWeaponAnimation(attackMotion.weaponImageId);
    }
};

Game_Actor.prototype.performDamage = function() {
    Game_Battler.prototype.performDamage.call(this);
    if (this.isSpriteVisible()) {
        this.requestMotion('damage');
    } else {
        $gameScreen.startShake(5, 5, 10);
    }
    SoundManager.playActorDamage();
};

Game_Actor.prototype.performEvasion = function() {
    Game_Battler.prototype.performEvasion.call(this);
    this.requestMotion('evade');
};

Game_Actor.prototype.performMagicEvasion = function() {
    Game_Battler.prototype.performMagicEvasion.call(this);
    this.requestMotion('evade');
};

Game_Actor.prototype.performCounter = function() {
    Game_Battler.prototype.performCounter.call(this);
    this.performAttack();
};

Game_Actor.prototype.performCollapse = function() {
    Game_Battler.prototype.performCollapse.call(this);
    if ($gameParty.inBattle()) {
        SoundManager.playActorCollapse();
    }
};

Game_Actor.prototype.performVictory = function() {
    if (this.canMove()) {
        this.requestMotion('victory');
    }
};

Game_Actor.prototype.performEscape = function() {
    if (this.canMove()) {
        this.requestMotion('escape');
    }
};

Game_Actor.prototype.makeActionList = function() {
    var list = [];
    var action = new Game_Action(this);
    action.setAttack();
    list.push(action);
    this.usableSkills().forEach(function(skill) {
        action = new Game_Action(this);
        action.setSkill(skill.id);
        list.push(action);
    }, this);
    return list;
};

Game_Actor.prototype.makeAutoBattleActions = function() {
    for (var i = 0; i < this.numActions(); i++) {
        var list = this.makeActionList();
        var maxValue = Number.MIN_VALUE;
        for (var j = 0; j < list.length; j++) {
            var value = list[j].evaluate();
            if (value > maxValue) {
                maxValue = value;
                this.setAction(i, list[j]);
            }
        }
    }
    this.setActionState('waiting');
};

Game_Actor.prototype.makeConfusionActions = function() {
    for (var i = 0; i < this.numActions(); i++) {
        this.action(i).setConfusion();
    }
    this.setActionState('waiting');
};

Game_Actor.prototype.makeActions = function() {
    Game_Battler.prototype.makeActions.call(this);
    if (this.numActions() > 0) {
        this.setActionState('undecided');
    } else {
        this.setActionState('waiting');
    }
    if (this.isAutoBattle()) {
        this.makeAutoBattleActions();
    } else if (this.isConfused()) {
        this.makeConfusionActions();
    }
};

Game_Actor.prototype.onPlayerWalk = function() {
    this.clearResult();
    this.checkFloorEffect();
    if ($gamePlayer.isNormal()) {
        this.turnEndOnMap();
        this.states().forEach(function(state) {
            this.updateStateSteps(state);
        }, this);
        this.showAddedStates();
        this.showRemovedStates();
    }
};

Game_Actor.prototype.updateStateSteps = function(state) {
    if (state.removeByWalking) {
        if (this._stateSteps[state.id] > 0) {
            if (--this._stateSteps[state.id] === 0) {
                this.removeState(state.id);
            }
        }
    }
};

Game_Actor.prototype.showAddedStates = function() {
    this.result().addedStateObjects().forEach(function(state) {
        if (state.message1) {
            $gameMessage.add(this._name + state.message1);
        }
    }, this);
};

Game_Actor.prototype.showRemovedStates = function() {
    this.result().removedStateObjects().forEach(function(state) {
        if (state.message4) {
            $gameMessage.add(this._name + state.message4);
        }
    }, this);
};

Game_Actor.prototype.stepsForTurn = function() {
    return 20;
};

Game_Actor.prototype.turnEndOnMap = function() {
    if ($gameParty.steps() % this.stepsForTurn() === 0) {
        this.onTurnEnd();
        if (this.result().hpDamage > 0) {
            this.performMapDamage();
        }
    }
};

Game_Actor.prototype.checkFloorEffect = function() {
    if ($gamePlayer.isOnDamageFloor()) {
        this.executeFloorDamage();
    }
};

Game_Actor.prototype.executeFloorDamage = function() {
    var damage = Math.floor(this.basicFloorDamage() * this.fdr);
    damage = Math.min(damage, this.maxFloorDamage());
    this.gainHp(-damage);
    if (damage > 0) {
        this.performMapDamage();
    }
};

Game_Actor.prototype.basicFloorDamage = function() {
    return 10;
};

Game_Actor.prototype.maxFloorDamage = function() {
    return $dataSystem.optFloorDeath ? this.hp : Math.max(this.hp - 1, 0);
};

Game_Actor.prototype.performMapDamage = function() {
    if (!$gameParty.inBattle()) {
        $gameScreen.startFlashForDamage();
    }
};

Game_Actor.prototype.clearActions = function() {
    Game_Battler.prototype.clearActions.call(this);
    this._actionInputIndex = 0;
};

Game_Actor.prototype.inputtingAction = function() {
    return this.action(this._actionInputIndex);
};

Game_Actor.prototype.selectNextCommand = function() {
    if (this._actionInputIndex < this.numActions() - 1) {
        this._actionInputIndex++;
        return true;
    } else {
        return false;
    }
};

Game_Actor.prototype.selectPreviousCommand = function() {
    if (this._actionInputIndex > 0) {
        this._actionInputIndex--;
        return true;
    } else {
        return false;
    }
};

Game_Actor.prototype.lastMenuSkill = function() {
    return this._lastMenuSkill.object();
};

Game_Actor.prototype.setLastMenuSkill = function(skill) {
    this._lastMenuSkill.setObject(skill);
};

Game_Actor.prototype.lastBattleSkill = function() {
    return this._lastBattleSkill.object();
};

Game_Actor.prototype.setLastBattleSkill = function(skill) {
    this._lastBattleSkill.setObject(skill);
};

Game_Actor.prototype.lastCommandSymbol = function() {
    return this._lastCommandSymbol;
};

Game_Actor.prototype.setLastCommandSymbol = function(symbol) {
    this._lastCommandSymbol = symbol;
};

Game_Actor.prototype.testEscape = function(item) {
    return item.effects.some(function(effect, index, ar) {
        return effect && effect.code === Game_Action.EFFECT_SPECIAL;
    });
};

Game_Actor.prototype.meetsUsableItemConditions = function(item) {
    if ($gameParty.inBattle() && !BattleManager.canEscape() && this.testEscape(item)) {
        return false;
    }
    return Game_BattlerBase.prototype.meetsUsableItemConditions.call(this, item);
};
