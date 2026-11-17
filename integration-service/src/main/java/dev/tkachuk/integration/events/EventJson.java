package dev.tkachuk.integration.events;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the events on Kafka as JSON. That JSON is a contract with bank-core, so this class sets up the
 * format itself instead of sharing the web layer's mapper: dates as ISO-8601 text, amounts as JSON numbers that
 * are read into BigDecimal exactly, and unknown fields ignored, so bank-core can add a field without breaking
 * this service (a tolerant reader). Both settings are Jackson 3's defaults already; they are set here because
 * the contract depends on them.
 */
@Component
public class EventJson {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /**
     * @throws InvalidEventException when the text is not JSON, or not a valid event of this type; the reason
     *                               names the field and its rule, as the event's own checks word it
     */
    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("A " + type.getSimpleName() + " event is empty");
        }
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new InvalidEventException("Not a valid " + type.getSimpleName() + " event: " + reasonOf(e), e);
        }
    }

    public String write(Object event) {
        return mapper.writeValueAsString(event);
    }

    /**
     * The event's own check that failed, such as "amount must be positive", or else what Jackson found wrong.
     * Jackson wraps a check thrown by a record's constructor, so it is looked for along the causes.
     */
    private static String reasonOf(JacksonException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException rule) {
                return rule.getMessage();
            }
        }
        return e.getOriginalMessage();
    }
}
