#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace spectrum::detail {

enum class FirProfile { H2, D2, D3 };

// Immutable, shared coefficients. H2 stores only its nonzero symmetric pairs.
struct FirPlan {
    std::size_t taps = 0;
    std::size_t pairCount = 0;
    std::size_t pairStride = 1;
    unsigned divisor = 2;
    double center = 0.0;
    std::array<double, 96> pairs{};
};

const FirPlan& firPlan(FirProfile profile);

struct DecimatedBlock {
    std::size_t frames = 0;
    // Zero-based input frame responsible for the first output's source end.
    std::size_t firstInputFrame = 0;
};

class Decimator {
public:
    explicit Decimator(FirProfile profile, std::uint32_t channels);
    void reset() noexcept;
    DecimatedBlock process(std::span<const double> interleaved,
                           std::span<double> output) noexcept;
    unsigned divisor() const noexcept { return plan_->divisor; }
private:
    template<bool Sparse, std::uint32_t Channels>
    DecimatedBlock processBlock(std::span<const double> interleaved,
                                std::span<double> output) noexcept;
    const FirPlan* plan_;
    std::uint32_t channels_;
    std::size_t position_ = 0;
    unsigned phase_ = 0;
    // Mirroring removes wraparound from the convolution's hot loop.
    std::array<std::array<double, 386>, 2> history_{};
};

} // namespace spectrum::detail
