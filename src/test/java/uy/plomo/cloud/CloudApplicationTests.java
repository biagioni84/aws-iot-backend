package uy.plomo.cloud;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uy.plomo.cloud.services.MqttService;

@SpringBootTest
@Import(PostgresTestConfig.class)
@TestPropertySource(properties = {
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
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Kafka — apuntamos a un broker inexistente y deshabilitamos el listener
        // para que el context load no intente conectarse a Kafka real.
        // MqttService ya está mockeado, así que GatewayEventProducer tampoco se invoca.
        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.kafka.listener.auto-startup=false"
})
class CloudApplicationTests {

    @MockitoBean
    MqttService mqttService;

    @MockitoBean
    uy.plomo.cloud.kafka.GatewayEventProducer gatewayEventProducer;

    @Test
    void contextLoads() {
    }
}
