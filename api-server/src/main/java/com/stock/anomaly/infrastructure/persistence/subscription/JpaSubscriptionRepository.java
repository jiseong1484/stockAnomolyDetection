package com.stock.anomaly.infrastructure.persistence.subscription;

import com.stock.anomaly.domain.subscription.Subscription;
import com.stock.anomaly.domain.subscription.SubscriptionRepository;
import com.stock.anomaly.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JpaSubscriptionRepository extends JpaRepository<Subscription, Long>, SubscriptionRepository {
    List<Subscription> findAllByUser(User user);
    void deleteByUserAndTicker(User user, String ticker);
    boolean existsByUserAndTicker(User user, String ticker);
}
