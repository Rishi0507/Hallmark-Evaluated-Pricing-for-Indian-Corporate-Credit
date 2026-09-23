package io.hallmark.recon;

import io.hallmark.recon.persistence.ReconRepository;
import io.hallmark.recon.persistence.ReconRepository.PendingEod;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconCoordinator {
    private final ReconRepository repository;
    private final ReconEndOfDay endOfDay;

    public ReconCoordinator(ReconRepository repository, ReconEndOfDay endOfDay) {
        this.repository = repository;
        this.endOfDay = endOfDay;
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
        if (repository.reportedCount(eod.businessDate()) < eod.expectedReported()
                || repository.legCount(eod.businessDate()) < eod.expectedSettlements()) {
            return false;
        }
        endOfDay.run(eod.businessDate());
        return true;
    }
}
