#pragma once

#include "candle.hpp"
#include "zscore_calculator.hpp"
#include "../core/lock_free_queue.hpp"
#include "../core/object_pool.hpp"

#include <array>
#include <atomic>
#include <functional>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <thread>
#include <unordered_map>

namespace trading {

// ─── Thresholds (tunable via config later) ────────────────────────────────────
inline constexpr double kScalpingZScoreThreshold   = 5.0;  // σ — HIGH, triggers order
inline constexpr double kScalpingPriceChangeThresh  = 3.0;  // %
inline constexpr double kAlertMediumZThreshold      = 3.0;  // σ — MEDIUM, alert only
inline constexpr double kAlertMediumPriceThresh     = 1.5;  // %
inline constexpr double kAlertLowZThreshold         = 2.0;  // σ — LOW, volume anomaly
inline constexpr double kSwingZScoreLow             = 2.0;
inline constexpr double kSwingZScoreHigh            = 5.0;

// ─── Signals emitted by the aggregator ───────────────────────────────────────
enum class SignalType { Scalping, Swing, AlertLow, AlertMedium };

struct AggregatorSignal {
    SignalType type;
    char       ticker[12];
    double     zscore;
    double     price_change_pct;
    Candle     candle;           // populated only for Swing signals
    int64_t    timestamp_ms;
};

// ─── Per-ticker state for the swing (Object Pool) path ───────────────────────
struct SwingTickerState {
    Candle candle_15m;
    Candle candle_1h;
    ZScoreCalculator<20>    volume_zscore;  // 20-period rolling
    PriceChangeTracker<20>  price_change;
    std::mutex              mtx;
    bool                    active_15m{false};
    bool                    active_1h{false};
};

// ─── TickAggregator ──────────────────────────────────────────────────────────
// Task 1 Option C: dual-path aggregation.
//
//  Scalping path  — Lock-free MPSC queue → dedicated drainer thread.
//                   Emits ScalpingSignal when Z-score > 5σ AND price change > 3%.
//
//  Swing path     — Object-pooled SwingTickerState per ticker,
//                   sharded by mutex per state object.
//                   Emits SwingSignal when a 15m/1h candle closes.
//
// Both paths call the same on_signal_ callback from their respective threads.

class TickAggregator {
public:
    static constexpr std::size_t kQueueCapacity  = 65536; // 64K ticks in-flight
    static constexpr std::size_t kStatePoolSize  = 512;   // max concurrent tickers

    using SignalCallback = std::function<void(AggregatorSignal)>;

    explicit TickAggregator(SignalCallback on_signal)
        : on_signal_(std::move(on_signal)) {}

    ~TickAggregator() { stop(); }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    void start() {
        running_.store(true, std::memory_order_release);
        scalping_thread_ = std::thread(&TickAggregator::scalping_drain_loop, this);
    }

    void stop() {
        running_.store(false, std::memory_order_release);
        if (scalping_thread_.joinable()) scalping_thread_.join();
    }

    // ── Ingestion (called from Kafka consumer threads) ────────────────────────
    void push_tick(const Tick& tick) {
        // Scalping path: non-blocking push into lock-free queue.
        scalping_queue_.push(tick);

        // Swing path: update per-ticker candle state (sharded mutex).
        update_swing_state(tick);
    }

private:
    // ── Scalping path ─────────────────────────────────────────────────────────
    void scalping_drain_loop() {
        // Per-ticker stats live on this thread — no locking needed.
        std::unordered_map<std::string,
            std::pair<ZScoreCalculator<20>, PriceChangeTracker<20>>> stats;

        while (running_.load(std::memory_order_acquire)) {
            auto opt = scalping_queue_.pop();
            if (!opt) {
                // Yield to avoid burning a full core when idle.
                std::this_thread::yield();
                continue;
            }
            const Tick& tick = *opt;
            auto& [zscore_calc, price_tracker] = stats[tick.ticker];

            const double z   = zscore_calc.push(tick.volume);
            const double pct = price_tracker.push(tick.price);

            // Log Z-score periodically
            static long drain_count = 0;
            if (++drain_count % 20 == 0)
                fprintf(stdout, "[zscore] ticker=%-8s  z=%+.2f  pct=%+.2f%%  warmed=%s\n",
                        tick.ticker, z, pct, zscore_calc.warmed_up() ? "yes" : "no");

            if (!zscore_calc.warmed_up()) continue;

            AggregatorSignal sig{};
            sig.zscore           = z;
            sig.price_change_pct = pct;
            sig.timestamp_ms     = tick.timestamp_ms;
            sig.candle.close     = tick.price;
            __builtin_memcpy(sig.ticker, tick.ticker, sizeof(sig.ticker));

            const double abs_pct = pct < 0 ? -pct : pct;

            if (z >= kScalpingZScoreThreshold && abs_pct >= kScalpingPriceChangeThresh) {
                sig.type = SignalType::Scalping;
                fprintf(stdout, "[SIGNAL] HIGH   ticker=%-8s  z=%.2f  pct=%+.2f%%\n",
                        tick.ticker, z, pct);
                on_signal_(sig);
            } else if (z >= kAlertMediumZThreshold && abs_pct >= kAlertMediumPriceThresh) {
                sig.type = SignalType::AlertMedium;
                fprintf(stdout, "[ALERT]  MEDIUM ticker=%-8s  z=%.2f  pct=%+.2f%%\n",
                        tick.ticker, z, pct);
                on_signal_(sig);
            } else if (z >= kAlertLowZThreshold) {
                sig.type = SignalType::AlertLow;
                fprintf(stdout, "[ALERT]  LOW    ticker=%-8s  z=%.2f\n",
                        tick.ticker, z);
                on_signal_(sig);
            }
        }
    }

    // ── Swing path ────────────────────────────────────────────────────────────
    void update_swing_state(const Tick& tick) {
        SwingTickerState* state = get_or_create_state(tick.ticker);
        if (!state) return; // pool exhausted — drop

        std::lock_guard<std::mutex> lock(state->mtx);

        if (!state->active_15m) {
            state->candle_15m.reset(tick, 15);
            state->active_15m = true;
        } else if (state->candle_15m.is_expired(tick.timestamp_ms)) {
            emit_swing_candle(state->candle_15m);
            state->candle_15m.reset(tick, 15);
        } else {
            state->candle_15m.update(tick);
        }

        if (!state->active_1h) {
            state->candle_1h.reset(tick, 60);
            state->active_1h = true;
        } else if (state->candle_1h.is_expired(tick.timestamp_ms)) {
            emit_swing_candle(state->candle_1h);
            state->candle_1h.reset(tick, 60);
        } else {
            state->candle_1h.update(tick);
        }
    }

    void emit_swing_candle(const Candle& candle) {
        AggregatorSignal sig{};
        sig.type   = SignalType::Swing;
        sig.candle = candle;
        sig.timestamp_ms = candle.close_time_ms;
        __builtin_memcpy(sig.ticker, candle.ticker, sizeof(sig.ticker));
        on_signal_(sig);
    }

    // ── Ticker state registry (swing path) ───────────────────────────────────
    SwingTickerState* get_or_create_state(const char* ticker) {
        const std::string key(ticker);
        {
            std::shared_lock lock(registry_mtx_);
            auto it = registry_.find(key);
            if (it != registry_.end()) return it->second;
        }
        std::unique_lock lock(registry_mtx_);
        auto it = registry_.find(key);
        if (it != registry_.end()) return it->second;

        SwingTickerState* state = state_pool_.acquire();
        if (!state) return nullptr;
        new (state) SwingTickerState{}; // placement-new to initialize
        registry_[key] = state;
        return state;
    }

    // ── Members ───────────────────────────────────────────────────────────────
    SignalCallback on_signal_;

    // Scalping path
    MPSCQueue<Tick, kQueueCapacity> scalping_queue_;
    std::thread                     scalping_thread_;
    std::atomic<bool>               running_{false};

    // Swing path
    ObjectPool<SwingTickerState, kStatePoolSize> state_pool_;
    std::unordered_map<std::string, SwingTickerState*> registry_;
    std::shared_mutex registry_mtx_;
};

} // namespace trading
