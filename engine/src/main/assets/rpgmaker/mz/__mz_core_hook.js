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
})();