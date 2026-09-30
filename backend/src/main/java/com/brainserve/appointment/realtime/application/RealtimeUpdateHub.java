package com.brainserve.appointment.realtime.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeUpdateHub {
    private static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter connect() {
        UUID connectionId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.put(connectionId, emitter);
        emitter.onCompletion(() -> emitters.remove(connectionId));
        emitter.onTimeout(() -> {
            emitters.remove(connectionId);
            emitter.complete();
        });
        emitter.onError(ignored -> emitters.remove(connectionId));
        send(connectionId, emitter, "connected", "ready");
        return emitter;
    }

    public void broadcastRefresh() {
        emitters.forEach((id, emitter) -> send(id, emitter, "workspace-refresh", "refresh"));
    }

    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        String timestamp = Instant.now().toString();
        emitters.forEach((id, emitter) -> send(id, emitter, "heartbeat", timestamp));
    }

    private void send(UUID id, SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException exception) {
            emitters.remove(id);
            // A failed SSE write normally means the browser closed or replaced
            // the connection. The servlet container already starts async error
            // handling for IOException, so redispatching the same client abort
            // through completeWithError would create a second server failure.
        }
    }
}
