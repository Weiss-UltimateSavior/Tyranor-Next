//-----------------------------------------------------------------------------
// Window_ItemCategory
//
// The window for selecting a category of items on the item and shop screens.

function Window_ItemCategory() {
    this.initialize.apply(this, arguments);
}

Window_ItemCategory.prototype = Object.create(Window_HorzCommand.prototype);
Window_ItemCategory.prototype.constructor = Window_ItemCategory;

Window_ItemCategory.prototype.initialize = function() {
    Window_HorzCommand.prototype.initialize.call(this, 0, 0);
};

Window_ItemCategory.prototype.windowWidth = function() {
    return Graphics.boxWidth;
};

Window_ItemCategory.prototype.maxCols = function() {
    return 4;
};

Window_ItemCategory.prototype.update = function() {
    Window_HorzCommand.prototype.update.call(this);
    if (this._itemWindow) {
        this._itemWindow.setCategory(this.currentSymbol());
    }
};

Window_ItemCategory.prototype.makeCommandList = function() {
    this.addCommand(TextManager.item,    'item');
    this.addCommand(TextManager.weapon,  'weapon');
    this.addCommand(TextManager.armor,   'armor');
    this.addCommand(TextManager.keyItem, 'keyItem');
};

Window_ItemCategory.prototype.setItemWindow = function(itemWindow) {
    this._itemWindow = itemWindow;
};
