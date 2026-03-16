package uy.plomo.cloud.kafka.event;

import java.time.Instant;
import java.util.Map;

/**
 * Evento de telemetría que viaja por el topic {@code gateway.telemetry}.
 *
 * <p>{@code payload} captura el JSON arbitrario de sensores que envía el gateway
 * (múltiples campos, ~3 KB). Se tipea como {@code Map<String, Object>} porque
 * el schema varía por dispositivo; en It3 se definirán columnas concretas en
 * TimescaleDB.
 */
public record TelemetryEvent(
        String gatewayId,
        Instant receivedAt,
        Map<String, Object> payload
) {}
