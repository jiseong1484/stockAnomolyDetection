package com.stock.anomaly.application.subscription;

import com.stock.anomaly.domain.stock.Stock;
import com.stock.anomaly.domain.stock.StockRepository;
import com.stock.anomaly.domain.stock.StockTickPublisher;
import com.stock.anomaly.domain.subscription.Subscription;
import com.stock.anomaly.domain.subscription.SubscriptionRepository;
import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.infrastructure.external.kis.KisTokenManager;
import com.stock.anomaly.infrastructure.external.kis.KisWebSocketClient;
import com.stock.anomaly.infrastructure.persistence.redis.StockPriceRedisRepository;
import com.stock.anomaly.infrastructure.persistence.redis.SubscriptionCacheRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubscriptionService is the boundary between "user unsubscribes in the UI" and
 * "KIS actually stops streaming ticks for that ticker". These tests pin down that
 * unsubscribe() must tear down the upstream KIS subscription, not just the local
 * DB row/cache — see KisWebSocketClient#unsubscribe.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private StockPriceRedisRepository stockPriceRedisRepository;
    @Mock private SubscriptionCacheRedisRepository subscriptionCacheRedisRepository;
    @Mock private StockRepository stockRepository;
    @Mock private KisWebSocketClient kisWebSocketClient;
    @Mock private KisTokenManager kisTokenManager;
    @Mock private StockTickPublisher stockTickPublisher;

    private SubscriptionService subscriptionService;
    private User user;

    @BeforeEach
    void setUp() {
        subscriptionService = new SubscriptionService(
                subscriptionRepository,
                stockPriceRedisRepository,
                subscriptionCacheRedisRepository,
                stockRepository,
                kisWebSocketClient,
                kisTokenManager,
                stockTickPublisher
        );

        user = User.builder()
                .id(1L)
                .email("user@example.com")
                .password("encoded")
                .name("Tester")
                .kisApiKey("app-key")
                .kisSecretKey("app-secret")
                .build();
    }

    @Test
    void subscribe_newTicker_persistsAndOpensKisStream() {
        when(subscriptionRepository.existsByUserAndTicker(user, "005930")).thenReturn(false);
        when(stockRepository.existsById("005930")).thenReturn(true);
        // short-circuits fetchInitialPrice before it touches RestTemplate/apiUrl
        when(kisTokenManager.getAccessToken("app-key", "app-secret")).thenReturn(null);

        subscriptionService.subscribe(user, "005930");

        verify(subscriptionRepository).save(any(Subscription.class));
        verify(subscriptionCacheRedisRepository).evict(user.getId());
        verify(kisWebSocketClient).subscribe("app-key", "app-secret", Collections.singletonList("005930"));
    }

    @Test
    void subscribe_alreadySubscribed_isANoop() {
        when(subscriptionRepository.existsByUserAndTicker(user, "005930")).thenReturn(true);

        subscriptionService.subscribe(user, "005930");

        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(kisWebSocketClient);
    }

    @Test
    void unsubscribe_removesSubscriptionAndTearsDownKisStream() {
        subscriptionService.unsubscribe(user, "005930");

        verify(subscriptionRepository).deleteByUserAndTicker(user, "005930");
        verify(subscriptionCacheRedisRepository).evict(user.getId());
        verify(kisWebSocketClient).unsubscribe("app-key", "app-secret", Collections.singletonList("005930"));
    }

    @Test
    void getSubscriptions_cacheMiss_fallsBackToDbAndRepopulatesCache() {
        Subscription subscription = Subscription.builder().user(user).ticker("005930").build();
        Stock stock = Stock.builder().ticker("005930").name("삼성전자").build();

        when(subscriptionCacheRedisRepository.find(user.getId())).thenReturn(java.util.Optional.empty());
        when(subscriptionRepository.findAllByUser(user)).thenReturn(List.of(subscription));
        when(stockRepository.findAllById(List.of("005930"))).thenReturn(List.of(stock));
        when(stockPriceRedisRepository.getPrice("005930")).thenReturn("70000");
        when(stockPriceRedisRepository.getVolume("005930")).thenReturn("1000");

        List<com.stock.anomaly.web.subscription.dto.SubscriptionResponse> result =
                subscriptionService.getSubscriptions(user);

        verify(subscriptionCacheRedisRepository).save(user.getId(), List.of("005930"));
        org.junit.jupiter.api.Assertions.assertEquals(1, result.size());
        org.junit.jupiter.api.Assertions.assertEquals("삼성전자", result.get(0).getName());
    }
}
