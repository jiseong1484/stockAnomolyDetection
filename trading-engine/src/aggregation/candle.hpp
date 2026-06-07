#pragma once

#include <cstdint>
#include <string>

namespace trading {

struct Tick {
    char     ticker[12];    // e.g. "005930" (삼성전자)
    double   price;
    double   volume;
    int64_t  timestamp_ms;  // epoch milliseconds
};

struct Candle {
    char     ticker[12];
    double   open;
    double   high;
    double   low;
    double   close;
    double   volume;
    int64_t  open_time_ms;  // window start
    int64_t  close_time_ms; // window end
    int32_t  tick_count;
    int32_t  window_minutes; // 15 or 60

    void reset(const Tick& first_tick, int32_t window_min) noexcept {
        __builtin_memcpy(ticker, first_tick.ticker, sizeof(ticker));
        open  = high = low = close = first_tick.price;
        volume     = first_tick.volume;
        tick_count = 1;
        window_minutes = window_min;

        // Align open_time to the window boundary.
        const int64_t window_ms = static_cast<int64_t>(window_min) * 60'000;
        open_time_ms  = (first_tick.timestamp_ms / window_ms) * window_ms;
        close_time_ms = open_time_ms + window_ms;
    }

    void update(const Tick& tick) noexcept {
        if (tick.price > high) high = tick.price;
        if (tick.price < low)  low  = tick.price;
        close   = tick.price;
        volume += tick.volume;
        ++tick_count;
    }

    bool is_expired(int64_t timestamp_ms) const noexcept {
        return timestamp_ms >= close_time_ms;
    }
};

} // namespace trading
