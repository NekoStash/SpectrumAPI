#include "zygisk_next_api.h"
#include "platform_bridge.hpp"

#include <android/log.h>
#include <limits.h>
#include <unistd.h>

#include <atomic>
#include <cstring>

namespace {

static_assert(ZYGISK_NEXT_API_VERSION == 4);

enum class BootstrapState : unsigned { Initial, Initializing, Ready, Failed };
constinit std::atomic<BootstrapState> gState{BootstrapState::Initial};

constexpr char kTargetExecutable[] = "/system/bin/audioserver";

bool hasRequiredApi(const ZygiskNextAPI* api) noexcept {
    return api && api->pltHook && api->inlineHook && api->inlineUnhook &&
           api->newSymbolResolver && api->freeSymbolResolver &&
           api->getBaseAddress && api->symbolLookup;
}

void onModuleLoaded(void*, const ZygiskNextAPI* api) noexcept {
    BootstrapState expected = BootstrapState::Initial;
    if (!gState.compare_exchange_strong(expected, BootstrapState::Initializing,
                                        std::memory_order_acq_rel)) {
        return;
    }

    struct Guard {
        ~Guard() {
            BootstrapState state = BootstrapState::Initializing;
            gState.compare_exchange_strong(state, BootstrapState::Failed,
                                           std::memory_order_release);
        }
    } guard;

    char executable[PATH_MAX]{};
    const ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(executable) - 1) {
        __android_log_print(ANDROID_LOG_WARN, "SpectrumAPI",
                            "Unable to read /proc/self/exe; initialization skipped");
        return;
    }
    executable[length] = '\0';

    if (std::strcmp(executable, kTargetExecutable) != 0) {
        __android_log_print(ANDROID_LOG_INFO, "SpectrumAPI",
                            "Skipping non-target process %s", executable);
        return;
    }

    if (!hasRequiredApi(api)) {
        __android_log_print(ANDROID_LOG_WARN, "SpectrumAPI",
                            "Required module API v%d entries are missing",
                            ZYGISK_NEXT_API_VERSION);
        return;
    }

    if (!spectrum::initializePayload(api)) {
        __android_log_print(ANDROID_LOG_WARN, "SpectrumAPI",
                            "Audio pipeline initialization declined; module remains passive");
        return;
    }

    gState.store(BootstrapState::Ready, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, "SpectrumAPI",
                        "Audio spectrum module initialized in %s (pid=%ld)",
                        executable, static_cast<long>(getpid()));
}

} // namespace

extern "C" {
__attribute__((visibility("default"))) ZygiskNextModule zn_module = {
    ZYGISK_NEXT_API_VERSION,
    onModuleLoaded,
};
}
