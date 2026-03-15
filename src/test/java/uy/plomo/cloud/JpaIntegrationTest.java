package uy.plomo.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uy.plomo.cloud.entity.*;
import uy.plomo.cloud.repository.GatewayRepository;
import uy.plomo.cloud.repository.TunnelRepository;
import uy.plomo.cloud.repository.UserRepository;
import uy.plomo.cloud.services.MqttService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("JPA Integration Tests (PostgreSQL)")
class JpaIntegrationTest {

    @MockitoBean MqttService mqttService;

    @Autowired UserRepository userRepository;
    @Autowired GatewayRepository gatewayRepository;
    @Autowired TunnelRepository tunnelRepository;

    @Test
    @DisplayName("saves and retrieves user by username")
    void savesAndFindsUser() {
        User user = User.create("bob", "$2a$12$hashed");
        userRepository.save(user);

        Optional<User> found = userRepository.findByUsername("bob");

        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("bob");
    }

    @Test
    @DisplayName("findByUsernameWithGateways loads gateways in single query (no N+1)")
    void loadsGatewaysWithUser() {
        User user = User.create("carol", "$2a$12$hashed");
        userRepository.save(user);

        Gateway gw1 = Gateway.create("gw-100", "ssh-ed25519 AAA1...", user);
        Gateway gw2 = Gateway.create("gw-101", "ssh-ed25519 AAA2...", user);
        gatewayRepository.saveAll(List.of(gw1, gw2));

        Optional<User> found = userRepository.findByUsernameWithGateways("carol");

        assertThat(found).isPresent();
        assertThat(found.get().getGateways()).hasSize(2);
        assertThat(found.get().getGateways())
                .extracting(Gateway::getId)
                .containsExactlyInAnyOrder("gw-100", "gw-101");
    }

    @Test
    @DisplayName("findAllByOwnerUsernameWithTunnels loads tunnels eagerly")
    void loadsTunnelsWithGateway() {
        User user = User.create("dan", "$2a$12$hashed");
        userRepository.save(user);

        Gateway gw = Gateway.create("gw-200", "ssh-ed25519 AAAB...", user);
        gatewayRepository.save(gw);

        Tunnel t1 = Tunnel.create("tunnel-a", "localhost", "8080", "9001", true, gw);
        Tunnel t2 = Tunnel.create("tunnel-b", "localhost", "9090", "9002", false, gw);
        tunnelRepository.saveAll(List.of(t1, t2));

        List<Gateway> gateways = gatewayRepository.findAllByOwnerUsernameWithTunnels("dan");

        assertThat(gateways).hasSize(1);
        assertThat(gateways.get(0).getTunnels()).hasSize(2);
    }

    @Test
    @DisplayName("findByIdAndGatewayId rejects tunnel from different gateway")
    void rejectsCrossGatewayAccess() {
        User user = User.create("eve", "$2a$12$hashed");
        userRepository.save(user);

        Gateway gw1 = Gateway.create("gw-300", "ssh-ed25519 AAAC...", user);
        Gateway gw2 = Gateway.create("gw-301", "ssh-ed25519 AAAD...", user);
        gatewayRepository.saveAll(List.of(gw1, gw2));

        Tunnel t = Tunnel.create("secure-tunnel", "localhost", "8080", "9001", false, gw1);
        tunnelRepository.save(t);

        assertThat(tunnelRepository.findByIdAndGatewayId(t.getId(), "gw-300")).isPresent();
        assertThat(tunnelRepository.findByIdAndGatewayId(t.getId(), "gw-301")).isEmpty();
    }

    @Test
    @DisplayName("deleting gateway cascades to tunnels")
    void cascadeDeleteGatewayRemovesTunnels() {
        User user = User.create("grace", "$2a$12$hashed");
        userRepository.save(user);

        Gateway gw = Gateway.create("gw-500", "ssh-ed25519 AAAF...", user);
        gatewayRepository.save(gw);

        Tunnel t = Tunnel.create("t", "localhost", "8080", "9001", false, gw);
        tunnelRepository.save(t);
        String tunnelId = t.getId();

        gatewayRepository.delete(gw);

        assertThat(tunnelRepository.findById(tunnelId)).isEmpty();
    }
}
