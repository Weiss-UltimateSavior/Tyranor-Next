#ifndef ETHORNELL_DIAGNOSTIC_H
#define ETHORNELL_DIAGNOSTIC_H
#include <cstdint>
#include <string>

namespace ethornell_bridge {
// Input is the ABI's already-redacted last_error, never a raw engine/game error.
// Printable ASCII only: safe for ThrowNew's Modified UTF-8 and single-line diagnostics.
inline std::string boundedDiagnostic(int32_t (*lastError)(char*, uint32_t)) {
    if (!lastError) return {};
    constexpr uint32_t capacity = 2048;
    int32_t required = lastError(nullptr, 0);
    if (required <= 1 || required > static_cast<int32_t>(capacity)) return {};
    char detail[capacity]{};
    int32_t copied = lastError(detail, static_cast<uint32_t>(required));
    if (copied != required || detail[required - 1] != '\0') return {};
    for (int32_t i = 0; i < required - 1; ++i) {
        unsigned char c = static_cast<unsigned char>(detail[i]);
        if (c < 0x20 || c > 0x7e) return {};
    }
    return std::string(detail, static_cast<size_t>(required - 1));
}
}
#endif
