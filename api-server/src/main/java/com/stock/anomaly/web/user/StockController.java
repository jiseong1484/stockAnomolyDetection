package com.stock.anomaly.web.user;

import com.stock.anomaly.application.stock.StockService;
import com.stock.anomaly.domain.stock.StockTick;
import com.stock.anomaly.infrastructure.external.kis.KisWebSocketClient;
import com.stock.anomaly.infrastructure.persistence.influxdb.InfluxDbRepository;
import com.stock.anomaly.infrastructure.security.CustomUserDetails;
import com.stock.anomaly.web.user.dto.OhlcvResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stocks")
@RequiredArgsConstructor
public class StockController {

    private final KisWebSocketClient kisWebSocketClient;
    private final InfluxDbRepository influxDbRepository;
    private final StockService stockService;

    @GetMapping("/search")
    public List<com.stock.anomaly.domain.stock.Stock> searchStocks(
            @RequestParam String query,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return stockService.searchStocks(query, userDetails.getUser());
    }

    @GetMapping("/{ticker}/ohlcv")
    public List<OhlcvResponse> getOhlcv(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String endDate,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return stockService.getOhlcv(ticker, interval, days, endDate, userDetails.getUser());
    }

    @GetMapping("/{ticker}/history")
    public List<StockTick> getStockHistory(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "30") int minutes) {
        return influxDbRepository.findRecentTicks(ticker, minutes);
    }

    @PostMapping("/subscribe")
    public String subscribe(
            @RequestParam String appKey,
            @RequestParam String appSecret,
            @RequestBody List<String> tickers) {
        
        kisWebSocketClient.subscribe(appKey, appSecret, tickers);
        return "Subscription request sent for: " + tickers;
    }
}
