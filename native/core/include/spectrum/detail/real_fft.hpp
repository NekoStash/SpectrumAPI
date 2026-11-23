#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
#include <string>

namespace spectrum::detail {
// Shared immutable Hann/twiddle plans, but per-instance mutable FFT workspace.
template<typename Sample>
class BasicRealFft {
public:
    BasicRealFft() noexcept;
    ~BasicRealFft();
    BasicRealFft(BasicRealFft&&) noexcept;
    BasicRealFft& operator=(BasicRealFft&&) noexcept;
    BasicRealFft(const BasicRealFft&) = delete;
    BasicRealFft& operator=(const BasicRealFft&) = delete;
    bool configure(std::size_t fftSize, std::string& reason);
    // Planar ring: channel c occupies [c*N,(c+1)*N). writePosition is oldest.
    // Writes N/2+1 single-sided, Hann/Parseval-corrected channel-averaged powers.
    // All workspace is allocated by configure, never here.
    void power(std::span<const Sample> planarRing, std::size_t writePosition,
               std::uint32_t channels, std::span<double> output,
               double inputPowerRestore = 1.0) noexcept;
    std::size_t size() const noexcept;
private:
    struct State;
    std::unique_ptr<State> state_;
};

using RealFft = BasicRealFft<double>;
} // namespace spectrum::detail
