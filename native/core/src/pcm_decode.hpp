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

} // namespace spectrum::detail
