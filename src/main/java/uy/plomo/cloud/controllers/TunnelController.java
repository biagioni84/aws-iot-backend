package uy.plomo.cloud.controllers;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import uy.plomo.cloud.services.DynamoDBService;
import uy.plomo.cloud.services.MqttService;
import uy.plomo.cloud.services.PortPoolService;
import uy.plomo.cloud.services.DynamoDBService.TunnelRequest;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "03. Tunnels", description = "CRUD and start/stop tunnels")
@Slf4j
public class TunnelController {
    @Value("${tunnel.server.host}")
    private String serverHost;

    private final DynamoDBService dynamoDBService;
    private final MqttService mqttService;
    private final PortPoolService portPoolService;

    public TunnelController(DynamoDBService dynamoDBService,
                            MqttService mqttService,
                            PortPoolService portPoolService) {
        this.dynamoDBService = dynamoDBService;
        this.mqttService = mqttService;
        this.portPoolService = portPoolService;
    }


    @GetMapping("/{gwId}/tunnels")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelList(
            @Parameter(ref = "#/components/parameters/gwParam") @PathVariable String gwId) {

        return dynamoDBService.getTunnelList(gwId)
                .thenApply(ResponseEntity::ok);
    }


    @GetMapping("/{gwId}/tunnels/{tunnelId}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelDetail(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {
        return dynamoDBService.getTunnelDetail(gwId, tunnelId)
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/{gwId}/tunnels/{tunnelId}/start")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelStart(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {

        log.info("Starting tunnel {} on gateway {}", tunnelId, gwId);

        // Step 1: fetch gateway summary (to get its pubkey)
        JSONObject summaryPayload = new JSONObject();
        summaryPayload.put("path", "GET:/summary");
        summaryPayload.put("command", new JSONObject());

        return mqttService.sendAsync(gwId, summaryPayload)
                .thenCompose(gwSummary -> {
                    // Step 2: fetch tunnel config from DynamoDB
                    return dynamoDBService.getTunnelDetail(gwId, tunnelId)
                            .thenCompose(tunnel -> {
                                // Step 3: build and send the start command
                                JSONObject command = new JSONObject();
                                command.put("cmd", "start");
                                command.put("src-addr", tunnel.get("src_addr"));
                                command.put("src-port", tunnel.get("src_port"));

                                if ("on".equals(tunnel.get("use_this_server"))) {
                                    String dstPort = portPoolService.assignPort(
                                            (String) tunnel.get("dst_port"),
                                            "iot-" + gwId,
                                            gwSummary.get("pubkey").toString());
                                    command.put("dst-addr", serverHost());
                                    command.put("dst-port", dstPort);
                                }

                                JSONObject payload = new JSONObject();
                                payload.put("path", "POST:/tunnel");
                                payload.put("command", command);

                                return mqttService.sendAsync(gwId, payload);
                            });
                })
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/{gwId}/tunnels/{tunnelId}/stop")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelStop(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {

        log.info("Stopping tunnel {} on gateway {}", tunnelId, gwId);

        return dynamoDBService.getTunnelDetail(gwId, tunnelId)
                .thenCompose(tunnel -> {
                    JSONObject command = new JSONObject();
                    command.put("cmd", "stop");
                    command.put("src-addr", tunnel.get("src_addr"));
                    command.put("src-port", tunnel.get("src_port"));

                    if ("on".equals(tunnel.get("use_this_server"))) {
                        command.put("dst-addr", serverHost());
                        command.put("dst-port", tunnel.get("dst_port"));
                    }

                    JSONObject payload = new JSONObject();
                    payload.put("path", "POST:/tunnel");
                    payload.put("command", command);

                    return mqttService.sendAsync(gwId, payload)
                            .thenApply(response -> {
                                portPoolService.releasePort((String) tunnel.get("dst_port"));
                                return response;
                            });
                })
                .thenApply(ResponseEntity::ok);
    }


    /**
     * Creates a new tunnel with a backend-generated UUID.
     * Returns 201 Created with a Location header pointing to the new resource.
     */
    @PostMapping("/{gwId}/tunnels")
    public CompletableFuture<ResponseEntity<Map<String, String>>> newTunnel(
            @PathVariable String gwId,
            @RequestBody TunnelRequest body) {

        String tunnelId = UUID.randomUUID().toString();
        log.info("Creating tunnel {} on gateway {}", tunnelId, gwId);

        return dynamoDBService.createTunnel(gwId, tunnelId, body)
                .thenApply(v -> ResponseEntity
                        .created(URI.create("/api/v1/gateways/" + gwId + "/tunnels/" + tunnelId))
                        .body(Map.of("tunnelId", tunnelId)));
    }

    /**
     * Replaces all editable fields of an existing tunnel.
     * The tunnel must not be running — stop it first if needed.
     */
    @PutMapping("/{gwId}/tunnels/{tunnelId}")
    public CompletableFuture<ResponseEntity<Void>> updateTunnel(
            @PathVariable String gwId,
            @PathVariable String tunnelId,
            @RequestBody TunnelRequest body) {

        log.info("Updating tunnel {} on gateway {}", tunnelId, gwId);

        return dynamoDBService.updateTunnel(gwId, tunnelId, body)
                .thenApply(v -> ResponseEntity.<Void>noContent().build());
    }

    /**
     * Stops the tunnel if active, then removes it from DynamoDB.
     */
    @DeleteMapping("/{gwId}/tunnels/{tunnelId}")
    public CompletableFuture<ResponseEntity<Void>> deleteTunnel(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {

        log.info("Deleting tunnel {} on gateway {}", tunnelId, gwId);

        // Fetch tunnel first to build the stop command and check use_this_server
        return dynamoDBService.getTunnelDetail(gwId, tunnelId)
                .thenCompose(tunnel -> {
                    // Send stop command to gateway — ignore errors (tunnel may already be stopped)
                    JSONObject command = new JSONObject();
                    command.put("cmd", "stop");
                    command.put("src-addr", tunnel.get("src_addr"));
                    command.put("src-port", tunnel.get("src_port"));

                    if ("on".equals(tunnel.get("use_this_server"))) {
                        command.put("dst-addr", portPoolService.getServerHost());
                        command.put("dst-port", tunnel.get("dst_port"));
                    }

                    JSONObject payload = new JSONObject();
                    payload.put("path", "POST:/tunnel");
                    payload.put("command", command);

                    return mqttService.sendAsync(gwId, payload)
                            .handle((resp, ex) -> {
                                // Release port regardless of MQTT result
                                if ("on".equals(tunnel.get("use_this_server"))) {
                                    portPoolService.releasePort((String) tunnel.get("dst_port"));
                                }
                                if (ex != null) {
                                    log.warn("Stop command failed for tunnel {}, proceeding with delete: {}",
                                            tunnelId, ex.getMessage());
                                }
                                return tunnel;
                            });
                })
                .thenCompose(tunnel -> dynamoDBService.deleteTunnel(gwId, tunnelId))
                .thenApply(v -> ResponseEntity.<Void>noContent().build());
    }


    /**
     * Centralizes the server host so it's only hardcoded in one place.
     * TODO: move this to application.properties as tunnel.server.host
     */
    private String serverHost() {
        return serverHost;
    }
}