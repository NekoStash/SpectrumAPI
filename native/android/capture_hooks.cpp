#include "capture.hpp"
#include "platform_bridge.hpp"
#include "runtime_mapping.hpp"
#include "zygisk_next_api.h"

#include <android/binder_ibinder.h>
#include <android/log.h>
#include <limits.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>

namespace {

spectrum::RuntimeImage gSamplingImage{};
char gSamplingPath[PATH_MAX]{};
constinit std::atomic<bool> gCaptureEnabled{false};
constinit std::atomic<unsigned> gInitState{0};

struct AudioAttributes {
    int32_t contentType;
    int32_t usage;
    int32_t source;
    uint32_t flags;
    char tags[256];
};

struct ProviderBuffer {
    const void* raw;
    size_t frameCount;
};
static_assert(sizeof(ProviderBuffer) == 16);

// C2 and D2 receive the VTT after this. A by-value platform string is
// passed as an invisible pointer; the callback must not copy or destroy it.
using LegacyBaseCtor = void (*)(void*, const void*, void*, const void*, const AudioAttributes*,
                               uint32_t, int32_t, uint32_t, size_t, void*, size_t, int32_t,
                               int32_t, uint32_t, bool, int32_t, int32_t, int32_t, void*);
using ModernBaseCtor = void (*)(void*, const void*, void*, const void*, const AudioAttributes*,
                               uint32_t, int32_t, uint32_t, size_t, void*, size_t, int32_t,
                               int32_t, uint32_t, bool, int32_t, int32_t, int32_t, void*, const void*);
using BaseDtor = void (*)(void*, const void*);
using InterceptFn = void (*)(void*, const ProviderBuffer*);
using StartFn = int32_t (*)(void*, int32_t, int32_t);
using LifecycleFn = void (*)(void*);

LegacyBaseCtor gOriginalLegacyCtor = nullptr;
ModernBaseCtor gOriginalModernCtor = nullptr;
BaseDtor gOriginalDtor = nullptr;
InterceptFn gOriginalIntercept = nullptr;
StartFn gOriginalStart = nullptr;
LifecycleFn gOriginalStop = nullptr;
LifecycleFn gOriginalPause = nullptr;
LifecycleFn gOriginalFlush = nullptr;

void registerPlaybackTrack(void* self, uint32_t owner, int32_t session, int32_t usage,
                           uint32_t rate, int32_t format, uint32_t mask,
                           bool output, int32_t type, bool hasAttributes) {
    if (!gCaptureEnabled.load(std::memory_order_acquire) || !output || type != 0 || !hasAttributes) return;
    const uint32_t rep = mask >> 30;
    const uint32_t count = static_cast<uint32_t>(__builtin_popcount(mask & 0x3fffffffu));
    const uint32_t channels =
        ((rep == 0u || rep == 2u) && (count == 1u || count == 2u)) ? count : 0u;
    spectrum::capture::registerTrack(self, owner, session, usage, rate,
                                     static_cast<uint32_t>(format), channels);
}

void onLegacyTrackConstruct(void* self, const void* vtt, void* thread, const void* client,
                            const AudioAttributes* attr, uint32_t rate, int32_t format, uint32_t mask,
                            size_t frames, void* buffer, size_t bytes, int32_t session, int32_t pid,
                            uint32_t uid, bool output, int32_t allocation, int32_t type, int32_t port,
                            void* metrics) {
    const uint32_t caller = AIBinder_getCallingUid();
    const uint32_t owner = (uid != UINT32_MAX) ? uid : caller;
    const int32_t usage = attr ? attr->usage : 0;
    gOriginalLegacyCtor(self, vtt, thread, client, attr, rate, format, mask, frames, buffer, bytes,
                        session, pid, uid, output, allocation, type, port, metrics);
    registerPlaybackTrack(self, owner, session, usage, rate, format, mask, output, type, attr != nullptr);
}

void onTrackConstruct(void* self, const void* vtt, void* thread, const void* client,
                      const AudioAttributes* attr, uint32_t rate, int32_t format, uint32_t mask,
                      size_t frames, void* buffer, size_t bytes, int32_t session, int32_t pid,
                      uint32_t uid, bool output, int32_t allocation, int32_t type, int32_t port,
                      void* metrics, const void* codecProvenance) {
    const uint32_t caller = AIBinder_getCallingUid();
    const uint32_t owner = (uid != UINT32_MAX) ? uid : caller;
    const int32_t usage = attr ? attr->usage : 0;
    gOriginalModernCtor(self, vtt, thread, client, attr, rate, format, mask, frames, buffer, bytes,
                        session, pid, uid, output, allocation, type, port, metrics, codecProvenance);
    registerPlaybackTrack(self, owner, session, usage, rate, format, mask, output, type, attr != nullptr);
}

void onTrackDestroy(void* self, const void* vtt) {
    if (gCaptureEnabled.load(std::memory_order_acquire)) {
        spectrum::capture::retireTrack(self);
    }
    gOriginalDtor(self, vtt);
}

void onInterceptBuffer(void* self, const ProviderBuffer* buffer) {
    if (gCaptureEnabled.load(std::memory_order_acquire) && buffer && buffer->raw &&
        buffer->frameCount) {
        spectrum::capture::pcm(self, buffer->raw, buffer->frameCount);
    }
    gOriginalIntercept(self, buffer);
}

int32_t onTrackStart(void* self, int32_t event, int32_t session) {
    const int32_t status = gOriginalStart(self, event, session);
    if (status == 0 && gCaptureEnabled.load(std::memory_order_acquire)) {
        spectrum::capture::setTrackActive(self, true);
    }
    return status;
}

void onTrackStop(void* self) {
    gOriginalStop(self);
    if (gCaptureEnabled.load(std::memory_order_acquire)) {
        spectrum::capture::setTrackActive(self, false);
    }
}

void onTrackPause(void* self) {
    gOriginalPause(self);
    if (gCaptureEnabled.load(std::memory_order_acquire)) {
        spectrum::capture::setTrackActive(self, false);
    }
}

void onTrackFlush(void* self) {
    gOriginalFlush(self);
    if (gCaptureEnabled.load(std::memory_order_acquire)) {
        spectrum::capture::setTrackActive(self, false);
    }
}

enum class BindingKind { Plt, Inline };

struct EntryBinding {
    const char* symbol;
    void* replacement;
    void** originalSlot;
    BindingKind kind;
    void* targetAddress = nullptr;
    bool active = false;
};

constexpr char kLegacyTrackBaseCtor[] =
    "_ZN7android9TrackBaseC2EPNS_13IAfThreadBaseERKNS_2spINS_6ClientEEERK18audio_attributes_tj14audio_format_t20audio_channel_mask_tmPvm15audio_session_tijbNS_12IAfTrackBase10alloc_typeENSF_10track_typeEiNSt3__112basic_stringIcNSI_11char_traitsIcEENSI_9allocatorIcEEEE";
constexpr char kModernTrackBaseCtor[] =
    "_ZN7android9TrackBaseC2EPNS_13IAfThreadBaseERKNS_2spINS_6ClientEEERK18audio_attributes_tj14audio_format_t20audio_channel_mask_tmPvm15audio_session_tijbNS_12IAfTrackBase10alloc_typeENSF_10track_typeEiNSt3__112basic_stringIcNSI_11char_traitsIcEENSI_9allocatorIcEEEERKSO_";
constexpr char kTrackBaseDtor[] = "_ZN7android9TrackBaseD2Ev";
constexpr char kTrackIntercept[] =
    "_ZN7android5Track15interceptBufferERKNS_19AudioBufferProvider6BufferE";
constexpr char kTrackStart[] =
    "_ZN7android5Track5startENS_11AudioSystem12sync_event_tE15audio_session_t";
constexpr char kTrackStop[] = "_ZN7android5Track4stopEv";
constexpr char kTrackPause[] = "_ZN7android5Track5pauseEv";
constexpr char kTrackFlush[] = "_ZN7android5Track5flushEv";

bool failInit(const char* message) noexcept {
    spectrum::capture::setReady(0, message);
    __android_log_print(ANDROID_LOG_WARN, "SpectrumAPI", "%s", message);
    return false;
}

using Bindings = std::array<EntryBinding, 7>;

struct AbiProfile {
    const char* name;
    Bindings bindings;
};

Bindings makeBindings(EntryBinding constructor) noexcept {
    return {{
        constructor,
        {kTrackBaseDtor, reinterpret_cast<void*>(&onTrackDestroy),
         reinterpret_cast<void**>(&gOriginalDtor), BindingKind::Plt},
        {kTrackIntercept, reinterpret_cast<void*>(&onInterceptBuffer),
         reinterpret_cast<void**>(&gOriginalIntercept), BindingKind::Plt},
        {kTrackStart, reinterpret_cast<void*>(&onTrackStart),
         reinterpret_cast<void**>(&gOriginalStart), BindingKind::Inline},
        {kTrackStop, reinterpret_cast<void*>(&onTrackStop),
         reinterpret_cast<void**>(&gOriginalStop), BindingKind::Inline},
        {kTrackPause, reinterpret_cast<void*>(&onTrackPause),
         reinterpret_cast<void**>(&gOriginalPause), BindingKind::Inline},
        {kTrackFlush, reinterpret_cast<void*>(&onTrackFlush),
         reinterpret_cast<void**>(&gOriginalFlush), BindingKind::Inline},
    }};
}

bool restoreBindings(const ZygiskNextAPI* api, Bindings& bindings) noexcept {
    bool restored = true;
    for (auto it = bindings.rbegin(); it != bindings.rend(); ++it) {
        if (!it->active) continue;
        const int rc = it->kind == BindingKind::Inline
            ? api->inlineUnhook(it->targetAddress)
            : api->pltHook(gSamplingImage.base, it->symbol, *it->originalSlot, nullptr);
        if (rc == ZN_SUCCESS) {
            it->active = false;
            if (it->kind == BindingKind::Inline && !*it->originalSlot) {
                *it->originalSlot = it->targetAddress;
            }
        } else {
            restored = false;
            __android_log_print(ANDROID_LOG_ERROR, "SpectrumAPI",
                                "AudioFlinger hook rollback failed for %s (rc=%d)", it->symbol, rc);
        }
    }
    return restored;
}

struct ProfileFailure {
    const char* reason = "no candidate image with an AudioFlinger PLT entry";
    const char* symbol = nullptr;
    size_t validated = 0;
};

enum class Selection { Found, Missing, Ambiguous, InvalidPath };

Selection selectSamplingImage(const ZygiskNextAPI* api, AbiProfile& selected) noexcept {
    const std::array profiles{
        AbiProfile{"Legacy15_16", makeBindings(
            {kLegacyTrackBaseCtor, reinterpret_cast<void*>(&onLegacyTrackConstruct),
             reinterpret_cast<void**>(&gOriginalLegacyCtor), BindingKind::Plt})},
        AbiProfile{"Modern17", makeBindings(
            {kModernTrackBaseCtor, reinterpret_cast<void*>(&onTrackConstruct),
             reinterpret_cast<void**>(&gOriginalModernCtor), BindingKind::Plt})},
    };
    std::array<ProfileFailure, profiles.size()> failures{};
    char matchedPath[PATH_MAX]{};
    spectrum::RuntimeImage matchedImage{};
    Selection result = Selection::Missing;

    spectrum::runtime::forEachSystemImage([&](const spectrum::RuntimeImage& candidate,
                                              const dl_phdr_info& phdr) noexcept {
        const bool ctorImports[] = {
            spectrum::runtime::hasPltImport(phdr, kLegacyTrackBaseCtor),
            spectrum::runtime::hasPltImport(phdr, kModernTrackBaseCtor),
        };
        const bool dtorImport = spectrum::runtime::hasPltImport(phdr, kTrackBaseDtor);
        const bool interceptImport = spectrum::runtime::hasPltImport(phdr, kTrackIntercept);
        if (!ctorImports[0] && !ctorImports[1] && !dtorImport && !interceptImport) return true;
        auto* resolver = api->newSymbolResolver(candidate.path, candidate.base);
        if (!resolver || api->getBaseAddress(resolver) != candidate.base) {
            if (resolver) api->freeSymbolResolver(resolver);
            __android_log_print(ANDROID_LOG_WARN, "SpectrumAPI",
                                "AudioFlinger resolver unavailable or mapped at the wrong base: %s",
                                candidate.path);
            return true;
        }

        for (size_t p = 0; p < profiles.size(); ++p) {
            auto bindings = profiles[p].bindings;
            const std::array<bool, Bindings{}.size()> imports{ctorImports[p], dtorImport, interceptImport};
            size_t i = 0;
            for (; i < bindings.size(); ++i) {
                auto& entry = bindings[i];
                entry.targetAddress = api->symbolLookup(resolver, entry.symbol, false, nullptr);
                const char* failure = !entry.targetAddress ? "symbol missing" :
                    !spectrum::runtime::executable(candidate, entry.targetAddress) ? "target not executable" :
                    entry.kind == BindingKind::Plt && !imports[i] ? "PLT jump slot missing" : nullptr;
                if (!failure) continue;
                if (i >= failures[p].validated) failures[p] = {failure, entry.symbol, i};
                break;
            }
            if (i != bindings.size()) continue;

            if (result == Selection::Found) {
                __android_log_print(ANDROID_LOG_WARN, "SpectrumAPI",
                                    "Ambiguous AudioFlinger ABI: %s in %s and %s in %s",
                                    selected.name, matchedPath, profiles[p].name, candidate.path);
                result = Selection::Ambiguous;
                break;
            }
            const size_t length = std::strlen(candidate.path);
            if (length >= sizeof(matchedPath)) {
                result = Selection::InvalidPath;
                break;
            }
            std::memcpy(matchedPath, candidate.path, length + 1);
            matchedImage = {matchedPath, candidate.base, candidate.kind};
            selected = {profiles[p].name, bindings};
            result = Selection::Found;
        }
        api->freeSymbolResolver(resolver);
        return result == Selection::Missing || result == Selection::Found;
    });

    if (result == Selection::Found) {
        std::memcpy(gSamplingPath, matchedPath, std::strlen(matchedPath) + 1);
        gSamplingImage = {gSamplingPath, matchedImage.base, matchedImage.kind};
    } else if (result == Selection::Missing) {
        for (size_t p = 0; p < profiles.size(); ++p) {
            const auto& failure = failures[p];
            __android_log_print(ANDROID_LOG_WARN, "SpectrumAPI", "AudioFlinger %s: %s%s%s",
                                profiles[p].name, failure.reason, failure.symbol ? ": " : "",
                                failure.symbol ? failure.symbol : "");
        }
    }
    return result;
}

} // namespace

namespace spectrum {

bool initializePayload(const ZygiskNextAPI* api) noexcept {
    unsigned expected = 0;
    if (!gInitState.compare_exchange_strong(expected, 1u, std::memory_order_acq_rel)) {
        return expected == 2u;
    }

    AbiProfile profile{};
    const auto selection = selectSamplingImage(api, profile);
    if (selection != Selection::Found) {
        gInitState.store(3u, std::memory_order_release);
        if (selection == Selection::Ambiguous) return failInit("Ambiguous AudioFlinger ABI profiles");
        if (selection == Selection::InvalidPath) return failInit("AudioFlinger image path is too long");
        return failInit("No complete compatible AudioFlinger ABI profile found");
    }

    for (auto& entry : profile.bindings) {
        *entry.originalSlot = nullptr;
        const int rc = (entry.kind == BindingKind::Inline)
                           ? api->inlineHook(entry.targetAddress, entry.replacement,
                                             entry.originalSlot)
                           : api->pltHook(gSamplingImage.base, entry.symbol,
                                          entry.replacement, entry.originalSlot);
        entry.active = (rc == ZN_SUCCESS);
        const bool validOriginal = (*entry.originalSlot != nullptr);
        if (rc != ZN_SUCCESS || !validOriginal) {
            // A PLT replacement can be restored to the validated definition if
            // the backend reports success without providing its original slot.
            if (!validOriginal && entry.kind == BindingKind::Plt) *entry.originalSlot = entry.targetAddress;
            const bool restored = restoreBindings(api, profile.bindings);
            gInitState.store(3u, std::memory_order_release);
            char message[512];
            std::snprintf(message, sizeof(message), "%s hook failed for %s (rc=%d, original=%s, rollback=%s)",
                          profile.name, entry.symbol, rc, validOriginal ? "present" : "missing",
                          restored ? "complete" : "failed");
            return failInit(message);
        }
    }

    if (!installPlatformBridge(api)) {
        const bool restored = restoreBindings(api, profile.bindings);
        gInitState.store(3u, std::memory_order_release);
        return failInit(restored ? "Service registration extension bridge unavailable"
                                 : "Service registration bridge unavailable; playback hook rollback failed");
    }

    gCaptureEnabled.store(true, std::memory_order_release);
    capture::setReady(0x7u, "Passive playback PCM capture ready");
    gInitState.store(2u, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, "SpectrumAPI",
                        "Playback capture ready: %s on %s", profile.name, gSamplingImage.path);
    return true;
}

} // namespace spectrum
