package io.hallmark.pricing;

import io.hallmark.pricing.persistence.PricingRepository;
import io.hallmark.pricing.persistence.PricingRepository.PendingEod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PricingCoordinator {
    private final PricingRepository repository;
    private final PricingRun run;
    private final PricingProperties properties;
    private final Clock clock;

    public PricingCoordinator(PricingRepository repository, PricingRun run, PricingProperties properties, Clock clock) {
        this.repository = repository;
        this.run = run;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hallmark.pricing.poll-ms:2000}")
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
        if (repository.observationCount(eod.businessDate()) < eod.expectedObservations()) {
            return false;
        }
        boolean curveReady = repository.curveExists(eod.businessDate())
                || Duration.between(eod.receivedAt(), Instant.now(clock)).getSeconds() >= properties.curveGraceSeconds();
        if (!curveReady) {
            return false;
        }
        run.run(eod.businessDate());
        return true;
    }
}
