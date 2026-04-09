package com.stock.anomaly.web.subscription;

import com.stock.anomaly.application.subscription.SubscriptionService;
import com.stock.anomaly.infrastructure.security.CustomUserDetails;
import com.stock.anomaly.web.subscription.dto.SubscribeRequest;
import com.stock.anomaly.web.subscription.dto.SubscriptionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @PostMapping
    public ResponseEntity<String> subscribe(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid SubscribeRequest request) {
        
        subscriptionService.subscribe(userDetails.getUser(), request.getTicker());
        return ResponseEntity.ok("Successfully subscribed to " + request.getTicker());
    }

    @GetMapping
    public ResponseEntity<List<SubscriptionResponse>> getSubscriptions(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        return ResponseEntity.ok(subscriptionService.getSubscriptions(userDetails.getUser()));
    }

    @DeleteMapping("/{ticker}")
    public ResponseEntity<String> unsubscribe(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String ticker) {
        
        subscriptionService.unsubscribe(userDetails.getUser(), ticker);
        return ResponseEntity.ok("Successfully unsubscribed from " + ticker);
    }
}
