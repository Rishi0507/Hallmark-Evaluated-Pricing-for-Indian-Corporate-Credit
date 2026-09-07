package io.hallmark.tradeflow;

import io.hallmark.tradeflow.persistence.TradeflowRepository;
import io.hallmark.tradeflow.persistence.TradeflowRepository.PendingEod;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EndOfDayCoordinator {
    private final TradeflowRepository repository;
    private final EndOfDayProcessor processor;

    public EndOfDayCoordinator(TradeflowRepository repository, EndOfDayProcessor processor) {
        this.repository = repository;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${hallmark.eod.poll-ms:2000}")
    public void poll() {
        while (processNext()) {
        }
    }

    boolean processNext() {
        Optional<PendingEod> pending = repository.earliestPendingEod();
        if (pending.isEmpty()) {
            return false;
        }
        PendingEod eod = pending.get();
        boolean complete = repository.reportedCount(eod.businessDate()) >= eod.expectedReported()
                && repository.legCount(eod.businessDate()) >= eod.expectedSettlements();
        if (!complete) {
            return false;
        }
        processor.run(eod.businessDate());
        return true;
    }
}
