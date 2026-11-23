#include "capture.hpp"
#include "stream_layout.hpp"
#include <spectrum/analysis.hpp>
#include <aidl/hk/uwu/spectrumapi/ipc/BnSpectrumService.h>
#include <aidl/hk/uwu/spectrumapi/ipc/BnSpectrumSession.h>
#include <aidl/hk/uwu/spectrumapi/ipc/ISpectrumStateListener.h>
#include <android/binder_ibinder.h>
#include <android/sharedmem.h>
#if defined(SPECTRUM_CAPTURE_PROFILING)
#include <android/log.h>
#include <cinttypes>
#endif
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <poll.h>
#include <unistd.h>
#include <time.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <fcntl.h>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace spectrum::capture {
namespace {
namespace ipc = ::aidl::hk::uwu::spectrumapi::ipc;
namespace layout = ::spectrum::stream;
using Request = ipc::SpectrumRequest;
using Status = ndk::ScopedAStatus;
constexpr size_t kTrackSlots = 128, kMaxRings = 8, kMaxSessions = 64;
constexpr uint64_t kSecond = 1000000000ULL, kIdleNs = 300000000ULL;
constexpr int kOk = 0, kIdle = 1, kSilent = 2, kPartial = 3, kUnsupportedPath = 4;
constexpr int kUnsupportedBuild = 5, kDisconnected = 6, kDropped = 7;
static_assert(std::endian::native == std::endian::little);
static_assert(sizeof(uintptr_t) == sizeof(uint64_t), "64-bit Android is required");
static_assert(std::atomic<uint64_t>::is_always_lock_free);
static_assert(std::atomic<uintptr_t>::is_always_lock_free);
static_assert(std::atomic<bool>::is_always_lock_free);
static_assert(__atomic_always_lock_free(8, nullptr));
static_assert(__atomic_always_lock_free(4, nullptr));

uint64_t nowNs() noexcept {
    timespec value{};
    clock_gettime(CLOCK_MONOTONIC, &value);
    return uint64_t(value.tv_sec) * kSecond + uint64_t(value.tv_nsec);
}
Status invalid(const char* text) { return Status::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, text); }
Status failed(const char* text) { return Status::fromExceptionCodeWithMessage(EX_ILLEGAL_STATE, text); }
bool hasSpectrum(const Request& r) noexcept { return r.outputMode != int(OutputMode::Waveform); }
bool hasWaveform(const Request& r) noexcept { return r.outputMode != int(OutputMode::Spectrum); }
size_t sampleBytes(uint32_t format) noexcept {
    switch (format) {
    case 1: return 2;
    case 2: return 1;
    case 3: case 4: case 5: return 4;
    case 6: return 3;
    default: return 0;
    }
}
bool supportedInput(uint32_t rate, uint32_t format, uint32_t channels) noexcept {
    if (!sampleBytes(format) || (channels != 1 && channels != 2)) return false;
    switch (rate) {
    case 44100: case 48000: case 88200: case 96000: case 176400: case 192000: return true;
    default: return false;
    }
}
int selectedRate(uint32_t source, const Request& request) noexcept {
    if (request.analysisRatePolicy == 1) return int(source);
    const uint32_t limit = request.analysisRatePolicy == 0 ? 24000U : uint32_t(request.analysisRateHz);
    for (unsigned divisor : {1U, 2U, 3U, 4U, 6U, 8U}) {
        if (source % divisor) continue;
        const uint32_t actual = source / divisor;
        if ((request.analysisRatePolicy == 2 && actual == limit) ||
            (request.analysisRatePolicy != 2 && actual <= limit)) return int(actual);
    }
    return 0;
}
float powerDb(double power) noexcept { return float(10.0 * std::log10(std::max(power, 1.0e-12))); }
uint64_t earlierByFrames(uint64_t end, uint64_t frames, uint32_t rate) noexcept {
    const uint64_t duration = frames * kSecond / rate;
    return end > duration ? end - duration : 0;
}
FeatureFrame emptyFrame(WaveformStatus waveStatus = WaveformStatus::None) noexcept {
    FeatureFrame frame;
    frame.db.fill(-120.0F);
    frame.waveform.status = waveStatus;
    return frame;
}

struct PcmBlock {
    uint64_t firstSourceSequence = 0, captureEndNs = 0;
    size_t frames = 0;
};
// The byte allocation remains a power of two. Metadata is a fixed SPSC array,
// one entry per copied input block; borrowed fragments retain the original source sequence.
struct RawRing {
    static constexpr size_t kBlocks = 256;
    std::unique_ptr<std::byte[]> data;
    std::array<PcmBlock, kBlocks> blocks{};
    size_t allocation = 0, frameBytes = 0, capacityFrames = 0, blockOffset = 0;
    uint32_t sourceRate = 0;
    alignas(64) std::atomic<uint64_t> head{0}, blockHead{0};
    alignas(64) std::atomic<uint64_t> tail{0}, blockTail{0};
#if defined(SPECTRUM_CAPTURE_PROFILING)
    struct Profile {
        // Frame ranges: 1–32, 33–64, ..., 1025–2048, then >2048.
        std::array<std::atomic<uint64_t>, 8> blocks{};
        std::atomic<uint64_t> copies{0}, bytes{0}, highHits{0}, highEntries{0}, probes{0};
        std::atomic<uint64_t> peakFrames{0};
        bool wasHigh = false;
        // Only the worker updates or reads these counters.
        uint64_t bandProjects = 0, bandProjectNs = 0;
    };
    alignas(64) Profile profile;
#endif
    bool prepare(uint32_t rate, size_t bytes) {
        const uint64_t wanted = (uint64_t(rate) * bytes + 9) / 10;
        allocation = 65536;
        while (allocation < wanted && allocation < 262144) allocation *= 2;
        data.reset(new (std::nothrow) std::byte[allocation]);
        frameBytes = bytes; sourceRate = rate; capacityFrames = allocation / bytes;
        return data != nullptr && capacityFrames != 0;
    }
    size_t push(const void* source, size_t frames, uint64_t firstSequence, uint64_t captureEnd) noexcept {
#if defined(SPECTRUM_CAPTURE_PROFILING)
        const size_t bucket = std::min<size_t>(7, std::bit_width((frames - 1) >> 5));
        profile.blocks[bucket].fetch_add(1, std::memory_order_relaxed);
#endif
        const uint64_t entry = blockHead.load(std::memory_order_relaxed);
        if (entry - blockTail.load(std::memory_order_acquire) >= kBlocks) return 0;
        const uint64_t written = head.load(std::memory_order_relaxed);
        const uint64_t read = tail.load(std::memory_order_acquire);
        const size_t count = std::min(frames, capacityFrames - size_t(written - read));
        if (!count) return 0;
        const size_t offset = size_t(written % capacityFrames);
        const size_t first = std::min(count, capacityFrames - offset);
        std::memcpy(data.get() + offset * frameBytes, source, first * frameBytes);
        if (first != count) std::memcpy(data.get(), static_cast<const std::byte*>(source) + first * frameBytes,
                                       (count - first) * frameBytes);
#if defined(SPECTRUM_CAPTURE_PROFILING)
        profile.copies.fetch_add(1 + (first != count), std::memory_order_relaxed);
        profile.bytes.fetch_add(count * frameBytes, std::memory_order_relaxed);
        const auto occupancy = written + count - read;
        if (occupancy > profile.peakFrames.load(std::memory_order_relaxed))
            profile.peakFrames.store(occupancy, std::memory_order_relaxed);
#endif
        blocks[size_t(entry % kBlocks)] = {firstSequence, earlierByFrames(captureEnd, frames - count, sourceRate), count};
        head.store(written + count, std::memory_order_release);
        blockHead.store(entry + 1, std::memory_order_release);
        return count;
    }
    bool peek(size_t maxFrames, PcmBlock& out, const void*& view) const noexcept {
        const uint64_t entry = blockTail.load(std::memory_order_relaxed);
        if (entry == blockHead.load(std::memory_order_acquire)) return false;
        const auto& block = blocks[size_t(entry % kBlocks)];
        const uint64_t read = tail.load(std::memory_order_relaxed);
        const size_t offset = size_t(read % capacityFrames);
        const size_t count = std::min({block.frames - blockOffset, maxFrames, capacityFrames - offset});
        if (!count) return false;
        view = data.get() + offset * frameBytes;
        out.firstSourceSequence = block.firstSourceSequence + blockOffset;
        out.frames = count;
        out.captureEndNs = earlierByFrames(block.captureEndNs, block.frames - blockOffset - count, sourceRate);
        return true;
    }
    // Only the worker consumes a fragment returned by peek. Until this release,
    // the producer cannot reuse its PCM bytes or its block descriptor.
    void consume(size_t frames) noexcept {
        const uint64_t entry = blockTail.load(std::memory_order_relaxed);
        const auto& block = blocks[size_t(entry % kBlocks)];
        const uint64_t read = tail.load(std::memory_order_relaxed);
        blockOffset += frames;
        tail.store(read + frames, std::memory_order_release);
        if (blockOffset == block.frames) {
            blockOffset = 0;
            blockTail.store(entry + 1, std::memory_order_release);
        }
    }
    bool highWater() const noexcept {
        return head.load(std::memory_order_relaxed) - tail.load(std::memory_order_acquire) >= capacityFrames / 2 ||
            blockHead.load(std::memory_order_relaxed) - blockTail.load(std::memory_order_acquire) >= kBlocks / 2;
    }
    bool pending() const noexcept {
        return blockHead.load(std::memory_order_acquire) != blockTail.load(std::memory_order_relaxed);
    }
};

struct Binding;
struct Track {
    std::atomic<uintptr_t> object{0};
    std::atomic<uint64_t> epoch{1}, nextSourceSequence{0}, dropped{0};
    std::atomic<bool> enabled{false}, active{false}, producing{false};
    RawRing* realtimeRing = nullptr;
    std::unique_ptr<RawRing> ring;
    SourcePipeline pipeline;
    std::vector<Binding*> consumers; // Worker-only index, rebuilt whenever session bindings change.
    uint64_t token = 0, lastCaptureNs = 0;
    uint32_t uid = 0, rate = 0, format = 0, channels = 0;
    int session = 0, usage = 0;
    bool live = false, needsConfigure = true, capturing = false, idle = true, resetPending = false;
    std::string error;
    void disableAndLockProducer() noexcept {
        enabled.store(false, std::memory_order_seq_cst);
        while (producing.exchange(true, std::memory_order_acquire)) std::this_thread::yield();
    }
    void unlockProducer() noexcept { producing.store(false, std::memory_order_release); }
    void releaseRing() noexcept {
        disableAndLockProducer();
        epoch.fetch_add(1, std::memory_order_relaxed);
        realtimeRing = nullptr; ring.reset();
        unlockProducer();
    }
};

// Mutable shared fields, including float bits and waveform metadata, are lock-free atomics.
struct Region {
    int fd = -1;
    std::byte* mapping = nullptr;
    uint64_t sequence = 0, generation = 1;
    int bands = 16, rate = 0, hz = 30, outputMode = 0;
    std::array<float, 33> edges{};
    ~Region() {
        if (mapping) munmap(mapping, layout::kMappedBytes);
        if (fd >= 0) ::close(fd);
    }
    void store32(size_t offset, uint32_t value, int order = __ATOMIC_RELAXED) noexcept {
        __atomic_store_n(reinterpret_cast<uint32_t*>(mapping + offset), value, order);
    }
    void store64(size_t offset, uint64_t value, int order = __ATOMIC_RELAXED) noexcept {
        __atomic_store_n(reinterpret_cast<uint64_t*>(mapping + offset), value, order);
    }
    bool create(uint64_t gen, int count, int actualRate, int updateHz, int mode,
                const std::array<float, 33>& boundaries, int state) {
        fd = ASharedMemory_create("spectrum.session", layout::kMappedBytes);
        if (fd < 0) return false;
        void* ptr = mmap(nullptr, layout::kMappedBytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (ptr == MAP_FAILED) return false;
        mapping = static_cast<std::byte*>(ptr);
        std::memset(mapping, 0, layout::kMappedBytes);
        if (ASharedMemory_setProt(fd, PROT_READ) != 0) return false;
        generation = gen; bands = count; rate = actualRate; hz = updateHz; outputMode = mode; edges = boundaries;
        store32(0, layout::kMagic); store32(4, layout::kVersion);
        store32(8, uint32_t(layout::kHeaderBytes)); store32(12, uint32_t(layout::kFrameStride));
        store32(16, uint32_t(layout::kSlots)); store32(20, uint32_t(bands)); store64(24, 0);
        store64(32, generation); store32(40, uint32_t(state)); store32(44, uint32_t(hz));
        store32(48, uint32_t(outputMode));
        return true;
    }
    bool descriptor(ipc::StreamDescriptor* out) const {
        const int copy = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (copy < 0) return false;
        out->memory = ndk::ScopedFileDescriptor(copy);
        out->protocolVersion = int32_t(layout::kVersion); out->mappedBytes = int32_t(layout::kMappedBytes);
        out->headerBytes = int32_t(layout::kHeaderBytes); out->frameStride = int32_t(layout::kFrameStride);
        out->slots = int32_t(layout::kSlots); out->bandCount = bands;
        out->actualAnalysisRateHz = rate; out->actualUpdateRateHz = hz;
        out->generation = int64_t(generation); out->outputMode = outputMode;
        out->waveformBins = int32_t(layout::kWaveformBins);
        out->bandEdgesHz.clear();
        if (bands) out->bandEdgesHz.assign(edges.begin(), edges.begin() + bands + 1);
        return true;
    }
    void publish(const FeatureFrame& frame, int state, uint32_t coverage, uint32_t droppedFrames,
                 uint64_t timestamp) noexcept {
        const uint64_t next = ++sequence;
        const size_t base = layout::kHeaderBytes + size_t((next - 1) % layout::kSlots) * layout::kFrameStride;
        store64(base, (next << 1) | 1, __ATOMIC_SEQ_CST);
        __atomic_thread_fence(__ATOMIC_RELEASE);
        store64(base + 8, timestamp); store64(base + 16, uint64_t(-1));
        store64(base + 24, generation); store32(base + 32, uint32_t(state));
        store32(base + 36, coverage); store32(base + 40, droppedFrames); store32(base + 44, uint32_t(rate));
        store32(base + 48, std::bit_cast<uint32_t>(frame.rmsDb));
        store32(base + 52, std::bit_cast<uint32_t>(frame.peakDb));
        store32(base + 56, uint32_t(bands)); store32(base + 60, uint32_t(outputMode));
        for (size_t i = 0; i < 32; ++i) store32(base + 64 + i * 4, std::bit_cast<uint32_t>(frame.db[i]));
        const auto& wave = frame.waveform;
        const size_t meta = base + layout::kWaveformMetadata;
        store32(meta, uint32_t(wave.kind)); store32(meta + 4, uint32_t(wave.status));
        store32(meta + 8, wave.validBins); store32(meta + 12, wave.sourceSampleRate);
        store64(meta + 16, wave.sourceTrackToken); store64(meta + 24, wave.sourceEpoch);
        store64(meta + 32, wave.endSourceSequence); store32(meta + 40, wave.windowFrames);
        store32(meta + 44, wave.flags); store64(meta + 48, wave.captureEndTimeNs);
        store64(meta + 56, wave.windowDurationNs);
        for (size_t i = 0; i < wave.minMax.size(); ++i)
            store32(base + layout::kWaveformSamples + i * sizeof(float), std::bit_cast<uint32_t>(wave.minMax[i]));
        store64(base, next << 1, __ATOMIC_RELEASE);
        store32(40, uint32_t(state), __ATOMIC_RELEASE);
        store64(24, next, __ATOMIC_RELEASE);
    }
    void clear(int state, WaveformStatus waveStatus) noexcept {
        publish(emptyFrame(outputMode == int(OutputMode::Spectrum) ? WaveformStatus::None : waveStatus),
                state, 0, 0, nowNs());
    }
};

// A binding owns its session's projection and source-statistics accumulator.
// Decoding, FIR state, waveform buckets and rate FFT snapshots belong to Track::pipeline.
struct Binding {
    Track* track = nullptr;
    uint64_t token = 0, statisticsEpoch = 0;
    SourceStatistics statistics{};
    uint64_t spectrumEnd = 0, spectrumCaptureNs = 0;
    int rate = 0, bands = 0;
    float low = 0, high = 0;
    BandMapper mapper;
    std::array<double, 32> powers{};
    double rmsPower = 0, peakPower = 0;
    bool spectrumReady = false;
    void observeEpoch(uint64_t current) noexcept {
        if (statisticsEpoch == current) return;
        statisticsEpoch = current; statistics = {};
        rmsPower = 0; peakPower = 0; spectrumReady = false;
        spectrumEnd = 0; spectrumCaptureNs = 0; powers.fill(0);
    }
};
struct SessionState {
    uint64_t id = 0, generation = 0, nextPublishNs = 0, lastInputNs = 0, lastDropTotal = 0;
    int state = kIdle, notifiedState = -1, actualRate = 0;
    uint64_t notifiedGeneration = 0;
    Request request;
    bool active = false, closed = false, rotate = true, topologyPartial = false;
    size_t matched = 0;
    std::string error;
    std::array<float, 33> edges{};
    std::vector<uint64_t> topology;
    std::shared_ptr<ipc::ISpectrumStateListener> listener;
    std::unique_ptr<Region> region;
    std::vector<std::unique_ptr<Binding>> bindings;
};
struct Notification {
    std::shared_ptr<ipc::ISpectrumStateListener> listener;
    int state;
    uint64_t generation;
};
class Engine;
class Session;
class Service;
std::atomic<Engine*> gEngine{nullptr};

class Engine {
public:
    std::mutex mutex;
    std::condition_variable reconciled;
    std::array<Track, kTrackSlots> tracks;
    std::vector<std::shared_ptr<SessionState>> sessions;
    std::atomic<uint32_t> subscriptions{0};
    std::atomic<bool> wakePending{false};
    std::atomic<uint64_t> wakeAtNs{0};
#if defined(SPECTRUM_CAPTURE_PROFILING)
    uint64_t eventWrites = 0, pollWakes = 0, workerPasses = 0, publishedFrames = 0, lastProfileNs = 0;
    void reportCaptureProfile(uint64_t now) const noexcept;
#endif
    int event = -1;
    AIBinder_DeathRecipient* deathRecipient = nullptr;
    uint32_t supportedModes = 0;
    std::string diagnostic = "capture has not reported readiness";
    uint64_t nextToken = 1, nextSession = 1;
    uint64_t change = 1, applied = 0, rejectedTracks = 0;
    Engine() {
        event = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        deathRecipient = AIBinder_DeathRecipient_new([](void* cookie) {
            Engine* self = gEngine.load(std::memory_order_acquire);
            if (!self) return;
            const uint64_t id = uint64_t(reinterpret_cast<uintptr_t>(cookie));
            std::lock_guard lock(self->mutex);
            for (const auto& s : self->sessions) if (s->id == id && !s->closed) {
                if (s->region) s->region->clear(kDisconnected, WaveformStatus::Idle);
                s->closed = true; s->active = false; self->changed(); break;
            }
        });
    }
    ~Engine() {
        if (deathRecipient) AIBinder_DeathRecipient_delete(deathRecipient);
        if (event >= 0) ::close(event);
    }
    bool valid() const noexcept { return event >= 0 && deathRecipient != nullptr; }
    void start() { std::thread([this] { run(); }).detach(); }
    void wake() noexcept {
        if (event < 0 || wakePending.exchange(true, std::memory_order_acq_rel)) return;
        const uint64_t one = 1;
        const auto result = ::write(event, &one, sizeof(one));
        if (result < 0 && errno != EAGAIN) wakePending.store(false, std::memory_order_release);
    }
    void changed() noexcept { ++change; wake(); }
    bool validate(const Request& r, std::string& reason) const {
        const bool spectrum = hasSpectrum(r);
        if (r.outputMode < 0 || r.outputMode > 2 || r.sourceMode < 0 || r.sourceMode > 3 ||
            r.updateRateHz < 1 || r.updateRateHz > 60 || r.usage < 0 || r.usage > 17 ||
            (r.sourceMode == 0 && r.targetUid < 0) || (r.sourceMode == 1 && r.trackToken <= 0) ||
            (r.sourceMode == 2 && r.usage != 1) || r.outputToken < 0 ||
            (spectrum && (r.analysisRatePolicy < 0 || r.analysisRatePolicy > 3 || r.analysisRateHz < 1 ||
             r.analysisRateHz > 192000 || (r.bandCount != 8 && r.bandCount != 16 && r.bandCount != 32) ||
             !std::isfinite(r.minFrequencyHz) || !std::isfinite(r.maxFrequencyHz) || r.minFrequencyHz <= 0 ||
             r.minFrequencyHz >= r.maxFrequencyHz || r.maxFrequencyHz > 96000))) {
            reason = "invalid source, output mode, cadence or spectrum configuration"; return false;
        }
        return true;
    }
    bool matches(const SessionState& s, const Track& t) const {
        if (!t.live) return false;
        const auto& r = s.request;
        if (r.usage && r.usage != t.usage) return false;
        if (r.sourceMode == 0) return t.uid == uint32_t(r.targetUid);
        if (r.sourceMode == 1) return t.token == uint64_t(r.trackToken);
        return r.sourceMode == 2 && t.usage == 1;
    }
    bool waitFor(std::unique_lock<std::mutex>& lock, uint64_t revision) {
        return reconciled.wait_for(lock, std::chrono::seconds(5), [&] { return applied >= revision; });
    }
    void closeSession(const std::shared_ptr<SessionState>& s) noexcept {
        std::lock_guard lock(mutex);
        if (!s->closed) {
            if (s->region) s->region->clear(kDisconnected, WaveformStatus::Idle);
            s->closed = true; s->active = false; changed();
        }
    }
    void registerSource(void* object, uint32_t uid, int session, int usage,
                        uint32_t rate, uint32_t format, uint32_t channels) {
        if (!object) return;
        std::lock_guard lock(mutex);
        Track* selected = nullptr;
        for (auto& t : tracks) if (t.live && t.object.load(std::memory_order_relaxed) == uintptr_t(object)) { selected = &t; break; }
        if (!selected) for (auto& t : tracks) if (!t.live) { selected = &t; break; }
        if (!selected) { ++rejectedTracks; return; }
        auto& t = *selected;
        t.releaseRing(); t.object.store(0, std::memory_order_release);
        t.uid = uid; t.session = session; t.usage = usage; t.rate = rate;
        t.format = format; t.channels = channels; t.token = nextToken++;
        t.live = true; t.needsConfigure = true; t.resetPending = true;
        t.active.store(false, std::memory_order_release);
        t.nextSourceSequence.store(0, std::memory_order_relaxed); t.dropped.store(0, std::memory_order_relaxed);
        t.epoch.fetch_add(1, std::memory_order_relaxed);
        t.object.store(uintptr_t(object), std::memory_order_release); changed();
    }
    void retireSource(void* object) {
        std::lock_guard lock(mutex);
        for (auto& t : tracks) if (t.live && t.object.load(std::memory_order_relaxed) == uintptr_t(object)) {
            t.active.store(false, std::memory_order_release); t.object.store(0, std::memory_order_release);
            t.releaseRing(); t.live = false; t.resetPending = true; changed(); return;
        }
    }
    void activateSource(void* object, bool active) {
        std::lock_guard lock(mutex);
        for (auto& t : tracks) if (t.live && t.object.load(std::memory_order_relaxed) == uintptr_t(object)) {
            if (t.active.exchange(active, std::memory_order_acq_rel) != active) {
                if (!active) { t.enabled.store(false, std::memory_order_seq_cst); t.resetPending = true; }
                changed();
            }
            return;
        }
    }
    void realtime(void* object, const void* data, size_t frames) noexcept {
        if (!data || !frames || frames > size_t(INT32_MAX) || !subscriptions.load(std::memory_order_relaxed)) return;
        for (auto& t : tracks) {
            if (t.object.load(std::memory_order_acquire) != uintptr_t(object)) continue;
            const uint64_t epoch = t.epoch.load(std::memory_order_acquire);
            if (!t.enabled.load(std::memory_order_seq_cst)) return;
            bool expected = false;
            if (!t.producing.compare_exchange_strong(expected, true, std::memory_order_acquire, std::memory_order_relaxed)) {
                // A concurrent producer never waits, but its missing original frames still create a sequence gap.
                t.nextSourceSequence.fetch_add(frames, std::memory_order_relaxed);
                t.dropped.fetch_add(frames, std::memory_order_relaxed); return;
            }
            if (!t.enabled.load(std::memory_order_seq_cst) || t.epoch.load(std::memory_order_acquire) != epoch ||
                t.object.load(std::memory_order_acquire) != uintptr_t(object)) { t.unlockProducer(); return; }
            RawRing* ring = t.realtimeRing;
            if (!ring) { t.unlockProducer(); return; }
            const uint64_t firstSequence = t.nextSourceSequence.fetch_add(frames, std::memory_order_relaxed);
            const uint64_t timestamp = nowNs();
            const size_t copied = ring->push(data, frames, firstSequence, timestamp);
            if (copied != frames) t.dropped.fetch_add(frames - copied, std::memory_order_relaxed);
            const bool highWater = ring->highWater();
#if defined(SPECTRUM_CAPTURE_PROFILING)
            auto& profile = ring->profile;
            profile.probes.fetch_add(size_t(&t - tracks.data()) + 1, std::memory_order_relaxed);
            if (highWater) profile.highHits.fetch_add(1, std::memory_order_relaxed);
            if (highWater && !profile.wasHigh) profile.highEntries.fetch_add(1, std::memory_order_relaxed);
            profile.wasHigh = highWater;
#endif
            t.unlockProducer();
            if (highWater || timestamp >= wakeAtNs.load(std::memory_order_relaxed)) wake();
            return;
        }
    }
private:
    void stopCapture(Track& track);
    void configureTrackBranches(Track& track);
    void reconcile();
    void consumeAndPublish();
    uint64_t deadline(uint64_t now) const;
    void collectNotifications(std::vector<Notification>& out);
    void run();
};

Engine* engine() {
    static std::mutex initMutex;
    Engine* current = gEngine.load(std::memory_order_acquire);
    if (current) return current;
    std::lock_guard lock(initMutex);
    current = gEngine.load(std::memory_order_relaxed);
    if (current) return current;
    std::unique_ptr<Engine> created(new Engine);
    if (!created->valid()) return nullptr;
    current = created.get(); current->start();
    gEngine.store(current, std::memory_order_release);
    return created.release(); // Installed hooks retain this engine until audioserver exits.
}

void Engine::stopCapture(Track& t) {
    if (t.ring) t.releaseRing();
    if (!t.capturing) return;
    std::string reason;
    (void)t.pipeline.setRates({}, reason);
    (void)t.pipeline.setWaveformEnabled(false, reason);
    const uint64_t next = t.nextSourceSequence.load(std::memory_order_relaxed);
    t.pipeline.reset(next);
    t.capturing = false; t.idle = true; t.lastCaptureNs = 0;
}

void Engine::configureTrackBranches(Track& t) {
    std::array<int, kMaxSessions> rates{};
    size_t count = 0;
    bool wave = false, used = false;
    for (const auto& s : sessions) if (s->active && !s->closed) {
        for (const auto& b : s->bindings) if (b->track == &t) {
            used = true; wave = wave || hasWaveform(s->request);
            if (b->rate) rates[count++] = b->rate;
        }
    }
    if (!used) { stopCapture(t); return; }
    std::sort(rates.begin(), rates.begin() + count);
    const auto end = std::unique(rates.begin(), rates.begin() + count);
    if (!t.pipeline.setRates(std::span<const int>(rates.data(), size_t(end - rates.begin())), t.error) ||
        !t.pipeline.setWaveformEnabled(wave, t.error)) stopCapture(t);
}

void Engine::reconcile() {
    for (auto& t : tracks) t.consumers.clear();
    std::array<bool, kTrackSlots> wanted{};
    for (const auto& s : sessions) {
        s->matched = 0; s->topologyPartial = false;
        if (s->closed || !s->active || !supportedModes || s->request.sourceMode == 3 ||
            !(supportedModes & (1U << unsigned(s->request.sourceMode)))) continue;
        for (size_t i = 0; i < tracks.size(); ++i) if (matches(*s, tracks[i]) && tracks[i].active.load(std::memory_order_acquire)) {
            ++s->matched; wanted[i] = true;
        }
    }
    size_t selected = 0;
    for (size_t i = 0; i < tracks.size(); ++i) {
        auto& t = tracks[i];
        if (!wanted[i] || !supportedInput(t.rate, t.format, t.channels)) { stopCapture(t); continue; }
        if (t.resetPending) {
            if (t.ring) t.releaseRing();
            if (t.capturing) {
                const uint64_t next = t.nextSourceSequence.load(std::memory_order_relaxed);
                t.pipeline.reset(next);
            }
            t.idle = true; t.lastCaptureNs = 0; t.resetPending = false;
        }
        if (t.ring) ++selected;
    }
    for (size_t i = 0; i < tracks.size(); ++i) {
        auto& t = tracks[i];
        if (!wanted[i] || t.ring || selected >= kMaxRings || !supportedInput(t.rate, t.format, t.channels)) continue;
        t.error.clear();
        if (t.needsConfigure) {
            if (!t.pipeline.configure({t.rate, t.channels, static_cast<PcmFormat>(t.format)}, t.error)) continue;
            t.needsConfigure = false;
        }
        auto ring = std::make_unique<RawRing>();
        if (!ring->prepare(t.rate, sampleBytes(t.format) * t.channels)) continue;
        t.disableAndLockProducer();
        t.ring = std::move(ring); t.realtimeRing = t.ring.get(); t.epoch.fetch_add(1, std::memory_order_relaxed);
        t.enabled.store(true, std::memory_order_seq_cst); t.unlockProducer();
        t.capturing = true; ++selected;
    }
    for (const auto& s : sessions) {
        auto oldBindings = std::move(s->bindings);
        s->bindings.clear(); s->actualRate = 0; s->edges.fill(0); s->error.clear();
        std::vector<uint64_t> topology;
        int state = (s->state == kOk || s->state == kSilent || s->state == kPartial || s->state == kDropped) ? s->state : kIdle;
        bool capacityLimited = false;
        float high = hasSpectrum(s->request) ? s->request.maxFrequencyHz : 0.0F;
        if (s->closed) continue;
        if (!s->active) state = kIdle;
        else if (!supportedModes) state = kUnsupportedBuild;
        else if (s->request.sourceMode == 3 || !(supportedModes & (1U << unsigned(s->request.sourceMode)))) state = kUnsupportedPath;
        else {
            for (const auto& t : tracks) if (matches(*s, t) && t.active.load(std::memory_order_acquire)) {
                topology.push_back(t.token);
                const int rate = hasSpectrum(s->request) ? selectedRate(t.rate, s->request) : 0;
                if (!supportedInput(t.rate, t.format, t.channels) || (hasSpectrum(s->request) && !rate)) {
                    s->topologyPartial = true; continue;
                }
                if (!t.ring || !t.capturing) { capacityLimited = true; s->topologyPartial = true; continue; }
                if (rate) s->actualRate = s->actualRate ? std::min(s->actualRate, rate) : rate;
            }
            if (s->actualRate) high = std::min(high, float(s->actualRate) * 0.5F);
            if (hasSpectrum(s->request) && high <= s->request.minFrequencyHz) {
                state = kUnsupportedPath; s->actualRate = 0; s->error = "frequency range exceeds source Nyquist";
            } else {
                for (auto& t : tracks) {
                    if (!matches(*s, t) || !t.active.load(std::memory_order_acquire) || !t.ring || !t.capturing) continue;
                    const int rate = hasSpectrum(s->request) ? selectedRate(t.rate, s->request) : 0;
                    if (hasSpectrum(s->request) && !rate) continue;
                    std::unique_ptr<Binding> binding;
                    for (auto& previous : oldBindings) if (previous && previous->token == t.token && previous->rate == rate &&
                        previous->bands == (hasSpectrum(s->request) ? s->request.bandCount : 0) &&
                        (!rate || (previous->low == s->request.minFrequencyHz && previous->high == high))) {
                        binding = std::move(previous); break;
                    }
                    if (!binding) {
                        binding = std::make_unique<Binding>();
                        binding->track = &t; binding->token = t.token; binding->rate = rate;
                        binding->bands = hasSpectrum(s->request) ? s->request.bandCount : 0;
                        binding->low = rate ? s->request.minFrequencyHz : 0; binding->high = high;
                        binding->statisticsEpoch = t.pipeline.epoch();
                        if (rate && !binding->mapper.configure(rate, binding->bands, binding->low, binding->high, s->error)) {
                            s->topologyPartial = true; continue;
                        }
                    }
                    s->bindings.push_back(std::move(binding));
                }
                if (s->bindings.size() < s->matched) s->topologyPartial = true;
                if (s->matched && s->bindings.empty()) {
                    state = capacityLimited ? kPartial : kUnsupportedPath;
                    if (s->error.empty()) s->error = capacityLimited ? "eight-source capture capacity reached" : "source format/rate unsupported";
                }
            }
        }
        if (!s->bindings.empty() && hasSpectrum(s->request)) {
            const auto edges = s->bindings.front()->mapper.edges();
            std::copy(edges.begin(), edges.end(), s->edges.begin());
        } else if (hasSpectrum(s->request) && high > s->request.minFrequencyHz) {
            const double low = s->request.minFrequencyHz;
            for (int i = 0; i <= s->request.bandCount; ++i)
                s->edges[size_t(i)] = float(low * std::pow(double(high) / low, double(i) / s->request.bandCount));
        }
        if (s->topology != topology) { s->topology = std::move(topology); s->rotate = true; }
        s->state = state;
    }
    // Fixed per-source topology: only actual rates and the existence of any waveform branch matter.
    for (auto& t : tracks) if (t.capturing) configureTrackBranches(t);
    bool lostSessionMemory = false;
    for (const auto& s : sessions) {
        if (s->closed) continue;
        const size_t before = s->bindings.size();
        s->bindings.erase(std::remove_if(s->bindings.begin(), s->bindings.end(), [](const auto& b) {
            return !b->track->capturing;
        }), s->bindings.end());
        if (before != s->bindings.size()) {
            s->topologyPartial = true;
            if (s->bindings.empty()) { s->state = kUnsupportedPath; s->actualRate = 0; }
        }
        const int bands = hasSpectrum(s->request) ? s->request.bandCount : 0;
        if (!hasSpectrum(s->request)) s->actualRate = 0;
        const bool descriptorChanged = !s->region || s->region->rate != s->actualRate || s->region->edges != s->edges ||
            s->region->hz != s->request.updateRateHz || s->region->bands != bands || s->region->outputMode != s->request.outputMode;
        if (s->rotate || descriptorChanged) {
            auto memory = std::make_unique<Region>();
            if (memory->create(s->generation + 1, bands, s->actualRate, s->request.updateRateHz,
                               s->request.outputMode, s->edges, s->state)) {
                if (s->region) s->region->clear(kIdle, WaveformStatus::Idle);
                s->region = std::move(memory); ++s->generation; s->rotate = false;
                s->nextPublishNs = 0; s->lastInputNs = 0; s->lastDropTotal = 0;
                for (const auto& b : s->bindings) s->lastDropTotal += b->track->dropped.load(std::memory_order_relaxed);
                WaveformStatus waveState = WaveformStatus::None;
                if (hasWaveform(s->request)) {
                    waveState = !s->active || !s->matched ? WaveformStatus::Idle :
                        (s->matched > 1 ? WaveformStatus::UnsupportedMultisource : WaveformStatus::WarmingUp);
                    if (s->state == kUnsupportedPath || s->state == kUnsupportedBuild)
                        waveState = WaveformStatus::UnsupportedPath;
                }
                s->region->clear(s->state, waveState);
            } else {
                s->error = "read-only shared memory allocation failed"; s->state = kDisconnected;
                s->active = false; s->bindings.clear(); lostSessionMemory = true;
            }
        }
    }
    if (lostSessionMemory) for (auto& t : tracks) if (t.capturing) configureTrackBranches(t);
    uint32_t activeSubscriptions = 0;
    for (const auto& s : sessions) if (!s->closed && s->active && !s->bindings.empty()) {
        ++activeSubscriptions;
        for (const auto& b : s->bindings) b->track->consumers.push_back(b.get());
    }
    subscriptions.store(activeSubscriptions, std::memory_order_release);
    sessions.erase(std::remove_if(sessions.begin(), sessions.end(), [&](const auto& s) {
        if (!s->closed) return false;
        if (s->listener) {
            const auto binder = s->listener->asBinder();
            (void)AIBinder_unlinkToDeath(binder.get(), deathRecipient, reinterpret_cast<void*>(uintptr_t(s->id)));
        }
        s->bindings.clear(); s->region.reset(); s->listener.reset(); return true;
    }), sessions.end());
    applied = change; reconciled.notify_all();
}

void Engine::consumeAndPublish() {
    for (auto& t : tracks) if (t.ring && t.capturing) {
        size_t remaining = t.ring->capacityFrames;
        const size_t maxFrames = 32768 / t.ring->frameBytes;
        while (remaining) {
            PcmBlock block;
            const void* view = nullptr;
            if (!t.ring->peek(std::min(maxFrames, remaining), block, view)) break;
            t.pipeline.ingest(view, block.frames, block.firstSourceSequence, block.captureEndNs);
            const SourceStatistics input = t.pipeline.inputStatistics();
            t.ring->consume(block.frames);
            remaining -= block.frames;
            const uint64_t sourceEpoch = t.pipeline.epoch();
            for (Binding* binding : t.consumers) {
                binding->observeEpoch(sourceEpoch);
                binding->statistics.energy += input.energy;
                binding->statistics.peak = std::max(binding->statistics.peak, input.peak);
                binding->statistics.frames += input.frames;
            }
            t.lastCaptureNs = block.captureEndNs; t.idle = false;
        }
    }
    uint64_t now = nowNs();
    for (auto& t : tracks) if (t.capturing && !t.idle && t.lastCaptureNs && now >= t.lastCaptureNs + kIdleNs &&
                              (!t.ring || !t.ring->pending())) {
        const uint64_t next = t.nextSourceSequence.load(std::memory_order_relaxed);
        t.pipeline.reset(next); t.idle = true;
    }
    for (const auto& s : sessions) {
        if (!s->active || s->closed || !s->region || s->bindings.empty() ||
            s->state == kUnsupportedBuild || s->state == kUnsupportedPath || s->state == kDisconnected) continue;
        uint64_t newestInput = 0, dropTotal = 0;
        for (const auto& b : s->bindings) {
            if (!b->track->idle) newestInput = std::max(newestInput, b->track->lastCaptureNs);
            dropTotal += b->track->dropped.load(std::memory_order_relaxed);
        }
        const bool hadInput = s->lastInputNs != 0;
        s->lastInputNs = newestInput;
        if (!newestInput || now >= newestInput + kIdleNs) {
            if (hadInput || s->state != kIdle) s->region->clear(kIdle, WaveformStatus::Idle);
            s->state = kIdle;
            continue;
        }
        if (now < s->nextPublishNs) continue;
        uint64_t newestSpectrum = 0;
        for (auto& b : s->bindings) {
            auto& t = *b->track;
            b->observeEpoch(t.pipeline.epoch());
            b->spectrumReady = false;
            if (b->rate && !t.idle) {
                SpectrumSnapshot snapshot;
                if (t.pipeline.spectrum(b->rate, snapshot)) {
                    if (b->spectrumEnd != snapshot.endSourceSequence) {
#if defined(SPECTRUM_CAPTURE_PROFILING)
                        const auto projectionStart = nowNs();
#endif
                        b->mapper.project(snapshot, b->powers);
#if defined(SPECTRUM_CAPTURE_PROFILING)
                        if (t.ring) {
                            ++t.ring->profile.bandProjects;
                            t.ring->profile.bandProjectNs += nowNs() - projectionStart;
                        }
#endif
                    }
                    b->spectrumEnd = snapshot.endSourceSequence; b->spectrumCaptureNs = snapshot.captureEndTimeNs;
                    b->spectrumReady = true; newestSpectrum = std::max(newestSpectrum, snapshot.captureEndTimeNs);
                }
            }
        }
        now = nowNs();
        const uint64_t tolerance = std::min(kIdleNs, std::max(uint64_t(50000000), 2 * kSecond / uint64_t(s->request.updateRateHz)));
        FeatureFrame frame = emptyFrame();
        frame.actualRate = s->actualRate; frame.bands = s->region->bands; frame.edges = s->edges;
        std::array<double, 32> powers{};
        double rms = 0, peak = 0;
        size_t contributors = 0;
        for (auto& b : s->bindings) {
            auto& t = *b->track;
            if (t.idle || !t.lastCaptureNs || now >= t.lastCaptureNs + kIdleNs) continue;
            if (hasSpectrum(s->request) && (!b->spectrumReady || newestSpectrum - b->spectrumCaptureNs > tolerance ||
                now >= b->spectrumCaptureNs + kIdleNs)) continue;
            const auto& stats = b->statistics;
            if (stats.frames) { b->rmsPower = stats.energy / double(stats.frames); b->peakPower = stats.peak * stats.peak; }
            rms += b->rmsPower; peak += b->peakPower; ++contributors;
            if (hasSpectrum(s->request)) for (int i = 0; i < frame.bands; ++i) powers[size_t(i)] += b->powers[size_t(i)];
        }
        frame.rmsDb = powerDb(rms); frame.peakDb = powerDb(peak);
        if (hasSpectrum(s->request)) for (int i = 0; i < frame.bands; ++i) frame.db[size_t(i)] = powerDb(powers[size_t(i)]);
        bool waveReady = false;
        if (hasWaveform(s->request)) {
            if (s->matched > 1) frame.waveform.status = WaveformStatus::UnsupportedMultisource;
            else if (s->matched == 1 && s->bindings.size() == 1 && !s->bindings.front()->track->idle) {
                auto& t = *s->bindings.front()->track;
                waveReady = t.pipeline.waveform(frame.waveform);
                frame.waveform.sourceTrackToken = t.token;
            } else frame.waveform.status = s->matched ? WaveformStatus::UnsupportedPath : WaveformStatus::Idle;
        }
        int state = contributors ? (frame.peakDb <= -90.0F ? kSilent : kOk) : kIdle;
        if (!hasSpectrum(s->request) && s->matched > 1) state = kOk;
        if (hasSpectrum(s->request) && (s->topologyPartial || contributors < s->matched)) state = kPartial;
        if (!hasSpectrum(s->request) && s->matched == 1 && !waveReady) state = kIdle;
        const uint64_t delta = dropTotal >= s->lastDropTotal ? dropTotal - s->lastDropTotal : 0;
        const uint32_t lost = uint32_t(std::min(delta, uint64_t(UINT32_MAX)));
        if (lost && state != kPartial) state = kDropped;
        const size_t covered = hasSpectrum(s->request) ? contributors : (waveReady ? size_t(1) : size_t(0));
        const uint32_t coverage = s->matched ? uint32_t(std::min(size_t(1000), covered * 1000 / s->matched)) : 0;
        s->region->publish(frame, state, coverage, lost, now);
#if defined(SPECTRUM_CAPTURE_PROFILING)
        ++publishedFrames;
#endif
        // Publishing consumes only this session's block accumulators, never another subscriber's data.
        for (auto& b : s->bindings) b->statistics = {};
        s->state = state; s->lastDropTotal = dropTotal;
        const uint64_t period = kSecond / uint64_t(s->request.updateRateHz);
        if (!s->nextPublishNs || now - s->nextPublishNs >= period) s->nextPublishNs = now + period;
        else s->nextPublishNs += period;
    }
}

uint64_t Engine::deadline(uint64_t now) const {
    uint64_t next = std::numeric_limits<uint64_t>::max();
    for (const auto& t : tracks) if (t.ring && t.capturing) {
        if (t.ring->pending()) return now;
        if (!t.idle && t.lastCaptureNs) next = std::min(next, t.lastCaptureNs + kIdleNs);
    }
    for (const auto& s : sessions) {
        if (!s->active || s->closed || s->bindings.empty() || !s->lastInputNs ||
            now >= s->lastInputNs + kIdleNs) continue;
        next = std::min(next, s->lastInputNs + kIdleNs);
        next = std::min(next, s->nextPublishNs > now ? s->nextPublishNs : now);
    }
    return next;
}
void Engine::collectNotifications(std::vector<Notification>& out) {
    for (const auto& s : sessions) {
        if (s->closed || !s->listener || (s->notifiedState == s->state && s->notifiedGeneration == s->generation)) continue;
        out.push_back({s->listener, s->state, s->generation});
        s->notifiedState = s->state; s->notifiedGeneration = s->generation;
    }
}
#if defined(SPECTRUM_CAPTURE_PROFILING)
void Engine::reportCaptureProfile(uint64_t now) const noexcept {
    constexpr char tag[] = "SpectrumCapturePerf";
    __android_log_print(ANDROID_LOG_INFO, tag,
        "time_ns=%" PRIu64 " eventfd_writes=%" PRIu64 " poll_wakes=%" PRIu64
        " worker_passes=%" PRIu64 " published_frames=%" PRIu64,
        now, eventWrites, pollWakes, workerPasses, publishedFrames);
    const auto load = [](const auto& counter) { return counter.load(std::memory_order_relaxed); };
    for (const auto& t : tracks) {
        if (!t.ring) continue;
        const auto& p = t.ring->profile;
        __android_log_print(ANDROID_LOG_INFO, tag,
            "track=%" PRIu64 " rate=%u frame_bytes=%zu drop_frames=%" PRIu64
            " copy_calls=%" PRIu64 " copy_bytes=%" PRIu64 " peak_frames=%" PRIu64
            " high_hits=%" PRIu64 " high_entries=%" PRIu64 " lookup_probes=%" PRIu64
            " blocks=[%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 "]",
            t.token, t.rate, t.ring->frameBytes, load(t.dropped), load(p.copies), load(p.bytes),
            load(p.peakFrames), load(p.highHits), load(p.highEntries), load(p.probes),
            load(p.blocks[0]), load(p.blocks[1]), load(p.blocks[2]), load(p.blocks[3]),
            load(p.blocks[4]), load(p.blocks[5]), load(p.blocks[6]), load(p.blocks[7]));
        const auto dsp = t.pipeline.counters();
        __android_log_print(ANDROID_LOG_INFO, tag,
            "track=%" PRIu64 " bindings=%zu decoded_frames=%" PRIu64 " fft_executions=%" PRIu64
            " band_projects=%" PRIu64 " band_project_wall_ns=%" PRIu64,
            t.token, t.consumers.size(), dsp.decodedFrames, dsp.fftExecutions, p.bandProjects, p.bandProjectNs);
    }
}
#endif
void Engine::run() {
    uint64_t next = 0;
    for (;;) {
        if (next) {
            const uint64_t before = nowNs();
            int timeout = -1;
            if (next != std::numeric_limits<uint64_t>::max())
                timeout = next <= before ? 0 : int(std::min((next - before + 999999) / 1000000, uint64_t(INT32_MAX)));
            pollfd item{event, POLLIN, 0};
            int result;
            do { result = poll(&item, 1, timeout); } while (result < 0 && errno == EINTR);
#if defined(SPECTRUM_CAPTURE_PROFILING)
            pollWakes += result > 0;
#endif
        }
        uint64_t ignored;
        while (::read(event, &ignored, sizeof(ignored)) == sizeof(ignored)) {
#if defined(SPECTRUM_CAPTURE_PROFILING)
            eventWrites += ignored;
#endif
        }
        wakePending.store(false, std::memory_order_release);
        std::vector<Notification> notifications;
        {
            std::lock_guard lock(mutex);
            try {
                if (applied != change) reconcile();
                consumeAndPublish(); collectNotifications(notifications); next = deadline(nowNs());
            } catch (const std::bad_alloc&) {
                for (auto& t : tracks) if (t.ring) t.releaseRing();
                subscriptions.store(0, std::memory_order_release);
                for (const auto& s : sessions) {
                    if (s->region) s->region->clear(kDisconnected, WaveformStatus::Idle);
                    s->active = false; s->state = kDisconnected; s->bindings.clear();
                }
                applied = change; reconciled.notify_all(); next = std::numeric_limits<uint64_t>::max();
            }
#if defined(SPECTRUM_CAPTURE_PROFILING)
            ++workerPasses;
            const auto profileNow = nowNs();
            if (profileNow - lastProfileNs >= 5 * kSecond) {
                lastProfileNs = profileNow;
                reportCaptureProfile(profileNow);
            }
#endif
            wakeAtNs.store(next == std::numeric_limits<uint64_t>::max() ? 0 : next, std::memory_order_release);
        }
        for (const auto& notice : notifications) (void)notice.listener->onStateChanged(notice.state, int64_t(notice.generation));
    }
}

class Session final : public ipc::BnSpectrumSession {
public:
    Session(Engine* engine, std::shared_ptr<SessionState> state) : engine_(engine), state_(std::move(state)) {}
    ~Session() override { engine_->closeSession(state_); }
    Status getStream(ipc::StreamDescriptor* out) override {
        std::unique_lock lock(engine_->mutex);
        if (state_->closed) return failed("session is closed");
        if (!engine_->waitFor(lock, engine_->change)) return failed("capture worker timed out");
        if (state_->closed) return failed("session is closed");
        if (!state_->region || !state_->region->descriptor(out)) return failed("stream descriptor unavailable");
        return Status::ok();
    }
    Status update(const Request& request) override {
        std::unique_lock lock(engine_->mutex);
        if (state_->closed) return failed("session is closed");
        std::string reason;
        if (!engine_->validate(request, reason)) return invalid(reason.c_str());
        if (state_->region) state_->region->clear(kIdle, WaveformStatus::Idle);
        state_->region.reset(); state_->request = request; state_->rotate = true;
        state_->bindings.clear(); state_->state = kIdle; engine_->changed();
        if (!engine_->waitFor(lock, engine_->change)) return failed("capture worker timed out");
        return state_->closed ? failed("session is closed") : Status::ok();
    }
    Status setActive(bool active) override {
        std::unique_lock lock(engine_->mutex);
        if (state_->closed) return failed("session is closed");
        if (state_->active == active) return Status::ok();
        state_->active = active;
        if (state_->region) state_->region->clear(kIdle, WaveformStatus::Idle);
        if (active) { state_->region.reset(); state_->rotate = true; }
        else { state_->bindings.clear(); state_->state = kIdle; }
        engine_->changed();
        if (!engine_->waitFor(lock, engine_->change)) return failed("capture worker timed out");
        return state_->closed ? failed("session is closed") : Status::ok();
    }
    Status close() override {
        std::lock_guard lock(engine_->mutex);
        if (!state_->closed) {
            if (state_->region) state_->region->clear(kDisconnected, WaveformStatus::Idle);
            state_->closed = true; state_->active = false; state_->bindings.clear(); state_->region.reset(); engine_->changed();
        }
        return Status::ok();
    }
private:
    Engine* engine_;
    std::shared_ptr<SessionState> state_;
};
class Service final : public ipc::BnSpectrumService {
public:
    explicit Service(Engine* e) : engine_(e) {}
    Status getCapabilities(ipc::Capabilities* out) override {
        std::lock_guard lock(engine_->mutex);
        out->protocolVersion = int32_t(layout::kVersion); out->captureAvailable = engine_->supportedModes != 0;
        out->diagnostic = engine_->diagnostic; out->maxUpdateRateHz = 60; out->maxAnalysisRateHz = 192000;
        out->maxBandCount = 32; out->maxActiveSources = 8; out->supportedModes = int32_t(engine_->supportedModes);
        out->supportedOutputModes = 7; out->waveformBinCount = int32_t(layout::kWaveformBins); return Status::ok();
    }
    Status listSources(std::vector<ipc::SourceInfo>* out) override {
        std::lock_guard lock(engine_->mutex);
        out->clear();
        for (const auto& t : engine_->tracks) {
            if (!t.live) continue;
            ipc::SourceInfo item;
            item.token = int64_t(t.token); item.uid = int32_t(t.uid); item.sessionId = t.session;
            item.usage = t.usage; item.sampleRate = int32_t(t.rate); item.format = int32_t(t.format);
            item.channels = int32_t(t.channels); item.active = t.active.load(std::memory_order_acquire);
            item.mode = 1; item.label = "playback:" + std::to_string(t.token); out->push_back(std::move(item));
        }
        return Status::ok();
    }
    Status openSession(const Request& request, const std::shared_ptr<ipc::ISpectrumStateListener>& listener,
                       std::shared_ptr<ipc::ISpectrumSession>* out) override {
        if (!listener) return invalid("a state listener is required for client death tracking");
        std::unique_lock lock(engine_->mutex);
        std::string reason;
        if (!engine_->validate(request, reason)) return invalid(reason.c_str());
        if (engine_->sessions.size() >= kMaxSessions) return failed("session capacity reached");
        auto state = std::make_shared<SessionState>();
        state->id = engine_->nextSession++; state->request = request; state->listener = listener;
        engine_->sessions.push_back(state);
        const auto listenerBinder = listener->asBinder();
        const auto death = AIBinder_linkToDeath(listenerBinder.get(), engine_->deathRecipient, reinterpret_cast<void*>(uintptr_t(state->id)));
        if (death != STATUS_OK) { state->closed = true; engine_->changed(); return failed("cannot watch state-listener Binder death"); }
        engine_->changed();
        if (!engine_->waitFor(lock, engine_->change) || !state->region) {
            state->closed = true; engine_->changed(); return failed("capture worker could not allocate session stream");
        }
        if (state->closed) return failed("session is closed");
        *out = ndk::SharedRefBase::make<Session>(engine_, state); return Status::ok();
    }
private:
    Engine* engine_;
};
} // namespace

void registerTrack(void* object, uint32_t uid, int session, int usage,
                   uint32_t rate, uint32_t format, uint32_t channels) noexcept {
    try { if (auto* e = engine()) e->registerSource(object, uid, session, usage, rate, format, channels); } catch (...) {}
}
void retireTrack(void* object) noexcept {
    if (auto* e = gEngine.load(std::memory_order_acquire)) e->retireSource(object);
}
void setTrackActive(void* object, bool active) noexcept {
    if (auto* e = gEngine.load(std::memory_order_acquire)) e->activateSource(object, active);
}
void pcm(void* object, const void* data, size_t frames) noexcept {
    if (auto* e = gEngine.load(std::memory_order_acquire)) e->realtime(object, data, frames);
}
void setReady(uint32_t modes, const char* text) noexcept {
    try {
        if (auto* e = engine()) {
            std::lock_guard lock(e->mutex);
            e->supportedModes = modes & 7U; // No output tap exists at this capture boundary.
            e->diagnostic = text ? std::string(text, strnlen(text, 1024)) : ""; e->changed();
        }
    } catch (...) {}
}
ndk::SpAIBinder createService() {
    try { if (auto* e = engine()) return ndk::SharedRefBase::make<Service>(e)->asBinder(); } catch (...) {}
    return {};
}
} // namespace spectrum::capture
