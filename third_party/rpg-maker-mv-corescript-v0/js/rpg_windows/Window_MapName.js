//-----------------------------------------------------------------------------
// Window_MapName
//
// The window for displaying the map name on the map screen.

function Window_MapName() {
    this.initialize.apply(this, arguments);
}

Window_MapName.prototype = Object.create(Window_Base.prototype);
Window_MapName.prototype.constructor = Window_MapName;

Window_MapName.prototype.initialize = function() {
    var wight = this.windowWidth();
    var height = this.windowHeight();
    Window_Base.prototype.initialize.call(this, 0, 0, wight, height);
    this.opacity = 0;
    this.contentsOpacity = 0;
    this._showCount = 0;
    this.refresh();
};

Window_MapName.prototype.windowWidth = function() {
    return 360;
};

Window_MapName.prototype.windowHeight = function() {
    return this.fittingHeight(1);
};

Window_MapName.prototype.update = function() {
    Window_Base.prototype.update.call(this);
    if (this._showCount > 0 && $gameMap.isNameDisplayEnabled()) {
        this.updateFadeIn();
        this._showCount--;
    } else {
        this.updateFadeOut();
    }
};

Window_MapName.prototype.updateFadeIn = function() {
    this.contentsOpacity += 16;
};

Window_MapName.prototype.updateFadeOut = function() {
    this.contentsOpacity -= 16;
};

Window_MapName.prototype.open = function() {
    this.refresh();
    this._showCount = 150;
};

Window_MapName.prototype.close = function() {
    this._showCount = 0;
};

Window_MapName.prototype.refresh = function() {
    this.contents.clear();
    if ($gameMap.displayName()) {
        var width = this.contentsWidth();
        this.drawBackground(0, 0, width, this.lineHeight());
        this.drawText($gameMap.displayName(), 0, 0, width, 'center');
    }
};

Window_MapName.prototype.drawBackground = function(x, y, width, height) {
    var color1 = this.dimColor1();
    var color2 = this.dimColor2();
    this.contents.gradientFillRect(x, y, width / 2, height, color2, color1);
    this.contents.gradientFillRect(x + width / 2, y, width / 2, height, color1, color2);
};
