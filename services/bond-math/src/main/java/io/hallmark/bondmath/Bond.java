package io.hallmark.bondmath;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Bond {
    private final String id;
    private final List<CashFlow> schedule;
    private final LocalDate firstCallDate;
    private final Double faceValuePerUnit;

    private Bond(String id, List<CashFlow> schedule, LocalDate firstCallDate, Double faceValuePerUnit) {
        this.id = Objects.requireNonNull(id, "id");
        List<CashFlow> sorted = new ArrayList<>(schedule);
        sorted.sort(null);
        this.schedule = List.copyOf(sorted);
        this.firstCallDate = firstCallDate;
        this.faceValuePerUnit = faceValuePerUnit;
    }

    public static Bond of(String id, List<CashFlow> schedule, LocalDate firstCallDate, Double faceValuePerUnit) {
        return new Bond(id, schedule, firstCallDate, faceValuePerUnit);
    }

    public String id() {
        return id;
    }

    public List<CashFlow> schedule() {
        return schedule;
    }

    public Optional<PricingSchedule> at(LocalDate settle) {
        List<CashFlow> future = schedule.stream().filter(cf -> cf.date().isAfter(settle)).toList();
        double futurePrincipal = future.stream().filter(CashFlow::principal).mapToDouble(CashFlow::amount).sum();
        boolean toCall = false;
        if (futurePrincipal <= 0) {
            if (firstCallDate == null || !firstCallDate.isAfter(settle) || faceValuePerUnit == null) {
                return Optional.empty();
            }
            List<CashFlow> toCallFlows = new ArrayList<>(future.stream()
                    .filter(cf -> !cf.principal() && !cf.date().isAfter(firstCallDate))
                    .toList());
            toCallFlows.add(CashFlow.principal(firstCallDate, faceValuePerUnit));
            future = toCallFlows;
            futurePrincipal = faceValuePerUnit;
            toCall = true;
        }
        double factor = 100.0 / futurePrincipal;
        List<CashFlow> per100 = future.stream().map(cf -> cf.scaled(factor)).sorted().toList();

        Optional<CashFlow> nextCoupon = per100.stream().filter(cf -> !cf.principal() && cf.amount() > 0).findFirst();
        double accrued = 0.0;
        LocalDate previousCoupon = null;
        if (nextCoupon.isPresent()) {
            previousCoupon = previousCouponDate(settle, nextCoupon.get().date(), per100);
            long period = ChronoUnit.DAYS.between(previousCoupon, nextCoupon.get().date());
            long elapsed = ChronoUnit.DAYS.between(previousCoupon, settle);
            if (period > 0 && elapsed > 0) {
                accrued = nextCoupon.get().amount() * Math.min(1.0, (double) elapsed / period);
            }
        }
        return Optional.of(new PricingSchedule(settle, per100, accrued, previousCoupon, toCall));
    }

    private LocalDate previousCouponDate(LocalDate settle, LocalDate next, List<CashFlow> futurePer100) {
        Optional<LocalDate> lastPaid = schedule.stream()
                .filter(cf -> !cf.principal() && cf.amount() > 0 && !cf.date().isAfter(settle))
                .map(CashFlow::date)
                .max(LocalDate::compareTo);
        if (lastPaid.isPresent()) {
            return lastPaid.get();
        }

        List<LocalDate> coupons = futurePer100.stream()
                .filter(cf -> !cf.principal() && cf.amount() > 0)
                .map(CashFlow::date)
                .distinct()
                .toList();
        Period period = Period.ofYears(1);
        if (coupons.size() >= 2) {
            long months = Math.max(1, Math.round(ChronoUnit.DAYS.between(coupons.get(0), coupons.get(1)) / 30.4375));
            period = Period.ofMonths((int) months);
        }
        return next.minus(period);
    }
}
