package com.stock.anomaly.infrastructure.persistence.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
@RequiredArgsConstructor
public class StockPriceRedisRepository {

    private static final String KEY_PREFIX = "stock:price:";

    private final StringRedisTemplate redisTemplate;

    public void save(String ticker, String price, String volume) {
        redisTemplate.opsForHash().putAll(KEY_PREFIX + ticker, Map.of(
                "price", price != null ? price : "0",
                "volume", volume != null ? volume : "0"
        ));
    }

    public String getPrice(String ticker) {
        Object value = redisTemplate.opsForHash().get(KEY_PREFIX + ticker, "price");
        return value != null ? value.toString() : "0";
    }

    public String getVolume(String ticker) {
        Object value = redisTemplate.opsForHash().get(KEY_PREFIX + ticker, "volume");
        return value != null ? value.toString() : "0";
    }
}
