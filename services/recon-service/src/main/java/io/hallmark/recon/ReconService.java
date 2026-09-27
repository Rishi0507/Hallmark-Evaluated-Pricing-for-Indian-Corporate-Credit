package io.hallmark.recon;

import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventPublisher;
import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.events.Topics;
import io.hallmark.platform.idempotency.ProcessedEvents;
import io.hallmark.recon.domain.Inputs.EndOfDay;
import io.hallmark.recon.domain.Inputs.ReportedTrade;
import io.hallmark.recon.domain.Inputs.SettlementRecord;
import io.hallmark.recon.domain.Inputs.SourceRecord;
import io.hallmark.recon.domain.Outcome;
import io.hallmark.recon.domain.RefdataRecon;
import io.hallmark.recon.persistence.ReconRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReconService {
    private final ReconRepository repository;
    private final ProcessedEvents processed;
    private final EventReader reader;
    private final BreakBook book;
    private final Optional<EventPublisher> publisher;
    private final Clock clock;

    public ReconService(
            ReconRepository repository,
            ProcessedEvents processed,
            EventReader reader,
            BreakBook book,
            Optional<EventPublisher> publisher,
            Clock clock) {
        this.repository = repository;
        this.processed = processed;
        this.reader = reader;
        this.book = book;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public void onReported(EventEnvelope e) {
        if (processed.markIfNew(e.eventId())) {
            repository.saveReported(reader.payload(e, ReportedTrade.class));
        }
    }

    @Transactional
    public void onSettlement(EventEnvelope e) {
        if (processed.markIfNew(e.eventId())) {
            repository.saveLeg(e.eventId(), reader.payload(e, SettlementRecord.class));
        }
    }

    @Transactional
    public void onEndOfDay(EventEnvelope e) {
        if (processed.markIfNew(e.eventId())) {
            repository.saveEod(reader.payload(e, EndOfDay.class));
        }
    }

    @Transactional
    public void onSourceRecord(EventEnvelope e) {
        if (!processed.markIfNew(e.eventId())) {
            return;
        }
        SourceRecord record = reader.payload(e, SourceRecord.class);
        repository.saveSource(record, reader.mapper().writeValueAsString(e.payload()));
        Map<String, SourceRecord> sources = repository.sources(record.isin());
        SourceRecord nsdl = sources.get("NSDL");
        LocalDate asOf = nsdl != null && nsdl.snapshotDate() != null ? nsdl.snapshotDate() : LocalDate.now(clock);
        RefdataRecon.reconcile(nsdl, sources.get("NSE_NTRP"), asOf).ifPresent(o -> {
            Optional<String> change = book.apply(o, LocalDate.now(clock));
            change.ifPresent(status -> publishAfterCommit(o, status));
        });
    }

    void publishAfterCommit(Outcome o, String status) {
        if (publisher.isEmpty()) {
            return;
        }
        Runnable send = () -> publisher.get().publish(Topics.RECON_BREAKS, "ReconBreak", o.isin(), o.businessDate(),
                ReconEndOfDay.breakPayload(o, status));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
