package uy.plomo.cloud.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import uy.plomo.cloud.security.JwtService;
import uy.plomo.cloud.services.DynamoDBService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@Tag(name = "01. Authentication", description = "Login and Token management")
public class LoginController {

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, String status) {}

    private final JwtService jwtService;
    private final DynamoDBService dbService;

    public LoginController(JwtService jwtService, DynamoDBService dbService) {
        this.jwtService = jwtService;
        this.dbService = dbService;
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Login to get JWT", description = "Enter username and password to receive a Bearer token.")
    public CompletableFuture<LoginResponse> login(@RequestBody LoginRequest req) {
        return dbService.getUserSummary(req.username())
                .thenApply(userItem -> {
                    // Verify password
                    String pwdHash = userItem
                            .getOrDefault("password", "").toString();
                    if (pwdHash.isEmpty() || !BCrypt.checkpw(req.password(), pwdHash)) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
                    }

                    // Extract gateway list from user record
                    List<String> gatewayIds = userItem.containsKey("gateways")
                            ? (List<String>) userItem.get("gateways")
                            : List.of();

                    String token = jwtService.generateToken(
                            req.username(),
                            List.of(new SimpleGrantedAuthority("ROLE_USER")),
                            gatewayIds);

                    return new LoginResponse(token, "ok");
                });
    }
}