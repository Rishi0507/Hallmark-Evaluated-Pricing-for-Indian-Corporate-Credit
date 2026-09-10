package io.hallmark.tradeflow;

import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.idempotency.ProcessedEvents;
import io.hallmark.tradeflow.domain.Inputs.EndOfDay;
import io.hallmark.tradeflow.domain.Inputs.ReportedTrade;
import io.hallmark.tradeflow.domain.Inputs.SecurityRef;
import io.hallmark.tradeflow.domain.Inputs.SettlementRecord;
import io.hallmark.tradeflow.persistence.TradeflowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeflowService {
    private static final Pattern ISIN = Pattern.compile("^IN[A-Z0-9]{9}[0-9]$");

    private final TradeflowRepository repository;
    private final ProcessedEvents processed;
    private final EventReader reader;
    private final MeterRegistry meters;

    public TradeflowService(
            TradeflowRepository repository, ProcessedEvents processed, EventReader reader, MeterRegistry meters) {
        this.repository = repository;
        this.processed = processed;
        this.reader = reader;
        this.meters = meters;
    }

    @Transactional
    public void onReported(EventEnvelope envelope) {
        if (firstSeen(envelope, "ReportedTrade")) {
            ReportedTrade trade = reader.payload(envelope, ReportedTrade.class);
            requireIsin(trade.isin());
            if (trade.tradeDate() == null) {
                throw new IllegalArgumentException("reported trade without trade date");
            }
            repository.saveReported(trade);
        }
    }

    @Transactional
    public void onSettlement(EventEnvelope envelope) {
        if (firstSeen(envelope, "SettlementRecord")) {
            SettlementRecord leg = reader.payload(envelope, SettlementRecord.class);
            requireIsin(leg.isin());
            if (leg.tradeDate() == null || leg.settlementDate() == null) {
                throw new IllegalArgumentException("settlement record without dates");
            }
            repository.saveLeg(envelope.eventId(), leg);
        }
    }

    @Transactional
    public void onEndOfDay(EventEnvelope envelope) {
        if (firstSeen(envelope, "EndOfDay")) {
            repository.saveEod(reader.payload(envelope, EndOfDay.class));
        }
    }

    @Transactional
    public void onSecurity(EventEnvelope envelope) {
        SecurityRef ref = reader.payload(envelope, SecurityRef.class);
        requireIsin(ref.isin());
        repository.saveSecurity(ref, reader.mapper().writeValueAsString(envelope.payload()));
        meters.counter("hallmark.tradeflow.events", "type", "GoldenSecurity").increment();
    }

    private boolean firstSeen(EventEnvelope envelope, String expectedType) {
        if (!expectedType.equals(envelope.eventType())) {
            throw new IllegalArgumentException("expected " + expectedType + " but got " + envelope.eventType());
        }
        boolean fresh = processed.markIfNew(envelope.eventId());
        meters.counter("hallmark.tradeflow.events", "type", expectedType, "duplicate", String.valueOf(!fresh))
                .increment();
        return fresh;
    }

    private static void requireIsin(String isin) {
        if (isin == null || !ISIN.matcher(isin).matches()) {
            throw new IllegalArgumentException("invalid ISIN " + isin);
        }
    }
}
