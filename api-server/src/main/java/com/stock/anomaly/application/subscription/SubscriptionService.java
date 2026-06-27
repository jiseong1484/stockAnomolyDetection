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
import com.stock.anomaly.infrastructure.persistence.redis.StockPriceRedisRepository;
import com.stock.anomaly.infrastructure.persistence.redis.SubscriptionCacheRedisRepository;
import com.stock.anomaly.web.subscription.dto.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final StockPriceRedisRepository stockPriceRedisRepository;
    private final SubscriptionCacheRedisRepository subscriptionCacheRedisRepository;
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
        try {
            subscriptionRepository.save(subscription);
        } catch (DataIntegrityViolationException e) {
            log.info("Ticker {} already subscribed for user {} (concurrent request)", ticker, user.getEmail());
            return;
        }

        subscriptionCacheRedisRepository.evict(user.getId());
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
                String acmlVol      = (String) output.get("acml_vol");

                // 전일 대비 정보 추출 (WebSocket과 동일한 부호 계산)
                String sign    = (String) output.get("prdy_vrss_sign"); // 1=상한 2=상승 3=보합 4=하락 5=하한
                String vrss    = (String) output.get("prdy_vrss");      // 전일 대비 금액
                String ctrt    = (String) output.get("prdy_ctrt");      // 전일 대비율
                boolean neg    = "4".equals(sign) || "5".equals(sign);
                boolean zero   = "3".equals(sign);
                String changeAmount = (vrss == null || zero) ? "0" : (neg ? "-" : "") + vrss;
                String changeRate   = (ctrt == null || zero) ? "0" : (neg ? "-" : "") + ctrt;

                log.info("Fetched initial price for {}: {}", ticker, currentPrice);

                // Redis에 현재가 저장 (MySQL 대신)
                stockPriceRedisRepository.save(ticker, currentPrice, acmlVol);

                StockTick initialTick = StockTick.builder()
                        .ticker(ticker)
                        .price(currentPrice)
                        .volume(acmlVol)
                        .timestamp(System.currentTimeMillis())
                        .ownerEmail(user.getKisApiKey())
                        .changeAmount(changeAmount)
                        .changeRate(changeRate)
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
        List<String> tickers = subscriptionCacheRedisRepository.find(user.getId())
                .orElseGet(() -> {
                    List<String> fromDb = subscriptionRepository.findAllByUser(user).stream()
                            .map(Subscription::getTicker)
                            .collect(Collectors.toList());
                    subscriptionCacheRedisRepository.save(user.getId(), fromDb);
                    log.debug("[SubscriptionCache] Cache miss for userId={}, loaded {} tickers from DB", user.getId(), fromDb.size());
                    return fromDb;
                });

        Map<String, String> nameByTicker = stockRepository.findAllById(tickers).stream()
                .collect(Collectors.toMap(Stock::getTicker, Stock::getName));

        return tickers.stream()
                .map(ticker -> SubscriptionResponse.builder()
                        .ticker(ticker)
                        .name(nameByTicker.getOrDefault(ticker, "주식 " + ticker))
                        .price(stockPriceRedisRepository.getPrice(ticker))
                        .volume(stockPriceRedisRepository.getVolume(ticker))
                        .build())
                .collect(Collectors.toList());
    }

    public void unsubscribe(User user, String ticker) {
        subscriptionRepository.deleteByUserAndTicker(user, ticker);
        subscriptionCacheRedisRepository.evict(user.getId());
    }
}
