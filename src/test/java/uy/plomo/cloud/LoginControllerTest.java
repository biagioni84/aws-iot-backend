package uy.plomo.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.MediaType;
import uy.plomo.cloud.entity.User;
import uy.plomo.cloud.services.GatewayService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("POST /auth/login")
class LoginControllerTest extends BaseControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("returns 200 and a JWT token when credentials are valid")
    void login_validCredentials_returnsToken() throws Exception {
        User alice = User.create("alice", encoder.encode("secret123"));
        when(gatewayService.getUserWithGateways("alice")).thenReturn(alice);

        perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", "alice",
                        "password", "secret123"
                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("returns 401 when password is wrong")
    void login_wrongPassword_returns401() throws Exception {
        User alice = User.create("alice", encoder.encode("correct-password"));
        when(gatewayService.getUserWithGateways("alice")).thenReturn(alice);

        perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", "alice",
                        "password", "wrong-password"
                ))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("returns 404 when user does not exist")
    void login_unknownUser_returns404() throws Exception {
        when(gatewayService.getUserWithGateways("nobody"))
                .thenThrow(new GatewayService.ResourceNotFoundException("User not found: nobody"));

        perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "username", "nobody",
                        "password", "whatever"
                ))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("returns 5xx when body is missing")
    void login_missingBody_returns5xx() throws Exception {
        // HttpMessageNotReadableException es síncrona — no hay async dispatch
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is5xxServerError());
    }

    // -------------------------------------------------------------------------
    // POST /auth/register
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /auth/register -- returns 201 with valid admin key")
    void register_validKey_returns201() throws Exception {
        doNothing().when(gatewayService).registerUser(any(), any());

        mockMvc.perform(post("/auth/register")
                        .header("X-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "newuser",
                                "password", "pass123"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser"));
    }

    @Test
    @DisplayName("POST /auth/register -- returns 409 when username already exists")
    void register_duplicate_returns409() throws Exception {
        doThrow(new GatewayService.ConflictException("Username already exists: newuser"))
                .when(gatewayService).registerUser(any(), any());

        mockMvc.perform(post("/auth/register")
                        .header("X-Admin-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "newuser",
                                "password", "pass123"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    @DisplayName("POST /auth/register -- returns 403 without admin key")
    void register_noKey_returns403() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "newuser",
                                "password", "pass123"
                        ))))
                .andExpect(status().isForbidden());
    }
}
