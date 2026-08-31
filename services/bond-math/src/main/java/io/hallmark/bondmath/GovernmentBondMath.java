package io.hallmark.bondmath;

import java.time.LocalDate;

public final class GovernmentBondMath {
    private static final double TOLERANCE = 1e-12;

    private GovernmentBondMath() {}

    public static double dirtyPrice(double couponPct, LocalDate maturity, LocalDate settle, double yieldPct) {
        if (!maturity.isAfter(settle)) {
            throw new IllegalArgumentException("security has matured");
        }
        LocalDate next = nextCoupon(maturity, settle);
        int remaining = remainingCoupons(maturity, next);
        double f = DayCount.days30360(settle, next) / 180.0;
        double v = 1 / (1 + yieldPct / 200.0);
        double coupon = couponPct / 2.0;
        double pv = 0;
        for (int k = 0; k < remaining; k++) {
            pv += coupon * Math.pow(v, f + k);
        }
        pv += 100.0 * Math.pow(v, f + remaining - 1);
        return pv;
    }

    public static double accrued(double couponPct, LocalDate maturity, LocalDate settle) {
        LocalDate next = nextCoupon(maturity, settle);
        LocalDate previous = next.minusMonths(6);
        return couponPct / 2.0 * DayCount.days30360(previous, settle) / 180.0;
    }

    public static double cleanPrice(double couponPct, LocalDate maturity, LocalDate settle, double yieldPct) {
        return dirtyPrice(couponPct, maturity, settle, yieldPct) - accrued(couponPct, maturity, settle);
    }

    public static double yieldFromClean(double couponPct, LocalDate maturity, LocalDate settle, double clean) {
        double dirty = clean + accrued(couponPct, maturity, settle);
        return RootFinder.solve(y -> dirtyPrice(couponPct, maturity, settle, y) - dirty, 0.0, 20.0, TOLERANCE);
    }

    static LocalDate nextCoupon(LocalDate maturity, LocalDate settle) {
        LocalDate date = maturity;
        int k = 0;
        while (true) {
            LocalDate earlier = maturity.minusMonths(6L * (k + 1));
            if (!earlier.isAfter(settle)) {
                return date;
            }
            date = earlier;
            k++;
        }
    }

    private static int remainingCoupons(LocalDate maturity, LocalDate next) {
        int n = 1;
        while (maturity.minusMonths(6L * n).isAfter(next.minusDays(1))) {
            n++;
        }
        return n;
    }
}
