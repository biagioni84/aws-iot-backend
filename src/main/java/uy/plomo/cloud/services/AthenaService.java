package uy.plomo.cloud.services;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaAsyncClient;
import software.amazon.awssdk.services.athena.model.*;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Queries cold telemetry from Athena (Parquet files in S3 written by ArchiveService).
 * Activated only when athena.output-location is configured.
 *
 * Expected Athena table DDL:
 *   CREATE EXTERNAL TABLE {database}.{table} (
 *     timestamp    STRING,
 *     payload_json STRING)
 *   PARTITIONED BY (dt STRING, gateway_id STRING)
 *   STORED AS PARQUET
 *   LOCATION 's3://{archive.s3.bucket}/{archive.s3.prefix}'
 *
 * Run MSCK REPAIR TABLE after each archive run to register new partitions.
 */
@Service
@ConditionalOnProperty(name = "athena.output-location")
@Slf4j
public class AthenaService {

    private static final int POLL_INTERVAL_SECONDS = 1;
    private static final int QUERY_TIMEOUT_SECONDS = 60;

    private final AthenaAsyncClient athena;
    private final ScheduledExecutorService poller = Executors.newScheduledThreadPool(2);
    private final String database;
    private final String table;
    private final String outputLocation;

    @Autowired
    public AthenaService(
            @Value("${aws.region}") String region,
            @Value("${athena.database}") String database,
            @Value("${athena.table:telemetry_cold}") String table,
            @Value("${athena.output-location}") String outputLocation) {
        this.athena = AthenaAsyncClient.builder().region(Region.of(region)).build();
        this.database = database;
        this.table = table;
        this.outputLocation = outputLocation;
    }

    /** Package-private for tests. */
    AthenaService(AthenaAsyncClient athena, String database, String table, String outputLocation) {
        this.athena = athena;
        this.database = database;
        this.table = table;
        this.outputLocation = outputLocation;
    }

    /**
     * Queries cold telemetry for a gateway between two ISO-8601 timestamps.
     * Returns a list of { "timestamp": "...", "payload": {...} } maps, sorted by timestamp.
     * Uses partition pruning on dt to avoid full table scans.
     */
    public CompletableFuture<List<Map<String, Object>>> query(
            String gatewayId, String from, String to) {

        validateId(gatewayId);
        Instant fromInstant = Instant.parse(from);
        Instant toInstant   = Instant.parse(to);

        String fromDate = fromInstant.atZone(ZoneOffset.UTC).toLocalDate().toString();
        String toDate   = toInstant.atZone(ZoneOffset.UTC).toLocalDate().toString();

        // gateway_id and dt are partition columns — pruning avoids full table scans.
        // timestamp is a data column for fine-grained filtering within partitions.
        String sql = String.format(
                "SELECT timestamp, payload_json" +
                " FROM \"%s\".\"%s\"" +
                " WHERE gateway_id = '%s'" +
                "   AND dt BETWEEN '%s' AND '%s'" +
                "   AND timestamp BETWEEN '%s' AND '%s'" +
                " ORDER BY timestamp",
                database, table, gatewayId, fromDate, toDate, from, to);

        log.debug("Athena query for gateway {} [{} → {}]", gatewayId, from, to);

        return startQuery(sql)
                .thenCompose(this::pollUntilDone)
                .thenCompose(this::collectAllResults)
                .orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private CompletableFuture<String> startQuery(String sql) {
        return athena.startQueryExecution(StartQueryExecutionRequest.builder()
                .queryString(sql)
                .queryExecutionContext(QueryExecutionContext.builder()
                        .database(database).build())
                .resultConfiguration(ResultConfiguration.builder()
                        .outputLocation(outputLocation).build())
                .build())
                .thenApply(StartQueryExecutionResponse::queryExecutionId);
    }

    private CompletableFuture<String> pollUntilDone(String executionId) {
        CompletableFuture<String> promise = new CompletableFuture<>();
        scheduleCheck(executionId, promise);
        return promise;
    }

    private void scheduleCheck(String executionId, CompletableFuture<String> promise) {
        poller.schedule(() ->
                athena.getQueryExecution(GetQueryExecutionRequest.builder()
                                .queryExecutionId(executionId).build())
                        .whenComplete((response, ex) -> {
                            if (ex != null) {
                                promise.completeExceptionally(ex);
                                return;
                            }
                            QueryExecutionStatus status = response.queryExecution().status();
                            switch (status.state()) {
                                case SUCCEEDED  -> promise.complete(executionId);
                                case FAILED, CANCELLED -> promise.completeExceptionally(
                                        new RuntimeException("Athena query " + status.state()
                                                + ": " + status.stateChangeReason()));
                                default -> scheduleCheck(executionId, promise);
                            }
                        }),
                POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private CompletableFuture<List<Map<String, Object>>> collectAllResults(String executionId) {
        return collectPage(executionId, null, new ArrayList<>(), true);
    }

    private CompletableFuture<List<Map<String, Object>>> collectPage(
            String executionId, String nextToken,
            List<Map<String, Object>> acc, boolean firstPage) {

        GetQueryResultsRequest.Builder req = GetQueryResultsRequest.builder()
                .queryExecutionId(executionId);
        if (nextToken != null) req.nextToken(nextToken);

        return athena.getQueryResults(req.build()).thenCompose(response -> {
            List<Row> rows = response.resultSet().rows();
            // First page: row 0 is the header row — skip it
            rows.stream()
                    .skip(firstPage ? 1 : 0)
                    .map(this::parseRow)
                    .forEach(acc::add);

            if (response.nextToken() != null) {
                return collectPage(executionId, response.nextToken(), acc, false);
            }
            return CompletableFuture.completedFuture(acc);
        });
    }

    private Map<String, Object> parseRow(Row row) {
        // Columns are ordered: timestamp, payload_json (matches SELECT order)
        String timestamp   = row.data().get(0).varCharValue();
        String payloadJson = row.data().get(1).varCharValue();
        Map<String, Object> payload = payloadJson != null && !payloadJson.isBlank()
                ? new JSONObject(payloadJson).toMap()
                : Map.of();
        return Map.of("timestamp", timestamp, "payload", payload);
    }

    private static void validateId(String id) {
        if (!id.matches("[a-zA-Z0-9_:/-]+")) {
            throw new IllegalArgumentException("Invalid gateway ID: " + id);
        }
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
        athena.close();
    }
}
