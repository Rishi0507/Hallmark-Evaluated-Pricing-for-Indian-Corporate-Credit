package io.hallmark.pricing;

import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hallmark.pricing")
public record PricingProperties(
        int universeDays, LocalDate persistMarksFrom, int curveGraceSeconds, int historyDays, long pollMs) {
    public PricingProperties {
        universeDays = universeDays <= 0 ? 180 : universeDays;
        curveGraceSeconds = curveGraceSeconds <= 0 ? 30 : curveGraceSeconds;
        historyDays = historyDays <= 0 ? 75 : historyDays;
        pollMs = pollMs <= 0 ? 2000 : pollMs;
    }

    public boolean persistMarks(LocalDate date) {
        return persistMarksFrom == null || !date.isBefore(persistMarksFrom);
    }
}
