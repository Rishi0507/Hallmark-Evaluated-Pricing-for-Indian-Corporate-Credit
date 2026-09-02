package io.hallmark.refdata.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenSecurity(
        String isin,
        String description,
        String issuerName,
        String issuerKey,
        String cin,
        String lei,
        String ownership,
        String sectorGroup,
        String industry,
        String instrumentType,
        Double couponRate,
        String couponBasis,
        LocalDate maturityDate,
        Double faceValue,
        String denominationClass,
        LocalDate firstCallDate,
        LocalDate firstPutDate,
        Boolean stepUp,
        boolean pricingEligible,
        String ineligibleReason,
        List<String> scheduleWarnings,
        List<Rating> ratings,
        List<Cashflow> cashflows,
        Map<String, String> fieldSources,
        List<Conflict> conflicts) {
    public record Conflict(
            String field, String chosenSource, String chosenValue, String otherSource, String otherValue) {}
}
