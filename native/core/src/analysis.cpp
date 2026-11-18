#include "spectrum/analysis.hpp"
#include "spectrum/detail/real_fft.hpp"

#include "decimator.hpp"
#include "pcm_decode.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <exception>
#include <limits>
#include <utility>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace spectrum {
namespace {

constexpr std::array<unsigned, 6> kFactors{1, 2, 3, 4, 6, 8};
constexpr std::size_t kDecodeFrames = 512;
constexpr std::uint64_t kNsPerSecond = 1'000'000'000;
constexpr std::uint32_t kWaveBucketsPerSecond = 1600; // 64 bins / 40 ms.

bool supportedSourceRate(std::uint32_t rate) noexcept {
    switch (rate) {
    case 44100: case 48000: case 88200: case 96000: case 176400: case 192000:
        return true;
    default:
        return false;
    }
}

std::uint64_t durationNs(std::uint64_t frames, std::uint32_t rate) noexcept {
    return (frames / rate) * kNsPerSecond + (frames % rate) * kNsPerSecond / rate;
}

struct WaveformHistory {
    struct Bucket {
        double minimum = 0.0;
        double maximum = 0.0;
        std::uint64_t first = 0;
        std::uint64_t end = 0;
    };
    std::array<Bucket, kWaveformBins> buckets{};
    std::size_t write = 0;
    std::size_t count = 0;
    std::uint32_t phase = 0;
    std::uint32_t currentFrames = 0;
    Bucket current{};
    WaveformStatus pendingStatus = WaveformStatus::WarmingUp;

    void reset(WaveformStatus status) noexcept {
        write = 0;
        count = 0;
        phase = 0;
        currentFrames = 0;
        current = {};
        pendingStatus = status;
    }

    std::uint64_t append(std::span<const double> samples, std::uint32_t channels,
                         std::uint32_t rate, std::uint64_t firstSequence) noexcept {
        std::uint64_t completed = 0;
        const auto frames = samples.size() / channels;
        std::size_t consumed = 0;
        while (consumed < frames) {
            // The same integer phase as per-frame accumulation, but branch only
            // at a bucket boundary and reduce consecutive cross-channel samples.
            const auto needed = (rate - phase + kWaveBucketsPerSecond - 1) /
                                kWaveBucketsPerSecond;
            const auto take = std::min(frames - consumed, static_cast<std::size_t>(needed));
            const auto values = samples.subspan(consumed * channels, take * channels);
            if (currentFrames == 0) {
                current.minimum = values[0];
                current.maximum = values[0];
                current.first = firstSequence + consumed;
            }
            auto minimum = current.minimum;
            auto maximum = current.maximum;
            std::size_t i = 0;
#if defined(__aarch64__)
            auto low = vdupq_n_f64(minimum);
            auto high = vdupq_n_f64(maximum);
            for (; i + 2 <= values.size(); i += 2) {
                const auto value = vld1q_f64(values.data() + i);
                low = vminq_f64(low, value);
                high = vmaxq_f64(high, value);
            }
            minimum = vminvq_f64(low);
            maximum = vmaxvq_f64(high);
#endif
            for (; i < values.size(); ++i) {
                minimum = std::min(minimum, values[i]);
                maximum = std::max(maximum, values[i]);
            }
            current.minimum = minimum;
            current.maximum = maximum;
            currentFrames += static_cast<std::uint32_t>(take);
            phase += static_cast<std::uint32_t>(take) * kWaveBucketsPerSecond;
            consumed += take;
            if (phase < rate) continue;
            phase -= rate;
            current.end = firstSequence + consumed;
            buckets[write] = current;
            write = (write + 1) % kWaveformBins;
            count = std::min(count + 1, kWaveformBins);
            currentFrames = 0;
            ++completed;
        }
        return completed;
    }
};

struct RatePipeline {
    int rateHz = 0;
    std::uint32_t channels = 0;
    std::size_t size = 0;
    std::size_t write = 0;
    std::size_t ready = 0;
    std::uint64_t endSourceSequence = 0;
    std::uint64_t transformedEnd = 0;
    bool hasPower = false;
    std::unique_ptr<detail::Decimator> terminal;
    detail::RealFft fft;
    std::vector<double> ring;
    std::vector<double> powers;

    bool prepare(int rate, unsigned factor, std::uint32_t channelCount, std::string& reason) {
        rateHz = rate;
        channels = channelCount;
        size = fftSizeForRate(rate);
        ring.assign(size * channels, 0.0);
        powers.assign(size / 2 + 1, 0.0);
        if (!fft.configure(size, reason)) return false;
        if (factor != 1) {
            terminal = std::make_unique<detail::Decimator>(
                factor == 3 || factor == 6 ? detail::FirProfile::D3 : detail::FirProfile::D2,
                channels);
        }
        return true;
    }

    void reset() noexcept {
        std::fill(ring.begin(), ring.end(), 0.0);
        std::fill(powers.begin(), powers.end(), 0.0);
        if (terminal) terminal->reset();
        write = 0;
        ready = 0;
        endSourceSequence = 0;
        transformedEnd = 0;
        hasPower = false;
    }

    void accept(std::span<const double> values, std::uint64_t firstSourceEnd,
                std::uint64_t sourceStride) noexcept {
        const auto frames = values.size() / channels;
        if (frames == 0) return;
        std::size_t consumed = 0;
        while (consumed < frames) {
            const auto count = std::min(frames - consumed, size - write);
            const auto* input = values.data() + consumed * channels;
            auto* left = ring.data() + write;
            if (channels == 1) {
                std::copy_n(input, count, left);
            } else {
                auto* right = ring.data() + size + write;
                std::size_t i = 0;
#if defined(__aarch64__)
                for (; i + 2 <= count; i += 2) {
                    const auto pair = vld2q_f64(input + i * 2);
                    vst1q_f64(left + i, pair.val[0]);
                    vst1q_f64(right + i, pair.val[1]);
                }
#endif
                for (; i < count; ++i) {
                    left[i] = input[i * 2];
                    right[i] = input[i * 2 + 1];
                }
            }
            consumed += count;
            write = (write + count) & (size - 1);
        }
        ready = std::min(ready + frames, size);
        endSourceSequence = firstSourceEnd + (frames - 1) * sourceStride;
    }
};

struct SampleBlock {
    std::span<const double> values;
    std::uint64_t firstSourceEnd;
    std::uint64_t sourceStride;
};

struct DecimationWorkspace {
    std::array<double, kDecodeFrames> half;
    std::array<double, kDecodeFrames / 2> quarter;
    std::array<double, kDecodeFrames> terminal;
};

} // namespace

std::size_t fftSizeForRate(int rateHz) noexcept {
    std::size_t result = 512;
    while (result < 8192 && static_cast<double>(result) < static_cast<double>(rateHz) * 0.04) {
        result *= 2;
    }
    return result;
}

struct SourcePipeline::State {
    SourceFormat source;
    std::size_t frameBytes;
    SourceStatistics inputStats;
    std::unique_ptr<WaveformHistory> waveform;
    // Fixed paths only: source -> H2 -> H2, with D2/D3 terminal branches.
    std::unique_ptr<detail::Decimator> half1;
    std::unique_ptr<detail::Decimator> half2;
    std::unique_ptr<DecimationWorkspace> workspace;
    std::array<std::unique_ptr<RatePipeline>, kFactors.size()> rates;
    std::array<double, kDecodeFrames * 2> decoded{};
    std::uint64_t sequence = 0;
    std::uint64_t epoch = 1;
    std::uint64_t captureEndNs = 0;
    bool hasSequence = false;
    PipelineCounters counters;

    explicit State(SourceFormat input)
        : source(input), frameBytes(detail::pcmSampleBytes(input.format) * input.channels) {}

    void reset(std::uint64_t next, WaveformStatus status) noexcept {
        inputStats = {};
        if (waveform) waveform->reset(status);
        if (half1) half1->reset();
        if (half2) half2->reset();
        for (auto& rate : rates) if (rate) rate->reset();
        sequence = next;
        captureEndNs = 0;
        hasSequence = true;
    }

    SampleBlock decimate(detail::Decimator& node, SampleBlock input,
                         std::span<double> output) noexcept {
        const auto block = node.process(input.values, output);
        counters.decimatorOutputFrames += block.frames;
        return {output.first(block.frames * source.channels),
                input.firstSourceEnd + block.firstInputFrame * input.sourceStride,
                input.sourceStride * node.divisor()};
    }

    void terminal(std::size_t index, SampleBlock input) noexcept {
        if (!rates[index]) return;
        auto& rate = *rates[index];
        const auto output = decimate(*rate.terminal, input, workspace->terminal);
        rate.accept(output.values, output.firstSourceEnd, output.sourceStride);
    }

    void accept(std::span<const double> values, std::uint64_t firstSourceEnd) noexcept {
        const SampleBlock input{values, firstSourceEnd, 1};
        if (rates[0]) rates[0]->accept(values, firstSourceEnd, 1); // SOURCE: no FIR.
        terminal(1, input); // D2.
        terminal(2, input); // D3.
        if (!half1) return;
        const auto halfOutput = decimate(*half1, input, workspace->half);
        if (halfOutput.values.empty()) return;
        terminal(3, halfOutput); // H2 -> D2.
        terminal(4, halfOutput); // H2 -> D3.
        if (!half2) return;
        const auto quarterOutput = decimate(*half2, halfOutput, workspace->quarter);
        if (!quarterOutput.values.empty()) terminal(5, quarterOutput); // H2 -> H2 -> D2.
    }

    std::uint64_t timeForSequence(std::uint64_t end) const noexcept {
        const auto offset = durationNs(sequence - end, source.sampleRate);
        return captureEndNs > offset ? captureEndNs - offset : 0;
    }
};

SourcePipeline::SourcePipeline() noexcept = default;
SourcePipeline::~SourcePipeline() = default;
SourcePipeline::SourcePipeline(SourcePipeline&&) noexcept = default;
SourcePipeline& SourcePipeline::operator=(SourcePipeline&&) noexcept = default;

bool SourcePipeline::configure(SourceFormat source, std::string& reason) {
    if (!supportedSourceRate(source.sampleRate)) {
        reason = "source sampleRate must be 44100, 48000, 88200, 96000, 176400 or 192000";
        return false;
    }
    if (source.channels != 1 && source.channels != 2) {
        reason = "only mono and stereo PCM are supported";
        return false;
    }
    if (detail::pcmSampleBytes(source.format) == 0) {
        reason = "unsupported PCM format";
        return false;
    }
    try {
        auto replacement = std::make_unique<State>(source);
        replacement->epoch = state_ ? state_->epoch + 1 : 1;
        state_.swap(replacement);
        reason.clear();
        return true;
    } catch (const std::exception&) {
        reason = "cannot allocate source pipeline";
        return false;
    }
}

bool SourcePipeline::setRates(std::span<const int> requested, std::string& reason) {
    if (!state_) {
        reason = "source is not configured";
        return false;
    }
    auto& state = *state_;
    std::array<bool, kFactors.size()> wanted{};
    for (const auto rate : requested) {
        if (rate <= 0 || state.source.sampleRate % static_cast<unsigned>(rate) != 0) {
            reason = "actual rate must divide the source sample rate";
            return false;
        }
        const auto factor = state.source.sampleRate / static_cast<unsigned>(rate);
        const auto found = std::find(kFactors.begin(), kFactors.end(), factor);
        if (found == kFactors.end()) {
            reason = "source/actual rate ratio must be in {1,2,3,4,6,8}";
            return false;
        }
        wanted[static_cast<std::size_t>(found - kFactors.begin())] = true;
    }
    const auto needsHalf1 = wanted[3] || wanted[4] || wanted[5];
    const auto needsHalf2 = wanted[5];
    const auto needsWorkspace = wanted[1] || wanted[2] || needsHalf1;
    try {
        // Prepare new nodes before changing the existing, live topology.
        std::array<std::unique_ptr<RatePipeline>, kFactors.size()> additions;
        std::unique_ptr<detail::Decimator> half1;
        std::unique_ptr<detail::Decimator> half2;
        std::unique_ptr<DecimationWorkspace> workspace;
        if (needsWorkspace && !state.workspace) workspace = std::make_unique<DecimationWorkspace>();
        if (needsHalf1 && !state.half1) {
            half1 = std::make_unique<detail::Decimator>(detail::FirProfile::H2,
                                                       state.source.channels);
        }
        if (needsHalf2 && !state.half2) {
            half2 = std::make_unique<detail::Decimator>(detail::FirProfile::H2,
                                                       state.source.channels);
        }
        for (std::size_t i = 0; i < wanted.size(); ++i) {
            if (!wanted[i] || state.rates[i]) continue;
            additions[i] = std::make_unique<RatePipeline>();
            if (!additions[i]->prepare(static_cast<int>(state.source.sampleRate / kFactors[i]),
                                       kFactors[i], state.source.channels, reason)) return false;
        }
        for (std::size_t i = 0; i < wanted.size(); ++i) {
            if (!wanted[i]) state.rates[i].reset();
            else if (additions[i]) state.rates[i] = std::move(additions[i]);
        }
        if (!needsHalf1) state.half1.reset();
        else if (half1) state.half1 = std::move(half1);
        if (!needsHalf2) state.half2.reset();
        else if (half2) state.half2 = std::move(half2);
        if (!needsWorkspace) state.workspace.reset();
        else if (workspace) state.workspace = std::move(workspace);
        state.counters.ratePipelines = 0;
        state.counters.decimationNodes = static_cast<std::size_t>(needsHalf1) +
                                          static_cast<std::size_t>(needsHalf2);
        for (std::size_t i = 0; i < wanted.size(); ++i) {
            if (!wanted[i]) continue;
            ++state.counters.ratePipelines;
            if (i != 0) ++state.counters.decimationNodes;
        }
        reason.clear();
        return true;
    } catch (const std::exception&) {
        reason = "cannot allocate or prepare rate pipelines";
        return false;
    }
}

bool SourcePipeline::setWaveformEnabled(bool enabled, std::string& reason) {
    if (!state_) {
        reason = "source is not configured";
        return false;
    }
    try {
        if (enabled && !state_->waveform) state_->waveform = std::make_unique<WaveformHistory>();
        else if (!enabled) state_->waveform.reset();
        reason.clear();
        return true;
    } catch (const std::exception&) {
        reason = "cannot allocate waveform history";
        return false;
    }
}

void SourcePipeline::ingest(const void* pcm, std::size_t frames,
                            std::uint64_t firstSourceSequence, std::uint64_t captureEndTimeNs) {
    if (!state_) return;
    auto& state = *state_;
    state.inputStats = {};
    if (pcm == nullptr || frames == 0) return;
    if (frames > static_cast<std::size_t>(std::numeric_limits<std::ptrdiff_t>::max()) /
                     state.frameBytes ||
        frames > std::numeric_limits<std::uint64_t>::max() - firstSourceSequence) return;
    if (!state.hasSequence) state.reset(firstSourceSequence, WaveformStatus::WarmingUp);
    else if (state.sequence != firstSourceSequence) {
        state.reset(firstSourceSequence, WaveformStatus::Discontinuity);
        ++state.epoch;
    }
    ++state.counters.decodedBlocks;
    state.counters.decodedFrames += frames;
    const auto* input = static_cast<const unsigned char*>(pcm);
    std::size_t consumed = 0;
    while (consumed < frames) {
        const auto count = std::min(frames - consumed, kDecodeFrames);
        const auto samples = std::span<double>{state.decoded}.first(count * state.source.channels);
        const auto statistics = detail::decodePcm(state.source.format, input, samples,
                                                  state.source.channels);
        state.inputStats.energy += statistics.energy;
        state.inputStats.peak = std::max(state.inputStats.peak, statistics.peak);
        state.inputStats.frames += statistics.frames;
        if (state.waveform) {
            state.counters.waveformBuckets += state.waveform->append(
                samples, state.source.channels, state.source.sampleRate, state.sequence);
        }
        state.accept(samples, state.sequence + 1);
        state.sequence += count;
        consumed += count;
        input += count * state.frameBytes;
    }
    state.captureEndNs = captureEndTimeNs;
}

bool SourcePipeline::spectrum(int rateHz, SpectrumSnapshot& out) {
    out = {};
    if (!state_) return false;
    auto& state = *state_;
    for (auto& entry : state.rates) {
        if (!entry || entry->rateHz != rateHz) continue;
        auto& rate = *entry;
        if (rate.ready < rate.size) return false;
        if (!rate.hasPower || rate.transformedEnd != rate.endSourceSequence) {
            rate.fft.power(rate.ring, rate.write, rate.channels, rate.powers);
            rate.transformedEnd = rate.endSourceSequence;
            rate.hasPower = true;
            ++state.counters.fftExecutions;
        }
        out.rateHz = rate.rateHz;
        out.fftSize = rate.size;
        out.endSourceSequence = rate.endSourceSequence;
        out.captureEndTimeNs = state.timeForSequence(rate.endSourceSequence);
        out.powers = rate.powers;
        return true;
    }
    return false;
}

SourceStatistics SourcePipeline::inputStatistics() const noexcept {
    return state_ ? state_->inputStats : SourceStatistics{};
}

bool SourcePipeline::waveform(WaveformFrame& out) const noexcept {
    out = {};
    if (!state_ || !state_->waveform) return false;
    const auto& state = *state_;
    const auto& waveform = *state.waveform;
    out.kind = WaveformKind::MinMax64;
    out.sourceSampleRate = state.source.sampleRate;
    out.sourceEpoch = state.epoch;
    if (waveform.count < kWaveformBins) {
        out.status = waveform.pendingStatus;
        return false;
    }
    out.status = WaveformStatus::Ready;
    out.validBins = static_cast<std::uint32_t>(kWaveformBins);
    const auto first = waveform.buckets[waveform.write].first;
    const auto last = (waveform.write + kWaveformBins - 1) % kWaveformBins;
    out.endSourceSequence = waveform.buckets[last].end;
    out.windowFrames = static_cast<std::uint32_t>(out.endSourceSequence - first);
    out.windowDurationNs = durationNs(out.windowFrames, state.source.sampleRate);
    out.captureEndTimeNs = state.timeForSequence(out.endSourceSequence);
    for (std::size_t i = 0; i < kWaveformBins; ++i) {
        const auto& bucket = waveform.buckets[(waveform.write + i) % kWaveformBins];
        out.minMax[i * 2] = static_cast<float>(bucket.minimum);
        out.minMax[i * 2 + 1] = static_cast<float>(bucket.maximum);
    }
    return true;
}

void SourcePipeline::reset(std::uint64_t nextSourceSequence) noexcept {
    if (!state_) return;
    state_->reset(nextSourceSequence, WaveformStatus::Discontinuity);
    ++state_->epoch;
}

std::uint64_t SourcePipeline::sequence() const noexcept {
    return state_ ? state_->sequence : 0;
}

std::uint64_t SourcePipeline::epoch() const noexcept {
    return state_ ? state_->epoch : 0;
}

std::uint64_t SourcePipeline::captureEndTimeNs() const noexcept {
    return state_ ? state_->captureEndNs : 0;
}

SourceFormat SourcePipeline::sourceFormat() const noexcept {
    return state_ ? state_->source : SourceFormat{};
}

PipelineCounters SourcePipeline::counters() const noexcept {
    return state_ ? state_->counters : PipelineCounters{};
}

} // namespace spectrum
