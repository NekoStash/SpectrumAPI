#pragma once

#include "runtime_image.hpp"

#include <dlfcn.h>
#include <elf.h>
#include <limits.h>
#include <link.h>
#include <sys/auxv.h>
#include <unistd.h>

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace spectrum::runtime {
namespace detail {

inline void* ownImageBase() noexcept {
    Dl_info info{};
    if (dladdr(reinterpret_cast<const void*>(&ownImageBase), &info) != 0 && info.dli_fbase) {
        return info.dli_fbase;
    }
    return nullptr;
}

inline bool readMainExecutablePath(char (&buffer)[PATH_MAX]) noexcept {
    const ssize_t length = readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(buffer) - 1) return false;
    buffer[length] = '\0';
    return true;
}

inline bool isMainPhdr(const dl_phdr_info& info) noexcept {
    const unsigned long mainPhdr = getauxval(AT_PHDR);
    return mainPhdr != 0 && reinterpret_cast<uintptr_t>(info.dlpi_phdr) == mainPhdr;
}

inline bool matches(const dl_phdr_info& info, const RuntimeImage& image) noexcept {
    if (!image.path || !info.dlpi_addr) return false;
    if (image.base && reinterpret_cast<void*>(info.dlpi_addr) != image.base) return false;
    if (image.kind == RuntimeImageKind::MainExecutable) {
        return isMainPhdr(info) &&
               (!info.dlpi_name || !info.dlpi_name[0] || imagePathMatches(info.dlpi_name, image.path));
    }
    return !isMainPhdr(info) && info.dlpi_name && imagePathMatches(info.dlpi_name, image.path);
}

inline const uint8_t* resolveSegmentPointer(const dl_phdr_info& info, uintptr_t rawPtr,
                                            size_t minBytes, unsigned flags = PF_R) noexcept {
    if (!info.dlpi_addr || !info.dlpi_phdr || !rawPtr) return nullptr;
    for (size_t i = 0; i < info.dlpi_phnum; ++i) {
        const auto& seg = info.dlpi_phdr[i];
        if (seg.p_type != PT_LOAD || (seg.p_flags & flags) != flags) continue;
        // Bionic stores link-time virtual offsets in PT_DYNAMIC; also tolerate relocated addresses.
        if (rawPtr >= seg.p_vaddr && rawPtr - seg.p_vaddr <= seg.p_memsz &&
            minBytes <= seg.p_memsz - (rawPtr - seg.p_vaddr)) {
            return reinterpret_cast<const uint8_t*>(info.dlpi_addr + rawPtr);
        }
        const uintptr_t mappedBegin = info.dlpi_addr + seg.p_vaddr;
        if (rawPtr >= mappedBegin && rawPtr - mappedBegin <= seg.p_memsz &&
            minBytes <= seg.p_memsz - (rawPtr - mappedBegin)) {
            return reinterpret_cast<const uint8_t*>(rawPtr);
        }
    }
    return nullptr;
}

inline bool phdrHasPltImport(const dl_phdr_info& info, const char* symbolName) noexcept {
    if (!symbolName || !symbolName[0] || !info.dlpi_addr || !info.dlpi_phdr) return false;
    const Elf64_Dyn* dynamic = nullptr;
    size_t dynamicCount = 0;
    for (size_t i = 0; i < info.dlpi_phnum; ++i) {
        const auto& seg = info.dlpi_phdr[i];
        if (seg.p_type != PT_DYNAMIC) continue;
        if (seg.p_memsz < sizeof(Elf64_Dyn)) return false;
        const auto* ptr = resolveSegmentPointer(info, seg.p_vaddr, seg.p_memsz, PF_R);
        if (!ptr) return false;
        dynamic = reinterpret_cast<const Elf64_Dyn*>(ptr);
        dynamicCount = seg.p_memsz / sizeof(Elf64_Dyn);
        break;
    }
    if (!dynamic || !dynamicCount) return false;

    uintptr_t strtabRaw = 0;
    size_t strsz = 0;
    uintptr_t symtabRaw = 0;
    size_t syment = sizeof(Elf64_Sym);
    uintptr_t jmprelRaw = 0;
    size_t pltrelsz = 0;
    int64_t pltrelKind = DT_RELA;

    for (size_t i = 0; i < dynamicCount; ++i) {
        const auto& entry = dynamic[i];
        if (entry.d_tag == DT_NULL) break;
        switch (entry.d_tag) {
        case DT_STRTAB: strtabRaw = entry.d_un.d_ptr; break;
        case DT_STRSZ: strsz = entry.d_un.d_val; break;
        case DT_SYMTAB: symtabRaw = entry.d_un.d_ptr; break;
        case DT_SYMENT: syment = entry.d_un.d_val; break;
        case DT_JMPREL: jmprelRaw = entry.d_un.d_ptr; break;
        case DT_PLTRELSZ: pltrelsz = entry.d_un.d_val; break;
        case DT_PLTREL: pltrelKind = entry.d_un.d_val; break;
        default: break;
        }
    }
    if (!strtabRaw || !strsz || !symtabRaw || syment != sizeof(Elf64_Sym) ||
        !jmprelRaw || !pltrelsz || pltrelKind != DT_RELA || (pltrelsz % sizeof(Elf64_Rela)) != 0) {
        return false;
    }

    const auto* strtab = reinterpret_cast<const char*>(resolveSegmentPointer(info, strtabRaw, strsz, PF_R));
    const auto* relas = reinterpret_cast<const Elf64_Rela*>(resolveSegmentPointer(info, jmprelRaw, pltrelsz, PF_R));
    if (!strtab || !relas) return false;

    const size_t symbolLen = std::strlen(symbolName);
    const size_t relaCount = pltrelsz / sizeof(Elf64_Rela);
    for (size_t i = 0; i < relaCount; ++i) {
        const uint64_t rInfo = relas[i].r_info;
        if (ELF64_R_TYPE(rInfo) != R_AARCH64_JUMP_SLOT) continue;
        const size_t symIndex = ELF64_R_SYM(rInfo);
        const auto* symPtr = resolveSegmentPointer(
            info, symtabRaw + symIndex * sizeof(Elf64_Sym), sizeof(Elf64_Sym), PF_R);
        if (!symPtr) continue;
        const auto& sym = *reinterpret_cast<const Elf64_Sym*>(symPtr);
        if (sym.st_name >= strsz || strsz - sym.st_name <= symbolLen) continue;
        const char* candidate = strtab + sym.st_name;
        if (std::memcmp(candidate, symbolName, symbolLen) == 0 && candidate[symbolLen] == '\0') {
            return true;
        }
    }
    return false;
}

struct RangeQuery {
    RuntimeImage image;
    uintptr_t address;
    size_t length;
    unsigned flags;
    bool found = false;
};

inline int rangeCallback(dl_phdr_info* info, size_t, void* opaque) noexcept {
    auto& query = *static_cast<RangeQuery*>(opaque);
    if (!matches(*info, query.image)) return 0;
    for (size_t i = 0; i < info->dlpi_phnum; ++i) {
        const auto& seg = info->dlpi_phdr[i];
        if (seg.p_type != PT_LOAD || (seg.p_flags & query.flags) != query.flags) continue;
        const uintptr_t begin = info->dlpi_addr + seg.p_vaddr;
        if (query.address >= begin && query.address - begin <= seg.p_memsz &&
            query.length <= seg.p_memsz - (query.address - begin)) {
            query.found = true;
            return 1;
        }
    }
    return 0;
}

struct NamedQuery {
    uintptr_t address;
    size_t length;
    const char* expectedName;
    unsigned flags;
    bool found = false;
};

inline int namedRangeCallback(dl_phdr_info* info, size_t, void* opaque) noexcept {
    auto& query = *static_cast<NamedQuery*>(opaque);
    if (!info->dlpi_addr || isMainPhdr(*info) || !info->dlpi_name ||
        !imagePathMatches(info->dlpi_name, query.expectedName)) {
        return 0;
    }
    for (size_t i = 0; i < info->dlpi_phnum; ++i) {
        const auto& seg = info->dlpi_phdr[i];
        if (seg.p_type != PT_LOAD || (seg.p_flags & query.flags) != query.flags) continue;
        const uintptr_t begin = info->dlpi_addr + seg.p_vaddr;
        if (query.address >= begin && query.address - begin <= seg.p_memsz &&
            query.length <= seg.p_memsz - (query.address - begin)) {
            query.found = true;
            return 1;
        }
    }
    return 0;
}

} // namespace detail

template <typename Visitor>
inline void forEachSystemImage(Visitor&& visitor) noexcept {
    struct Context {
        Visitor* fn;
        void* selfBase;
        char mainPath[PATH_MAX]{};
        bool hasMain = false;
    } ctx{&visitor, detail::ownImageBase()};
    ctx.hasMain = detail::readMainExecutablePath(ctx.mainPath);

    dl_iterate_phdr(
        [](dl_phdr_info* info, size_t, void* opaque) -> int {
            auto& c = *static_cast<Context*>(opaque);
            if (!info->dlpi_addr) return 0;
            void* base = reinterpret_cast<void*>(info->dlpi_addr);
            if (c.selfBase && base == c.selfBase) return 0;

            if (detail::isMainPhdr(*info)) {
                if (!c.hasMain || !isSystemPartitionPath(c.mainPath)) return 0;
                RuntimeImage image{c.mainPath, base, RuntimeImageKind::MainExecutable};
                return (*c.fn)(image, *info) ? 0 : 1;
            }
            if (!info->dlpi_name || !isSystemPartitionPath(info->dlpi_name)) return 0;
            RuntimeImage image{info->dlpi_name, base, RuntimeImageKind::SharedLibrary};
            return (*c.fn)(image, *info) ? 0 : 1;
        },
        &ctx);
}

inline bool contains(const RuntimeImage& image, const void* address, size_t length,
                     unsigned flags) noexcept {
    if (!address || !length || !image.path || !image.base) return false;
    detail::RangeQuery query{image, reinterpret_cast<uintptr_t>(address), length, flags};
    dl_iterate_phdr(detail::rangeCallback, &query);
    return query.found;
}

inline bool executable(const RuntimeImage& image, const void* address) noexcept {
    return address && (reinterpret_cast<uintptr_t>(address) & 3U) == 0 &&
           contains(image, address, sizeof(uint32_t), PF_R | PF_X);
}

inline bool mappedInLibrary(const void* address, size_t length, const char* libraryName,
                            unsigned flags) noexcept {
    if (!address || !length || !libraryName) return false;
    detail::NamedQuery query{reinterpret_cast<uintptr_t>(address), length, libraryName, flags};
    dl_iterate_phdr(detail::namedRangeCallback, &query);
    return query.found;
}

inline bool executableInLibrary(const void* address, const char* libraryName) noexcept {
    return address && (reinterpret_cast<uintptr_t>(address) & 3U) == 0 &&
           mappedInLibrary(address, sizeof(uint32_t), libraryName, PF_R | PF_X);
}

inline bool hasPltImport(const dl_phdr_info& info, const char* symbolName) noexcept {
    return detail::phdrHasPltImport(info, symbolName);
}

} // namespace spectrum::runtime
