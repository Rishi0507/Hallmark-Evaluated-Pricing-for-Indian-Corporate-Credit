package io.hallmark.tradeflow;

import io.hallmark.platform.events.EventPublisher;
import io.hallmark.platform.events.Topics;
import io.hallmark.tradeflow.domain.LifecycleRules;
import io.hallmark.tradeflow.domain.LifecycleState;
import io.hallmark.tradeflow.domain.ObservationBuilder;
import io.hallmark.tradeflow.domain.ObservationBuilder.TradeObservation;
import io.hallmark.tradeflow.domain.TradeFlow;
import io.hallmark.tradeflow.persistence.TradeflowRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class EndOfDayProcessor {
    static final int LIFECYCLE_LOOKBACK_DAYS = 10;

    private static final Logger log = LoggerFactory.getLogger(EndOfDayProcessor.class);

    private final TradeflowRepository repository;
    private final Optional<EventPublisher> publisher;

    public EndOfDayProcessor(TradeflowRepository repository, Optional<EventPublisher> publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    public record Result(int lifecycleChanges, int observations, int cleanObservations) {}

    @Transactional
    public Result run(LocalDate businessDate) {
        List<Runnable> outbox = new ArrayList<>();
        Map<String, TradeFlow> todaysFlows = new HashMap<>();
        int changes = 0;
        LocalDate from = businessDate.minusDays(LIFECYCLE_LOOKBACK_DAYS);
        Map<String, String> previousStates = repository.lifecycleStates(from, businessDate);
        for (TradeFlow flow : repository.flows(from, businessDate)) {
            if (flow.tradeDate().equals(businessDate)) {
                todaysFlows.put(flow.isin(), flow);
            }
            LifecycleState state = LifecycleRules.evaluate(flow, businessDate);
            String previous = previousStates.get(flow.isin() + "|" + flow.tradeDate());

            repository.saveLifecycle(flow, state, businessDate);
            if (!state.name().equals(previous)) {
                changes++;
                outbox.add(() -> publish(Topics.TRADES_LIFECYCLE, "TradeLifecycle", flow.isin(), flow.tradeDate(),
                        lifecyclePayload(flow, state)));
            }
        }

        int observations = 0;
        int clean = 0;
        for (var trade : repository.reportedOn(businessDate)) {
            TradeFlow flow = todaysFlows.get(trade.isin());
            Optional<TradeObservation> obs = ObservationBuilder.build(
                    trade, repository.security(trade.isin()), flow == null ? null : flow.mandatoryShare());
            if (obs.isEmpty()) {
                continue;
            }
            TradeObservation o = obs.get();
            repository.saveObservation(o);
            observations++;
            clean += o.clean() ? 1 : 0;
            outbox.add(() -> publish(Topics.TRADES_OBSERVATION, "TradeObservation", o.isin(), o.tradeDate(), o));
        }
        repository.completeEod(businessDate);

        Map<String, Object> control = new LinkedHashMap<>();
        control.put("businessDate", businessDate);
        control.put("counts", Map.of("observations", observations, "lifecycleChanges", changes));
        control.put("curveAvailable", true);
        outbox.add(() -> publish(Topics.CONTROL_TRADEFLOW_EOD, "EndOfDay", businessDate.toString(), businessDate,
                control));
        afterCommit(outbox);
        log.info("end of day {}: {} lifecycle changes, {} observations ({} clean)", businessDate, changes,
                observations, clean);
        return new Result(changes, observations, clean);
    }

    private static Map<String, Object> lifecyclePayload(TradeFlow f, LifecycleState state) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("isin", f.isin());
        p.put("tradeDate", f.tradeDate());
        p.put("state", state.name());
        p.put("reportedFaceLakh", f.reportedFaceLakh());
        p.put("settledFaceLakh", f.settledFaceLakh());
        p.put("failedFaceLakh", f.failedFaceLakh());
        p.put("settledShare", f.reportedFaceLakh() == null ? null : f.settledShare());
        p.put("lastSettlementDate", f.lastSettlementDate());
        return p;
    }

    private void publish(String topic, String type, String key, LocalDate date, Object payload) {
        publisher.ifPresent(p -> p.publish(topic, type, key, date, payload));
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
