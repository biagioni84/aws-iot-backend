package uy.plomo.cloud.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import uy.plomo.cloud.kafka.GatewayEventProducer;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuración explícita de Kafka para It2.
 *
 * <p>En Spring Boot 4.x, {@code KafkaAutoConfiguration} no registra
 * {@code KafkaTemplate} ni {@code kafkaListenerContainerFactory} automáticamente
 * — hay que declararlos de forma explícita.
 *
 * <p>Los valores de {@code @Value} tienen defaults ({@code :localhost:9092},
 * {@code :iot-backend}) para que los tests que mockean {@code GatewayEventProducer}
 * no fallen por {@code PlaceholderResolutionException} al procesar esta clase de
 * configuración.
 *
 * <p><b>It3:</b> cambiar {@code AckMode} a {@code MANUAL} y hacer el consumer
 * transaccional con TimescaleDB.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:iot-backend}")
    private String groupId;

    // -------------------------------------------------------------------------
    // Producer
    // -------------------------------------------------------------------------

    @Bean
    public ProducerFactory<Object, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // -------------------------------------------------------------------------
    // Consumer
    // -------------------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        JsonDeserializer<Object> deserializer = new JsonDeserializer<>(Object.class);
        deserializer.addTrustedPackages("uy.plomo.cloud.kafka.event");
        deserializer.setUseTypeHeaders(true);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // It3: factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }

    // -------------------------------------------------------------------------
    // Topic — creado automáticamente en startup via KafkaAdmin
    // -------------------------------------------------------------------------

    @Bean
    public NewTopic telemetryTopic() {
        return TopicBuilder.name(GatewayEventProducer.TOPIC_TELEMETRY)
                .partitions(3)
                .replicas(1)
                .build();
    }
}


