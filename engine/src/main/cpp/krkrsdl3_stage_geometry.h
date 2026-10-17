#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>

// TVPRenderOnce in the pinned ELF uses float bounds for drawing and stores
// truncated bounds separately for input. Never feed these bounds back to input.
struct Krkrsdl3StageGeometry {
    int width = 0, height = 0, screenWidth = 0, screenHeight = 0;
    int canvasWidth = 0, canvasHeight = 0;
    int padLeft = 0, padRight = 0, padBottom = 0, padTop = 0;
    float scale = 0, left = 0, bottom = 0;
    float canvasLeft = 0, canvasBottom = 0;
    bool set(int w, int h, int sw, int sh) {
        constexpr uint64_t maxPixels = (256ull * 1024 * 1024 - 1) / 8;
        if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0
                || static_cast<uint64_t>(w) * static_cast<uint64_t>(h) > maxPixels) return false;
        width = w; height = h; screenWidth = sw; screenHeight = sh;
        scale = std::min(static_cast<float>(sw) / w, static_cast<float>(sh) / h);
        float extentX = scale * w, extentY = scale * h;
        left = (sw - extentX) * 0.5f;
        bottom = (sh - extentY) * 0.5f;
        if (!std::isfinite(scale) || scale <= 0) return false;
        auto padding = [&](float margin, int& result) {
            double pixels = std::ceil(std::max(0.0, static_cast<double>(margin) / scale));
            if (!std::isfinite(pixels) || pixels > std::numeric_limits<int>::max()) return false;
            result = static_cast<int>(pixels); return true;
        };
        if (!padding(left, padLeft) || !padding(sw - left - extentX, padRight)
                || !padding(bottom, padBottom) || !padding(sh - bottom - extentY, padTop)) return false;
        uint64_t cw = static_cast<uint64_t>(w) + padLeft + padRight;
        uint64_t ch = static_cast<uint64_t>(h) + padBottom + padTop;
        if (cw > std::numeric_limits<int>::max() || ch > std::numeric_limits<int>::max()
                || cw * ch > maxPixels) return false;
        canvasWidth = static_cast<int>(cw); canvasHeight = static_cast<int>(ch);
        canvasLeft = left - padLeft * scale;
        canvasBottom = bottom - padBottom * scale;
        return true;
    }
    // Window-coordinate inverse into an integer-padded display canvas. The
    // canvas covers the complete physical window, including letterbox content.
    float x(float v) const { return (v * screenWidth - canvasLeft) / (scale * canvasWidth); }
    float y(float v) const { return (v * screenHeight - canvasBottom) / (scale * canvasHeight); }
    float w(float v) const { return v * screenWidth / (scale * canvasWidth); }
    float h(float v) const { return v * screenHeight / (scale * canvasHeight); }
    float mainX() const { return static_cast<float>(padLeft) / canvasWidth; }
    float mainY() const { return static_cast<float>(padBottom) / canvasHeight; }
    float mainW() const { return static_cast<float>(width) / canvasWidth; }
    float mainH() const { return static_cast<float>(height) / canvasHeight; }
    float inMainX(float v) const { return (v * screenWidth - left) / (scale * width); }
    float inMainY(float v) const { return (v * screenHeight - bottom) / (scale * height); }
    float inMainW(float v) const { return v * screenWidth / (scale * width); }
    float inMainH(float v) const { return v * screenHeight / (scale * height); }
    bool containsDraw(float px, float py, float pw, float ph) const {
        float minX = left / screenWidth, minY = bottom / screenHeight;
        float maxX = minX + scale * width / screenWidth, maxY = minY + scale * height / screenHeight;
        return std::isfinite(px) && std::isfinite(py) && std::isfinite(pw) && std::isfinite(ph)
                && pw >= 0 && ph >= 0 && px >= minX && py >= minY
                && px + pw <= maxX && py + ph <= maxY;
    }
    bool mainDraw(float px, float py, float pw, float ph) const {
        // Tolerance covers float arithmetic only, never a pixel of resampling.
        constexpr float epsilon = 0.00001f;
        return std::abs(inMainX(px)) < epsilon && std::abs(inMainY(py)) < epsilon
                && std::abs(inMainW(pw) - 1) < epsilon && std::abs(inMainH(ph) - 1) < epsilon;
    }
};
