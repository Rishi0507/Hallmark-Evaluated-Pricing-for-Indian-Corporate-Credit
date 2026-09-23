package io.hallmark.recon.messaging;

import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import io.hallmark.recon.ReconService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReconListeners {
    private final EventReader reader;
    private final ReconService service;

    public ReconListeners(EventReader reader, ReconService service) {
        this.reader = reader;
        this.service = service;
    }

    @KafkaListener(id = "recon-reported", topics = Topics.TRADES_REPORTED, concurrency = "${hallmark.kafka.concurrency:1}")
    public void reported(ConsumerRecord<String, String> record) {
        service.onReported(reader.envelope(record.value()));
    }

    @KafkaListener(id = "recon-settlement", topics = Topics.TRADES_SETTLEMENT,
            concurrency = "${hallmark.kafka.concurrency:1}")
    public void settlement(ConsumerRecord<String, String> record) {
        service.onSettlement(reader.envelope(record.value()));
    }

    @KafkaListener(id = "recon-eod", topics = Topics.CONTROL_EOD)
    public void endOfDay(ConsumerRecord<String, String> record) {
        service.onEndOfDay(reader.envelope(record.value()));
    }

    @KafkaListener(id = "recon-refdata", topics = Topics.REFDATA_SOURCE)
    public void refdata(ConsumerRecord<String, String> record) {
        service.onSourceRecord(reader.envelope(record.value()));
    }
}
