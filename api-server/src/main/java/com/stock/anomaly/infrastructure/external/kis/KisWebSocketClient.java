package com.stock.anomaly.infrastructure.external.kis;

import com.stock.anomaly.domain.stock.StockTick;
import com.stock.anomaly.domain.stock.StockTickPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisWebSocketClient extends TextWebSocketHandler {

    @Value("${kis.websocket.url}")
    private String wsUrl;

    @Value("${kis.system.app-key}")
    private String systemAppKey;

    @Value("${kis.system.app-secret}")
    private String systemAppSecret;

    @Value("${kis.top-tickers}")
    private String topTickersStr;

    private final StockTickPublisher stockTickPublisher;
    private final KisTokenManager kisTokenManager;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private Set<String> topTickers;

    public synchronized void subscribe(String appKey, String appSecret, List<String> tickers) {
        if (topTickers == null) {
            topTickers = new HashSet<>(Arrays.asList(topTickersStr.split(",")));
        }

        List<String> systemTickers = new ArrayList<>();
        List<String> privateTickers = new ArrayList<>();

        for (String ticker : tickers) {
            if (topTickers.contains(ticker)) {
                systemTickers.add(ticker);
            } else {
                privateTickers.add(ticker);
            }
        }

        if (!systemTickers.isEmpty()) {
            performSubscribe(systemAppKey, systemAppSecret, systemTickers);
        }

        if (!privateTickers.isEmpty()) {
            performSubscribe(appKey, appSecret, privateTickers);
        }
    }

    private void performSubscribe(String appKey, String appSecret, List<String> tickers) {
        String approvalKey = kisTokenManager.getApprovalKey(appKey, appSecret);
        if (approvalKey == null) return;

        try {
            WebSocketSession session = sessions.get(appKey);
            if (session == null || !session.isOpen()) {
                StandardWebSocketClient client = new StandardWebSocketClient();
                session = client.execute(this, wsUrl).get();
                // 세션 속성에 appKey 저장하여 어떤 세션에서 온 데이터인지 식별 가능하게 함
                session.getAttributes().put("appKey", appKey);
                sessions.put(appKey, session);
                log.info("Established KIS WS session for appKey: {}", appKey);
            }

            for (String ticker : tickers) {
                String subMsg = String.format(
                    "{\"header\":{\"approval_key\":\"%s\",\"custtype\":\"P\",\"tr_type\":\"1\",\"content-type\":\"utf-8\"},\"body\":{\"input\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"%s\"}}}",
                    approvalKey, ticker
                );
                session.sendMessage(new TextMessage(subMsg));
            }
        } catch (Exception e) {
            log.error("Failed to subscribe for appKey {}: {}", appKey, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        String appKey = (String) session.getAttributes().get("appKey");
        if (appKey != null) {
            sessions.remove(appKey);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        String appKey = (String) session.getAttributes().get("appKey");

        if (payload.startsWith("0") || payload.startsWith("1")) {
            parseAndPublish(payload, appKey);
        }
    }

    private void parseAndPublish(String payload, String appKey) {
        try {
            String[] parts = payload.split("\\|");
            if (parts.length < 4) return;

            String data = parts[3];
            String[] dataParts = data.split("\\^");
            
            if (dataParts.length > 2) {
                String ticker = dataParts[0];
                String price = dataParts[2];
                String volume = dataParts[7];

                // appKey가 시스템 키이면 공용 데이터(ownerEmail=null), 아니면 개인 데이터로 표시
                // 여기서는 간단히 appKey 자체를 owner 필드 대용으로 넘겨서 Consumer가 처리하게 함
                String owner = systemAppKey.equals(appKey) ? null : appKey;

                StockTick tick = StockTick.builder()
                        .ticker(ticker)
                        .price(price)
                        .volume(volume)
                        .timestamp(System.currentTimeMillis())
                        .ownerEmail(owner) // 임시로 appKey를 담아 보냄 (Consumer에서 User 조회용)
                        .build();

                stockTickPublisher.publish(tick);
            }
        } catch (Exception e) {
            log.error("Failed to parse KIS message: {}", e.getMessage());
        }
    }
}
