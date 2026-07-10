#include "aggregation/zscore_calculator.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <deque>
#include <numeric>
#include <random>

using trading::PriceChangeTracker;
using trading::ZScoreCalculator;

namespace {

double naive_mean(const std::deque<double>& window) {
    return std::accumulate(window.begin(), window.end(), 0.0) / static_cast<double>(window.size());
}

double naive_stddev(const std::deque<double>& window) {
    const double m = naive_mean(window);
    double sum_sq = 0.0;
    for (double v : window) sum_sq += (v - m) * (v - m);
    return std::sqrt(sum_sq / static_cast<double>(window.size() - 1));
}

// Feeds `count` random observations through `calc` and asserts that, once warmed up,
// its running mean/stddev match a naive recomputation over the same trailing window.
// This is the direct regression check for the ring-buffer indexing in
// ZScoreCalculator::push — a bug there produces a mean/stddev that silently drifts
// away from the true trailing window instead of a crash, so parity with a naive
// implementation is the only thing that catches it.
template <std::size_t Window>
void assert_matches_naive_sliding_window(int count, unsigned seed) {
    ZScoreCalculator<Window> calc;
    std::deque<double> window;
    std::mt19937 rng(seed);
    std::uniform_real_distribution<double> dist(1.0, 1000.0);

    for (int i = 0; i < count; ++i) {
        const double v = dist(rng);
        calc.push(v);

        window.push_back(v);
        if (window.size() > Window) window.pop_front();

        if (calc.warmed_up()) {
            ASSERT_EQ(window.size(), Window);
            EXPECT_NEAR(calc.mean(), naive_mean(window), 1e-6)
                << "iteration " << i << " window=" << Window;
            EXPECT_NEAR(calc.stddev(), naive_stddev(window), 1e-6)
                << "iteration " << i << " window=" << Window;
        }
    }
}

} // namespace

TEST(ZScoreCalculator, WarmUpPhase_NotWarmedUpBeforeWindowFills) {
    ZScoreCalculator<4> calc;
    calc.push(1.0);
    calc.push(2.0);
    calc.push(3.0);
    EXPECT_FALSE(calc.warmed_up());
    calc.push(4.0);
    EXPECT_TRUE(calc.warmed_up());
}

TEST(ZScoreCalculator, WarmUpPhase_MeanAndStddevMatchKnownValues) {
    ZScoreCalculator<4> calc;
    for (double v : {2.0, 4.0, 4.0, 4.0}) calc.push(v);

    // mean = 3.5, sample variance = ((1.5^2)+(0.5^2)*3)/3 = 1.0 -> stddev = 1.0
    EXPECT_NEAR(calc.mean(), 3.5, 1e-9);
    EXPECT_NEAR(calc.stddev(), 1.0, 1e-9);
}

TEST(ZScoreCalculator, SlidingWindow_PowerOfTwoWindowMatchesNaiveComputation) {
    assert_matches_naive_sliding_window<8>(500, /*seed=*/1);
}

// Production uses ZScoreCalculator<20> (see tick_aggregator.hpp) — 20 is NOT a
// power of two, so the buffer's ring-index arithmetic must not rely on masking.
TEST(ZScoreCalculator, SlidingWindow_ProductionWindowSizeMatchesNaiveComputation) {
    assert_matches_naive_sliding_window<20>(500, /*seed=*/2);
}

TEST(ZScoreCalculator, Reset_ClearsAccumulatedState) {
    ZScoreCalculator<4> calc;
    for (double v : {10.0, 20.0, 30.0, 40.0}) calc.push(v);
    ASSERT_TRUE(calc.warmed_up());

    calc.reset();

    EXPECT_FALSE(calc.warmed_up());
    EXPECT_EQ(calc.mean(), 0.0);
    EXPECT_EQ(calc.stddev(), 0.0);
}

TEST(PriceChangeTracker, ReturnsZeroWhileWindowNotFull) {
    PriceChangeTracker<3> tracker;
    EXPECT_EQ(tracker.push(100.0), 0.0);
    EXPECT_EQ(tracker.push(110.0), 0.0);
}

TEST(PriceChangeTracker, ComputesPercentChangeFromOldestInWindow) {
    PriceChangeTracker<3> tracker;
    tracker.push(100.0);
    tracker.push(110.0);
    tracker.push(120.0); // window now full: [100, 110, 120]

    // next push evicts 100 (oldest) and compares 130 against it
    const double pct = tracker.push(130.0);
    EXPECT_NEAR(pct, 30.0, 1e-9);
}

TEST(PriceChangeTracker, Reset_ClearsWindow) {
    PriceChangeTracker<3> tracker;
    tracker.push(100.0);
    tracker.push(110.0);
    tracker.push(120.0);

    tracker.reset();

    EXPECT_EQ(tracker.push(50.0), 0.0);
}
