package com.stock.anomaly.web.auth;

import com.stock.anomaly.application.user.AuthService;
import com.stock.anomaly.infrastructure.security.jwt.JwtBlacklistService;
import com.stock.anomaly.web.auth.dto.LoginRequest;
import com.stock.anomaly.web.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtBlacklistService jwtBlacklistService;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String bearerToken) {
        if (StringUtils.hasText(bearerToken)) {
            String token = bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken;
            jwtBlacklistService.blacklist(token);
        }
        return ResponseEntity.noContent().build();
    }
}
