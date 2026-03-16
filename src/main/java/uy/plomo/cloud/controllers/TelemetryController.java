package uy.plomo.cloud.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.plomo.cloud.services.TelemetryService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@PreAuthorize("hasRole('USER')")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping("/api/v1/{gwId}/telemetry")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getTelemetry(
            @PathVariable String gwId,
            @RequestParam String from,
            @RequestParam String to) {
        return telemetryService.query(gwId, from, to)
                .thenApply(ResponseEntity::ok);
    }
}
