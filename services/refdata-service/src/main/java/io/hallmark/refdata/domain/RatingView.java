package io.hallmark.refdata.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class RatingView {
    static final String[] LONG_TERM = {
        "AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB+", "BBB", "BBB-",
        "BB+", "BB", "BB-", "B+", "B", "B-", "C+", "C", "C-", "D"
    };

    private RatingView() {}

    public static List<Rating> inForce(Collection<Rating> history, LocalDate date) {
        Map<String, Optional<Rating>> latest = history.stream()
                .filter(r -> r.agency() != null && "LONG".equals(r.scale()))
                .filter(r -> r.effectiveDate() != null && !r.effectiveDate().isAfter(date))
                .collect(Collectors.groupingBy(
                        Rating::agency, Collectors.maxBy(Comparator.comparing(Rating::effectiveDate))));
        return latest.values().stream()
                .flatMap(Optional::stream)
                .filter(r -> !r.withdrawn() && r.notch() != null)
                .toList();
    }

    public static Optional<Rating> compositeCurrent(Collection<Rating> history) {
        return history.stream()
                .filter(Rating::isCurrent)
                .filter(Rating::longTerm)
                .max(Comparator.comparingInt(Rating::notch));
    }

    public static int dispersion(Collection<Rating> history) {
        var notches = history.stream()
                .filter(Rating::isCurrent)
                .filter(Rating::longTerm)
                .mapToInt(Rating::notch)
                .summaryStatistics();
        return notches.getCount() < 2 ? 0 : notches.getMax() - notches.getMin();
    }

    public static String symbol(int notch) {
        return notch >= 1 && notch <= LONG_TERM.length ? LONG_TERM[notch - 1] : "NR";
    }

    public static String bucket(Integer notch) {
        if (notch == null) {
            return "UNRATED";
        }
        if (notch <= 7) {
            return LONG_TERM[notch - 1];
        }
        if (notch <= 10) {
            return "BBB";
        }
        return notch < 20 ? "SUB_IG" : "D";
    }
}
