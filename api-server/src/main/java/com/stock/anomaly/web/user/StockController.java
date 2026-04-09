package com.stock.anomaly.web.user;

import com.stock.anomaly.domain.stock.StockTick;
import com.stock.anomaly.infrastructure.external.kis.KisWebSocketClient;
import com.stock.anomaly.infrastructure.persistence.influxdb.InfluxDbRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stocks")
@RequiredArgsConstructor
public class StockController {

    private final KisWebSocketClient kisWebSocketClient;
    private final InfluxDbRepository influxDbRepository;

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
