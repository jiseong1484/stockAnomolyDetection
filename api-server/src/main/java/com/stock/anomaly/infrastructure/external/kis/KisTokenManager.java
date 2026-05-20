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

    public String getAccessToken(String appKey, String appSecret) {
        if (tokenCache.containsKey(appKey)) {
            return tokenCache.get(appKey);
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
                tokenCache.put(appKey, token);
                return token;
            }
        } catch (Exception e) {
            log.error("Failed to issue KIS Access Token: {}", e.getMessage());
        }
        return null;
    }

    public String getApprovalKey(String appKey, String appSecret) {
        String url = apiUrl + "/oauth2/Approval";
        
        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "client_credentials");
        body.put("appkey", appKey);
        body.put("appsecret", appSecret);

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
