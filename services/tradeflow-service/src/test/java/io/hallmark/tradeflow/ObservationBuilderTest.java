package io.hallmark.tradeflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.hallmark.platform.testing.Replay;
import io.hallmark.tradeflow.domain.Inputs.ReportedTrade;
import io.hallmark.tradeflow.domain.Inputs.SecurityRef;
import io.hallmark.tradeflow.domain.ObservationBuilder;
import io.hallmark.tradeflow.domain.ObservationBuilder.TradeObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObservationBuilderTest {
    private final Map<String, SecurityRef> securities = RealDay.securities();

    private List<ReportedTrade> trades() {
        return Replay.events("trades.reported.v1").stream()
                .map(json -> RealDay.READER.payload(RealDay.READER.envelope(json), ReportedTrade.class))
                .toList();
    }

    @Test
    void recomputedYieldsMatchNseForMostPriceableBonds() {
        List<TradeObservation> withSchedule = new ArrayList<>();
        for (ReportedTrade t : trades()) {
            SecurityRef s = securities.get(t.isin());
            if (s == null || !s.pricingEligible()) {
                continue;
            }
            ObservationBuilder.build(t, Optional.of(s), null).ifPresent(withSchedule::add);
        }
        assertThat(withSchedule).hasSizeGreaterThan(50);
        long matching = withSchedule.stream().filter(TradeObservation::clean).count();

        assertThat((double) matching / withSchedule.size()).isGreaterThan(0.85);
        withSchedule.stream().filter(TradeObservation::clean).forEach(o ->
                assertThat(Math.abs(o.recomputedYieldPct() - o.yieldPct()) * 100).isLessThanOrEqualTo(25.0));
    }

    @Test
    void flagsMissingReferenceDataAndSkipsNonBonds() {
        int cp = 0;
        for (ReportedTrade t : trades()) {
            Optional<TradeObservation> o = ObservationBuilder.build(t, Optional.empty(), null);
            if (!"BOND".equals(t.instrumentClass())) {
                assertThat(o).isEmpty();
                cp++;
            } else if (o.isPresent()) {
                assertThat(o.get().flags()).containsExactly(ObservationBuilder.NO_REFDATA);
                assertThat(o.get().sizeClass())
                        .isEqualTo(t.totalTradeValueLakh() >= ObservationBuilder.INSTITUTIONAL_MIN_LAKH
                                ? "INSTITUTIONAL"
                                : "RETAIL");
            }
        }
        assertThat(cp).as("CP, CD and SDL rows on the day").isPositive();
    }

    @Test
    void ineligibleSecurityIsFlagged() {
        ReportedTrade t = trades().stream().filter(x -> "BOND".equals(x.instrumentClass()) && x.wap() != null)
                .findFirst()
                .orElseThrow();
        SecurityRef ineligible = new SecurityRef(t.isin(), null, null, null, null, null, false, List.of());
        assertThat(ObservationBuilder.build(t, Optional.of(ineligible), 0.5).orElseThrow().flags())
                .containsExactly(ObservationBuilder.NOT_PRICEABLE);
    }
}
