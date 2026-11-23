#include "decimator.hpp"

#include <algorithm>
#include <cmath>
#include <numbers>
#include <stdexcept>
#include <type_traits>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace spectrum::detail {
namespace {

constexpr double kPi = std::numbers::pi_v<double>;

// I0's positive series converges rapidly for the fixed Kaiser beta=10.
double besselI0(double x) noexcept {
    double term = 1.0;
    double result = 1.0;
    const auto square = x * x * 0.25;
    for (unsigned k = 1; k < 64; ++k) {
        term *= square / static_cast<double>(k * k);
        result += term;
        if (term < result * 1.0e-17) break;
    }
    return result;
}

FirPlan makePlan(FirProfile profile) {
    FirPlan result;
    result.taps = profile == FirProfile::H2 ? 95 : profile == FirProfile::D2 ? 129 : 193;
    result.divisor = profile == FirProfile::D3 ? 3U : 2U;
    result.pairStride = profile == FirProfile::H2 ? 2 : 1;
    const auto half = result.taps / 2;
    result.pairCount = (half + result.pairStride - 1) / result.pairStride;
    const auto cutoff = profile == FirProfile::H2 ? 0.25 :
                        profile == FirProfile::D2 ? 0.225 : 0.15;
    constexpr double beta = 10.0;
    const auto denominator = besselI0(beta);
    result.center = 2.0 * cutoff;
    double gain = result.center;
    for (std::size_t pair = 0; pair < result.pairCount; ++pair) {
        const auto i = pair * result.pairStride;
        const auto offset = static_cast<double>(i) - static_cast<double>(half);
        const auto normalized = offset / static_cast<double>(half);
        const auto window = besselI0(beta * std::sqrt(1.0 - normalized * normalized)) /
                            denominator;
        const auto coefficient = std::sin(2.0 * kPi * cutoff * offset) / (kPi * offset) *
                                 window;
        result.pairs[pair] = coefficient;
        gain += 2.0 * coefficient;
    }
    result.center /= gain;
    for (std::size_t pair = 0; pair < result.pairCount; ++pair) {
        result.pairs[pair] /= gain;
    }
    return result;
}

#if defined(__aarch64__)
template<bool Sparse>
float64x2_t pairedSamples(const double* samples, std::size_t taps,
                          std::size_t pair) noexcept {
    if constexpr (Sparse) {
        const auto left = vld2q_f64(samples + pair * 2).val[0];
        const auto right = vld2q_f64(samples + taps - 3 - pair * 2).val[0];
        return vaddq_f64(left, vextq_f64(right, right, 1));
    } else {
        const auto left = vld1q_f64(samples + pair);
        const auto right = vld1q_f64(samples + taps - 2 - pair);
        return vaddq_f64(left, vextq_f64(right, right, 1));
    }
}
#endif

template<bool Sparse>
double convolve(const FirPlan& plan, const double* samples) noexcept {
    double result = plan.center * samples[plan.taps / 2];
    std::size_t pair = 0;
#if defined(__aarch64__)
    auto sum0 = vdupq_n_f64(0.0);
    auto sum1 = vdupq_n_f64(0.0);
    auto sum2 = vdupq_n_f64(0.0);
    auto sum3 = vdupq_n_f64(0.0);
    for (; pair + 8 <= plan.pairCount; pair += 8) {
        sum0 = vfmaq_f64(sum0, pairedSamples<Sparse>(samples, plan.taps, pair),
                         vld1q_f64(plan.pairs.data() + pair));
        sum1 = vfmaq_f64(sum1, pairedSamples<Sparse>(samples, plan.taps, pair + 2),
                         vld1q_f64(plan.pairs.data() + pair + 2));
        sum2 = vfmaq_f64(sum2, pairedSamples<Sparse>(samples, plan.taps, pair + 4),
                         vld1q_f64(plan.pairs.data() + pair + 4));
        sum3 = vfmaq_f64(sum3, pairedSamples<Sparse>(samples, plan.taps, pair + 6),
                         vld1q_f64(plan.pairs.data() + pair + 6));
    }
    result += vaddvq_f64(vaddq_f64(vaddq_f64(sum0, sum1), vaddq_f64(sum2, sum3)));
#endif
    // Four independent accumulators also expose ILP on the host scalar build.
    double sum0s = 0.0;
    double sum1s = 0.0;
    double sum2s = 0.0;
    double sum3s = 0.0;
    constexpr std::size_t stride = Sparse ? 2 : 1;
    for (; pair + 4 <= plan.pairCount; pair += 4) {
        const auto i = pair * stride;
        sum0s += plan.pairs[pair] * (samples[i] + samples[plan.taps - 1 - i]);
        sum1s += plan.pairs[pair + 1] *
                 (samples[i + stride] + samples[plan.taps - 1 - i - stride]);
        sum2s += plan.pairs[pair + 2] *
                 (samples[i + 2 * stride] + samples[plan.taps - 1 - i - 2 * stride]);
        sum3s += plan.pairs[pair + 3] *
                 (samples[i + 3 * stride] + samples[plan.taps - 1 - i - 3 * stride]);
    }
    result += (sum0s + sum1s) + (sum2s + sum3s);
    for (; pair < plan.pairCount; ++pair) {
        const auto i = pair * stride;
        result += plan.pairs[pair] * (samples[i] + samples[plan.taps - 1 - i]);
    }
    return result;
}

} // namespace

const FirPlan& firPlan(FirProfile profile) {
    // Construct only the requested immutable profile, and only off the audio thread.
    switch (profile) {
    case FirProfile::H2: {
        static const auto plan = makePlan(FirProfile::H2);
        return plan;
    }
    case FirProfile::D2: {
        static const auto plan = makePlan(FirProfile::D2);
        return plan;
    }
    case FirProfile::D3: {
        static const auto plan = makePlan(FirProfile::D3);
        return plan;
    }
    }
    // The private enum has exactly these three values.
    __builtin_unreachable();
}

template<typename Sample>
struct FirCoefficients {
    Sample center;
    std::array<Sample, 96> pairs{};
};

namespace {

template<FirProfile Profile>
const FirCoefficients<float>& floatCoefficients() {
    static const auto coefficients = [] {
        const auto& plan = firPlan(Profile);
        FirCoefficients<float> result{static_cast<float>(plan.center)};
        double norm = std::abs(result.center);
        for (std::size_t i = 0; i < plan.pairCount; ++i) {
            result.pairs[i] = static_cast<float>(plan.pairs[i]);
            norm += 2.0 * std::abs(result.pairs[i]);
        }
        // Float32 F32 input scaling relies on this bound (see pcm_decode.hpp).
        if (!(norm < 4.0)) throw std::logic_error("Float32 FIR coefficient norm exceeds headroom");
        return result;
    }();
    return coefficients;
}

#if defined(__aarch64__)
template<bool Sparse>
float32x4_t pairedSamples(const float* samples, std::size_t taps,
                          std::size_t first) noexcept {
    constexpr std::size_t stride = Sparse ? 2 : 1;
    const auto offset = first * stride;
    const auto* left = samples + offset;
    const auto* right = samples + taps - 1 - (offset + 3 * stride);
    float32x4_t lo, hi;
    if constexpr (Sparse) {
        lo = vld2q_f32(left).val[0];
        hi = vld2q_f32(right).val[0];
    } else {
        lo = vld1q_f32(left);
        hi = vld1q_f32(right);
    }
    const auto reversed = vrev64q_f32(hi);
    return vaddq_f32(lo, vextq_f32(reversed, reversed, 2));
}
#endif

template<bool Sparse>
float convolve(const FirPlan& plan, const FirCoefficients<float>& coefficients,
               const float* samples) noexcept {
    float result = coefficients.center * samples[plan.taps / 2];
    constexpr std::size_t stride = Sparse ? 2 : 1;
    std::size_t pair = 0;
#if defined(__aarch64__)
    const auto paired = [&](std::size_t first) {
        return pairedSamples<Sparse>(samples, plan.taps, first);
    };
    auto sum0 = vdupq_n_f32(0.0F);
    auto sum1 = vdupq_n_f32(0.0F);
    for (; pair + 8 <= plan.pairCount; pair += 8) {
        sum0 = vfmaq_f32(sum0, paired(pair), vld1q_f32(coefficients.pairs.data() + pair));
        sum1 = vfmaq_f32(sum1, paired(pair + 4), vld1q_f32(coefficients.pairs.data() + pair + 4));
    }
    if (pair + 4 <= plan.pairCount) {
        sum0 = vfmaq_f32(sum0, paired(pair), vld1q_f32(coefficients.pairs.data() + pair));
        pair += 4;
    }
    result += vaddvq_f32(vaddq_f32(sum0, sum1));
#endif
    for (; pair < plan.pairCount; ++pair) {
        const auto i = pair * stride;
        result += coefficients.pairs[pair] * (samples[i] + samples[plan.taps - 1 - i]);
    }
    return result;
}

#if defined(__aarch64__)
template<bool Sparse>
void convolveStereo(const FirPlan& plan, const FirCoefficients<float>& coefficients,
                    const float* left, const float* right, float* output) noexcept {
    const auto taps = plan.taps;
    const auto center = coefficients.center;
    float leftResult = center * left[taps / 2];
    float rightResult = center * right[taps / 2];
    auto left0 = vdupq_n_f32(0.0F);
    auto left1 = vdupq_n_f32(0.0F);
    auto right0 = vdupq_n_f32(0.0F);
    auto right1 = vdupq_n_f32(0.0F);
    const auto accumulate = [&](std::size_t pair, float32x4_t& leftSum, float32x4_t& rightSum) {
        const auto c = vld1q_f32(coefficients.pairs.data() + pair);
        leftSum = vfmaq_f32(leftSum, pairedSamples<Sparse>(left, taps, pair), c);
        rightSum = vfmaq_f32(rightSum, pairedSamples<Sparse>(right, taps, pair), c);
    };
    // Share loads, but keep the two original accumulator chains per channel.
    std::size_t pair = 0;
    for (; pair + 8 <= plan.pairCount; pair += 8) {
        accumulate(pair, left0, right0);
        accumulate(pair + 4, left1, right1);
    }
    if (pair + 4 <= plan.pairCount) {
        accumulate(pair, left0, right0);
        pair += 4;
    }
    leftResult += vaddvq_f32(vaddq_f32(left0, left1));
    rightResult += vaddvq_f32(vaddq_f32(right0, right1));
    constexpr std::size_t stride = Sparse ? 2 : 1;
    for (; pair < plan.pairCount; ++pair) {
        const auto i = pair * stride;
        const auto c = coefficients.pairs[pair];
        leftResult += c * (left[i] + left[taps - 1 - i]);
        rightResult += c * (right[i] + right[taps - 1 - i]);
    }
    output[0] = leftResult;
    output[1] = rightResult;
}
#endif

} // namespace

template<typename Sample>
BasicDecimator<Sample>::BasicDecimator(FirProfile profile, std::uint32_t channels)
    : plan_(&firPlan(profile)), coefficients_(nullptr), channels_(channels) {
    if constexpr (std::is_same_v<Sample, float>) {
        switch (profile) {
        case FirProfile::H2: coefficients_ = &floatCoefficients<FirProfile::H2>(); break;
        case FirProfile::D2: coefficients_ = &floatCoefficients<FirProfile::D2>(); break;
        case FirProfile::D3: coefficients_ = &floatCoefficients<FirProfile::D3>(); break;
        }
    }
}

template<typename Sample>
void BasicDecimator<Sample>::reset() noexcept {
    for (auto& channel : history_) channel.fill(0.0);
    position_ = 0;
    phase_ = 0;
}

template<typename Sample>
template<bool Sparse, std::uint32_t Channels>
DecimatedBlock BasicDecimator<Sample>::processBlock(std::span<const Sample> input,
                                                     std::span<Sample> output) noexcept {
    DecimatedBlock result;
    result.firstInputFrame = plan_->divisor - 1 - phase_;
    auto position = position_;
    auto phase = phase_;
    const auto taps = plan_->taps;
    const auto frames = input.size() / Channels;
    for (std::size_t frame = 0; frame < frames; ++frame) {
        // Channel count, profile and node dispatch are outside this input loop.
        for (std::uint32_t channel = 0; channel < Channels; ++channel) {
            const auto value = input[frame * Channels + channel];
            history_[channel][position] = value;
            history_[channel][position + taps] = value;
        }
        if (++position == taps) position = 0;
        if (++phase != plan_->divisor) continue;
        phase = 0;
#if defined(__aarch64__)
        if constexpr (Channels == 2 && std::is_same_v<Sample, float>) {
            convolveStereo<Sparse>(*plan_, *coefficients_, history_[0].data() + position,
                                   history_[1].data() + position, output.data() + result.frames * Channels);
        } else
#endif
        {
            for (std::uint32_t channel = 0; channel < Channels; ++channel) {
                const auto* samples = history_[channel].data() + position;
                if constexpr (std::is_same_v<Sample, float>) {
                    output[result.frames * Channels + channel] =
                        convolve<Sparse>(*plan_, *coefficients_, samples);
                } else {
                    output[result.frames * Channels + channel] = convolve<Sparse>(*plan_, samples);
                }
            }
        }
        ++result.frames;
    }
    position_ = position;
    phase_ = phase;
    return result;
}

template<typename Sample>
DecimatedBlock BasicDecimator<Sample>::process(std::span<const Sample> input,
                                               std::span<Sample> output) noexcept {
    if (plan_->pairStride == 2) {
        if (channels_ == 2) return processBlock<true, 2>(input, output);
        return processBlock<true, 1>(input, output);
    }
    if (channels_ == 2) return processBlock<false, 2>(input, output);
    return processBlock<false, 1>(input, output);
}

template class BasicDecimator<double>;
template class BasicDecimator<float>;

} // namespace spectrum::detail
