package io.hallmark.tradeflow.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class LifecycleRules {
    public static final int SETTLEMENT_WINDOW_DAYS = 7;
    private static final double RELATIVE_TOLERANCE = 0.005;
    private static final double ABSOLUTE_TOLERANCE_LAKH = 1.0;

    private LifecycleRules() {}

    public static double tolerance(double reportedFaceLakh) {
        return Math.max(ABSOLUTE_TOLERANCE_LAKH, RELATIVE_TOLERANCE * reportedFaceLakh);
    }

    public static boolean windowOpen(LocalDate tradeDate, LocalDate asOf) {
        return ChronoUnit.DAYS.between(tradeDate, asOf) < SETTLEMENT_WINDOW_DAYS;
    }

    public static LifecycleState evaluate(TradeFlow flow, LocalDate asOf) {
        double settled = flow.settledFaceLakh();
        double failed = flow.failedFaceLakh();
        if (flow.reportedFaceLakh() == null) {
            return LifecycleState.SETTLED_UNREPORTED;
        }
        double reported = flow.reportedFaceLakh();
        double tol = tolerance(reported);
        boolean open = windowOpen(flow.tradeDate(), asOf);
        if (Math.abs(reported - settled) <= tol) {
            return LifecycleState.SETTLED;
        }
        if (settled > reported + tol) {
            return LifecycleState.OVER_SETTLED;
        }
        double unsettled = reported - settled;
        if (failed > 0 && failed >= unsettled - tol) {
            return LifecycleState.FAILED;
        }
        if (open) {
            return settled > 0 || failed > 0 ? LifecycleState.SETTLING : LifecycleState.REPORTED;
        }
        return settled > 0 || failed > 0 ? LifecycleState.PARTIALLY_SETTLED : LifecycleState.UNSETTLED;
    }
}
