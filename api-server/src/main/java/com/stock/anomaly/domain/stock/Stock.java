package com.stock.anomaly.domain.stock;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "stocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Stock {

    @Id
    @Column(length = 10)
    private String ticker; // 종목 코드 (PK)

    @Column(nullable = false)
    private String name;   // 종목명

    @Column(length = 20)
    private String market; // 시장 구분 (KOSPI, KOSDAQ 등)

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public void updateName(String name) {
        this.name = name;
    }
}
