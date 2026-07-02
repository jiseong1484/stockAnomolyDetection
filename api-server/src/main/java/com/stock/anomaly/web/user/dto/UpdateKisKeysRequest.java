package com.stock.anomaly.web.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateKisKeysRequest {

    @NotBlank
    private String kisApiKey;

    @NotBlank
    private String kisSecretKey;
}
