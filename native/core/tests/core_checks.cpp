#include "decimator.hpp"
#include "pcm_decode.hpp"
#include "spectrum/detail/real_fft.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>
#include <numbers>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

namespace {
using namespace spectrum;
using namespace spectrum::detail;

void check(bool condition, std::string_view context) {
    if (!condition) throw std::runtime_error(std::string(context));
}

void near(double actual, double expected, double absolute, double relative,
          std::string_view context, std::size_t index = 0) {
    if (std::isfinite(actual) && std::abs(actual - expected) <= absolute + relative * std::abs(expected)) return;
    std::cerr << context << " [" << index << "]: expected " << expected << ", got " << actual << '\n';
    throw std::runtime_error(std::string(context));
}

void appendLittleEndian(std::vector<unsigned char>& bytes, std::uint32_t bits, std::size_t width) {
    for (std::size_t i = 0; i < width; ++i) bytes.push_back(static_cast<unsigned char>(bits >> (8 * i)));
}

void checkDecode() {
    struct Case {
        PcmFormat format;
        std::size_t width;
        std::array<std::uint32_t, 9> bits;
        std::array<double, 9> expected;
    };
    constexpr double q8 = 1.0 / 128, q16 = 1.0 / 32768, q24 = 1.0 / 8388608, q32 = 1.0 / 2147483648.0;
    constexpr std::array cases{
        Case{PcmFormat::S24Packed, 3, {0x800000, 0x7fffff, 0xffffff, 0x100, 0x818273, 0x123456, 0, 1, 0x800001},
             {-1, 1-q24, -q24, 256*q24, -8289677*q24, 1193046*q24, 0, q24, -1+q24}},
        Case{PcmFormat::S24In32, 4, {0x80000000, 0x7fffffff, 0xff800000, 0x00800000, 0x12345678, 0xffffffff, 0, 1, 0x80000001},
             {-256, 256-q24, -1, 1, 305419896*q24, -q24, 0, q24, -256+q24}},
        Case{PcmFormat::S32, 4, {0x80000000, 0x7fffffff, 0xffffffff, 256, 0x12345678, 0xc0000000, 0, 1, 0x80000001},
             {-1, 1-q32, -q32, 256*q32, 305419896*q32, -0.5, 0, q32, -1+q32}},
        Case{PcmFormat::S16, 2, {0x8000, 0x7fff, 0xffff, 256, 0x1234, 0xc000, 0, 1, 0x8001},
             {-1, 1-q16, -q16, 256*q16, 4660*q16, -0.5, 0, q16, -1+q16}},
        Case{PcmFormat::U8, 1, {0, 255, 127, 129, 0x42, 64, 128, 192, 1},
             {-1, 1-q8, -q8, q8, -62*q8, -0.5, 0, 0.5, -1+q8}},
        Case{PcmFormat::F32, 4, {0xbf800000, 0x3f800000, 0x7f800000, 0xff800000, 0x7fc12345, 0x80000000, 0x40000000, 1, 0xffc12345},
             {-1, 1, 0, 0, 0, -0.0, 2, 0x1p-149, 0}},
    };
    for (const auto& c : cases) {
        const auto label = "PCM format " + std::to_string(static_cast<unsigned>(c.format));
        check(pcmSampleBytes(c.format) == c.width, label + " width");
        for (const unsigned channels : {1U, 2U}) {
            for (std::size_t frames = 1; frames <= 35; ++frames) {
                const auto samples = frames * channels;
                // Deliberately misalign the input. No readable padding after the last sample is required.
                std::vector<unsigned char> bytes;
                bytes.reserve(1 + samples * c.width);
                bytes.push_back(0x5a);
                std::vector<double> output(samples + 2, -999.0);
                double expectedEnergy = 0.0;
                double expectedPeak = 0.0;
                for (std::size_t i = 0; i < samples; ++i) {
                    const auto v = c.expected[i % c.expected.size()];
                    appendLittleEndian(bytes, c.bits[i % c.bits.size()], c.width);
                    expectedEnergy += v * v;
                    expectedPeak = std::max(expectedPeak, std::abs(v));
                }
                const auto stats = decodePcm(c.format, bytes.data() + 1, std::span(output).subspan(1, samples), channels);
                check(stats.frames == frames && stats.peak == expectedPeak, label + " exact frame count and peak");
                near(stats.energy, expectedEnergy / channels, 0.0, 2e-15, label + " energy");
                check(output.front() == -999.0 && output.back() == -999.0, label + " output bounds");
                for (std::size_t i = 0; i < samples; ++i) {
                    const auto v = c.expected[i % c.expected.size()];
                    near(output[i + 1], v, 0.0, 0.0, label + " exact sample", i);
                    if (v == 0) check(std::signbit(output[i + 1]) == std::signbit(v), label + " signed zero");
                }
            }
        }
    }
}

void checkFir() {
    constexpr std::array profiles{FirProfile::H2, FirProfile::D2, FirProfile::D3};
    constexpr std::array divisors{2U, 2U, 3U};
    constexpr std::array<std::size_t, 7> chunks{1, 2, 3, 17, 512, 5, 63};
    constexpr std::size_t frames = 2049;
    for (std::size_t p = 0; p < profiles.size(); ++p) {
        const auto& plan = firPlan(profiles[p]);
        std::vector<double> coefficients(plan.taps);
        coefficients[plan.taps / 2] = plan.center;
        for (std::size_t i = 0; i < plan.pairCount; ++i) {
            const auto tap = i * plan.pairStride;
            coefficients[tap] = coefficients[plan.taps - 1 - tap] = plan.pairs[i];
        }
        for (const unsigned channels : {1U, 2U}) {
            std::vector<double> input(frames * channels), output(input.size() + 1, -999.0);
            for (std::size_t i = 0; i < input.size(); ++i) {
                input[i] = (static_cast<double>((37 * i + 5) % 113) - 56) / 64;
            }
            Decimator filter(profiles[p], channels);
            const auto divisor = divisors[p];
            check(filter.divisor() == divisor, "FIR divisor");
            for (int replay = 0; replay < 2; ++replay) {
                filter.reset();
                for (std::size_t start = 0, part = 0; start < frames; ++part) {
                    const auto count = std::min(chunks[part % chunks.size()], frames - start);
                    const auto result = filter.process(std::span(input).subspan(start * channels, count * channels), output);
                    check(result.frames == (start + count) / divisor - start / divisor, "FIR frame count");
                    const auto first = divisor - 1 - start % divisor;
                    if (result.frames) check(result.firstInputFrame == first, "FIR first output source index");
                    for (std::size_t frame = 0; frame < result.frames; ++frame) {
                        const auto end = start + first + frame * divisor;
                        for (unsigned channel = 0; channel < channels; ++channel) {
                            double expected = 0;
                            for (std::size_t lag = 0; lag < coefficients.size() && lag <= end; ++lag) {
                                expected += coefficients[lag] * input[(end - lag) * channels + channel];
                            }
                            near(output[frame * channels + channel], expected, 2e-14, 2e-13, "FIR direct convolution", end);
                        }
                    }
                    start += count;
                }
            }
        }
    }
}

void checkFft() {
    constexpr auto tau = 2 * std::numbers::pi;
    std::string reason;
    for (const std::size_t n : {512, 1024, 2048, 4096, 8192}) {
        RealFft fft;
        check(fft.configure(n, reason), "FFT configure");
        for (const unsigned channels : {1U, 2U}) {
            const auto oldest = channels == 1 ? 0 : n - 3;
            std::vector<double> ring(n * channels), window(n), actual(n / 2 + 1, -999.0);
            double windowEnergy = 0;
            for (std::size_t i = 0; i < n; ++i) {
                window[i] = 0.5 - 0.5 * std::cos(tau * i / n);
                windowEnergy += window[i] * window[i];
                for (unsigned c = 0; c < channels; ++c) {
                    const auto dc = c == 0 ? 0.1 : -0.25;
                    ring[c * n + (oldest + i) % n] = dc + 0.125 * (i % 2 == 0 ? 1 : -1) +
                        0.3 * std::sin(tau * (31.625 + 27 * c) * i / n) +
                        (static_cast<double>((i * 37) % 251) - 125) / 2048;
                }
            }
            fft.power(ring, oldest, channels, actual);
            const auto scale = n * windowEnergy * channels;
            for (std::size_t k = 0; k <= n / 2; ++k) {
                if (n != 512 && !(k <= 2 || k == 31 || k == 58 || k == n / 4 || k >= n / 2 - 2)) continue;
                double expected = 0;
                for (unsigned c = 0; c < channels; ++c) {
                    double re = 0, im = 0;
                    for (std::size_t i = 0; i < n; ++i) {
                        const auto sample = window[i] * ring[c * n + (oldest + i) % n];
                        const auto angle = tau * ((i * k) % n) / n;
                        re += sample * std::cos(angle);
                        im -= sample * std::sin(angle);
                    }
                    expected += re * re + im * im;
                }
                expected *= (k == 0 || k == n / 2 ? 1 : 2) / scale;
                near(actual[k], expected, 5e-15, 5e-12, "FFT direct DFT power", k);
            }
            double actualTotal = 0, expectedTotal = 0;
            for (const auto bin : actual) actualTotal += bin;
            for (unsigned c = 0; c < channels; ++c) {
                for (std::size_t i = 0; i < n; ++i) {
                    const auto sample = window[i] * ring[c * n + (oldest + i) % n];
                    expectedTotal += sample * sample;
                }
            }
            near(actualTotal, expectedTotal / (windowEnergy * channels), 2e-15, 2e-12, "FFT Parseval power");
        }
    }
}

void checkBand() {
    constexpr int rate = 48000;
    const auto n = fftSizeForRate(rate);
    const auto binHz = static_cast<double>(rate) / n;
    std::vector<double> bins(n / 2 + 1);
    for (std::size_t i = 0; i < bins.size(); ++i) bins[i] = (1 + i % 7) * 0.125;
    const SpectrumSnapshot snapshot{rate, n, 0, 0, bins};
    for (const int bands : {8, 16, 32}) {
        BandMapper mapper;
        std::string reason;
        check(mapper.configure(rate, bands, 1.0F, 30000.0F, reason), "band configure");
        const auto edges = mapper.edges();
        check(mapper.bandCount() == bands && edges.size() == static_cast<std::size_t>(bands + 1) &&
              edges.front() == 1 && edges.back() == rate / 2, "band edges and Nyquist clipping");
        std::array<double, 32> actual{};
        mapper.project(snapshot, actual);
        for (int b = 0; b < bands; ++b) {
            double expected = 0;
            for (std::size_t k = 0; k < bins.size(); ++k) {
                const auto left = std::max(0.0, (static_cast<double>(k) - 0.5) * binHz);
                const auto right = std::min(rate / 2.0, (static_cast<double>(k) + 0.5) * binHz);
                const auto overlap = std::clamp(static_cast<double>(edges[b + 1]), left, right) -
                                     std::clamp(static_cast<double>(edges[b]), left, right);
                expected += bins[k] * overlap / (right - left);
            }
            near(actual[b], expected, 5e-15, 2e-13, "band fractional overlap", b);
        }
        check(std::all_of(actual.begin() + bands, actual.end(), [](double p) { return p == 0; }), "unused band slots");
        auto shortSnapshot = snapshot;
        shortSnapshot.powers = shortSnapshot.powers.first(n / 2);
        mapper.project(shortSnapshot, actual);
        check(std::all_of(actual.begin(), actual.end(), [](double p) { return p == 0; }), "truncated spectrum rejected");
    }
}

void checkPipeline() {
    static_assert(std::is_same_v<decltype(SpectrumSnapshot::powers), std::span<const double>>);
    constexpr int rate = 48000, down = 16000;
    constexpr std::size_t frames = 4096;
    constexpr std::uint64_t start = 101, timestamp = 200000000;
    SourcePipeline pipeline;
    std::string reason;
#ifdef SPECTRUM_HARNESS_HAS_DSP_BACKEND
    check(pipeline.configure({rate, 2, PcmFormat::S16}, reason, DspBackend::LegacyDouble), "pipeline configure");
#else
    check(pipeline.configure({rate, 2, PcmFormat::S16}, reason), "pipeline configure");
#endif
    check(pipeline.setRates(std::array{rate, down}, reason), "pipeline rates");
    check(pipeline.setWaveformEnabled(true, reason), "pipeline waveform enable");
    std::vector<unsigned char> data;
    data.reserve(frames * 4);
    for (std::size_t frame = 0; frame < frames; ++frame) {
        appendLittleEndian(data, 0xc000, 2);
        appendLittleEndian(data, 0x2000, 2);
    }
    SpectrumSnapshot full, repeat, decimated;
    WaveformFrame wave;
    check(!pipeline.spectrum(rate, full) && full.powers.empty(), "spectrum initially warming up");
    pipeline.ingest(data.data(), frames, start, timestamp);
    const auto stats = pipeline.inputStatistics();
    check(stats.frames == frames && stats.peak == 0.5 && stats.energy == 0.15625 * frames, "pipeline source statistics");
    check(pipeline.spectrum(rate, full), "full-band snapshot ready");
    check(full.rateHz == rate && full.fftSize == fftSizeForRate(rate) &&
          full.powers.size() == full.fftSize / 2 + 1 && full.endSourceSequence == start + frames &&
          full.captureEndTimeNs == timestamp, "snapshot metadata");
    const auto dc = full.powers.front();
    near(dc, 0.15625 * (2.0 / 3.0), 2e-14, 2e-13, "pipeline DC power");
    check(pipeline.spectrum(rate, repeat) && repeat.powers.data() == full.powers.data() &&
          pipeline.counters().fftExecutions == 1, "borrowed cache reused without FFT");
    check(pipeline.spectrum(down, decimated) && decimated.powers.data() != full.powers.data() &&
          decimated.endSourceSequence == start + 4095 &&
          decimated.captureEndTimeNs == timestamp - 1000000000ULL / rate &&
          pipeline.counters().fftExecutions == 2, "independent downsampled snapshot and timestamp");
    check(full.powers.front() == dc, "other rate preserves borrowed powers");
    check(pipeline.waveform(wave) && wave.status == WaveformStatus::Ready &&
          wave.validBins == 64 && wave.windowFrames == 1920, "waveform ready");
    for (std::size_t b = 0; b < wave.validBins; ++b) {
        check(wave.minMax[2 * b] == -0.5F && wave.minMax[2 * b + 1] == 0.25F, "waveform cross-channel extrema");
    }
    const std::array<unsigned char, 4> silence{};
    pipeline.ingest(silence.data(), 1, start + frames, timestamp + 20833);
    check(full.powers.front() == dc && pipeline.counters().fftExecutions == 2, "ingest leaves borrowed powers intact until query");
    check(pipeline.spectrum(rate, repeat) && pipeline.counters().fftExecutions == 3 &&
          repeat.endSourceSequence == start + frames + 1, "new window recomputes");
    // The previous full-rate span is no longer read after querying a new window.
    const auto epoch = pipeline.epoch();
    pipeline.ingest(silence.data(), 1, start + frames + 11, timestamp + 100000);
    check(pipeline.epoch() == epoch + 1 && pipeline.sequence() == start + frames + 12 &&
          !pipeline.spectrum(rate, repeat) && repeat.powers.empty(), "gap clears FFT history and advances epoch");
    check(!pipeline.waveform(wave) && wave.status == WaveformStatus::Discontinuity &&
          wave.sourceEpoch == epoch + 1, "gap clears waveform history");
}

#ifdef SPECTRUM_HARNESS_HAS_DSP_BACKEND
void compareBackends(SourceFormat source, std::span<const int> rates,
                     const std::vector<unsigned char>& data, double expectedPeak) {
    SourcePipeline legacy, candidate;
    std::string reason;
    check(legacy.configure(source, reason, DspBackend::LegacyDouble) &&
          candidate.configure(source, reason, DspBackend::Float32), "explicit backend configure");
    for (auto* pipeline : {&legacy, &candidate}) {
        check(pipeline->setRates(rates, reason) && pipeline->setWaveformEnabled(true, reason), "backend setup");
    }
    const auto frameBytes = pcmSampleBytes(source.format) * source.channels;
    const auto frames = data.size() / frameBytes;
    constexpr std::array<std::size_t, 5> chunks{513, 129, 772, 111, 5};
    double peak = 0.0;
    for (std::size_t start = 0, step = 0; start < frames; ++step) {
        const auto count = std::min(chunks[step % chunks.size()], frames - start);
        const auto timestamp = 500000000 + (start + count) * 1000000000ULL / source.sampleRate;
        const auto* pcm = data.data() + start * frameBytes;
        legacy.ingest(pcm, count, 37 + start, timestamp);
        candidate.ingest(pcm, count, 37 + start, timestamp);
        const auto ref = legacy.inputStatistics();
        const auto got = candidate.inputStatistics();
        check(ref.frames == count && got.frames == count && got.peak == ref.peak, "backend exact source count and peak");
        near(got.energy, ref.energy, 0.0, 5e-14, "backend source energy");
        peak = std::max(peak, got.peak);
        start += count;
    }
    check(peak == expectedPeak, "backend fixture peak");
    for (const auto rate : rates) {
        const auto label = "Float32 " + std::to_string(source.sampleRate) + "->" + std::to_string(rate);
        SpectrumSnapshot ref, got;
        check(legacy.spectrum(rate, ref) && candidate.spectrum(rate, got), label + " spectrum ready");
        check(got.rateHz == ref.rateHz && got.fftSize == ref.fftSize &&
              got.endSourceSequence == ref.endSourceSequence && got.captureEndTimeNs == ref.captureEndTimeNs &&
              got.powers.size() == ref.powers.size(), label + " exact spectrum metadata");
        const auto largest = *std::max_element(ref.powers.begin(), ref.powers.end());
        check(largest > 0 && std::isfinite(largest), label + " finite nonzero reference spectrum");
        // Below -90 dBFS use an absolute power bound, including Float32 underflow.
        // For over-full-scale F32, apply the same noise bound relative to the input peak.
        const auto peakPower = std::max(1.0, peak * peak);
        const auto powerFloor = peakPower * 1e-9;
        const auto noiseBound = peakPower * 2e-12;
        for (std::size_t k = 0; k < ref.powers.size(); ++k) {
            check(got.powers[k] >= 0, label + " nonnegative power");
            const auto absolute = ref.powers[k] < powerFloor ? noiseBound : largest * 2e-10;
            near(got.powers[k], ref.powers[k], absolute, 8e-4, label + " FFT power", k);
        }
        BandMapper mapper;
        check(mapper.configure(rate, 32, 20.0F, rate * 0.5F, reason), label + " band setup");
        std::array<double, 32> referenceBands{}, candidateBands{};
        mapper.project(ref, referenceBands);
        mapper.project(got, candidateBands);
        const auto largestBand = *std::max_element(referenceBands.begin(), referenceBands.end());
        for (std::size_t i = 0; i < referenceBands.size(); ++i) {
            if (referenceBands[i] < powerFloor) {
                near(candidateBands[i], referenceBands[i], noiseBound, 0.0, label + " band absolute power", i);
            } else if (referenceBands[i] >= largestBand * 1e-8) {
                near(10 * std::log10(candidateBands[i] / referenceBands[i]), 0.0, 0.02, 0.0,
                     label + " band error (dB)", i);
            }
        }
    }
    WaveformFrame refWave, gotWave;
    check(legacy.waveform(refWave) && candidate.waveform(gotWave), "backend waveform ready");
    check(gotWave.kind == refWave.kind && gotWave.status == refWave.status &&
          gotWave.validBins == refWave.validBins && gotWave.sourceEpoch == refWave.sourceEpoch &&
          gotWave.windowFrames == refWave.windowFrames && gotWave.endSourceSequence == refWave.endSourceSequence &&
          gotWave.captureEndTimeNs == refWave.captureEndTimeNs && gotWave.minMax == refWave.minMax,
          "backend exact waveform metadata and extrema");
    const auto refCounters = legacy.counters(), gotCounters = candidate.counters();
    check(gotCounters.decodedBlocks == refCounters.decodedBlocks &&
          gotCounters.decodedFrames == refCounters.decodedFrames &&
          gotCounters.decimatorOutputFrames == refCounters.decimatorOutputFrames &&
          gotCounters.fftExecutions == refCounters.fftExecutions && gotCounters.waveformBuckets == refCounters.waveformBuckets &&
          gotCounters.ratePipelines == refCounters.ratePipelines && gotCounters.decimationNodes == refCounters.decimationNodes,
          "backend exact pipeline work counters");
}

void checkFloat32() {
    constexpr std::array rates48k{48000, 16000, 8000};
    constexpr std::array rates192k{192000, 96000, 64000, 48000, 32000, 24000};
    constexpr std::size_t frames = 17309;
    // Negative max, NaN payloads, infinities and a subnormal accompany each finite peak.
    for (const auto magnitude : {0x7f7fffffU, 0x00800000U}) {
        const std::array bits{magnitude, 0x7fc12345U, magnitude | 0x80000000U, 0x7f800000U,
                              0xff800000U, 0x00000001U, 0x3e800000U, 0x80000000U, 0U};
        std::vector<unsigned char> data;
        data.reserve(frames * 4);
        for (std::size_t i = 0; i < frames; ++i) {
            auto value = bits[i % bits.size()];
            if (magnitude == 0x00800000U && value == 0x3e800000U) value = 0;
            appendLittleEndian(data, value, 4);
        }
        const auto expectedPeak = magnitude == 0x7f7fffffU ? std::numeric_limits<float>::max() :
                                                          std::numeric_limits<float>::min();
        compareBackends({48000, 1, PcmFormat::F32}, rates48k, data, expectedPeak);
    }
    std::vector<unsigned char> packed;
    packed.reserve(frames * 6);
    std::uint32_t random = 0xB25E3A4DU;
    constexpr auto tau = 2 * std::numbers::pi;
    for (std::size_t frame = 0; frame < frames; ++frame) {
        for (unsigned channel = 0; channel < 2; ++channel) {
            random = random * 1664525U + 1013904223U;
            const auto noise = (static_cast<double>(random >> 16) - 32768.0) / 32768.0;
            const auto signal = 0.31 * std::sin(tau * (271 + 347 * channel) * frame / 192000) +
                                0.23 * std::sin(tau * (7284 + 15103 * channel) * frame / 192000) + 0.17 * noise;
            const auto sample = static_cast<std::int32_t>(signal * 8388608);
            appendLittleEndian(packed, frame == 13 ? (channel == 0 ? 0x800000U : 0x7fffffU) :
                               static_cast<std::uint32_t>(sample), 3);
        }
    }
    compareBackends({192000, 2, PcmFormat::S24Packed}, rates192k, packed, 1.0);
}
#endif
} // namespace

int main() {
    try {
        checkDecode();
        checkFir();
        checkFft();
        checkBand();
        checkPipeline();
#ifdef SPECTRUM_HARNESS_HAS_DSP_BACKEND
        checkFloat32();
        std::cout << "Float32 versus LegacyDouble checks passed.\n";
#endif
        std::cout << "Core checks passed: PCM, FIR, FFT, band mapping, pipeline.\n";
    } catch (const std::exception& error) {
        std::cerr << "Core checks failed: " << error.what() << '\n';
        return 1;
    }
}
