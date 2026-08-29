//-----------------------------------------------------------------------------
// Window_GameEnd
//
// The window for selecting "Go to Title" on the game end screen.

function Window_GameEnd() {
    this.initialize.apply(this, arguments);
}

Window_GameEnd.prototype = Object.create(Window_Command.prototype);
Window_GameEnd.prototype.constructor = Window_GameEnd;

Window_GameEnd.prototype.initialize = function() {
    Window_Command.prototype.initialize.call(this, 0, 0);
    this.updatePlacement();
    this.openness = 0;
    this.open();
};

Window_GameEnd.prototype.windowWidth = function() {
    return 240;
};

Window_GameEnd.prototype.updatePlacement = function() {
    this.x = (Graphics.boxWidth - this.width) / 2;
    this.y = (Graphics.boxHeight - this.height) / 2;
};

Window_GameEnd.prototype.makeCommandList = function() {
    this.addCommand(TextManager.toTitle, 'toTitle');
    this.addCommand(TextManager.cancel,  'cancel');
};
