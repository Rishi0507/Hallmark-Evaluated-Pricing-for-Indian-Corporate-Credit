package io.hallmark.bondmath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ZeroCurveTest {
    private final ZeroCurve curve = Fixtures.fbilCurve();

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30, 40, 45})
    void bootstrappedCurveRepricesFbilParBondsAtPar(double tenor) {
        double c = curve.parYieldSemiAnnualPct(tenor);
        int n = (int) Math.round(tenor * 2);
        double pv = 0;
        for (int k = 1; k <= n; k++) {
            pv += c / 2 * curve.discountFactor(0.5 * k);
        }
        pv += 100 * curve.discountFactor(tenor);
        assertThat(pv).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void forwardFloorOnlyTouchesTheRoundedLongEnd() {
        assertThat(curve.flooredKnots()).isBetween(1, 4);
        double c = curve.parYieldSemiAnnualPct(50);
        double pv = 0;
        for (int k = 1; k <= 100; k++) {
            pv += c / 2 * curve.discountFactor(0.5 * k);
        }
        pv += 100 * curve.discountFactor(50);

        assertThat(pv).isCloseTo(100.0, within(0.15));
    }

    @Test
    void discountFactorsArePositiveAndDecreasing() {
        double previous = 1.0;
        for (double t = 0.05; t <= 60; t += 0.05) {
            double df = curve.discountFactor(t);
            assertThat(df).isPositive().isLessThanOrEqualTo(previous + 1e-15);
            previous = df;
        }
        assertThat(curve.discountFactor(0)).isEqualTo(1.0);
        assertThat(curve.discountFactor(Fixtures.FBIL_DATE)).isEqualTo(1.0);
    }

    @Test
    void zeroRatesSitCloseToParYieldsOnAnUpwardSlopingCurve() {
        for (double t = 1; t <= 40; t += 1) {
            assertThat(curve.zeroRatePct(t)).isCloseTo(curve.parYieldSemiAnnualPct(t), within(0.40));
        }
    }

    @Test
    void annualisedParYieldMatchesFbilAnnualisedColumn() {
        for (String[] row : Fixtures.rows("fbil_par_curve_2026-10-08.csv")) {
            double tenor = Double.parseDouble(row[0]);
            double annual = Double.parseDouble(row[2]);

            assertThat(curve.parYieldAnnualPct(tenor)).isCloseTo(annual, within(0.0105));
        }
    }

    @Test
    void interpolatesAndExtrapolatesFlat() {
        double[] x = {1, 2, 4};
        double[] y = {5, 6, 8};
        assertThat(ZeroCurve.interpolate(x, y, 0.5)).isEqualTo(5);
        assertThat(ZeroCurve.interpolate(x, y, 3)).isEqualTo(7);
        assertThat(ZeroCurve.interpolate(x, y, 2)).isEqualTo(6);
        assertThat(ZeroCurve.interpolate(x, y, 9)).isEqualTo(8);
    }

    @Test
    void rejectsMalformedInput() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        assertThatThrownBy(() -> ZeroCurve.fromSemiAnnualParYields(d, new double[] {1}, new double[] {5}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ZeroCurve.fromSemiAnnualParYields(d, new double[] {2, 1}, new double[] {5, 5}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fbilGsecPricesSitOnTheFbilParCurve() {
        List<Double> residuals = new ArrayList<>();
        for (Fixtures.GsecQuote q : Fixtures.fbilGsecQuotes()) {
            double years = DayCount.ACT_365F.yearFraction(Fixtures.FBIL_SETTLE, q.maturity());
            if (years < 1 || !q.fixedCoupon()) {
                continue;
            }
            Bond bond = Bond.of(q.isin(), Fixtures.gsecFlows(q.coupon(), q.maturity(), Fixtures.FBIL_SETTLE), null, null);
            PricingSchedule s = bond.at(Fixtures.FBIL_SETTLE).orElseThrow();
            double dirty = q.price() + GovernmentBondMath.accrued(q.coupon(), q.maturity(), Fixtures.FBIL_SETTLE);
            residuals.add(Math.abs(SpreadMath.zSpreadFromDirty(s, curve, dirty)));
        }
        residuals.sort(null);
        double median = residuals.get(residuals.size() / 2);
        assertThat(residuals).hasSizeGreaterThan(80);
        assertThat(median).isLessThan(8.0);
        assertThat(residuals.get((int) (residuals.size() * 0.95))).isLessThan(30.0);
    }
}
