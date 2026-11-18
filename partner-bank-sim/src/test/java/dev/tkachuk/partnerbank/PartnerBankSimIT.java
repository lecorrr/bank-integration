package dev.tkachuk.partnerbank;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The simulator against a real RabbitMQ: the test plays our bank, sends a pacs.008 and reads the answer. */
@SpringBootTest
@Import(RabbitTestcontainer.class)
class PartnerBankSimIT {

    private static final long RECEIVE_TIMEOUT_MS = 10_000;

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
    void answersAPaymentWithAnAcceptedStatusReport() {
        Pacs008 payment = TestPayments.pacs008("MSG-IT-1");

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());

        Message answer = rabbit.receive(BANK_PACS002, RECEIVE_TIMEOUT_MS);
        assertThat(answer).as("a pacs.002 on queue " + BANK_PACS002).isNotNull();
        Pacs002 report = Pacs002.parse(bodyOf(answer));
        assertThat(report.originalMessageId()).isEqualTo("MSG-IT-1");
        assertThat(report.originalEndToEndId()).isEqualTo(payment.endToEndId());
        assertThat(report.status()).isEqualTo(TransactionStatus.ACSC);
        // The answer travels under its own id, not the payment's.
        assertThat(answer.getMessageProperties().getMessageId()).isEqualTo(report.messageId());
    }

    @Test
    void refusesAPaymentToTheClosedAccountInItsConfiguration() {
        Pacs008 payment = TestPayments.pacs008("MSG-IT-4", TestPayments.CLOSED_ACCOUNT);

        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, payment.toXml());

        Message answer = rabbit.receive(BANK_PACS002, RECEIVE_TIMEOUT_MS);
        assertThat(answer).as("a pacs.002 on queue " + BANK_PACS002).isNotNull();
        Pacs002 report = Pacs002.parse(bodyOf(answer));
        assertThat(report.originalMessageId()).isEqualTo("MSG-IT-4");
        assertThat(report.status()).isEqualTo(TransactionStatus.RJCT);
        assertThat(report.reasonCode()).isEqualTo("AC04");
    }

    @Test
    void answersEveryPaymentOnce() {
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, TestPayments.pacs008("MSG-IT-2").toXml());
        rabbit.convertAndSend(EXCHANGE, PARTNER_PACS008, TestPayments.pacs008("MSG-IT-3").toXml());

        Message first = rabbit.receive(BANK_PACS002, RECEIVE_TIMEOUT_MS);
        Message second = rabbit.receive(BANK_PACS002, RECEIVE_TIMEOUT_MS);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(Pacs002.parse(bodyOf(first)).originalMessageId())
                .isNotEqualTo(Pacs002.parse(bodyOf(second)).originalMessageId());
        assertThat(rabbit.receive(BANK_PACS002, 1_000)).as("no third answer").isNull();
    }

    /** Honours the encoding the sender declared; Camel declares the JVM's, which is UTF-8 since Java 18. */
    private static String bodyOf(Message message) {
        String encoding = message.getMessageProperties().getContentEncoding();
        return new String(message.getBody(), encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
    }
}
