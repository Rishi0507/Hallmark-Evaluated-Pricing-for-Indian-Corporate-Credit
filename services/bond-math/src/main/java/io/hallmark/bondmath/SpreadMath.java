package io.hallmark.bondmath;

public final class SpreadMath {
    private static final double TOLERANCE = 1e-10;

    private SpreadMath() {}

    public static double dirtyFromZSpread(PricingSchedule s, ZeroCurve curve, double zSpreadBps) {
        double z = zSpreadBps / 10_000.0;
        double tSettle = DayCount.ACT_365F.yearFraction(curve.asOf(), s.settle());
        double dfSettle = curve.discountFactor(tSettle);
        double pv = 0;
        for (CashFlow cf : s.flows()) {
            double t = DayCount.ACT_365F.yearFraction(curve.asOf(), cf.date());
            double tau = t - tSettle;
            pv += cf.amount() * curve.discountFactor(t) / dfSettle * Math.exp(-z * tau);
        }
        return pv;
    }

    public static double zSpreadFromDirty(PricingSchedule s, ZeroCurve curve, double dirty) {
        if (!(dirty > 0)) {
            throw new IllegalArgumentException("dirty price must be positive: " + dirty);
        }
        return RootFinder.solve(z -> dirtyFromZSpread(s, curve, z) - dirty, -500.0, 3000.0, TOLERANCE);
    }

    public static double gSpreadBps(PricingSchedule s, ZeroCurve curve, double yieldPct) {
        return (yieldPct - curve.parYieldAnnualPct(s.yearsToFinal())) * 100.0;
    }
}
