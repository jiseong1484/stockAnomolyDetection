package com.stock.anomaly.web.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserProfileResponse {
    private String email;
    private String name;
    private String phoneNumber;
    private boolean hasKisApiKey;
}
