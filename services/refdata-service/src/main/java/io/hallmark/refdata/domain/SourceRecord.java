package io.hallmark.refdata.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SourceRecord(
        String isin,
        String source,
        LocalDate snapshotDate,
        String description,
        String issuerName,
        String cin,
        String lei,
        String ownership,
        String issuerNature,
        String sector,
        String industry,
        String basicIndustry,
        String instrumentType,
        Double couponRate,
        String couponBasis,
        String paymentFrequency,
        String dayCount,
        LocalDate maturityDate,
        LocalDate issueDate,
        Double faceValue,
        String listingStatus,
        Boolean stepUp,
        String stepUpTerms,
        LocalDate firstCallDate,
        LocalDate firstPutDate,
        List<String> scheduleWarnings,
        List<Rating> ratings,
        List<Cashflow> cashflows) {
    public static final String NSDL = "NSDL";
    public static final String NSE_NTRP = "NSE_NTRP";

    public static final java.util.Set<String> CRITICAL_SCHEDULE_WARNINGS =
            java.util.Set.of("MISSING_AMOUNT", "ZERO_INTEREST_AMOUNTS", "COUPON_AMOUNT_INCONSISTENT");

    public SourceRecord {
        scheduleWarnings = scheduleWarnings == null ? List.of() : List.copyOf(scheduleWarnings);
        ratings = ratings == null ? List.of() : List.copyOf(ratings);
        cashflows = cashflows == null ? List.of() : List.copyOf(cashflows);
    }
}
