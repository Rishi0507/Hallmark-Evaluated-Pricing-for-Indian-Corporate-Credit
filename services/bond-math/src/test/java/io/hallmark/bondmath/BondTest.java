package io.hallmark.bondmath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BondTest {
    private static final LocalDate SETTLE = LocalDate.of(2026, 10, 15);

    @Test
    void amortisingNcdIsNormalisedPer100Outstanding() {
        Bond bond = Bond.of("INE786X07BN6", Fixtures.nsdlSchedule("INE786X07BN6"), null, 10_000.0);
        PricingSchedule s = bond.at(SETTLE).orElseThrow();
        double principal = s.flows().stream().filter(CashFlow::principal).mapToDouble(CashFlow::amount).sum();
        assertThat(principal).isCloseTo(100.0, within(1e-9));
        assertThat(s.flows().stream().filter(CashFlow::principal).count()).isEqualTo(6);
        assertThat(s.finalDate()).isEqualTo(LocalDate.of(2029, 3, 29));
        assertThat(s.toCall()).isFalse();

        assertThat(s.accrued()).isBetween(0.4, 0.8);
    }

    @Test
    void amortisingYieldIsCloseToCouponAtParPrice() {
        Bond bond = Bond.of("INE786X07BN6", Fixtures.nsdlSchedule("INE786X07BN6"), null, 10_000.0);
        PricingSchedule s = bond.at(SETTLE).orElseThrow();
        double y = YieldMath.yieldFromClean(s, 100.0);

        assertThat(y).isCloseTo(12.68, within(0.15));
    }

    @Test
    void perpetualAt1BondIsPricedToFirstCall() {
        Bond bond = Bond.of(
                "INE476A08282", Fixtures.nsdlSchedule("INE476A08282"), LocalDate.of(2031, 9, 18), 10_000_000.0);
        PricingSchedule s = bond.at(SETTLE).orElseThrow();
        assertThat(s.toCall()).isTrue();
        assertThat(s.finalDate()).isEqualTo(LocalDate.of(2031, 9, 18));
        assertThat(s.flows().get(s.flows().size() - 1).amount()).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void perpetualWithoutCallCannotBePriced() {
        Bond bond = Bond.of("INE476A08282", Fixtures.nsdlSchedule("INE476A08282"), null, null);
        assertThat(bond.at(SETTLE)).isEmpty();
    }

    @Test
    void annualPayerUsesPastCouponForAccrual() {
        Bond bond = Bond.of("INE053F08403", Fixtures.nsdlSchedule("INE053F08403"), null, 100_000.0);
        PricingSchedule s = bond.at(SETTLE).orElseThrow();
        assertThat(s.previousCouponDate()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(s.accrued()).isCloseTo(7.39 * 183 / 365.0, within(0.01));
    }

    @Test
    void maturedBondHasNoSchedule() {
        Bond bond = Bond.of("X", List.of(CashFlow.principal(LocalDate.of(2025, 1, 1), 100)), null, null);
        assertThat(bond.at(SETTLE)).isEmpty();
    }

    @Test
    void zeroCouponBondHasNoAccrual() {
        Bond bond = Bond.of("Z", List.of(CashFlow.principal(LocalDate.of(2034, 11, 3), 100_000)), null, null);
        PricingSchedule s = bond.at(SETTLE).orElseThrow();
        assertThat(s.accrued()).isZero();
        assertThat(s.previousCouponDate()).isNull();
    }

    @Test
    void cashFlowRejectsNegativeAmounts() {
        assertThatThrownBy(() -> CashFlow.interest(SETTLE, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingSchedule(SETTLE, List.of(), 0, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
