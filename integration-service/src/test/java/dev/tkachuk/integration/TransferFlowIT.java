package dev.tkachuk.integration;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.integration.outgoing.TransferRequested;
import dev.tkachuk.integration.outgoing.TransferStatusChanged;
import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flow A on our side, against a real RabbitMQ and a real Kafka. The test plays both neighbours: bank-core, which
 * publishes the transfer and reads its status, and the partner bank, which takes the pacs.008 off its queue and
 * answers it. So this module is tested without bank-core and without partner-bank-sim.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({RabbitTestcontainer.class, KafkaTestcontainer.class})
class TransferFlowIT {

    /** Generous: the first test also waits for the service's Kafka consumer to join its group. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String OUR_CUSTOMER = "UA843999990000000000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private KafkaContainer kafka;

    @BeforeEach
    void declareTheTopologyAndEmptyTheQueues() {
        // Declares InterbankTopology now, so no message is published before its queue exists.
        admin.initialize();
        admin.purgeQueue(PARTNER_PACS008, false);
        admin.purgeQueue(BANK_PACS002, false);
    }

    @Test
    void aTransferGoesOutAsAPacs008AndItsAnswerComesBackAsAStatusEvent() throws Exception {
        UUID transferId = UUID.randomUUID();
        publish(TransferRequested.TOPIC, OUR_CUSTOMER, """
                {"transferId": "%s",
                 "fromIban": "UA843999990000000000000000001", "fromName": "Artem Tkachuk",
                 "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko",
                 "amount": 150.00, "currency": "UAH", "description": "Rent for November",
                 "requestedAt": "2026-11-17T08:30:00Z"}
                """.formatted(transferId));

        Pacs008 payment = takePacs008();
        assertThat(payment.uetr()).isEqualTo(transferId.toString());
        assertThat(payment.creditorAgentBic()).isEqualTo("PRTBUAUKXXX");
        assertThat(payment.amount()).isEqualByComparingTo("150.00");
        answer(payment, TransactionStatus.ACSC);

        Map<?, ?> status = nextEvent(TransferStatusChanged.TOPIC, transferId.toString());
        assertThat(status.get("transferId")).isEqualTo(transferId.toString());
        assertThat(status.get("status")).isEqualTo("SETTLED");
    }

    @Test
    void theDemoTransferShowsThePartnerBanksAnswer() throws Exception {
        String location = mockMvc.perform(post("/api/v1/demo/payments"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getHeader("Location");

        Pacs008 payment = takePacs008();
        assertThat(location).isEqualTo("/api/v1/demo/payments/" + payment.uetr());
        answer(payment, TransactionStatus.ACSC);

        await().atMost(TIMEOUT).untilAsserted(() -> mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED")));
    }

    @Test
    void aTransferTheDemoNeverSawIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/demo/payments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    /** Publishes as bank-core does: the JSON as text, keyed by the payer's IBAN. */
    private void publish(String topic, String key, String json) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, json)).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /** The first event with this key, read from the start of the topic in a consumer group of its own. */
    private Map<?, ?> nextEvent(String topic, String key) throws Exception {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return new JsonMapper().readValue(record.value(), Map.class);
                    }
                }
            }
        }
        throw new AssertionError("No event with key " + key + " on " + topic + " within " + TIMEOUT);
    }

    private Pacs008 takePacs008() {
        Message sent = rabbit.receive(PARTNER_PACS008, TIMEOUT.toMillis());
        assertThat(sent).as("a pacs.008 on queue " + PARTNER_PACS008).isNotNull();
        Pacs008 payment = Pacs008.parse(bodyOf(sent));
        assertThat(sent.getMessageProperties().getMessageId()).isEqualTo(payment.messageId());
        return payment;
    }

    /** Answers as the partner bank does. */
    private void answer(Pacs008 payment, TransactionStatus status) {
        Pacs002 answer = Pacs002.answering(payment, status, UUID.randomUUID().toString().replace("-", ""),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        rabbit.convertAndSend(EXCHANGE, BANK_PACS002, answer.toXml());
    }

    /** Honours the encoding the sender declared; Camel declares the JVM's, which is UTF-8 since Java 18. */
    private static String bodyOf(Message message) {
        String encoding = message.getMessageProperties().getContentEncoding();
        return new String(message.getBody(), encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
    }
}
