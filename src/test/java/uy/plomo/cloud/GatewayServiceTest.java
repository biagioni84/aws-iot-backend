package uy.plomo.cloud.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uy.plomo.cloud.dto.TunnelRequest;
import uy.plomo.cloud.entity.Gateway;
import uy.plomo.cloud.entity.Tunnel;
import uy.plomo.cloud.entity.TunnelState;
import uy.plomo.cloud.entity.User;
import uy.plomo.cloud.repository.GatewayRepository;
import uy.plomo.cloud.repository.TunnelRepository;
import uy.plomo.cloud.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests — no Spring context, no DB, no Testcontainers.
 * Fast. Run on every build.
 *
 * @ExtendWith(MockitoExtension.class) wires @Mock and @InjectMocks automatically.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayService")
class GatewayServiceTest {

    @Mock UserRepository userRepository;
    @Mock GatewayRepository gatewayRepository;
    @Mock TunnelRepository tunnelRepository;

    @InjectMocks GatewayService service;

    // Shared test fixtures
    private User alice;
    private Gateway gw;

    @BeforeEach
    void setUp() {
        alice = User.create("alice", "$2a$12$hashedpwd");
        gw = Gateway.create("gw-001", "ssh-ed25519 AAAA...", alice);
    }

    // -------------------------------------------------------------------------
    // getUserSummary
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getUserSummary")
    class GetUserSummary {

        @Test
        @DisplayName("returns map with username and gateway IDs")
        void returnsUserSummary() {
            when(userRepository.findByUsernameWithGateways("alice"))
                    .thenReturn(Optional.of(alice));

            Map<String, Object> result = service.getUserSummary("alice");

            assertThat(result.get("username")).isEqualTo("alice");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user not found")
        void throwsWhenUserNotFound() {
            when(userRepository.findByUsernameWithGateways("ghost"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUserSummary("ghost"))
                    .isInstanceOf(GatewayService.ResourceNotFoundException.class)
                    .hasMessageContaining("ghost");
        }
    }

    // -------------------------------------------------------------------------
    // getTunnelList
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getTunnelList")
    class GetTunnelList {

        @Test
        @DisplayName("returns map of tunnelId to tunnel fields")
        void returnsTunnelMap() {
            Tunnel t = Tunnel.create("my-tunnel", "localhost", "8080", "9001", false, gw);

            when(gatewayRepository.existsById("gw-001")).thenReturn(true);
            when(tunnelRepository.findAllByGatewayId("gw-001")).thenReturn(List.of(t));

            Map<String, Object> result = service.getTunnelList("gw-001");

            assertThat(result).hasSize(1);
            Map<String, Object> tunnelData = (Map<String, Object>) result.values().iterator().next();
            assertThat(tunnelData.get("name")).isEqualTo("my-tunnel");
            assertThat(tunnelData.get("src_port")).isEqualTo("8080");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when gateway not found")
        void throwsWhenGatewayNotFound() {
            when(gatewayRepository.existsById("gw-999")).thenReturn(false);

            assertThatThrownBy(() -> service.getTunnelList("gw-999"))
                    .isInstanceOf(GatewayService.ResourceNotFoundException.class)
                    .hasMessageContaining("gw-999");
        }
    }

    // -------------------------------------------------------------------------
    // createTunnel
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("createTunnel")
    class CreateTunnel {

        @Test
        @DisplayName("saves tunnel and returns generated ID")
        void savesAndReturnsId() {
            when(gatewayRepository.findById("gw-001")).thenReturn(Optional.of(gw));
            when(tunnelRepository.save(any(Tunnel.class))).thenAnswer(inv -> inv.getArgument(0));

            TunnelRequest req = new TunnelRequest("test", "localhost", "8080", "9001", "off");
            String id = service.createTunnel("gw-001", req);

            assertThat(id).isNotBlank();
            verify(tunnelRepository).save(any(Tunnel.class));
        }

        @Test
        @DisplayName("throws when gateway not found")
        void throwsWhenGatewayNotFound() {
            when(gatewayRepository.findById("gw-999")).thenReturn(Optional.empty());

            TunnelRequest req = new TunnelRequest("test", "localhost", "8080", "9001", "off");

            assertThatThrownBy(() -> service.createTunnel("gw-999", req))
                    .isInstanceOf(GatewayService.ResourceNotFoundException.class);
        }
    }

    // -------------------------------------------------------------------------
    // updateTunnel
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("updateTunnel")
    class UpdateTunnel {

        @Test
        @DisplayName("updates tunnel fields without explicit save() call")
        void updatesDirtyEntity() {
            Tunnel t = Tunnel.create("old-name", "localhost", "8080", "9001", false, gw);

            when(tunnelRepository.findByIdAndGatewayId(t.getId(), "gw-001"))
                    .thenReturn(Optional.of(t));

            TunnelRequest req = new TunnelRequest("new-name", "remotehost", "9090", "9002", "on");
            service.updateTunnel("gw-001", t.getId(), req);

            // Verify the entity was mutated — Hibernate dirty checking handles the DB write
            assertThat(t.getName()).isEqualTo("new-name");
            assertThat(t.getSrcAddr()).isEqualTo("remotehost");
            assertThat(t.isUseThisServer()).isTrue();

            // No save() call expected — dirty checking is the point
            verify(tunnelRepository, never()).save(any());
        }
    }

    // -------------------------------------------------------------------------
    // markTunnelActive / markTunnelStopped
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("tunnel state transitions")
    class TunnelStateTransitions {

        @Test
        @DisplayName("markTunnelActive sets state and assigned port")
        void markActive() {
            Tunnel t = Tunnel.create("t", "localhost", "8080", "9001", true, gw);
            when(tunnelRepository.findByIdAndGatewayId(t.getId(), "gw-001"))
                    .thenReturn(Optional.of(t));

            service.markTunnelActive("gw-001", t.getId(), 9001);

            assertThat(t.getState()).isEqualTo(TunnelState.ACTIVE);
            assertThat(t.getAssignedPort()).isEqualTo(9001);
        }

        @Test
        @DisplayName("markTunnelStopped clears port and sets STOPPED state")
        void markStopped() {
            Tunnel t = Tunnel.create("t", "localhost", "8080", "9001", true, gw);
            when(tunnelRepository.findByIdAndGatewayId(t.getId(), "gw-001"))
                    .thenReturn(Optional.of(t));

            service.markTunnelStopped("gw-001", t.getId());

            assertThat(t.getState()).isEqualTo(TunnelState.STOPPED);
            assertThat(t.getAssignedPort()).isNull();
        }
    }
}
