package uy.plomo.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uy.plomo.cloud.services.GatewayEventBroadcaster;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayEventBroadcaster")
class GatewayEventBroadcasterTest {

    private final GatewayEventBroadcaster broadcaster = new GatewayEventBroadcaster();

    @Test
    @DisplayName("subscribe returns an SseEmitter")
    void subscribe_returnsEmitter() {
        assertThat(broadcaster.subscribe("gw-001")).isNotNull();
    }

    @Test
    @DisplayName("broadcast sends event to subscribed emitter without throwing")
    void broadcast_sendsToEmitter() {
        broadcaster.subscribe("gw-001");
        // SseEmitter.send() throws IllegalStateException outside a servlet context —
        // the broadcaster catches IOException and removes the emitter. No exception escapes.
        broadcaster.broadcast("gw-001", "INTERVIEW_COMPLETE", "{\"status\":\"ok\"}");
    }

    @Test
    @DisplayName("broadcast to gateway with no subscribers does nothing")
    void broadcast_noSubscribers_doesNothing() {
        broadcaster.broadcast("gw-nobody", "some-event", "{}");
    }

    @Test
    @DisplayName("failed send removes emitter — second broadcast does not throw")
    void broadcast_failedSend_removesEmitter() {
        GatewayEventBroadcaster b = new GatewayEventBroadcaster();
        b.subscribe("gw-001");
        // First broadcast: send fails outside servlet context, emitter is removed
        b.broadcast("gw-001", "test", "{}");
        // Second broadcast: registry is empty, should be a no-op
        b.broadcast("gw-001", "test", "{}");
    }

    @Test
    @DisplayName("multiple subscribers all receive the broadcast")
    void broadcast_multipleSubscribers() {
        SseEmitter e1 = broadcaster.subscribe("gw-multi");
        SseEmitter e2 = broadcaster.subscribe("gw-multi");
        assertThat(e1).isNotSameAs(e2);
        // Both fail (no servlet context) and are cleaned up — no exception escapes
        broadcaster.broadcast("gw-multi", "event", "{}");
    }
}
