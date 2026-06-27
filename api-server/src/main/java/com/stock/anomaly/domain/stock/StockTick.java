package com.stock.anomaly.domain.stock;

import lombok.*;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@ToString
public class StockTick {
    private String ticker;        // 종목 코드
    private String price;         // 현재가
    private String volume;        // 거래량
    private long timestamp;       // 수신 시간
    private String ownerEmail;    // 소유자 이메일 (공용 데이터는 null)
    private String changeAmount;  // 전일 대비 금액 (부호 포함, e.g. "-29000")
    private String changeRate;    // 전일 대비율 (부호 포함, e.g. "-8.87")
}
