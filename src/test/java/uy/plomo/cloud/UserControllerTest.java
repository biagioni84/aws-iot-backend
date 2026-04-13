package uy.plomo.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("UserController")
class UserControllerTest extends BaseControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private String authHeader;

    @BeforeEach
    void setUp() {
        declareOwnership("alice");
        authHeader = bearerToken("alice", List.of());
    }

    @Test
    @DisplayName("POST /api/v1/user/password -- returns 204 when current password is correct")
    void changePassword_valid_returns204() throws Exception {
        doNothing().when(gatewayService).changePassword(any(), any(), any());

        mockMvc.perform(post("/api/v1/user/password")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "currentPassword", "oldpass",
                                "newPassword", "newpass"
                        ))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/v1/user/password -- returns 401 when current password is wrong")
    void changePassword_wrongCurrent_returns401() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect"))
                .when(gatewayService).changePassword(any(), any(), any());

        mockMvc.perform(post("/api/v1/user/password")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "currentPassword", "wrong",
                                "newPassword", "newpass"
                        ))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/user/password -- returns 401 without token")
    void changePassword_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/user/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "currentPassword", "oldpass",
                                "newPassword", "newpass"
                        ))))
                .andExpect(status().isUnauthorized());
    }
}
