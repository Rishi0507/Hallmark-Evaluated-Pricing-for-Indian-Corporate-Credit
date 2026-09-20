package io.hallmark.pricing;

import io.hallmark.bondmath.ZeroCurve;
import io.hallmark.platform.events.EventPublisher;
import io.hallmark.platform.events.Topics;
import io.hallmark.pricing.domain.CreditCurveModel;
import io.hallmark.pricing.domain.MarkingEngine;
import io.hallmark.pricing.domain.MarkingEngine.Mark;
import io.hallmark.pricing.domain.MarkingEngine.Prediction;
import io.hallmark.pricing.domain.MarkingEngine.Residual;
import io.hallmark.pricing.domain.Observation;
import io.hallmark.pricing.domain.SecurityView;
import io.hallmark.pricing.persistence.PricingRepository;
import io.hallmark.pricing.persistence.PricingRepository.RawObservation;
import io.hallmark.pricing.persistence.PricingRepository.StoredCurve;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PricingRun {
    private static final Logger log = LoggerFactory.getLogger(PricingRun.class);

    private final PricingRepository repository;
    private final SecurityCache cache;
    private final PricingProperties properties;
    private final Optional<EventPublisher> publisher;
    private final JsonMapper mapper;
    private final CreditCurveModel.Params curveParams = CreditCurveModel.Params.defaults();
    private final MarkingEngine.Params markParams = MarkingEngine.Params.defaults();

    public PricingRun(
            PricingRepository repository,
            SecurityCache cache,
            PricingProperties properties,
            Optional<EventPublisher> publisher,
            JsonMapper mapper) {
        this.repository = repository;
        this.cache = cache;
        this.properties = properties;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    public record Result(LocalDate date, LocalDate curveDate, int observations, int marks, int predictions) {}

    @Transactional
    public Result run(LocalDate date) {
        Optional<StoredCurve> stored = repository.latestCurveOnOrBefore(date);
        if (stored.isEmpty()) {
            repository.completeEod(date, null, 0, 0);
            log.warn("no risk-free curve on or before {}; nothing priced", date);
            return new Result(date, null, 0, 0, 0);
        }
        ZeroCurve curve = toZeroCurve(stored.get(), date);
        Map<String, SecurityView> securities = cache.snapshot();

        List<Observation> todays = new ArrayList<>();
        for (RawObservation r : repository.cleanObservationsOn(date)) {
            SecurityView s = securities.get(r.isin());
            if (s != null) {
                MarkingEngine.observe(s, date, r.cleanPrice(), r.yieldPct(), r.faceLakh(), r.institutional(), curve)
                        .ifPresent(todays::add);
            }
        }
        repository.saveObservationSpreads(todays);

        List<Observation> history = repository.spreadObservations(date.minusDays(properties.historyDays()), date);
        Map<String, Residual> residualsBefore =
                repository.lastInstitutionalResiduals(date.minusDays(markParams.carryWindowDays()), date);

        List<Prediction> predictions = CreditCurveModel.fit(date, date.minusDays(1), history, curveParams)
                .map(before -> MarkingEngine.backtest(date, curve, before,
                        todays.stream().filter(Observation::institutional).toList(), securities, residualsBefore,
                        markParams))
                .orElse(List.of());
        repository.replacePredictions(date, predictions);

        Optional<CreditCurveModel> model = CreditCurveModel.fit(date, date, history, curveParams);
        List<Mark> marks = List.of();
        if (model.isPresent()) {
            List<SecurityView> universe = repository
                    .recentlyObservedIsins(date.minusDays(properties.universeDays()), date)
                    .stream()
                    .map(securities::get)
                    .filter(Objects::nonNull)
                    .toList();
            Map<String, List<Observation>> todaysByIsin =
                    todays.stream().collect(Collectors.groupingBy(Observation::isin));
            List<Observation> issuerEvidence = history.stream()
                    .filter(o -> o.issuerKey() != null && !o.tradeDate().isBefore(date.minusDays(60)))
                    .toList();
            marks = MarkingEngine.mark(date, curve, model.get(), universe, todaysByIsin, residualsBefore,
                    issuerEvidence, markParams);

            Map<String, Double> modelSpreads = new HashMap<>();
            for (Observation o : todays) {
                modelSpreads.putIfAbsent(o.isin(), model.get().modelSpread(o.bucket(), o.sectorGroup(),
                        o.issuerKey(), o.tenorYears()));
            }
            repository.saveModelSpreads(date, modelSpreads);
            repository.replaceCurveFits(date, model.get());
            if (properties.persistMarks(date)) {
                repository.replaceMarks(date, marks);
            }
        }
        repository.completeEod(date, stored.get().asOf(), marks.size(), predictions.size());
        publishAfterCommit(date, stored.get().asOf(), marks, predictions.size());
        log.info("priced {}: curve {}, {} observations, {} marks, {} out-of-sample predictions", date,
                stored.get().asOf(), todays.size(), marks.size(), predictions.size());
        return new Result(date, stored.get().asOf(), todays.size(), marks.size(), predictions.size());
    }

    private ZeroCurve toZeroCurve(StoredCurve stored, LocalDate valuationDate) {
        JsonNode points = mapper.readTree(stored.pointsJson());
        double[] tenors = new double[points.size()];
        double[] yields = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            tenors[i] = points.get(i).get("tenorYears").asDouble();
            yields[i] = points.get(i).get("parYield").asDouble();
        }
        return ZeroCurve.fromSemiAnnualParYields(valuationDate, tenors, yields);
    }

    private void publishAfterCommit(LocalDate date, LocalDate curveDate, List<Mark> marks, int predictions) {
        if (publisher.isEmpty()) {
            return;
        }
        boolean publishMarks = properties.persistMarks(date);
        Runnable send = () -> {
            EventPublisher p = publisher.get();
            if (publishMarks) {
                marks.forEach(m -> p.publish(Topics.PRICING_MARKS, "Mark", m.isin(), date, markPayload(m)));
            }
            Map<String, Object> control = new LinkedHashMap<>();
            control.put("businessDate", date);
            control.put("counts", Map.of("marks", marks.size(), "predictions", predictions));
            control.put("curveAvailable", date.equals(curveDate));
            p.publish(Topics.CONTROL_PRICING_EOD, "EndOfDay", date.toString(), date, control);
        };
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

    static Map<String, Object> markPayload(Mark m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("isin", m.isin());
        p.put("valuationDate", m.valuationDate());
        p.put("method", m.method().name());
        p.put("cleanPrice", m.cleanPrice());
        p.put("dirtyPrice", m.dirtyPrice());
        p.put("accrued", m.accrued());
        p.put("yieldPct", m.yieldPct());
        p.put("zSpreadBps", m.zSpreadBps());
        p.put("gSpreadBps", m.gSpreadBps());
        p.put("modifiedDuration", m.modifiedDuration());
        p.put("ratingBucket", m.ratingBucket());
        p.put("confidence", m.confidence());
        p.put("evidence", m.evidence().stream().map(e -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("kind", e.kind());
            ev.put("isin", e.isin());
            ev.put("date", e.date());
            ev.put("weight", e.weight());
            ev.put("spreadBps", e.spreadBps());
            ev.put("faceValueLakh", e.faceValueLakh());
            return ev;
        }).toList());
        return p;
    }
}
