package com.stock.anomaly.web.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SubscribeRequest {
    @NotBlank
    private String ticker;
}
