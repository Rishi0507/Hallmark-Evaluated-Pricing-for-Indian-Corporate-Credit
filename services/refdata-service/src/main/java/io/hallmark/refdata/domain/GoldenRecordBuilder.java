package io.hallmark.refdata.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class GoldenRecordBuilder {
    static final List<String> PRECEDENCE = List.of(SourceRecord.NSDL, SourceRecord.NSE_NTRP);
    private static final double COUPON_TOLERANCE = 1e-4;

    private GoldenRecordBuilder() {}

    public static GoldenSecurity merge(String isin, Map<String, SourceRecord> bySource, LocalDate asOf) {
        Map<String, String> lineage = new LinkedHashMap<>();
        List<GoldenSecurity.Conflict> conflicts = new ArrayList<>();
        SourceRecord nsdl = bySource.get(SourceRecord.NSDL);

        String description = pick("description", bySource, SourceRecord::description, lineage);
        String issuerName = pick("issuerName", bySource, SourceRecord::issuerName, lineage);
        String cin = pick("cin", bySource, SourceRecord::cin, lineage);
        String lei = pick("lei", bySource, SourceRecord::lei, lineage);
        Double coupon = pick("couponRate", bySource, SourceRecord::couponRate, lineage);
        LocalDate maturity = pick("maturityDate", bySource, SourceRecord::maturityDate, lineage);
        Double faceValue = nsdl != null ? nsdl.faceValue() : null;
        if (faceValue != null) {
            lineage.put("faceValue", SourceRecord.NSDL);
        }

        compare("couponRate", bySource, SourceRecord::couponRate, conflicts, (a, b) ->
                Math.abs(a - b) <= COUPON_TOLERANCE);
        compare("maturityDate", bySource, SourceRecord::maturityDate, conflicts, Objects::equals);

        List<Rating> ratings = nsdl != null ? nsdl.ratings() : List.of();
        List<Cashflow> cashflows = nsdl != null ? nsdl.cashflows() : List.of();
        if (!ratings.isEmpty()) {
            lineage.put("ratings", SourceRecord.NSDL);
        }
        if (!cashflows.isEmpty()) {
            lineage.put("cashflows", SourceRecord.NSDL);
        }

        String couponBasis = nsdl != null ? nsdl.couponBasis() : null;
        List<String> warnings = nsdl != null ? nsdl.scheduleWarnings() : List.of();
        String ineligible = ineligibleReason(description, couponBasis, cashflows, maturity, asOf);
        if (ineligible == null && warnings.stream().anyMatch(SourceRecord.CRITICAL_SCHEDULE_WARNINGS::contains)) {
            ineligible = "SCHEDULE_DEFECT";
        }
        return new GoldenSecurity(
                isin,
                description,
                issuerName,
                issuerKey(cin, lei, issuerName),
                cin,
                lei,
                nsdl != null ? nsdl.ownership() : null,
                SectorClassifier.classify(nsdl),
                nsdl != null ? nsdl.basicIndustry() : null,
                nsdl != null ? nsdl.instrumentType() : null,
                coupon,
                couponBasis,
                maturity,
                faceValue,
                denominationClass(faceValue),
                nsdl != null ? nsdl.firstCallDate() : null,
                nsdl != null ? nsdl.firstPutDate() : null,
                nsdl != null ? nsdl.stepUp() : null,
                ineligible == null,
                ineligible,
                warnings,
                ratings,
                cashflows,
                lineage,
                conflicts);
    }

    static String issuerKey(String cin, String lei, String name) {
        if (cin != null && cin.length() >= 15) {
            return "CIN:" + cin;
        }
        if (lei != null && lei.length() == 20) {
            return "LEI:" + lei;
        }
        if (name == null) {
            return null;
        }
        String normalised = name.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\b(LIMITED|LTD|PRIVATE|PVT|THE|CO|COMPANY|CORPORATION|CORP)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return "NAME:" + normalised;
    }

    static String denominationClass(Double faceValue) {
        if (faceValue == null) {
            return "UNKNOWN";
        }
        if (faceValue <= 10_000) {
            return "RETAIL";
        }
        return faceValue < 100_000 ? "HNI" : "INSTITUTIONAL";
    }

    static String ineligibleReason(
            String description, String couponBasis, List<Cashflow> cashflows, LocalDate maturity, LocalDate asOf) {
        String basis = couponBasis == null ? "" : couponBasis.toLowerCase(Locale.ROOT);
        String desc = description == null ? "" : " " + description.toUpperCase(Locale.ROOT) + " ";
        if (basis.contains("index") || desc.contains(" BR ") || desc.contains(" MLD ")) {
            return "MARKET_LINKED";
        }
        if (basis.contains("variable") || basis.contains("floating")) {
            return "FLOATING_RATE";
        }
        if (cashflows.isEmpty()) {
            return "NO_SCHEDULE";
        }
        if (maturity != null && maturity.isBefore(asOf)) {
            return "MATURED";
        }
        return null;
    }

    private static <T> T pick(
            String field, Map<String, SourceRecord> bySource, Function<SourceRecord, T> getter, Map<String, String> lineage) {
        for (String source : PRECEDENCE) {
            SourceRecord record = bySource.get(source);
            if (record == null) {
                continue;
            }
            T value = getter.apply(record);
            if (value != null && !(value instanceof String s && s.isBlank())) {
                lineage.put(field, source);
                return value;
            }
        }
        return null;
    }

    private static <T> void compare(
            String field,
            Map<String, SourceRecord> bySource,
            Function<SourceRecord, T> getter,
            List<GoldenSecurity.Conflict> conflicts,
            java.util.function.BiPredicate<T, T> same) {
        SourceRecord primary = bySource.get(PRECEDENCE.get(0));
        SourceRecord secondary = bySource.get(PRECEDENCE.get(1));
        if (primary == null || secondary == null) {
            return;
        }
        T a = getter.apply(primary);
        T b = getter.apply(secondary);
        if (a != null && b != null && !same.test(a, b)) {
            conflicts.add(new GoldenSecurity.Conflict(
                    field, primary.source(), String.valueOf(a), secondary.source(), String.valueOf(b)));
        }
    }
}
