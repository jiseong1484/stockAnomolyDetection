package com.stock.anomaly.infrastructure.external.kis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenManager {

    @Value("${kis.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private String accessToken;

    public String getAccessToken(String appKey, String appSecret) {
        // ... (existing code)
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
            log.error("Failed to issue KIS Approval Key: {}", e.getMessage());
        }
        return null;
    }
}
