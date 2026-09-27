package io.hallmark.recon.domain;

import io.hallmark.bondmath.Bond;
import io.hallmark.bondmath.CashFlow;
import io.hallmark.bondmath.PricingSchedule;
import io.hallmark.bondmath.RootFinder;
import io.hallmark.bondmath.YieldMath;
import io.hallmark.recon.domain.Inputs.ReportedTrade;
import io.hallmark.recon.domain.Inputs.SourceRecord;
import io.hallmark.recon.domain.Outcome.ReconType;
import io.hallmark.recon.domain.Outcome.Result;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class YieldRecon {
    public static final double TOLERANCE_BPS = 25.0;

    private YieldRecon() {}

    public static Optional<Outcome> reconcile(ReportedTrade t, SourceRecord nsdl) {
        if (t.wap() == null || t.way() == null || !(t.wap() > 0) || nsdl == null || !nsdl.fixedCoupon()) {
            return Optional.empty();
        }
        List<CashFlow> flows = nsdl.cashflows().stream().map(Inputs.Flow::toCashFlow).toList();
        Bond bond = Bond.of(t.isin(), flows, nsdl.firstCallDate(), nsdl.faceValue());
        Optional<Double> toMaturity = computeYield(bond, t.tradeDate(), t.wap());
        if (toMaturity.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reportedYieldPct", t.way());
        d.put("reportedPrice", t.wap());
        d.put("recomputedYieldPct", round(toMaturity.get()));
        double diff = (toMaturity.get() - t.way()) * 100;
        d.put("differenceBps", round(diff));
        if (Math.abs(diff) <= TOLERANCE_BPS) {
            return Optional.of(out(t, Result.MATCHED, null, d));
        }

        if (nsdl.firstCallDate() != null && nsdl.firstCallDate().isAfter(t.tradeDate())) {
            Optional<Double> y = computeYield(truncated(t.isin(), flows, nsdl.firstCallDate()), t.tradeDate(), t.wap());
            if (explains(y, t.way(), d, "yieldToCallPct")) {
                return Optional.of(out(t, Result.EXPLAINED, "QUOTED_TO_CALL", d));
            }
        }
        if (nsdl.firstPutDate() != null && nsdl.firstPutDate().isAfter(t.tradeDate())) {
            Optional<Double> y = computeYield(truncated(t.isin(), flows, nsdl.firstPutDate()), t.tradeDate(), t.wap());
            if (explains(y, t.way(), d, "yieldToPutPct")) {
                return Optional.of(out(t, Result.EXPLAINED, "QUOTED_TO_PUT", d));
            }
        }
        if (explains(computeYield(bond, nextBusinessDay(t.tradeDate()), t.wap()), t.way(), d, "yieldNextDaySettlePct")) {
            return Optional.of(out(t, Result.EXPLAINED, "SETTLEMENT_DATE_CONVENTION", d));
        }
        double outstanding = outstandingFraction(flows, nsdl.faceValue(), t.tradeDate());
        if (outstanding < 0.999 && outstanding > 0) {
            d.put("outstandingFraction", round(outstanding));
            if (explains(computeYield(bond, t.tradeDate(), t.wap() / outstanding), t.way(), d, "yieldOnOriginalFacePct")) {
                return Optional.of(out(t, Result.EXPLAINED, "PRICE_ON_ORIGINAL_FACE", d));
            }
        }
        if (!nsdl.criticalWarnings().isEmpty()) {
            d.put("scheduleWarnings", nsdl.criticalWarnings());
            return Optional.of(out(t, Result.BREAK, "SCHEDULE_DEFECT", d));
        }
        Optional<PricingSchedule> schedule = bond.at(t.tradeDate());
        if (schedule.isPresent()) {
            double undiscounted = schedule.get().flows().stream().mapToDouble(CashFlow::amount).sum();
            double dirty = t.wap() + schedule.get().accrued();
            d.put("undiscountedFlowsPer100", round(undiscounted));
            if (undiscounted < dirty && t.way() > 0) {
                return Optional.of(out(t, Result.BREAK, "SCHEDULE_INCOMPLETE", d));
            }
        }
        return Optional.of(out(t, Result.BREAK, "YIELD_MISMATCH", d));
    }

    private static boolean explains(Optional<Double> y, double reported, Map<String, Object> d, String key) {
        if (y.isEmpty()) {
            return false;
        }
        d.put(key, round(y.get()));
        return Math.abs(y.get() - reported) * 100 <= TOLERANCE_BPS;
    }

    private static Outcome out(ReportedTrade t, Result r, String type, Map<String, Object> d) {
        String severity = null;
        if (type != null) {
            severity = r == Result.EXPLAINED ? "LOW" : Outcome.severityForFace(t.totalTradeValueLakh());
        }
        return new Outcome(ReconType.YIELD_RECOMPUTE, t.isin(), t.tradeDate(), r, type, severity,
                t.totalTradeValueLakh(), d);
    }

    static Optional<Double> computeYield(Bond bond, LocalDate settle, double cleanPrice) {
        Optional<PricingSchedule> s = bond.at(settle);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(YieldMath.yieldFromClean(s.get(), cleanPrice));
        } catch (RootFinder.NoConvergenceException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static Bond truncated(String isin, List<CashFlow> flows, LocalDate exerciseDate) {
        List<CashFlow> out = new ArrayList<>();
        double principalAfter = 0;
        for (CashFlow cf : flows) {
            if (cf.date().isAfter(exerciseDate)) {
                if (cf.principal()) {
                    principalAfter += cf.amount();
                }
            } else {
                out.add(cf);
            }
        }
        if (principalAfter > 0) {
            out.add(CashFlow.principal(exerciseDate, principalAfter));
        }
        return Bond.of(isin, out, null, null);
    }

    static double outstandingFraction(List<CashFlow> flows, Double faceValue, LocalDate date) {
        double scheduled = flows.stream().filter(CashFlow::principal).mapToDouble(CashFlow::amount).sum();
        double original = Math.max(scheduled, faceValue == null ? 0 : faceValue);
        double future = flows.stream().filter(c -> c.principal() && c.date().isAfter(date))
                .mapToDouble(CashFlow::amount).sum();
        return original <= 0 ? 1 : future / original;
    }

    static LocalDate nextBusinessDay(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
            n = n.plusDays(1);
        }
        return n;
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
