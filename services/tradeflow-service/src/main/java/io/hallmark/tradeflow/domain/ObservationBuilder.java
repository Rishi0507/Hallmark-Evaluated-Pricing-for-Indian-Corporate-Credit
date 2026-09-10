package io.hallmark.tradeflow.domain;

import io.hallmark.bondmath.PricingSchedule;
import io.hallmark.bondmath.RootFinder;
import io.hallmark.bondmath.YieldMath;
import io.hallmark.tradeflow.domain.Inputs.ReportedTrade;
import io.hallmark.tradeflow.domain.Inputs.SecurityRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ObservationBuilder {
    public static final double INSTITUTIONAL_MIN_LAKH = 25.0;

    public static final double YIELD_TOLERANCE_BPS = 25.0;

    public static final String NO_REFDATA = "NO_REFDATA";
    public static final String NOT_PRICEABLE = "NOT_PRICEABLE";
    public static final String YIELD_MISMATCH = "YIELD_MISMATCH";
    public static final String YIELD_NOT_COMPUTABLE = "YIELD_NOT_COMPUTABLE";

    private ObservationBuilder() {}

    public record TradeObservation(
            String isin,
            LocalDate tradeDate,
            double cleanPrice,
            double yieldPct,
            double faceValueLakh,
            String sizeClass,
            List<String> flags,
            Double recomputedYieldPct,
            Double settledMandatoryShare) {
        public boolean clean() {
            return flags.isEmpty();
        }
    }

    public static Optional<TradeObservation> build(
            ReportedTrade trade, Optional<SecurityRef> security, Double mandatoryShare) {
        if (!"BOND".equals(trade.instrumentClass()) || trade.wap() == null || trade.way() == null
                || !(trade.wap() > 0) || trade.totalTradeValueLakh() <= 0) {
            return Optional.empty();
        }
        List<String> flags = new ArrayList<>();
        Double recomputed = null;
        if (security.isEmpty()) {
            flags.add(NO_REFDATA);
        } else if (!security.get().pricingEligible()) {
            flags.add(NOT_PRICEABLE);
        } else {
            recomputed = recomputeYield(security.get(), trade.tradeDate(), trade.wap()).orElse(null);
            if (recomputed == null) {
                flags.add(YIELD_NOT_COMPUTABLE);
            } else if (Math.abs(recomputed - trade.way()) * 100 > YIELD_TOLERANCE_BPS) {
                flags.add(YIELD_MISMATCH);
            }
        }
        String size = trade.totalTradeValueLakh() >= INSTITUTIONAL_MIN_LAKH ? "INSTITUTIONAL" : "RETAIL";
        return Optional.of(new TradeObservation(
                trade.isin(),
                trade.tradeDate(),
                trade.wap(),
                trade.way(),
                trade.totalTradeValueLakh(),
                size,
                List.copyOf(flags),
                recomputed,
                mandatoryShare));
    }

    public static Optional<Double> recomputeYield(SecurityRef security, LocalDate settle, double cleanPrice) {
        Optional<PricingSchedule> schedule = security.toBond().at(settle);
        if (schedule.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(YieldMath.yieldFromClean(schedule.get(), cleanPrice));
        } catch (RootFinder.NoConvergenceException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
