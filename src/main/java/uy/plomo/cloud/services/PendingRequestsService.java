package uy.plomo.cloud.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class PendingRequestsService {

    private static final long TIMEOUT_SECONDS = 30;

    private record PendingRequest(CompletableFuture<String> future, Instant createdAt) {}

    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();

    /**
     * Register a new pending request. Returns the future that will be
     * completed when the gateway responds.
     */
    public CompletableFuture<String> create(String id) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(id, new PendingRequest(future, Instant.now()));
        log.debug("Created pending request: {}", id);
        return future;
    }

    /**
     * Complete a pending request with the gateway's response payload.
     */
    public void complete(String id, String payload) {
        PendingRequest request = pending.remove(id);
        if (request != null) {
            request.future().complete(payload);
            log.debug("Completed pending request: {}", id);
        } else {
            log.warn("Received response for unknown or already-expired request: {}", id);
        }
    }

    /**
     * Cancel a pending request — used when orTimeout fires before the
     * scheduled cleanup runs.
     */
    public void cancel(String id) {
        PendingRequest request = pending.remove(id);
        if (request != null && !request.future().isDone()) {
            request.future().completeExceptionally(
                    new TimeoutException("Request " + id + " cancelled"));
            log.debug("Cancelled pending request: {}", id);
        }
    }

    /**
     * Fail all pending requests with the given exception.
     * Called when the MQTT connection is lost so callers get an immediate
     * error instead of waiting for the timeout.
     */
    public void failAll(Throwable cause) {
        int count = pending.size();
        pending.forEach((id, req) -> {
            if (!req.future().isDone()) {
                req.future().completeExceptionally(cause);
            }
        });
        pending.clear();
        if (count > 0) {
            log.info("Failed {} pending requests due to: {}", count, cause.getMessage());
        }
    }
    /**
     * Fallback cleanup for any futures that slipped through (e.g. if the
     * caller didn't call cancel). Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanupExpired() {
        long now = Instant.now().getEpochSecond();
        int removed = 0;

        for (Map.Entry<String, PendingRequest> entry : pending.entrySet()) {
            long age = now - entry.getValue().createdAt().getEpochSecond();
            if (age > TIMEOUT_SECONDS) {
                PendingRequest req = pending.remove(entry.getKey());
                if (req != null && !req.future().isDone()) {
                    req.future().completeExceptionally(
                            new TimeoutException("Request " + entry.getKey() + " timed out"));
                    removed++;
                }
            }
        }

        if (removed > 0) {
            log.info("Cleanup removed {} expired pending requests", removed);
        }
    }

    public int getPendingCount() {
        return pending.size();
    }
}