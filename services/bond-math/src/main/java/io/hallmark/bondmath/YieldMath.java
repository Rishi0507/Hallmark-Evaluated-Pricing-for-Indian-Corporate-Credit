package io.hallmark.bondmath;

import java.util.List;

public final class YieldMath {
    private static final double TOLERANCE = 1e-12;

    private YieldMath() {}

    public static double dirtyFromYield(PricingSchedule s, double yieldPct) {
        double y = yieldPct / 100.0;
        double pv = 0;
        for (CashFlow cf : s.flows()) {
            double t = DayCount.ACT_365F.yearFraction(s.settle(), cf.date());
            pv += cf.amount() * Math.pow(1 + y, -t);
        }
        return pv;
    }

    public static double cleanFromYield(PricingSchedule s, double yieldPct) {
        return dirtyFromYield(s, yieldPct) - s.accrued();
    }

    public static double yieldFromDirty(PricingSchedule s, double dirty) {
        if (!(dirty > 0)) {
            throw new IllegalArgumentException("dirty price must be positive: " + dirty);
        }
        return RootFinder.solve(y -> dirtyFromYield(s, y) - dirty, 0.0, 20.0, TOLERANCE);
    }

    public static double yieldFromClean(PricingSchedule s, double clean) {
        return yieldFromDirty(s, clean + s.accrued());
    }

    public static double modifiedDuration(PricingSchedule s, double yieldPct) {
        double y = yieldPct / 100.0;
        double pv = 0;
        double weighted = 0;
        List<CashFlow> flows = s.flows();
        for (CashFlow cf : flows) {
            double t = DayCount.ACT_365F.yearFraction(s.settle(), cf.date());
            double disc = cf.amount() * Math.pow(1 + y, -t);
            pv += disc;
            weighted += t * disc;
        }
        return pv == 0 ? 0 : (weighted / pv) / (1 + y);
    }
}
