package io.hallmark.bondmath;

import java.time.LocalDate;
import java.util.List;

public record PricingSchedule(
        LocalDate settle, List<CashFlow> flows, double accrued, LocalDate previousCouponDate, boolean toCall) {
    public PricingSchedule {
        flows = List.copyOf(flows);
        if (flows.isEmpty()) {
            throw new IllegalArgumentException("schedule has no future cash flows");
        }
    }

    public LocalDate finalDate() {
        return flows.get(flows.size() - 1).date();
    }

    public double yearsToFinal() {
        return DayCount.ACT_365F.yearFraction(settle, finalDate());
    }
}
