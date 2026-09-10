package io.hallmark.refdata.domain;

import java.util.Locale;

public final class SectorClassifier {
    public static final String PSU_BANK = "PSU_BANK";
    public static final String PRIVATE_BANK = "PRIVATE_BANK";
    public static final String PSU_FINANCIAL = "PSU_FINANCIAL";
    public static final String HFC = "HFC";
    public static final String NBFC = "NBFC";
    public static final String PSU_CORPORATE = "PSU_CORPORATE";
    public static final String CORPORATE = "CORPORATE";
    public static final String UNKNOWN = "UNKNOWN";

    private SectorClassifier() {}

    public static String classify(SourceRecord nsdl) {
        if (nsdl == null) {
            return UNKNOWN;
        }
        boolean psu = "PSU".equals(nsdl.ownership());
        String industry = lower(nsdl.industry());
        String basic = lower(nsdl.basicIndustry());
        String nature = lower(nsdl.issuerNature());
        String macro = lower(nsdl.sector());
        boolean nbfc = basic.contains("nbfc") || basic.contains("non-bank") || nature.contains("nbfc");
        if (!nbfc && (industry.equals("banks") || basic.contains("bank") || nature.equals("bank"))) {
            return psu ? PSU_BANK : PRIVATE_BANK;
        }
        if (basic.contains("housing finance") || nature.contains("hfc") || nature.contains("housing")) {
            return psu ? PSU_FINANCIAL : HFC;
        }
        if (psu && (macro.contains("financial") || industry.contains("finance"))) {
            return PSU_FINANCIAL;
        }
        if (nbfc || industry.equals("finance")) {
            return NBFC;
        }
        if (psu) {
            return PSU_CORPORATE;
        }
        if (industry.isEmpty() && basic.isEmpty() && macro.isEmpty()) {
            return UNKNOWN;
        }
        return CORPORATE;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
