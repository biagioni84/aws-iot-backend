package uy.plomo.cloud.controllers;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.plomo.cloud.services.MqttService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@PreAuthorize("hasRole('USER')")
@Slf4j
public class GatewayController {

    private final MqttService mqttService;

    public GatewayController(MqttService mqttService) {
        this.mqttService = mqttService;
    }

    @RequestMapping(value = "/api/v1/{gwId}/proxy/{*path}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public CompletableFuture<ResponseEntity<Map<String, Object>>> proxy(
            @AuthenticationPrincipal String username,
            @PathVariable String gwId,
            @PathVariable String path,
            HttpMethod httpMethod,
            @RequestBody(required = false) Map<String, Object> body) {
        JSONObject payload = new JSONObject();
        payload.put("path", httpMethod + ":" + path);
        payload.put("command", body != null ? new JSONObject(body) : new JSONObject());

        log.debug("Proxying {} to gateway {}: {}", httpMethod + ":" + path, gwId, payload);

        return mqttService.sendAsync(gwId, payload)
                .thenApply(ResponseEntity::ok);
    }
}