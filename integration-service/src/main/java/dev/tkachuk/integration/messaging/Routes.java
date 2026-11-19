package dev.tkachuk.integration.messaging;

import dev.tkachuk.interbank.InterbankTopology;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.DefaultKafkaManualCommitFactory;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.apache.camel.component.springrabbit.SpringRabbitMQConstants;
import org.apache.camel.model.errorhandler.DefaultErrorHandlerDefinition;

/**
 * What every route shares: how it reads from Kafka and RabbitMQ, how it retries, where it sends, and how it puts
 * aside a message it cannot act on. In one place, so that both flows handle failures alike (ARCHITECTURE.md,
 * "When something fails").
 */
public final class Routes {

    /** Kafka keeps the offsets per group, so after a restart the service carries on where it stopped. */
    public static final String CONSUMER_GROUP = "integration-service";

    /** Why a message was put aside: the failure's own words, which never quote personal data. */
    public static final String DEAD_LETTER_REASON = "dead-letter-reason";

    /** Where a Kafka event that was put aside came from: topic-partition@offset. */
    public static final String DEAD_LETTER_ORIGIN = "dead-letter-origin";

    /** The Kafka event's offset commit. Every send clears the headers first; exchange properties stay. */
    private static final String OFFSET_COMMIT = "offsetCommit";

    private Routes() {
    }

    /**
     * A Kafka topic to read, in the service's consumer group. Nothing is committed but what the route commits
     * itself (commitOffset), once the event's work is done: auto-commit commits whatever a poll returned, so on
     * shutdown it would commit an event still failing, and skip it for good. With breakOnFirstError a failed
     * event commits only what went through before it and is read again; the commit factory makes both commits
     * synchronous. A new group starts at the oldest event, so nothing published before the first start is missed.
     */
    public static String fromKafka(String topic) {
        return "kafka:" + topic
                + "?groupId=" + CONSUMER_GROUP
                + "&autoOffsetReset=earliest"
                + "&autoCommitEnable=false"
                + "&allowManualCommit=true"
                + "&kafkaManualCommitFactory=#class:" + DefaultKafkaManualCommitFactory.class.getName()
                + "&breakOnFirstError=true";
    }

    /**
     * A Kafka topic to send to. The send waits for Kafka's acknowledgement (acks=all) on the route's own thread,
     * so the step after it runs on that thread too: a Kafka route commits its consumer's offset afterwards, and a
     * Kafka consumer must not be used from another thread.
     */
    public static String toKafka(String topic) {
        return "kafka:" + topic + "?synchronous=true";
    }

    /**
     * A queue to read. A failure that still reaches the listener, which happens only on shutdown since the routes
     * retry until it works, sends the message back to the queue rather than dropping it; the routes do their own
     * retrying, so the listener does none.
     */
    public static String fromQueue(String queue) {
        return "spring-rabbitmq:" + InterbankTopology.EXCHANGE + "?queues=" + queue
                + "&rejectAndDontRequeue=false"
                + "&maximumRetryAttempts=0";
    }

    /**
     * A queue of the interbank exchange to send to. The send returns once RabbitMQ has confirmed it holds the
     * message (publisher confirms, see spring.rabbitmq.publisher-confirm-type), and fails if no confirm comes
     * within 5 seconds; the route then tries again.
     */
    public static String toQueue(String queue) {
        return "spring-rabbitmq:" + InterbankTopology.EXCHANGE + "?routingKey=" + queue + "&confirm=enabled";
    }

    /**
     * Retries for a route that reads Kafka: a failed step is tried 5 more times, 1 to 16 seconds apart; then the
     * event starts over from Kafka (see fromKafka). The event is tried until it works, and each round stays well
     * within Kafka's max.poll.interval.ms, so the consumer keeps its place in the group. On shutdown a failing
     * event stops at once, uncommitted, and comes again after the restart.
     */
    public static DefaultErrorHandlerDefinition retryInRounds(DefaultErrorHandlerDefinition errorHandler) {
        return errorHandler
                .maximumRedeliveries(5)
                .redeliveryDelay(1_000)
                .useExponentialBackOff()
                .backOffMultiplier(2)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .allowRedeliveryWhileStopping(false);
    }

    /**
     * Retries for a route that reads RabbitMQ: until it works, 1 second to 1 minute apart, while RabbitMQ keeps
     * the message; dropping a payment message could leave money held for good. On shutdown the retries stop at
     * once, and the message goes back to its queue (see fromQueue).
     */
    public static DefaultErrorHandlerDefinition retryUntilItWorks(DefaultErrorHandlerDefinition errorHandler) {
        return errorHandler
                .maximumRedeliveries(-1)
                .redeliveryDelay(1_000)
                .useExponentialBackOff()
                .backOffMultiplier(2)
                .maximumRedeliveryDelay(60_000)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .allowRedeliveryWhileStopping(false);
    }

    /** The first step of a route that reads Kafka: keeps the event's offset commit for commitOffset. */
    public static void keepOffsetCommit(Exchange exchange) {
        exchange.setProperty(OFFSET_COMMIT, exchange.getMessage().getHeader(KafkaConstants.MANUAL_COMMIT));
    }

    /** Commits the event in hand, and with it every earlier event of its partition. */
    public static void commitOffset(Exchange exchange) {
        KafkaManualCommit commit = exchange.getProperty(OFFSET_COMMIT, KafkaManualCommit.class);
        if (commit == null) {
            throw new IllegalStateException("The event has no offset commit: read it through Routes.fromKafka, "
                    + "and keep the commit with keepOffsetCommit");
        }
        commit.commit();
    }

    /**
     * Makes the Kafka event in hand, as it came (useOriginalMessage), ready for a dead-letter topic: keyed as it
     * was, with where it came from and why it was put aside.
     *
     * @return where it came from, for the route's log
     */
    public static String asKafkaDeadLetter(Exchange exchange) {
        Message message = exchange.getMessage();
        String origin = message.getHeader(KafkaConstants.TOPIC) + "-" + message.getHeader(KafkaConstants.PARTITION)
                + "@" + message.getHeader(KafkaConstants.OFFSET);
        Object key = message.getHeader(KafkaConstants.KEY);
        message.removeHeaders("*");
        if (key != null) {
            message.setHeader(KafkaConstants.KEY, key);
        }
        message.setHeader(DEAD_LETTER_ORIGIN, origin);
        message.setHeader(DEAD_LETTER_REASON, failureOf(exchange));
        return origin;
    }

    /** Makes the RabbitMQ message in hand, as it came, ready for a dead-letter queue: its id, and why. */
    public static void asQueueDeadLetter(Exchange exchange) {
        Message message = exchange.getMessage();
        String messageId = message.getHeader(SpringRabbitMQConstants.MESSAGE_ID, String.class);
        message.removeHeaders("*");
        if (messageId != null) {
            message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, messageId);
        }
        message.setHeader(DEAD_LETTER_REASON, failureOf(exchange));
    }

    /**
     * The message in hand becomes an ISO 20022 message for the other bank: nothing internal to our bank goes
     * with it, and its AMQP message id is its MsgId, by which the other bank can spot a copy.
     */
    public static void asInterbankMessage(Exchange exchange, String messageId, String xml) {
        Message message = exchange.getMessage();
        message.removeHeaders("*");
        message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, messageId);
        message.setBody(xml);
    }

    /** The message in hand becomes a Kafka event: JSON, keyed so that the events of one key stay in order. */
    public static void asKafkaEvent(Exchange exchange, String key, String json) {
        Message message = exchange.getMessage();
        message.removeHeaders("*");
        message.setHeader(KafkaConstants.KEY, key);
        message.setBody(json);
    }

    /** Why the exchange came to an onException clause: the message of the exception that sent it there. */
    public static String failureOf(Exchange exchange) {
        Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        return cause == null ? "no exception recorded" : cause.getMessage();
    }

    /** A new MsgId: a random UUID without its hyphens, 32 characters where ISO 20022 allows 35. */
    public static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
