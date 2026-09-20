package io.hallmark.pricing.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.hallmark.bondmath.Bond;
import io.hallmark.bondmath.CashFlow;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SecurityView(
        String isin,
        String issuerName,
        String issuerKey,
        String sectorGroup,
        String denominationClass,
        Double faceValue,
        Double couponRate,
        LocalDate maturityDate,
        LocalDate firstCallDate,
        boolean pricingEligible,
        List<RatingRecord> ratings,
        List<Flow> cashflows) {
    public SecurityView {
        ratings = ratings == null ? List.of() : List.copyOf(ratings);
        cashflows = cashflows == null ? List.of() : List.copyOf(cashflows);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flow(LocalDate paymentDate, double amountPerUnit, boolean principal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingRecord(
            String agency,
            String symbol,
            String scale,
            Integer notch,
            LocalDate ratingDate,
            LocalDate changeDate,
            boolean withdrawn) {
        LocalDate effectiveDate() {
            return changeDate != null ? changeDate : ratingDate;
        }
    }

    public Bond bond() {
        List<CashFlow> flows = cashflows.stream()
                .filter(f -> f.paymentDate() != null && f.amountPerUnit() >= 0)
                .map(f -> new CashFlow(f.paymentDate(), f.amountPerUnit(), f.principal()))
                .toList();
        return Bond.of(isin, flows, firstCallDate, faceValue);
    }

    public OptionalInt notchOn(LocalDate date) {
        Map<String, Optional<RatingRecord>> latest = ratings.stream()
                .filter(r -> r.agency() != null && "LONG".equals(r.scale()))
                .filter(r -> r.effectiveDate() != null && !r.effectiveDate().isAfter(date))
                .collect(Collectors.groupingBy(
                        RatingRecord::agency, Collectors.maxBy(Comparator.comparing(RatingRecord::effectiveDate))));
        return latest.values().stream()
                .flatMap(Optional::stream)
                .filter(r -> !r.withdrawn() && r.notch() != null)
                .mapToInt(RatingRecord::notch)
                .max();
    }

    public String bucketOn(LocalDate date) {
        OptionalInt notch = notchOn(date);
        return RatingBuckets.bucket(notch.isPresent() ? notch.getAsInt() : null);
    }
}
