package io.hallmark.tradeflow.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.hallmark.bondmath.Bond;
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
            String venue,
            String instrumentClass,
            Double lastPrice,
            Double lastYield,
            Double lastTradeValueLakh,
            double totalTradeValueLakh,
            Double wap,
            Double way) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SettlementRecord(
            String isin,
            String description,
            LocalDate tradeDate,
            LocalDate settlementDate,
            String instrumentClass,
            String listing,
            String counterpartyClass,
            String status,
            Long quantity,
            double nominalValueInr,
            Double wap,
            Double way,
            Double faceValueInr,
            String denominationClass) {
        public boolean failed() {
            return "FAILED".equals(status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndOfDay(LocalDate businessDate, Map<String, Integer> counts, boolean curveAvailable) {
        public int expected(String key) {
            return counts == null ? 0 : counts.getOrDefault(key, 0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cashflow(LocalDate paymentDate, double amountPerUnit, boolean principal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecurityRef(
            String isin,
            String issuerKey,
            String sectorGroup,
            String denominationClass,
            Double faceValue,
            LocalDate firstCallDate,
            boolean pricingEligible,
            List<Cashflow> cashflows) {
        public Bond toBond() {
            List<CashFlow> flows = cashflows == null
                    ? List.of()
                    : cashflows.stream()
                            .filter(c -> c.paymentDate() != null && c.amountPerUnit() >= 0)
                            .map(c -> new CashFlow(c.paymentDate(), c.amountPerUnit(), c.principal()))
                            .toList();
            return Bond.of(isin, flows, firstCallDate, faceValue);
        }
    }
}
