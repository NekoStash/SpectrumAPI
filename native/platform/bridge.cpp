#include "platform_bridge.hpp"
#include "capture.hpp"
#include "runtime_mapping.hpp"
#include "zygisk_next_api.h"

#include <binder/Binder.h>
#include <binder/IServiceManager.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <type_traits>
#include <utility>

android::sp<android::IBinder> AIBinder_toPlatformBinder(AIBinder* binder);

namespace spectrum {
namespace {

static_assert(ZYGISK_NEXT_API_VERSION == 4);
static_assert(sizeof(void*) == 8, "AArch64 ABI required");

using Manager = android::IServiceManager;
using DefaultServiceManagerFn = android::sp<Manager> (*)();
using ToPlatformBinderFn = decltype(&AIBinder_toPlatformBinder);
using AddServiceFn = android::status_t (*)(void*, const android::String16&,
                                           const android::sp<android::IBinder>&, bool, int);
using AddServiceMember = android::status_t (Manager::*)(
    const android::String16&, const android::sp<android::IBinder>&, bool, int);

static_assert(sizeof(android::sp<Manager>) == sizeof(void*));
static_assert(!std::is_trivially_destructible_v<android::sp<Manager>>);
static_assert(std::is_same_v<decltype(&Manager::addService), AddServiceMember>);

constexpr char kLogTag[] = "SpectrumPlatform";
constexpr char kDefaultManagerSymbol[] = "_ZN7android21defaultServiceManagerEv";
constexpr char kToPlatformBinderSymbol[] = "_Z25AIBinder_toPlatformBinderP8AIBinder";
constexpr char16_t kServiceName[] = u"media.audio_flinger";
constexpr size_t kServiceNameLength = (sizeof(kServiceName) / sizeof(char16_t)) - 1;
// Android AArch64 Clang emits {0x30, 1} for &IServiceManager::addService with the
// pinned frameworks/native headers (third_party/aosp/manifest.json).
constexpr std::ptrdiff_t kAddServiceOffset = 0x30;
constexpr size_t kMaxManagerSlots = 4;
constexpr size_t kMaxCallerSlots = 32;

pthread_mutex_t gBridgeMutex = PTHREAD_MUTEX_INITIALIZER;
const ZygiskNextAPI* gBridgeApi = nullptr;
std::atomic<bool> gBridgeActive{false};
std::atomic<bool> gExtensionAttached{false};
bool gExtensionTried = false;
bool gBridgeInstallTried = false;
size_t gVtableSlotOffset = 0;

DefaultServiceManagerFn gDefaultFallback = nullptr;
ToPlatformBinderFn gToPlatformBinder = nullptr;
ndk::SpAIBinder gNdkService;
android::sp<android::IBinder> gPlatformExtension;

struct DispatchSlot {
    void* key = nullptr;
    void* next = nullptr;
};

std::array<DispatchSlot, kMaxManagerSlots> gAddServiceSlots{};
std::array<DispatchSlot, kMaxCallerSlots> gCallerSlots{};

class ScopedMutex final {
public:
    explicit ScopedMutex(pthread_mutex_t& m) noexcept
        : mutex_(m), error_(pthread_mutex_lock(&m)) {}
    ~ScopedMutex() {
        if (!error_) pthread_mutex_unlock(&mutex_);
    }
    explicit operator bool() const noexcept { return error_ == 0; }
    int error() const noexcept { return error_; }

private:
    pthread_mutex_t& mutex_;
    int error_;
};

const char* addressImage(const void* addr) noexcept {
    if (!addr) return "<null>";
    Dl_info info{};
    return dladdr(addr, &info) != 0 && info.dli_fname ? info.dli_fname : "<unknown image>";
}

size_t resolveAddServiceOffset() noexcept {
    const AddServiceMember member = &Manager::addService;
    struct MemberPointer {
        std::ptrdiff_t fn;
        std::ptrdiff_t adj;
    } rep{};
    static_assert(sizeof(member) == sizeof(rep));
    std::memcpy(&rep, &member, sizeof(member));
    std::ptrdiff_t offset = -1;
    if ((rep.fn & 1) != 0 && rep.adj == 0) {
        offset = rep.fn - 1; // Generic Itanium encoding: vtable byte offset + 1.
    } else if (rep.adj == 1) {
        offset = rep.fn; // AArch64 encoding: virtual bit set, zero this adjustment.
    }
    if (offset == kAddServiceOffset) return static_cast<size_t>(offset);
    __android_log_print(ANDROID_LOG_WARN, kLogTag,
                        "[abi] IServiceManager::addService member pointer (%td,%td) "
                        "does not encode the expected vtable offset %#zx with zero this adjustment",
                        rep.fn, rep.adj, static_cast<size_t>(kAddServiceOffset));
    return 0;
}

bool checkRequiredExports() noexcept {
    struct ExportSpec {
        const char* symbol;
        const char* library;
    };
    constexpr ExportSpec kSpecs[] = {
        {kDefaultManagerSymbol, "libbinder.so"},
        {"_ZN7android7BBinder11wasParceledEv", "libbinder.so"},
        {"_ZN7android7BBinder12getExtensionEv", "libbinder.so"},
        {"_ZN7android7BBinder12setExtensionERKNS_2spINS_7IBinderEEE", "libbinder.so"},
        {kToPlatformBinderSymbol, "libbinder_ndk.so"},
        {"_ZNK7android7RefBase9incStrongEPKv", "libutils.so"},
        {"_ZNK7android7RefBase9decStrongEPKv", "libutils.so"},
        {"_ZNK7android8String164sizeEv", "libutils.so"},
        {"_ZN7android14sp_report_raceEv", "libutils.so"},
    };
    bool usable = true;
    for (const auto& spec : kSpecs) {
        void* addr = dlsym(RTLD_DEFAULT, spec.symbol);
        if (!addr) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[exports] %s: %s not visible to dlsym(RTLD_DEFAULT)",
                                spec.library, spec.symbol);
            usable = false;
            continue;
        }
        if (!runtime::executableInLibrary(addr, spec.library)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[exports] %s: %s resolved in %s but is not an aligned "
                                "executable address in the expected library",
                                spec.library, spec.symbol, addressImage(addr));
            usable = false;
            continue;
        }
        if (std::strcmp(spec.symbol, kDefaultManagerSymbol) == 0) {
            gDefaultFallback = reinterpret_cast<DefaultServiceManagerFn>(addr);
        } else if (std::strcmp(spec.symbol, kToPlatformBinderSymbol) == 0) {
            gToPlatformBinder = reinterpret_cast<ToPlatformBinderFn>(addr);
        }
    }
    return usable;
}

bool maybeAttachAudioExtension(const android::String16& name,
                               const android::sp<android::IBinder>& service) noexcept {
    if (!gBridgeActive.load(std::memory_order_acquire) ||
        gExtensionAttached.load(std::memory_order_acquire) ||
        name.size() != kServiceNameLength ||
        std::memcmp(name.c_str(), kServiceName, kServiceNameLength * sizeof(char16_t)) != 0) {
        return false;
    }

    ScopedMutex lock(gBridgeMutex);
    if (!lock) {
        static std::atomic_flag logged{};
        if (!logged.test_and_set(std::memory_order_relaxed)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[extension] cannot lock bridge for audio_flinger attachment: %d",
                                lock.error());
        }
        return false;
    }
    if (gExtensionTried) return false;
    gExtensionTried = true;

    const char* failure = nullptr;
    try {
        if (!service) {
            failure = "registered binder is null";
        } else if (!gPlatformExtension) {
            failure = "platform extension binder was not initialized";
        } else if (auto* local = service->localBinder(); !local) {
            failure = "registered binder is not local";
        } else if (local->wasParceled()) {
            failure = "local binder was already parceled";
        } else if (local->getExtension()) {
            failure = "local binder already has an extension; leaving it unchanged";
        } else {
            local->setExtension(gPlatformExtension);
            if (local->getExtension().get() == gPlatformExtension.get()) {
                gExtensionAttached.store(true, std::memory_order_release);
                __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                    "[extension] attached to media.audio_flinger before registration");
                return true;
            }
            failure = "setExtension read-back did not match";
        }
    } catch (...) {
        failure = "exception during attachment";
    }
    __android_log_print(ANDROID_LOG_WARN, kLogTag,
                        "[extension] media.audio_flinger attachment not confirmed: %s", failure);
    return false;
}

template <size_t SlotIndex>
android::status_t onAddService(void* self, const android::String16& name,
                               const android::sp<android::IBinder>& service,
                               bool allowIsolated, int dumpsysFlags) noexcept {
    AddServiceFn nextFn = nullptr;
    {
        ScopedMutex lock(gBridgeMutex);
        if (lock) nextFn = reinterpret_cast<AddServiceFn>(gAddServiceSlots[SlotIndex].next);
        if (!nextFn) {
            static std::atomic_flag logged{};
            if (!logged.test_and_set(std::memory_order_relaxed)) {
                __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                    "[inline] cannot forward addService (slot=%zu, mutex error=%d): "
                                    "no safe original; refusing to re-enter the patched target",
                                    SlotIndex, lock.error());
            }
            return android::INVALID_OPERATION;
        }
    }
    const bool attached = maybeAttachAudioExtension(name, service);
    const auto status = nextFn(self, name, service, allowIsolated, dumpsysFlags);
    if (attached) {
        __android_log_print(status == android::OK ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
                            kLogTag, "[extension] media.audio_flinger registration with extension "
                                     "returned %d", status);
    }
    return status;
}

constexpr std::array<AddServiceFn, kMaxManagerSlots> kAddServiceCallbacks = {
    onAddService<0>, onAddService<1>, onAddService<2>, onAddService<3>,
};

void bindServiceManagerInstance(const android::sp<Manager>& manager) noexcept {
    if (!gBridgeActive.load(std::memory_order_acquire) ||
        gExtensionAttached.load(std::memory_order_acquire)) {
        return;
    }
    if (!manager) {
        static std::atomic_flag logged{};
        if (!logged.test_and_set(std::memory_order_relaxed)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[manager] defaultServiceManager returned null; cannot bind addService");
        }
        return;
    }
    const void* vtable = nullptr;
    std::memcpy(&vtable, static_cast<const void*>(manager.get()), sizeof(vtable));
    if (!runtime::mappedInLibrary(vtable, gVtableSlotOffset + sizeof(void*), "libbinder.so", PF_R)) {
        static std::atomic_flag logged{};
        if (!logged.test_and_set(std::memory_order_relaxed)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[manager] vtable in %s is not readable through slot %#zx in libbinder.so",
                                addressImage(vtable), gVtableSlotOffset);
        }
        return;
    }
    void* addServiceEntry = nullptr;
    std::memcpy(&addServiceEntry,
                static_cast<const uint8_t*>(vtable) + gVtableSlotOffset,
                sizeof(addServiceEntry));
    if (!runtime::executableInLibrary(addServiceEntry, "libbinder.so")) {
        static std::atomic_flag logged{};
        if (!logged.test_and_set(std::memory_order_relaxed)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[manager] slot %#zx points into %s, not aligned executable code in libbinder.so",
                                gVtableSlotOffset, addressImage(addServiceEntry));
        }
        return;
    }

    ScopedMutex lock(gBridgeMutex);
    if (!lock) {
        static std::atomic_flag logged{};
        if (!logged.test_and_set(std::memory_order_relaxed)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[manager] cannot lock bridge to install addService hook: %d", lock.error());
        }
        return;
    }
    for (const auto& slot : gAddServiceSlots) {
        if (slot.key == addServiceEntry) return;
    }
    for (size_t i = 0; i < gAddServiceSlots.size(); ++i) {
        auto& slot = gAddServiceSlots[i];
        if (slot.key) continue;
        slot.key = addServiceEntry;
        void* next = nullptr;
        const int rc = gBridgeApi->inlineHook(
            addServiceEntry, reinterpret_cast<void*>(kAddServiceCallbacks[i]), &next);
        if (rc == ZN_SUCCESS && next) {
            slot.next = next;
            __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                "[inline] bound IServiceManager::addService (slot %#zx, hook %zu)",
                                gVtableSlotOffset, i);
        } else {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[inline] addService hook %zu failed (rc=%d, trampoline=%s)",
                                i, rc, next ? "present" : "missing");
            if (rc == ZN_SUCCESS) {
                const int rollback = gBridgeApi->inlineUnhook(addServiceEntry);
                if (rollback == ZN_SUCCESS) {
                    // A callback already in flight can now call the unpatched entry.
                    slot.next = addServiceEntry;
                } else {
                    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                        "[rollback] addService hook %zu unhook failed (rc=%d); "
                                        "no safe original for this callback", i, rollback);
                }
            }
        }
        return;
    }
    static bool capacityLogged = false; // Protected by gBridgeMutex.
    if (!capacityLogged) {
        capacityLogged = true;
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "[inline] all %zu addService hook slots used; skipping a new implementation",
                            gAddServiceSlots.size());
    }
}

template <size_t CallerIndex>
android::sp<Manager> onDefaultServiceManager() noexcept {
    DefaultServiceManagerFn nextFn = gDefaultFallback;
    if (void* slotNext = __atomic_load_n(&gCallerSlots[CallerIndex].next, __ATOMIC_ACQUIRE)) {
        nextFn = reinterpret_cast<DefaultServiceManagerFn>(slotNext);
    }
    auto manager = nextFn();
    bindServiceManagerInstance(manager);
    return manager;
}

template <size_t... Indices>
constexpr auto makeCallerTable(std::index_sequence<Indices...>) noexcept {
    return std::array<DefaultServiceManagerFn, sizeof...(Indices)>{
        onDefaultServiceManager<Indices>...};
}

constexpr auto kCallerCallbacks = makeCallerTable(std::make_index_sequence<kMaxCallerSlots>{});

} // namespace

bool installPlatformBridge(const ZygiskNextAPI* api) noexcept {
    ScopedMutex lock(gBridgeMutex);
    if (!lock) {
        static std::atomic_flag logged{};
        if (!logged.test_and_set(std::memory_order_relaxed)) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[init] cannot lock bridge for installation: %d", lock.error());
        }
        return false;
    }
    if (gBridgeInstallTried) return gBridgeActive.load(std::memory_order_acquire);
    gBridgeInstallTried = true;

    if (!api || !api->pltHook || !api->inlineHook || !api->inlineUnhook) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "[init] Zygisk Next API lacks %s",
                            !api ? "the hook API table" : !api->pltHook ? "pltHook" :
                            !api->inlineHook ? "inlineHook" : "inlineUnhook");
        return false;
    }
    gBridgeApi = api;
    gVtableSlotOffset = resolveAddServiceOffset();
    if (!gVtableSlotOffset || !checkRequiredExports()) return false;

    auto ndkService = capture::createService();
    if (!ndkService.get()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "[extension] capture::createService returned a null NDK Binder");
        return false;
    }
    auto platformExt = gToPlatformBinder(ndkService.get());
    if (!platformExt) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "[extension] AIBinder_toPlatformBinder returned a null platform Binder");
        return false;
    }

    size_t eligibleCount = 0;
    size_t callerSlotsUsed = 0;
    size_t installedCount = 0;
    bool mainRequiresImport = false;
    bool mainInstalled = false;
    bool capacityLogged = false;
    bool partialRollbackFailed = false;

    runtime::forEachSystemImage([&](const RuntimeImage& image, const dl_phdr_info& phdr) noexcept {
        if (image.kind == RuntimeImageKind::SharedLibrary &&
            imagePathMatches(image.path, "libbinder.so")) {
            return true;
        }
        if (!runtime::hasPltImport(phdr, kDefaultManagerSymbol)) return true;

        ++eligibleCount;
        if (image.kind == RuntimeImageKind::MainExecutable) mainRequiresImport = true;
        if (callerSlotsUsed == gCallerSlots.size()) {
            if (!capacityLogged) {
                capacityLogged = true;
                __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                    "[plt] all %zu defaultServiceManager caller slots used; "
                                    "first skipped image: %s", gCallerSlots.size(), image.path);
            }
            return true;
        }

        const size_t idx = callerSlotsUsed;
        void* next = nullptr;
        const int rc = api->pltHook(
            image.base, kDefaultManagerSymbol,
            reinterpret_cast<void*>(kCallerCallbacks[idx]), &next);
        // Even after rollback, no other image should reuse a callback that may be in flight.
        if (rc == ZN_SUCCESS) ++callerSlotsUsed;
        if (rc == ZN_SUCCESS && next) {
            gCallerSlots[idx].key = image.base;
            __atomic_store_n(&gCallerSlots[idx].next, next, __ATOMIC_RELEASE);
            ++installedCount;
            if (image.kind == RuntimeImageKind::MainExecutable) mainInstalled = true;
        } else {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "[plt] defaultServiceManager hook failed in %s (rc=%d, original=%s)",
                                image.path, rc, next ? "present" : "missing");
            if (rc == ZN_SUCCESS) {
                const int rollback = api->pltHook(image.base, kDefaultManagerSymbol,
                                                  reinterpret_cast<void*>(gDefaultFallback), nullptr);
                if (rollback != ZN_SUCCESS) {
                    partialRollbackFailed = true;
                    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                        "[rollback] cannot restore defaultServiceManager in %s (rc=%d); "
                                        "residual callback will use the validated platform export",
                                        image.path, rollback);
                } else {
                    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                        "[rollback] replaced defaultServiceManager hook without an original "
                                        "in %s with the validated platform export", image.path);
                }
            }
        }
        return !partialRollbackFailed;
    });

    const char* failure = partialRollbackFailed ? "partial hook rollback failed" :
                          mainRequiresImport && !mainInstalled ? "main executable import was not hooked" :
                          !eligibleCount ? "no eligible defaultServiceManager PLT imports found" :
                          !installedCount ? "no defaultServiceManager imports were hooked" : nullptr;
    if (failure) {
        size_t restoredCount = 0;
        for (size_t i = callerSlotsUsed; i > 0; --i) {
            const auto& slot = gCallerSlots[i - 1];
            if (!slot.key) continue;
            const int rollback = api->pltHook(slot.key, kDefaultManagerSymbol, slot.next, nullptr);
            if (rollback == ZN_SUCCESS) {
                ++restoredCount;
            } else {
                __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                    "[rollback] cannot restore defaultServiceManager in %s (rc=%d); "
                                    "residual callback will forward to its saved original",
                                    addressImage(slot.key), rollback);
            }
        }
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "[plt] bridge not enabled: %s (imports seen=%zu, installed=%zu, restored=%zu)",
                            failure, eligibleCount, installedCount, restoredCount);
        return false;
    }

    gNdkService = std::move(ndkService);
    gPlatformExtension = std::move(platformExt);
    gBridgeActive.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "[plt] ServiceManager bridge ready across %zu caller images; "
                        "waiting for media.audio_flinger registration", installedCount);
    return true;
}

} // namespace spectrum
