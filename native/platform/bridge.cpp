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

constexpr char kDefaultManagerSymbol[] = "_ZN7android21defaultServiceManagerEv";
constexpr char kToPlatformBinderSymbol[] = "_Z25AIBinder_toPlatformBinderP8AIBinder";
constexpr char16_t kServiceName[] = u"media.audio_flinger";
constexpr size_t kServiceNameLength = (sizeof(kServiceName) / sizeof(char16_t)) - 1;
constexpr size_t kMaxManagerSlots = 4;
constexpr size_t kMaxCallerSlots = 32;

pthread_mutex_t gBridgeMutex = PTHREAD_MUTEX_INITIALIZER;
const ZygiskNextAPI* gBridgeApi = nullptr;
std::atomic<bool> gBridgeActive{false};
std::atomic<bool> gExtensionPublished{false};
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
        : mutex_(m), locked_(pthread_mutex_lock(&m) == 0) {}
    ~ScopedMutex() {
        if (locked_) pthread_mutex_unlock(&mutex_);
    }
    explicit operator bool() const noexcept { return locked_; }

private:
    pthread_mutex_t& mutex_;
    bool locked_;
};

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
        offset = rep.fn - 1;
    } else if (rep.adj == 1) {
        offset = rep.fn;
    }
    return offset == 0x30 ? static_cast<size_t>(offset) : 0;
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
    for (const auto& spec : kSpecs) {
        void* addr = dlsym(RTLD_DEFAULT, spec.symbol);
        if (!runtime::executableInLibrary(addr, spec.library)) {
            __android_log_print(ANDROID_LOG_WARN, "SpectrumPlatform",
                                "Missing platform symbol %s in %s", spec.symbol, spec.library);
            return false;
        }
        if (std::strcmp(spec.symbol, kDefaultManagerSymbol) == 0) {
            gDefaultFallback = reinterpret_cast<DefaultServiceManagerFn>(addr);
        } else if (std::strcmp(spec.symbol, kToPlatformBinderSymbol) == 0) {
            gToPlatformBinder = reinterpret_cast<ToPlatformBinderFn>(addr);
        }
    }
    return gDefaultFallback != nullptr && gToPlatformBinder != nullptr;
}

void maybeAttachAudioExtension(const android::String16& name,
                               const android::sp<android::IBinder>& service) noexcept {
    if (!gBridgeActive.load(std::memory_order_acquire) ||
        gExtensionPublished.load(std::memory_order_acquire)) {
        return;
    }
    if (name.size() != kServiceNameLength ||
        std::memcmp(name.c_str(), kServiceName, kServiceNameLength * sizeof(char16_t)) != 0) {
        return;
    }

    ScopedMutex lock(gBridgeMutex);
    if (!lock || gExtensionTried) return;
    gExtensionTried = true;

    try {
        if (!service || !gPlatformExtension) return;
        android::BBinder* local = service->localBinder();
        if (!local || local->wasParceled() || local->getExtension()) {
            __android_log_print(ANDROID_LOG_WARN, "SpectrumPlatform",
                                "AudioFlinger binder not eligible for pre-publication extension");
            return;
        }
        local->setExtension(gPlatformExtension);
        if (local->getExtension().get() == gPlatformExtension.get()) {
            gExtensionPublished.store(true, std::memory_order_release);
            __android_log_print(ANDROID_LOG_INFO, "SpectrumPlatform",
                                "Attached Spectrum Binder extension to media.audio_flinger");
        }
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, "SpectrumPlatform",
                            "Exception while attaching Spectrum Binder extension");
    }
}

template <size_t SlotIndex>
android::status_t onAddService(void* self, const android::String16& name,
                               const android::sp<android::IBinder>& service,
                               bool allowIsolated, int dumpsysFlags) noexcept {
    AddServiceFn nextFn = nullptr;
    {
        ScopedMutex lock(gBridgeMutex);
        nextFn = reinterpret_cast<AddServiceFn>(gAddServiceSlots[SlotIndex].next);
    }
    maybeAttachAudioExtension(name, service);
    return nextFn(self, name, service, allowIsolated, dumpsysFlags);
}

constexpr std::array<AddServiceFn, kMaxManagerSlots> kAddServiceCallbacks = {
    onAddService<0>, onAddService<1>, onAddService<2>, onAddService<3>,
};

void bindServiceManagerInstance(const android::sp<Manager>& manager) noexcept {
    if (!manager || !gBridgeActive.load(std::memory_order_acquire) ||
        gExtensionPublished.load(std::memory_order_acquire)) {
        return;
    }
    const void* vtable = nullptr;
    std::memcpy(&vtable, static_cast<const void*>(manager.get()), sizeof(vtable));
    if (!runtime::mappedInLibrary(vtable, gVtableSlotOffset + sizeof(void*), "libbinder.so", PF_R)) {
        return;
    }
    void* addServiceEntry = nullptr;
    std::memcpy(&addServiceEntry,
                static_cast<const uint8_t*>(vtable) + gVtableSlotOffset,
                sizeof(addServiceEntry));
    if (!runtime::executableInLibrary(addServiceEntry, "libbinder.so")) {
        return;
    }

    ScopedMutex lock(gBridgeMutex);
    if (!lock) return;
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
        } else {
            if (rc == ZN_SUCCESS) (void)gBridgeApi->inlineUnhook(addServiceEntry);
            slot.next = addServiceEntry;
        }
        return;
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
    if (!lock) return false;
    if (gBridgeInstallTried) return gBridgeActive.load(std::memory_order_acquire);
    gBridgeInstallTried = true;

    if (!api || !api->pltHook || !api->inlineHook || !api->inlineUnhook) return false;
    gBridgeApi = api;
    gVtableSlotOffset = resolveAddServiceOffset();
    if (!gVtableSlotOffset || !checkRequiredExports()) return false;

    auto ndkService = capture::createService();
    if (!ndkService.get()) return false;
    auto platformExt = gToPlatformBinder(ndkService.get());
    if (!platformExt) return false;

    size_t installedCount = 0;
    bool mainRequiresImport = false;
    bool mainInstalled = false;

    runtime::forEachSystemImage([&](const RuntimeImage& image, const dl_phdr_info& phdr) noexcept {
        if (image.kind == RuntimeImageKind::SharedLibrary &&
            imagePathMatches(image.path, "libbinder.so")) {
            return true;
        }
        if (!runtime::hasPltImport(phdr, kDefaultManagerSymbol)) return true;

        if (image.kind == RuntimeImageKind::MainExecutable) mainRequiresImport = true;
        if (installedCount >= gCallerSlots.size()) return true;

        const size_t idx = installedCount;
        void* next = nullptr;
        const int rc = api->pltHook(
            image.base, kDefaultManagerSymbol,
            reinterpret_cast<void*>(kCallerCallbacks[idx]), &next);
        if (rc == ZN_SUCCESS && next) {
            gCallerSlots[idx].key = image.base;
            __atomic_store_n(&gCallerSlots[idx].next, next, __ATOMIC_RELEASE);
            ++installedCount;
            if (image.kind == RuntimeImageKind::MainExecutable) mainInstalled = true;
        } else if (rc == ZN_SUCCESS) {
            (void)api->pltHook(image.base, kDefaultManagerSymbol,
                               reinterpret_cast<void*>(gDefaultFallback), nullptr);
        }
        return true;
    });

    if (installedCount == 0 || (mainRequiresImport && !mainInstalled)) {
        for (size_t i = installedCount; i > 0; --i) {
            const auto& slot = gCallerSlots[i - 1];
            if (slot.key && slot.next) {
                (void)api->pltHook(slot.key, kDefaultManagerSymbol, slot.next, nullptr);
            }
        }
        __android_log_print(ANDROID_LOG_WARN, "SpectrumPlatform",
                            "Failed to install ServiceManager interception (installed=%zu)",
                            installedCount);
        return false;
    }

    gNdkService = std::move(ndkService);
    gPlatformExtension = std::move(platformExt);
    gBridgeActive.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, "SpectrumPlatform",
                        "ServiceManager bridge ready across %zu caller images", installedCount);
    return true;
}

} // namespace spectrum
