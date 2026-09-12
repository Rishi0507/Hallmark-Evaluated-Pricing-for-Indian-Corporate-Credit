package io.hallmark.pricing.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CreditCurveModel {
    public record Params(
            int curveWindowBusinessDays,
            double curveHalfLifeDays,
            int issuerWindowCalendarDays,
            double issuerHalfLifeDays,
            double retailWeight,
            double minBucketWeight,
            double minLogTenorRange,
            double sectorShrinkage,
            double issuerShrinkage,
            double maxAbsSpreadBps,
            double minTenorYears) {
        public static Params defaults() {
            return new Params(10, 3.0, 60, 15.0, 0.25, 6.0, 0.35, 5.0, 1.0, 3000.0, 0.1);
        }
    }

    public record BucketCurve(String bucket, double level, double slope, double scaleBps, double effectiveN, String source) {
        public double rawSpread(double tenorYears) {
            return level + slope * Math.log1p(tenorYears);
        }
    }

    public record Offset(double bps, double weight, int observations) {}

    private final LocalDate asOf;
    private final Map<String, BucketCurve> curves;
    private final Map<String, Offset> sectorOffsets;
    private final Map<String, Offset> issuerOffsets;
    private final RobustRegression.Fit pooled;

    private CreditCurveModel(
            LocalDate asOf,
            Map<String, BucketCurve> curves,
            Map<String, Offset> sectorOffsets,
            Map<String, Offset> issuerOffsets,
            RobustRegression.Fit pooled) {
        this.asOf = asOf;
        this.curves = curves;
        this.sectorOffsets = sectorOffsets;
        this.issuerOffsets = issuerOffsets;
        this.pooled = pooled;
    }

    public static Optional<CreditCurveModel> fit(
            LocalDate asOf, LocalDate lastObservationDate, List<Observation> history, Params p) {
        LocalDate curveFrom = minusBusinessDays(lastObservationDate, p.curveWindowBusinessDays() - 1);
        LocalDate issuerFrom = lastObservationDate.minusDays(p.issuerWindowCalendarDays());

        List<Observation> curveObs = new ArrayList<>();
        List<Double> curveW = new ArrayList<>();
        List<Observation> issuerObs = new ArrayList<>();
        List<Double> issuerW = new ArrayList<>();
        for (Observation o : history) {
            if (o.tradeDate().isAfter(lastObservationDate) || !usable(o, p)) {
                continue;
            }
            double size = o.institutional() ? 1.0 : p.retailWeight();
            double age = businessDaysBetween(o.tradeDate(), lastObservationDate);
            if (!o.tradeDate().isBefore(curveFrom)) {
                curveObs.add(o);
                curveW.add(size * Math.pow(0.5, age / p.curveHalfLifeDays()));
            }
            if (!o.tradeDate().isBefore(issuerFrom) && o.issuerKey() != null) {
                issuerObs.add(o);
                issuerW.add(size * Math.pow(0.5, age / p.issuerHalfLifeDays()));
            }
        }
        if (curveObs.size() < 10) {
            return Optional.empty();
        }

        double[][] x = new double[curveObs.size()][];
        double[] y = new double[curveObs.size()];
        double[] w = new double[curveObs.size()];
        for (int i = 0; i < curveObs.size(); i++) {
            Observation o = curveObs.get(i);
            x[i] = new double[] {1, Math.log1p(o.tenorYears()), RatingBuckets.representativeNotch(o.bucket())};
            y[i] = o.zSpreadBps();
            w[i] = curveW.get(i);
        }
        RobustRegression.Fit pooled = RobustRegression.fit(x, y, w);

        Map<String, BucketCurve> curves = new LinkedHashMap<>();
        List<String> buckets = new ArrayList<>(RatingBuckets.ORDER);
        buckets.add(RatingBuckets.UNRATED);
        for (String bucket : buckets) {
            curves.put(bucket, fitBucket(bucket, curveObs, curveW, pooled, p));
        }

        CreditCurveModel base = new CreditCurveModel(asOf, curves, Map.of(), Map.of(), pooled);
        Map<String, Offset> sectors = offsets(curveObs, curveW, base, p.sectorShrinkage(), true);
        CreditCurveModel withSectors = new CreditCurveModel(asOf, curves, sectors, Map.of(), pooled);
        Map<String, Offset> issuers = offsets(issuerObs, issuerW, withSectors, p.issuerShrinkage(), false);
        return Optional.of(new CreditCurveModel(asOf, curves, sectors, issuers, pooled));
    }

    private static BucketCurve fitBucket(
            String bucket, List<Observation> obs, List<Double> weights, RobustRegression.Fit pooled, Params p) {
        List<double[]> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        double minLog = Double.MAX_VALUE;
        double maxLog = -Double.MAX_VALUE;
        double weight = 0;
        for (int i = 0; i < obs.size(); i++) {
            Observation o = obs.get(i);
            if (!bucket.equals(o.bucket())) {
                continue;
            }
            double lt = Math.log1p(o.tenorYears());
            xs.add(new double[] {1, lt});
            ys.add(o.zSpreadBps());
            ws.add(weights.get(i));
            minLog = Math.min(minLog, lt);
            maxLog = Math.max(maxLog, lt);
            weight += weights.get(i);
        }
        double notch = RatingBuckets.representativeNotch(bucket);
        double pooledLevel = pooled.coefficients()[0] + pooled.coefficients()[2] * notch;
        double pooledSlope = pooled.coefficients()[1];
        if (weight < p.minBucketWeight() || xs.size() < 3) {
            return new BucketCurve(bucket, pooledLevel, pooledSlope, pooled.scale(), weight, "POOLED");
        }
        double[] y = ys.stream().mapToDouble(Double::doubleValue).toArray();
        double[] w = ws.stream().mapToDouble(Double::doubleValue).toArray();
        if (maxLog - minLog < p.minLogTenorRange()) {
            double[][] x = new double[y.length][];
            double[] adjusted = new double[y.length];
            for (int i = 0; i < y.length; i++) {
                x[i] = new double[] {1};
                adjusted[i] = y[i] - pooledSlope * xs.get(i)[1];
            }
            RobustRegression.Fit fit = RobustRegression.fit(x, adjusted, w);
            return new BucketCurve(bucket, fit.coefficients()[0], pooledSlope, fit.scale(), fit.effectiveN(), "LEVEL");
        }
        RobustRegression.Fit fit = RobustRegression.fit(xs.toArray(new double[0][]), y, w);
        return new BucketCurve(bucket, fit.coefficients()[0], fit.coefficients()[1], fit.scale(), fit.effectiveN(), "FITTED");
    }

    private static Map<String, Offset> offsets(
            List<Observation> obs, List<Double> weights, CreditCurveModel model, double shrinkage, boolean bySector) {
        Map<String, double[]> acc = new HashMap<>();
        for (int i = 0; i < obs.size(); i++) {
            Observation o = obs.get(i);
            String key = bySector ? o.sectorGroup() : o.issuerKey();
            if (key == null) {
                continue;
            }
            double residual = o.zSpreadBps() - model.spread(o.bucket(), o.tenorYears())
                    - (bySector ? 0 : model.sectorOffset(o.sectorGroup()));
            double[] a = acc.computeIfAbsent(key, k -> new double[3]);
            a[0] += weights.get(i) * residual;
            a[1] += weights.get(i);
            a[2] += 1;
        }
        Map<String, Offset> out = new HashMap<>();
        acc.forEach((k, a) -> out.put(k, new Offset(a[0] / (a[1] + shrinkage), a[1], (int) a[2])));
        return out;
    }

    static boolean usable(Observation o, Params p) {
        return Double.isFinite(o.zSpreadBps())
                && Math.abs(o.zSpreadBps()) <= p.maxAbsSpreadBps()
                && o.tenorYears() >= p.minTenorYears();
    }

    public double spread(String bucket, double tenorYears) {
        if (RatingBuckets.UNRATED.equals(bucket)) {
            return Math.max(curves.get(bucket).rawSpread(tenorYears), monotoneSpread(RatingBuckets.ORDER.size() - 2,
                    tenorYears));
        }
        int idx = RatingBuckets.index(bucket);
        return monotoneSpread(idx < 0 ? RatingBuckets.ORDER.size() - 1 : idx, tenorYears);
    }

    private double monotoneSpread(int upToIndex, double tenorYears) {
        double running = -Double.MAX_VALUE;
        for (int i = 0; i <= upToIndex; i++) {
            running = Math.max(running, curves.get(RatingBuckets.ORDER.get(i)).rawSpread(tenorYears));
        }
        return running;
    }

    public double sectorOffset(String sector) {
        Offset o = sector == null ? null : sectorOffsets.get(sector);
        return o == null ? 0 : o.bps();
    }

    public Optional<Offset> issuerOffset(String issuerKey) {
        return Optional.ofNullable(issuerKey == null ? null : issuerOffsets.get(issuerKey));
    }

    public double modelSpread(String bucket, String sector, String issuerKey, double tenorYears) {
        return spread(bucket, tenorYears)
                + sectorOffset(sector)
                + issuerOffset(issuerKey).map(Offset::bps).orElse(0.0);
    }

    public LocalDate asOf() {
        return asOf;
    }

    public Map<String, BucketCurve> curves() {
        return curves;
    }

    public Map<String, Offset> sectorOffsets() {
        return sectorOffsets;
    }

    public double pooledScaleBps() {
        return pooled.scale();
    }

    static LocalDate minusBusinessDays(LocalDate date, int days) {
        LocalDate d = date;
        int left = days;
        while (left > 0) {
            d = d.minusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    static int businessDaysBetween(LocalDate from, LocalDate to) {
        int n = 0;
        LocalDate d = from;
        while (d.isBefore(to)) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }
}
