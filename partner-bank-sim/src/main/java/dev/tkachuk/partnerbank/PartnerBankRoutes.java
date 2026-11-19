package dev.tkachuk.partnerbank;

import static dev.tkachuk.interbank.InterbankTopology.BANK_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.EXCHANGE;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS002;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS008;
import static dev.tkachuk.interbank.InterbankTopology.PARTNER_PACS028;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.springframework.stereotype.Component;

/**
 * The partner bank's three queues. As a payee's bank (flow A), every pacs.008 gets a pacs.002 back on bank-core's
 * queue, unless it is to a silent account, and every status request (pacs.028) gets the pacs.002 for the payment
 * it asks about. As a payer's bank (flow B), it keeps the answers to its own payments (SentPayments), and sends a
 * payment again when bank-core's bank says it never received it (RJCT AG09), as a real bank would.
 */
@Component
class PartnerBankRoutes extends RouteBuilder {

    private static final String ANSWERS = "spring-rabbitmq:" + EXCHANGE + "?routingKey=" + BANK_PACS002;
    /** Whether the bank answers the message in hand. */
    private static final String ANSWERED = "answered";

    private final StatusReports statusReports;
    private final SentPayments sentPayments;
    private final PaymentSender sender;

    PartnerBankRoutes(StatusReports statusReports, SentPayments sentPayments, PaymentSender sender) {
        this.statusReports = statusReports;
        this.sentPayments = sentPayments;
        this.sender = sender;
    }

    @Override
    public void configure() {
        from("spring-rabbitmq:" + EXCHANGE + "?queues=" + PARTNER_PACS008).routeId("answer-pacs008")
                .convertBodyTo(String.class)
                .process(this::answerPayment)
                .filter(exchange -> exchange.getProperty(ANSWERED, false, Boolean.class))
                    .to(ANSWERS)
                .end();

        from("spring-rabbitmq:" + EXCHANGE + "?queues=" + PARTNER_PACS028).routeId("answer-pacs028")
                .convertBodyTo(String.class)
                .process(this::answerStatusRequest)
                .to(ANSWERS);

        from("spring-rabbitmq:" + EXCHANGE + "?queues=" + PARTNER_PACS002).routeId("record-pacs002")
                .convertBodyTo(String.class)
                .process(this::recordAnswer);
    }

    private void answerPayment(Exchange exchange) {
        Optional<Pacs002> answer = statusReports.answerPayment(exchange.getMessage().getBody(String.class));
        exchange.setProperty(ANSWERED, answer.isPresent());
        answer.ifPresent(report -> becomeAnswer(exchange.getMessage(), report));
    }

    private void answerStatusRequest(Exchange exchange) {
        Message message = exchange.getMessage();
        becomeAnswer(message, statusReports.answerStatusRequest(message.getBody(String.class)));
    }

    /** An answer to one of this bank's payments: kept, and acted on when it says the payment never arrived. */
    private void recordAnswer(Exchange exchange) {
        Pacs002 answer = Pacs002.parse(exchange.getMessage().getBody(String.class));
        Optional<SentPayments.Sent> payment = sentPayments.recordAnswer(answer);
        if (payment.isEmpty()) {
            log.warn("pacs.002 {} is about pacs.008 {} (payment {}), which this simulator does not remember",
                    answer.messageId(), answer.originalMessageId(), answer.originalUetr());
            return;
        }
        log.info("pacs.002 {} about pacs.008 {} (payment {}): {}{}", answer.messageId(), answer.originalMessageId(),
                answer.originalUetr(), answer.status(), answer.reasonCode() == null ? "" : " " + answer.reasonCode());
        if (answer.status() == TransactionStatus.RJCT
                && StatusReason.PAYMENT_NOT_RECEIVED.equals(answer.reasonCode())) {
            sender.sendAgain(payment.get().payment());
        }
    }

    /** The message in hand becomes the answer: its headers go, so its AMQP message id does not travel on. */
    private static void becomeAnswer(Message message, Pacs002 answer) {
        message.removeHeaders("*");
        message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, answer.messageId());
        message.setBody(answer.toXml());
    }
}
