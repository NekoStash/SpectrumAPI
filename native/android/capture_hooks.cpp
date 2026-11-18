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

using BaseCtor = void (*)(void*, const void*, void*, const void*, const AudioAttributes*, uint32_t,
                          int32_t, uint32_t, size_t, void*, size_t, int32_t, int32_t,
                          uint32_t, bool, int32_t, int32_t, int32_t, void*, const void*);
using BaseDtor = void (*)(void*, const void*);
using InterceptFn = void (*)(void*, const ProviderBuffer*);
using StartFn = int32_t (*)(void*, int32_t, int32_t);
using LifecycleFn = void (*)(void*);

BaseCtor gOriginalCtor = nullptr;
BaseDtor gOriginalDtor = nullptr;
InterceptFn gOriginalIntercept = nullptr;
StartFn gOriginalStart = nullptr;
LifecycleFn gOriginalStop = nullptr;
LifecycleFn gOriginalPause = nullptr;
LifecycleFn gOriginalFlush = nullptr;

void onTrackConstruct(void* self, const void* vtt, void* thread, const void* client,
                      const AudioAttributes* attr, uint32_t rate, int32_t format, uint32_t mask,
                      size_t frames, void* buffer, size_t bytes, int32_t session, int32_t pid,
                      uint32_t uid, bool output, int32_t allocation, int32_t type, int32_t port,
                      void* metrics, const void* tag) {
    const uint32_t caller = AIBinder_getCallingUid();
    const uint32_t owner = (uid != UINT32_MAX) ? uid : caller;
    const int32_t usage = attr ? attr->usage : 0;
    gOriginalCtor(self, vtt, thread, client, attr, rate, format, mask, frames, buffer, bytes,
                  session, pid, uid, output, allocation, type, port, metrics, tag);
    if (!gCaptureEnabled.load(std::memory_order_acquire) || !output || type != 0 || !attr) return;
    const uint32_t rep = mask >> 30;
    const uint32_t count = static_cast<uint32_t>(__builtin_popcount(mask & 0x3fffffffu));
    const uint32_t channels =
        ((rep == 0u || rep == 2u) && (count == 1u || count == 2u)) ? count : 0u;
    spectrum::capture::registerTrack(self, owner, session, usage, rate,
                                     static_cast<uint32_t>(format), channels);
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

constexpr char kTrackBaseCtor[] =
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

void restoreBindings(const ZygiskNextAPI* api, std::array<EntryBinding, 7>& bindings) noexcept {
    for (auto it = bindings.rbegin(); it != bindings.rend(); ++it) {
        if (!it->active) continue;
        if (it->kind == BindingKind::Inline) {
            (void)api->inlineUnhook(it->targetAddress);
        } else {
            (void)api->pltHook(gSamplingImage.base, it->symbol, *it->originalSlot, nullptr);
        }
    }
}

bool selectSamplingImage(const ZygiskNextAPI* api,
                         std::array<EntryBinding, 7>& bindings) noexcept {
    bool matched = false;
    bool ambiguous = false;

    spectrum::runtime::forEachSystemImage([&](const spectrum::RuntimeImage& candidate,
                                              const dl_phdr_info& phdr) noexcept {
        for (const auto& entry : bindings) {
            if (entry.kind == BindingKind::Plt &&
                !spectrum::runtime::hasPltImport(phdr, entry.symbol)) {
                return true;
            }
        }
        auto* resolver = api->newSymbolResolver(candidate.path, candidate.base);
        if (!resolver) return true;
        if (api->getBaseAddress(resolver) != candidate.base) {
            api->freeSymbolResolver(resolver);
            return true;
        }

        std::array<void*, 7> resolved{};
        bool complete = true;
        for (size_t i = 0; i < bindings.size(); ++i) {
            resolved[i] = api->symbolLookup(resolver, bindings[i].symbol, false, nullptr);
            if (!spectrum::runtime::executable(candidate, resolved[i])) {
                complete = false;
                break;
            }
        }
        api->freeSymbolResolver(resolver);
        if (!complete) return true;

        if (matched) {
            ambiguous = true;
            return false;
        }
        const size_t len = std::strlen(candidate.path);
        if (len >= sizeof(gSamplingPath)) {
            ambiguous = true;
            return false;
        }
        std::memcpy(gSamplingPath, candidate.path, len + 1);
        gSamplingImage = {gSamplingPath, candidate.base, candidate.kind};
        for (size_t i = 0; i < bindings.size(); ++i) {
            bindings[i].targetAddress = resolved[i];
        }
        matched = true;
        return true;
    });

    return matched && !ambiguous;
}

} // namespace

namespace spectrum {

bool initializePayload(const ZygiskNextAPI* api) noexcept {
    unsigned expected = 0;
    if (!gInitState.compare_exchange_strong(expected, 1u, std::memory_order_acq_rel)) {
        return expected == 2u;
    }

    std::array<EntryBinding, 7> bindings{{
        {kTrackBaseCtor, reinterpret_cast<void*>(&onTrackConstruct),
         reinterpret_cast<void**>(&gOriginalCtor), BindingKind::Plt},
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

    if (!selectSamplingImage(api, bindings)) {
        gInitState.store(3u, std::memory_order_release);
        return failInit("Compatible audio playback image not found or ambiguous");
    }

    for (auto& entry : bindings) {
        *entry.originalSlot = nullptr;
        const int rc = (entry.kind == BindingKind::Inline)
                           ? api->inlineHook(entry.targetAddress, entry.replacement,
                                             entry.originalSlot)
                           : api->pltHook(gSamplingImage.base, entry.symbol,
                                          entry.replacement, entry.originalSlot);
        entry.active = (rc == ZN_SUCCESS);
        const bool validOriginal = (*entry.originalSlot != nullptr);
        if (!validOriginal) *entry.originalSlot = entry.targetAddress;
        if (rc != ZN_SUCCESS || !validOriginal) {
            restoreBindings(api, bindings);
            gInitState.store(3u, std::memory_order_release);
            return failInit("Playback callback installation failed");
        }
    }

    if (!installPlatformBridge(api)) {
        restoreBindings(api, bindings);
        gInitState.store(3u, std::memory_order_release);
        return failInit("Service registration extension bridge unavailable");
    }

    gCaptureEnabled.store(true, std::memory_order_release);
    capture::setReady(0x7u, "Passive playback PCM capture ready");
    gInitState.store(2u, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, "SpectrumAPI",
                        "Playback capture ready on %s", gSamplingImage.path);
    return true;
}

} // namespace spectrum
