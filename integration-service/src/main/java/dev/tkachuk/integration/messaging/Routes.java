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

/** Endpoints, retry policies and message helpers shared by all routes. */
public final class Routes {

    public static final String CONSUMER_GROUP = "integration-service";

    public static final String DEAD_LETTER_REASON = "dead-letter-reason";

    public static final String DEAD_LETTER_ORIGIN = "dead-letter-origin";

    private static final String OFFSET_COMMIT = "offsetCommit";

    private Routes() {
    }

    /**
     * Offsets are committed by the route (commitOffset), never automatically: auto-commit could commit an event
     * that is still failing at shutdown, and it would be lost. With breakOnFirstError a failed event is read again.
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

    /** Waits for Kafka's ack on the route's own thread, so the offset commit after it runs there too. */
    public static String toKafka(String topic) {
        return "kafka:" + topic + "?synchronous=true";
    }

    /** The route does the retrying; on shutdown an unfinished message goes back to the queue. */
    public static String fromQueue(String queue) {
        return "spring-rabbitmq:" + InterbankTopology.EXCHANGE + "?queues=" + queue
                + "&rejectAndDontRequeue=false"
                + "&maximumRetryAttempts=0";
    }

    /** Waits for RabbitMQ's publisher confirm. */
    public static String toQueue(String queue) {
        return "spring-rabbitmq:" + InterbankTopology.EXCHANGE + "?routingKey=" + queue + "&confirm=enabled";
    }

    /** Kafka routes: 5 retries 1 to 16 s apart, then the event is read again from Kafka. */
    public static DefaultErrorHandlerDefinition retryInRounds(DefaultErrorHandlerDefinition errorHandler) {
        return errorHandler
                .maximumRedeliveries(5)
                .redeliveryDelay(1_000)
                .useExponentialBackOff()
                .backOffMultiplier(2)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .allowRedeliveryWhileStopping(false);
    }

    /** RabbitMQ routes: retry until it works, up to a minute apart; dropping a payment could leave money held. */
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

    public static void keepOffsetCommit(Exchange exchange) {
        exchange.setProperty(OFFSET_COMMIT, exchange.getMessage().getHeader(KafkaConstants.MANUAL_COMMIT));
    }

    public static void commitOffset(Exchange exchange) {
        KafkaManualCommit commit = exchange.getProperty(OFFSET_COMMIT, KafkaManualCommit.class);
        if (commit == null) {
            throw new IllegalStateException("No offset commit on the exchange; call keepOffsetCommit first");
        }
        commit.commit();
    }

    /** The original event for a .DLT topic: same key, plus where it came from and why. */
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

    public static void asQueueDeadLetter(Exchange exchange) {
        Message message = exchange.getMessage();
        String messageId = message.getHeader(SpringRabbitMQConstants.MESSAGE_ID, String.class);
        message.removeHeaders("*");
        if (messageId != null) {
            message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, messageId);
        }
        message.setHeader(DEAD_LETTER_REASON, failureOf(exchange));
    }

    /** No internal headers go to the other bank; the AMQP message id is the ISO MsgId. */
    public static void asInterbankMessage(Exchange exchange, String messageId, String xml) {
        Message message = exchange.getMessage();
        message.removeHeaders("*");
        message.setHeader(SpringRabbitMQConstants.MESSAGE_ID, messageId);
        message.setBody(xml);
    }

    public static void asKafkaEvent(Exchange exchange, String key, String json) {
        Message message = exchange.getMessage();
        message.removeHeaders("*");
        message.setHeader(KafkaConstants.KEY, key);
        message.setBody(json);
    }

    public static String failureOf(Exchange exchange) {
        Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        return cause == null ? "no exception recorded" : cause.getMessage();
    }

    /** A random UUID without hyphens: 32 characters, ISO 20022 allows 35. */
    public static String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
