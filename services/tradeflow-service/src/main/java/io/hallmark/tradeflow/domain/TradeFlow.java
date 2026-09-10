package io.hallmark.tradeflow.domain;

import java.time.LocalDate;

public record TradeFlow(
        String isin,
        LocalDate tradeDate,
        String instrumentClass,
        Double reportedFaceLakh,
        Double reportedWap,
        Double reportedWay,
        double settledFaceLakh,
        double settledMandatoryFaceLakh,
        double failedFaceLakh,
        Double settledWap,
        LocalDate lastSettlementDate) {
    public double settledShare() {
        return reportedFaceLakh == null || reportedFaceLakh <= 0 ? 0 : settledFaceLakh / reportedFaceLakh;
    }

    public Double mandatoryShare() {
        return settledFaceLakh <= 0 ? null : settledMandatoryFaceLakh / settledFaceLakh;
    }
}
