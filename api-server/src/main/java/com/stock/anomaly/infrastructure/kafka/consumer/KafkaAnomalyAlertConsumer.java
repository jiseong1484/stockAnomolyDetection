package com.stock.anomaly.infrastructure.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.anomaly.application.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaAnomalyAlertConsumer {

    private final SseService sseService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "anomaly-alerts", groupId = "anomaly-alert-group")
    public void consume(String message) {
        try {
            Map<String, Object> alert = objectMapper.readValue(message, Map.class);
            log.debug("Anomaly alert: ticker={} severity={} z={}",
                    alert.get("ticker"), alert.get("severity"), alert.get("zscore"));

            sseService.broadcastAnomaly(alert);
        } catch (Exception e) {
            log.error("Failed to process anomaly alert: {}", e.getMessage());
        }
    }
}
