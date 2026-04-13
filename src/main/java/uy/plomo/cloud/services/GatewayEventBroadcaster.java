package uy.plomo.cloud.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Maintains SSE emitter registrations per gateway and broadcasts
 * MQTT events to all connected UI clients for that gateway.
 */
@Service
@Slf4j
public class GatewayEventBroadcaster {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> registry =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(String gatewayId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 min timeout
        registry.computeIfAbsent(gatewayId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> remove(gatewayId, emitter);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        emitter.onCompletion(cleanup);

        log.debug("SSE client subscribed to gateway {}", gatewayId);
        return emitter;
    }

    public void broadcast(String gatewayId, String eventType, String payload) {
        List<SseEmitter> emitters = registry.get(gatewayId);
        if (emitters == null || emitters.isEmpty()) return;

        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(eventType)
                .data(payload);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (IOException e) {
                log.debug("SSE send failed for gateway {}, removing emitter: {}", gatewayId, e.getMessage());
                remove(gatewayId, emitter);
            }
        }
    }

    private void remove(String gatewayId, SseEmitter emitter) {
        List<SseEmitter> emitters = registry.get(gatewayId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) registry.remove(gatewayId);
        }
    }
}
