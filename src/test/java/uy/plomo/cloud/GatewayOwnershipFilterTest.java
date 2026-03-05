package uy.plomo.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GatewayOwnershipFilter")
class GatewayOwnershipFilterTest extends BaseControllerTest {

    @Test
    @DisplayName("allows access when the user owns the gateway")
    void ownedGateway_allowsRequest() throws Exception {
        when(dynamoDBService.getTunnelList("gw-owned")).thenReturn(
                CompletableFuture.completedFuture(Map.of())
        );

        perform(get("/api/v1/gw-owned/tunnels")
                .header("Authorization", bearerToken("alice", List.of("gw-owned"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("returns 404 when the user does not own the gateway")
    void unownedGateway_returns404() throws Exception {
        // El GatewayOwnershipFilter rechaza sincrónicamente (response.sendError),
        // no pasa por async dispatch. Usar mockMvc.perform directo.
        mockMvc.perform(get("/api/v1/gw-someone-elses/tunnels")
                        .header("Authorization", bearerToken("alice", List.of("gw-owned"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 403 when no token is provided")
    void noToken_returns403() throws Exception {
        // Spring Security rechaza sincrónicamente — no hay async dispatch.
        mockMvc.perform(get("/api/v1/gw-owned/tunnels"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("allows access to /summary (non-gateway route)")
    void summaryRoute_isNotBlockedByOwnershipFilter() throws Exception {
        when(dynamoDBService.getUserSummary("alice")).thenReturn(
                CompletableFuture.completedFuture(
                        mutableMap("username", "alice", "gateways", List.of())
                )
        );

        perform(get("/api/v1/summary")
                .header("Authorization", bearerToken("alice", List.of())))
                .andExpect(status().isOk());
    }
}
