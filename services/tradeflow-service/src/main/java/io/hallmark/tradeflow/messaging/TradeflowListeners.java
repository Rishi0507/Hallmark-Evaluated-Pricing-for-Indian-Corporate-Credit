package io.hallmark.tradeflow.messaging;

import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import io.hallmark.tradeflow.TradeflowService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TradeflowListeners {
    private final EventReader reader;
    private final TradeflowService service;

    public TradeflowListeners(EventReader reader, TradeflowService service) {
        this.reader = reader;
        this.service = service;
    }

    @KafkaListener(id = "tradeflow-reported", topics = Topics.TRADES_REPORTED,
            concurrency = "${hallmark.kafka.concurrency:1}")
    public void reported(ConsumerRecord<String, String> record) {
        service.onReported(reader.envelope(record.value()));
    }

    @KafkaListener(id = "tradeflow-settlement", topics = Topics.TRADES_SETTLEMENT,
            concurrency = "${hallmark.kafka.concurrency:1}")
    public void settlement(ConsumerRecord<String, String> record) {
        service.onSettlement(reader.envelope(record.value()));
    }

    @KafkaListener(id = "tradeflow-eod", topics = Topics.CONTROL_EOD)
    public void endOfDay(ConsumerRecord<String, String> record) {
        service.onEndOfDay(reader.envelope(record.value()));
    }

    @KafkaListener(id = "tradeflow-security", topics = Topics.REFDATA_SECURITY)
    public void security(ConsumerRecord<String, String> record) {
        service.onSecurity(reader.envelope(record.value()));
    }
}
