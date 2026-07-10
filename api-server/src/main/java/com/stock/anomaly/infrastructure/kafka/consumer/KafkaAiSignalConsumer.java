package com.stock.anomaly.infrastructure.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.anomaly.application.sse.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

// ai-signal-service가 발행하는 bull/bear 확률 신호.
// trading-engine(자동매매 판단, 별도 컨슈머 그룹)과 같은 토픽을 공유해 프론트엔드 노출용으로만 소비한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaAiSignalConsumer {

    private final SseService sseService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "ai-signals", groupId = "ai-signal-ui-group")
    public void consume(String message) {
        try {
            Map<String, Object> signal = objectMapper.readValue(message, Map.class);
            log.debug("AI signal: ticker={} direction={} bull={}",
                    signal.get("ticker"), signal.get("direction"), signal.get("bull_probability"));

            sseService.broadcastAiSignal(signal);
        } catch (Exception e) {
            log.error("Failed to process AI signal: {}", e.getMessage());
        }
    }
}
