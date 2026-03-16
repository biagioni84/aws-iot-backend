package uy.plomo.cloud.services;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("TelemetryService")
class TelemetryServiceTest {

    private DynamoDbAsyncClient dynamo;
    private TelemetryService service;

    @BeforeEach
    void setUp() {
        dynamo = mock(DynamoDbAsyncClient.class);
        service = new TelemetryService(dynamo);
    }

    // -------------------------------------------------------------------------
    // save
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("calls putItem with correct table, gateway_id, timestamp, and ttl")
        void callsPutItemWithCorrectAttributes() {
            when(dynamo.putItem(any(PutItemRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            PutItemResponse.builder().build()));

            JSONObject payload = new JSONObject(Map.of("temp", 22.5, "humidity", 60));
            service.save("gw-001", "2025-06-01T12:00:00.000Z", payload).join();

            var captor = org.mockito.ArgumentCaptor.forClass(PutItemRequest.class);
            verify(dynamo).putItem(captor.capture());

            PutItemRequest req = captor.getValue();
            assertThat(req.tableName()).isEqualTo("iot-telemetry");
            assertThat(req.item()).containsKey("gateway_id");
            assertThat(req.item().get("gateway_id").s()).isEqualTo("gw-001");
            assertThat(req.item()).containsKey("timestamp");
            assertThat(req.item().get("timestamp").s()).isEqualTo("2025-06-01T12:00:00.000Z");
            assertThat(req.item()).containsKey("payload");
            assertThat(req.item()).containsKey("ttl");
            // TTL should be roughly now + 48h (in seconds)
            long ttl = Long.parseLong(req.item().get("ttl").n());
            long expectedMin = System.currentTimeMillis() / 1000 + (47 * 3600);
            long expectedMax = System.currentTimeMillis() / 1000 + (49 * 3600);
            assertThat(ttl).isBetween(expectedMin, expectedMax);
        }

        @Test
        @DisplayName("propagates DynamoDB errors")
        void propagatesErrors() {
            when(dynamo.putItem(any(PutItemRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            ResourceNotFoundException.builder().message("table not found").build()));

            JSONObject payload = new JSONObject(Map.of("temp", 22.5));
            CompletableFuture<Void> future = service.save("gw-001", "2025-06-01T12:00:00.000Z", payload);

            assertThatThrownBy(future::join)
                    .hasCauseInstanceOf(ResourceNotFoundException.class);
        }
    }

    // -------------------------------------------------------------------------
    // query
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("query")
    class Query {

        @Test
        @DisplayName("returns list of timestamp+payload maps")
        void returnsFormattedItems() {
            Map<String, AttributeValue> item = Map.of(
                    "gateway_id", AttributeValue.builder().s("gw-001").build(),
                    "timestamp",  AttributeValue.builder().s("2025-06-01T12:00:00.000Z").build(),
                    "payload",    AttributeValue.builder().m(Map.of(
                            "temp", AttributeValue.builder().n("22.5").build()
                    )).build()
            );
            QueryResponse response = QueryResponse.builder().items(List.of(item)).build();
            when(dynamo.query(any(QueryRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(response));

            List<Map<String, Object>> result = service.query(
                    "gw-001", "2025-06-01T00:00:00Z", "2025-06-01T23:59:59Z").join();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("timestamp")).isEqualTo("2025-06-01T12:00:00.000Z");
            assertThat(result.get(0)).containsKey("payload");
        }

        @Test
        @DisplayName("queries with correct key condition and expression attribute names")
        void usesCorrectKeyCondition() {
            when(dynamo.query(any(QueryRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            QueryResponse.builder().items(List.of()).build()));

            service.query("gw-001", "2025-06-01T00:00:00Z", "2025-06-01T23:59:59Z").join();

            var captor = org.mockito.ArgumentCaptor.forClass(QueryRequest.class);
            verify(dynamo).query(captor.capture());

            QueryRequest req = captor.getValue();
            assertThat(req.tableName()).isEqualTo("iot-telemetry");
            assertThat(req.keyConditionExpression()).contains("BETWEEN");
            assertThat(req.expressionAttributeNames()).containsKey("#ts");
            assertThat(req.expressionAttributeValues().get(":gwId").s()).isEqualTo("gw-001");
        }

        @Test
        @DisplayName("returns empty list when no items found")
        void returnsEmptyList() {
            when(dynamo.query(any(QueryRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(
                            QueryResponse.builder().items(List.of()).build()));

            List<Map<String, Object>> result = service.query(
                    "gw-001", "2025-06-01T00:00:00Z", "2025-06-01T23:59:59Z").join();

            assertThat(result).isEmpty();
        }
    }
}
