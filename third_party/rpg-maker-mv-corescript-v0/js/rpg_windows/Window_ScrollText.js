//-----------------------------------------------------------------------------
// Window_ScrollText
//
// The window for displaying scrolling text. No frame is displayed, but it
// is handled as a window for convenience.

function Window_ScrollText() {
    this.initialize.apply(this, arguments);
}

Window_ScrollText.prototype = Object.create(Window_Base.prototype);
Window_ScrollText.prototype.constructor = Window_ScrollText;

Window_ScrollText.prototype.initialize = function() {
    var width = Graphics.boxWidth;
    var height = Graphics.boxHeight;
    Window_Base.prototype.initialize.call(this, 0, 0, width, height);
    this.opacity = 0;
    this.hide();
    this._text = '';
    this._allTextHeight = 0;
};

Window_ScrollText.prototype.update = function() {
    Window_Base.prototype.update.call(this);
    if ($gameMessage.scrollMode()) {
        if (this._text) {
            this.updateMessage();
        }
        if (!this._text && $gameMessage.hasText()) {
            this.startMessage();
        }
    }
};

Window_ScrollText.prototype.startMessage = function() {
    this._text = $gameMessage.allText();
    this.refresh();
    this.show();
};

Window_ScrollText.prototype.refresh = function() {
    var textState = { index: 0 };
    textState.text = this.convertEscapeCharacters(this._text);
    this.resetFontSettings();
    this._allTextHeight = this.calcTextHeight(textState, true);
    this.createContents();
    this.origin.y = -this.height;
    this.drawTextEx(this._text, this.textPadding(), 1);
};

Window_ScrollText.prototype.contentsHeight = function() {
    return Math.max(this._allTextHeight, 1);
};

Window_ScrollText.prototype.updateMessage = function() {
    this.origin.y += this.scrollSpeed();
    if (this.origin.y >= this.contents.height) {
        this.terminateMessage();
    }
};

Window_ScrollText.prototype.scrollSpeed = function() {
    var speed = $gameMessage.scrollSpeed() / 2;
    if (this.isFastForward()) {
        speed *= this.fastForwardRate();
    }
    return speed;
};

Window_ScrollText.prototype.isFastForward = function() {
    if ($gameMessage.scrollNoFast()) {
        return false;
    } else {
        return (Input.isPressed('ok') || Input.isPressed('shift') ||
                TouchInput.isPressed());
    }
};

Window_ScrollText.prototype.fastForwardRate = function() {
    return 3;
};

Window_ScrollText.prototype.terminateMessage = function() {
    this._text = null;
    $gameMessage.clear();
    this.hide();
};
