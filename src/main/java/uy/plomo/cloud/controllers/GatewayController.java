package uy.plomo.cloud.controllers;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.plomo.cloud.dto.GatewayRegistrationRequest;
import uy.plomo.cloud.services.GatewayService;
import uy.plomo.cloud.services.MqttService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@PreAuthorize("hasRole('USER')")
@Slf4j
public class GatewayController {

    private final MqttService mqttService;
    private final GatewayService gatewayService;

    public GatewayController(MqttService mqttService, GatewayService gatewayService) {
        this.mqttService = mqttService;
        this.gatewayService = gatewayService;
    }

    @PostMapping("/api/v1/gateways")
    public ResponseEntity<Map<String, String>> registerGateway(
            @AuthenticationPrincipal String username,
            @RequestBody GatewayRegistrationRequest req) {
        String gatewayId = gatewayService.registerGateway(username, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("gateway_id", gatewayId));
    }

    @RequestMapping(value = "/api/v1/{gwId}/proxy/{*path}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public CompletableFuture<ResponseEntity<Map<String, Object>>> proxy(
            @AuthenticationPrincipal String username,
            @PathVariable String gwId,
            @PathVariable String path,
            HttpMethod httpMethod,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = Map.of(
                "path", httpMethod + ":" + path,
                "command", body != null ? body : Map.of());

        log.debug("Proxying {} to gateway {}: {}", httpMethod + ":" + path, gwId, payload);

        return mqttService.sendAsync(gwId, payload)
                .thenApply(ResponseEntity::ok);
    }
}