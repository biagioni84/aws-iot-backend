package uy.plomo.cloud.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.athena.AthenaAsyncClient;
import software.amazon.awssdk.services.athena.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AthenaService")
class AthenaServiceTest {

    @Mock AthenaAsyncClient athena;

    AthenaService service;

    @BeforeEach
    void setUp() {
        service = new AthenaService(athena, "mydb", "telemetry_cold",
                "s3://bucket/athena-results/");
    }

    // -------------------------------------------------------------------------
    // query — happy path
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("query")
    class Query {

        @Test
        @DisplayName("returns parsed rows when Athena query succeeds")
        void returnsRows() {
            stubStart("exec-1");
            stubStatus("exec-1", QueryExecutionState.SUCCEEDED);
            stubResults("exec-1", List.of(
                    row("timestamp", "payload_json"),                            // header
                    row("2025-06-01T10:00:00Z", "{\"temp\":22.5}")
            ));

            List<Map<String, Object>> result = service
                    .query("gw-001", "2025-06-01T00:00:00Z", "2025-06-01T23:59:59Z")
                    .join();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("timestamp")).isEqualTo("2025-06-01T10:00:00Z");
            Map<?, ?> payload = (Map<?, ?>) result.get(0).get("payload");
            assertThat(((Number) payload.get("temp")).doubleValue()).isEqualTo(22.5);
        }

        @Test
        @DisplayName("returns empty list when Athena returns no data rows")
        void returnsEmptyList() {
            stubStart("exec-2");
            stubStatus("exec-2", QueryExecutionState.SUCCEEDED);
            stubResults("exec-2", List.of(row("timestamp", "payload_json")));

            List<Map<String, Object>> result = service
                    .query("gw-001", "2025-06-01T00:00:00Z", "2025-06-01T23:59:59Z")
                    .join();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("fails future when Athena query status is FAILED")
        void failsOnQueryFailure() {
            stubStart("exec-3");
            stubStatusFailed("exec-3", "Table not found");

            CompletableFuture<List<Map<String, Object>>> future = service
                    .query("gw-001", "2025-06-01T00:00:00Z", "2025-06-01T23:59:59Z");

            assertThatThrownBy(future::join)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Table not found");
        }

        @Test
        @DisplayName("rejects gateway IDs with special characters")
        void rejectsInvalidGatewayId() {
            assertThatThrownBy(() ->
                    service.query("gw'; DROP TABLE--", "2025-06-01T00:00:00Z", "2025-06-01T23:59:59Z"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(athena, never()).startQueryExecution(any(StartQueryExecutionRequest.class));
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private void stubStart(String execId) {
        when(athena.startQueryExecution(any(StartQueryExecutionRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        StartQueryExecutionResponse.builder().queryExecutionId(execId).build()));
    }

    private void stubStatus(String execId, QueryExecutionState state) {
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueryExecutionResponse.builder()
                                .queryExecution(QueryExecution.builder()
                                        .queryExecutionId(execId)
                                        .status(QueryExecutionStatus.builder()
                                                .state(state).build())
                                        .build())
                                .build()));
    }

    private void stubStatusFailed(String execId, String reason) {
        when(athena.getQueryExecution(any(GetQueryExecutionRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueryExecutionResponse.builder()
                                .queryExecution(QueryExecution.builder()
                                        .queryExecutionId(execId)
                                        .status(QueryExecutionStatus.builder()
                                                .state(QueryExecutionState.FAILED)
                                                .stateChangeReason(reason).build())
                                        .build())
                                .build()));
    }

    private void stubResults(String execId, List<Row> rows) {
        when(athena.getQueryResults(any(GetQueryResultsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetQueryResultsResponse.builder()
                                .resultSet(ResultSet.builder()
                                        .rows(rows)
                                        .resultSetMetadata(ResultSetMetadata.builder().build())
                                        .build())
                                .build()));
    }

    private static Row row(String col0, String col1) {
        return Row.builder().data(
                Datum.builder().varCharValue(col0).build(),
                Datum.builder().varCharValue(col1).build()
        ).build();
    }
}
