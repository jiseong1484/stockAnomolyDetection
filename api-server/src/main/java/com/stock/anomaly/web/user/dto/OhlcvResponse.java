package com.stock.anomaly.web.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OhlcvResponse {
    private long time; // seconds
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
}
