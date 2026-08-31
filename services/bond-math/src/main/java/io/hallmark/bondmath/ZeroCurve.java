package io.hallmark.bondmath;

import java.time.LocalDate;
import java.util.Arrays;

public final class ZeroCurve {
    private final LocalDate asOf;
    private final double[] parTenors;
    private final double[] parYields;
    private final double[] knotTimes;
    private final double[] logDf;
    private final int flooredKnots;

    private ZeroCurve(
            LocalDate asOf,
            double[] parTenors,
            double[] parYields,
            double[] knotTimes,
            double[] logDf,
            int flooredKnots) {
        this.asOf = asOf;
        this.parTenors = parTenors;
        this.parYields = parYields;
        this.knotTimes = knotTimes;
        this.logDf = logDf;
        this.flooredKnots = flooredKnots;
    }

    public static ZeroCurve fromSemiAnnualParYields(LocalDate asOf, double[] tenors, double[] parYieldsPct) {
        if (tenors.length != parYieldsPct.length || tenors.length < 2) {
            throw new IllegalArgumentException("need at least two par points of equal length arrays");
        }
        for (int i = 1; i < tenors.length; i++) {
            if (!(tenors[i] > tenors[i - 1])) {
                throw new IllegalArgumentException("tenors must be strictly increasing");
            }
        }
        double[] t = tenors.clone();
        double[] y = parYieldsPct.clone();
        int halfYears = (int) Math.floor(t[t.length - 1] * 2 + 1e-9);
        double[] times = new double[halfYears + 1];
        double[] logs = new double[halfYears + 1];

        double shortTenor = Math.min(0.25, t[0]);
        double shortYield = interpolate(t, y, shortTenor) / 100.0;
        times[0] = shortTenor;
        logs[0] = Math.log(Math.pow(1 + shortYield / 2, -2 * shortTenor));

        double sumDf = 0;
        double previousDf = Math.exp(logs[0]);
        int floored = 0;
        for (int k = 1; k <= halfYears; k++) {
            double tenor = 0.5 * k;
            double c = interpolate(t, y, tenor) / 100.0;
            double df = (1 - c / 2 * sumDf) / (1 + c / 2);
            if (!(df > 0)) {
                throw new IllegalStateException("non-positive discount factor at " + tenor + "y");
            }
            if (df > previousDf) {
                df = previousDf;
                floored++;
            }
            sumDf += df;
            previousDf = df;
            times[k] = tenor;
            logs[k] = Math.log(df);
        }
        return new ZeroCurve(asOf, t, y, times, logs, floored);
    }

    public LocalDate asOf() {
        return asOf;
    }

    public int flooredKnots() {
        return flooredKnots;
    }

    public double discountFactor(double years) {
        if (years <= 0) {
            return 1.0;
        }
        if (years <= knotTimes[0]) {
            return Math.exp(logDf[0] / knotTimes[0] * years);
        }
        int last = knotTimes.length - 1;
        if (years >= knotTimes[last]) {
            return Math.exp(logDf[last] / knotTimes[last] * years);
        }
        int idx = Arrays.binarySearch(knotTimes, years);
        if (idx >= 0) {
            return Math.exp(logDf[idx]);
        }
        int hi = -idx - 1;
        int lo = hi - 1;
        double w = (years - knotTimes[lo]) / (knotTimes[hi] - knotTimes[lo]);
        return Math.exp(logDf[lo] + w * (logDf[hi] - logDf[lo]));
    }

    public double discountFactor(LocalDate date) {
        return discountFactor(DayCount.ACT_365F.yearFraction(asOf, date));
    }

    public double zeroRatePct(double years) {
        double t = Math.max(years, 1e-6);
        return -Math.log(discountFactor(t)) / t * 100.0;
    }

    public double parYieldSemiAnnualPct(double years) {
        return interpolate(parTenors, parYields, years);
    }

    public double parYieldAnnualPct(double years) {
        double s = parYieldSemiAnnualPct(years) / 200.0;
        return (Math.pow(1 + s, 2) - 1) * 100.0;
    }

    static double interpolate(double[] x, double[] y, double at) {
        if (at <= x[0]) {
            return y[0];
        }
        if (at >= x[x.length - 1]) {
            return y[y.length - 1];
        }
        int idx = Arrays.binarySearch(x, at);
        if (idx >= 0) {
            return y[idx];
        }
        int hi = -idx - 1;
        int lo = hi - 1;
        double w = (at - x[lo]) / (x[hi] - x[lo]);
        return y[lo] + w * (y[hi] - y[lo]);
    }
}
