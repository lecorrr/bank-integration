package dev.tkachuk.partnerbank;

import dev.tkachuk.interbank.iso20022.Pacs002;
import dev.tkachuk.interbank.iso20022.Pacs008;
import dev.tkachuk.interbank.iso20022.Pacs028;
import dev.tkachuk.interbank.iso20022.StatusReason;
import dev.tkachuk.interbank.iso20022.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The partner bank's side of each payment: its decision on the pacs.008, and its answer whenever it is asked
 * again. A payment to a closed account is refused (AC04), one to a silent account gets no answer, and every
 * other valid payment is accepted.
 *
 * <p>Like a real bank, it remembers the payments it received, by MsgId: the same pacs.008 again gets the same
 * answer again rather than becoming a second payment, and a status request (pacs.028) gets the payment's answer,
 * PDNG while it has none, or RJCT AG09 for a payment the bank never received. The memory is the simulator's
 * own: it keeps the latest payments only, and a restart empties it, which bank-integration then hears as "never
 * received" and answers by sending the payment again.
 */
@Component
class StatusReports {

    /** How many payments the simulator remembers; the oldest are forgotten first. */
    static final int REMEMBERED_PAYMENTS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(StatusReports.class);

    private final Clock clock;
    private final PartnerBankProperties properties;
    /** By the pacs.008's MsgId, oldest first. Guarded by this. */
    private final Map<String, Received> received;

    @Autowired
    StatusReports(Clock clock, PartnerBankProperties properties) {
        this(clock, properties, REMEMBERED_PAYMENTS);
    }

    /** With a memory of another size, for tests. */
    StatusReports(Clock clock, PartnerBankProperties properties, int rememberedPayments) {
        this.clock = clock;
        this.properties = properties;
        this.received = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Received> eldest) {
                return size() > rememberedPayments;
            }
        };
    }

    /**
     * @return the answer to the payment, or none for a payment to a silent account
     * @throws dev.tkachuk.interbank.iso20022.InvalidMessageException when the XML is not a valid pacs.008
     */
    synchronized Optional<Pacs002> answerPayment(String pacs008Xml) {
        Pacs008 payment = Pacs008.parse(pacs008Xml);
        Received earlier = received.get(payment.messageId());
        if (earlier != null) {
            log.info("pacs.008 {} came again: a duplicate, answered as before", payment.messageId());
            return Optional.ofNullable(earlier.answer()).map(answer -> answer.repeat(newMessageId(), now()));
        }
        Pacs002 answer = decide(payment);
        received.put(payment.messageId(), new Received(payment, answer));
        return Optional.ofNullable(answer);
    }

    /**
     * @return the answer about the payment asked about: its own answer again, PDNG while it has none, or RJCT
     *         AG09 when the bank never received it
     * @throws dev.tkachuk.interbank.iso20022.InvalidMessageException when the XML is not a valid pacs.028
     */
    synchronized Pacs002 answerStatusRequest(String pacs028Xml) {
        Pacs028 request = Pacs028.parse(pacs028Xml);
        Received earlier = received.get(request.originalMessageId());
        Pacs002 answer;
        if (earlier == null) {
            answer = Pacs002.notReceived(request, newMessageId(), now());
        } else if (earlier.answer() == null) {
            answer = Pacs002.answering(earlier.payment(), TransactionStatus.PDNG, newMessageId(), now());
        } else {
            answer = earlier.answer().repeat(newMessageId(), now());
        }
        log.info("pacs.028 {} asks about pacs.008 {}: answering pacs.002 {} {}{}", request.messageId(),
                request.originalMessageId(), answer.messageId(), answer.status(),
                answer.reasonCode() == null ? "" : " " + answer.reasonCode());
        return answer;
    }

    /** The bank's decision on a new payment; null for a silent account, whose payment gets no answer. */
    private Pacs002 decide(Pacs008 payment) {
        if (properties.isSilent(payment.creditorIban())) {
            log.info("pacs.008 {}: received, and left without an answer (a silent account)", payment.messageId());
            return null;
        }
        if (properties.isClosed(payment.creditorIban())) {
            Pacs002 refusal = Pacs002.rejecting(payment, StatusReason.CLOSED_ACCOUNT, newMessageId(), now());
            log.info("pacs.008 {}: the payee's account is closed, answering pacs.002 {} {} {}", payment.messageId(),
                    refusal.messageId(), refusal.status(), refusal.reasonCode());
            return refusal;
        }
        Pacs002 report = Pacs002.answering(payment, TransactionStatus.ACSC, newMessageId(), now());
        log.info("pacs.008 {}: {} {} credited, answering pacs.002 {} {}", payment.messageId(),
                payment.amount().toPlainString(), payment.currency(), report.messageId(), report.status());
        return report;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** A payment the bank received, and its answer; null while it has none. */
    private record Received(Pacs008 payment, Pacs002 answer) {
    }
}
