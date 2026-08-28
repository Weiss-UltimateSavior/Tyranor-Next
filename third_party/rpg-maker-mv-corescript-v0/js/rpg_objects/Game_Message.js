//-----------------------------------------------------------------------------
// Game_Message
//
// The game object class for the state of the message window that displays text
// or selections, etc.

function Game_Message() {
    this.initialize.apply(this, arguments);
}

Game_Message.prototype.initialize = function() {
    this.clear();
};

Game_Message.prototype.clear = function() {
    this._texts = [];
    this._choices = [];
    this._faceName = '';
    this._faceIndex = 0;
    this._background = 0;
    this._positionType = 2;
    this._choiceDefaultType = 0;
    this._choiceCancelType = 0;
    this._choiceBackground = 0;
    this._choicePositionType = 2;
    this._numInputVariableId = 0;
    this._numInputMaxDigits = 0;
    this._itemChoiceVariableId = 0;
    this._itemChoiceItypeId = 0;
    this._scrollMode = false;
    this._scrollSpeed = 2;
    this._scrollNoFast = false;
    this._choiceCallback = null;
};

Game_Message.prototype.choices = function() {
    return this._choices;
};

Game_Message.prototype.faceName = function() {
    return this._faceName;
};

Game_Message.prototype.faceIndex = function() {
    return this._faceIndex;
};

Game_Message.prototype.background = function() {
    return this._background;
};

Game_Message.prototype.positionType = function() {
    return this._positionType;
};

Game_Message.prototype.choiceDefaultType = function() {
    return this._choiceDefaultType;
};

Game_Message.prototype.choiceCancelType = function() {
    return this._choiceCancelType;
};

Game_Message.prototype.choiceBackground = function() {
    return this._choiceBackground;
};

Game_Message.prototype.choicePositionType = function() {
    return this._choicePositionType;
};

Game_Message.prototype.numInputVariableId = function() {
    return this._numInputVariableId;
};

Game_Message.prototype.numInputMaxDigits = function() {
    return this._numInputMaxDigits;
};

Game_Message.prototype.itemChoiceVariableId = function() {
    return this._itemChoiceVariableId;
};

Game_Message.prototype.itemChoiceItypeId = function() {
    return this._itemChoiceItypeId;
};

Game_Message.prototype.scrollMode = function() {
    return this._scrollMode;
};

Game_Message.prototype.scrollSpeed = function() {
    return this._scrollSpeed;
};

Game_Message.prototype.scrollNoFast = function() {
    return this._scrollNoFast;
};

Game_Message.prototype.add = function(text) {
    this._texts.push(text);
};

Game_Message.prototype.setFaceImage = function(faceName, faceIndex) {
    this._faceName = faceName;
    this._faceIndex = faceIndex;
};

Game_Message.prototype.setBackground = function(background) {
    this._background = background;
};

Game_Message.prototype.setPositionType = function(positionType) {
    this._positionType = positionType;
};

Game_Message.prototype.setChoices = function(choices, defaultType, cancelType) {
    this._choices = choices;
    this._choiceDefaultType = defaultType;
    this._choiceCancelType = cancelType;
};

Game_Message.prototype.setChoiceBackground = function(background) {
    this._choiceBackground = background;
};

Game_Message.prototype.setChoicePositionType = function(positionType) {
    this._choicePositionType = positionType;
};

Game_Message.prototype.setNumberInput = function(variableId, maxDigits) {
    this._numInputVariableId = variableId;
    this._numInputMaxDigits = maxDigits;
};

Game_Message.prototype.setItemChoice = function(variableId, itemType) {
    this._itemChoiceVariableId = variableId;
    this._itemChoiceItypeId = itemType;
};

Game_Message.prototype.setScroll = function(speed, noFast) {
    this._scrollMode = true;
    this._scrollSpeed = speed;
    this._scrollNoFast = noFast;
};

Game_Message.prototype.setChoiceCallback = function(callback) {
    this._choiceCallback = callback;
};

Game_Message.prototype.onChoice = function(n) {
    if (this._choiceCallback) {
        this._choiceCallback(n);
        this._choiceCallback = null;
    }
};

Game_Message.prototype.hasText = function() {
    return this._texts.length > 0;
};

Game_Message.prototype.isChoice = function() {
    return this._choices.length > 0;
};

Game_Message.prototype.isNumberInput = function() {
    return this._numInputVariableId > 0;
};

Game_Message.prototype.isItemChoice = function() {
    return this._itemChoiceVariableId > 0;
};

Game_Message.prototype.isBusy = function() {
    return (this.hasText() || this.isChoice() ||
            this.isNumberInput() || this.isItemChoice());
};

Game_Message.prototype.newPage = function() {
    if (this._texts.length > 0) {
        this._texts[this._texts.length - 1] += '\f';
    }
};

Game_Message.prototype.allText = function() {
    return this._texts.join('\n');
};
