package uy.plomo.cloud.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import uy.plomo.cloud.kafka.event.TelemetryEvent;

/**
 * Publica eventos de telemetría en el topic {@value #TOPIC_TELEMETRY}.
 *
 * <p>La clave del mensaje es el {@code gatewayId}: garantiza que todos los
 * mensajes del mismo gateway aterricen en la misma partición (orden preservado
 * por gateway, útil cuando It3 persista en TimescaleDB).
 *
 * <p>El send es fire-and-forget con callback asíncrono — si Kafka no está
 * disponible el error se loguea pero no rompe el hilo MQTT.
 */
@Service
@Slf4j
public class GatewayEventProducer {

    public static final String TOPIC_TELEMETRY = "gateway.telemetry";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public GatewayEventProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendTelemetry(TelemetryEvent event) {
        kafkaTemplate.send(TOPIC_TELEMETRY, event.gatewayId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Kafka] Failed to send telemetry for gateway '{}': {}",
                                event.gatewayId(), ex.getMessage());
                    } else {
                        log.debug("[Kafka] Telemetry sent — gateway='{}', partition={}, offset={}",
                                event.gatewayId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
