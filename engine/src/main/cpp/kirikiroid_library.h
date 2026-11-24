#pragma once

#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

namespace kirikiroid {

struct Image {
    uintptr_t base = 0;
    const Elf64_Phdr* phdr = nullptr;
    size_t count = 0;
    std::string path;
};

inline int findImage(dl_phdr_info* info, size_t, void* value) {
    auto* image=static_cast<Image*>(value);
    // The Android linker stores a resolved FD path, which can differ from
    // Java's /data/user/0 or /data/data spelling for the same loaded file.
    // The verified symbol's image base identifies it without a path alias.
    if (static_cast<uintptr_t>(info->dlpi_addr) != image->base) return 0;
    image->phdr = info->dlpi_phdr;
    image->count = info->dlpi_phnum;
    return 1;
}

enum class Kernel { Unknown, V139, V134 };

inline Kernel identify(const Image& image) {
    const std::string filename = image.path.substr(image.path.find_last_of('/') + 1);
    const char* expected;
    Kernel kernel;
    if (filename == "libgame.so") {
        expected = "de22234bffa0545d276b705487ca0c3d35101386";
        kernel = Kernel::V139;
    } else if (filename == "libgame134.so") {
        expected = "97c91143725ba2cf99471c0aed309cf66703fc79";
        kernel = Kernel::V134;
    } else {
        return Kernel::Unknown;
    }
    for (size_t i = 0; i < image.count; ++i) {
        const auto& noteSegment = image.phdr[i];
        if (noteSegment.p_type != PT_NOTE) continue;
        bool readable = false;
        for (size_t j = 0; j < image.count; ++j) {
            const auto& load = image.phdr[j];
            if (load.p_type != PT_LOAD || !(load.p_flags & PF_R) || noteSegment.p_vaddr < load.p_vaddr) continue;
            Elf64_Addr offset = noteSegment.p_vaddr - load.p_vaddr;
            if (offset <= load.p_memsz && noteSegment.p_memsz <= load.p_memsz - offset) readable = true;
        }
        if (!readable || noteSegment.p_vaddr > std::numeric_limits<uintptr_t>::max() - image.base) continue;
        uintptr_t start = image.base + noteSegment.p_vaddr;
        if (noteSegment.p_memsz > std::numeric_limits<uintptr_t>::max() - start) continue;
        const auto* at = reinterpret_cast<const unsigned char*>(start);
        size_t length = noteSegment.p_memsz;
        while (length >= sizeof(Elf64_Nhdr)) {
            Elf64_Nhdr note{};
            std::memcpy(&note, at, sizeof(note));
            size_t name = (static_cast<size_t>(note.n_namesz) + 3u) & ~size_t{3};
            size_t desc = (static_cast<size_t>(note.n_descsz) + 3u) & ~size_t{3};
            if (name > length - sizeof(note) || desc > length - sizeof(note) - name) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && note.n_descsz == 20
                    && std::memcmp(at + sizeof(note), "GNU", 4) == 0) {
                char hex[41]{};
                const unsigned char* id = at + sizeof(note) + name;
                for (size_t n = 0; n < 20; ++n) {
                    hex[2 * n] = "0123456789abcdef"[id[n] >> 4];
                    hex[2 * n + 1] = "0123456789abcdef"[id[n] & 15];
                }
                return std::strcmp(hex, expected) == 0 ? kernel : Kernel::Unknown;
            }
            size_t total = sizeof(note) + name + desc;
            at += total;
            length -= total;
        }
    }
    return Kernel::Unknown;
}

struct Change {
    void** slot;
    void* before;
    void* after;
    int protection;
};

inline bool collect(const Image& image, std::vector<Change>& changes,
                    void** slot, void* before, void* after) {
    if (!before || !after) return false;
    const uintptr_t slotAddress = reinterpret_cast<uintptr_t>(slot);
    for (size_t i = 0; i < image.count; ++i) {
        const auto& segment = image.phdr[i];
        const uintptr_t start = image.base + segment.p_vaddr;
        if (segment.p_type != PT_LOAD || !(segment.p_flags & PF_R) || slotAddress < start
                || segment.p_memsz < sizeof(void*) || slotAddress - start > segment.p_memsz - sizeof(void*)) continue;
        if (*slot != before) return false;
        int protection = PROT_READ | (segment.p_flags & PF_X ? PROT_EXEC : 0)
                | (segment.p_flags & PF_W ? PROT_WRITE : 0);
        // RELRO covers writable .data pages after relocation.
        for (size_t j = 0; j < image.count; ++j) {
            const auto& relro = image.phdr[j];
            const uintptr_t relroStart = image.base + relro.p_vaddr;
            if (relro.p_type == PT_GNU_RELRO && slotAddress >= relroStart
                    && slotAddress - relroStart < relro.p_memsz) protection &= ~PROT_WRITE;
        }
        changes.push_back({slot, before, after, protection});
        return true;
    }
    return false;
}

inline bool change(const Change& value, bool restore) {
    long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    uintptr_t page = reinterpret_cast<uintptr_t>(value.slot) & ~(static_cast<uintptr_t>(pageSize) - 1);
    if (mprotect(reinterpret_cast<void*>(page), pageSize, value.protection | PROT_WRITE) != 0) return false;
    __atomic_store_n(value.slot, restore ? value.before : value.after, __ATOMIC_RELEASE);
    return mprotect(reinterpret_cast<void*>(page), pageSize, value.protection) == 0;
}

}  // namespace kirikiroid
