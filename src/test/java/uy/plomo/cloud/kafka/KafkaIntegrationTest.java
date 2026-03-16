package uy.plomo.cloud.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uy.plomo.cloud.PostgresTestConfig;
import uy.plomo.cloud.kafka.event.TelemetryEvent;
import uy.plomo.cloud.services.MqttService;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifica el pipeline end-to-end
 * {@code GatewayEventProducer → Kafka (EmbeddedKafka) → topic gateway.telemetry}.
 *
 * <p>Usa un consumer de raw strings para validar que el mensaje llega al topic
 * con la clave y el JSON correctos — sin depender de {@link GatewayEventConsumer}
 * (que en producción vive en el mismo proceso pero podría moverse a otro servicio).
 *
 * <p>{@link MqttService} se mockea para evitar la conexión a AWS IoT Core.
 * {@link PostgresTestConfig} levanta el container PostgreSQL vía Testcontainers.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {GatewayEventProducer.TOPIC_TELEMETRY}
)
@TestPropertySource(properties = {
        // EmbeddedKafka setea este system property antes de que arranque el context
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.group-id=iot-backend-test",
        // Propiedades dummy para beans que requieren config de AWS / JWT
        "jwt.secret=test-secret-key-that-is-long-enough-for-hmac",
        "jwt.expiration-ms=86400000",
        "aws.region=us-east-1",
        "aws.iot.endpoint=test-endpoint",
        "aws.iot.clientId=test-client",
        "cors.allowed-origins=http://localhost:5173",
        "tunnel.server.host=test-server",
        "port.pool.start=9000",
        "port.pool.end=9010",
        "iot.instanceName=test-instance",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("Kafka integration — pipeline MQTT → topic")
class KafkaIntegrationTest {

    @Autowired
    private GatewayEventProducer producer;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    MqttService mqttService;  // evita la conexión a AWS IoT Core

    @Test
    @DisplayName("sendTelemetry publica el mensaje en gateway.telemetry con key=gatewayId")
    void telemetryEvent_isPublishedToKafkaTopic() throws Exception {
        TelemetryEvent event = new TelemetryEvent(
                "gw-e2e-001",
                Instant.parse("2025-06-01T12:00:00Z"),
                Map.of("temperature", 21.3, "co2_ppm", 412.0, "voltage", 220.1)
        );

        producer.sendTelemetry(event);

        // Crear un consumer raw para leer el mensaje del topic directamente
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "test-verifier-group", "true", embeddedKafka);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        Consumer<String, String> rawConsumer =
                new DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer();
        embeddedKafka.consumeFromAnEmbeddedTopic(rawConsumer, GatewayEventProducer.TOPIC_TELEMETRY);

        ConsumerRecords<String, String> records =
                KafkaTestUtils.getRecords(rawConsumer, Duration.ofSeconds(10));

        rawConsumer.close();

        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        var record = records.iterator().next();

        // Verificar key — es el gatewayId para particionado por gateway
        assertThat(record.key()).isEqualTo("gw-e2e-001");

        // Verificar que el JSON contiene los campos correctos
        @SuppressWarnings("unchecked")
        Map<String, Object> json = objectMapper.readValue(record.value(), Map.class);
        assertThat(json).containsKey("gatewayId");
        assertThat(json.get("gatewayId")).isEqualTo("gw-e2e-001");
        assertThat(json).containsKey("payload");
        assertThat(json).containsKey("receivedAt");
    }
}
