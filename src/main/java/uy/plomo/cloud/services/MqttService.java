package uy.plomo.cloud.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt5.*;
import software.amazon.awssdk.crt.mqtt5.packets.*;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;


@Service
@Slf4j
public class MqttService {

    private static final String TOPIC_NAMESPACE = "iot/v1";
    private static final String CMD_REQUEST_PATTERN  = TOPIC_NAMESPACE + "/%s/request/%s";
    private static final String CMD_RESPONSE_TOPIC   = TOPIC_NAMESPACE + "/+/response/+";
    private static final String EVENT_TOPIC          = TOPIC_NAMESPACE + "/+/event/#";
    private static final String STATUS_TOPIC         = TOPIC_NAMESPACE + "/+/status";

    private static final Pattern RESPONSE_PATTERN =
            Pattern.compile(TOPIC_NAMESPACE + "/(.*)/response/(.*)");
    private static final Pattern STATUS_PATTERN =
            Pattern.compile(TOPIC_NAMESPACE + "/(.*)/status");
    private static final Pattern EVENT_PATTERN =
            Pattern.compile(TOPIC_NAMESPACE + "/(.*)/event/(.*)");

    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int RESPONSE_TIMEOUT_SECONDS = 30;

    // Exponential backoff: start at 1s, double each attempt, cap at 30s
    private static final long RECONNECT_MIN_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;

    @Value("${aws.iot.endpoint}")
    private String endpoint;

    @Value("${aws.iot.clientId}")
    private String clientId;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Mqtt5Client client;
    private final PendingRequestsService pendingRequests;
    private final TelemetryService telemetryService;
    private final GatewayEventBroadcaster eventBroadcaster;

    private final AtomicBoolean everConnected = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public MqttService(PendingRequestsService pendingRequests, TelemetryService telemetryService,
                       GatewayEventBroadcaster eventBroadcaster) {
        this.pendingRequests = pendingRequests;
        this.telemetryService = telemetryService;
        this.eventBroadcaster = eventBroadcaster;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing MQTT5 client, connecting to: {}", endpoint);

        CountDownLatch initialConnect = new CountDownLatch(1);

        Mqtt5ClientOptions.LifecycleEvents lifecycleEvents = new Mqtt5ClientOptions.LifecycleEvents() {
            @Override
            public void onAttemptingConnect(Mqtt5Client client, OnAttemptingConnectReturn r) {
                log.info("MQTT connecting to '{}' with clientId '{}'", endpoint, clientId);
            }

//            @Override
//            public void onConnectionSuccess(Mqtt5Client client, OnConnectionSuccessReturn r) {
//                log.info("MQTT connected, reason: {}", r.getConnAckPacket().getReasonCode());
//                connected.countDown();
//            }

            @Override
            public void onConnectionSuccess(Mqtt5Client client, OnConnectionSuccessReturn r) {
                log.info("MQTT connected, reason: {}", r.getConnAckPacket().getReasonCode());
                connected.set(true);

                if (everConnected.compareAndSet(false, true)) {
                    // First connection — unblock init()
                    initialConnect.countDown();
                } else {
                    // Reconnection — resubscribe since the broker doesn't remember us
                    log.info("MQTT reconnected — resubscribing to topics");
                    resubscribe();
                }
            }

            @Override
            public void onConnectionFailure(Mqtt5Client client, OnConnectionFailureReturn r) {
                log.error("MQTT connection failed: {} - {}",
                        CRT.awsErrorName(r.getErrorCode()),
                        CRT.awsErrorString(r.getErrorCode()));
            }

            @Override
            public void onDisconnection(Mqtt5Client client, OnDisconnectionReturn r) {
                connected.set(false);
                DisconnectPacket dp = r.getDisconnectPacket();
                if (dp != null) {
                    log.warn("MQTT disconnected: {} - {}", dp.getReasonCode(), dp.getReasonString());
                } else {
                    log.warn("MQTT disconnected (no disconnect packet)");
                }
                log.warn("{} pending requests will wait for reconnection or timeout", pendingRequests.getPendingCount());
            }

            @Override
            public void onStopped(Mqtt5Client client, OnStoppedReturn r) {
                log.info("MQTT client stopped");
            }
        };

        Mqtt5ClientOptions.PublishEvents publishEvents = (mqttClient, publishReturn) -> {
            PublishPacket publish = publishReturn.getPublishPacket();
            String topic = publish.getTopic();
            String payload = publish.getPayload() == null
                    ? ""
                    : new String(publish.getPayload(), StandardCharsets.UTF_8);

            log.debug("MQTT message received on topic '{}': {}", topic, payload);

            Matcher responseMatcher = RESPONSE_PATTERN.matcher(topic);
            Matcher statusMatcher  = STATUS_PATTERN.matcher(topic);
            Matcher eventMatcher   = EVENT_PATTERN.matcher(topic);

            if (responseMatcher.find()) {
                String requestId = responseMatcher.group(2);
                log.debug("Completing pending request: {}", requestId);
                pendingRequests.complete(requestId, payload);
            } else if (statusMatcher.find()) {
                String gatewayId = statusMatcher.group(1);
                String timestamp = Instant.now().toString();
                log.info("Telemetry received from gateway {}", gatewayId);
                try {
                    Map<String, Object> json = MAPPER.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    telemetryService.save(gatewayId, timestamp, json)
                            .exceptionally(ex -> {
                                log.error("Failed to save telemetry for gateway {}: {}",
                                        gatewayId, ex.getMessage());
                                return null;
                            });
                } catch (Exception ex) {
                    log.error("Invalid telemetry payload from gateway {}: {}", gatewayId, ex.getMessage());
                }
            } else if (eventMatcher.find()) {
                String gatewayId = eventMatcher.group(1);
                String eventType = eventMatcher.group(2);
                log.debug("Event '{}' received from gateway {}", eventType, gatewayId);
                eventBroadcaster.broadcast(gatewayId, eventType, payload);
            } else {
                log.debug("Unhandled topic: {}", topic);
            }
        };
        AwsIotMqtt5ClientBuilder builder = AwsIotMqtt5ClientBuilder.newWebsocketMqttBuilderWithSigv4Auth(endpoint, null);
        builder.withMinReconnectDelayMs(RECONNECT_MIN_MS);
        builder.withMaxReconnectDelayMs(RECONNECT_MAX_MS);
        builder.withLifeCycleEvents(lifecycleEvents);
        builder.withPublishEvents(publishEvents);
        builder.withClientId(clientId);
        client = builder.build();
        builder.close();

        client.start();

        try {
            if (!initialConnect.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException("MQTT connection timeout after " + CONNECT_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for MQTT connection", e);
        }

        subscribe(CMD_RESPONSE_TOPIC);
        subscribe(EVENT_TOPIC);
        subscribe(STATUS_TOPIC);
    }

    private void subscribe(String topic) {
        log.info("Subscribing to topic: {}", topic);
        SubscribePacket subscribePacket = SubscribePacket.of(topic, QOS.AT_LEAST_ONCE);
        try {
            SubAckPacket ack = client.subscribe(subscribePacket).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Subscribed to '{}', ack: {}", topic, ack.getReasonCodes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to subscribe to topic: " + topic, e);
        }
    }
    private void resubscribe() {
        try {
            subscribe(CMD_RESPONSE_TOPIC);
            subscribe(EVENT_TOPIC);
            subscribe(STATUS_TOPIC);
        } catch (Exception e) {
            log.error("Failed to resubscribe after reconnection — topics may be missing", e);
        }
    }

    /**
     * Publish a message to a gateway and return a CompletableFuture that
     * completes when the gateway responds (or times out). Never blocks a thread.
     */
    public CompletableFuture<Map<String, Object>> sendAsync(String gwId, Map<String, Object> payload) {
        if (!connected.get()) {
            return CompletableFuture.completedFuture(Map.of(
                    "error", "MQTT_DISCONNECTED",
                    "message", "Backend is not connected to the broker"));
        }
        String requestId = UUID.randomUUID().toString();
        String topic = String.format(CMD_REQUEST_PATTERN, gwId, requestId);
        CompletableFuture<String> responseFuture = pendingRequests.create(requestId);

        try {
            publish(topic, MAPPER.writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            pendingRequests.cancel(requestId);
            return CompletableFuture.failedFuture(e);
        }

        return responseFuture
                .orTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(json -> {
                    try {
                        return MAPPER.<Map<String, Object>>readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    } catch (Exception e) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid JSON from gateway");
                    }
                })
                .exceptionally(ex -> {
                    pendingRequests.cancel(requestId); // clean up if still pending
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("Request {} to gateway {} failed: {}", requestId, gwId, cause.getMessage());

                    if (cause instanceof java.util.concurrent.TimeoutException) {
                        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                                "No response from gateway within " + RESPONSE_TIMEOUT_SECONDS + "s");
                    }
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Gateway request failed: " + cause.getMessage());
                });
    }

    private CompletableFuture<Void> publish(String topic, String payload) {
        log.debug("Publishing to '{}': {}", topic, payload);
        PublishPacket publishPacket = PublishPacket.of(
                topic,
                QOS.AT_LEAST_ONCE,
                payload.getBytes(StandardCharsets.UTF_8));
        client.publish(publishPacket);
        return CompletableFuture.completedFuture(null);
    }
}