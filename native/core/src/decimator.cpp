#include "decimator.hpp"

#include <algorithm>
#include <cmath>
#include <numbers>

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

Decimator::Decimator(FirProfile profile, std::uint32_t channels)
    : plan_(&firPlan(profile)), channels_(channels) {}

void Decimator::reset() noexcept {
    for (auto& channel : history_) channel.fill(0.0);
    position_ = 0;
    phase_ = 0;
}

template<bool Sparse, std::uint32_t Channels>
DecimatedBlock Decimator::processBlock(std::span<const double> input,
                                       std::span<double> output) noexcept {
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
        for (std::uint32_t channel = 0; channel < Channels; ++channel) {
            output[result.frames * Channels + channel] =
                convolve<Sparse>(*plan_, history_[channel].data() + position);
        }
        ++result.frames;
    }
    position_ = position;
    phase_ = phase;
    return result;
}

DecimatedBlock Decimator::process(std::span<const double> input,
                                  std::span<double> output) noexcept {
    if (plan_->pairStride == 2) {
        if (channels_ == 2) return processBlock<true, 2>(input, output);
        return processBlock<true, 1>(input, output);
    }
    if (channels_ == 2) return processBlock<false, 2>(input, output);
    return processBlock<false, 1>(input, output);
}

} // namespace spectrum::detail
