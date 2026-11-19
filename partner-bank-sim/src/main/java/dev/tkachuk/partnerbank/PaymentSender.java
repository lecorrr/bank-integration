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

/** Sends payments from this bank's test customer to bank-core's bank, and status requests about them. */
@Component
class PaymentSender {

    static final String NOT_PROVIDED = "NOTPROVIDED";
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

    Pacs008 send(String toIban, String toName, BigDecimal amount, String currency, String description) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String messageId = newMessageId();
        Pacs008 payment = new Pacs008(messageId, now, NOT_PROVIDED, messageId, UUID.randomUUID().toString(), amount,
                currency, LocalDate.ofInstant(now, BANK_ZONE), sending.customerName(), sending.customerIban(),
                sending.bic(), toName, toIban, sending.payeeBankBic(), description);
        String xml = payment.toXml();
        sent.remember(payment);
        log.info("Sending pacs.008 {} (payment {}, {} {})", payment.messageId(), payment.uetr(),
                payment.amount().toPlainString(), payment.currency());
        toBank(BANK_PACS008, payment.messageId(), xml);
        sent.countSent(payment.uetr());
        return payment;
    }

    void sendAgain(Pacs008 payment) {
        log.info("Resending pacs.008 {} (payment {})", payment.messageId(), payment.uetr());
        toBank(BANK_PACS008, payment.messageId(), payment.toXml());
        sent.countSent(payment.uetr());
    }

    Pacs028 ask(Pacs008 payment) {
        Pacs028 request = Pacs028.about(payment, newMessageId(), clock.instant().truncatedTo(ChronoUnit.SECONDS));
        log.info("Sending pacs.028 {} for pacs.008 {} (payment {})", request.messageId(), payment.messageId(),
                payment.uetr());
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
