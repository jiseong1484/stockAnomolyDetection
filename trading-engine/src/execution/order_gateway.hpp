#pragma once

#include "../events/scalping_handler.hpp"

#include <functional>
#include <string>
#include <cstdio>

namespace trading {

// Placeholder order gateway — replace with actual KIS (한국투자증권) REST/WebSocket
// call when broker integration is wired up.
// Returns true on success, false on rejection.
inline OrderExecutor make_order_executor(const std::string& api_base_url,
                                         const std::string& access_token) {
    return [api_base_url, access_token](const OrderRequest& req) -> bool {
        const char* side_str = (req.side == OrderRequest::Side::Buy) ? "BUY" : "SELL";

        // TODO: replace with actual HTTP call via libcurl or Boost.Beast
        fprintf(stdout,
                "[order] %s  ticker=%.12s  price=%.2f  qty=%.0f  (stub — not executed)\n",
                side_str, req.ticker, req.price, req.quantity);

        // Stub always succeeds.
        return true;
    };
}

} // namespace trading
