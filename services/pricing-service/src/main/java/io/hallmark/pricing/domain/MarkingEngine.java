package io.hallmark.pricing.domain;

import io.hallmark.bondmath.PricingSchedule;
import io.hallmark.bondmath.RootFinder;
import io.hallmark.bondmath.SpreadMath;
import io.hallmark.bondmath.YieldMath;
import io.hallmark.bondmath.ZeroCurve;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MarkingEngine {
    public enum Method {
        TRADED,
        TRADED_RETAIL,
        CARRIED,
        ISSUER_CURVE,
        MATRIX
    }

    public record Params(int carryWindowDays, double carryHalfLifeDays, double retailBlend, double passThrough) {
        public static Params defaults() {
            return new Params(30, 20.0, 0.5, 0.25);
        }
    }

    public record Evidence(String kind, String isin, LocalDate date, double weight, double spreadBps, Double faceValueLakh) {}

    public record Mark(
            String isin,
            LocalDate valuationDate,
            Method method,
            double cleanPrice,
            double dirtyPrice,
            double accrued,
            double yieldPct,
            double zSpreadBps,
            double gSpreadBps,
            double modifiedDuration,
            double tenorYears,
            String ratingBucket,
            String sectorGroup,
            double modelSpreadBps,
            double confidence,
            List<Evidence> evidence) {}

    public record Residual(
            LocalDate tradeDate,
            double zSpreadBps,
            double modelSpreadBps,
            double yieldPct,
            double faceValueLakh,
            double riskFreeYieldPct) {
        public double residualBps() {
            return zSpreadBps - modelSpreadBps;
        }
    }

    public record Prediction(
            String isin,
            LocalDate tradeDate,
            Method method,
            String ratingBucket,
            String sectorGroup,
            double predictedYieldPct,
            double observedYieldPct,
            double errorBps,
            Double lastYieldErrorBps,
            Double carriedSpreadErrorBps,
            double confidence) {}

    private MarkingEngine() {}

    public static Optional<Observation> observe(
            SecurityView s, LocalDate tradeDate, double cleanPrice, double yieldPct, double faceValueLakh,
            boolean institutional, ZeroCurve curve) {
        Optional<PricingSchedule> schedule = s.bond().at(tradeDate);
        if (schedule.isEmpty()) {
            return Optional.empty();
        }
        try {
            PricingSchedule sch = schedule.get();
            double dirty = cleanPrice + sch.accrued();
            double z = SpreadMath.zSpreadFromDirty(sch, curve, dirty);
            double ownYield = YieldMath.yieldFromDirty(sch, dirty);
            return Optional.of(new Observation(s.isin(), tradeDate, cleanPrice, ownYield, faceValueLakh, institutional,
                    z, sch.yearsToFinal(), s.bucketOn(tradeDate), s.sectorGroup(), s.issuerKey(),
                    curve.parYieldAnnualPct(sch.yearsToFinal())));
        } catch (RootFinder.NoConvergenceException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static List<Mark> mark(
            LocalDate date,
            ZeroCurve curve,
            CreditCurveModel model,
            Collection<SecurityView> universe,
            Map<String, List<Observation>> todaysObservations,
            Map<String, Residual> lastInstitutionalResidual,
            List<Observation> issuerEvidence,
            Params p) {
        List<Mark> marks = new ArrayList<>(universe.size());
        for (SecurityView s : universe) {
            Optional<PricingSchedule> schedule = s.bond().at(date);
            if (schedule.isEmpty()) {
                continue;
            }
            PricingSchedule sch = schedule.get();
            double tenor = sch.yearsToFinal();
            String bucket = s.bucketOn(date);
            double modelSpread = model.modelSpread(bucket, s.sectorGroup(), s.issuerKey(), tenor);
            List<Observation> today = todaysObservations.getOrDefault(s.isin(), List.of());
            Optional<Observation> instToday = today.stream().filter(Observation::institutional).findFirst();
            Optional<Observation> retailToday = today.stream().filter(o -> !o.institutional()).findFirst();
            Residual carried = lastInstitutionalResidual.get(s.isin());
            Optional<CreditCurveModel.Offset> issuer = model.issuerOffset(s.issuerKey());

            Method method;
            double spread;
            double confidence;
            List<Evidence> evidence = new ArrayList<>();
            if (instToday.isPresent()) {
                Observation o = instToday.get();
                method = Method.TRADED;
                spread = o.zSpreadBps();
                confidence = 0.95;
                evidence.add(new Evidence("OWN_TRADE", s.isin(), date, 1.0, o.zSpreadBps(), o.faceValueLakh()));
            } else if (retailToday.isPresent()) {
                Observation o = retailToday.get();
                method = Method.TRADED_RETAIL;
                spread = p.retailBlend() * o.zSpreadBps() + (1 - p.retailBlend()) * modelSpread;
                confidence = 0.70;
                evidence.add(new Evidence("OWN_RETAIL_TRADE", s.isin(), date, p.retailBlend(), o.zSpreadBps(),
                        o.faceValueLakh()));
            } else if (carried != null && !carried.tradeDate().isBefore(date.minusDays(p.carryWindowDays()))) {
                double age = CreditCurveModel.businessDaysBetween(carried.tradeDate(), date);
                double decay = Math.pow(0.5, age / p.carryHalfLifeDays());
                method = Method.CARRIED;
                spread = carriedSpread(sch, curve, carried, modelSpread, decay, p).orElse(modelSpread);
                confidence = Math.max(0.5, 0.9 * decay);
                evidence.add(new Evidence("OWN_TRADE", s.isin(), carried.tradeDate(), decay, carried.zSpreadBps(),
                        carried.faceValueLakh()));
            } else if (issuer.isPresent() && issuer.get().observations() > 0) {
                method = Method.ISSUER_CURVE;
                spread = modelSpread;
                confidence = 0.45 + 0.25 * Math.min(1.0, issuer.get().weight() / 3.0);
                issuerEvidence.stream()
                        .filter(o -> s.issuerKey() != null && s.issuerKey().equals(o.issuerKey()))
                        .sorted(Comparator.comparing(Observation::tradeDate).reversed())
                        .limit(3)
                        .forEach(o -> evidence.add(new Evidence("ISSUER_TRADE", o.isin(), o.tradeDate(), 0,
                                o.zSpreadBps(), o.faceValueLakh())));
            } else {
                method = Method.MATRIX;
                spread = modelSpread;
                CreditCurveModel.BucketCurve curveFit = model.curves().get(bucket);
                confidence = "POOLED".equals(curveFit.source()) ? 0.2 : 0.2 + 0.2 * Math.min(1.0, curveFit.effectiveN() / 30);
            }
            if (method != Method.TRADED) {
                CreditCurveModel.BucketCurve curveFit = model.curves().get(bucket);
                evidence.add(new Evidence("BUCKET_CURVE_" + curveFit.source(), bucket, date, curveFit.effectiveN(),
                        model.spread(bucket, tenor), null));
            }
            if (RatingBuckets.UNRATED.equals(bucket) || "SUB_IG".equals(bucket)) {
                confidence *= 0.8;
            }
            toMark(s, date, sch, curve, method, spread, tenor, bucket, modelSpread, confidence, evidence)
                    .ifPresent(marks::add);
        }
        return marks;
    }

    public static List<Prediction> backtest(
            LocalDate date,
            ZeroCurve curve,
            CreditCurveModel modelBefore,
            Collection<Observation> todaysInstitutional,
            Map<String, SecurityView> securities,
            Map<String, Residual> residualsBefore,
            Params p) {
        List<Prediction> out = new ArrayList<>();
        for (Observation o : todaysInstitutional) {
            SecurityView s = securities.get(o.isin());
            if (s == null) {
                continue;
            }
            Optional<PricingSchedule> schedule = s.bond().at(date);
            if (schedule.isEmpty()) {
                continue;
            }
            PricingSchedule sch = schedule.get();
            double modelSpread = modelBefore.modelSpread(o.bucket(), s.sectorGroup(), s.issuerKey(), sch.yearsToFinal());
            Residual last = residualsBefore.get(o.isin());
            Method method;
            double spread;
            double confidence;
            if (last != null && !last.tradeDate().isBefore(date.minusDays(p.carryWindowDays()))) {
                double decay = Math.pow(0.5, CreditCurveModel.businessDaysBetween(last.tradeDate(), date)
                        / p.carryHalfLifeDays());
                method = Method.CARRIED;
                spread = carriedSpread(sch, curve, last, modelSpread, decay, p).orElse(modelSpread);
                confidence = Math.max(0.5, 0.9 * decay);
            } else if (modelBefore.issuerOffset(s.issuerKey()).isPresent()) {
                method = Method.ISSUER_CURVE;
                spread = modelSpread;
                confidence = 0.45 + 0.25 * Math.min(1.0, modelBefore.issuerOffset(s.issuerKey()).get().weight() / 3.0);
            } else {
                method = Method.MATRIX;
                spread = modelSpread;
                confidence = 0.3;
            }
            Optional<Double> predicted = yieldAt(sch, curve, spread);
            Optional<Double> observed = yieldAt(sch, curve, o.zSpreadBps());
            if (predicted.isEmpty() || observed.isEmpty()) {
                continue;
            }
            Double lastYieldError = last == null ? null : (last.yieldPct() - observed.get()) * 100;
            Double carriedError = last == null
                    ? null
                    : yieldAt(sch, curve, last.zSpreadBps()).map(y -> (y - observed.get()) * 100).orElse(null);
            out.add(new Prediction(o.isin(), date, method, o.bucket(), s.sectorGroup(), predicted.get(), observed.get(),
                    (predicted.get() - observed.get()) * 100, lastYieldError, carriedError, confidence));
        }
        return out;
    }

    static Optional<Double> carriedSpread(
            PricingSchedule s, ZeroCurve curve, Residual last, double modelSpread, double decay, Params p) {
        Optional<Double> modelYield = yieldAt(s, curve, modelSpread);
        if (modelYield.isEmpty()) {
            return Optional.empty();
        }
        double riskFreeMove = curve.parYieldAnnualPct(s.yearsToFinal()) - last.riskFreeYieldPct();
        double carriedYield = last.yieldPct() + p.passThrough() * riskFreeMove;
        double yield = decay * carriedYield + (1 - decay) * modelYield.get();
        try {
            return Optional.of(SpreadMath.zSpreadFromDirty(s, curve, YieldMath.dirtyFromYield(s, yield)));
        } catch (RootFinder.NoConvergenceException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Double> yieldAt(PricingSchedule s, ZeroCurve curve, double zSpreadBps) {
        try {
            return Optional.of(YieldMath.yieldFromDirty(s, SpreadMath.dirtyFromZSpread(s, curve, zSpreadBps)));
        } catch (RootFinder.NoConvergenceException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Mark> toMark(
            SecurityView s, LocalDate date, PricingSchedule sch, ZeroCurve curve, Method method, double spread,
            double tenor, String bucket, double modelSpread, double confidence, List<Evidence> evidence) {
        try {
            double dirty = SpreadMath.dirtyFromZSpread(sch, curve, spread);
            double yield = YieldMath.yieldFromDirty(sch, dirty);
            return Optional.of(new Mark(
                    s.isin(),
                    date,
                    method,
                    dirty - sch.accrued(),
                    dirty,
                    sch.accrued(),
                    yield,
                    spread,
                    SpreadMath.gSpreadBps(sch, curve, yield),
                    YieldMath.modifiedDuration(sch, yield),
                    tenor,
                    bucket,
                    s.sectorGroup(),
                    modelSpread,
                    Math.round(confidence * 1000) / 1000.0,
                    List.copyOf(evidence)));
        } catch (RootFinder.NoConvergenceException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
