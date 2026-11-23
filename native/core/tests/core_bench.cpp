#include "decimator.hpp"
#include "pcm_decode.hpp"
#include "spectrum/detail/real_fft.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {
using namespace spectrum;
using namespace spectrum::detail;
using Clock = std::chrono::steady_clock;
constexpr std::size_t blockFrames = 512;
constexpr std::array formats{PcmFormat::S16, PcmFormat::U8, PcmFormat::S32,
    PcmFormat::S24In32, PcmFormat::F32, PcmFormat::S24Packed};
constexpr std::array formatNames{"S16", "U8", "S32", "S24In32", "F32", "S24Packed"};

std::vector<double> signal(std::size_t n) {
    std::vector<double> samples(n);
    std::uint32_t state = 0xB25E3A4DU;
    for (auto& sample : samples) {
        state = state * 1664525U + 1013904223U;
        sample = (static_cast<double>(state >> 16) - 32768.0) / 32768.0;
    }
    return samples;
}

std::vector<unsigned char> pcm(PcmFormat format, std::size_t samples) {
    const auto width = pcmSampleBytes(format);
    std::vector<unsigned char> bytes(samples * width);
    const auto values = signal(samples);
    for (std::size_t i = 0; i < samples; ++i) {
        const auto value = values[i];
        std::uint32_t bits = 0;
        switch (format) {
        case PcmFormat::U8: bits = static_cast<std::uint32_t>(value * 128 + 128); break;
        case PcmFormat::S16: bits = static_cast<std::int32_t>(value * 32768); break;
        case PcmFormat::S32: bits = static_cast<std::int32_t>(value * 2147483648.0); break;
        case PcmFormat::S24In32:
        case PcmFormat::S24Packed: bits = static_cast<std::int32_t>(value * 8388608); break;
        case PcmFormat::F32: bits = std::bit_cast<std::uint32_t>(static_cast<float>(value)); break;
        }
        for (std::size_t j = 0; j < width; ++j) {
            bytes[i * width + j] = static_cast<unsigned char>(bits >> (8 * j));
        }
    }
    return bytes;
}

template<class Operation>
void measure(std::string_view name, int iterations, Operation&& operation,
             bool perBlockLatency = false) {
    for (int i = 0; i < 32; ++i) operation(static_cast<unsigned>(i));
    std::array<double, 7> times{};
    double checksum = 0.0;
    for (auto& time : times) {
        const auto begin = Clock::now();
        for (int i = 0; i < iterations; ++i) checksum += operation(static_cast<unsigned>(i));
        time = std::chrono::duration<double, std::nano>(Clock::now() - begin).count() / iterations;
    }
    std::vector<double> blockTimes;
    double blockTotal = 0.0;
    if (perBlockLatency) {
        // Measure individual ingest/cadence calls separately from batched throughput.
        blockTimes.resize(static_cast<std::size_t>(iterations));
        for (std::size_t i = 0; i < blockTimes.size(); ++i) {
            const auto begin = Clock::now();
            const auto result = operation(static_cast<unsigned>(i));
            const auto elapsed = std::chrono::duration<double, std::nano>(Clock::now() - begin).count();
            blockTimes[i] = elapsed;
            blockTotal += elapsed;
            checksum += result;
        }
        std::sort(blockTimes.begin(), blockTimes.end());
    }
    if (!std::isfinite(checksum)) throw std::runtime_error("non-finite benchmark checksum");
    std::sort(times.begin(), times.end());
    std::cout << name << ',' << iterations << ',' << std::fixed << std::setprecision(2)
              << times[3] << ',' << times[0] << ',' << std::setprecision(9) << checksum;
    if (!blockTimes.empty()) {
        const auto percentile = [&](std::size_t p) { return blockTimes[(p * blockTimes.size() + 99) / 100 - 1]; };
        std::cout << ',' << blockTimes.size() << ',' << std::setprecision(2)
                  << blockTotal / blockTimes.size() << ',' << percentile(50) << ',' << percentile(95) << ','
                  << percentile(99) << ',' << blockTimes.back();
        } else {
            std::cout << ",,,,,,";
    }
    std::cout << '\n';
}

void runMicroBenchmarks() {
    std::string reason;
    for (const unsigned channels : {1U, 2U}) {
        const auto suffix = "/ch" + std::to_string(channels);
        std::vector<double> output(blockFrames * channels);
        for (std::size_t f = 0; f < formats.size(); ++f) {
            const auto input = pcm(formats[f], output.size());
            measure(std::string("decode/") + formatNames[f] + suffix, 20000, [&](unsigned i) {
                const auto stats = decodePcm(formats[f], input.data(), output, channels);
                return stats.energy + stats.peak + output[i % output.size()];
            });
        }
        const auto input = signal(output.size());
        constexpr std::array profiles{FirProfile::H2, FirProfile::D2, FirProfile::D3};
        constexpr std::array names{"H2", "D2", "D3"};
        for (std::size_t p = 0; p < profiles.size(); ++p) {
            Decimator filter(profiles[p], channels);
            measure(std::string("fir/") + names[p] + suffix, 2000, [&](unsigned i) {
                const auto block = filter.process(input, output);
                return output[i % (block.frames * channels)];
            });
        }
        for (const std::size_t n : {512, 1024, 2048, 4096, 8192}) {
            RealFft fft;
            if (!fft.configure(n, reason)) throw std::runtime_error(reason);
            const auto ring = signal(n * channels);
            std::vector<double> powers(n / 2 + 1);
            measure("fft/" + std::to_string(n) + suffix, 2000, [&](unsigned i) {
                fft.power(ring, (29 * i) % n, channels, powers);
                return powers[i % powers.size()];
            });
        }
    }
    for (const int rate : {48000, 192000}) {
        const auto n = fftSizeForRate(rate);
        auto input = signal(n / 2 + 1);
        for (auto& value : input) value *= value;
        SpectrumSnapshot snapshot{rate, n, 0, 0, input};
        std::array<double, 32> powers{};
        for (const int bands : {8, 16, 32}) {
            BandMapper mapper;
            if (!mapper.configure(rate, bands, 20.0F, 96000.0F, reason)) throw std::runtime_error(reason);
            measure("band/" + std::to_string(rate) + "/b" + std::to_string(bands), 30000, [&](unsigned i) {
                mapper.project(snapshot, powers);
                return powers[i % static_cast<unsigned>(bands)];
            });
        }
    }
}

void runPipelineBenchmarks(bool float32) {
    std::string reason;
    const auto backend = float32 ? "float32" : "legacy";
    struct Input { int rate; PcmFormat format; std::string_view name; };
    constexpr std::array inputs{
        Input{44100, PcmFormat::S16, "S16"},
        Input{48000, PcmFormat::S24Packed, "S24Packed"},
        Input{96000, PcmFormat::S24Packed, "S24Packed"},
        Input{192000, PcmFormat::S24Packed, "S24Packed"},
        Input{192000, PcmFormat::S32, "S32"},
        Input{192000, PcmFormat::F32, "F32"},
    };
    for (const auto& [rate, format, name] : inputs) {
        const auto input = pcm(format, blockFrames * 2);
        for (const int analysisRate : {22050, 24000, 48000, 96000, 192000}) {
            if (analysisRate == 22050 && rate != 44100) continue;
            if (analysisRate > rate || rate % analysisRate) continue;
            const std::array rates{analysisRate};
            for (const unsigned cadence : {30U, 60U}) {
                SourcePipeline pipeline;
                const SourceFormat source{static_cast<unsigned>(rate), 2, format};
#ifdef SPECTRUM_HARNESS_HAS_DSP_BACKEND
                const bool configured = pipeline.configure(source, reason,
                    float32 ? DspBackend::Float32 : DspBackend::LegacyDouble);
#else
                const bool configured = !float32 && pipeline.configure(source, reason);
#endif
                BandMapper mapper;
                if (!configured || !pipeline.setRates(rates, reason) ||
                    !pipeline.setWaveformEnabled(true, reason) ||
                    !mapper.configure(analysisRate, 32, 20.0F, analysisRate * 0.45F, reason)) {
                    throw std::runtime_error(reason);
                }
                std::uint64_t sequence = 0;
                for (int i = 0; i < 64; ++i) {
                    pipeline.ingest(input.data(), blockFrames, sequence, 0);
                    sequence += blockFrames;
                }
                SpectrumSnapshot snapshot;
                WaveformFrame wave;
                std::array<double, 32> powers{};
                auto publish = sequence * cadence / rate;
                const std::string label = std::string("pipeline/") + backend + '/' +
                    std::to_string(rate) + '/' + std::string(name) + '/' +
                    std::to_string(analysisRate) + '/' + std::to_string(cadence) + "Hz";
                measure(label, 1200, [&](unsigned i) {
                    const auto end = sequence + blockFrames;
                    pipeline.ingest(input.data(), blockFrames, sequence, end * 1000000000ULL / rate);
                    sequence = end;
                    const auto stats = pipeline.inputStatistics();
                    double result = stats.energy + stats.peak;
                    const auto tick = end * cadence / rate;
                    if (tick != publish) {
                        publish = tick;
                        if (!pipeline.spectrum(analysisRate, snapshot) || !pipeline.waveform(wave)) {
                            throw std::runtime_error("analysis not ready");
                        }
                        mapper.project(snapshot, powers);
                        result += powers[i % powers.size()] + wave.minMax[i % wave.minMax.size()];
                    }
                    return result;
                }, true);
            }
        }
    }
}
} // namespace

int main(int argc, char** argv) {
    try {
        const std::string_view backend = argc == 2 ? argv[1] : "--backend=legacy";
        if (argc > 2 || (backend != "--backend=legacy" && backend != "--backend=float32" &&
                         backend != "--backend=both")) {
            throw std::runtime_error("usage: spectrum_core_bench [--backend=legacy|float32|both]");
        }
#ifndef SPECTRUM_HARNESS_HAS_DSP_BACKEND
        if (backend != "--backend=legacy") throw std::runtime_error("Float32 is unavailable in this baseline");
#endif
        std::cout << "case,iterations_per_trial,median_ns_per_op,min_ns_per_op,checksum,"
                     "block_samples,block_mean_ns,block_p50_ns,block_p95_ns,block_p99_ns,block_max_ns\n";
        if (backend != "--backend=float32") {
            runMicroBenchmarks();
            runPipelineBenchmarks(false);
        }
        if (backend != "--backend=legacy") runPipelineBenchmarks(true);
    } catch (const std::exception& error) {
        std::cerr << "Benchmark failed: " << error.what() << '\n';
        return 1;
    }
}
