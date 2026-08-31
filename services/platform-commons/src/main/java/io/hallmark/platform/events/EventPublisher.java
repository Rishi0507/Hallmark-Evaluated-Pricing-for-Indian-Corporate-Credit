package io.hallmark.platform.events;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

public class EventPublisher {
    private final KafkaTemplate<String, String> template;
    private final JsonMapper mapper;
    private final String source;

    public EventPublisher(KafkaTemplate<String, String> template, JsonMapper mapper, String source) {
        this.template = template;
        this.mapper = mapper;
        this.source = source;
    }

    public CompletableFuture<SendResult<String, String>> publish(
            String topic, String eventType, String key, LocalDate businessDate, Object payload) {
        EventEnvelope envelope = EventEnvelope.of(mapper, eventType, source, key, businessDate, payload);
        return template.send(topic, key, mapper.writeValueAsString(envelope));
    }

    public void flush() {
        template.flush();
    }
}
