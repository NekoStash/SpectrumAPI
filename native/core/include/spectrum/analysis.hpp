#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>

namespace spectrum {

enum class PcmFormat : std::uint32_t {
    S16 = 1, U8 = 2, S32 = 3, S24In32 = 4, F32 = 5, S24Packed = 6,
};
struct SourceFormat {
    std::uint32_t sampleRate = 48000;
    std::uint32_t channels = 2;
    PcmFormat format = PcmFormat::S16;
};

enum class OutputMode : int { Spectrum = 0, Waveform = 1, Combined = 2 };
enum class WaveformKind : std::uint32_t { None = 0, MinMax64 = 1 };
enum class WaveformStatus : std::uint32_t {
    None = 0, Ready = 1, WarmingUp = 2, Discontinuity = 3,
    Idle = 4, UnsupportedMultisource = 5, UnsupportedPath = 6,
};
inline constexpr std::size_t kWaveformBins = 64;

// Signed, cross-channel extrema: not a coherent stereo mix or replayable PCM.
struct WaveformFrame {
    WaveformKind kind = WaveformKind::None;
    WaveformStatus status = WaveformStatus::None;
    std::uint32_t validBins = 0;
    std::uint32_t sourceSampleRate = 0;
    std::uint64_t sourceTrackToken = 0;
    std::uint64_t sourceEpoch = 0;
    std::uint64_t endSourceSequence = 0;
    std::uint32_t windowFrames = 0;
    std::uint32_t flags = 0;
    std::uint64_t captureEndTimeNs = 0;
    std::uint64_t windowDurationNs = 0;
    std::array<float, kWaveformBins * 2> minMax{};
};
struct FeatureFrame {
    int actualRate = 0;
    int bands = 0;
    std::uint32_t status = 1;
    float rmsDb = -120.0F;
    float peakDb = -120.0F;
    std::array<float, 32> db{};
    std::array<float, 33> edges{};
    WaveformFrame waveform{};
};

// Source-domain energy sums channel-averaged squared samples, before any FIR.
struct SourceStatistics {
    double energy = 0.0;
    double peak = 0.0;
    std::uint64_t frames = 0;
};
struct SpectrumSnapshot {
    int rateHz = 0;
    std::size_t fftSize = 0;
    std::uint64_t endSourceSequence = 0;
    std::uint64_t captureEndTimeNs = 0;
    // Hann/Parseval-corrected single-sided powers, averaged across channels.
    // Borrowed until the next spectrum() for this rate or topology change.
    std::span<const double> powers{};
};
struct PipelineCounters {
    std::uint64_t decodedBlocks = 0;
    std::uint64_t decodedFrames = 0;
    std::uint64_t decimatorOutputFrames = 0;
    std::uint64_t fftExecutions = 0;
    std::uint64_t waveformBuckets = 0;
    std::size_t ratePipelines = 0;
    std::size_t decimationNodes = 0;
};

std::size_t fftSizeForRate(int rateHz) noexcept;

// One instance per captured Track. All calls belong to the existing worker;
// nothing here runs on the AudioFlinger real-time thread.
class SourcePipeline {
public:
    SourcePipeline() noexcept;
    ~SourcePipeline();
    SourcePipeline(SourcePipeline&&) noexcept;
    SourcePipeline& operator=(SourcePipeline&&) noexcept;
    SourcePipeline(const SourcePipeline&) = delete;
    SourcePipeline& operator=(const SourcePipeline&) = delete;

    bool configure(SourceFormat source, std::string& reason);
    // Exact actual rates already selected by the session. Empty means no FFT/FIR.
    // Retains unchanged nodes; changing bands/cadence must not reset source state.
    bool setRates(std::span<const int> ratesHz, std::string& reason);
    bool setWaveformEnabled(bool enabled, std::string& reason);
    // Sequence is the first original source frame in this contiguous block.
    // A sequence gap resets histories and increments epoch; no replay/compensation.
    void ingest(const void* pcm, std::size_t frames, std::uint64_t firstSourceSequence,
                std::uint64_t captureEndTimeNs);
    // Computes at most once per rate and current window end; no allocation.
    bool spectrum(int rateHz, SpectrumSnapshot& out);
    // Aggregate for the most recent ingest() only. Each session binding adds this
    // once, then clears only its own accumulator after publishing. No PCM history.
    SourceStatistics inputStatistics() const noexcept;
    bool waveform(WaveformFrame& out) const noexcept;
    void reset(std::uint64_t nextSourceSequence = 0) noexcept;
    std::uint64_t sequence() const noexcept;
    std::uint64_t epoch() const noexcept;
    std::uint64_t captureEndTimeNs() const noexcept;
    SourceFormat sourceFormat() const noexcept;
    PipelineCounters counters() const noexcept;
private:
    struct State;
    std::unique_ptr<State> state_;
};

// Per-session frequency projection; does not own a decoder, FIR or FFT window.
class BandMapper {
public:
    BandMapper() noexcept;
    ~BandMapper();
    BandMapper(BandMapper&&) noexcept;
    BandMapper& operator=(BandMapper&&) noexcept;
    BandMapper(const BandMapper&) = delete;
    BandMapper& operator=(const BandMapper&) = delete;
    bool configure(int rateHz, int bands, float lowHz, float highHz, std::string& reason);
    void project(const SpectrumSnapshot& snapshot, std::array<double, 32>& powers) const noexcept;
    std::span<const float> edges() const noexcept;
    int bandCount() const noexcept;
private:
    struct State;
    std::unique_ptr<State> state_;
};
} // namespace spectrum
