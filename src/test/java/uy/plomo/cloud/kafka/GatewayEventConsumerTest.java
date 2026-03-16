package uy.plomo.cloud.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uy.plomo.cloud.kafka.event.TelemetryEvent;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit test del consumer — sin Spring context, sin Kafka real.
 *
 * <p>En It2 el consumer solo loguea, así que el test verifica que:
 * <ul>
 *   <li>No lanza excepción con payload completo</li>
 *   <li>No lanza excepción con payload vacío (gateway que envía JSON vacío)</li>
 *   <li>No lanza excepción con payload null (deserialización parcial)</li>
 * </ul>
 *
 * En It3 estos tests se expandirán para verificar llamadas al TelemetryService.
 */
@DisplayName("GatewayEventConsumer")
class GatewayEventConsumerTest {

    private final GatewayEventConsumer consumer = new GatewayEventConsumer();

    @Test
    @DisplayName("onTelemetry no lanza con payload normal")
    void onTelemetry_handlesNormalPayload() {
        TelemetryEvent event = new TelemetryEvent(
                "gw-001",
                Instant.now(),
                Map.of("temperature", 23.5, "humidity", 65.0, "pressure", 1013.0)
        );
        assertThatCode(() -> consumer.onTelemetry(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onTelemetry no lanza con payload vacío")
    void onTelemetry_handlesEmptyPayload() {
        TelemetryEvent event = new TelemetryEvent("gw-002", Instant.now(), Map.of());
        assertThatCode(() -> consumer.onTelemetry(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onTelemetry no lanza con payload null")
    void onTelemetry_handlesNullPayload() {
        TelemetryEvent event = new TelemetryEvent("gw-003", Instant.now(), null);
        assertThatCode(() -> consumer.onTelemetry(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onTelemetry no lanza con tipo inesperado (Object desconocido)")
    void onTelemetry_handlesUnexpectedType() {
        assertThatCode(() -> consumer.onTelemetry("unexpected-string")).doesNotThrowAnyException();
    }
}
