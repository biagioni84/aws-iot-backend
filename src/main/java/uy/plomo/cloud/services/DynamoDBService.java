package uy.plomo.cloud.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class DynamoDBService {

    private final DynamoDbAsyncClient dynamo;

    public DynamoDBService(@Value("${aws.region}") String region) {
        this.dynamo = DynamoDbAsyncClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Request body for creating or updating a tunnel.
     *
     * Example JSON:
     * {
     *   "name": "my-tunnel",
     *   "src_addr": "localhost",
     *   "src_port": "8080",
     *   "dst_port": "9001",
     *   "use_this_server": "on"
     * }
     */
    public record TunnelRequest(
            String name,
            @JsonProperty("src_addr")
            String srcAddr,
            @JsonProperty("src_port")
            String srcPort,
            @JsonProperty("dst_port")
            String dstPort,
            @JsonProperty("use_this_server")
            String useThisServer
    ) {
        public TunnelRequest {
            if (srcAddr == null || srcAddr.isBlank())  throw new IllegalArgumentException("src_addr is required");
            if (srcPort == null || srcPort.isBlank())  throw new IllegalArgumentException("src_port is required");
            if (dstPort == null || dstPort.isBlank())  throw new IllegalArgumentException("dst_port is required");
            if (name == null || name.isBlank())         name = "tunnel";
            if (useThisServer == null)                  useThisServer = "off";
        }
    }
    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    public CompletableFuture<String> getPasswordHash(String username) {
        QueryRequest request = QueryRequest.builder()
                .tableName("users")
                .keyConditionExpression("username = :username")
                .expressionAttributeValues(Map.of(
                        ":username", AttributeValue.builder().s(username).build()
                ))
                .limit(1)
                .build();

        return dynamo.query(request).thenApply(response -> {
            if (response.items().isEmpty()) {
                return "";
            }
            return response.items().get(0)
                    .getOrDefault("password", AttributeValue.builder().s("").build())
                    .s();
        });
    }

    public CompletableFuture<Map<String, Object>> getUserSummary(String username) {
        QueryRequest request = QueryRequest.builder()
                .tableName("users")
                .keyConditionExpression("username = :username")
                .expressionAttributeValues(Map.of(
                        ":username", AttributeValue.builder().s(username).build()
                ))
                .limit(1)
                .build();

        return dynamo.query(request).thenApply(response -> {
            if (response.items().isEmpty()) {
                throw new ResourceNotFoundException("User not found: " + username);
            }
            return new JSONObject(
                    EnhancedDocument.fromAttributeValueMap(response.items().get(0)).toJson()).toMap();
        });
    }

    // -------------------------------------------------------------------------
    // Gateways
    // -------------------------------------------------------------------------

    public CompletableFuture<Map<String, Object>> getGatewaySummary(String gwId) {
        return queryGateway(gwId).thenApply(response -> {
            if (response.items().isEmpty()) {
                throw new ResourceNotFoundException("Gateway not found: " + gwId);
            }
            return new JSONObject(
                    EnhancedDocument.fromAttributeValueMap(response.items().get(0)).toJson()).toMap();
        });
    }

    // -------------------------------------------------------------------------
    // Tunnels
    // -------------------------------------------------------------------------

    public CompletableFuture<Map<String, Object>> getTunnelList(String gwId) {
        return queryGateway(gwId).thenApply(response -> {
            if (response.items().isEmpty()) {
                throw new ResourceNotFoundException("Gateway not found: " + gwId);
            }
            AttributeValue tunnels = response.items().get(0).get("tunnels");
            if (tunnels == null) return Map.of();
            return new JSONObject(
                    EnhancedDocument.fromAttributeValueMap(tunnels.m()).toJson()).toMap();
        });
    }

    public CompletableFuture<Map<String, Object>> getTunnelDetail(String gwId, String tunnelId) {
        return queryGateway(gwId).thenApply(response -> {
            if (response.items().isEmpty()) {
                throw new ResourceNotFoundException("Gateway not found: " + gwId);
            }
            AttributeValue tunnels = response.items().get(0).get("tunnels");
            if (tunnels == null || !tunnels.m().containsKey(tunnelId)) {
                throw new ResourceNotFoundException("Tunnel not found: " + tunnelId);
            }
            return new JSONObject(
                    EnhancedDocument.fromAttributeValueMap(tunnels.m().get(tunnelId).m()).toJson()).toMap();
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<QueryResponse> queryGateway(String gwId) {
        QueryRequest request = QueryRequest.builder()
                .tableName("iot-gateways")
                .keyConditionExpression("gateway_id = :gwId")
                .expressionAttributeValues(Map.of(
                        ":gwId", AttributeValue.builder().s(gwId).build()
                ))
                .limit(1)
                .build();

        return dynamo.query(request);
    }



    /**
     * Creates a new tunnel entry. Fails if a tunnel with the same ID already exists.
     */
    public CompletableFuture<Void> createTunnel(String gwId, String tunnelId, TunnelRequest tunnel) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName("iot-gateways")
                .key(Map.of("gateway_id", AttributeValue.builder().s(gwId).build()))
                .updateExpression("SET tunnels.#tid = :tunnel")
                .conditionExpression("attribute_not_exists(tunnels.#tid)")
                .expressionAttributeNames(Map.of("#tid", tunnelId))
                .expressionAttributeValues(Map.of(":tunnel", tunnelToAttributeValue(tunnel)))
                .build();
        return dynamo.updateItem(request)
                .thenApply(r -> (Void) null)
                .exceptionally(ex -> {
                    if (ex.getCause() instanceof ConditionalCheckFailedException) {
                        throw new ConflictException("Tunnel already exists: " + tunnelId);
                    }
                    throw new RuntimeException(ex);
                });
    }

    /**
     * Updates an existing tunnel. Fails if the tunnel does not exist.
     */
    public CompletableFuture<Void> updateTunnel(String gwId, String tunnelId, TunnelRequest tunnel) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName("iot-gateways")
                .key(Map.of("gateway_id", AttributeValue.builder().s(gwId).build()))
                .updateExpression("SET tunnels.#tid = :tunnel")
                .conditionExpression("attribute_exists(tunnels.#tid)")
                .expressionAttributeNames(Map.of("#tid", tunnelId))
                .expressionAttributeValues(Map.of(":tunnel", tunnelToAttributeValue(tunnel)))
                .build();

        return dynamo.updateItem(request)
                .thenApply(r -> (Void) null)
                .exceptionally(ex -> {
                    if (ex.getCause() instanceof ConditionalCheckFailedException) {
                        throw new ResourceNotFoundException("Tunnel not found: " + tunnelId);
                    }
                    throw new RuntimeException(ex);
                });
    }

    /**
     * Removes a tunnel from the gateway's tunnels map. Fails if it does not exist.
     */
    public CompletableFuture<Void> deleteTunnel(String gwId, String tunnelId) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName("iot-gateways")
                .key(Map.of("gateway_id", AttributeValue.builder().s(gwId).build()))
                .updateExpression("REMOVE tunnels.#tid")
                .conditionExpression("attribute_exists(tunnels.#tid)")
                .expressionAttributeNames(Map.of("#tid", tunnelId))
                .build();

        return dynamo.updateItem(request)
                .thenApply(r -> (Void) null)
                .exceptionally(ex -> {
                    if (ex.getCause() instanceof ConditionalCheckFailedException) {
                        throw new ResourceNotFoundException("Tunnel not found: " + tunnelId);
                    }
                    throw new RuntimeException(ex);
                });
    }


    private AttributeValue tunnelToAttributeValue(TunnelRequest tunnel) {
        return AttributeValue.builder().m(Map.of(
                "name",            AttributeValue.builder().s(tunnel.name()).build(),
                "src_addr",        AttributeValue.builder().s(tunnel.srcAddr()).build(),
                "src_port",        AttributeValue.builder().s(tunnel.srcPort()).build(),
                "dst_port",        AttributeValue.builder().s(tunnel.dstPort()).build(),
                "use_this_server", AttributeValue.builder().s(tunnel.useThisServer()).build()
        )).build();
    }

    // -------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------

    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }
}