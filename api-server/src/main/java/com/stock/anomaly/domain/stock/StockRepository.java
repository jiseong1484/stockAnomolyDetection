package com.stock.anomaly.domain.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Set;

public interface StockRepository extends JpaRepository<Stock, String> {
    List<Stock> findByNameContaining(String name);
    List<Stock> findByTickerStartingWith(String ticker);

    @Query("SELECT s.ticker FROM Stock s")
    Set<String> findAllTickers();
}
