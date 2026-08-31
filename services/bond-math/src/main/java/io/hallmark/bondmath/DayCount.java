package io.hallmark.bondmath;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public enum DayCount {
    ACT_365F {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return ChronoUnit.DAYS.between(start, end) / 365.0;
        }
    },

    THIRTY_E_360 {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return days30360(start, end) / 360.0;
        }
    };

    public abstract double yearFraction(LocalDate start, LocalDate end);

    static int days30360(LocalDate start, LocalDate end) {
        int d1 = Math.min(start.getDayOfMonth(), 30);
        int d2 = Math.min(end.getDayOfMonth(), 30);
        return 360 * (end.getYear() - start.getYear()) + 30 * (end.getMonthValue() - start.getMonthValue()) + (d2 - d1);
    }
}
