package uy.plomo.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.MediaType;
import uy.plomo.cloud.services.DynamoDBService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("POST /auth/login")
class LoginControllerTest extends BaseControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("returns 200 and a JWT token when credentials are valid")
    void login_validCredentials_returnsToken() throws Exception {
        String hashedPassword = BCrypt.hashpw("secret123", BCrypt.gensalt());
        when(dynamoDBService.getUserSummary("alice")).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "username", "alice",
                        "password", hashedPassword,
                        "gateways", List.of("gw-001")
                ))
        );

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
        String hashedPassword = BCrypt.hashpw("correct-password", BCrypt.gensalt());
        when(dynamoDBService.getUserSummary("alice")).thenReturn(
                CompletableFuture.completedFuture(Map.of(
                        "username", "alice",
                        "password", hashedPassword,
                        "gateways", List.of()
                ))
        );

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
        when(dynamoDBService.getUserSummary("nobody")).thenReturn(
                CompletableFuture.failedFuture(
                        new DynamoDBService.ResourceNotFoundException("User not found: nobody")
                )
        );

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
        // HttpMessageNotReadableException es síncrona (Spring la lanza antes de entrar
        // al controller), así que no hay async dispatch — usar mockMvc.perform directo.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is5xxServerError());
    }
}