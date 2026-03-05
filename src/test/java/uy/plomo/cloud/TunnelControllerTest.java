package uy.plomo.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import uy.plomo.cloud.services.DynamoDBService;
import uy.plomo.cloud.services.DynamoDBService.TunnelRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("TunnelController")
class TunnelControllerTest extends BaseControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String GW_ID = "gw-001";
    private static final String TUNNEL_ID = "tunnel-abc";
    private String authHeader;

    @BeforeEach
    void setUp() {
        authHeader = bearerToken("alice", List.of(GW_ID));
    }

    // -------------------------------------------------------------------------
    // GET /{gwId}/tunnels
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /{gwId}/tunnels -- returns tunnel list")
    void listTunnels_returnsList() throws Exception {
        when(dynamoDBService.getTunnelList(GW_ID)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        TUNNEL_ID, Map.of("name", "my-tunnel", "src_port", "8080")
                ))
        );

        perform(get("/api/v1/{gwId}/tunnels", GW_ID)
                .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$." + TUNNEL_ID + ".name").value("my-tunnel"));
    }

    @Test
    @DisplayName("GET /{gwId}/tunnels -- returns 404 when gateway not found")
    void listTunnels_gatewayNotFound_returns404() throws Exception {
        when(dynamoDBService.getTunnelList(GW_ID)).thenReturn(
                CompletableFuture.failedFuture(
                        new DynamoDBService.ResourceNotFoundException("Gateway not found: " + GW_ID)
                )
        );

        perform(get("/api/v1/{gwId}/tunnels", GW_ID)
                .header("Authorization", authHeader))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // GET /{gwId}/tunnels/{tunnelId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /{gwId}/tunnels/{tunnelId} -- returns tunnel detail")
    void getTunnel_returnsTunnel() throws Exception {
        when(dynamoDBService.getTunnelDetail(GW_ID, TUNNEL_ID)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "name", "my-tunnel",
                        "src_addr", "localhost",
                        "src_port", "8080",
                        "dst_port", "9001",
                        "use_this_server", "off"
                ))
        );

        perform(get("/api/v1/{gwId}/tunnels/{tunnelId}", GW_ID, TUNNEL_ID)
                .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("my-tunnel"))
                .andExpect(jsonPath("$.src_port").value("8080"));
    }

    @Test
    @DisplayName("GET /{gwId}/tunnels/{tunnelId} -- returns 404 when tunnel not found")
    void getTunnel_notFound_returns404() throws Exception {
        when(dynamoDBService.getTunnelDetail(GW_ID, TUNNEL_ID)).thenReturn(
                CompletableFuture.failedFuture(
                        new DynamoDBService.ResourceNotFoundException("Tunnel not found: " + TUNNEL_ID)
                )
        );

        perform(get("/api/v1/{gwId}/tunnels/{tunnelId}", GW_ID, TUNNEL_ID)
                .header("Authorization", authHeader))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // POST /{gwId}/tunnels
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /{gwId}/tunnels -- creates tunnel and returns 201 with tunnelId")
    void createTunnel_returns201() throws Exception {
        when(dynamoDBService.createTunnel(eq(GW_ID), anyString(), any(TunnelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        perform(post("/api/v1/{gwId}/tunnels", GW_ID)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "name", "my-tunnel",
                        "src_addr", "localhost",
                        "src_port", "8080",
                        "dst_port", "9001",
                        "use_this_server", "off"
                ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tunnelId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /{gwId}/tunnels -- returns 409 when tunnel already exists")
    void createTunnel_conflict_returns409() throws Exception {
        when(dynamoDBService.createTunnel(eq(GW_ID), anyString(), any(TunnelRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new DynamoDBService.ConflictException("Tunnel already exists: " + TUNNEL_ID)
                ));

        perform(post("/api/v1/{gwId}/tunnels", GW_ID)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "name", "my-tunnel",
                        "src_addr", "localhost",
                        "src_port", "8080",
                        "dst_port", "9001"
                ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    // -------------------------------------------------------------------------
    // PUT /{gwId}/tunnels/{tunnelId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT /{gwId}/tunnels/{tunnelId} -- updates and returns 204")
    void updateTunnel_returns204() throws Exception {
        when(dynamoDBService.updateTunnel(eq(GW_ID), eq(TUNNEL_ID), any(TunnelRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        perform(put("/api/v1/{gwId}/tunnels/{tunnelId}", GW_ID, TUNNEL_ID)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "name", "updated-tunnel",
                        "src_addr", "localhost",
                        "src_port", "9090",
                        "dst_port", "9002"
                ))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PUT /{gwId}/tunnels/{tunnelId} -- returns 404 when tunnel does not exist")
    void updateTunnel_notFound_returns404() throws Exception {
        when(dynamoDBService.updateTunnel(eq(GW_ID), eq(TUNNEL_ID), any(TunnelRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new DynamoDBService.ResourceNotFoundException("Tunnel not found: " + TUNNEL_ID)
                ));

        perform(put("/api/v1/{gwId}/tunnels/{tunnelId}", GW_ID, TUNNEL_ID)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "name", "updated-tunnel",
                        "src_addr", "localhost",
                        "src_port", "9090",
                        "dst_port", "9002"
                ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // DELETE /{gwId}/tunnels/{tunnelId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /{gwId}/tunnels/{tunnelId} -- deletes and returns 204")
    void deleteTunnel_returns204() throws Exception {
        when(dynamoDBService.getTunnelDetail(GW_ID, TUNNEL_ID)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "src_addr", "localhost",
                        "src_port", "8080",
                        "dst_port", "9001",
                        "use_this_server", "off"
                ))
        );
        when(mqttService.sendAsync(eq(GW_ID), any())).thenReturn(
                CompletableFuture.completedFuture(Map.of("status", "ok"))
        );
        when(dynamoDBService.deleteTunnel(GW_ID, TUNNEL_ID))
                .thenReturn(CompletableFuture.completedFuture(null));

        perform(delete("/api/v1/{gwId}/tunnels/{tunnelId}", GW_ID, TUNNEL_ID)
                .header("Authorization", authHeader))
                .andExpect(status().isNoContent());

        verify(dynamoDBService).deleteTunnel(GW_ID, TUNNEL_ID);
    }

    @Test
    @DisplayName("DELETE /{gwId}/tunnels/{tunnelId} -- deletes even if gateway MQTT fails")
    void deleteTunnel_mqttFails_stillDeletes() throws Exception {
        when(dynamoDBService.getTunnelDetail(GW_ID, TUNNEL_ID)).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "src_addr", "localhost",
                        "src_port", "8080",
                        "dst_port", "9001",
                        "use_this_server", "off"
                ))
        );
        when(mqttService.sendAsync(eq(GW_ID), any())).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("Gateway timeout"))
        );
        when(dynamoDBService.deleteTunnel(GW_ID, TUNNEL_ID))
                .thenReturn(CompletableFuture.completedFuture(null));

        perform(delete("/api/v1/{gwId}/tunnels/{tunnelId}", GW_ID, TUNNEL_ID)
                .header("Authorization", authHeader))
                .andExpect(status().isNoContent());

        verify(dynamoDBService).deleteTunnel(GW_ID, TUNNEL_ID);
    }

    @Test
    @DisplayName("DELETE /{gwId}/tunnels/{tunnelId} -- returns 404 when tunnel does not exist")
    void deleteTunnel_notFound_returns404() throws Exception {
        when(dynamoDBService.getTunnelDetail(GW_ID, TUNNEL_ID)).thenReturn(
                CompletableFuture.failedFuture(
                        new DynamoDBService.ResourceNotFoundException("Tunnel not found: " + TUNNEL_ID)
                )
        );

        perform(delete("/api/v1/{gwId}/tunnels/{tunnelId}", GW_ID, TUNNEL_ID)
                .header("Authorization", authHeader))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verify(dynamoDBService, never()).deleteTunnel(any(), any());
    }
}
