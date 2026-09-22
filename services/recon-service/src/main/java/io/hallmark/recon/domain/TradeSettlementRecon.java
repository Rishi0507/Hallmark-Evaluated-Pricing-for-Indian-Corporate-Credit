package io.hallmark.recon.domain;

import io.hallmark.recon.domain.Outcome.ReconType;
import io.hallmark.recon.domain.Outcome.Result;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TradeSettlementRecon {
    public static final int WINDOW_DAYS = 7;
    public static final double PRICE_TOLERANCE = 0.5;

    private TradeSettlementRecon() {}

    public record Activity(
            String isin,
            LocalDate tradeDate,
            String instrumentClass,
            Double reportedFaceLakh,
            Double reportedWap,
            double settledFaceLakh,
            double settledMandatoryFaceLakh,
            double failedFaceLakh,
            Double settledWap,
            LocalDate lastSettlementDate) {}

    public static double tolerance(double reportedFaceLakh) {
        return Math.max(1.0, 0.005 * reportedFaceLakh);
    }

    public static Outcome reconcile(Activity a, LocalDate asOf) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("instrumentClass", a.instrumentClass());
        d.put("reportedFaceLakh", a.reportedFaceLakh());
        d.put("settledFaceLakh", round(a.settledFaceLakh()));
        d.put("settledMandatoryFaceLakh", round(a.settledMandatoryFaceLakh()));
        d.put("failedFaceLakh", round(a.failedFaceLakh()));
        d.put("reportedWap", a.reportedWap());
        d.put("settledWap", a.settledWap() == null ? null : round(a.settledWap()));
        d.put("lastSettlementDate", a.lastSettlementDate());
        boolean open = ChronoUnit.DAYS.between(a.tradeDate(), asOf) < WINDOW_DAYS;

        if (a.reportedFaceLakh() == null) {
            return outcome(a, open ? Result.PENDING : Result.BREAK, "SETTLED_NOT_REPORTED",
                    a.settledFaceLakh(), d);
        }
        double reported = a.reportedFaceLakh();
        double tol = tolerance(reported);
        double settled = a.settledFaceLakh();
        double failed = a.failedFaceLakh();
        double gap = reported - settled;
        d.put("gapLakh", round(gap));

        if (Math.abs(gap) <= tol) {
            if (a.reportedWap() != null && a.settledWap() != null
                    && Math.abs(a.reportedWap() - a.settledWap()) > PRICE_TOLERANCE) {
                return new Outcome(ReconType.TRADE_VS_SETTLEMENT, a.isin(), a.tradeDate(), Result.BREAK,
                        "PRICE_MISMATCH", "LOW", reported, d);
            }
            return outcome(a, Result.MATCHED, null, reported, d);
        }
        if (settled > reported + tol) {
            return outcome(a, Result.BREAK, "SETTLED_EXCEEDS_REPORTED", settled - reported, d);
        }
        if (failed > 0 && failed >= gap - tol) {
            return outcome(a, Result.BREAK, "SETTLEMENT_FAILED", failed, d);
        }
        if (open) {
            return outcome(a, Result.PENDING, null, gap, d);
        }
        return outcome(a, Result.BREAK, settled > 0 ? "PARTIALLY_SETTLED" : "NOT_SETTLED", gap, d);
    }

    private static Outcome outcome(Activity a, Result r, String type, double amount, Map<String, Object> d) {
        return new Outcome(ReconType.TRADE_VS_SETTLEMENT, a.isin(), a.tradeDate(), r, type,
                type == null ? null : Outcome.severityForFace(amount), round(amount), d);
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
