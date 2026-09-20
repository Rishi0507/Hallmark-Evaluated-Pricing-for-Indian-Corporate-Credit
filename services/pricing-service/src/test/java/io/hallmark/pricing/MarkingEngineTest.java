package io.hallmark.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.hallmark.bondmath.ZeroCurve;
import io.hallmark.pricing.domain.CreditCurveModel;
import io.hallmark.pricing.domain.MarkingEngine;
import io.hallmark.pricing.domain.MarkingEngine.Mark;
import io.hallmark.pricing.domain.MarkingEngine.Method;
import io.hallmark.pricing.domain.MarkingEngine.Prediction;
import io.hallmark.pricing.domain.MarkingEngine.Residual;
import io.hallmark.pricing.domain.Observation;
import io.hallmark.pricing.domain.RatingBuckets;
import io.hallmark.pricing.domain.SecurityView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MarkingEngineTest {
    static Map<String, SecurityView> securities;
    static List<Observation> day1;
    static List<Observation> day2;
    static ZeroCurve curve2;
    static CreditCurveModel.Params curveParams = CreditCurveModel.Params.defaults();
    static MarkingEngine.Params markParams = MarkingEngine.Params.defaults();

    @BeforeAll
    static void load() {
        securities = RealMarket.securities();
        day1 = RealMarket.observations(RealMarket.DAY1, securities);
        day2 = RealMarket.observations(RealMarket.DAY2, securities);
        curve2 = RealMarket.curve(RealMarket.DAY2);
    }

    @Test
    void realDaysProduceEnoughObservations() {
        assertThat(securities).hasSizeGreaterThan(300);
        assertThat(day1).hasSizeGreaterThan(100);
        assertThat(day2).hasSizeGreaterThan(100);
        assertThat(day2).allSatisfy(o -> assertThat(o.zSpreadBps()).isBetween(-300.0, 3000.0));
    }

    @Test
    void fittedCurvesAreOrderedByRatingAndPlausible() {
        List<Observation> history = new ArrayList<>(day1);
        history.addAll(day2);
        CreditCurveModel model = CreditCurveModel.fit(RealMarket.DAY2, RealMarket.DAY2, history, curveParams)
                .orElseThrow();
        assertThat(model.curves().get("AAA").source()).isEqualTo("FITTED");
        double previous = -Double.MAX_VALUE;
        for (String bucket : RatingBuckets.ORDER) {
            double s = model.spread(bucket, 3.0);
            assertThat(s).as(bucket).isGreaterThanOrEqualTo(previous);
            previous = s;
        }

        assertThat(model.spread("AAA", 3.0)).isBetween(0.0, 250.0);
        assertThat(model.spread(RatingBuckets.UNRATED, 3.0)).isGreaterThanOrEqualTo(model.spread("BBB", 3.0));
    }

    @Test
    void tradedMarksReproduceTradedPrices() {
        CreditCurveModel model = CreditCurveModel.fit(RealMarket.DAY2, RealMarket.DAY2, day2, curveParams).orElseThrow();
        Map<String, List<Observation>> byIsin = day2.stream().collect(Collectors.groupingBy(Observation::isin));
        List<SecurityView> universe = byIsin.keySet().stream().map(securities::get).toList();
        List<Mark> marks = MarkingEngine.mark(RealMarket.DAY2, curve2, model, universe, byIsin, Map.of(), day2, markParams);
        assertThat(marks).isNotEmpty();
        Map<String, Mark> markByIsin = marks.stream().collect(Collectors.toMap(Mark::isin, Function.identity()));
        for (Observation o : day2) {
            Mark m = markByIsin.get(o.isin());
            if (m == null) {
                continue;
            }
            assertThat(m.confidence()).isBetween(0.0, 1.0);
            if (m.method() == Method.TRADED) {
                assertThat(m.cleanPrice()).as(o.isin()).isCloseTo(o.cleanPrice(), within(0.01));
                assertThat(m.zSpreadBps()).isCloseTo(o.zSpreadBps(), within(1e-6));
                assertThat(m.evidence()).extracting(MarkingEngine.Evidence::kind).contains("OWN_TRADE");
            } else {
                assertThat(m.method()).isEqualTo(Method.TRADED_RETAIL);
            }
        }
    }

    @Test
    void secondDayCarriesFirstDayResidualsAndIsScoredOutOfSample() {
        CreditCurveModel model1 = CreditCurveModel.fit(RealMarket.DAY1, RealMarket.DAY1, day1, curveParams).orElseThrow();
        Map<String, Residual> residuals = new HashMap<>();
        for (Observation o : day1) {
            if (o.institutional()) {
                residuals.put(o.isin(), new Residual(o.tradeDate(), o.zSpreadBps(),
                        model1.modelSpread(o.bucket(), o.sectorGroup(), o.issuerKey(), o.tenorYears()),
                        o.yieldPct(), o.faceValueLakh(), o.riskFreeYieldPct()));
            }
        }
        List<Observation> history = new ArrayList<>(day1);
        history.addAll(day2);

        CreditCurveModel before = CreditCurveModel.fit(RealMarket.DAY2, RealMarket.DAY1, history, curveParams)
                .orElseThrow();
        List<Prediction> predictions = MarkingEngine.backtest(RealMarket.DAY2, curve2, before,
                day2.stream().filter(Observation::institutional).toList(), securities, residuals, markParams);
        assertThat(predictions).hasSizeGreaterThan(30);
        assertThat(predictions).extracting(Prediction::method).contains(Method.CARRIED, Method.MATRIX);
        double mae = predictions.stream().mapToDouble(p -> Math.abs(p.errorBps())).average().orElseThrow();
        assertThat(mae).isLessThan(150.0);
        predictions.stream().filter(p -> p.method() == Method.CARRIED)
                .forEach(p -> assertThat(p.carriedSpreadErrorBps()).isNotNull());

        CreditCurveModel model2 = CreditCurveModel.fit(RealMarket.DAY2, RealMarket.DAY2, history, curveParams)
                .orElseThrow();
        List<SecurityView> universe = history.stream().map(Observation::isin).distinct().map(securities::get).toList();
        Map<String, List<Observation>> today = day2.stream().collect(Collectors.groupingBy(Observation::isin));
        List<Mark> marks = MarkingEngine.mark(RealMarket.DAY2, curve2, model2, universe, today, residuals, history,
                markParams);
        Map<Method, Long> mix = marks.stream().collect(Collectors.groupingBy(Mark::method, Collectors.counting()));
        assertThat(mix).containsKeys(Method.TRADED, Method.CARRIED);
        marks.stream().filter(m -> m.method() == Method.CARRIED).forEach(m -> {
            assertThat(m.evidence().get(0).date()).isEqualTo(RealMarket.DAY1);
            assertThat(m.cleanPrice()).isPositive();
        });
    }

    @Test
    void modelNeedsAMinimumNumberOfObservations() {
        assertThat(CreditCurveModel.fit(RealMarket.DAY2, RealMarket.DAY2, day2.subList(0, 5), curveParams)).isEmpty();
    }
}
