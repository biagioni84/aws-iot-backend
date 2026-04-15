package uy.plomo.cloud.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uy.plomo.cloud.entity.Gateway;
import uy.plomo.cloud.security.JwtService;
import uy.plomo.cloud.services.GatewayService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@Tag(name = "01. Authentication", description = "Login and Token management")
public class LoginController {

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, String status) {}
    public record RegisterRequest(String username, String password) {}

    private final JwtService jwtService;
    private final GatewayService gatewayService;
    private final PasswordEncoder passwordEncoder;

    public LoginController(JwtService jwtService, GatewayService gatewayService, PasswordEncoder passwordEncoder) {
        this.jwtService = jwtService;
        this.gatewayService = gatewayService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/auth/register")
    @Operation(summary = "Register a new user (admin only)", description = "Requires X-Admin-Key header.")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest req) {
        gatewayService.registerUser(req.username(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("username", req.username()));
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Login to get JWT", description = "Enter username and password to receive a Bearer token.")
    public CompletableFuture<LoginResponse> login(@RequestBody LoginRequest req) {
        return CompletableFuture.supplyAsync(() -> {
            var user = gatewayService.getUserWithGateways(req.username());

            if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
            }

            List<String> gatewayIds = user.getGateways().stream()
                    .map(Gateway::getId)
                    .toList();

            String token = jwtService.generateToken(
                    req.username(),
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

            return new LoginResponse(token, "ok");
        });
    }
}
