#pragma once

#include <cstdint>
#include <cstring>
#include <vector>

namespace hyperpods {
struct MemoryRange {
    uintptr_t begin;
    uintptr_t end;
    bool executable;
};

inline uintptr_t findString(const std::vector<MemoryRange>& ranges, const char* text) {
    const size_t size = std::strlen(text) + 1;
    for (const auto& range : ranges) {
        if (range.end < range.begin || range.end - range.begin < size) continue;
        for (uintptr_t pos = range.begin; pos <= range.end - size; ++pos) {
            if (std::memcmp(reinterpret_cast<const void*>(pos), text, size) == 0) return pos;
        }
    }
    return 0;
}

inline uint32_t instructionAt(uintptr_t address) {
    uint32_t instruction;
    std::memcpy(&instruction, reinterpret_cast<const void*>(address), sizeof(instruction));
    return instruction;
}

// Resolve ADRP + ADD, including the signed page displacement. Matching only
// ADD's low 12 address bits can mistake an unrelated function for the target.
inline uintptr_t stringReference(uintptr_t addAddress) {
    const uint32_t adrp = instructionAt(addAddress - 4);
    const uint32_t add = instructionAt(addAddress);
    if ((adrp & 0x9f000000u) != 0x90000000u || (add & 0xffc00000u) != 0x91000000u) return 0;
    if ((adrp & 31u) != ((add >> 5) & 31u)) return 0;
    int64_t pages = ((adrp >> 29) & 3u) | (((adrp >> 5) & 0x7ffffu) << 2);
    if (pages & (1 << 20)) pages -= (1 << 21);
    const uintptr_t page = (addAddress - 4) & ~uintptr_t(0xfff);
    return page + pages * 4096 + ((add >> 10) & 0xfffu);
}

inline uintptr_t findFunction(const std::vector<MemoryRange>& ranges,
                              uintptr_t name, uintptr_t assertion, bool isMtk) {
    if (name == 0 || assertion == 0) return 0;
    const uintptr_t functionOffset = isMtk ? 0x84 : 0x44;
    uintptr_t result = 0;
    for (const auto& range : ranges) {
        if (!range.executable || range.end < range.begin || range.end - range.begin < functionOffset + 20) continue;
        for (uintptr_t pos = range.begin + functionOffset; pos <= range.end - 20; pos += 4) {
            if ((instructionAt(pos) & 0xff0003ffu) != 0x91000108u ||
                (instructionAt(pos + 16) & 0xff0003ffu) != 0x91000063u) continue;
            if (stringReference(pos) != name || stringReference(pos + 16) != assertion) continue;
            const uintptr_t candidate = pos - functionOffset;
            const uint32_t entry = instructionAt(candidate);
            // String references alone cannot validate a fixed-offset function
            // boundary after a ROM update. Require a known ARM64 entry sequence.
            const bool signedEntry = entry == 0xd503233fu || entry == 0xd503237fu;
            const bool btiEntry = entry == 0xd503245fu || entry == 0xd50324dfu;
            const bool stackAllocation = (entry & 0xffc003ffu) == 0xd10003ffu;
            const bool savedRegisters = (entry & 0xffc003e0u) == 0xa98003e0u;
            if (!signedEntry && !btiEntry && !stackAllocation && !savedRegisters) continue;
            if (result != 0 && result != candidate) return 0;
            result = candidate;
        }
    }
    return result;
}
} // namespace hyperpods
