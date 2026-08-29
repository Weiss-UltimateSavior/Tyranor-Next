//-----------------------------------------------------------------------------
/**
 * The color matrix filter for WebGL.
 *
 * @class ToneFilter
 * @extends PIXI.Filter
 * @constructor
 */
function ToneFilter() {
    PIXI.filters.ColorMatrixFilter.call(this);
}

ToneFilter.prototype = Object.create(PIXI.filters.ColorMatrixFilter.prototype);
ToneFilter.prototype.constructor = ToneFilter;

/**
 * Changes the hue.
 *
 * @method adjustHue
 * @param {Number} value The hue value in the range (-360, 360)
 */
ToneFilter.prototype.adjustHue = function(value) {
    this.hue(value, true);
};

/**
 * Changes the saturation.
 *
 * @method adjustSaturation
 * @param {Number} value The saturation value in the range (-255, 255)
 */
ToneFilter.prototype.adjustSaturation = function(value) {
    value = (value || 0).clamp(-255, 255) / 255;
    this.saturate(value, true);
};

/**
 * Changes the tone.
 *
 * @method adjustTone
 * @param {Number} r The red strength in the range (-255, 255)
 * @param {Number} g The green strength in the range (-255, 255)
 * @param {Number} b The blue strength in the range (-255, 255)
 */
ToneFilter.prototype.adjustTone = function(r, g, b) {
    r = (r || 0).clamp(-255, 255) / 255;
    g = (g || 0).clamp(-255, 255) / 255;
    b = (b || 0).clamp(-255, 255) / 255;

    if (r !== 0 || g !== 0 || b !== 0) {
        var matrix = [
            1, 0, 0, r, 0,
            0, 1, 0, g, 0,
            0, 0, 1, b, 0,
            0, 0, 0, 1, 0
        ];

        this._loadMatrix(matrix, true);
    }
};
