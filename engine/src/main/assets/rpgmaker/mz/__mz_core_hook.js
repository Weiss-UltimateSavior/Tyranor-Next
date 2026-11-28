// 追加到 js/rmmz_core.js 末尾（由 RpgMakerLocalHttpServer 的 scriptAppends 拼接）。
// 求值时刻：main.js 动态插入本脚本时——此刻 Graphics / WebAudio 已由本文件上方定义。
(function () {
    "use strict";

    var cfg = window.__tyranorRpg || {};

    // MZ 的 Graphics._stretchHeight 在移动端返回 clientHeight * 0.9（给浏览器顶栏
    // 留白），WebView 全屏宿主下会留一圈黑边。铺满与 _defaultStretchMode 是两件事：
    // 这里只改高度来源，不动 _defaultStretchMode（否则会与铺满目标冲突）。
    if (window.Graphics && typeof Graphics._stretchHeight === "function") {
        Graphics._stretchHeight = function () {
            return document.documentElement.clientHeight;
        };
    }

    // MZ 硬编码 audioFileExt() = ".ogg"，Html5Audio 整个删除，只剩 WebAudio 一路。
    // Android WebView 的 canPlayType('audio/ogg; codecs="vorbis"') 通常返回 "maybe"，
    // 于是 _shouldUseDecoder() 为 false → 走 decodeAudioData 整段解码：慢机上长 BGM
    // 要 20s+ 才出声，并发解码还会 EncodingError 或永不 settle。
    //
    // 钉成 true 走 js/libs/vorbisdecoder.js 的流式解码。**前置条件**：该库是游戏自带
    // 资产，被裁剪时必须退回原实现，否则 BGM 全哑。
    if (cfg.audioStreaming !== false &&
        typeof VorbisDecoder === "function" &&
        window.WebAudio && typeof WebAudio.prototype._shouldUseDecoder === "function"
    ) {
        WebAudio.prototype._shouldUseDecoder = function () {
            return true;
        };
    }

    // 超出 GPU 纹理上限的图降采样。
    //
    // 真机症状：`WebGL: INVALID_VALUE: texImage2D: width or height out of range`，
    // 该图整张不上传，表现为「部分画面不显示」。实测样本（まもの博物館）542 张图里
    // 156 张宽 > 4096、130 张 > 8192，最大 15400x1000——MZ 的立绘把整段动画压成
    // 一张长条图，宽度很容易越过 GL_MAX_TEXTURE_SIZE。
    //
    // 做法：在 Bitmap 把 Image 交给 PIXI 之前，按 `maxTextureSize / 长边` 等比缩到
    // 上限内，并把缩放比记在 Bitmap 上（`_tyranorTextureScale`）。
    //
    // **关键**：PIXI 的 `Texture.setFrame` 在 frame 超出 baseTexture 尺寸时会**直接抛
    // `Texture Error: frame does not fit inside the base Texture dimensions`**，而 MZ 的
    // `Sprite.setFrame(0, 0, bitmap.width, bitmap.height)` 用的是**原图**尺寸
    // （`Bitmap.width` 读 `_image`，不受本补丁影响）。因此 `Sprite.prototype.setFrame`
    // 必须同步除以缩放比，否则超限图全部变成抛错。反过来，`Bitmap.width/height` 保持
    // 原值，所以布局、动画帧坐标、碰撞判定都不变。
    //
    // 只对**超过上限**的图动手：未超限的图保持原样，不白丢清晰度。
    if (window.Bitmap && typeof Bitmap.prototype._createBaseTexture === "function") {
        var detectMaxTextureSize = function () {
            try {
                if (typeof window.__tyranorMaxTexture !== "number") {
                    var gl = null;
                    var canvas = document.createElement("canvas");
                    gl = canvas.getContext("webgl2") || canvas.getContext("webgl") ||
                        canvas.getContext("experimental-webgl");
                    window.__tyranorMaxTexture = gl
                        ? (gl.getParameter(gl.MAX_TEXTURE_SIZE) || 4096)
                        : 4096;
                    var lose = gl && gl.getExtension("WEBGL_lose_context");
                    if (lose) lose.loseContext();
                }
            } catch (e) {
                window.__tyranorMaxTexture = window.__tyranorMaxTexture || 4096;
            }
            return window.__tyranorMaxTexture;
        };

        var origCreateBaseTexture = Bitmap.prototype._createBaseTexture;
        Bitmap.prototype._createBaseTexture = function (source) {
            var maxSize = detectMaxTextureSize();
            var w = (source && (source.width || source.naturalWidth)) || 0;
            var h = (source && (source.height || source.naturalHeight)) || 0;
            var longest = Math.max(w, h);
            if (!w || !h || longest <= maxSize) {
                this._tyranorTextureScale = 1;
                return origCreateBaseTexture.call(this, source);
            }
            var scale = maxSize / longest;
            var targetW = Math.max(1, Math.floor(w * scale));
            var targetH = Math.max(1, Math.floor(h * scale));
            var canvas = document.createElement("canvas");
            canvas.width = targetW;
            canvas.height = targetH;
            var ctx = canvas.getContext("2d");
            if (!ctx) {
                // 拿不到 2D 上下文：保持原样（交给 GL 报错，但至少不引入新行为）
                this._tyranorTextureScale = 1;
                return origCreateBaseTexture.call(this, source);
            }
            ctx.imageSmoothingEnabled = true;
            if ("imageSmoothingQuality" in ctx) ctx.imageSmoothingQuality = "high";
            try {
                ctx.drawImage(source, 0, 0, targetW, targetH);
            } catch (e) {
                this._tyranorTextureScale = 1;
                return origCreateBaseTexture.call(this, source);
            }
            // 只影响纹理；this._image 不动，Bitmap.width/height 仍是原图尺寸。
            this._tyranorTextureScale = scale;
            return origCreateBaseTexture.call(this, canvas);
        };

        // 帧坐标同步折算：MZ 用 bitmap.width/height（原图）做 setFrame，
        // 而 baseTexture 已是缩小后的尺寸，不折算会被 PIXI 判为越界并抛错。
        //
        // **必须向下取整**：纹理尺寸是 `Math.floor(w * scale)`，帧宽高若用 round 会
        // 在 15400x1000 → 4096x265 这类比例上算出 266 > 265，PIXI 立刻抛
        // "frame does not fit inside the base Texture dimensions"。
        //
        // Sprite 与 TilingSprite 是**两套独立的 setFrame 实现**，都要折算；图块集
        // 走 TilingSprite，超限时同样会撞上限。
        var patchSetFrame = function (ctor) {
            if (!ctor || !ctor.prototype || typeof ctor.prototype.setFrame !== "function") return;
            var orig = ctor.prototype.setFrame;
            if (orig.__tyranorFrameScaled) return;
            var patched = function (x, y, width, height) {
                var scale = (this._bitmap && this._bitmap._tyranorTextureScale) || 1;
                if (scale !== 1) {
                    return orig.call(
                        this,
                        Math.floor(x * scale),
                        Math.floor(y * scale),
                        Math.floor(width * scale),
                        Math.floor(height * scale),
                    );
                }
                return orig.call(this, x, y, width, height);
            };
            patched.__tyranorFrameScaled = true;
            ctor.prototype.setFrame = patched;
        };
        patchSetFrame(window.Sprite);
        patchSetFrame(window.TilingSprite);
    }
})();