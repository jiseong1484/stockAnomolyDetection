package com.stock.anomaly.infrastructure.persistence.user;

import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.domain.user.UserRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JpaUserRepository extends JpaRepository<User, Long>, UserRepository {
    // Spring Data JPA magic
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
