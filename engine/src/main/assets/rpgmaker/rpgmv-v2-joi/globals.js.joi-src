var process = require('process')
var nw = require('nw')
nw.gui = require('nw.gui')

var greenworks = require('greenworks')

var _setItem = Storage.prototype.setItem;
Storage.prototype.setItem = function(key, value) {
    NWJSApi.saveFile(key,value);
};

var _getItem = Storage.prototype.getItem;
Storage.prototype.getItem = function(key){
    var data = "";
    data = NWJSApi.getFile(key);
    if (data && undefined !== data ){
        return data;
    } else {
        return null;
    }
};

var _removeItem = Storage.prototype.removeItem;
Storage.prototype.removeItem = function(key){
	NWJSApi.removeFile(key);
};

if(typeof window.process === 'undefined'){
    window.process = process;
}

window.Clipboard = nw.gui.Clipboard
window.clipboard = nw.gui.Clipboard

var Buffer = require('buffer').Buffer
if(typeof window.Buffer === 'undefined'){
    window.Buffer = Buffer;
}

if(typeof global === 'undefined'){
    var global = window
}

window.App = nw.gui.App;
window.gc = function(){};
window.focus = function(){};
window.on = function(name, func){
    window.addEventListener(name, func);
};

window.__dirname = NWJSApi.execDir();

window.speechSynthesis = {
    getVoices: function(...argv){ return [];},
    cancel: function(...argv){},
    pause: function(...argv){},
    resume: function(...argv){},
    speak: function(...argv){},
}

window.joiSaveAs = function(blob, type, path){
    var reader = new FileReader();
    reader.readAsDataURL(blob);
    reader.onloadend = function() {
        var base64data = reader.result;
        NWJSApi.saveBlob(base64data, path);
    }
}

var screen = {};
screen.orientation = {};
screen.orientation.lock = function(orientation){
    NWJSApi.lockOrientation(orientation);
}

screen.orientation.unlock = function(){
    NWJSApi.unlockOrientation();
}

window.screen = screen;


console.log("Injected globals.js")
