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

    private final StockTickPublisher stockTickPublisher;
    private final KisTokenManager kisTokenManager;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public synchronized void subscribe(String appKey, String appSecret, List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return;

        // 시스템 키 로직 제거 - 모든 구독은 인자로 받은 appKey/appSecret (사용자 키) 사용
        performSubscribe(appKey, appSecret, tickers);
    }

    public synchronized void unsubscribe(String appKey, String appSecret, List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return;

        performUnsubscribe(appKey, appSecret, tickers);
    }

    private void performSubscribe(String appKey, String appSecret, List<String> tickers) {
        String approvalKey = kisTokenManager.getApprovalKey(appKey, appSecret);
        if (approvalKey == null) return;

        try {
            WebSocketSession session = sessions.get(appKey);
            if (session == null || !session.isOpen()) {
                StandardWebSocketClient client = new StandardWebSocketClient();
                session = client.execute(this, wsUrl).get();
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

    private void performUnsubscribe(String appKey, String appSecret, List<String> tickers) {
        WebSocketSession session = sessions.get(appKey);
        if (session == null || !session.isOpen()) {
            log.warn("No active KIS WS session for appKey {} — nothing to unsubscribe", appKey);
            return;
        }

        String approvalKey = kisTokenManager.getApprovalKey(appKey, appSecret);
        if (approvalKey == null) return;

        try {
            for (String ticker : tickers) {
                String unsubMsg = String.format(
                    "{\"header\":{\"approval_key\":\"%s\",\"custtype\":\"P\",\"tr_type\":\"2\",\"content-type\":\"utf-8\"},\"body\":{\"input\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"%s\"}}}",
                    approvalKey, ticker
                );
                session.sendMessage(new TextMessage(unsubMsg));
            }
        } catch (Exception e) {
            log.error("Failed to unsubscribe for appKey {}: {}", appKey, e.getMessage());
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

            // H0STCNT0 필드 순서:
            // [0] MKSC_SHRN_ISCD  [1] STCK_CNTG_HOUR  [2] STCK_PRPR(현재가)
            // [3] PRDY_VRSS_SIGN  [4] PRDY_VRSS(전일대비금액)  [5] PRDY_CTRT(전일대비율)
            // [13] ACML_VOL(누적거래량)
            if (dataParts.length > 13) {
                String ticker = dataParts[0];
                String price  = dataParts[2];
                String volume = dataParts[13];

                String sign      = dataParts[3]; // 1=상한 2=상승 3=보합 4=하락 5=하한
                String prdyVrss  = dataParts[4]; // 전일 대비 금액 (절댓값)
                String prdyCtrt  = dataParts[5]; // 전일 대비율 (절댓값)

                boolean negative = "4".equals(sign) || "5".equals(sign);
                boolean zero     = "3".equals(sign);
                String changeAmount = zero ? "0" : (negative ? "-" : "") + prdyVrss;
                String changeRate   = zero ? "0" : (negative ? "-" : "") + prdyCtrt;

                StockTick tick = StockTick.builder()
                        .ticker(ticker)
                        .price(price)
                        .volume(volume)
                        .timestamp(System.currentTimeMillis())
                        .ownerEmail(appKey)
                        .changeAmount(changeAmount)
                        .changeRate(changeRate)
                        .build();

                stockTickPublisher.publish(tick);
            }
        } catch (Exception e) {
            log.error("Failed to parse KIS message: {}", e.getMessage());
        }
    }
}
