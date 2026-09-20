package io.hallmark.pricing.messaging;

import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import io.hallmark.pricing.PricingInputs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PricingListeners {
    private final EventReader reader;
    private final PricingInputs inputs;

    public PricingListeners(EventReader reader, PricingInputs inputs) {
        this.reader = reader;
        this.inputs = inputs;
    }

    @KafkaListener(id = "pricing-security", topics = Topics.REFDATA_SECURITY)
    public void security(ConsumerRecord<String, String> record) {
        inputs.onSecurity(reader.envelope(record.value()));
    }

    @KafkaListener(id = "pricing-curve", topics = Topics.MARKET_CURVE)
    public void curve(ConsumerRecord<String, String> record) {
        inputs.onCurve(reader.envelope(record.value()));
    }

    @KafkaListener(id = "pricing-observation", topics = Topics.TRADES_OBSERVATION,
            concurrency = "${hallmark.kafka.concurrency:1}")
    public void observation(ConsumerRecord<String, String> record) {
        inputs.onObservation(reader.envelope(record.value()));
    }

    @KafkaListener(id = "pricing-eod", topics = Topics.CONTROL_TRADEFLOW_EOD)
    public void endOfDay(ConsumerRecord<String, String> record) {
        inputs.onTradeflowEod(reader.envelope(record.value()));
    }
}
