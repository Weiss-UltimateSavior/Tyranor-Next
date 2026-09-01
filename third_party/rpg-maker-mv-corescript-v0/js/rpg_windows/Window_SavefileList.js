//-----------------------------------------------------------------------------
// Window_SavefileList
//
// The window for selecting a save file on the save and load screens.

function Window_SavefileList() {
    this.initialize.apply(this, arguments);
}

Window_SavefileList.prototype = Object.create(Window_Selectable.prototype);
Window_SavefileList.prototype.constructor = Window_SavefileList;

Window_SavefileList.prototype.initialize = function(x, y, width, height) {
    Window_Selectable.prototype.initialize.call(this, x, y, width, height);
    this.activate();
    this._mode = null;
};

Window_SavefileList.prototype.setMode = function(mode) {
    this._mode = mode;
};

Window_SavefileList.prototype.maxItems = function() {
    return DataManager.maxSavefiles();
};

Window_SavefileList.prototype.maxVisibleItems = function() {
    return 5;
};

Window_SavefileList.prototype.itemHeight = function() {
    var innerHeight = this.height - this.padding * 2;
    return Math.floor(innerHeight / this.maxVisibleItems());
};

Window_SavefileList.prototype.drawItem = function(index) {
    var id = index + 1;
    var valid = DataManager.isThisGameFile(id);
    var info = DataManager.loadSavefileInfo(id);
    var rect = this.itemRectForText(index);
    this.resetTextColor();
    if (this._mode === 'load') {
        this.changePaintOpacity(valid);
    }
    this.drawFileId(id, rect.x, rect.y);
    if (info) {
        this.changePaintOpacity(valid);
        this.drawContents(info, rect, valid);
        this.changePaintOpacity(true);
    }
};

Window_SavefileList.prototype.drawFileId = function(id, x, y) {
    if (DataManager.isAutoSaveFileId(id)) {
        if (this._mode === 'save') {
            this.changePaintOpacity(false);
        }
        this.drawText(TextManager.file + ' ' + id + '(Auto)', x, y, 180);
    } else {
        this.drawText(TextManager.file + ' ' + id, x, y, 180);
    }
};

Window_SavefileList.prototype.drawContents = function(info, rect, valid) {
    var bottom = rect.y + rect.height;
    if (rect.width >= 420) {
        this.drawGameTitle(info, rect.x + 192, rect.y, rect.width - 192);
        if (valid) {
            this.drawPartyCharacters(info, rect.x + 220, bottom - 4);
        }
    }
    var lineHeight = this.lineHeight();
    var y2 = bottom - lineHeight;
    if (y2 >= lineHeight) {
        this.drawPlaytime(info, rect.x, y2, rect.width);
    }
};

Window_SavefileList.prototype.drawGameTitle = function(info, x, y, width) {
    if (info.title) {
        this.drawText(info.title, x, y, width);
    }
};

Window_SavefileList.prototype.drawPartyCharacters = function(info, x, y) {
    if (info.characters) {
        for (var i = 0; i < info.characters.length; i++) {
            var data = info.characters[i];
            this.drawCharacter(data[0], data[1], x + i * 48, y);
        }
    }
};

Window_SavefileList.prototype.drawPlaytime = function(info, x, y, width) {
    if (info.playtime) {
        this.drawText(info.playtime, x, y, width, 'right');
    }
};

Window_SavefileList.prototype.playOkSound = function() {
};
