// Standalone host test; no Android/GLES dependencies or engine binary required.
// Compile as C++17 and run separately from the Android application build.
#include "../../main/cpp/krkrsdl3_stage_geometry.h"
#include <cassert>
#include <climits>

static void close(float a, float b) { assert(std::abs(a - b) < 0.00001f); }
static void closePixel(float a, float b) { assert(std::abs(a - b) < 0.001f); }
static void check(int w, int h, int sw, int sh) {
    Krkrsdl3StageGeometry g;
    assert(g.set(w, h, sw, sh));
    float x = g.left / sw, y = g.bottom / sh;
    float width = g.scale * w / sw, height = g.scale * h / sh;
    assert(g.mainDraw(x, y, width, height));
    close(g.inMainX(x), 0); close(g.inMainY(y), 0);
    close(g.inMainW(width), 1); close(g.inMainH(height), 1);
    close(g.x(x), g.mainX()); close(g.y(y), g.mainY());
    close(g.w(width), g.mainW()); close(g.h(height), g.mainH());
    // Main source pixels occupy integer canvas pixels, including their centers.
    closePixel(g.mainX() * g.canvasWidth, static_cast<float>(g.padLeft));
    closePixel(g.mainY() * g.canvasHeight, static_cast<float>(g.padBottom));
    assert(g.canvasWidth == w + g.padLeft + g.padRight);
    assert(g.canvasHeight == h + g.padBottom + g.padTop);
    assert(g.canvasLeft <= 0.001f && g.canvasBottom <= 0.001f);
    assert(g.canvasLeft + g.scale * g.canvasWidth >= sw - 0.001f);
    assert(g.canvasBottom + g.scale * g.canvasHeight >= sh - 0.001f);
    // The full physical window fits in the canvas, including black-bar regions.
    assert(g.x(0) >= -0.00001f && g.y(0) >= -0.00001f);
    assert(g.x(1) <= 1.00001f && g.y(1) <= 1.00001f);
    // Overlay rectangles retain physical placement after inverse composition
    // and final display fitting; they need not match the main sprite geometry.
    constexpr float overlayX = 0.23f, overlayY = 0.41f, overlayW = 0.32f, overlayH = 0.17f;
    close((g.x(overlayX) * g.scale * g.canvasWidth + g.canvasLeft) / sw, overlayX);
    close((g.y(overlayY) * g.scale * g.canvasHeight + g.canvasBottom) / sh, overlayY);
    close(g.w(overlayW) * g.scale * g.canvasWidth / sw, overlayW);
    close(g.h(overlayH) * g.scale * g.canvasHeight / sh, overlayH);
    assert(!g.mainDraw(overlayX, overlayY, overlayW, overlayH));
}

int main() {
    check(1280, 720, 1280, 720); // Native 1:1.
    check(1920, 1080, 3440, 1440); // Landscape pillarbox.
    check(1280, 720, 1080, 2400); // Portrait letterbox.
    check(641, 479, 2341, 1081); // Noninteger scale and centered bounds.
    check(1920, 1080, 800, 600); // Display reduction retains raw stage size.
    Krkrsdl3StageGeometry fractional;
    assert(fractional.set(1280, 720, 1080, 2400));
    close(fractional.bottom, 896.25f); // Input's truncated 896 is not a draw bound.
    assert(!fractional.mainDraw(0, 896.0f / 2400, 1, 607.5f / 2400));
    assert(fractional.canvasWidth == 1280 && fractional.canvasHeight == 2846);
    assert(fractional.padBottom == 1063 && fractional.padTop == 1063);
    assert(fractional.canvasBottom < 0); // Padding covers the fractional physical edge.
    assert(fractional.containsDraw(0, fractional.bottom / 2400, 1, 607.5f / 2400));
    // A square overlay joined or made visible after BeginFrame must switch the
    // current frame to complete-canvas processing, rather than being cropped.
    bool allContained = true;
    allContained = allContained && fractional.containsDraw(0, fractional.bottom / 2400, 1, 607.5f / 2400);
    assert(allContained);
    const float squareBottom = (2400.0f - 1080.0f) * 0.5f / 2400.0f;
    allContained = allContained && fractional.containsDraw(0, squareBottom, 1, 1080.0f / 2400.0f);
    assert(!allContained);
    assert(fractional.y(squareBottom) >= 0);
    assert(fractional.y(squareBottom) + fractional.h(1080.0f / 2400.0f) <= 1);
    // On the next frame a now-hidden overlay contributes no draw: main input
    // dimensions are recovered without a stale visibility/Join registry.
    allContained = fractional.containsDraw(0, fractional.bottom / 2400, 1, 607.5f / 2400);
    assert(allContained);
    Krkrsdl3StageGeometry wide;
    assert(wide.set(1920, 1080, 3440, 1440));
    assert(wide.containsDraw(1000.0f / 3440, 0, 1440.0f / 3440, 1)); // Centered square is contained.
    assert(!wide.containsDraw(0, 0, 1, 1)); // Full-window overlay reaches into pillarbox bars.
    assert(wide.x(0) >= 0 && wide.x(1) <= 1);
    Krkrsdl3StageGeometry square;
    assert(square.set(1000, 1000, 3440, 1440));
    assert(!square.containsDraw(440.0f / 3440, 0, 2560.0f / 3440, 1));
    assert(square.containsDraw(square.left / 3440, square.bottom / 1440,
            square.scale * 1000 / 3440, square.scale * 1000 / 1440));
    assert(!square.containsDraw(0, 0, 1, 1));
    assert(!square.containsDraw(0, 0, -1, 1));
    // Fractional native alignment and cropping remain exact integer rectangles.
    Krkrsdl3StageGeometry native;
    assert(native.set(1280, 720, 1280, 720));
    assert(native.canvasWidth == 1280 && native.canvasHeight == 720);
    assert(native.padLeft == 0 && native.padRight == 0 && native.padBottom == 0 && native.padTop == 0);
    Krkrsdl3StageGeometry invalid;
    assert(!invalid.set(0, 720, 1920, 1080));
    assert(!invalid.set(1280, -1, 1920, 1080));
    assert(!invalid.set(1280, 720, 0, 1080));
    assert(!invalid.set(INT_MAX, INT_MAX, 1920, 1080));
    assert(!invalid.set(8192, 8192, 1920, 1080)); // Stage exceeds its own memory budget.
    assert(!invalid.set(1, 1, INT_MAX, 1)); // Main fits, padded display canvas does not.
    return 0;
}
