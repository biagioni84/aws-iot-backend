package uy.plomo.cloud.services;

import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TelemetryService {

    private static final String TABLE = "iot-telemetry";
    private static final long TTL_SECONDS = 48 * 3600L;

    private final DynamoDbAsyncClient dynamo;

    @Autowired
    public TelemetryService(@Value("${aws.region}") String region) {
        this.dynamo = DynamoDbAsyncClient.builder()
                .region(Region.of(region))
                .build();
    }

    /** Package-private constructor for unit tests — inject a mock client directly. */
    TelemetryService(DynamoDbAsyncClient dynamo) {
        this.dynamo = dynamo;
    }

    /**
     * Persists one telemetry message. Fire-and-forget: callers should attach
     * an exceptionally() handler and never block on the returned future.
     */
    public CompletableFuture<Void> save(String gatewayId, String timestamp, JSONObject payload) {
        long ttl = Instant.now().getEpochSecond() + TTL_SECONDS;
        Map<String, AttributeValue> payloadAttrMap = jsonObjectToAttributeMap(payload);

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE)
                .item(Map.of(
                        "gateway_id", AttributeValue.builder().s(gatewayId).build(),
                        "timestamp",  AttributeValue.builder().s(timestamp).build(),
                        "payload",    AttributeValue.builder().m(payloadAttrMap).build(),
                        "ttl",        AttributeValue.builder().n(String.valueOf(ttl)).build()
                ))
                .build();

        return dynamo.putItem(request)
                .thenAccept(r -> log.debug("Telemetry saved: {}/{}", gatewayId, timestamp));
    }

    /**
     * Queries telemetry for a gateway between two ISO-8601 timestamps (inclusive).
     * Returns a list of { "timestamp": "...", "payload": {...} } maps.
     */
    public CompletableFuture<List<Map<String, Object>>> query(
            String gatewayId, String from, String to) {

        QueryRequest request = QueryRequest.builder()
                .tableName(TABLE)
                .keyConditionExpression("gateway_id = :gwId AND #ts BETWEEN :from AND :to")
                .expressionAttributeNames(Map.of("#ts", "timestamp"))
                .expressionAttributeValues(Map.of(
                        ":gwId", AttributeValue.builder().s(gatewayId).build(),
                        ":from", AttributeValue.builder().s(from).build(),
                        ":to",   AttributeValue.builder().s(to).build()
                ))
                .build();

        return dynamo.query(request).thenApply(response ->
                response.items().stream()
                        .map(item -> {
                            String ts = item.get("timestamp").s();
                            AttributeValue payloadAttr = item.get("payload");
                            Map<String, Object> payloadMap = payloadAttr != null
                                    ? new JSONObject(EnhancedDocument
                                            .fromAttributeValueMap(payloadAttr.m()).toJson()).toMap()
                                    : Map.of();
                            return Map.<String, Object>of("timestamp", ts, "payload", payloadMap);
                        })
                        .collect(Collectors.toList())
        );
    }

    /**
     * Queries all pages of raw DynamoDB items for a gateway in the given time range.
     * Handles DynamoDB pagination (1 MB page limit) transparently.
     * Package-private — used by ArchiveService.
     */
    CompletableFuture<List<Map<String, AttributeValue>>> queryRawPaginated(
            String gatewayId, String from, String to) {
        return queryPage(gatewayId, from, to, null, new ArrayList<>());
    }

    private CompletableFuture<List<Map<String, AttributeValue>>> queryPage(
            String gatewayId, String from, String to,
            Map<String, AttributeValue> lastKey,
            List<Map<String, AttributeValue>> acc) {

        QueryRequest.Builder req = QueryRequest.builder()
                .tableName(TABLE)
                .keyConditionExpression("gateway_id = :gwId AND #ts BETWEEN :from AND :to")
                .expressionAttributeNames(Map.of("#ts", "timestamp"))
                .expressionAttributeValues(Map.of(
                        ":gwId", AttributeValue.builder().s(gatewayId).build(),
                        ":from", AttributeValue.builder().s(from).build(),
                        ":to",   AttributeValue.builder().s(to).build()
                ));
        if (lastKey != null) req.exclusiveStartKey(lastKey);

        return dynamo.query(req.build()).thenCompose(response -> {
            acc.addAll(response.items());
            if (response.hasLastEvaluatedKey()) {
                return queryPage(gatewayId, from, to, response.lastEvaluatedKey(), acc);
            }
            return CompletableFuture.completedFuture(acc);
        });
    }

    private static Map<String, AttributeValue> jsonObjectToAttributeMap(JSONObject json) {
        Map<String, AttributeValue> result = new HashMap<>();
        for (String key : json.keySet()) {
            result.put(key, toAttributeValue(json.get(key)));
        }
        return result;
    }

    private static AttributeValue toAttributeValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return AttributeValue.builder().nul(true).build();
        } else if (value instanceof String s) {
            return AttributeValue.builder().s(s).build();
        } else if (value instanceof Number n) {
            return AttributeValue.builder().n(n.toString()).build();
        } else if (value instanceof Boolean b) {
            return AttributeValue.builder().bool(b).build();
        } else if (value instanceof JSONObject obj) {
            return AttributeValue.builder().m(jsonObjectToAttributeMap(obj)).build();
        } else if (value instanceof JSONArray arr) {
            List<AttributeValue> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(toAttributeValue(arr.get(i)));
            }
            return AttributeValue.builder().l(list).build();
        } else {
            return AttributeValue.builder().s(value.toString()).build();
        }
    }
}
