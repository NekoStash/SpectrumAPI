#pragma once

#include "spectrum/analysis.hpp"

#include <cstddef>
#include <cstdint>
#include <span>

namespace spectrum::detail {

std::size_t pcmSampleBytes(PcmFormat format) noexcept;
// One block-level format dispatch. Source statistics use decoded registers in
// the same pass, with channel-averaged double energy and absolute sample peak.
SourceStatistics decodePcm(PcmFormat format, const unsigned char* input,
                          std::span<double> interleaved, std::uint32_t channels) noexcept;

// A normalized FIR has |taps| sum < 4 (checked when making Float32 coefficients).
// At most three FIRs precede an FFT of <= 8192 points. Leave an extra bit
// for rounding and intermediate symmetric sums and butterflies.
inline constexpr float kFloatDspF32Scale = 0x1p-20F;
inline constexpr double kFloatDspF32PowerRestore = 0x1p40;

// If unscaledWaveform is nonempty, also emit the unscaled source samples there.
// Only F32 needs an extra waveform buffer; integer extrema can use output.
SourceStatistics decodePcm(PcmFormat format, const unsigned char* input,
                          std::span<float> output, std::uint32_t channels,
                          std::span<float> unscaledWaveform) noexcept;

} // namespace spectrum::detail
