package com.stock.anomaly.infrastructure.external.kis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenManager {

    private static final String TOKEN_KEY_PREFIX = "kis:token:";
    // 만료 5분 전에 미리 갱신
    private static final long TOKEN_EXPIRY_BUFFER_SEC = 5 * 60L;

    @Value("${kis.api.url}")
    private String apiUrl;

    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    public String getAccessToken(String appKey, String appSecret) {
        String redisKey = TOKEN_KEY_PREFIX + appKey;

        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            return cached;
        }

        String url = apiUrl + "/oauth2/tokenP";

        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", appKey);
        body.put("appsecret", appSecret);

        try {
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
            if (response != null && response.containsKey("access_token")) {
                String token = (String) response.get("access_token");
                long expiresIn = response.containsKey("expires_in")
                        ? ((Number) response.get("expires_in")).longValue()
                        : 86400L;

                long ttlSec = Math.max(expiresIn - TOKEN_EXPIRY_BUFFER_SEC, 60L);
                redisTemplate.opsForValue().set(redisKey, token, Duration.ofSeconds(ttlSec));
                log.info("[KisTokenManager] Token cached in Redis: key={}, ttl={}s", redisKey, ttlSec);
                return token;
            }
        } catch (Exception e) {
            log.error("[KisTokenManager] Failed to issue KIS Access Token: {}", e.getMessage());
        }
        return null;
    }

    public String getApprovalKey(String appKey, String appSecret) {
        String url = apiUrl + "/oauth2/Approval";

        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", appKey);
        body.put("secretkey", appSecret);

        try {
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
            if (response != null && response.containsKey("approval_key")) {
                return (String) response.get("approval_key");
            }
        } catch (Exception e) {
            log.error("[KisTokenManager] Failed to issue KIS Approval Key: {}", e.getMessage());
        }
        return null;
    }
}
