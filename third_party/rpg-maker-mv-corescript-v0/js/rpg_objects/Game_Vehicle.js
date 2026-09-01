//-----------------------------------------------------------------------------
// Game_Vehicle
//
// The game object class for a vehicle.

function Game_Vehicle() {
    this.initialize.apply(this, arguments);
}

Game_Vehicle.prototype = Object.create(Game_Character.prototype);
Game_Vehicle.prototype.constructor = Game_Vehicle;

Game_Vehicle.prototype.initialize = function(type) {
    Game_Character.prototype.initialize.call(this);
    this._type = type;
    this.resetDirection();
    this.initMoveSpeed();
    this.loadSystemSettings();
};

Game_Vehicle.prototype.initMembers = function() {
    Game_Character.prototype.initMembers.call(this);
    this._type = '';
    this._mapId = 0;
    this._altitude = 0;
    this._driving = false;
    this._bgm = null;
};

Game_Vehicle.prototype.isBoat = function() {
    return this._type === 'boat';
};

Game_Vehicle.prototype.isShip = function() {
    return this._type === 'ship';
};

Game_Vehicle.prototype.isAirship = function() {
    return this._type === 'airship';
};

Game_Vehicle.prototype.resetDirection = function() {
    this.setDirection(4);
};

Game_Vehicle.prototype.initMoveSpeed = function() {
    if (this.isBoat()) {
        this.setMoveSpeed(4);
    } else if (this.isShip()) {
        this.setMoveSpeed(5);
    } else if (this.isAirship()) {
        this.setMoveSpeed(6);
    }
};

Game_Vehicle.prototype.vehicle = function() {
    if (this.isBoat()) {
        return $dataSystem.boat;
    } else if (this.isShip()) {
        return $dataSystem.ship;
    } else if (this.isAirship()) {
        return $dataSystem.airship;
    } else {
        return null;
    }
};

Game_Vehicle.prototype.loadSystemSettings = function() {
    var vehicle = this.vehicle();
    this._mapId = vehicle.startMapId;
    this.setPosition(vehicle.startX, vehicle.startY);
    this.setImage(vehicle.characterName, vehicle.characterIndex);
};

Game_Vehicle.prototype.refresh = function() {
    if (this._driving) {
        this._mapId = $gameMap.mapId();
        this.syncWithPlayer();
    } else if (this._mapId === $gameMap.mapId()) {
        this.locate(this.x, this.y);
    }
    if (this.isAirship()) {
        this.setPriorityType(this._driving ? 2 : 0);
    } else {
        this.setPriorityType(1);
    }
    this.setWalkAnime(this._driving);
    this.setStepAnime(this._driving);
    this.setTransparent(this._mapId !== $gameMap.mapId());
};

Game_Vehicle.prototype.setLocation = function(mapId, x, y) {
    this._mapId = mapId;
    this.setPosition(x, y);
    this.refresh();
};

Game_Vehicle.prototype.pos = function(x, y) {
    if (this._mapId === $gameMap.mapId()) {
        return Game_Character.prototype.pos.call(this, x, y);
    } else {
        return false;
    }
};

Game_Vehicle.prototype.isMapPassable = function(x, y, d) {
    var x2 = $gameMap.roundXWithDirection(x, d);
    var y2 = $gameMap.roundYWithDirection(y, d);
    if (this.isBoat()) {
        return $gameMap.isBoatPassable(x2, y2);
    } else if (this.isShip()) {
        return $gameMap.isShipPassable(x2, y2);
    } else if (this.isAirship()) {
        return true;
    } else {
        return false;
    }
};

Game_Vehicle.prototype.getOn = function() {
    this._driving = true;
    this.setWalkAnime(true);
    this.setStepAnime(true);
    $gameSystem.saveWalkingBgm();
    this.playBgm();
};

Game_Vehicle.prototype.getOff = function() {
    this._driving = false;
    this.setWalkAnime(false);
    this.setStepAnime(false);
    this.resetDirection();
    $gameSystem.replayWalkingBgm();
};

Game_Vehicle.prototype.setBgm = function(bgm) {
    this._bgm = bgm;
};

Game_Vehicle.prototype.playBgm = function() {
    AudioManager.playBgm(this._bgm || this.vehicle().bgm);
};

Game_Vehicle.prototype.syncWithPlayer = function() {
    this.copyPosition($gamePlayer);
    this.refreshBushDepth();
};

Game_Vehicle.prototype.screenY = function() {
    return Game_Character.prototype.screenY.call(this) - this._altitude;
};

Game_Vehicle.prototype.shadowX = function() {
    return this.screenX();
};

Game_Vehicle.prototype.shadowY = function() {
    return this.screenY() + this._altitude;
};

Game_Vehicle.prototype.shadowOpacity = function() {
    return 255 * this._altitude / this.maxAltitude();
};

Game_Vehicle.prototype.canMove = function() {
    if (this.isAirship()) {
        return this.isHighest();
    } else {
        return true;
    }
};

Game_Vehicle.prototype.update = function() {
    Game_Character.prototype.update.call(this);
    if (this.isAirship()) {
        this.updateAirship();
    }
};

Game_Vehicle.prototype.updateAirship = function() {
    this.updateAirshipAltitude();
    this.setStepAnime(this.isHighest());
    this.setPriorityType(this.isLowest() ? 0 : 2);
};

Game_Vehicle.prototype.updateAirshipAltitude = function() {
    if (this._driving && !this.isHighest()) {
        this._altitude++;
    }
    if (!this._driving && !this.isLowest()) {
        this._altitude--;
    }
};

Game_Vehicle.prototype.maxAltitude = function() {
    return 48;
};

Game_Vehicle.prototype.isLowest = function() {
    return this._altitude <= 0;
};

Game_Vehicle.prototype.isHighest = function() {
    return this._altitude >= this.maxAltitude();
};

Game_Vehicle.prototype.isTakeoffOk = function() {
    return $gamePlayer.areFollowersGathered();
};

Game_Vehicle.prototype.isLandOk = function(x, y, d) {
    if (this.isAirship()) {
        if (!$gameMap.isAirshipLandOk(x, y)) {
            return false;
        }
        if ($gameMap.eventsXy(x, y).length > 0) {
            return false;
        }
    } else {
        var x2 = $gameMap.roundXWithDirection(x, d);
        var y2 = $gameMap.roundYWithDirection(y, d);
        if (!$gameMap.isValid(x2, y2)) {
            return false;
        }
        if (!$gameMap.isPassable(x2, y2, this.reverseDir(d))) {
            return false;
        }
        if (this.isCollidedWithCharacters(x2, y2)) {
            return false;
        }
    }
    return true;
};
