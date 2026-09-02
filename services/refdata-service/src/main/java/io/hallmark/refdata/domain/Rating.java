package io.hallmark.refdata.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Rating(
        String agency,
        String rawRating,
        String symbol,
        String scale,
        Integer notch,
        String outlook,
        String action,
        LocalDate ratingDate,
        LocalDate changeDate,
        String watch,
        boolean isCurrent,
        boolean creditEnhanced,
        boolean structured,
        boolean provisional,
        boolean withdrawn) {
    public LocalDate effectiveDate() {
        return changeDate != null ? changeDate : ratingDate;
    }

    public boolean longTerm() {
        return "LONG".equals(scale) && notch != null && !withdrawn;
    }
}
