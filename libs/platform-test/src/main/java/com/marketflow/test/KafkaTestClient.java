package com.marketflow.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

/** Minimal producer/consumer for tests: publish raw envelopes and poll topics until a condition holds. */
public class KafkaTestClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final String brokers;
    private final KafkaProducer<String, String> producer;

    public KafkaTestClient(String brokers) {
        this.brokers = brokers;
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        this.producer = new KafkaProducer<>(p);
    }

    public void send(String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds an envelope JSON exactly like the outbox would. */
    public static String envelope(UUID eventId, String eventType, String aggregateType, String aggregateId,
                                  Object payload) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "eventId", eventId.toString(),
                    "eventType", eventType,
                    "schemaVersion", 1,
                    "aggregateType", aggregateType,
                    "aggregateId", aggregateId,
                    "occurredAt", Instant.now().toString(),
                    "correlationId", "test-" + UUID.randomUUID(),
                    "producer", "test",
                    "payload", payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the topic from the beginning until {@code done} accepts the collected envelopes or timeout. */
    public List<JsonNode> readUntil(String topic, Duration timeout, Predicate<List<JsonNode>> done) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                    try {
                        out.add(MAPPER.readTree(r.value()));
                    } catch (Exception e) {
                        out.add(MAPPER.getNodeFactory().textNode(r.value()));
                    }
                }
                if (done.test(out)) {
                    return out;
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(2));
    }
}
