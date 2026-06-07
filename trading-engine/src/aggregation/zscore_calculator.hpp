#pragma once

#include <cmath>
#include <cstddef>
#include <array>

namespace trading {

// Welford's online algorithm for rolling mean and variance.
// Window: last N observations. Used on the 단타(scalping) hot path.
// Not thread-safe — each Ticker gets its own instance on one thread.
template <std::size_t Window>
class ZScoreCalculator {
    static_assert(Window >= 2, "Window must be at least 2");

public:
    // Feed a new volume observation; returns the Z-score of that value.
    double push(double volume) noexcept {
        const std::size_t idx = count_ & kMask;

        if (count_ >= Window) {
            // Remove oldest sample from running stats using Welford reverse step.
            const double old_val = buffer_[idx];
            const double new_mean = mean_ + (volume - old_val) / static_cast<double>(Window);
            m2_ += (volume - old_val) * ((volume - new_mean) + (old_val - mean_));
            mean_ = new_mean;
        } else {
            // Welford forward step (warm-up phase).
            ++count_;
            const double delta  = volume - mean_;
            mean_ += delta / static_cast<double>(count_);
            const double delta2 = volume - mean_;
            m2_  += delta * delta2;
        }

        buffer_[idx] = volume;
        if (count_ >= Window) ++count_; // keep advancing after warmup

        const double variance = (count_ > 1)
            ? m2_ / static_cast<double>(std::min(count_, Window) - 1)
            : 0.0;
        const double stddev = std::sqrt(variance);
        return (stddev > 1e-9) ? (volume - mean_) / stddev : 0.0;
    }

    double mean()   const noexcept { return mean_; }
    double stddev() const noexcept {
        const double var = (count_ > 1)
            ? m2_ / static_cast<double>(std::min(count_, Window) - 1) : 0.0;
        return std::sqrt(var);
    }
    bool warmed_up() const noexcept { return count_ >= Window; }
    void reset() noexcept { mean_ = 0; m2_ = 0; count_ = 0; buffer_.fill(0); }

private:
    static constexpr std::size_t kMask = Window - 1; // works only if Window is power-of-two; else use % Window
    std::array<double, Window> buffer_{};
    double      mean_{0};
    double      m2_{0};
    std::size_t count_{0};
};

// Tracks the price change rate over a rolling N-tick window.
template <std::size_t Window>
class PriceChangeTracker {
    static_assert(Window >= 2);
public:
    // Returns percentage change from the oldest price in the window.
    double push(double price) noexcept {
        const std::size_t idx = head_ % Window;
        const double oldest = buffer_[idx];
        buffer_[idx] = price;
        head_++;

        if (head_ < Window) return 0.0; // not enough data
        return (oldest > 1e-9) ? (price - oldest) / oldest * 100.0 : 0.0;
    }

    void reset() noexcept { head_ = 0; buffer_.fill(0); }

private:
    std::array<double, Window> buffer_{};
    std::size_t                head_{0};
};

} // namespace trading
