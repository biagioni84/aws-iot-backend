package uy.plomo.cloud.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uy.plomo.cloud.services.GatewayService;

@RestController
@PreAuthorize("hasRole('USER')")
public class UserController {

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    private final GatewayService gatewayService;

    public UserController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/api/v1/user/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal String username,
            @RequestBody ChangePasswordRequest req) {
        gatewayService.changePassword(username, req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }
}
