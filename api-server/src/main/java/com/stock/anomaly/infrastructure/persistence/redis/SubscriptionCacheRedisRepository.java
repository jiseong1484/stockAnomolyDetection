package com.stock.anomaly.infrastructure.persistence.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class SubscriptionCacheRedisRepository {

    private static final String KEY_PREFIX = "user:subscriptions:";

    private final StringRedisTemplate redisTemplate;

    public Optional<List<String>> find(Long userId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Arrays.asList(value.split(",")));
    }

    public void save(Long userId, List<String> tickers) {
        if (tickers.isEmpty()) {
            redisTemplate.delete(KEY_PREFIX + userId);
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, String.join(",", tickers));
        log.debug("[SubscriptionCache] Cached {} tickers for userId={}", tickers.size(), userId);
    }

    public void evict(Long userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
        log.debug("[SubscriptionCache] Evicted cache for userId={}", userId);
    }
}
