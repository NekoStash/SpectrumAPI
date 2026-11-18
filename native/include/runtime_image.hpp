#pragma once

#include <cstring>
#include <string_view>

namespace spectrum {

enum class RuntimeImageKind { SharedLibrary, MainExecutable };

struct RuntimeImage {
    const char* path = nullptr;
    void* base = nullptr;
    RuntimeImageKind kind = RuntimeImageKind::SharedLibrary;
};

constexpr bool imagePathMatches(std::string_view actual, std::string_view expected) noexcept {
    if (actual.empty() || expected.empty()) return false;
    if (expected.front() == '/') return actual == expected;
    const auto slash = actual.find_last_of('/');
    return actual.substr(slash == std::string_view::npos ? 0 : slash + 1) == expected;
}

constexpr bool isSystemPartitionPath(std::string_view path) noexcept {
    return path.starts_with("/system/") ||
           path.starts_with("/system_ext/") ||
           path.starts_with("/product/") ||
           path.starts_with("/vendor/") ||
           path.starts_with("/apex/");
}

inline bool sameRuntimeImage(const RuntimeImage& first, const RuntimeImage& second) noexcept {
    return first.base && first.base == second.base && first.kind == second.kind &&
           first.path && second.path && std::strcmp(first.path, second.path) == 0;
}

} // namespace spectrum
