package com.stock.anomaly.domain.user;

import com.stock.anomaly.infrastructure.persistence.converter.CryptoConverter;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "kis_api_key", length = 1000)
    private String kisApiKey;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "kis_secret_key", length = 1000)
    private String kisSecretKey;

    public void updateKisKeys(String kisApiKey, String kisSecretKey) {
        this.kisApiKey = kisApiKey;
        this.kisSecretKey = kisSecretKey;
    }
}
