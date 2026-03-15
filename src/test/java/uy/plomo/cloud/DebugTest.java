package uy.plomo.cloud;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

class DebugTest extends BaseControllerTest {

    @Test
    void debug_validateTokenParsing() throws Exception {
        String token = bearerToken("alice", List.of("gw-001"));
        System.out.println(">>> TOKEN: " + token);

        try {
            var claims = jwtService.extractAllClaims(token.replace("Bearer ", ""));
            System.out.println(">>> CLAIMS OK: sub=" + claims.getSubject()
                    + ", roles=" + claims.get("roles")
                    + ", gateways=" + claims.get("gateways"));
        } catch (Exception e) {
            System.out.println(">>> CLAIMS FAILED: " + e.getClass().getName() + ": " + e.getMessage());
        }

        // GatewayService es síncrono — retorna Map directamente
        when(gatewayService.getTunnelList("gw-001")).thenReturn(Map.of());

        mockMvc.perform(
                get("/api/v1/gw-001/tunnels")
                        .header("Authorization", token)
        ).andDo(print());
    }
}
