//-----------------------------------------------------------------------------
// Window_ShopCommand
//
// The window for selecting buy/sell on the shop screen.

function Window_ShopCommand() {
    this.initialize.apply(this, arguments);
}

Window_ShopCommand.prototype = Object.create(Window_HorzCommand.prototype);
Window_ShopCommand.prototype.constructor = Window_ShopCommand;

Window_ShopCommand.prototype.initialize = function(width, purchaseOnly) {
    this._windowWidth = width;
    this._purchaseOnly = purchaseOnly;
    Window_HorzCommand.prototype.initialize.call(this, 0, 0);
};

Window_ShopCommand.prototype.windowWidth = function() {
    return this._windowWidth;
};

Window_ShopCommand.prototype.maxCols = function() {
    return 3;
};

Window_ShopCommand.prototype.makeCommandList = function() {
    this.addCommand(TextManager.buy,    'buy');
    this.addCommand(TextManager.sell,   'sell',   !this._purchaseOnly);
    this.addCommand(TextManager.cancel, 'cancel');
};
