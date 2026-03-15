package uy.plomo.cloud.controllers;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.plomo.cloud.dto.TunnelRequest;
import uy.plomo.cloud.platform.LightsailRemoteAccess;
import uy.plomo.cloud.services.GatewayService;
import uy.plomo.cloud.services.MqttService;
import uy.plomo.cloud.services.PortPoolService;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "03. Tunnels", description = "CRUD and start/stop tunnels")
@PreAuthorize("hasRole('USER')")
@Slf4j
public class TunnelController {

    @Value("${tunnel.server.host}")
    private String serverHost;

    private final GatewayService gatewayService;
    private final MqttService mqttService;
    private final PortPoolService portPoolService;
    private final LightsailRemoteAccess lightsailRemoteAccess;

    public TunnelController(GatewayService gatewayService,
                            MqttService mqttService,
                            PortPoolService portPoolService,
                            LightsailRemoteAccess lightsailRemoteAccess) {
        this.gatewayService = gatewayService;
        this.mqttService = mqttService;
        this.portPoolService = portPoolService;
        this.lightsailRemoteAccess = lightsailRemoteAccess;
    }

    @GetMapping("/{gwId}/tunnels")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelList(
            @Parameter(ref = "#/components/parameters/gwParam") @PathVariable String gwId) {

        return CompletableFuture.supplyAsync(() -> gatewayService.getTunnelList(gwId))
                .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/{gwId}/tunnels/{tunnelId}")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelDetail(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {

        return CompletableFuture.supplyAsync(() -> gatewayService.getTunnelDetail(gwId, tunnelId))
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/{gwId}/tunnels")
    public CompletableFuture<ResponseEntity<Map<String, String>>> newTunnel(
            @PathVariable String gwId,
            @RequestBody TunnelRequest body) {

        return CompletableFuture.supplyAsync(() -> gatewayService.createTunnel(gwId, body))
                .thenApply(tunnelId -> {
                    log.info("Created tunnel {} on gateway {}", tunnelId, gwId);
                    return ResponseEntity
                            .created(URI.create("/api/v1/" + gwId + "/tunnels/" + tunnelId))
                            .body(Map.of("tunnelId", tunnelId));
                });
    }

    @PutMapping("/{gwId}/tunnels/{tunnelId}")
    public CompletableFuture<ResponseEntity<Void>> updateTunnel(
            @PathVariable String gwId,
            @PathVariable String tunnelId,
            @RequestBody TunnelRequest body) {

        return CompletableFuture.runAsync(() -> {
            log.info("Updating tunnel {} on gateway {}", tunnelId, gwId);
            gatewayService.updateTunnel(gwId, tunnelId, body);
        }).thenApply(v -> ResponseEntity.<Void>noContent().build());
    }

    @DeleteMapping("/{gwId}/tunnels/{tunnelId}")
    public CompletableFuture<ResponseEntity<Void>> deleteTunnel(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {

        log.info("Deleting tunnel {} on gateway {}", tunnelId, gwId);

        return CompletableFuture.supplyAsync(() -> gatewayService.getTunnelDetail(gwId, tunnelId))
                .thenCompose(tunnel -> {
                    // Enviamos stop al gateway — ignoramos errores (puede estar offline)
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
                .thenCompose(tunnel -> CompletableFuture.runAsync(() ->
                        gatewayService.deleteTunnel(gwId, tunnelId)))
                .thenApply(v -> ResponseEntity.<Void>noContent().build());
    }


    @PostMapping("/{gwId}/tunnels/{tunnelId}/start")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelStart(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {

        log.info("Starting tunnel {} on gateway {}", tunnelId, gwId);

        JSONObject summaryPayload = new JSONObject();
        summaryPayload.put("path", "GET:/summary");
        summaryPayload.put("command", new JSONObject());

        return mqttService.sendAsync(gwId, summaryPayload)
                .thenCompose(gwSummary ->
                        CompletableFuture.supplyAsync(() -> gatewayService.getTunnelDetail(gwId, tunnelId))
                                .thenCompose(tunnel -> {
                                    JSONObject command = new JSONObject();
                                    command.put("cmd", "start");
                                    command.put("src-addr", tunnel.get("src_addr"));
                                    command.put("src-port", tunnel.get("src_port"));

                                    final String assignedPort;
                                    if ("on".equals(tunnel.get("use_this_server"))) {
                                        assignedPort = portPoolService.assignPort(
                                                (String) tunnel.get("dst_port"),
                                                "iot-" + gwId,
                                                gwSummary.get("pubkey").toString());
                                        command.put("dst-addr", serverHost);
                                        command.put("dst-port", assignedPort);
                                    } else {
                                        assignedPort = null;
                                    }

                                    JSONObject payload = new JSONObject();
                                    payload.put("path", "POST:/tunnel");
                                    payload.put("command", command);

                                    return mqttService.sendAsync(gwId, payload)
                                            .thenApply(response -> {
                                                if (assignedPort != null) {
                                                    gatewayService.markTunnelActive(
                                                            gwId, tunnelId, Integer.parseInt(assignedPort));
                                                }
                                                return response;
                                            });
                                }))
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/{gwId}/tunnels/{tunnelId}/stop")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> tunnelStop(
            @PathVariable String gwId,
            @PathVariable String tunnelId) {

        log.info("Stopping tunnel {} on gateway {}", tunnelId, gwId);

        return CompletableFuture.supplyAsync(() -> gatewayService.getTunnelDetail(gwId, tunnelId))
                .thenCompose(tunnel -> {
                    JSONObject command = new JSONObject();
                    command.put("cmd", "stop");
                    command.put("src-addr", tunnel.get("src_addr"));
                    command.put("src-port", tunnel.get("src_port"));

                    if ("on".equals(tunnel.get("use_this_server"))) {
                        command.put("dst-addr", serverHost);
                        command.put("dst-port", tunnel.get("dst_port"));
                    }

                    JSONObject payload = new JSONObject();
                    payload.put("path", "POST:/tunnel");
                    payload.put("command", command);

                    return mqttService.sendAsync(gwId, payload)
                            .thenApply(response -> {
                                if ("on".equals(tunnel.get("use_this_server"))) {
                                    String dstPort = (String) tunnel.get("dst_port");
                                    portPoolService.releasePort(dstPort);

                                    LightsailRemoteAccess.ShellResult kill =
                                            lightsailRemoteAccess.killSshTunnelByPort(dstPort);
                                    if (kill.exit() != 0) {
                                        log.warn("killSshTunnelByPort({}) exited {}: {}",
                                                dstPort, kill.exit(), kill.out());
                                    } else {
                                        log.info("SSH tunnel on port {} killed", dstPort);
                                    }
                                }
                                gatewayService.markTunnelStopped(gwId, tunnelId);
                                return response;
                            });
                })
                .thenApply(ResponseEntity::ok);
    }
}