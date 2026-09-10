package io.hallmark.tradeflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.hallmark.tradeflow.domain.LifecycleRules;
import io.hallmark.tradeflow.domain.LifecycleState;
import io.hallmark.tradeflow.domain.TradeFlow;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LifecycleRulesTest {
    private static final LocalDate TRADE = LocalDate.of(2026, 10, 13);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "fully settled same day,                 10000,    10000,   0,      2026-10-13, SETTLED",
        "within 0.5% tolerance,                  73500,    73200,   0,      2026-10-14, SETTLED",
        "partly settled with window open,            3800,     2800,    0,      2026-10-14, SETTLING",
        "partly settled with window closed,          3800,     2800,    0,      2026-10-21, PARTIALLY_SETTLED",
        "nothing yet with window open,               65000,    0,       0,      2026-10-14, REPORTED",
        "nothing with window closed,                 65000,    0,       0,      2026-10-22, UNSETTLED",
        "remainder failed,                       1.2,      0,       1.2,    2026-10-14, FAILED",
        "part settled and remainder failed,      12,       9,       3,      2026-10-14, FAILED",
        "settled more than reported,             500,      1000,    0,      2026-10-14, OVER_SETTLED",
        "small trade under Rs 1 lakh floor,           0.5,      0,       0,      2026-10-14, SETTLED",
    })
    void classifies(String name, double reported, double settled, double failed, LocalDate asOf, LifecycleState expected) {
        TradeFlow flow = new TradeFlow("INE020B08FJ3", TRADE, "BOND", reported, 56.5, 7.1, settled, settled, failed,
                56.5, asOf);
        assertThat(LifecycleRules.evaluate(flow, asOf)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0.5, 1.0", "100, 1.0", "1000, 5.0", "73500, 367.5"})
    void toleranceIsHalfAPercentWithARupeeLakhFloor(double reported, double expected) {
        assertThat(LifecycleRules.tolerance(reported)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"2026-10-13, true", "2026-10-19, true", "2026-10-20, false"})
    void windowIsSevenCalendarDays(LocalDate asOf, boolean open) {
        assertThat(LifecycleRules.windowOpen(TRADE, asOf)).isEqualTo(open);
    }

    @ParameterizedTest
    @CsvSource({"0, 100"})
    void settlementWithoutReportIsFlagged(double reported, double settled) {
        TradeFlow flow = new TradeFlow("INE020B08FJ3", TRADE, "BOND", null, null, null, settled, 0, 0, 99.0, TRADE);
        assertThat(LifecycleRules.evaluate(flow, TRADE)).isEqualTo(LifecycleState.SETTLED_UNREPORTED);
        assertThat(flow.settledShare()).isZero();
        assertThat(flow.mandatoryShare()).isZero();
    }
}
