package com.stock.anomaly.application.subscription;

import com.stock.anomaly.domain.stock.Stock;
import com.stock.anomaly.domain.stock.StockRepository;
import com.stock.anomaly.domain.subscription.Subscription;
import com.stock.anomaly.domain.subscription.SubscriptionRepository;
import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.infrastructure.external.kis.KisWebSocketClient;
import com.stock.anomaly.web.subscription.dto.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final StockRepository stockRepository;
    private final KisWebSocketClient kisWebSocketClient;

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
        kisWebSocketClient.subscribe(user.getKisApiKey(), user.getKisSecretKey(), Collections.singletonList(ticker));
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
                    
                    return SubscriptionResponse.builder()
                            .ticker(ticker)
                            .name(name)
                            .build();
                })
                .collect(Collectors.toList());
    }

    public void unsubscribe(User user, String ticker) {
        subscriptionRepository.deleteByUserAndTicker(user, ticker);
    }
}
