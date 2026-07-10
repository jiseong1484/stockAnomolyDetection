package com.stock.anomaly.application.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SseService {

    // key: userEmail, value: SseEmitter
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userEmail) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // 무제한 연결
        emitters.put(userEmail, emitter);

        emitter.onCompletion(() -> emitters.remove(userEmail));
        emitter.onTimeout(() -> emitters.remove(userEmail));
        emitter.onError((e) -> emitters.remove(userEmail));

        // 초기 연결 성공 메시지
        try {
            emitter.send(SseEmitter.event().name("connect").data("connected!"));
        } catch (IOException e) {
            log.error("Failed to send initial SSE event: {}", e.getMessage());
        }

        return emitter;
    }

    public void broadcast(Object data) {
        emitters.forEach((email, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("stock-tick").data(data));
            } catch (IOException e) {
                log.error("Failed to send SSE broadcast for {}: {}", email, e.getMessage());
                emitters.remove(email);
            }
        });
    }

    public void sendToUser(String email, Object data) {
        SseEmitter emitter = emitters.get(email);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("stock-tick").data(data));
            } catch (IOException e) {
                log.error("Failed to send SSE to {}: {}", email, e.getMessage());
                emitters.remove(email);
            }
        }
    }

    public void broadcastAnomaly(Object data) {
        emitters.forEach((email, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("anomaly-alert").data(data));
            } catch (IOException e) {
                log.error("Failed to broadcast anomaly to {}: {}", email, e.getMessage());
                emitters.remove(email);
            }
        });
    }

    public void broadcastAiSignal(Object data) {
        emitters.forEach((email, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("ai-signal").data(data));
            } catch (IOException e) {
                log.error("Failed to broadcast AI signal to {}: {}", email, e.getMessage());
                emitters.remove(email);
            }
        });
    }
}
