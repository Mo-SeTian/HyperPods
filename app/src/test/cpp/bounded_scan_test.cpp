#include "../../main/cpp/bounded_scan.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <sys/mman.h>
#include <unistd.h>

using hyperpods::MemoryRange;

// ELF64 layout for the optional Android-library test, also usable on macOS.
struct Elf64_Ehdr {
    unsigned char e_ident[16];
    uint16_t e_type, e_machine;
    uint32_t e_version;
    uint64_t e_entry, e_phoff, e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx;
};
struct Elf64_Phdr {
    uint32_t p_type, p_flags;
    uint64_t p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align;
};
static_assert(sizeof(Elf64_Ehdr) == 64 && sizeof(Elf64_Phdr) == 56);
constexpr uint32_t PT_LOAD = 1, PF_R = 4, PF_X = 1;
constexpr uint16_t EM_AARCH64 = 183;

static void writeReference(uintptr_t pos, uintptr_t target, uint32_t reg) {
    const int64_t pages = (int64_t(target & ~uintptr_t(0xfff)) -
        int64_t((pos - 4) & ~uintptr_t(0xfff))) / 4096;
    const uint32_t displacement = uint32_t(pages) & 0x1fffff;
    const uint32_t adrp = 0x90000000u | ((displacement & 3u) << 29) |
        ((displacement >> 2) << 5) | reg;
    const uint32_t add = 0x91000000u | ((target & 0xfffu) << 10) | (reg << 5) | reg;
    std::memcpy(reinterpret_cast<void*>(pos - 4), &adrp, 4);
    std::memcpy(reinterpret_cast<void*>(pos), &add, 4);
}

int main(int argc, char** argv) {
    const size_t page = sysconf(_SC_PAGESIZE);
    auto* allocation = static_cast<char*>(mmap(nullptr, page * 3, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(allocation != MAP_FAILED);
    char* data = allocation + page;
    assert(mprotect(data, page, PROT_READ | PROT_WRITE) == 0);
    const uintptr_t begin = reinterpret_cast<uintptr_t>(data);
    const std::vector<MemoryRange> ranges{{begin, begin + page, true}};
    std::memcpy(data + page - 5, "edge", 5);
    assert(hyperpods::findString(ranges, "edge") == begin + page - 5);
    assert(hyperpods::findString(ranges, "absent") == 0);
    assert(hyperpods::findString({}, "absent") == 0);
    std::memcpy(data + 512, "name", 5);
    std::memcpy(data + 768, "assertion", 10);
    const uintptr_t log = begin + 128;
    writeReference(log, begin + 512, 8);
    writeReference(log + 16, begin + 768, 3);
    // The assertion ADD stores into x3 using the expected x3 base.
    assert(hyperpods::findFunction(ranges, begin + 512, begin + 768, false) == log - 0x44);
    assert(hyperpods::findFunction(ranges, 0, begin + 768, false) == 0);
    assert(hyperpods::findFunction({{begin, begin + page, false}}, begin + 512, begin + 768, false) == 0);
    // The same low 12 bits in another address must not produce a false match.
    assert(hyperpods::findFunction(ranges, begin + 512 + 4096, begin + 768, false) == 0);
    writeReference(begin + 256, begin + 512, 8);
    writeReference(begin + 272, begin + 768, 3);
    assert(hyperpods::findFunction(ranges, begin + 512, begin + 768, false) == 0);
    munmap(allocation, page * 3);
    std::cout << "Native boundary, address-reference and ambiguous-target tests passed\n";

    if (argc == 2) {
        std::ifstream input(argv[1], std::ios::binary);
        assert(input);
        const std::vector<char> file((std::istreambuf_iterator<char>(input)), {});
        assert(file.size() >= sizeof(Elf64_Ehdr));
        Elf64_Ehdr elf;
        std::memcpy(&elf, file.data(), sizeof(elf));
        assert(std::memcmp(elf.e_ident, "\177ELF", 4) == 0 && elf.e_machine == EM_AARCH64);
        assert(elf.e_phentsize == sizeof(Elf64_Phdr));
        size_t imageSize = 0;
        std::vector<Elf64_Phdr> segments;
        for (int i = 0; i < elf.e_phnum; ++i) {
            assert(elf.e_phoff + (i + 1) * sizeof(Elf64_Phdr) <= file.size());
            Elf64_Phdr ph;
            std::memcpy(&ph, file.data() + elf.e_phoff + i * elf.e_phentsize, sizeof(ph));
            if (ph.p_type == PT_LOAD) {
                segments.push_back(ph);
                imageSize = std::max(imageSize, size_t(ph.p_vaddr + ph.p_memsz));
            }
        }
        auto* image = static_cast<char*>(mmap(nullptr, imageSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
        assert(image != MAP_FAILED);
        std::vector<MemoryRange> loaded;
        for (const auto& ph : segments) {
            assert(ph.p_offset + ph.p_filesz <= file.size());
            std::memcpy(image + ph.p_vaddr, file.data() + ph.p_offset, ph.p_filesz);
            if (ph.p_flags & PF_R) {
                const uintptr_t address = reinterpret_cast<uintptr_t>(image + ph.p_vaddr);
                loaded.push_back({address, address + ph.p_memsz, (ph.p_flags & PF_X) != 0});
            }
        }
        const auto name = hyperpods::findString(loaded, "l2c_fcr_chk_chan_modes");
        const auto assertion = hyperpods::findString(loaded, "assert failed: p_ccb != NULL");
        const auto target = hyperpods::findFunction(loaded, name, assertion, false);
        assert(target != 0);
        std::cout << "Extracted ARM64 library: unique target at ELF address 0x" << std::hex
            << target - reinterpret_cast<uintptr_t>(image) << '\n';
        munmap(image, imageSize);
    }
}
