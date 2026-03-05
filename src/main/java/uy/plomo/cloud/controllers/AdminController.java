package uy.plomo.cloud.controllers;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.plomo.cloud.platform.LightsailRemoteAccess;
import uy.plomo.cloud.services.DynamoDBService;
import uy.plomo.cloud.services.MqttService;
import uy.plomo.cloud.utils.JsonConverter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('USER')")
@Slf4j
@Tag(name = "02. Info", description = "User and device data")
public class AdminController {

    private final DynamoDBService dynamoDBService;
    private final MqttService mqttService;
    private final LightsailRemoteAccess lightsailRemoteAccess;

    public AdminController(DynamoDBService dynamoDBService,
                           MqttService mqttService,
                           LightsailRemoteAccess lightsailRemoteAccess) {
        this.dynamoDBService = dynamoDBService;
        this.mqttService = mqttService;
        this.lightsailRemoteAccess = lightsailRemoteAccess;
    }

    /**
     * Returns the user's profile plus DynamoDB metadata for each of their gateways.
     * Does a single DB call for the user, then fans out gateway calls in parallel.
     */
    @GetMapping("/summary")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> summary(
            @AuthenticationPrincipal String username) {

        return dynamoDBService.getUserSummary(username)
                .thenCompose(userItem -> {
                    List<String> gateways = userItem.containsKey("gateways")
                            ? (List<String>) userItem.get("gateways")
                            : List.of();
                    userItem.put("gateways", new HashMap<String, Object>());
                    // ---- FUTURE SSH (en paralelo, NO espera gateways) ----
                    CompletableFuture<List<Map<String, Object>>> sshFuture =
                            CompletableFuture.supplyAsync(lightsailRemoteAccess::listSshConnections);
                    // Fan out gateway DB lookups in parallel
                    List<CompletableFuture<Void>> gwFutures = gateways.stream()
                            .map(gw -> dynamoDBService.getGatewaySummary(gw)
                                    .thenAccept(gwItem -> {
                                        synchronized (userItem) {
                                            ((Map<String, Object>) userItem.get("gateways")).put(gw, gwItem);
                                        }
                                    }))
                            .toList();

                    CompletableFuture<Void> gatewaysDone =
                            CompletableFuture.allOf(gwFutures.toArray(new CompletableFuture[0]));

                    // ---- combinar TODO ----
                    return gatewaysDone
                            .thenCombine(sshFuture, (v, sshList) -> {
                                userItem.put("active_tunnels", sshList);
                                log.trace("Summary response for {}: {}", username, userItem);
                                return ResponseEntity.ok(userItem);
                            });
                });
    }

    /**
     * Returns live status from each gateway via MQTT, fetched in parallel.
     */
    @GetMapping("/gateways")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> gateways(
            @AuthenticationPrincipal String username) {

        return dynamoDBService.getUserSummary(username)
                .thenCompose(userItem -> {
                    List<String> gateways = userItem.containsKey("gateways")
                            ? (List<String>) userItem.get("gateways")
                            : List.of();

                    JSONObject result = new JSONObject();

                    // Fan out MQTT requests in parallel — no sequential blocking
                    List<CompletableFuture<Void>> mqttFutures = gateways.stream()
                            .map(gw -> {
                                JSONObject payload = new JSONObject();
                                payload.put("path", "GET:/summary");
                                payload.put("command", new JSONObject());

                                return mqttService.sendAsync( gw, payload)
                                        .thenAccept(gwStatus -> {
                                            synchronized (result) {
                                                result.put(gw, gwStatus);
                                            }
                                        });
                            })
                            .toList();

                    return CompletableFuture.allOf(mqttFutures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> {
                                log.debug("Gateways response for {}: {}", username, result);
                                return ResponseEntity.ok(JsonConverter.toMap(result.toString()));
                            });
                });
    }
}