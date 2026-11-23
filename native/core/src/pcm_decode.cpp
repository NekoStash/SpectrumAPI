#include "pcm_decode.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <limits>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace spectrum::detail {
namespace {

std::uint32_t read24(const unsigned char* p) noexcept {
    return static_cast<std::uint32_t>(p[0]) |
           (static_cast<std::uint32_t>(p[1]) << 8) |
           (static_cast<std::uint32_t>(p[2]) << 16);
}

std::uint32_t read32(const unsigned char* p) noexcept {
    return read24(p) | (static_cast<std::uint32_t>(p[3]) << 24);
}

template<PcmFormat Format>
constexpr std::size_t sampleBytes() noexcept {
    if constexpr (Format == PcmFormat::U8) return 1;
    if constexpr (Format == PcmFormat::S16) return 2;
    if constexpr (Format == PcmFormat::S24Packed) return 3;
    return 4;
}

template<PcmFormat Format>
double scalarSample(const unsigned char* p) noexcept {
    if constexpr (Format == PcmFormat::U8) {
        return (static_cast<double>(*p) - 128.0) * (1.0 / 128.0);
    } else if constexpr (Format == PcmFormat::S16) {
        const auto bits = static_cast<std::uint32_t>(p[0]) |
                          (static_cast<std::uint32_t>(p[1]) << 8);
        return static_cast<double>(static_cast<std::int32_t>(bits ^ 0x8000U) - 0x8000) *
               (1.0 / 32768.0);
    } else if constexpr (Format == PcmFormat::S24Packed) {
        return static_cast<double>(static_cast<std::int32_t>(read24(p) ^ 0x800000U) -
                                   0x800000) * (1.0 / 8388608.0);
    } else if constexpr (Format == PcmFormat::F32) {
        static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559);
        const auto value = std::bit_cast<float>(read32(p));
        return std::isfinite(value) ? static_cast<double>(value) : 0.0;
    } else {
        const auto value = std::bit_cast<std::int32_t>(read32(p));
        // Android PCM_8_24 is signed Q8.23; preserve the high-byte headroom.
        constexpr double scale = Format == PcmFormat::S24In32 ?
                                 1.0 / 8388608.0 : 1.0 / 2147483648.0;
        return static_cast<double>(value) * scale;
    }
}

struct DecodeAccumulator {
    std::array<double, 4> scalarSums{};
    double scalarPeak = 0.0;
#if defined(__aarch64__)
    float64x2_t sum0 = vdupq_n_f64(0.0);
    float64x2_t sum1 = vdupq_n_f64(0.0);
    float64x2_t maximum = vdupq_n_f64(0.0);

    void store(float64x2_t lo, float64x2_t hi, double* output) noexcept {
        // Statistics consume the decoded registers, never reread the output buffer.
        vst1q_f64(output, lo);
        vst1q_f64(output + 2, hi);
        add(lo, hi);
    }

    void add(float64x2_t lo, float64x2_t hi) noexcept {
        sum0 = vfmaq_f64(sum0, lo, lo);
        sum1 = vfmaq_f64(sum1, hi, hi);
        maximum = vmaxq_f64(maximum, vmaxq_f64(vabsq_f64(lo), vabsq_f64(hi)));
    }

    void storeInt32(int32x4_t input, double scale, double* output) noexcept {
        const auto lo = vcvtq_f64_s64(vmovl_s32(vget_low_s32(input)));
        const auto hi = vcvtq_f64_s64(vmovl_s32(vget_high_s32(input)));
        store(vmulq_n_f64(lo, scale), vmulq_n_f64(hi, scale), output);
    }
#endif

    void add(double value) noexcept {
        scalarSums[0] += value * value;
        scalarPeak = std::max(scalarPeak, std::abs(value));
    }

    void addFour(double a, double b, double c, double d) noexcept {
        scalarSums[0] += a * a;
        scalarSums[1] += b * b;
        scalarSums[2] += c * c;
        scalarSums[3] += d * d;
        scalarPeak = std::max(scalarPeak, std::max(std::max(std::abs(a), std::abs(b)),
                                                  std::max(std::abs(c), std::abs(d))));
    }

    SourceStatistics result(std::size_t samples, std::uint32_t channels) const noexcept {
        SourceStatistics out;
        out.frames = samples / channels;
        out.energy = (scalarSums[0] + scalarSums[1]) + (scalarSums[2] + scalarSums[3]);
        out.peak = scalarPeak;
#if defined(__aarch64__)
        out.energy += vaddvq_f64(vaddq_f64(sum0, sum1));
        out.peak = std::max(out.peak, vmaxvq_f64(maximum));
#endif
        out.energy /= channels;
        return out;
    }
};

#if defined(__aarch64__)
int32x4x2_t loadPacked24(const unsigned char* input) noexcept {
    const auto planes = vld3_u8(input);
    const auto low = vreinterpretq_s16_u8(vzip1q_u8(
        vcombine_u8(planes.val[0], planes.val[0]), vcombine_u8(planes.val[1], planes.val[1])));
    const auto high = vmovl_s8(vreinterpret_s8_u8(planes.val[2]));
    return {{vreinterpretq_s32_s16(vzip1q_s16(low, high)),
             vreinterpretq_s32_s16(vzip2q_s16(low, high))}};
}
#endif

template<PcmFormat Format>
SourceStatistics decode(const unsigned char* input, std::span<double> output,
                        std::uint32_t channels) noexcept {
    constexpr auto bytes = sampleBytes<Format>();
    DecodeAccumulator accumulator;
    std::size_t i = 0;
#if defined(__aarch64__)
    if constexpr (Format == PcmFormat::U8) {
        for (; i + 8 <= output.size(); i += 8, input += 8) {
            const auto samples = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(input)));
            const auto centered = vsubq_s16(samples, vdupq_n_s16(128));
            accumulator.storeInt32(vmovl_s16(vget_low_s16(centered)), 1.0 / 128.0,
                                    output.data() + i);
            accumulator.storeInt32(vmovl_s16(vget_high_s16(centered)), 1.0 / 128.0,
                                    output.data() + i + 4);
        }
#if defined(SPECTRUM_EXPERIMENTAL_PACKED24_LD3)
    } else if constexpr (Format == PcmFormat::S24Packed) {
        for (; i + 8 <= output.size(); i += 8, input += 24) {
            const auto packed = loadPacked24(input);
            accumulator.storeInt32(packed.val[0], 1.0 / 8388608.0, output.data() + i);
            accumulator.storeInt32(packed.val[1], 1.0 / 8388608.0, output.data() + i + 4);
        }
#endif
    } else {
        for (; i + 4 <= output.size(); i += 4, input += bytes * 4) {
            if constexpr (Format == PcmFormat::F32) {
                const auto bits = vreinterpretq_u32_u8(vld1q_u8(input));
                const auto finite = vcltq_u32(vandq_u32(bits, vdupq_n_u32(0x7fffffffU)),
                                              vdupq_n_u32(0x7f800000U));
                const auto values = vreinterpretq_f32_u32(vandq_u32(bits, finite));
                accumulator.store(vcvt_f64_f32(vget_low_f32(values)),
                                  vcvt_f64_f32(vget_high_f32(values)), output.data() + i);
            } else if constexpr (Format == PcmFormat::S16) {
                const auto samples = vreinterpret_s16_u8(vld1_u8(input));
                accumulator.storeInt32(vmovl_s16(samples), 1.0 / 32768.0, output.data() + i);
            } else if constexpr (Format == PcmFormat::S24Packed) {
                // Gather exactly twelve bytes: packed tails are never overread.
                const std::int32_t samples[4]{
                    static_cast<std::int32_t>(read24(input) ^ 0x800000U) - 0x800000,
                    static_cast<std::int32_t>(read24(input + 3) ^ 0x800000U) - 0x800000,
                    static_cast<std::int32_t>(read24(input + 6) ^ 0x800000U) - 0x800000,
                    static_cast<std::int32_t>(read24(input + 9) ^ 0x800000U) - 0x800000,
                };
                accumulator.storeInt32(vld1q_s32(samples), 1.0 / 8388608.0, output.data() + i);
            } else {
                constexpr double scale = Format == PcmFormat::S24In32 ?
                                         1.0 / 8388608.0 : 1.0 / 2147483648.0;
                accumulator.storeInt32(vreinterpretq_s32_u8(vld1q_u8(input)), scale,
                                        output.data() + i);
            }
        }
    }
#else
    for (; i + 4 <= output.size(); i += 4, input += bytes * 4) {
        const auto a = scalarSample<Format>(input);
        const auto b = scalarSample<Format>(input + bytes);
        const auto c = scalarSample<Format>(input + bytes * 2);
        const auto d = scalarSample<Format>(input + bytes * 3);
        output[i] = a;
        output[i + 1] = b;
        output[i + 2] = c;
        output[i + 3] = d;
        accumulator.addFour(a, b, c, d);
    }
#endif
    for (; i < output.size(); ++i, input += bytes) {
        const auto value = scalarSample<Format>(input);
        output[i] = value;
        accumulator.add(value);
    }
    return accumulator.result(output.size(), channels);
}

template<PcmFormat Format, bool Waveform = false>
SourceStatistics decodeFloat(const unsigned char* input, std::span<float> output,
                             std::uint32_t channels, float* waveform = nullptr) noexcept {
    constexpr auto bytes = sampleBytes<Format>();
    constexpr unsigned fractionBits = Format == PcmFormat::U8 ? 7 :
        Format == PcmFormat::S16 ? 15 : Format == PcmFormat::S32 ? 31 : 23;
    DecodeAccumulator accumulator;
    std::size_t i = 0;
#if defined(__aarch64__)
    const auto storeInt = [&](int32x4_t raw, std::size_t index) {
        if constexpr (Format == PcmFormat::S32 || Format == PcmFormat::S24In32) {
            // Keep exact integer conversion for source statistics before narrowing.
            const auto lo = vcvtq_n_f64_s64(vmovl_s32(vget_low_s32(raw)), fractionBits);
            const auto hi = vcvtq_n_f64_s64(vmovl_s32(vget_high_s32(raw)), fractionBits);
            accumulator.add(lo, hi);
            vst1q_f32(output.data() + index, vcombine_f32(vcvt_f32_f64(lo), vcvt_f32_f64(hi)));
        } else {
            const auto values = vcvtq_n_f32_s32(raw, fractionBits);
            accumulator.add(vcvt_f64_f32(vget_low_f32(values)), vcvt_f64_f32(vget_high_f32(values)));
            vst1q_f32(output.data() + index, values);
        }
    };
    if constexpr (Format == PcmFormat::U8) {
        for (; i + 8 <= output.size(); i += 8, input += 8) {
            const auto samples = vreinterpretq_s16_u16(vmovl_u8(vld1_u8(input)));
            const auto centered = vsubq_s16(samples, vdupq_n_s16(128));
            storeInt(vmovl_s16(vget_low_s16(centered)), i);
            storeInt(vmovl_s16(vget_high_s16(centered)), i + 4);
        }
    } else if constexpr (Format == PcmFormat::S24Packed) {
        // This candidate uses LD3; the LegacyDouble switch stays independent.
        for (; i + 8 <= output.size(); i += 8, input += 24) {
            const auto packed = loadPacked24(input);
            storeInt(packed.val[0], i);
            storeInt(packed.val[1], i + 4);
        }
    } else {
        for (; i + 4 <= output.size(); i += 4, input += bytes * 4) {
            if constexpr (Format == PcmFormat::F32) {
                const auto bits = vreinterpretq_u32_u8(vld1q_u8(input));
                const auto finite = vcltq_u32(vandq_u32(bits, vdupq_n_u32(0x7fffffffU)),
                                              vdupq_n_u32(0x7f800000U));
                const auto values = vreinterpretq_f32_u32(vandq_u32(bits, finite));
                accumulator.add(vcvt_f64_f32(vget_low_f32(values)), vcvt_f64_f32(vget_high_f32(values)));
                if constexpr (Waveform) vst1q_f32(waveform + i, values);
                vst1q_f32(output.data() + i, vmulq_n_f32(values, kFloatDspF32Scale));
            } else if constexpr (Format == PcmFormat::S16) {
                storeInt(vmovl_s16(vreinterpret_s16_u8(vld1_u8(input))), i);
            } else {
                storeInt(vreinterpretq_s32_u8(vld1q_u8(input)), i);
            }
        }
    }
#else
    (void)fractionBits;
    for (; i + 4 <= output.size(); i += 4, input += bytes * 4) {
        const auto a = scalarSample<Format>(input);
        const auto b = scalarSample<Format>(input + bytes);
        const auto c = scalarSample<Format>(input + bytes * 2);
        const auto d = scalarSample<Format>(input + bytes * 3);
        accumulator.addFour(a, b, c, d);
        const double values[]{a, b, c, d};
        for (std::size_t j = 0; j < 4; ++j) {
            const auto value = static_cast<float>(values[j]);
            if constexpr (Waveform) waveform[i + j] = value;
            output[i + j] = Format == PcmFormat::F32 ? value * kFloatDspF32Scale : value;
        }
    }
#endif
    for (; i < output.size(); ++i, input += bytes) {
        const auto value = scalarSample<Format>(input);
        accumulator.add(value);
        if constexpr (Waveform) waveform[i] = static_cast<float>(value);
        output[i] = static_cast<float>(value) * (Format == PcmFormat::F32 ? kFloatDspF32Scale : 1.0F);
    }
    return accumulator.result(output.size(), channels);
}

} // namespace

std::size_t pcmSampleBytes(PcmFormat format) noexcept {
    switch (format) {
    case PcmFormat::S16: return 2;
    case PcmFormat::U8: return 1;
    case PcmFormat::S24Packed: return 3;
    case PcmFormat::S32: case PcmFormat::S24In32: case PcmFormat::F32: return 4;
    }
    return 0;
}

SourceStatistics decodePcm(PcmFormat format, const unsigned char* input,
                          std::span<double> output, std::uint32_t channels) noexcept {
    switch (format) {
    case PcmFormat::S16: return decode<PcmFormat::S16>(input, output, channels);
    case PcmFormat::U8: return decode<PcmFormat::U8>(input, output, channels);
    case PcmFormat::S32: return decode<PcmFormat::S32>(input, output, channels);
    case PcmFormat::S24In32: return decode<PcmFormat::S24In32>(input, output, channels);
    case PcmFormat::F32: return decode<PcmFormat::F32>(input, output, channels);
    case PcmFormat::S24Packed: return decode<PcmFormat::S24Packed>(input, output, channels);
    }
    return {};
}

SourceStatistics decodePcm(PcmFormat format, const unsigned char* input,
                          std::span<float> output, std::uint32_t channels,
                          std::span<float> unscaledWaveform) noexcept {
    switch (format) {
    case PcmFormat::S16: return decodeFloat<PcmFormat::S16>(input, output, channels);
    case PcmFormat::U8: return decodeFloat<PcmFormat::U8>(input, output, channels);
    case PcmFormat::S32: return decodeFloat<PcmFormat::S32>(input, output, channels);
    case PcmFormat::S24In32: return decodeFloat<PcmFormat::S24In32>(input, output, channels);
    case PcmFormat::F32:
        if (!unscaledWaveform.empty()) {
            return decodeFloat<PcmFormat::F32, true>(input, output, channels, unscaledWaveform.data());
        }
        return decodeFloat<PcmFormat::F32>(input, output, channels);
    case PcmFormat::S24Packed: return decodeFloat<PcmFormat::S24Packed>(input, output, channels);
    }
    return {};
}

} // namespace spectrum::detail
