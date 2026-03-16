package uy.plomo.cloud.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import uy.plomo.cloud.kafka.event.TelemetryEvent;

/**
 * Consume el topic {@code gateway.telemetry} y loguea el mensaje.
 *
 * <p><b>Iteración 2 — scope intencional:</b> el consumer solo loguea.
 * El objetivo es validar que el pipeline MQTT → Kafka funciona end-to-end
 * antes de agregar persistencia en It3 (TimescaleDB).
 *
 * <p>En It3 este método:
 * <ul>
 *   <li>Recibirá la anotación {@code @Transactional}</li>
 *   <li>Llamará a un {@code TelemetryService} que persista en TimescaleDB</li>
 *   <li>El container se configurará con {@code AckMode.MANUAL} para commitear
 *       el offset solo si el INSERT en BD fue exitoso</li>
 * </ul>
 */
@Service
@Slf4j
public class GatewayEventConsumer {

    @KafkaListener(
            topics = GatewayEventProducer.TOPIC_TELEMETRY,
            groupId = "${spring.kafka.consumer.group-id:iot-backend}"
    )
    public void onTelemetry(Object raw) {
        if (!(raw instanceof TelemetryEvent event)) {
            log.warn("[Kafka] Unexpected message type: {}", raw == null ? "null" : raw.getClass().getName());
            return;
        }
        log.info("[Kafka] Telemetry received — gateway='{}', receivedAt={}, fields={}",
                event.gatewayId(),
                event.receivedAt(),
                event.payload() != null ? event.payload().size() : 0);
        log.debug("[Kafka] Full payload for '{}': {}", event.gatewayId(), event.payload());
    }
}
