package com.stock.anomaly.application.subscription;

import com.stock.anomaly.domain.stock.Stock;
import com.stock.anomaly.domain.stock.StockRepository;
import com.stock.anomaly.domain.stock.StockTick;
import com.stock.anomaly.domain.stock.StockTickPublisher;
import com.stock.anomaly.domain.subscription.Subscription;
import com.stock.anomaly.domain.subscription.SubscriptionRepository;
import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.infrastructure.external.kis.KisTokenManager;
import com.stock.anomaly.infrastructure.external.kis.KisWebSocketClient;
import com.stock.anomaly.web.subscription.dto.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {

    @Value("${kis.api.url}")
    private String apiUrl;

    private final SubscriptionRepository subscriptionRepository;
    private final StockRepository stockRepository;
    private final KisWebSocketClient kisWebSocketClient;
    private final KisTokenManager kisTokenManager;
    private final StockTickPublisher stockTickPublisher;
    private final RestTemplate restTemplate = new RestTemplate();

    // key: appKey, value: Set of userEmails
    private final Map<String, Set<String>> appKeyToUsers = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void initSubscriptions() {
        log.info("Initializing KIS WebSocket subscriptions for all users...");
        List<Subscription> allSubscriptions = subscriptionRepository.findAll();

        Map<User, List<String>> userTickersMap = allSubscriptions.stream()
                .collect(Collectors.groupingBy(
                        Subscription::getUser,
                        Collectors.mapping(Subscription::getTicker, Collectors.toList())
                ));

        userTickersMap.forEach((user, tickers) -> {
            if (!tickers.isEmpty()) {
                registerAppKey(user.getKisApiKey(), user.getEmail());
                log.info("Re-subscribing tickers for user {}: {}", user.getEmail(), tickers);
                kisWebSocketClient.subscribe(user.getKisApiKey(), user.getKisSecretKey(), tickers);
                
                // 서버 재시작 시에도 초기 가격 정보 갱신 시도
                for (String ticker : tickers) {
                    fetchInitialPrice(user, ticker);
                }
            }
        });
    }

    public void subscribe(User user, String ticker) {
        if (subscriptionRepository.existsByUserAndTicker(user, ticker)) {
            log.info("Ticker {} already subscribed for user {}", ticker, user.getEmail());
            return;
        }

        if (!stockRepository.existsById(ticker)) {
            Stock newStock = Stock.builder()
                    .ticker(ticker)
                    .name("주식 " + ticker)
                    .build();
            stockRepository.save(newStock);
        }

        Subscription subscription = Subscription.builder()
                .user(user)
                .ticker(ticker)
                .build();
        subscriptionRepository.save(subscription);

        registerAppKey(user.getKisApiKey(), user.getEmail());
        
        // 1. 초기 가격 정보 가져오기 (REST API)
        fetchInitialPrice(user, ticker);
        
        // 2. 실시간 구독 시작 (WebSocket)
        kisWebSocketClient.subscribe(user.getKisApiKey(), user.getKisSecretKey(), Collections.singletonList(ticker));
    }

    private void fetchInitialPrice(User user, String ticker) {
        String accessToken = kisTokenManager.getAccessToken(user.getKisApiKey(), user.getKisSecretKey());
        if (accessToken == null) return;

        String url = apiUrl + "/uapi/domestic-stock/v1/quotations/inquire-price?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + ticker;

        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + accessToken);
        headers.set("appkey", user.getKisApiKey());
        headers.set("appsecret", user.getKisSecretKey());
        headers.set("tr_id", "FHKST01010100");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getBody() != null && response.getBody().containsKey("output")) {
                Map output = (Map) response.getBody().get("output");
                String currentPrice = (String) output.get("stck_prpr");
                String acmlVol = (String) output.get("acml_vol");
                
                log.info("Fetched initial price for {}: {}", ticker, currentPrice);
                
                // Stock 테이블의 현재가 정보 업데이트
                stockRepository.findById(ticker).ifPresent(stock -> {
                    stock.updatePrice(currentPrice, acmlVol);
                    stockRepository.save(stock);
                });

                StockTick initialTick = StockTick.builder()
                        .ticker(ticker)
                        .price(currentPrice)
                        .volume(acmlVol)
                        .timestamp(System.currentTimeMillis())
                        .ownerEmail(user.getKisApiKey()) // WebSocket과 동일하게 appKey를 식별자로 사용
                        .build();
                
                stockTickPublisher.publish(initialTick);
            }
        } catch (Exception e) {
            log.error("Failed to fetch initial price for {}: {}", ticker, e.getMessage());
        }
    }

    private void registerAppKey(String appKey, String email) {
        if (appKey != null) {
            appKeyToUsers.computeIfAbsent(appKey, k -> ConcurrentHashMap.newKeySet()).add(email);
        }
    }

    public Set<String> getUsersByAppKey(String appKey) {
        return appKeyToUsers.getOrDefault(appKey, Collections.emptySet());
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getSubscriptions(User user) {
        List<Subscription> subscriptions = subscriptionRepository.findAllByUser(user);
        
        return subscriptions.stream()
                .map(sub -> {
                    String ticker = sub.getTicker();
                    Optional<Stock> stockOpt = stockRepository.findById(ticker);
                    String name = stockOpt.map(Stock::getName).orElse("주식 " + ticker);
                    String price = stockOpt.map(Stock::getCurrentPrice).orElse("0");
                    String volume = stockOpt.map(Stock::getLastVolume).orElse("0");
                    
                    return SubscriptionResponse.builder()
                            .ticker(ticker)
                            .name(name)
                            .price(price)
                            .volume(volume)
                            .build();
                })
                .collect(Collectors.toList());
    }

    public void unsubscribe(User user, String ticker) {
        subscriptionRepository.deleteByUserAndTicker(user, ticker);
    }
}
