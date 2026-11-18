#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>

namespace spectrum::detail {
// Shared immutable Hann/twiddle plans, but per-instance mutable FFT workspace.
class RealFft {
public:
    RealFft() noexcept;
    ~RealFft();
    RealFft(RealFft&&) noexcept;
    RealFft& operator=(RealFft&&) noexcept;
    RealFft(const RealFft&) = delete;
    RealFft& operator=(const RealFft&) = delete;
    bool configure(std::size_t fftSize, std::string& reason);
    // Planar ring: channel c occupies [c*N,(c+1)*N). writePosition is oldest.
    // Writes N/2+1 single-sided, Hann/Parseval-corrected channel-averaged powers.
    // All workspace is allocated by configure, never here.
    void power(std::span<const double> planarRing, std::size_t writePosition,
               std::uint32_t channels, std::span<double> output) noexcept;
    std::size_t size() const noexcept;
private:
    struct State;
    std::unique_ptr<State> state_;
};
} // namespace spectrum::detail
