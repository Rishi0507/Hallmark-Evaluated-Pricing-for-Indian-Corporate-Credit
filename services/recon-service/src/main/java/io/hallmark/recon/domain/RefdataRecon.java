package io.hallmark.recon.domain;

import io.hallmark.recon.domain.Inputs.SourceRecord;
import io.hallmark.recon.domain.Outcome.ReconType;
import io.hallmark.recon.domain.Outcome.Result;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class RefdataRecon {
    static final double COUPON_TOLERANCE = 0.005;

    private RefdataRecon() {}

    public static Optional<Outcome> reconcile(SourceRecord nsdl, SourceRecord nse, LocalDate asOf) {
        if (nsdl == null || nse == null) {
            return Optional.empty();
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nsdlDescription", nsdl.description());
        d.put("nseDescription", nse.description());
        d.put("nsdlCoupon", nsdl.couponRate());
        d.put("nseCoupon", nse.couponRate());
        d.put("nsdlMaturity", nsdl.maturityDate());
        d.put("nseMaturity", nse.maturityDate());
        boolean couponDiffers = nsdl.couponRate() != null && nse.couponRate() != null
                && Math.abs(nsdl.couponRate() - nse.couponRate()) > COUPON_TOLERANCE;
        boolean maturityDiffers = nsdl.maturityDate() != null && nse.maturityDate() != null
                && !nsdl.maturityDate().equals(nse.maturityDate());
        Result result = couponDiffers || maturityDiffers ? Result.BREAK : Result.MATCHED;
        String type = null;
        String severity = null;
        if (couponDiffers && maturityDiffers) {
            type = "COUPON_AND_MATURITY_MISMATCH";
            severity = "HIGH";
        } else if (couponDiffers) {
            type = "COUPON_MISMATCH";
            severity = "HIGH";
        } else if (maturityDiffers) {
            type = "MATURITY_MISMATCH";
            severity = "MEDIUM";
        }
        return Optional.of(new Outcome(ReconType.REFDATA_SOURCES, nsdl.isin(), asOf, result, type, severity, null, d));
    }
}
