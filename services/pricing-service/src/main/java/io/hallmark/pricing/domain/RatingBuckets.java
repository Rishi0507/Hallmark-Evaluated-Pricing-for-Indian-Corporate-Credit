package io.hallmark.pricing.domain;

import java.util.List;

public final class RatingBuckets {
    public static final String UNRATED = "UNRATED";

    public static final List<String> ORDER = List.of("AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB", "SUB_IG");

    private static final int[] REPRESENTATIVE_NOTCH = {1, 2, 3, 4, 5, 6, 7, 9, 13};
    private static final String[] SYMBOLS = {"AAA", "AA+", "AA", "AA-", "A+", "A", "A-"};

    private RatingBuckets() {}

    public static String bucket(Integer notch) {
        if (notch == null) {
            return UNRATED;
        }
        if (notch <= 7) {
            return SYMBOLS[notch - 1];
        }
        if (notch <= 10) {
            return "BBB";
        }
        return "SUB_IG";
    }

    public static int index(String bucket) {
        return ORDER.indexOf(bucket);
    }

    public static double representativeNotch(String bucket) {
        int i = ORDER.indexOf(bucket);
        return i >= 0 ? REPRESENTATIVE_NOTCH[i] : 13;
    }
}
