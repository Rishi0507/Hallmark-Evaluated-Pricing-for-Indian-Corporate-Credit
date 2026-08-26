package io.hallmark.bondmath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class YieldAndSpreadTest {
    private static final LocalDate SETTLE = LocalDate.of(2026, 10, 8);
    private final ZeroCurve curve = Fixtures.fbilCurve();

    private static PricingSchedule annualBond(double coupon, int years) {
        List<CashFlow> flows = new java.util.ArrayList<>();
        flows.add(CashFlow.interest(SETTLE, coupon));
        for (int k = 1; k <= years; k++) {
            flows.add(CashFlow.interest(SETTLE.plusYears(k), coupon));
        }
        flows.add(CashFlow.principal(SETTLE.plusYears(years), 100));
        return Bond.of("A", flows, null, null).at(SETTLE).orElseThrow();
    }

    @ParameterizedTest
    @CsvSource({"7.5, 3", "9.0, 5", "11.25, 2", "6.8, 10"})
    void annualBondPriceIsTheActual365DiscountedSum(double coupon, int years) {
        PricingSchedule s = annualBond(coupon, years);
        double expected = 0;
        for (int k = 1; k <= years; k++) {
            double t = java.time.temporal.ChronoUnit.DAYS.between(SETTLE, SETTLE.plusYears(k)) / 365.0;
            expected += (coupon + (k == years ? 100 : 0)) * Math.pow(1 + coupon / 100, -t);
        }
        assertThat(YieldMath.cleanFromYield(s, coupon)).isCloseTo(expected, within(1e-9));

        assertThat(YieldMath.yieldFromClean(s, 100.0)).isCloseTo(coupon, within(0.02));
    }

    @Test
    void zeroCouponYieldHasClosedForm() {
        LocalDate maturity = SETTLE.plusDays(3 * 365);
        PricingSchedule s = Bond.of("Z", List.of(CashFlow.principal(maturity, 100)), null, null)
                .at(SETTLE)
                .orElseThrow();
        double price = 100 / Math.pow(1.08, 3);
        assertThat(YieldMath.yieldFromDirty(s, price)).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void priceYieldRoundTripHoldsAcrossTheYieldRange() {
        PricingSchedule s = annualBond(9.0, 7);
        for (double y = 1; y <= 35; y += 0.5) {
            double clean = YieldMath.cleanFromYield(s, y);
            assertThat(YieldMath.yieldFromClean(s, clean)).isCloseTo(y, within(1e-8));
        }
    }

    @Test
    void modifiedDurationMatchesFiniteDifference() {
        PricingSchedule s = annualBond(8.0, 10);
        double y = 8.3;
        double h = 0.0001;
        double up = YieldMath.dirtyFromYield(s, y + h);
        double down = YieldMath.dirtyFromYield(s, y - h);
        double numeric = -(up - down) / (2 * h / 100) / YieldMath.dirtyFromYield(s, y);
        assertThat(YieldMath.modifiedDuration(s, y)).isCloseTo(numeric, within(1e-4));
    }

    @Test
    void zSpreadRoundTrips() {
        PricingSchedule s = annualBond(9.5, 6);
        for (double z : new double[] {-20, 0, 35, 150, 480, 1200}) {
            double dirty = SpreadMath.dirtyFromZSpread(s, curve, z);
            assertThat(SpreadMath.zSpreadFromDirty(s, curve, dirty)).isCloseTo(z, within(1e-6));
        }
    }

    @Test
    void wideningSpreadLowersPrice() {
        PricingSchedule s = annualBond(9.5, 6);
        assertThat(SpreadMath.dirtyFromZSpread(s, curve, 200)).isLessThan(SpreadMath.dirtyFromZSpread(s, curve, 100));
    }

    @Test
    void gSpreadIsYieldMinusAnnualisedParYield() {
        PricingSchedule s = annualBond(9.0, 5);
        double gs = SpreadMath.gSpreadBps(s, curve, 9.0);
        assertThat(gs).isCloseTo((9.0 - curve.parYieldAnnualPct(s.yearsToFinal())) * 100, within(1e-9));
    }

    @Test
    void rejectsNonPositivePrices() {
        PricingSchedule s = annualBond(9.0, 5);
        assertThatThrownBy(() -> YieldMath.yieldFromDirty(s, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpreadMath.zSpreadFromDirty(s, curve, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootFinderExpandsBracketAndReportsFailure() {
        assertThat(RootFinder.solve(x -> x * x * x - 1000, 0, 1, 1e-12)).isCloseTo(10.0, within(1e-9));
        assertThat(RootFinder.solve(x -> x - 3, 3, 5, 1e-12)).isEqualTo(3.0);
        assertThatThrownBy(() -> RootFinder.solve(x -> x * x + 1, -1, 1, 1e-12))
                .isInstanceOf(RootFinder.NoConvergenceException.class);
        assertThatThrownBy(() -> RootFinder.solve(x -> Math.log(x), -2, -1, 1e-12))
                .isInstanceOf(RootFinder.NoConvergenceException.class);
    }
}
