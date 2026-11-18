#include "spectrum/analysis.hpp"

#include <algorithm>
#include <cmath>
#include <new>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace spectrum {
namespace {

struct BandPlan {
    std::size_t first = 0;
    std::size_t last = 0;
    double firstWeight = 0.0;
    double lastWeight = 0.0;
};

double sumInterior(const double* powers, std::size_t first, std::size_t end) noexcept {
    double sum = 0.0;
#if defined(__aarch64__)
    auto sum0 = vdupq_n_f64(0.0);
    auto sum1 = vdupq_n_f64(0.0);
    auto sum2 = vdupq_n_f64(0.0);
    auto sum3 = vdupq_n_f64(0.0);
    for (; first + 7 < end; first += 8) {
        sum0 = vaddq_f64(sum0, vld1q_f64(powers + first));
        sum1 = vaddq_f64(sum1, vld1q_f64(powers + first + 2));
        sum2 = vaddq_f64(sum2, vld1q_f64(powers + first + 4));
        sum3 = vaddq_f64(sum3, vld1q_f64(powers + first + 6));
    }
    for (; first + 1 < end; first += 2) {
        sum0 = vaddq_f64(sum0, vld1q_f64(powers + first));
    }
    sum = vaddvq_f64(vaddq_f64(vaddq_f64(sum0, sum1), vaddq_f64(sum2, sum3)));
#else
    double sum0 = 0.0;
    double sum1 = 0.0;
    double sum2 = 0.0;
    double sum3 = 0.0;
    for (; first + 3 < end; first += 4) {
        sum0 += powers[first];
        sum1 += powers[first + 1];
        sum2 += powers[first + 2];
        sum3 += powers[first + 3];
    }
    sum = (sum0 + sum1) + (sum2 + sum3);
#endif
    for (; first < end; ++first) {
        sum += powers[first];
    }
    return sum;
}

} // namespace

struct BandMapper::State {
    int rateHz = 0;
    int bands = 0;
    std::size_t fftSize = 0;
    std::array<float, 33> edges{};
    std::array<BandPlan, 32> plans{};
};

BandMapper::BandMapper() noexcept = default;
BandMapper::~BandMapper() = default;
BandMapper::BandMapper(BandMapper&&) noexcept = default;
BandMapper& BandMapper::operator=(BandMapper&&) noexcept = default;

bool BandMapper::configure(int rateHz, int bands, float lowHz, float highHz,
                           std::string& reason) {
    if (rateHz <= 0 || rateHz > 192000) {
        reason = "analysis rate must be in [1, 192000] Hz";
        return false;
    }
    if (bands != 8 && bands != 16 && bands != 32) {
        reason = "bands must be 8, 16 or 32";
        return false;
    }
    if (!std::isfinite(lowHz) || !std::isfinite(highHz) ||
        lowHz <= 0.0F || highHz <= lowHz) {
        reason = "band frequencies must be finite and satisfy 0 < low < high";
        return false;
    }
    const auto nyquist = static_cast<double>(rateHz) * 0.5;
    const auto low = static_cast<double>(lowHz);
    const auto high = std::min(static_cast<double>(highHz), nyquist);
    if (!(low < high)) {
        reason = "low is not below the effective high/Nyquist frequency";
        return false;
    }
    try {
        auto replacement = std::make_unique<State>();
        auto& state = *replacement;
        state.rateHz = rateHz;
        state.bands = bands;
        state.fftSize = fftSizeForRate(rateHz);
        const auto logLow = std::log(low);
        const auto logWidth = std::log(high) - logLow;
        for (int band = 0; band <= bands; ++band) {
            state.edges[static_cast<std::size_t>(band)] = static_cast<float>(
                std::exp(logLow + logWidth * static_cast<double>(band) /
                                  static_cast<double>(bands)));
        }
        state.edges[0] = lowHz;
        state.edges[static_cast<std::size_t>(bands)] = static_cast<float>(high);
        for (int band = 1; band <= bands; ++band) {
            if (!(state.edges[static_cast<std::size_t>(band)] >
                  state.edges[static_cast<std::size_t>(band - 1)])) {
                reason = "effective frequency range is too narrow for the band count";
                return false;
            }
        }
        const auto binHz = static_cast<double>(rateHz) / static_cast<double>(state.fftSize);
        for (int band = 0; band < bands; ++band) {
            auto& plan = state.plans[static_cast<std::size_t>(band)];
            const auto left = static_cast<double>(state.edges[static_cast<std::size_t>(band)]);
            const auto right = static_cast<double>(state.edges[static_cast<std::size_t>(band + 1)]);
            bool first = true;
            for (std::size_t bin = 0; bin <= state.fftSize / 2; ++bin) {
                const auto binLow = std::max(0.0, (static_cast<double>(bin) - 0.5) * binHz);
                const auto binHigh = std::min(nyquist, (static_cast<double>(bin) + 0.5) * binHz);
                const auto overlap = std::min(right, binHigh) - std::max(left, binLow);
                if (overlap <= 0.0) {
                    continue;
                }
                const auto weight = overlap / (binHigh - binLow);
                if (first) {
                    plan.first = bin;
                    plan.firstWeight = weight;
                    first = false;
                }
                plan.last = bin;
                plan.lastWeight = weight;
            }
        }
        state_.swap(replacement);
        reason.clear();
        return true;
    } catch (const std::bad_alloc&) {
        reason = "cannot allocate frequency band projection";
        return false;
    }
}

void BandMapper::project(const SpectrumSnapshot& snapshot,
                         std::array<double, 32>& powers) const noexcept {
    powers.fill(0.0);
    if (!state_ || snapshot.rateHz != state_->rateHz || snapshot.fftSize != state_->fftSize ||
        snapshot.powers.size() < state_->fftSize / 2 + 1) {
        return;
    }
    const auto& state = *state_;
    for (int band = 0; band < state.bands; ++band) {
        const auto& plan = state.plans[static_cast<std::size_t>(band)];
        auto value = snapshot.powers[plan.first] * plan.firstWeight;
        if (plan.first != plan.last) {
            value += sumInterior(snapshot.powers.data(), plan.first + 1, plan.last);
            value += snapshot.powers[plan.last] * plan.lastWeight;
        }
        powers[static_cast<std::size_t>(band)] = value;
    }
}

std::span<const float> BandMapper::edges() const noexcept {
    return state_ ? std::span<const float>{state_->edges.data(),
        static_cast<std::size_t>(state_->bands) + 1} : std::span<const float>{};
}

int BandMapper::bandCount() const noexcept {
    return state_ ? state_->bands : 0;
}

} // namespace spectrum
