//-----------------------------------------------------------------------------
// Window_Gold
//
// The window for displaying the party's gold.

function Window_Gold() {
    this.initialize.apply(this, arguments);
}

Window_Gold.prototype = Object.create(Window_Base.prototype);
Window_Gold.prototype.constructor = Window_Gold;

Window_Gold.prototype.initialize = function(x, y) {
    var width = this.windowWidth();
    var height = this.windowHeight();
    Window_Base.prototype.initialize.call(this, x, y, width, height);
    this.refresh();
};

Window_Gold.prototype.windowWidth = function() {
    return 240;
};

Window_Gold.prototype.windowHeight = function() {
    return this.fittingHeight(1);
};

Window_Gold.prototype.refresh = function() {
    var x = this.textPadding();
    var width = this.contents.width - this.textPadding() * 2;
    this.contents.clear();
    this.drawCurrencyValue(this.value(), this.currencyUnit(), x, 0, width);
};

Window_Gold.prototype.value = function() {
    return $gameParty.gold();
};

Window_Gold.prototype.currencyUnit = function() {
    return TextManager.currencyUnit;
};

Window_Gold.prototype.open = function() {
    this.refresh();
    Window_Base.prototype.open.call(this);
};
