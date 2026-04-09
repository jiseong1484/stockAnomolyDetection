package com.stock.anomaly.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.anomaly.domain.stock.StockTick;
import com.stock.anomaly.domain.stock.StockTickPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaStockTickPublisher implements StockTickPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TOPIC = "raw-stock-prices";

    @Override
    public void publish(StockTick tick) {
        try {
            String json = objectMapper.writeValueAsString(tick);
            kafkaTemplate.send(TOPIC, tick.getTicker(), json);
        } catch (Exception e) {
            log.error("Failed to publish stock tick to Kafka: {}", e.getMessage());
        }
    }
}
