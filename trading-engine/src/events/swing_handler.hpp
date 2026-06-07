#pragma once

#include "event_types.hpp"
#include "scalping_handler.hpp" // reuse OrderExecutor / OrderRequest

#include <chrono>
#include <mutex>
#include <string>
#include <unordered_map>

namespace trading {

// ─── SwingHandler ─────────────────────────────────────────────────────────────
// Subscribes to SwingEvent on the EventBus.
//
// State machine per ticker:
//
//   IDLE ──(CandleClosed)──→ ARMED ──(AIConfirmed + direction OK)──→ EXECUTING
//                               └──(TTLExpired)──────────────────→ IDLE
//
// When ARMED, the handler stores the candle close time so the incoming AI signal
// can be matched to the correct candle (prevents stale AI results from triggering
// an entry on a different bar).

class SwingHandler {
public:
    // TTL: how long to stay ARMED while waiting for AI response.
    static constexpr int64_t kArmedTTLMs      = 5 * 60'000LL; // 5 minutes
    static constexpr double  kMinBullProb     = 0.65;           // AI confidence threshold
    static constexpr double  kFixedQty        = 50.0;
    static constexpr double  kStopLossPct     = -3.0;           // -3% stop
    static constexpr double  kTakeProfitPct   =  6.0;           // +6% target

    explicit SwingHandler(OrderExecutor executor)
        : executor_(std::move(executor)) {}

    // Called from the EventBus swing dispatch thread.
    void on_event(const SwingEvent& event) {
        const std::string ticker(event.ticker);

        switch (event.state) {
        case SwingState::CandleClosed:
            on_candle_closed(ticker, event);
            break;
        case SwingState::AIConfirmed:
            on_ai_confirmed(ticker, event);
            break;
        case SwingState::TTLExpired:
            on_ttl_expired(ticker);
            break;
        }
    }

private:
    enum class State { Idle, Armed };

    struct TickerState {
        State   state{State::Idle};
        int64_t armed_at_ms{0};
        int64_t candle_close_time_ms{0}; // which candle armed this ticker
        double  entry_price{0};
        int64_t entry_ms{0};
        bool    in_position{false};
    };

    // ── CandleClosed: candle just closed, transition to ARMED ────────────────
    void on_candle_closed(const std::string& ticker, const SwingEvent& event) {
        std::lock_guard lock(states_mtx_);
        auto& ts = states_[ticker];
        if (ts.in_position) return; // already in a trade

        ts.state               = State::Armed;
        ts.armed_at_ms         = now_epoch_ms();
        ts.candle_close_time_ms = event.candle_close_time_ms;
    }

    // ── AIConfirmed: AI signal received, check if it matches armed candle ────
    void on_ai_confirmed(const std::string& ticker, const SwingEvent& event) {
        std::lock_guard lock(states_mtx_);
        auto it = states_.find(ticker);
        if (it == states_.end()) return;

        TickerState& ts = it->second;
        if (ts.state != State::Armed || ts.in_position) return;

        // Guard: discard if AI signal is for a different (older) candle.
        if (event.ai_signal.candle_close_time != ts.candle_close_time_ms) return;

        // Guard: discard if ARMED window has already expired.
        if (now_epoch_ms() - ts.armed_at_ms > kArmedTTLMs) {
            ts.state = State::Idle;
            return;
        }

        // Require strong bull conviction.
        if (event.ai_signal.direction != "BUY_READY" ||
            event.ai_signal.bull_probability < kMinBullProb) {
            ts.state = State::Idle;
            return;
        }

        OrderRequest req{};
        std::strncpy(req.ticker, ticker.c_str(), sizeof(req.ticker) - 1);
        req.price    = event.close;
        req.quantity = kFixedQty;
        req.side     = OrderRequest::Side::Buy;

        if (executor_(req)) {
            ts.state      = State::Idle;
            ts.in_position = true;
            ts.entry_price = event.close;
            ts.entry_ms    = now_epoch_ms();
        }
    }

    // ── TTLExpired: AI never responded in time ────────────────────────────────
    void on_ttl_expired(const std::string& ticker) {
        std::lock_guard lock(states_mtx_);
        auto it = states_.find(ticker);
        if (it != states_.end())
            it->second.state = State::Idle;
    }

public:
    // Called on each candle close (swing frequency) to check open positions.
    void check_exit(const char* ticker, double current_price, int64_t /*timestamp_ms*/) {
        const std::string key(ticker);
        std::lock_guard lock(states_mtx_);
        auto it = states_.find(key);
        if (it == states_.end() || !it->second.in_position) return;

        TickerState& ts = it->second;
        const double change_pct = (current_price - ts.entry_price) / ts.entry_price * 100.0;

        const bool stop_hit   = change_pct <= kStopLossPct;
        const bool target_hit = change_pct >= kTakeProfitPct;

        if (stop_hit || target_hit) {
            OrderRequest req{};
            std::strncpy(req.ticker, ticker, sizeof(req.ticker) - 1);
            req.price    = current_price;
            req.quantity = kFixedQty;
            req.side     = OrderRequest::Side::Sell;
            executor_(req);
            ts.in_position = false;
        }
    }

private:
    static int64_t now_epoch_ms() noexcept {
        using namespace std::chrono;
        return duration_cast<milliseconds>(
            system_clock::now().time_since_epoch()).count();
    }

    OrderExecutor executor_;
    std::mutex    states_mtx_;
    std::unordered_map<std::string, TickerState> states_;
};

} // namespace trading
