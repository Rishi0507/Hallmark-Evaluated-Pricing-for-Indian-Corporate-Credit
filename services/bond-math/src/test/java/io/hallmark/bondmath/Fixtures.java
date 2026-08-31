package io.hallmark.bondmath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class Fixtures {
    static final LocalDate FBIL_DATE = LocalDate.of(2026, 10, 8);

    static final LocalDate FBIL_SETTLE = LocalDate.of(2026, 10, 9);

    private Fixtures() {}

    record GsecQuote(
            String isin, double coupon, LocalDate maturity, double price, double ytm, String kind, boolean curveInput) {
        boolean fixedCoupon() {
            return "FIXED".equals(kind);
        }
    }

    static List<String[]> rows(String name) {
        var stream = Objects.requireNonNull(Fixtures.class.getResourceAsStream("/fixtures/" + name), name);
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<String[]> out = new ArrayList<>();
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    out.add(line.split(",", -1));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<GsecQuote> fbilGsecQuotes() {
        return rows("fbil_gsec_prices_2026-10-08.csv").stream()
                .map(r -> new GsecQuote(
                        r[0],
                        Double.parseDouble(r[1]),
                        LocalDate.parse(r[2]),
                        Double.parseDouble(r[3]),
                        Double.parseDouble(r[4]),
                        r[5],
                        Boolean.parseBoolean(r[6])))
                .toList();
    }

    static ZeroCurve fbilCurve() {
        List<String[]> rows = rows("fbil_par_curve_2026-10-08.csv");
        double[] tenors = rows.stream().mapToDouble(r -> Double.parseDouble(r[0])).toArray();
        double[] yields = rows.stream().mapToDouble(r -> Double.parseDouble(r[1])).toArray();
        return ZeroCurve.fromSemiAnnualParYields(FBIL_DATE, tenors, yields);
    }

    static List<CashFlow> nsdlSchedule(String isin) {
        return rows("nsdl_cashflows_" + isin + ".csv").stream()
                .map(r -> new CashFlow(LocalDate.parse(r[0]), Double.parseDouble(r[1]), Boolean.parseBoolean(r[2])))
                .toList();
    }

    static List<CashFlow> gsecFlows(double couponPct, LocalDate maturity, LocalDate after) {
        List<CashFlow> flows = new ArrayList<>();
        for (int k = 0; ; k++) {
            LocalDate date = maturity.minusMonths(6L * k);
            if (!date.isAfter(after)) {
                flows.add(CashFlow.interest(date, couponPct / 2));
                break;
            }
            flows.add(CashFlow.interest(date, couponPct / 2));
        }
        flows.add(CashFlow.principal(maturity, 100.0));
        return flows;
    }
}
