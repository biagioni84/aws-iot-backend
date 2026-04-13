package uy.plomo.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import uy.plomo.cloud.dto.GatewayRegistrationRequest;
import uy.plomo.cloud.services.GatewayService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GatewayController")
class GatewayControllerTest extends BaseControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String GW_ID = "gw-new-001";
    private String authHeader;

    @BeforeEach
    void setUp() {
        declareOwnership("alice");
        authHeader = bearerToken("alice", List.of());
    }

    @Test
    @DisplayName("POST /api/v1/gateways -- registers gateway and returns 201")
    void registerGateway_returns201() throws Exception {
        when(gatewayService.registerGateway(eq("alice"), any(GatewayRegistrationRequest.class)))
                .thenReturn(GW_ID);

        mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new GatewayRegistrationRequest(GW_ID, "ssh-rsa AAAA..."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gateway_id").value(GW_ID));
    }

    @Test
    @DisplayName("POST /api/v1/gateways -- returns 409 when gateway already exists")
    void registerGateway_conflict_returns409() throws Exception {
        when(gatewayService.registerGateway(eq("alice"), any(GatewayRegistrationRequest.class)))
                .thenThrow(new GatewayService.ConflictException("Gateway already exists: " + GW_ID));

        mockMvc.perform(post("/api/v1/gateways")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new GatewayRegistrationRequest(GW_ID, "ssh-rsa AAAA..."))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    @DisplayName("POST /api/v1/gateways -- returns 401 without token")
    void registerGateway_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/gateways")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new GatewayRegistrationRequest(GW_ID, "ssh-rsa AAAA..."))))
                .andExpect(status().isUnauthorized());
    }
}
