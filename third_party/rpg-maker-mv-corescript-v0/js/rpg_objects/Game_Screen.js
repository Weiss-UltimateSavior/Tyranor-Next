//-----------------------------------------------------------------------------
// Game_Screen
//
// The game object class for screen effect data, such as changes in color tone
// and flashes.

function Game_Screen() {
    this.initialize.apply(this, arguments);
}

Game_Screen.prototype.initialize = function() {
    this.clear();
};

Game_Screen.prototype.clear = function() {
    this.clearFade();
    this.clearTone();
    this.clearFlash();
    this.clearShake();
    this.clearZoom();
    this.clearWeather();
    this.clearPictures();
};

Game_Screen.prototype.onBattleStart = function() {
    this.clearFade();
    this.clearFlash();
    this.clearShake();
    this.clearZoom();
    this.eraseBattlePictures();
};

Game_Screen.prototype.brightness = function() {
    return this._brightness;
};

Game_Screen.prototype.tone = function() {
    return this._tone;
};

Game_Screen.prototype.flashColor = function() {
    return this._flashColor;
};

Game_Screen.prototype.shake = function() {
    return this._shake;
};

Game_Screen.prototype.zoomX = function() {
    return this._zoomX;
};

Game_Screen.prototype.zoomY = function() {
    return this._zoomY;
};

Game_Screen.prototype.zoomScale = function() {
    return this._zoomScale;
};

Game_Screen.prototype.weatherType = function() {
    return this._weatherType;
};

Game_Screen.prototype.weatherPower = function() {
    return this._weatherPower;
};

Game_Screen.prototype.picture = function(pictureId) {
    var realPictureId = this.realPictureId(pictureId);
    return this._pictures[realPictureId];
};

Game_Screen.prototype.realPictureId = function(pictureId) {
    if ($gameParty.inBattle()) {
        return pictureId + this.maxPictures();
    } else {
        return pictureId;
    }
};

Game_Screen.prototype.clearFade = function() {
    this._brightness = 255;
    this._fadeOutDuration = 0;
    this._fadeInDuration = 0;
};

Game_Screen.prototype.clearTone = function() {
    this._tone = [0, 0, 0, 0];
    this._toneTarget = [0, 0, 0, 0];
    this._toneDuration = 0;
};

Game_Screen.prototype.clearFlash = function() {
    this._flashColor = [0, 0, 0, 0];
    this._flashDuration = 0;
};

Game_Screen.prototype.clearShake = function() {
    this._shakePower = 0;
    this._shakeSpeed = 0;
    this._shakeDuration = 0;
    this._shakeDirection = 1;
    this._shake = 0;
};

Game_Screen.prototype.clearZoom = function() {
    this._zoomX = 0;
    this._zoomY = 0;
    this._zoomScale = 1;
    this._zoomScaleTarget = 1;
    this._zoomDuration = 0;
};

Game_Screen.prototype.clearWeather = function() {
    this._weatherType = 'none';
    this._weatherPower = 0;
    this._weatherPowerTarget = 0;
    this._weatherDuration = 0;
};

Game_Screen.prototype.clearPictures = function() {
    this._pictures = [];
};

Game_Screen.prototype.eraseBattlePictures = function() {
    this._pictures = this._pictures.slice(0, this.maxPictures() + 1);
};

Game_Screen.prototype.maxPictures = function() {
    return 100;
};

Game_Screen.prototype.startFadeOut = function(duration) {
    this._fadeOutDuration = duration;
    this._fadeInDuration = 0;
};

Game_Screen.prototype.startFadeIn = function(duration) {
    this._fadeInDuration = duration;
    this._fadeOutDuration = 0;
};

Game_Screen.prototype.startTint = function(tone, duration) {
    this._toneTarget = tone.clone();
    this._toneDuration = duration;
    if (this._toneDuration === 0) {
        this._tone = this._toneTarget.clone();
    }
};

Game_Screen.prototype.startFlash = function(color, duration) {
    this._flashColor = color.clone();
    this._flashDuration = duration;
};

Game_Screen.prototype.startShake = function(power, speed, duration) {
    this._shakePower = power;
    this._shakeSpeed = speed;
    this._shakeDuration = duration;
};

Game_Screen.prototype.startZoom = function(x, y, scale, duration) {
    this._zoomX = x;
    this._zoomY = y;
    this._zoomScaleTarget = scale;
    this._zoomDuration = duration;
};

Game_Screen.prototype.setZoom = function(x, y, scale) {
    this._zoomX = x;
    this._zoomY = y;
    this._zoomScale = scale;
};

Game_Screen.prototype.changeWeather = function(type, power, duration) {
    if (type !== 'none' || duration === 0) {
        this._weatherType = type;
    }
    this._weatherPowerTarget = type === 'none' ? 0 : power;
    this._weatherDuration = duration;
    if (duration === 0) {
        this._weatherPower = this._weatherPowerTarget;
    }
};

Game_Screen.prototype.update = function() {
    this.updateFadeOut();
    this.updateFadeIn();
    this.updateTone();
    this.updateFlash();
    this.updateShake();
    this.updateZoom();
    this.updateWeather();
    this.updatePictures();
};

Game_Screen.prototype.updateFadeOut = function() {
    if (this._fadeOutDuration > 0) {
        var d = this._fadeOutDuration;
        this._brightness = (this._brightness * (d - 1)) / d;
        this._fadeOutDuration--;
    }
};

Game_Screen.prototype.updateFadeIn = function() {
    if (this._fadeInDuration > 0) {
        var d = this._fadeInDuration;
        this._brightness = (this._brightness * (d - 1) + 255) / d;
        this._fadeInDuration--;
    }
};

Game_Screen.prototype.updateTone = function() {
    if (this._toneDuration > 0) {
        var d = this._toneDuration;
        for (var i = 0; i < 4; i++) {
            this._tone[i] = (this._tone[i] * (d - 1) + this._toneTarget[i]) / d;
        }
        this._toneDuration--;
    }
};

Game_Screen.prototype.updateFlash = function() {
    if (this._flashDuration > 0) {
        var d = this._flashDuration;
        this._flashColor[3] *= (d - 1) / d;
        this._flashDuration--;
    }
};

Game_Screen.prototype.updateShake = function() {
    if (this._shakeDuration > 0 || this._shake !== 0) {
        var delta = (this._shakePower * this._shakeSpeed * this._shakeDirection) / 10;
        if (this._shakeDuration <= 1 && this._shake * (this._shake + delta) < 0) {
            this._shake = 0;
        } else {
            this._shake += delta;
        }
        if (this._shake > this._shakePower * 2) {
            this._shakeDirection = -1;
        }
        if (this._shake < - this._shakePower * 2) {
            this._shakeDirection = 1;
        }
        this._shakeDuration--;
    }
};

Game_Screen.prototype.updateZoom = function() {
    if (this._zoomDuration > 0) {
        var d = this._zoomDuration;
        var t = this._zoomScaleTarget;
        this._zoomScale = (this._zoomScale * (d - 1) + t) / d;
        this._zoomDuration--;
    }
};

Game_Screen.prototype.updateWeather = function() {
    if (this._weatherDuration > 0) {
        var d = this._weatherDuration;
        var t = this._weatherPowerTarget;
        this._weatherPower = (this._weatherPower * (d - 1) + t) / d;
        this._weatherDuration--;
        if (this._weatherDuration === 0 && this._weatherPowerTarget === 0) {
            this._weatherType = 'none';
        }
    }
};

Game_Screen.prototype.updatePictures = function() {
    this._pictures.forEach(function(picture) {
        if (picture) {
            picture.update();
        }
    });
};

Game_Screen.prototype.startFlashForDamage = function() {
    this.startFlash([255, 0, 0, 128], 8);
};

Game_Screen.prototype.showPicture = function(pictureId, name, origin, x, y,
                                             scaleX, scaleY, opacity, blendMode) {
    var realPictureId = this.realPictureId(pictureId);
    var picture = new Game_Picture();
    picture.show(name, origin, x, y, scaleX, scaleY, opacity, blendMode);
    this._pictures[realPictureId] = picture;
};

Game_Screen.prototype.movePicture = function(pictureId, origin, x, y, scaleX,
                                             scaleY, opacity, blendMode, duration) {
    var picture = this.picture(pictureId);
    if (picture) {
        picture.move(origin, x, y, scaleX, scaleY, opacity, blendMode, duration);
    }
};

Game_Screen.prototype.rotatePicture = function(pictureId, speed) {
    var picture = this.picture(pictureId);
    if (picture) {
        picture.rotate(speed);
    }
};

Game_Screen.prototype.tintPicture = function(pictureId, tone, duration) {
    var picture = this.picture(pictureId);
    if (picture) {
        picture.tint(tone, duration);
    }
};

Game_Screen.prototype.erasePicture = function(pictureId) {
    var realPictureId = this.realPictureId(pictureId);
    this._pictures[realPictureId] = null;
};
