package dev.tkachuk.integration.events;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON for the Kafka events, with its own mapper. Unknown fields are ignored, so bank-core can add fields
 * without breaking this service.
 */
@Component
public class EventJson {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("Empty " + type.getSimpleName() + " event");
        }
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new InvalidEventException("Invalid " + type.getSimpleName() + " event: " + reasonOf(e), e);
        }
    }

    public String write(Object event) {
        return mapper.writeValueAsString(event);
    }

    // The record's own validation message is clearer than Jackson's wrapper.
    private static String reasonOf(JacksonException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException rule) {
                return rule.getMessage();
            }
        }
        return e.getOriginalMessage();
    }
}
