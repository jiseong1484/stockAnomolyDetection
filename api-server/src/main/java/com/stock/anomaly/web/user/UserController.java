package com.stock.anomaly.web.user;

import com.stock.anomaly.application.user.UserService;
import com.stock.anomaly.infrastructure.security.CustomUserDetails;
import com.stock.anomaly.web.user.dto.SignUpRequest;
import com.stock.anomaly.web.user.dto.UpdateKisKeysRequest;
import com.stock.anomaly.web.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<String> signUp(@RequestBody @Valid SignUpRequest request) {
        userService.signUp(request);
        return ResponseEntity.ok("Successfully signed up");
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(userService.getProfile(userDetails.getUser()));
    }

    @PutMapping("/me/kis-keys")
    public ResponseEntity<String> updateKisKeys(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid UpdateKisKeysRequest request) {
        userService.updateKisKeys(userDetails.getUser(), request);
        return ResponseEntity.ok("KIS API 키가 업데이트되었습니다.");
    }
}
