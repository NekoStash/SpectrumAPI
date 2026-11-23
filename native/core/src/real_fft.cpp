#include "spectrum/detail/real_fft.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <mutex>
#include <new>
#include <numbers>
#include <utility>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace spectrum::detail {
namespace {

template<typename Sample>
struct FftPlan {
    explicit FftPlan(std::size_t size)
        : n(size), half(size / 2), hann(size), reversed(half),
          twiddleReal(half - 1), twiddleImag(half - 1),
          splitReal(half), splitImag(half) {
        constexpr auto tau = 2.0 * std::numbers::pi_v<double>;
        std::array<double, 4> energy{};
        for (std::size_t i = 0; i < n; ++i) {
            const auto coefficient = 0.5 - 0.5 * std::cos(tau * static_cast<double>(i) /
                                                          static_cast<double>(n));
            hann[i] = static_cast<Sample>(coefficient);
            energy[i & 3U] += coefficient * coefficient;
        }
        powerScale = 1.0 / (static_cast<double>(n) *
            ((energy[0] + energy[1]) + (energy[2] + energy[3])));
        for (std::size_t i = 1; i < half; ++i) {
            reversed[i] = (reversed[i >> 1] >> 1) | ((i & 1U) * (half / 2));
        }
        // Every radix-2 stage owns a contiguous twiddle range at halfLength-1.
        for (std::size_t length = 2; length <= half; length *= 2) {
            const auto halfLength = length / 2;
            for (std::size_t i = 0; i < halfLength; ++i) {
                const auto angle = -tau * static_cast<double>(i) /
                                   static_cast<double>(length);
                twiddleReal[halfLength - 1 + i] = std::cos(angle);
                twiddleImag[halfLength - 1 + i] = std::sin(angle);
            }
        }
        for (std::size_t i = 0; i < half; ++i) {
            const auto angle = -tau * static_cast<double>(i) / static_cast<double>(n);
            splitReal[i] = std::cos(angle);
            splitImag[i] = std::sin(angle);
        }
    }

    const std::size_t n;
    const std::size_t half;
    std::vector<Sample> hann;
    std::vector<std::size_t> reversed;
    std::vector<Sample> twiddleReal;
    std::vector<Sample> twiddleImag;
    std::vector<Sample> splitReal;
    std::vector<Sample> splitImag;
    double powerScale = 0.0;
};

template<typename Sample>
std::shared_ptr<const FftPlan<Sample>> acquirePlan(std::size_t n) {
    // Only configure touches the cache. power reads its immutable shared plan.
    static std::array<std::shared_ptr<const FftPlan<Sample>>, 5> plans;
    static std::mutex mutex;
    const auto index = static_cast<std::size_t>(std::countr_zero(n) - 9);
    const std::lock_guard lock(mutex);
    if (!plans[index]) {
        plans[index] = std::make_shared<const FftPlan<Sample>>(n);
    }
    return plans[index];
}

void windowSegment(const double* samples, const double* window, double* destination,
                   std::size_t count) noexcept {
    std::size_t i = 0;
#if defined(__aarch64__)
    for (; i + 3 < count; i += 4) {
        const auto first = vmulq_f64(vld1q_f64(samples + i), vld1q_f64(window + i));
        const auto second = vmulq_f64(vld1q_f64(samples + i + 2),
                                     vld1q_f64(window + i + 2));
        vst1q_f64(destination + i, first);
        vst1q_f64(destination + i + 2, second);
    }
#endif
    for (; i < count; ++i) {
        destination[i] = samples[i] * window[i];
    }
}

void windowSegment(const float* samples, const float* window, float* destination,
                   std::size_t count) noexcept {
    std::size_t i = 0;
#if defined(__aarch64__)
    for (; i + 4 <= count; i += 4) {
        vst1q_f32(destination + i, vmulq_f32(vld1q_f32(samples + i), vld1q_f32(window + i)));
    }
#endif
    for (; i < count; ++i) destination[i] = samples[i] * window[i];
}

#if defined(__aarch64__)
void butterflyPair(double* real, double* imag, std::size_t even, std::size_t odd,
                   const double* twiddleReal, const double* twiddleImag) noexcept {
    const auto ar = vld1q_f64(real + even);
    const auto ai = vld1q_f64(imag + even);
    const auto br = vld1q_f64(real + odd);
    const auto bi = vld1q_f64(imag + odd);
    const auto wr = vld1q_f64(twiddleReal);
    const auto wi = vld1q_f64(twiddleImag);
    const auto tr = vfmsq_f64(vmulq_f64(br, wr), bi, wi);
    const auto ti = vfmaq_f64(vmulq_f64(br, wi), bi, wr);
    vst1q_f64(real + even, vaddq_f64(ar, tr));
    vst1q_f64(imag + even, vaddq_f64(ai, ti));
    vst1q_f64(real + odd, vsubq_f64(ar, tr));
    vst1q_f64(imag + odd, vsubq_f64(ai, ti));
}
#endif

void complexTransform(const FftPlan<double>& plan, double* real, double* imag) noexcept {
    // N/2 complex DIT transform, with the packed input already bit-reversed.
    std::size_t base = 0;
#if defined(__aarch64__)
    for (; base < plan.half; base += 4) {
        const auto r = vld2q_f64(real + base);
        const auto i = vld2q_f64(imag + base);
        const float64x2x2_t outReal{{vaddq_f64(r.val[0], r.val[1]),
                                   vsubq_f64(r.val[0], r.val[1])}};
        const float64x2x2_t outImag{{vaddq_f64(i.val[0], i.val[1]),
                                   vsubq_f64(i.val[0], i.val[1])}};
        vst2q_f64(real + base, outReal);
        vst2q_f64(imag + base, outImag);
    }
#else
    for (; base < plan.half; base += 2) {
        const auto ar = real[base];
        const auto ai = imag[base];
        const auto br = real[base + 1];
        const auto bi = imag[base + 1];
        real[base] = ar + br;
        imag[base] = ai + bi;
        real[base + 1] = ar - br;
        imag[base + 1] = ai - bi;
    }
#endif
    for (std::size_t length = 4; length <= plan.half; length *= 2) {
        const auto halfLength = length / 2;
        const auto* wr = plan.twiddleReal.data() + halfLength - 1;
        const auto* wi = plan.twiddleImag.data() + halfLength - 1;
        for (base = 0; base < plan.half; base += length) {
#if defined(__aarch64__)
            if (halfLength == 2) {
                butterflyPair(real, imag, base, base + halfLength, wr, wi);
            } else {
                // Two independent f64x2 butterflies expose separate FMA chains.
                for (std::size_t j = 0; j < halfLength; j += 4) {
                    butterflyPair(real, imag, base + j, base + halfLength + j,
                                  wr + j, wi + j);
                    butterflyPair(real, imag, base + j + 2, base + halfLength + j + 2,
                                  wr + j + 2, wi + j + 2);
                }
            }
#else
            for (std::size_t j = 0; j < halfLength; ++j) {
                const auto even = base + j;
                const auto odd = even + halfLength;
                const auto ar = real[even];
                const auto ai = imag[even];
                const auto tr = real[odd] * wr[j] - imag[odd] * wi[j];
                const auto ti = real[odd] * wi[j] + imag[odd] * wr[j];
                real[even] = ar + tr;
                imag[even] = ai + ti;
                real[odd] = ar - tr;
                imag[odd] = ai - ti;
            }
#endif
        }
    }
}

void complexTransform(const FftPlan<float>& plan, float* real, float* imag) noexcept {
#if defined(__aarch64__)
    for (std::size_t base = 0; base < plan.half; base += 8) {
        const auto r = vld2q_f32(real + base);
        const auto i = vld2q_f32(imag + base);
        const float32x4x2_t outReal{{vaddq_f32(r.val[0], r.val[1]), vsubq_f32(r.val[0], r.val[1])}};
        const float32x4x2_t outImag{{vaddq_f32(i.val[0], i.val[1]), vsubq_f32(i.val[0], i.val[1])}};
        vst2q_f32(real + base, outReal);
        vst2q_f32(imag + base, outImag);
    }
    constexpr std::size_t firstLength = 4;
#else
    constexpr std::size_t firstLength = 2;
#endif
    for (std::size_t length = firstLength; length <= plan.half; length *= 2) {
        const auto halfLength = length / 2;
        const auto* wr = plan.twiddleReal.data() + halfLength - 1;
        const auto* wi = plan.twiddleImag.data() + halfLength - 1;
        for (std::size_t base = 0; base < plan.half; base += length) {
#if defined(__aarch64__)
            if (halfLength >= 4) {
                for (std::size_t j = 0; j < halfLength; j += 4) {
                    const auto even = base + j;
                    const auto odd = even + halfLength;
                    const auto ar = vld1q_f32(real + even);
                    const auto ai = vld1q_f32(imag + even);
                    const auto br = vld1q_f32(real + odd);
                    const auto bi = vld1q_f32(imag + odd);
                    const auto wReal = vld1q_f32(wr + j);
                    const auto wImag = vld1q_f32(wi + j);
                    const auto tr = vfmsq_f32(vmulq_f32(br, wReal), bi, wImag);
                    const auto ti = vfmaq_f32(vmulq_f32(br, wImag), bi, wReal);
                    vst1q_f32(real + even, vaddq_f32(ar, tr));
                    vst1q_f32(imag + even, vaddq_f32(ai, ti));
                    vst1q_f32(real + odd, vsubq_f32(ar, tr));
                    vst1q_f32(imag + odd, vsubq_f32(ai, ti));
                }
                continue;
            }
#endif
            for (std::size_t j = 0; j < halfLength; ++j) {
                const auto even = base + j;
                const auto odd = even + halfLength;
                const auto ar = real[even];
                const auto ai = imag[even];
                const auto tr = real[odd] * wr[j] - imag[odd] * wi[j];
                const auto ti = real[odd] * wi[j] + imag[odd] * wr[j];
                real[even] = ar + tr;
                imag[even] = ai + ti;
                real[odd] = ar - tr;
                imag[odd] = ai - ti;
            }
        }
    }
}

template<bool Add, typename Sample>
void accumulateBin(const FftPlan<Sample>& plan, const Sample* real, const Sample* imag,
                   std::size_t bin, double scale, double* output) noexcept {
    const double ar = real[bin];
    const double ai = imag[bin];
    const double br = real[plan.half - bin];
    const double bi = imag[plan.half - bin];
    const auto difference = ar - br;
    const auto sum = ai + bi;
    const auto xr = 0.5 * ((ar + br) + plan.splitImag[bin] * difference +
                          plan.splitReal[bin] * sum);
    const auto xi = 0.5 * ((ai - bi) + plan.splitImag[bin] * sum -
                          plan.splitReal[bin] * difference);
    const auto power = (xr * xr + xi * xi) * scale;
    if constexpr (Add) output[bin] += power;
    else output[bin] = power;
}

#if defined(__aarch64__)
float64x2_t loadAsDouble(const double* input) noexcept { return vld1q_f64(input); }
float64x2_t loadAsDouble(const float* input) noexcept { return vcvt_f64_f32(vld1_f32(input)); }
#endif

template<bool Add, typename Sample>
void accumulatePowers(const FftPlan<Sample>& plan, const Sample* real, const Sample* imag,
                      double scale, double* output) noexcept {
    const auto dc = static_cast<double>(real[0]) + imag[0];
    const auto nyquist = static_cast<double>(real[0]) - imag[0];
    if constexpr (Add) {
        output[0] += dc * dc * scale;
        output[plan.half] += nyquist * nyquist * scale;
    } else {
        output[0] = dc * dc * scale;
        output[plan.half] = nyquist * nyquist * scale;
    }
    const auto sidedScale = 2.0 * scale;
    accumulateBin<Add>(plan, real, imag, 1, sidedScale, output);
#if defined(__aarch64__)
    const auto half = vdupq_n_f64(0.5);
    const auto normalization = vdupq_n_f64(sidedScale);
    for (std::size_t bin = 2; bin < plan.half; bin += 2) {
        const auto ar = loadAsDouble(real + bin);
        const auto ai = loadAsDouble(imag + bin);
        const auto reversedReal = loadAsDouble(real + plan.half - bin - 1);
        const auto reversedImag = loadAsDouble(imag + plan.half - bin - 1);
        const auto br = vextq_f64(reversedReal, reversedReal, 1);
        const auto bi = vextq_f64(reversedImag, reversedImag, 1);
        const auto wr = loadAsDouble(plan.splitReal.data() + bin);
        const auto wi = loadAsDouble(plan.splitImag.data() + bin);
        const auto difference = vsubq_f64(ar, br);
        const auto sum = vaddq_f64(ai, bi);
        const auto xr = vmulq_f64(half, vfmaq_f64(
            vfmaq_f64(vaddq_f64(ar, br), wi, difference), wr, sum));
        const auto xi = vmulq_f64(half, vfmsq_f64(
            vfmaq_f64(vsubq_f64(ai, bi), wi, sum), wr, difference));
        const auto norm = vfmaq_f64(vmulq_f64(xr, xr), xi, xi);
        if constexpr (Add) {
            vst1q_f64(output + bin, vfmaq_f64(vld1q_f64(output + bin), norm, normalization));
        } else {
            vst1q_f64(output + bin, vmulq_f64(norm, normalization));
        }
    }
#else
    for (std::size_t bin = 2; bin < plan.half; ++bin) {
        accumulateBin<Add>(plan, real, imag, bin, sidedScale, output);
    }
#endif
}

} // namespace

template<typename Sample>
struct BasicRealFft<Sample>::State {
    explicit State(std::shared_ptr<const FftPlan<Sample>> sharedPlan)
        : plan(std::move(sharedPlan)), windowed(plan->n), real(plan->half), imag(plan->half) {}

    const std::shared_ptr<const FftPlan<Sample>> plan;
    std::vector<Sample> windowed;
    std::vector<Sample> real;
    std::vector<Sample> imag;
};

template<typename Sample>
BasicRealFft<Sample>::BasicRealFft() noexcept = default;
template<typename Sample>
BasicRealFft<Sample>::~BasicRealFft() = default;
template<typename Sample>
BasicRealFft<Sample>::BasicRealFft(BasicRealFft&&) noexcept = default;
template<typename Sample>
BasicRealFft<Sample>& BasicRealFft<Sample>::operator=(BasicRealFft&&) noexcept = default;

template<typename Sample>
bool BasicRealFft<Sample>::configure(std::size_t fftSize, std::string& reason) {
    if (fftSize < 512 || fftSize > 8192 || !std::has_single_bit(fftSize)) {
        reason = "FFT size must be a power of two in [512, 8192]";
        return false;
    }
    if (state_ && state_->plan->n == fftSize) {
        reason.clear();
        return true;
    }
    try {
        auto replacement = std::make_unique<State>(acquirePlan<Sample>(fftSize));
        state_.swap(replacement);
        reason.clear();
        return true;
    } catch (const std::bad_alloc&) {
        reason = "cannot allocate FFT plan or workspace";
        return false;
    }
}

template<typename Sample>
void BasicRealFft<Sample>::power(std::span<const Sample> planarRing, std::size_t writePosition,
                                  std::uint32_t channels, std::span<double> output,
                                  double inputPowerRestore) noexcept {
    if (!state_) {
        std::fill(output.begin(), output.end(), 0.0);
        return;
    }
    auto& state = *state_;
    const auto& plan = *state.plan;
    if ((channels != 1 && channels != 2) || writePosition >= plan.n ||
        planarRing.size() < plan.n * channels || output.size() < plan.half + 1) {
        std::fill(output.begin(), output.end(), 0.0);
        return;
    }
    std::fill(output.begin() + static_cast<std::ptrdiff_t>(plan.half + 1), output.end(), 0.0);
    const auto firstCount = plan.n - writePosition;
    const auto scale = plan.powerScale / static_cast<double>(channels) * inputPowerRestore;
    for (std::uint32_t channel = 0; channel < channels; ++channel) {
        const auto* samples = planarRing.data() + static_cast<std::size_t>(channel) * plan.n;
        windowSegment(samples + writePosition, plan.hann.data(),
                      state.windowed.data(), firstCount);
        windowSegment(samples, plan.hann.data() + firstCount,
                      state.windowed.data() + firstCount, writePosition);
        // Pack even/odd real samples into one N/2 complex transform.
        for (std::size_t i = 0; i < plan.half; ++i) {
            const auto destination = plan.reversed[i];
            state.real[destination] = state.windowed[2 * i];
            state.imag[destination] = state.windowed[2 * i + 1];
        }
        complexTransform(plan, state.real.data(), state.imag.data());
        if (channel == 0) {
            accumulatePowers<false>(plan, state.real.data(), state.imag.data(), scale, output.data());
        } else {
            accumulatePowers<true>(plan, state.real.data(), state.imag.data(), scale, output.data());
        }
    }
}

template<typename Sample>
std::size_t BasicRealFft<Sample>::size() const noexcept {
    return state_ ? state_->plan->n : 0;
}

template class BasicRealFft<double>;
template class BasicRealFft<float>;

} // namespace spectrum::detail
