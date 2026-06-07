#pragma once

#include <cstdint>
#include <string>

namespace trading {

// ─── Scalping event ─────────────────────────────────────────────────────────
// Emitted by the scalping (단타) path when Z-score > 5σ AND price change > 3%.
// The handler immediately attempts order execution — no AI involved.
struct ScalpingEvent {
    char     ticker[12];
    double   zscore;
    double   price_change_pct;
    double   current_price;
    int64_t  timestamp_ms;
};

// ─── Swing event ─────────────────────────────────────────────────────────────
// Emitted by either:
//   (a) TickAggregator swing path — when a 15m/1h candle closes (no ai_signal yet)
//   (b) KafkaConsumer AI path    — when ai-signals topic has a result (ai_signal populated)
enum class SwingState {
    CandleClosed,   // candle is ready; waiting for AI
    AIConfirmed,    // AI has responded; handler decides whether to execute
    TTLExpired,     // ARMED state timed out before AI responded
};

struct AISignal {
    double  bull_probability;
    double  bear_probability;
    std::string direction;     // "BUY_READY" | "SELL_READY" | "NEUTRAL"
    int64_t candle_close_time; // links back to the candle this signal is for
};

struct SwingEvent {
    SwingState state;
    char       ticker[12];
    double     open;
    double     high;
    double     low;
    double     close;
    double     volume;
    int64_t    candle_close_time_ms;
    int32_t    window_minutes;
    AISignal   ai_signal;   // valid only when state == AIConfirmed
    int64_t    timestamp_ms;
};

} // namespace trading
