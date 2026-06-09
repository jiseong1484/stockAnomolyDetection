package com.stock.anomaly.infrastructure.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.anomaly.application.sse.SseService;
import com.stock.anomaly.application.subscription.SubscriptionService;
import com.stock.anomaly.domain.stock.StockTick;
import com.stock.anomaly.infrastructure.persistence.influxdb.InfluxDbRepository;
import com.stock.anomaly.infrastructure.persistence.redis.StockPriceRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaStockTickConsumer {

    private final SseService sseService;
    private final InfluxDbRepository influxDbRepository;
    private final SubscriptionService subscriptionService;
    private final StockPriceRedisRepository stockPriceRedisRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "raw-market-data", groupId = "sse-push-group")
    public void consume(String message) {
        try {
            StockTick tick = objectMapper.readValue(message, StockTick.class);
            log.debug("Consumed stock tick: {}", tick.getTicker());

            // InfluxDB 저장
            influxDbRepository.save(tick);

            // Redis 현재가 업데이트 (MySQL 대신)
            stockPriceRedisRepository.save(tick.getTicker(), tick.getPrice(), tick.getVolume());

            // 지능형 SSE 브로드캐스트
            if (tick.getOwnerEmail() == null) {
                // 공용 종목 -> 모든 접속 유저에게 방송
                sseService.broadcast(tick);
            } else {
                // 개인 종목 -> 해당 API Key를 사용하는 유저들에게만 발송
                String appKey = tick.getOwnerEmail();
                Set<String> targetUsers = subscriptionService.getUsersByAppKey(appKey);
                for (String email : targetUsers) {
                    sseService.sendToUser(email, tick);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process stock tick from Kafka: {}", e.getMessage());
        }
    }
}
