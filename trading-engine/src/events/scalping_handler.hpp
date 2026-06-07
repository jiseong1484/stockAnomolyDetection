#pragma once

#include "event_types.hpp"

#include <chrono>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>

namespace trading {

// ─── Order execution interface ────────────────────────────────────────────────
// Injected as a dependency so the handler can be unit-tested without a live broker.
struct OrderRequest {
    char   ticker[12];
    double price;
    double quantity;
    enum class Side { Buy, Sell } side;
};

using OrderExecutor = std::function<bool(const OrderRequest&)>;

// ─── ScalpingHandler ──────────────────────────────────────────────────────────
// Subscribes to ScalpingEvent on the EventBus.
// When a confirmed scalping signal arrives:
//   1. Re-validates the signal is not stale (within TTL).
//   2. Checks the ticker is not already in a position.
//   3. Submits a buy order immediately.
//   4. Arms an exit watchdog (separate thread via callback) to sell on reversal.
//
// No AI involvement — speed is the priority.

class ScalpingHandler {
public:
    static constexpr int64_t kSignalTTLMs  = 10'000;  // 10 seconds
    static constexpr double  kFixedQty     = 10.0;    // shares per trade (tune later)
    static constexpr double  kExitDropPct  = -1.5;    // exit if price drops 1.5% from entry

    explicit ScalpingHandler(OrderExecutor executor)
        : executor_(std::move(executor)) {}

    // Called from the EventBus scalping dispatch thread.
    void on_event(const ScalpingEvent& event) {
        const int64_t now_ms = now_epoch_ms();

        // Drop stale signals.
        if (now_ms - event.timestamp_ms > kSignalTTLMs) return;

        const std::string ticker(event.ticker);
        {
            std::lock_guard lock(positions_mtx_);
            if (positions_.count(ticker)) return; // already holding
        }

        OrderRequest req{};
        __builtin_memcpy(req.ticker, event.ticker, sizeof(req.ticker));
        req.price    = event.current_price;
        req.quantity = kFixedQty;
        req.side     = OrderRequest::Side::Buy;

        if (executor_(req)) {
            std::lock_guard lock(positions_mtx_);
            positions_[ticker] = PositionInfo{event.current_price, now_ms};
        }
    }

    // Called on each tick from the fast path to check exit condition.
    void check_exit(const char* ticker, double current_price, int64_t timestamp_ms) {
        const std::string key(ticker);
        std::lock_guard lock(positions_mtx_);
        auto it = positions_.find(key);
        if (it == positions_.end()) return;

        const double& entry = it->second.entry_price;
        const double change_pct = (current_price - entry) / entry * 100.0;

        const bool stop_hit  = change_pct <= kExitDropPct;
        const bool ttl_hit   = (timestamp_ms - it->second.entry_ms) > 60'000; // max 1-minute hold

        if (stop_hit || ttl_hit) {
            OrderRequest req{};
            std::strncpy(req.ticker, ticker, sizeof(req.ticker) - 1);
            req.price    = current_price;
            req.quantity = kFixedQty;
            req.side     = OrderRequest::Side::Sell;
            executor_(req);
            positions_.erase(it);
        }
    }

private:
    static int64_t now_epoch_ms() noexcept {
        using namespace std::chrono;
        return duration_cast<milliseconds>(
            system_clock::now().time_since_epoch()).count();
    }

    struct PositionInfo {
        double  entry_price;
        int64_t entry_ms;
    };

    OrderExecutor executor_;
    std::mutex    positions_mtx_;
    std::unordered_map<std::string, PositionInfo> positions_;
};

} // namespace trading
