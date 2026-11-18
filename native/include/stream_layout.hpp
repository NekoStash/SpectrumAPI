#pragma once
#include <cstddef>
#include <cstdint>

namespace spectrum::stream {
// Early-development layout is changed in place; no legacy-format negotiation.
inline constexpr std::uint32_t kMagic = 0x53504354;
inline constexpr std::uint32_t kVersion = 1;
inline constexpr std::size_t kMappedBytes = 8192;
inline constexpr std::size_t kHeaderBytes = 64;
inline constexpr std::size_t kFrameStride = 768;
inline constexpr std::size_t kSlots = 8;
inline constexpr std::size_t kWaveformMetadata = 192;
inline constexpr std::size_t kWaveformSamples = 256;
inline constexpr std::size_t kWaveformBins = 64;
// Frame offsets 0..191 retain the existing spectrum/control fields.
// +192 kind:u32, +196 status:u32, +200 validBins:u32, +204 sourceRate:u32,
// +208 trackToken:u64, +216 sourceEpoch:u64, +224 endSourceSequence:u64,
// +232 windowFrames:u32, +236 flags:u32, +240 captureEndNs:u64,
// +248 windowDurationNs:u64, +256 interleaved [min,max] * 64 as float bits.
// Header +48 is outputMode:u32. Frame +60 mirrors outputMode:u32.
static_assert(kHeaderBytes + kSlots * kFrameStride <= kMappedBytes);
static_assert(kWaveformSamples + kWaveformBins * 2 * sizeof(float) == kFrameStride);
} // namespace spectrum::stream
