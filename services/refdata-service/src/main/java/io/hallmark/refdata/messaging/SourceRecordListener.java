package io.hallmark.refdata.messaging;

import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import io.hallmark.refdata.RefdataService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SourceRecordListener {
    private final EventReader reader;
    private final RefdataService service;

    public SourceRecordListener(EventReader reader, RefdataService service) {
        this.reader = reader;
        this.service = service;
    }

    @KafkaListener(
            id = "refdata-source",
            topics = Topics.REFDATA_SOURCE,
            concurrency = "${hallmark.kafka.concurrency:1}")
    public void onMessage(ConsumerRecord<String, String> record) {
        service.apply(reader.envelope(record.value()));
    }
}
