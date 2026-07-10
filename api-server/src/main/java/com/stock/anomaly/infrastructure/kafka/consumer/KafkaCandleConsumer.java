package com.stock.anomaly.infrastructure.kafka.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.anomaly.infrastructure.persistence.influxdb.InfluxDbRepository;
import com.stock.anomaly.web.user.dto.OhlcvResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// trading-engine이 15m/1h 캔들이 닫힐 때마다 발행하는 토픽을 InfluxDB에 적재한다.
// AI 신호 서비스(ai-signal-service)도 같은 토픽을 별도 컨슈머 그룹으로 구독하므로
// 여기서는 순수 저장(축적)만 담당한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaCandleConsumer {

    private final InfluxDbRepository influxDbRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "candle-data", groupId = "candle-persist-group")
    public void consume(String message) {
        try {
            JsonNode j = objectMapper.readTree(message);
            String ticker = j.get("ticker").asText();
            int windowMinutes = j.get("window_minutes").asInt();
            String interval = (windowMinutes == 60) ? "1h" : "15m";

            OhlcvResponse ohlcv = OhlcvResponse.builder()
                    .time(j.get("close_time_ms").asLong() / 1000)
                    .open(j.get("open").asDouble())
                    .high(j.get("high").asDouble())
                    .low(j.get("low").asDouble())
                    .close(j.get("close").asDouble())
                    .volume(j.get("volume").asLong())
                    .build();

            influxDbRepository.saveOhlcv(ticker, interval, ohlcv);
            log.debug("Persisted candle: ticker={} interval={} close_time={}", ticker, interval, ohlcv.getTime());
        } catch (Exception e) {
            log.error("Failed to process candle from Kafka: {}", e.getMessage());
        }
    }
}
