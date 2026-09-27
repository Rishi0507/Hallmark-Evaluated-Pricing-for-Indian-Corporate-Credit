package io.hallmark.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.hallmark.platform.events.EventReader;
import io.hallmark.platform.testing.Replay;
import io.hallmark.recon.domain.Inputs.ReportedTrade;
import io.hallmark.recon.domain.Inputs.SourceRecord;
import io.hallmark.recon.domain.Outcome;
import io.hallmark.recon.domain.Outcome.Result;
import io.hallmark.recon.domain.RefdataRecon;
import io.hallmark.recon.domain.TradeSettlementRecon;
import io.hallmark.recon.domain.TradeSettlementRecon.Activity;
import io.hallmark.recon.domain.YieldRecon;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class ReconRulesTest {
    static final EventReader READER = new EventReader(JsonMapper.builder().build());
    static final LocalDate TRADE = LocalDate.of(2026, 10, 13);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "matched,                         3800,  3800, 0,   100.10, 100.12, 2026-10-14, MATCHED,",
        "matched but price differs,       3800,  3800, 0,   100.10, 101.00, 2026-10-14, BREAK,   PRICE_MISMATCH",
        "shortfall with window open,      3800,  2800, 0,   56.51,  56.51,  2026-10-14, PENDING,",
        "shortfall after window,          3800,  2800, 0,   56.51,  56.51,  2026-10-22, BREAK,   PARTIALLY_SETTLED",
        "nothing settled after window,    65000, 0,    0,   97.80,  ,       2026-10-22, BREAK,   NOT_SETTLED",
        "fail explains the gap,           12,    9,    3,   97.30,  97.30,  2026-10-14, BREAK,   SETTLEMENT_FAILED",
        "more settled than reported,      500,   1000, 0,   99.99,  99.99,  2026-10-14, BREAK,   SETTLED_EXCEEDS_REPORTED",
    })
    void classifiesReportedAgainstSettled(String name, double reported, double settled, double failed,
            Double reportedWap, Double settledWap, LocalDate asOf, Result result, String breakType) {
        Activity a = new Activity("INE020B08FJ3", TRADE, "BOND", reported, reportedWap, settled, settled * 0.6, failed,
                settledWap, asOf);
        Outcome o = TradeSettlementRecon.reconcile(a, asOf);
        assertThat(o.result()).isEqualTo(result);
        assertThat(o.breakType()).isEqualTo(breakType);
        assertThat(o.breakId()).isEqualTo("TRADE_VS_SETTLEMENT:INE020B08FJ3:2026-10-13");
    }

    @Test
    void settlementWithoutAReportIsABreakOnceTheWindowCloses() {
        Activity a = new Activity("INE020B08FJ3", TRADE, "BOND", null, null, 50, 50, 0, 99.0, TRADE);
        assertThat(TradeSettlementRecon.reconcile(a, TRADE).result()).isEqualTo(Result.PENDING);
        assertThat(TradeSettlementRecon.reconcile(a, TRADE.plusDays(8)).breakType()).isEqualTo("SETTLED_NOT_REPORTED");
        assertThat(Outcome.severityForFace(1000)).isEqualTo("HIGH");
        assertThat(Outcome.severityForFace(150)).isEqualTo("MEDIUM");
        assertThat(Outcome.severityForFace(2)).isEqualTo("LOW");
    }

    static Map<String, Map<String, SourceRecord>> sources() {
        Map<String, Map<String, SourceRecord>> out = new HashMap<>();
        for (String json : Replay.events("2026-10-14", "refdata.source.v1")) {
            SourceRecord r = READER.payload(READER.envelope(json), SourceRecord.class);
            out.computeIfAbsent(r.isin(), k -> new HashMap<>()).put(r.source(), r);
        }
        return out;
    }

    @Test
    void recomputedYieldsMostlyMatchAndDifferencesAreDiagnosed() {
        Map<String, Map<String, SourceRecord>> sources = sources();
        Map<Result, Integer> counts = new EnumMap<>(Result.class);
        List<Outcome> exceptions = new ArrayList<>();
        for (String day : List.of("2026-10-13", "2026-10-14")) {
            for (String json : Replay.events(day, "trades.reported.v1")) {
                ReportedTrade t = READER.payload(READER.envelope(json), ReportedTrade.class);
                SourceRecord nsdl = sources.getOrDefault(t.isin(), Map.of()).get("NSDL");
                Optional<Outcome> o = YieldRecon.reconcile(t, nsdl);
                o.ifPresent(x -> {
                    counts.merge(x.result(), 1, Integer::sum);
                    if (x.exception()) {
                        exceptions.add(x);
                    }
                });
            }
        }
        int evaluated = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(evaluated).isGreaterThan(200);
        assertThat(counts.getOrDefault(Result.MATCHED, 0)).isGreaterThan((int) (0.80 * evaluated));
        long unexplained = exceptions.stream().filter(x -> "YIELD_MISMATCH".equals(x.breakType())).count();
        System.out.printf("yield recon: %s, unexplained %d%n", counts, unexplained);
        assertThat(unexplained).isLessThan((long) (0.15 * evaluated));
        exceptions.forEach(x -> {
            assertThat(x.details()).containsKeys("reportedYieldPct", "recomputedYieldPct", "differenceBps");
            assertThat(x.breakType()).isIn("YIELD_MISMATCH", "QUOTED_TO_CALL", "QUOTED_TO_PUT",
                    "SETTLEMENT_DATE_CONVENTION", "PRICE_ON_ORIGINAL_FACE", "SCHEDULE_INCOMPLETE", "SCHEDULE_DEFECT");
        });
    }

    @Test
    void depositoryAndExchangeTermsMostlyAgree() {
        int compared = 0;
        int breaks = 0;
        for (Map<String, SourceRecord> bySource : sources().values()) {
            Optional<Outcome> o = RefdataRecon.reconcile(bySource.get("NSDL"), bySource.get("NSE_NTRP"), TRADE);
            if (o.isPresent()) {
                compared++;
                breaks += o.get().result() == Result.BREAK ? 1 : 0;
            }
        }
        assertThat(compared).isGreaterThan(20);
        assertThat(breaks).isLessThan(compared / 4 + 1);

        SourceRecord nsdl = sources().get("INE053F08403").get("NSDL");
        SourceRecord nse = sources().get("INE053F08403").get("NSE_NTRP");
        assertThat(RefdataRecon.reconcile(nsdl, nse, TRADE).orElseThrow().result()).isEqualTo(Result.MATCHED);

        SourceRecord shifted = new SourceRecord(nse.isin(), nse.source(), nse.snapshotDate(), nse.description(),
                nse.couponBasis(), nse.couponRate(), nse.maturityDate().plusYears(1), nse.faceValue(), null, null, null,
                null);
        assertThat(RefdataRecon.reconcile(nsdl, shifted, TRADE).orElseThrow().breakType()).isEqualTo("MATURITY_MISMATCH");
    }
}
