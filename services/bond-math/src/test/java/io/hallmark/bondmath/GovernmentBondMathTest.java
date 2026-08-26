package io.hallmark.bondmath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GovernmentBondMathTest {
    private static final double PRICE_TOLERANCE = 0.0015;

    static Stream<Fixtures.GsecQuote> fbilQuotes() {
        return Fixtures.fbilGsecQuotes().stream().filter(Fixtures.GsecQuote::fixedCoupon);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fbilQuotes")
    void reproducesFbilPriceFromFbilYield(Fixtures.GsecQuote q) {
        double price = GovernmentBondMath.cleanPrice(q.coupon(), q.maturity(), Fixtures.FBIL_SETTLE, q.ytm());
        assertThat(price).as(q.isin()).isCloseTo(q.price(), within(PRICE_TOLERANCE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fbilQuotes")
    void reproducesFbilYieldFromFbilPrice(Fixtures.GsecQuote q) {
        double years = DayCount.ACT_365F.yearFraction(Fixtures.FBIL_SETTLE, q.maturity());
        double ytm = GovernmentBondMath.yieldFromClean(q.coupon(), q.maturity(), Fixtures.FBIL_SETTLE, q.price());

        double tolerance = years < 0.5 ? 0.02 : 0.002;
        assertThat(ytm).as(q.isin()).isCloseTo(q.ytm(), within(tolerance));
    }

    @Test
    void accruedIsZeroOnCouponDateAndHalfCouponJustBeforeNext() {
        LocalDate maturity = LocalDate.of(2034, 6, 15);
        assertThat(GovernmentBondMath.accrued(7.0, maturity, LocalDate.of(2026, 6, 15))).isZero();
        assertThat(GovernmentBondMath.accrued(7.0, maturity, LocalDate.of(2026, 12, 14)))
                .isCloseTo(3.5 * 179 / 180.0, within(1e-12));
    }

    @Test
    void parBondPricesAtParOnCouponDate() {
        LocalDate maturity = LocalDate.of(2036, 4, 1);
        LocalDate settle = LocalDate.of(2026, 10, 1);
        assertThat(GovernmentBondMath.cleanPrice(6.5, maturity, settle, 6.5)).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void rejectsMaturedSecurity() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        assertThatThrownBy(() -> GovernmentBondMath.dirtyPrice(7, d, d, 7)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thirtyEThreeSixtyHandlesMonthEnds() {
        assertThat(DayCount.days30360(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31)))
                .isEqualTo(60);

        assertThat(DayCount.days30360(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 12, 31)))
                .isEqualTo(81);
        assertThat(DayCount.THIRTY_E_360.yearFraction(LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 15)))
                .isEqualTo(1.0);
    }
}
