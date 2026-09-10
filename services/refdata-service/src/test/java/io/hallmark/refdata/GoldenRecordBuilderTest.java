package io.hallmark.refdata;

import static org.assertj.core.api.Assertions.assertThat;

import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.testing.Contracts;
import io.hallmark.refdata.domain.GoldenRecordBuilder;
import io.hallmark.refdata.domain.GoldenSecurity;
import io.hallmark.refdata.domain.Rating;
import io.hallmark.refdata.domain.RatingView;
import io.hallmark.refdata.domain.SectorClassifier;
import io.hallmark.refdata.domain.SourceRecord;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GoldenRecordBuilderTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 15);
    private final EventReader reader = new EventReader(JsonMapper.builder().build());

    private Map<String, Map<String, SourceRecord>> examplesByIsin() {
        Map<String, Map<String, SourceRecord>> out = new HashMap<>();
        for (String json : Contracts.examples("SecurityMasterRecord")) {
            SourceRecord r = reader.payload(reader.envelope(json), SourceRecord.class);
            out.computeIfAbsent(r.isin(), k -> new HashMap<>()).put(r.source(), r);
        }
        return out;
    }

    @Test
    void examplesConformToTheContract() {
        Contracts.examples("SecurityMasterRecord").forEach(json -> Contracts.assertConforms("SecurityMasterRecord", json));
    }

    @Test
    void psuRailwayFinancierMergesNsdlAndNseWithLineage() {
        Map<String, SourceRecord> irfc = examplesByIsin().get("INE053F08403");
        assertThat(irfc).containsKeys(SourceRecord.NSDL, SourceRecord.NSE_NTRP);
        GoldenSecurity g = GoldenRecordBuilder.merge("INE053F08403", irfc, AS_OF);

        assertThat(g.sectorGroup()).isEqualTo(SectorClassifier.PSU_FINANCIAL);
        assertThat(g.issuerKey()).isEqualTo("CIN:U65910DL1986GOI026363");
        assertThat(g.couponRate()).isEqualTo(7.39);
        assertThat(g.maturityDate()).isEqualTo(LocalDate.of(2034, 7, 15));
        assertThat(g.denominationClass()).isEqualTo("INSTITUTIONAL");
        assertThat(g.fieldSources()).containsEntry("couponRate", "NSDL").containsEntry("cashflows", "NSDL");
        assertThat(g.conflicts()).isEmpty();
        assertThat(g.pricingEligible()).isTrue();
        assertThat(RatingView.compositeCurrent(g.ratings()).map(Rating::symbol)).contains("AAA");
    }

    @Test
    void conflictingCouponIsRecordedAndNsdlWins() {
        Map<String, SourceRecord> irfc = new HashMap<>(examplesByIsin().get("INE053F08403"));
        SourceRecord nse = irfc.get(SourceRecord.NSE_NTRP);

        irfc.put(SourceRecord.NSE_NTRP, new SourceRecord(nse.isin(), nse.source(), nse.snapshotDate(),
                nse.description(), nse.issuerName(), null, null, null, null, null, null, null, null, 7.49,
                null, null, null, nse.maturityDate(), null, null, null, null, null, null, null, null, null, null));
        GoldenSecurity g = GoldenRecordBuilder.merge("INE053F08403", irfc, AS_OF);
        assertThat(g.couponRate()).isEqualTo(7.39);
        assertThat(g.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.field()).isEqualTo("couponRate");
            assertThat(c.otherValue()).isEqualTo("7.49");
        });
    }

    @Test
    void nseOnlyRecordIsNotPriceable() {
        Map<String, SourceRecord> adani = examplesByIsin().get("INE423A07484");
        GoldenSecurity g = GoldenRecordBuilder.merge("INE423A07484", Map.of(
                SourceRecord.NSE_NTRP, adani.get(SourceRecord.NSE_NTRP)), AS_OF);
        assertThat(g.sectorGroup()).isEqualTo(SectorClassifier.UNKNOWN);
        assertThat(g.pricingEligible()).isFalse();
        assertThat(g.ineligibleReason()).isEqualTo("NO_SCHEDULE");
        assertThat(g.issuerKey()).isEqualTo("NAME:ADANI ENTERPRISES");
    }

    @Test
    void pointInTimeRatingsIgnoreLaterActions() {
        List<Rating> history = examplesByIsin().get("INE053F08403").get(SourceRecord.NSDL).ratings();
        assertThat(RatingView.inForce(history, LocalDate.of(2020, 1, 1))).isEmpty();
        assertThat(RatingView.inForce(history, AS_OF)).isNotEmpty().allSatisfy(r -> assertThat(r.notch()).isEqualTo(1));
        assertThat(RatingView.dispersion(history)).isZero();
    }

    @Test
    void bucketsAndClassesFollowTheScale() {
        assertThat(RatingView.bucket(null)).isEqualTo("UNRATED");
        assertThat(RatingView.bucket(4)).isEqualTo("AA-");
        assertThat(RatingView.bucket(9)).isEqualTo("BBB");
        assertThat(RatingView.bucket(12)).isEqualTo("SUB_IG");
        assertThat(RatingView.bucket(20)).isEqualTo("D");
        assertThat(RatingView.symbol(5)).isEqualTo("A+");
        assertThat(RatingView.symbol(99)).isEqualTo("NR");
    }

    @Test
    void marketLinkedAndFloatingInstrumentsAreExcludedFromPricing() {
        assertThat(GoldenRecordBuilder.merge("INE093JA76M5", Map.of(), AS_OF).ineligibleReason())
                .isEqualTo("NO_SCHEDULE");
        SourceRecord ns = examplesByIsin().get("INE053F08403").get(SourceRecord.NSDL);
        SourceRecord mld = new SourceRecord(ns.isin(), ns.source(), ns.snapshotDate(),
                "ANAND RATHI GLOBAL FINANCE LIMITED SR SP79 II BR NCD 29DC26 FVRS1LAC", ns.issuerName(), ns.cin(),
                ns.lei(), ns.ownership(), ns.issuerNature(), ns.sector(), ns.industry(), ns.basicIndustry(),
                ns.instrumentType(), null, "Variable - Index Linked", null, null, ns.maturityDate(), null,
                100_000.0, null, null, null, null, null, null, ns.ratings(), ns.cashflows());
        assertThat(GoldenRecordBuilder.merge(ns.isin(), Map.of(SourceRecord.NSDL, mld), AS_OF).ineligibleReason())
                .isEqualTo("MARKET_LINKED");
    }
}
