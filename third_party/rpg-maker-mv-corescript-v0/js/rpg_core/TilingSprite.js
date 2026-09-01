//-----------------------------------------------------------------------------
/**
 * The sprite object for a tiling image.
 *
 * @class TilingSprite
 * @constructor
 * @param {Bitmap} bitmap The image for the tiling sprite
 */
function TilingSprite() {
    this.initialize.apply(this, arguments);
}

TilingSprite.prototype = Object.create(PIXI.extras.PictureTilingSprite.prototype);
TilingSprite.prototype.constructor = TilingSprite;

TilingSprite.prototype.initialize = function(bitmap) {
    var texture = new PIXI.Texture(new PIXI.BaseTexture());

    PIXI.extras.PictureTilingSprite.call(this, texture);

    this._bitmap = null;
    this._width = 0;
    this._height = 0;
    this._frame = new Rectangle();
    this.spriteId = Sprite._counter++;
    /**
     * The origin point of the tiling sprite for scrolling.
     *
     * @property origin
     * @type Point
     */
    this.origin = new Point();

    this.bitmap = bitmap;
};

TilingSprite.prototype._renderCanvas_PIXI = PIXI.extras.PictureTilingSprite.prototype._renderCanvas;
TilingSprite.prototype._renderWebGL_PIXI = PIXI.extras.PictureTilingSprite.prototype._renderWebGL;

/**
 * @method _renderCanvas
 * @param {Object} renderer
 * @private
 */
TilingSprite.prototype._renderCanvas = function(renderer) {
    if (this._bitmap) {
        this._bitmap.touch();
    }
    if (this.texture.frame.width > 0 && this.texture.frame.height > 0) {
        this._renderCanvas_PIXI(renderer);
    }
};

/**
 * The image for the tiling sprite.
 *
 * @property bitmap
 * @type Bitmap
 */
Object.defineProperty(TilingSprite.prototype, 'bitmap', {
    get: function() {
        return this._bitmap;
    },
    set: function(value) {
        if (this._bitmap !== value) {
            this._bitmap = value;
            if (this._bitmap) {
                this._bitmap.addLoadListener(this._onBitmapLoad.bind(this));
            } else {
                this.texture.frame = Rectangle.emptyRectangle;
            }
        }
    },
    configurable: true
});

/**
 * The opacity of the tiling sprite (0 to 255).
 *
 * @property opacity
 * @type Number
 */
Object.defineProperty(TilingSprite.prototype, 'opacity', {
    get: function() {
        return this.alpha * 255;
    },
    set: function(value) {
        this.alpha = value.clamp(0, 255) / 255;
    },
    configurable: true
});

/**
 * Updates the tiling sprite for each frame.
 *
 * @method update
 */
TilingSprite.prototype.update = function() {
    this.children.forEach(function(child) {
        if (child.update) {
            child.update();
        }
    });
};

/**
 * Sets the x, y, width, and height all at once.
 *
 * @method move
 * @param {Number} x The x coordinate of the tiling sprite
 * @param {Number} y The y coordinate of the tiling sprite
 * @param {Number} width The width of the tiling sprite
 * @param {Number} height The height of the tiling sprite
 */
TilingSprite.prototype.move = function(x, y, width, height) {
    this.x = x || 0;
    this.y = y || 0;
    this._width = width || 0;
    this._height = height || 0;
};

/**
 * Specifies the region of the image that the tiling sprite will use.
 *
 * @method setFrame
 * @param {Number} x The x coordinate of the frame
 * @param {Number} y The y coordinate of the frame
 * @param {Number} width The width of the frame
 * @param {Number} height The height of the frame
 */
TilingSprite.prototype.setFrame = function(x, y, width, height) {
    this._frame.x = x;
    this._frame.y = y;
    this._frame.width = width;
    this._frame.height = height;
    this._refresh();
};

/**
 * @method updateTransform
 * @private
 */
TilingSprite.prototype.updateTransform = function() {
    this.tilePosition.x = Math.round(-this.origin.x);
    this.tilePosition.y = Math.round(-this.origin.y);
    this.updateTransformTS();
};

TilingSprite.prototype.updateTransformTS = PIXI.extras.TilingSprite.prototype.updateTransform;

/**
 * @method _onBitmapLoad
 * @private
 */
TilingSprite.prototype._onBitmapLoad = function() {
    this.texture.baseTexture = this._bitmap.baseTexture;
    this._refresh();
};

/**
 * @method _refresh
 * @private
 */
TilingSprite.prototype._refresh = function() {
    var frame = this._frame.clone();
    if (frame.width === 0 && frame.height === 0 && this._bitmap) {
        frame.width = this._bitmap.width;
        frame.height = this._bitmap.height;
    }
    this.texture.frame = frame;
    this.texture._updateID++;
    this.tilingTexture = null;
};


TilingSprite.prototype._speedUpCustomBlendModes = Sprite.prototype._speedUpCustomBlendModes;

/**
 * @method _renderWebGL
 * @param {Object} renderer
 * @private
 */
TilingSprite.prototype._renderWebGL = function(renderer) {
    if (this._bitmap) {
        this._bitmap.touch();
        this._bitmap.checkDirty();
    }

    this._speedUpCustomBlendModes(renderer);

    this._renderWebGL_PIXI(renderer);
};

// The important members from Pixi.js

/**
 * The visibility of the tiling sprite.
 *
 * @property visible
 * @type Boolean
 */

/**
 * The x coordinate of the tiling sprite.
 *
 * @property x
 * @type Number
 */

/**
 * The y coordinate of the tiling sprite.
 *
 * @property y
 * @type Number
 */
