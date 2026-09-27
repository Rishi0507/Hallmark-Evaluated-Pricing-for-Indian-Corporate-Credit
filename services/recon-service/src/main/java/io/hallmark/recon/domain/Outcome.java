package io.hallmark.recon.domain;

import java.time.LocalDate;
import java.util.Map;

public record Outcome(
        ReconType reconType,
        String isin,
        LocalDate businessDate,
        Result result,
        String breakType,
        String severity,
        Double amountLakh,
        Map<String, Object> details) {
    public enum ReconType {
        TRADE_VS_SETTLEMENT,
        YIELD_RECOMPUTE,
        REFDATA_SOURCES
    }

    public enum Result {
        MATCHED,
        EXPLAINED,
        PENDING,
        BREAK
    }

    public String breakId() {
        return reconType + ":" + isin + ":" + businessDate;
    }

    public boolean exception() {
        return result == Result.BREAK || result == Result.EXPLAINED;
    }

    public static String severityForFace(double faceLakh) {
        if (faceLakh >= 1000) {
            return "HIGH";
        }
        return faceLakh >= 100 ? "MEDIUM" : "LOW";
    }
}
