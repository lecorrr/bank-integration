package dev.tkachuk.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/** Publishes and reads Kafka records the way bank-core does. */
public final class TestKafka {

    private final KafkaContainer kafka;
    private final Duration timeout;

    public TestKafka(KafkaContainer kafka, Duration timeout) {
        this.kafka = kafka;
        this.timeout = timeout;
    }

    public void publish(String topic, String key, String json) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, json)).get(timeout.toSeconds(), TimeUnit.SECONDS);
        }
    }

    public List<ConsumerRecord<String, String>> records(String topic, Predicate<ConsumerRecord<String, String>> wanted,
            int count) {
        List<ConsumerRecord<String, String>> found = recordsWithin(topic, wanted, count, timeout);
        assertThat(found).as(count + " matching records on " + topic + " within " + timeout).hasSize(count);
        return found;
    }

    public List<ConsumerRecord<String, String>> recordsWithin(String topic,
            Predicate<ConsumerRecord<String, String>> wanted, int count, Duration wait) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                // A new group every time, so each call reads the topic from the start.
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(wait);
            while (found.size() < count && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (wanted.test(record) && found.size() < count) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    public static Predicate<ConsumerRecord<String, String>> keyed(String key) {
        return record -> key.equals(record.key());
    }

    public static String headerOf(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header " + name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
