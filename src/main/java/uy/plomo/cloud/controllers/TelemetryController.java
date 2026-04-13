package uy.plomo.cloud.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.plomo.cloud.services.AthenaService;
import uy.plomo.cloud.services.TelemetryService;
import uy.plomo.cloud.utils.TelemetryAggregator;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Unified telemetry endpoint: routes to DynamoDB (hot, last 48 h),
 * Athena (cold, >48 h), or both based on the requested time range.
 *
 * AthenaService is injected as Optional — if athena.output-location is not
 * configured, cold queries return an empty list with a warning.
 */
@RestController
@PreAuthorize("hasRole('USER')")
public class TelemetryController {

    static final long HOT_WINDOW_HOURS = 48;

    private final TelemetryService telemetryService;
    private final Optional<AthenaService> athenaService;

    public TelemetryController(TelemetryService telemetryService,
                               Optional<AthenaService> athenaService) {
        this.telemetryService = telemetryService;
        this.athenaService = athenaService;
    }

    @GetMapping("/api/v1/{gwId}/telemetry")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getTelemetry(
            @PathVariable String gwId,
            @RequestParam String from,
            @RequestParam String to) {
        return fetchRaw(gwId, from, to).thenApply(ResponseEntity::ok);
    }

    @GetMapping("/api/v1/{gwId}/telemetry/aggregate")
    public CompletableFuture<ResponseEntity<List<TelemetryAggregator.AggregatedPoint>>> getAggregate(
            @PathVariable String gwId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "1h") String window,
            @RequestParam(defaultValue = "avg") String fn) {

        Duration windowDuration = TelemetryAggregator.parseWindow(window);
        TelemetryAggregator.Fn aggFn = TelemetryAggregator.parseFn(fn);

        return fetchRaw(gwId, from, to)
                .thenApply(points -> ResponseEntity.ok(
                        TelemetryAggregator.aggregate(points, windowDuration, aggFn)));
    }

    private CompletableFuture<List<Map<String, Object>>> fetchRaw(
            String gwId, String from, String to) {

        Instant hotBoundary = Instant.now().minus(HOT_WINDOW_HOURS, ChronoUnit.HOURS);
        Instant fromInstant = Instant.parse(from);
        Instant toInstant   = Instant.parse(to);

        boolean needsHot  = toInstant.isAfter(hotBoundary);
        boolean needsCold = fromInstant.isBefore(hotBoundary);

        if (needsHot && needsCold) {
            String boundaryStr = hotBoundary.toString();
            CompletableFuture<List<Map<String, Object>>> hotFuture =
                    telemetryService.query(gwId, boundaryStr, to);
            CompletableFuture<List<Map<String, Object>>> coldFuture =
                    queryAthena(gwId, from, boundaryStr);

            return hotFuture.thenCombine(coldFuture, (hot, cold) -> {
                List<Map<String, Object>> merged = new ArrayList<>(cold);
                merged.addAll(hot);
                return merged;
            });
        }

        if (needsCold) return queryAthena(gwId, from, to);

        return telemetryService.query(gwId, from, to);
    }

    private CompletableFuture<List<Map<String, Object>>> queryAthena(
            String gwId, String from, String to) {
        return athenaService
                .map(svc -> svc.query(gwId, from, to))
                .orElseGet(() -> CompletableFuture.completedFuture(List.of()));
    }
}
