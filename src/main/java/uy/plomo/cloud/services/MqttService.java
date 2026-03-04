package uy.plomo.cloud.services;

import java.nio.charset.StandardCharsets;
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
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt5.*;
import software.amazon.awssdk.crt.mqtt5.packets.*;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;
import uy.plomo.cloud.utils.JsonConverter;

@Service
@Slf4j
public class MqttService {

    // Topic constants — change the namespace here and it propagates everywhere
    private static final String TOPIC_NAMESPACE = "iot/v1";
    private static final String CMD_REQUEST_PATTERN  = TOPIC_NAMESPACE + "/%s/request/%s";
    private static final String CMD_RESPONSE_TOPIC   = TOPIC_NAMESPACE + "/+/response/+";
    private static final String EVENT_TOPIC          = TOPIC_NAMESPACE + "/+/event/#";
    private static final String STATUS_TOPIC         = TOPIC_NAMESPACE + "/+/status";

    // Regex to extract gwId and requestId from a response topic
    private static final Pattern RESPONSE_PATTERN =
            Pattern.compile(TOPIC_NAMESPACE + "/(.*)/response/(.*)");

    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int RESPONSE_TIMEOUT_SECONDS = 30;

    // Exponential backoff: start at 1s, double each attempt, cap at 30s
    private static final long RECONNECT_MIN_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;

    @Value("${aws.iot.endpoint}")
    private String endpoint;

    @Value("${aws.iot.clientId}")
    private String clientId;

    private Mqtt5Client client;
    private final PendingRequestsService pendingRequests;

    // Tracks whether we've successfully connected at least once
    private final AtomicBoolean everConnected = new AtomicBoolean(false);
    // Tracks whether we're currently connected
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public MqttService(PendingRequestsService pendingRequests) {
        this.pendingRequests = pendingRequests;
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
                // Fail all pending requests immediately — no point waiting 30s for a timeout
                log.warn("Failing {} pending requests due to disconnection", pendingRequests.getPendingCount());
                pendingRequests.failAll(new RuntimeException("MQTT connection lost"));
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

            Matcher matcher = RESPONSE_PATTERN.matcher(topic);
            if (matcher.find()) {
                String requestId = matcher.group(2);
                log.debug("Completing pending request: {}", requestId);
                pendingRequests.complete(requestId, payload);
            } else {
                // Events and status updates — extend here as needed
                log.debug("Unhandled topic: {}", topic);
            }
        };
        AwsIotMqtt5ClientBuilder builder = AwsIotMqtt5ClientBuilder.newWebsocketMqttBuilderWithSigv4Auth(endpoint, null);
        // Configure exponential backoff reconnection
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

        // Subscribe only to response, event, and status topics — not the wildcard #
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
    private void subscribeAll() {
        subscribe(CMD_RESPONSE_TOPIC);
        subscribe(EVENT_TOPIC);
        subscribe(STATUS_TOPIC);
    }

    private void resubscribe() {
        try {
            subscribeAll();
        } catch (Exception e) {
            log.error("Failed to resubscribe after reconnection — topics may be missing", e);
        }
    }

    /**
     * Publish a message to a gateway and return a CompletableFuture that
     * completes when the gateway responds (or times out). Never blocks a thread.
     */
    public CompletableFuture<Map<String, Object>> sendAsync(String gwId, JSONObject payload) {
        if (!connected.get()) {
            return CompletableFuture.completedFuture(Map.of(
                    "error", "MQTT_DISCONNECTED",
                    "message", "Backend is not connected to the broker"));
        }
        String requestId = UUID.randomUUID().toString();
        String topic = String.format(CMD_REQUEST_PATTERN, gwId, requestId);
        CompletableFuture<String> responseFuture = pendingRequests.create(requestId);
        publish(topic, payload.toString());

        return responseFuture
                .orTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenApply(JsonConverter::toMap)
                .exceptionally(ex -> {
                    log.warn("Request {} to gateway {} failed: {}", requestId, gwId, ex.getMessage());
                    pendingRequests.cancel(requestId); // clean up if still pending
                    return Map.of("error", "GATEWAY_TIMEOUT",
                            "message", "No response from gateway within " + RESPONSE_TIMEOUT_SECONDS + "s",
                            "requestId", requestId);
                });
    }

    private void publish(String topic, String payload) {
        log.debug("Publishing to '{}': {}", topic, payload);
        PublishPacket publishPacket = PublishPacket.of(
                topic,
                QOS.AT_LEAST_ONCE,
                payload.getBytes(StandardCharsets.UTF_8));
        try {
            PubAckPacket pubAck = client.publish(publishPacket)
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getResultPubAck();
            log.debug("PubAck: {}", pubAck.getReasonCode());
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to topic: " + topic, e);
        }
    }
}