package com.stock.anomaly.domain.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, String> {
}
