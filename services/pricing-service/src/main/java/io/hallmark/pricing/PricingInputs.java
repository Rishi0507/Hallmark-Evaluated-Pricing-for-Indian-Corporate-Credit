package io.hallmark.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.idempotency.ProcessedEvents;
import io.hallmark.pricing.domain.SecurityView;
import io.hallmark.pricing.persistence.PricingRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class PricingInputs {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TradeObservation(
            String isin, LocalDate tradeDate, double cleanPrice, double yieldPct, double faceValueLakh,
            String sizeClass, List<String> flags) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EndOfDay(LocalDate businessDate, Map<String, Integer> counts) {}

    private final PricingRepository repository;
    private final ProcessedEvents processed;
    private final EventReader reader;
    private final SecurityCache cache;

    public PricingInputs(
            PricingRepository repository, ProcessedEvents processed, EventReader reader, SecurityCache cache) {
        this.repository = repository;
        this.processed = processed;
        this.reader = reader;
        this.cache = cache;
    }

    @Transactional
    public void onSecurity(EventEnvelope envelope) {
        SecurityView view = reader.payload(envelope, SecurityView.class);
        JsonNode p = envelope.payload();
        repository.saveSecurity(view.isin(), view.issuerKey(), view.issuerName(), view.sectorGroup(),
                view.pricingEligible(), reader.mapper().writeValueAsString(p));
        cache.put(view);
    }

    @Transactional
    public void onCurve(EventEnvelope envelope) {
        if (!processed.markIfNew(envelope.eventId())) {
            return;
        }
        JsonNode p = envelope.payload();
        LocalDate asOf = LocalDate.parse(p.get("asOfDate").asString());
        JsonNode points = p.get("points");
        if (points == null || !points.isArray() || points.size() < 4) {
            throw new IllegalArgumentException("curve for " + asOf + " has fewer than four points");
        }
        repository.saveCurve(asOf, reader.mapper().writeValueAsString(points));
    }

    @Transactional
    public void onObservation(EventEnvelope envelope) {
        if (!processed.markIfNew(envelope.eventId())) {
            return;
        }
        TradeObservation o = reader.payload(envelope, TradeObservation.class);
        repository.saveRawObservation(o.isin(), o.tradeDate(), o.cleanPrice(), o.yieldPct(), o.faceValueLakh(),
                "INSTITUTIONAL".equals(o.sizeClass()), o.flags() == null ? List.of() : o.flags());
    }

    @Transactional
    public void onTradeflowEod(EventEnvelope envelope) {
        if (!processed.markIfNew(envelope.eventId())) {
            return;
        }
        EndOfDay eod = reader.payload(envelope, EndOfDay.class);
        int expected = eod.counts() == null ? 0 : eod.counts().getOrDefault("observations", 0);
        repository.saveEod(eod.businessDate(), expected);
    }
}
