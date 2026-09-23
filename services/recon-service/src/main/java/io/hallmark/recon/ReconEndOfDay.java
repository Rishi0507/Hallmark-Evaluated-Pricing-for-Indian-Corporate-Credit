package io.hallmark.recon;

import io.hallmark.platform.events.EventPublisher;
import io.hallmark.platform.events.Topics;
import io.hallmark.recon.domain.Inputs.ReportedTrade;
import io.hallmark.recon.domain.Inputs.SourceRecord;
import io.hallmark.recon.domain.Outcome;
import io.hallmark.recon.domain.Outcome.ReconType;
import io.hallmark.recon.domain.Outcome.Result;
import io.hallmark.recon.domain.TradeSettlementRecon;
import io.hallmark.recon.domain.TradeSettlementRecon.Activity;
import io.hallmark.recon.domain.YieldRecon;
import io.hallmark.recon.persistence.ReconRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ReconEndOfDay {
    static final int LOOKBACK_DAYS = 10;

    private static final Logger log = LoggerFactory.getLogger(ReconEndOfDay.class);

    private final ReconRepository repository;
    private final BreakBook book;
    private final Optional<EventPublisher> publisher;

    public ReconEndOfDay(ReconRepository repository, BreakBook book, Optional<EventPublisher> publisher) {
        this.repository = repository;
        this.book = book;
        this.publisher = publisher;
    }

    @Transactional
    public Map<String, int[]> run(LocalDate date) {
        List<Runnable> outbox = new ArrayList<>();
        Map<LocalDate, int[]> settlementCounts = new HashMap<>();
        for (Activity a : repository.activity(date.minusDays(LOOKBACK_DAYS), date)) {
            Outcome o = TradeSettlementRecon.reconcile(a, date);
            count(settlementCounts.computeIfAbsent(a.tradeDate(), d -> new int[5]), o.result());
            book.apply(o, date).ifPresent(status -> outbox.add(() -> publish(o, status)));
        }
        settlementCounts.forEach((tradeDate, counts) ->
                repository.saveRun(ReconType.TRADE_VS_SETTLEMENT.name(), tradeDate, counts, date));

        List<ReportedTrade> bonds = repository.reportedOn(date).stream()
                .filter(t -> "BOND".equals(t.instrumentClass()))
                .toList();
        Map<String, SourceRecord> nsdl =
                repository.nsdlRecords(bonds.stream().map(ReportedTrade::isin).collect(Collectors.toSet()));
        int[] yieldCounts = new int[5];
        for (ReportedTrade t : bonds) {
            Optional<Outcome> o = YieldRecon.reconcile(t, nsdl.get(t.isin()));
            if (o.isEmpty()) {
                continue;
            }
            count(yieldCounts, o.get().result());
            book.apply(o.get(), date).ifPresent(status -> outbox.add(() -> publish(o.get(), status)));
        }
        repository.saveRun(ReconType.YIELD_RECOMPUTE.name(), date, yieldCounts, date);
        repository.completeEod(date);
        afterCommit(outbox);

        int[] today = settlementCounts.getOrDefault(date, new int[5]);
        log.info("reconciled {}: settlement {} evaluated / {} breaks (window open: {} pending); yields {} evaluated / {}"
                + " explained / {} breaks", date, today[0], today[4], today[3], yieldCounts[0], yieldCounts[2],
                yieldCounts[4]);
        Map<String, int[]> out = new LinkedHashMap<>();
        out.put(ReconType.TRADE_VS_SETTLEMENT.name(), today);
        out.put(ReconType.YIELD_RECOMPUTE.name(), yieldCounts);
        return out;
    }

    private static void count(int[] counts, Result r) {
        counts[0]++;
        switch (r) {
            case MATCHED -> counts[1]++;
            case EXPLAINED -> counts[2]++;
            case PENDING -> counts[3]++;
            case BREAK -> counts[4]++;
        }
    }

    static Map<String, Object> breakPayload(Outcome o, String status) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("breakId", o.breakId());
        p.put("reconType", o.reconType().name());
        p.put("breakType", o.breakType() == null ? "MATCHED" : o.breakType());
        p.put("status", status);
        p.put("businessKey", o.isin());
        p.put("businessDate", o.businessDate());
        p.put("severity", o.severity() == null ? "LOW" : o.severity());
        p.put("amountLakh", o.amountLakh());
        p.put("details", o.details());
        return p;
    }

    private void publish(Outcome o, String status) {
        publisher.ifPresent(p -> p.publish(Topics.RECON_BREAKS, "ReconBreak", o.isin(), o.businessDate(),
                breakPayload(o, status)));
    }

    private static void afterCommit(List<Runnable> outbox) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    outbox.forEach(Runnable::run);
                }
            });
        } else {
            outbox.forEach(Runnable::run);
        }
    }
}
