package io.hallmark.pricing.domain;

import java.time.LocalDate;

public record Observation(
        String isin,
        LocalDate tradeDate,
        double cleanPrice,
        double yieldPct,
        double faceValueLakh,
        boolean institutional,
        double zSpreadBps,
        double tenorYears,
        String bucket,
        String sectorGroup,
        String issuerKey,
        double riskFreeYieldPct) {}
