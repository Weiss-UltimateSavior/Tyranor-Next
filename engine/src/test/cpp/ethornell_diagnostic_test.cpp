#include "../../main/cpp/ethornell_diagnostic.h"
#include <cassert>
#include <cstring>
#include <string>

namespace {
std::string text;
int copies;
int32_t readError(char* out, uint32_t capacity) {
    auto required = static_cast<int32_t>(text.size() + 1);
    if (out) {
        ++copies;
        if (capacity >= static_cast<uint32_t>(required)) std::memcpy(out, text.c_str(), required);
    }
    return required;
}
int32_t changedSize(char* out, uint32_t) { if (out) { out[0] = 'x'; return 2049; } return 2; }
int32_t noTerminator(char* out, uint32_t) { if (out) { out[0] = 'x'; out[1] = 'y'; } return 2; }
}
int main() {
    using ethornell_bridge::boundedDiagnostic;
    text = "filesystem policy stage=104 errno=13";
    assert(boundedDiagnostic(readError) == text);
    for (const std::string& invalid : {std::string("x\ny"), std::string("x\ry"),
            std::string("x\ty"), std::string("x\0y", 3), std::string("x\x7f"), std::string("\xc3\xa9")}) {
        text = invalid; assert(boundedDiagnostic(readError).empty());
    }
    text.assign(2047, 'a'); assert(boundedDiagnostic(readError) == text);
    copies = 0; text.assign(2048, 'a');
    assert(boundedDiagnostic(readError).empty()); assert(copies == 0);
    text.clear(); assert(boundedDiagnostic(readError).empty());
    assert(boundedDiagnostic(changedSize).empty());
    assert(boundedDiagnostic(noTerminator).empty());
    assert(boundedDiagnostic(nullptr).empty());
}
