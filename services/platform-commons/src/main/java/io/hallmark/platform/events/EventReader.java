package io.hallmark.platform.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public class EventReader {
    private final JsonMapper mapper;

    public EventReader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public EventEnvelope envelope(String json) {
        try {
            EventEnvelope envelope = mapper.readValue(json, EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventType() == null || envelope.payload() == null) {
                throw new MalformedEventException("envelope is missing eventId, eventType or payload");
            }
            return envelope;
        } catch (JacksonException e) {
            throw new MalformedEventException("unparseable event: " + e.getOriginalMessage(), e);
        }
    }

    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return envelope.payloadAs(mapper, type);
        } catch (JacksonException e) {
            throw new MalformedEventException(
                    "payload does not match " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public JsonMapper mapper() {
        return mapper;
    }

    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message) {
            super(message);
        }

        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
