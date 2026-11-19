package dev.tkachuk.partnerbank;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS028;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;

import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the partner bank's payments to bank-core's customers (pacs.008) and its status requests about them
 * (pacs.028), to bank-core's bank over RabbitMQ. Each message travels under its own MsgId as the AMQP message id.
 */
@Component
class PaymentSender {

    /** ISO 20022's word for an end-to-end id the payer did not give. */
    static final String NOT_PROVIDED = "NOTPROVIDED";
    /** The business day follows Kyiv time. */
    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Kyiv");

    private static final Logger log = LoggerFactory.getLogger(PaymentSender.class);

    private final ProducerTemplate producer;
    private final SendingProperties sending;
    private final SentPayments sent;
    private final Clock clock;

    PaymentSender(ProducerTemplate producer, SendingProperties sending, SentPayments sent, Clock clock) {
        this.producer = producer;
        this.sending = sending;
        this.sent = sent;
        this.clock = clock;
    }

    /**
     * A new payment from this bank's customer, under a new UETR and MsgId.
     *
     * @throws IllegalArgumentException when the payment is not one a pacs.008 can carry, such as a name of 141
     *                                  characters; the message says which field
     */
    Pacs008 send(String toIban, String toName, BigDecimal amount, String currency, String description) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String messageId = newMessageId();
        Pacs008 payment = new Pacs008(messageId, now, NOT_PROVIDED, messageId, UUID.randomUUID().toString(), amount,
                currency, LocalDate.ofInstant(now, BANK_ZONE), sending.customerName(), sending.customerIban(),
                sending.bic(), toName, toIban, sending.payeeBankBic(), description);
        String xml = payment.toXml();
        sent.remember(payment);
        log.info("Sending pacs.008 {} (payment {}, {} {}) to bank-core's bank", payment.messageId(), payment.uetr(),
                payment.amount().toPlainString(), payment.currency());
        toBank(BANK_PACS008, payment.messageId(), xml);
        sent.countSent(payment.uetr());
        return payment;
    }

    /** The same pacs.008 again, MsgId and all, so a bank that has it already drops the copy. */
    void sendAgain(Pacs008 payment) {
        log.info("Sending pacs.008 {} (payment {}) again", payment.messageId(), payment.uetr());
        toBank(BANK_PACS008, payment.messageId(), payment.toXml());
        sent.countSent(payment.uetr());
    }

    /** Asks what became of the payment; the answer comes back like any other. */
    Pacs028 ask(Pacs008 payment) {
        Pacs028 request = Pacs028.about(payment, newMessageId(), clock.instant().truncatedTo(ChronoUnit.SECONDS));
        log.info("Asking about pacs.008 {} (payment {}) with pacs.028 {}", payment.messageId(), payment.uetr(),
                request.messageId());
        toBank(BANK_PACS028, request.messageId(), request.toXml());
        return request;
    }

    private void toBank(String queue, String messageId, String xml) {
        producer.sendBodyAndHeader("spring-rabbitmq:" + EXCHANGE + "?routingKey=" + queue, xml,
                SpringRabbitMQConstants.MESSAGE_ID, messageId);
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
