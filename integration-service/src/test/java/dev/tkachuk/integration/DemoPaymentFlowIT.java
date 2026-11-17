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

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/**
 * The whole walking skeleton on our side, against a real RabbitMQ. The test plays the partner bank: it takes
 * the pacs.008 off the partner's queue and answers it, so this module is tested without partner-bank-sim.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RabbitTestcontainer.class)
class DemoPaymentFlowIT {

    private static final long RECEIVE_TIMEOUT_MS = 10_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @BeforeEach
    void declareTheTopologyAndEmptyTheQueues() {
        // Declares InterbankTopology now, so no message is published before its queue exists.
        admin.initialize();
        admin.purgeQueue(PARTNER_PACS008, false);
        admin.purgeQueue(BANK_PACS002, false);
    }

    @Test
    void aSentPaymentShowsThePartnerBanksAnswer() throws Exception {
        String location = mockMvc.perform(post("/api/v1/demo/payments"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andReturn().getResponse().getHeader("Location");

        Message sent = rabbit.receive(PARTNER_PACS008, RECEIVE_TIMEOUT_MS);
        assertThat(sent).as("a pacs.008 on queue " + PARTNER_PACS008).isNotNull();
        Pacs008 payment = Pacs008.parse(bodyOf(sent));
        assertThat(sent.getMessageProperties().getMessageId()).isEqualTo(payment.messageId());
        assertThat(location).isEqualTo("/api/v1/demo/payments/" + payment.messageId());

        Pacs002 answer = Pacs002.answering(payment, TransactionStatus.ACSC, "PARTNER-ANSWER-1",
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        rabbit.convertAndSend(EXCHANGE, BANK_PACS002, answer.toXml());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACSC")));
    }

    @Test
    void aPaymentThisServiceNeverSentIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/demo/payments/no-such-payment"))
                .andExpect(status().isNotFound());
    }

    /** Honours the encoding the sender declared; Camel declares the JVM's, which is UTF-8 since Java 18. */
    private static String bodyOf(Message message) {
        String encoding = message.getMessageProperties().getContentEncoding();
        return new String(message.getBody(), encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
    }
}
