package io.hallmark.recon.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.hallmark.bondmath.CashFlow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class Inputs {
    private Inputs() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportedTrade(
            String isin,
            LocalDate tradeDate,
            String instrumentClass,
            double totalTradeValueLakh,
            Double wap,
            Double way) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SettlementRecord(
            String isin,
            LocalDate tradeDate,
            LocalDate settlementDate,
            String instrumentClass,
            String counterpartyClass,
            String status,
            double nominalValueInr,
            Double wap) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndOfDay(LocalDate businessDate, Map<String, Integer> counts) {
        public int expected(String key) {
            return counts == null ? 0 : counts.getOrDefault(key, 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flow(LocalDate paymentDate, double amountPerUnit, boolean principal) {
        CashFlow toCashFlow() {
            return new CashFlow(paymentDate, amountPerUnit, principal);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceRecord(
            String isin,
            String source,
            LocalDate snapshotDate,
            String description,
            String couponBasis,
            Double couponRate,
            LocalDate maturityDate,
            Double faceValue,
            LocalDate firstCallDate,
            LocalDate firstPutDate,
            List<String> scheduleWarnings,
            List<Flow> cashflows) {
        static final java.util.Set<String> CRITICAL =
                java.util.Set.of("MISSING_AMOUNT", "ZERO_INTEREST_AMOUNTS", "COUPON_AMOUNT_INCONSISTENT");

        public SourceRecord {
            scheduleWarnings = scheduleWarnings == null ? List.of() : List.copyOf(scheduleWarnings);
            cashflows = cashflows == null ? List.of() : List.copyOf(cashflows);
        }

        public List<String> criticalWarnings() {
            return scheduleWarnings.stream().filter(CRITICAL::contains).toList();
        }

        public boolean fixedCoupon() {
            String b = couponBasis == null ? "" : couponBasis.toLowerCase();
            String d = description == null ? "" : " " + description + " ";
            return (b.contains("fixed") || b.contains("zero")) && !d.contains(" BR ") && !cashflows.isEmpty();
        }
    }
}
