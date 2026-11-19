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

/** The partner bank's side of the queues. A payment to a silent account gets no answer at all. */
@Component
class PartnerBankRoutes extends RouteBuilder {

    private static final String ANSWERS = "spring-rabbitmq:" + EXCHANGE + "?routingKey=" + BANK_PACS002;
    private static final String ANSWERED = "answered";

    private final StatusReports statusReports;
    private final SentPayments sentPayments;
    private final PaymentSender sender;
    private final CorrespondentAccount account;

    PartnerBankRoutes(StatusReports statusReports, SentPayments sentPayments, PaymentSender sender,
            CorrespondentAccount account) {
        this.statusReports = statusReports;
        this.sentPayments = sentPayments;
        this.sender = sender;
        this.account = account;
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

    private void recordAnswer(Exchange exchange) {
        Pacs002 answer = Pacs002.parse(exchange.getMessage().getBody(String.class));
        Optional<SentPayments.Sent> payment = sentPayments.find(answer.originalUetr());
        if (payment.isEmpty()) {
            log.warn("pacs.002 {} refers to unknown pacs.008 {} (payment {})", answer.messageId(),
                    answer.originalMessageId(), answer.originalUetr());
            return;
        }
        // Booked before the answer shows in the REST API, so whoever sees the ACSC there finds it on the account.
        if (answer.status() == TransactionStatus.ACSC) {
            account.credit(payment.get().payment());
        }
        sentPayments.recordAnswer(answer);
        log.info("pacs.002 {} about pacs.008 {} (payment {}): {}{}", answer.messageId(), answer.originalMessageId(),
                answer.originalUetr(), answer.status(), answer.reasonCode() == null ? "" : " " + answer.reasonCode());
        // Same rule as integration-service: resend a payment the other bank never received.
        if (answer.status() == TransactionStatus.RJCT
                && StatusReason.PAYMENT_NOT_RECEIVED.equals(answer.reasonCode())) {
            sender.sendAgain(payment.get().payment());
        }
    }

    private static void becomeAnswer(Message message, Pacs002 answer) {
        message.removeHeaders("*");
        message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, answer.messageId());
        message.setBody(answer.toXml());
    }
}
