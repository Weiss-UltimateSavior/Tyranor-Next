//-----------------------------------------------------------------------------
// Game_Event
//
// The game object class for an event. It contains functionality for event page
// switching and running parallel process events.

function Game_Event() {
    this.initialize.apply(this, arguments);
}

Game_Event.prototype = Object.create(Game_Character.prototype);
Game_Event.prototype.constructor = Game_Event;

Game_Event.prototype.initialize = function(mapId, eventId) {
    Game_Character.prototype.initialize.call(this);
    this._mapId = mapId;
    this._eventId = eventId;
    this.locate(this.event().x, this.event().y);
    this.refresh();
};

Game_Event.prototype.initMembers = function() {
    Game_Character.prototype.initMembers.call(this);
    this._moveType = 0;
    this._trigger = 0;
    this._starting = false;
    this._erased = false;
    this._pageIndex = -2;
    this._originalPattern = 1;
    this._originalDirection = 2;
    this._prelockDirection = 0;
    this._locked = false;
};

Game_Event.prototype.eventId = function() {
    return this._eventId;
};

Game_Event.prototype.event = function() {
    return $dataMap.events[this._eventId];
};

Game_Event.prototype.page = function() {
    return this.event().pages[this._pageIndex];
};

Game_Event.prototype.list = function() {
    return this.page().list;
};

Game_Event.prototype.isCollidedWithCharacters = function(x, y) {
    return (Game_Character.prototype.isCollidedWithCharacters.call(this, x, y) ||
            this.isCollidedWithPlayerCharacters(x, y));
};

Game_Event.prototype.isCollidedWithEvents = function(x, y) {
    var events = $gameMap.eventsXyNt(x, y);
    return events.length > 0;
};

Game_Event.prototype.isCollidedWithPlayerCharacters = function(x, y) {
    return this.isNormalPriority() && $gamePlayer.isCollided(x, y);
};

Game_Event.prototype.lock = function() {
    if (!this._locked) {
        this._prelockDirection = this.direction();
        this.turnTowardPlayer();
        this._locked = true;
    }
};

Game_Event.prototype.unlock = function() {
    if (this._locked) {
        this._locked = false;
        this.setDirection(this._prelockDirection);
    }
};

Game_Event.prototype.updateStop = function() {
    if (this._locked) {
        this.resetStopCount();
    }
    Game_Character.prototype.updateStop.call(this);
    if (!this.isMoveRouteForcing()) {
        this.updateSelfMovement();
    }
};

Game_Event.prototype.updateSelfMovement = function() {
    if (!this._locked && this.isNearTheScreen() &&
            this.checkStop(this.stopCountThreshold())) {
        switch (this._moveType) {
        case 1:
            this.moveTypeRandom();
            break;
        case 2:
            this.moveTypeTowardPlayer();
            break;
        case 3:
            this.moveTypeCustom();
            break;
        }
    }
};

Game_Event.prototype.stopCountThreshold = function() {
    return 30 * (5 - this.moveFrequency());
};

Game_Event.prototype.moveTypeRandom = function() {
    switch (Math.randomInt(6)) {
    case 0: case 1:
        this.moveRandom();
        break;
    case 2: case 3: case 4:
        this.moveForward();
        break;
    case 5:
        this.resetStopCount();
        break;
    }
};

Game_Event.prototype.moveTypeTowardPlayer = function() {
    if (this.isNearThePlayer()) {
        switch (Math.randomInt(6)) {
        case 0: case 1: case 2: case 3:
            this.moveTowardPlayer();
            break;
        case 4:
            this.moveRandom();
            break;
        case 5:
            this.moveForward();
            break;
        }
    } else {
        this.moveRandom();
    }
};

Game_Event.prototype.isNearThePlayer = function() {
    var sx = Math.abs(this.deltaXFrom($gamePlayer.x));
    var sy = Math.abs(this.deltaYFrom($gamePlayer.y));
    return sx + sy < 20;
};

Game_Event.prototype.moveTypeCustom = function() {
    this.updateRoutineMove();
};

Game_Event.prototype.isStarting = function() {
    return this._starting;
};

Game_Event.prototype.clearStartingFlag = function() {
    this._starting = false;
};

Game_Event.prototype.isTriggerIn = function(triggers) {
    return triggers.contains(this._trigger);
};

Game_Event.prototype.start = function() {
    var list = this.list();
    if (list && list.length > 1) {
        this._starting = true;
        if (this.isTriggerIn([0,1,2])) {
            this.lock();
        }
    }
};

Game_Event.prototype.erase = function() {
    this._erased = true;
    this.refresh();
};

Game_Event.prototype.refresh = function() {
    var newPageIndex = this._erased ? -1 : this.findProperPageIndex();
    if (this._pageIndex !== newPageIndex) {
        this._pageIndex = newPageIndex;
        this.setupPage();
    }
};

Game_Event.prototype.findProperPageIndex = function() {
    var pages = this.event().pages;
    for (var i = pages.length - 1; i >= 0; i--) {
        var page = pages[i];
        if (this.meetsConditions(page)) {
            return i;
        }
    }
    return -1;
};

Game_Event.prototype.meetsConditions = function(page) {
    var c = page.conditions;
    if (c.switch1Valid) {
        if (!$gameSwitches.value(c.switch1Id)) {
            return false;
        }
    }
    if (c.switch2Valid) {
        if (!$gameSwitches.value(c.switch2Id)) {
            return false;
        }
    }
    if (c.variableValid) {
        if ($gameVariables.value(c.variableId) < c.variableValue) {
            return false;
        }
    }
    if (c.selfSwitchValid) {
        var key = [this._mapId, this._eventId, c.selfSwitchCh];
        if ($gameSelfSwitches.value(key) !== true) {
            return false;
        }
    }
    if (c.itemValid) {
        var item = $dataItems[c.itemId];
        if (!$gameParty.hasItem(item)) {
            return false;
        }
    }
    if (c.actorValid) {
        var actor = $gameActors.actor(c.actorId);
        if (!$gameParty.members().contains(actor)) {
            return false;
        }
    }
    return true;
};

Game_Event.prototype.setupPage = function() {
    if (this._pageIndex >= 0) {
        this.setupPageSettings();
    } else {
        this.clearPageSettings();
    }
    this.refreshBushDepth();
    this.clearStartingFlag();
    this.checkEventTriggerAuto();
};

Game_Event.prototype.clearPageSettings = function() {
    this.setImage('', 0);
    this._moveType = 0;
    this._trigger = null;
    this._interpreter = null;
    this.setThrough(true);
};

Game_Event.prototype.setupPageSettings = function() {
    var page = this.page();
    var image = page.image;
    if (image.tileId > 0) {
        this.setTileImage(image.tileId);
    } else {
        this.setImage(image.characterName, image.characterIndex);
    }
    if (this._originalDirection !== image.direction) {
        this._originalDirection = image.direction;
        this._prelockDirection = 0;
        this.setDirectionFix(false);
        this.setDirection(image.direction);
    }
    if (this._originalPattern !== image.pattern) {
        this._originalPattern = image.pattern;
        this.setPattern(image.pattern);
    }
    this.setMoveSpeed(page.moveSpeed);
    this.setMoveFrequency(page.moveFrequency);
    this.setPriorityType(page.priorityType);
    this.setWalkAnime(page.walkAnime);
    this.setStepAnime(page.stepAnime);
    this.setDirectionFix(page.directionFix);
    this.setThrough(page.through);
    this.setMoveRoute(page.moveRoute);
    this._moveType = page.moveType;
    this._trigger = page.trigger;
    if (this._trigger === 4) {
        this._interpreter = new Game_Interpreter();
    } else {
        this._interpreter = null;
    }
};

Game_Event.prototype.isOriginalPattern = function() {
    return this.pattern() === this._originalPattern;
};

Game_Event.prototype.resetPattern = function() {
    this.setPattern(this._originalPattern);
};

Game_Event.prototype.checkEventTriggerTouch = function(x, y) {
    if (!$gameMap.isEventRunning()) {
        if (this._trigger === 2 && $gamePlayer.pos(x, y)) {
            if (!this.isJumping() && this.isNormalPriority()) {
                this.start();
            }
        }
    }
};

Game_Event.prototype.checkEventTriggerAuto = function() {
    if (this._trigger === 3) {
        this.start();
    }
};

Game_Event.prototype.update = function() {
    Game_Character.prototype.update.call(this);
    this.checkEventTriggerAuto();
    this.updateParallel();
};

Game_Event.prototype.updateParallel = function() {
    if (this._interpreter) {
        if (!this._interpreter.isRunning()) {
            this._interpreter.setup(this.list(), this._eventId);
            this._interpreter.setEventInfo(this.getEventInfo());
        }
        this._interpreter.update();
    }
};

Game_Event.prototype.locate = function(x, y) {
    Game_Character.prototype.locate.call(this, x, y);
    this._prelockDirection = 0;
};

Game_Event.prototype.forceMoveRoute = function(moveRoute) {
    Game_Character.prototype.forceMoveRoute.call(this, moveRoute);
    this._prelockDirection = 0;
};

Game_Event.prototype.getEventInfo = function() {
    return { eventType: "map_event", mapId: this._mapId, mapEventId: this._eventId, page: this._pageIndex + 1 };
};
