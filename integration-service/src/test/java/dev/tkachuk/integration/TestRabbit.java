package dev.tkachuk.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Reads the partner bank's queues and the dead-letter queues. */
public final class TestRabbit {

    private static final String DEAD_LETTER_REASON = "dead-letter-reason";

    private final RabbitTemplate rabbit;

    public TestRabbit(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public <T> T next(String queue, Duration wait, Function<String, T> parse, Predicate<T> wanted) {
        Instant deadline = Instant.now().plus(wait);
        while (Instant.now().isBefore(deadline)) {
            Message message = rabbit.receive(queue, 500);
            if (message == null) {
                continue;
            }
            T parsed = parse.apply(bodyOf(message));
            if (wanted.test(parsed)) {
                assertThat(message.getMessageProperties().getMessageId()).as("the AMQP message id").isNotBlank();
                return parsed;
            }
        }
        return null;
    }

    public Message deadLetter(String queue, Duration wait) {
        Message dead = rabbit.receive(queue, wait.toMillis());
        assertThat(dead).as("a message on " + queue).isNotNull();
        return dead;
    }

    public static String reasonOf(Message deadLetter) {
        return String.valueOf(deadLetter.getMessageProperties().getHeaders().get(DEAD_LETTER_REASON));
    }

    public static String bodyOf(Message message) {
        String encoding = message.getMessageProperties().getContentEncoding();
        return new String(message.getBody(), encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding));
    }
}
