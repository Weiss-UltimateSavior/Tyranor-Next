// Compat stub for MPTPShowforActor.js (@target MZ) running on MV engine.
// Original plugin extends Window_StatusBase which doesn't exist in MV.
// This stub provides safe no-op behavior; actual visibility control is disabled on MV.
(function(){
    if (typeof Window_StatusBase === "undefined" && typeof Window_Status !== "undefined") {
        Window_StatusBase = Window_Status;
    }
    if (typeof Window_StatusBase === "undefined" && typeof Window_Selectable !== "undefined") {
        Window_StatusBase = Window_Selectable;
    }
    // If still missing, skip patching entirely
    if (typeof Window_StatusBase === "undefined" || !Window_StatusBase.prototype) return;
    var _orig = Window_StatusBase.prototype.placeBasicGauges;
    if (typeof _orig !== "function") return;
    Window_StatusBase.prototype.placeBasicGauges = function(actor, x, y) {
        try { return _orig.apply(this, arguments); } catch(e) {
            // Fallback: place only hp gauge
            try { this.placeGauge(actor, "hp", x, y); } catch(e2){}
            try { this.placeGauge(actor, "mp", x, y + (this.gaugeLineHeight ? this.gaugeLineHeight() : 36)); } catch(e3){}
        }
    };
})();
