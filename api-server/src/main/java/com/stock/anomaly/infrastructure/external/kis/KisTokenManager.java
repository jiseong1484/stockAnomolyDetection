package com.stock.anomaly.infrastructure.external.kis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenManager {

    @Value("${kis.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, String> tokenCache = new ConcurrentHashMap<>();
    private final Map<String, Long> tokenExpiry = new ConcurrentHashMap<>();
    // 만료 5분 전에 미리 갱신
    private static final long TOKEN_EXPIRY_BUFFER_MS = 5 * 60 * 1000L;

    //REST API용 토큰 발급
    public String getAccessToken(String appKey, String appSecret) {
        Long expiry = tokenExpiry.get(appKey);
        if (expiry != null && System.currentTimeMillis() < expiry && tokenCache.containsKey(appKey)) {
            return tokenCache.get(appKey);
        }

        tokenCache.remove(appKey);
        tokenExpiry.remove(appKey);

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
                tokenCache.put(appKey, token);
                tokenExpiry.put(appKey, System.currentTimeMillis() + expiresIn * 1000L - TOKEN_EXPIRY_BUFFER_MS);
                return token;
            }
        } catch (Exception e) {
            log.error("Failed to issue KIS Access Token: {}", e.getMessage());
        }
        return null;
    }

    //웹소켓 연결용 key 발급
    public String getApprovalKey(String appKey, String appSecret) {
        String url = apiUrl + "/oauth2/Approval";
        
        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", appKey);
        body.put("secretkey", appSecret);  // /oauth2/Approval uses "secretkey", not "appsecret"

        try {
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
            if (response != null && response.containsKey("approval_key")) {
                return (String) response.get("approval_key");
            }
        } catch (Exception e) {
            log.error("Failed to issue KIS Approval Key: {}", e.getMessage());
        }
        return null;
    }
}
