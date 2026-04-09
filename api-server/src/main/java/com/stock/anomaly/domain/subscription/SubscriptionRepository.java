package com.stock.anomaly.domain.subscription;

import com.stock.anomaly.domain.user.User;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository {
    Subscription save(Subscription subscription);
    List<Subscription> findAll();
    List<Subscription> findAllByUser(User user);
    void deleteByUserAndTicker(User user, String ticker);
    boolean existsByUserAndTicker(User user, String ticker);
}
