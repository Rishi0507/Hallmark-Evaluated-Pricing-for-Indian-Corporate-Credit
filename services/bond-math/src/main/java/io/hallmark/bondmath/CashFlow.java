package io.hallmark.bondmath;

import java.time.LocalDate;
import java.util.Objects;

public record CashFlow(LocalDate date, double amount, boolean principal) implements Comparable<CashFlow> {
    public CashFlow {
        Objects.requireNonNull(date, "date");
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("amount must be finite and non-negative: " + amount);
        }
    }

    public static CashFlow interest(LocalDate date, double amount) {
        return new CashFlow(date, amount, false);
    }

    public static CashFlow principal(LocalDate date, double amount) {
        return new CashFlow(date, amount, true);
    }

    CashFlow scaled(double factor) {
        return new CashFlow(date, amount * factor, principal);
    }

    @Override
    public int compareTo(CashFlow other) {
        int byDate = date.compareTo(other.date);
        return byDate != 0 ? byDate : Boolean.compare(principal, other.principal);
    }
}
