package com.stock.anomaly.domain.stock;

public interface StockTickPublisher {
    void publish(StockTick tick);
}
