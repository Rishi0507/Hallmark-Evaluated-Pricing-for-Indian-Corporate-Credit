package io.hallmark.backfill;

import io.hallmark.bondmath.ZeroCurve;
import io.hallmark.platform.events.EventEnvelope;
import io.hallmark.platform.events.EventReader;
import io.hallmark.pricing.domain.CreditCurveModel;
import io.hallmark.pricing.domain.MarkingEngine;
import io.hallmark.pricing.domain.MarkingEngine.Mark;
import io.hallmark.pricing.domain.MarkingEngine.Prediction;
import io.hallmark.pricing.domain.MarkingEngine.Residual;
import io.hallmark.pricing.domain.Observation;
import io.hallmark.pricing.domain.SecurityView;
import io.hallmark.recon.domain.Outcome;
import io.hallmark.recon.domain.RefdataRecon;
import io.hallmark.recon.domain.TradeSettlementRecon;
import io.hallmark.recon.domain.YieldRecon;
import io.hallmark.refdata.domain.GoldenRecordBuilder;
import io.hallmark.refdata.domain.GoldenSecurity;
import io.hallmark.refdata.domain.SourceRecord;
import io.hallmark.tradeflow.domain.Inputs.SecurityRef;
import io.hallmark.tradeflow.domain.LifecycleRules;
import io.hallmark.tradeflow.domain.LifecycleState;
import io.hallmark.tradeflow.domain.ObservationBuilder;
import io.hallmark.tradeflow.domain.ObservationBuilder.TradeObservation;
import io.hallmark.tradeflow.domain.TradeFlow;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Backfill {
    private static final double LAKH = 100_000.0;
    private static final int LOOKBACK_DAYS = 10;
    private static final int HISTORY_DAYS = 75;
    private static final int UNIVERSE_DAYS = 180;
    private static final int EVIDENCE_DAYS = 5;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EventReader reader = new EventReader(mapper);
    private final Path in;
    private final Path out;

    private Backfill(Path in, Path out) {
        this.in = in;
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: Backfill <events-dir> <output-dir>");
            System.exit(2);
        }
        Files.createDirectories(Path.of(args[1]));
        new Backfill(Path.of(args[0]), Path.of(args[1])).run();
    }

    private static final class Agg {
        String instrumentClass;
        Double reportedFace;
        Double reportedWap;
        double settled;
        double settledMandatory;
        double failed;
        double wapNumerator;
        double wapWeight;
        LocalDate lastSettlement;

        Double settledWap() {
            return wapWeight > 0 ? wapNumerator / wapWeight : null;
        }
    }

    private record Key(String isin, LocalDate tradeDate) {}

    private void forEachPayload(String topic, Consumer<JsonNode> action) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(in.resolve(topic + ".jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    EventEnvelope e = reader.envelope(line);
                    action.accept(e.payload());
                }
            }
        }
    }

    private void run() throws IOException {
        long started = System.currentTimeMillis();

        Map<String, Map<String, SourceRecord>> sources = new HashMap<>();
        forEachPayload("refdata.source.v1", p -> {
            SourceRecord r = mapper.treeToValue(p, SourceRecord.class);
            sources.computeIfAbsent(r.isin(), k -> new HashMap<>()).put(r.source(), r);
        });

        TreeSet<LocalDate> days = new TreeSet<>();
        forEachPayload("control.eod.v1", p -> days.add(LocalDate.parse(p.get("businessDate").asString())));
        LocalDate asOf = days.last();

        Map<String, GoldenSecurity> golden = new LinkedHashMap<>();
        sources.forEach((isin, bySource) -> golden.put(isin, GoldenRecordBuilder.merge(isin, bySource, asOf)));
        Map<String, SecurityRef> tradeflowRefs = new HashMap<>();
        Map<String, SecurityView> views = new HashMap<>();
        Map<String, io.hallmark.recon.domain.Inputs.SourceRecord> reconNsdl = new HashMap<>();
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("golden.jsonl"), StandardCharsets.UTF_8)) {
            for (GoldenSecurity g : golden.values()) {
                JsonNode node = mapper.valueToTree(g);
                w.write(mapper.writeValueAsString(node));
                w.newLine();
                tradeflowRefs.put(g.isin(), mapper.treeToValue(node, SecurityRef.class));
                SecurityView view = mapper.treeToValue(node, SecurityView.class);
                if (view.pricingEligible()) {
                    views.put(g.isin(), view);
                }
            }
        }
        List<Outcome> reconOutcomes = new ArrayList<>();
        sources.forEach((isin, bySource) -> {
            SourceRecord nsdl = bySource.get(SourceRecord.NSDL);
            if (nsdl != null) {
                var recon = mapper.treeToValue(mapper.valueToTree(nsdl), io.hallmark.recon.domain.Inputs.SourceRecord.class);
                reconNsdl.put(isin, recon);
                SourceRecord nse = bySource.get(SourceRecord.NSE_NTRP);
                if (nse != null) {
                    RefdataRecon.reconcile(recon, mapper.treeToValue(mapper.valueToTree(nse),
                                    io.hallmark.recon.domain.Inputs.SourceRecord.class), asOf)
                            .ifPresent(reconOutcomes::add);
                }
            }
        });
        log("reference data: %d ISINs, %d eligible for pricing", golden.size(), views.size());

        TreeMap<LocalDate, ZeroCurve> curves = new TreeMap<>();
        forEachPayload("market.curve.v1", p -> {
            LocalDate d = LocalDate.parse(p.get("asOfDate").asString());
            JsonNode points = p.get("points");
            double[] t = new double[points.size()];
            double[] y = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                t[i] = points.get(i).get("tenorYears").asDouble();
                y[i] = points.get(i).get("parYield").asDouble();
            }
            curves.put(d, ZeroCurve.fromSemiAnnualParYields(d, t, y));
        });

        Map<LocalDate, List<io.hallmark.tradeflow.domain.Inputs.ReportedTrade>> reportedByDay = new HashMap<>();
        forEachPayload("trades.reported.v1", p -> {
            var t = mapper.treeToValue(p, io.hallmark.tradeflow.domain.Inputs.ReportedTrade.class);
            reportedByDay.computeIfAbsent(t.tradeDate(), k -> new ArrayList<>()).add(t);
        });
        Map<LocalDate, List<io.hallmark.tradeflow.domain.Inputs.SettlementRecord>> settlementsByDay = new HashMap<>();
        forEachPayload("trades.settlement.v1", p -> {
            var s = mapper.treeToValue(p, io.hallmark.tradeflow.domain.Inputs.SettlementRecord.class);
            settlementsByDay.computeIfAbsent(s.settlementDate(), k -> new ArrayList<>()).add(s);
        });

        Map<Key, Agg> activity = new HashMap<>();
        Map<Key, LifecycleState> lifecycle = new HashMap<>();
        Map<Key, Outcome> settlementOutcomes = new HashMap<>();
        List<Observation> history = new ArrayList<>();
        Map<String, Residual> lastResidual = new HashMap<>();
        Map<String, LocalDate> lastCleanTrade = new HashMap<>();
        CreditCurveModel.Params curveParams = CreditCurveModel.Params.defaults();
        MarkingEngine.Params markParams = MarkingEngine.Params.defaults();

        try (Csv observations = new Csv(out.resolve("observations.csv"), "isin", "trade_date", "clean_price",
                        "yield_pct", "face_lakh", "size_class", "flags", "recomputed_yield_pct",
                        "settled_mandatory_share", "z_spread_bps", "tenor_years", "rating_bucket", "sector_group",
                        "model_spread_bps");
                Csv marks = new Csv(out.resolve("marks.csv"), "valuation_date", "isin", "method", "clean_price",
                        "dirty_price", "accrued", "yield_pct", "z_spread_bps", "g_spread_bps", "modified_duration",
                        "tenor_years", "rating_bucket", "sector_group", "model_spread_bps", "confidence", "evidence");
                Csv backtest = new Csv(out.resolve("backtest.csv"), "trade_date", "isin", "method", "rating_bucket",
                        "sector_group", "predicted_yield_pct", "observed_yield_pct", "error_bps",
                        "last_yield_error_bps", "carried_spread_error_bps", "confidence");
                Csv fits = new Csv(out.resolve("curve_fits.csv"), "valuation_date", "bucket", "level_bps",
                        "slope_bps", "scale_bps", "effective_n", "source");
                Csv sectors = new Csv(out.resolve("sector_offsets.csv"), "valuation_date", "sector_group",
                        "offset_bps", "weight", "observations");
                Csv yields = new Csv(out.resolve("recon_yield.csv"), "trade_date", "isin", "result", "break_type",
                        "severity", "face_lakh", "details")) {
            for (LocalDate day : days) {
                for (var s : settlementsByDay.getOrDefault(day, List.of())) {
                    Agg a = activity.computeIfAbsent(new Key(s.isin(), s.tradeDate()), k -> new Agg());
                    a.instrumentClass = a.instrumentClass == null ? s.instrumentClass() : a.instrumentClass;
                    double face = s.nominalValueInr() / LAKH;
                    if (s.failed()) {
                        a.failed += face;
                    } else {
                        a.settled += face;
                        if ("MANDATORY".equals(s.counterpartyClass())) {
                            a.settledMandatory += face;
                        }
                        if (s.wap() != null) {
                            a.wapNumerator += face * s.wap();
                            a.wapWeight += face;
                        }
                    }
                    a.lastSettlement = a.lastSettlement == null || day.isAfter(a.lastSettlement) ? day : a.lastSettlement;
                }
                List<io.hallmark.tradeflow.domain.Inputs.ReportedTrade> reported = reportedByDay.getOrDefault(day, List.of());
                for (var t : reported) {
                    Agg a = activity.computeIfAbsent(new Key(t.isin(), t.tradeDate()), k -> new Agg());
                    a.instrumentClass = t.instrumentClass();
                    a.reportedFace = t.totalTradeValueLakh();
                    a.reportedWap = t.wap();
                }

                for (Map.Entry<Key, Agg> e : activity.entrySet()) {
                    Key k = e.getKey();
                    if (k.tradeDate().isBefore(day.minusDays(LOOKBACK_DAYS)) || k.tradeDate().isAfter(day)) {
                        continue;
                    }
                    Agg a = e.getValue();
                    TradeFlow flow = new TradeFlow(k.isin(), k.tradeDate(), a.instrumentClass, a.reportedFace,
                            a.reportedWap, null, a.settled, a.settledMandatory, a.failed, a.settledWap(),
                            a.lastSettlement);
                    lifecycle.put(k, LifecycleRules.evaluate(flow, day));
                    settlementOutcomes.put(k, TradeSettlementRecon.reconcile(new TradeSettlementRecon.Activity(
                            k.isin(), k.tradeDate(), a.instrumentClass, a.reportedFace, a.reportedWap, a.settled,
                            a.settledMandatory, a.failed, a.settledWap(), a.lastSettlement), day));
                }

                Map.Entry<LocalDate, ZeroCurve> curveEntry = curves.floorEntry(day);
                ZeroCurve curve = curveEntry == null ? null : curveEntry.getValue();
                List<Observation> todays = new ArrayList<>();
                Map<String, TradeObservation> todaysRaw = new HashMap<>();
                for (var t : reported) {
                    Agg a = activity.get(new Key(t.isin(), t.tradeDate()));
                    Double mandatory = a == null || a.settled <= 0 ? null : a.settledMandatory / a.settled;
                    Optional<TradeObservation> obs =
                            ObservationBuilder.build(t, Optional.ofNullable(tradeflowRefs.get(t.isin())), mandatory);
                    if (obs.isEmpty()) {
                        continue;
                    }
                    todaysRaw.put(t.isin(), obs.get());
                    SecurityView view = views.get(t.isin());
                    if (obs.get().clean() && view != null && curve != null) {
                        MarkingEngine.observe(view, day, obs.get().cleanPrice(), obs.get().yieldPct(),
                                        obs.get().faceValueLakh(), "INSTITUTIONAL".equals(obs.get().sizeClass()), curve)
                                .ifPresent(todays::add);
                    }
                    var reconTrade = new io.hallmark.recon.domain.Inputs.ReportedTrade(t.isin(), t.tradeDate(),
                            t.instrumentClass(), t.totalTradeValueLakh(), t.wap(), t.way());
                    YieldRecon.reconcile(reconTrade, reconNsdl.get(t.isin())).ifPresent(o -> yields.row(
                            day, o.isin(), o.result(), o.breakType(), o.severity(), o.amountLakh(),
                            mapper.writeValueAsString(o.details())));
                }
                if (curve == null) {
                    continue;
                }

                LocalDate historyFrom = day.minusDays(HISTORY_DAYS);
                history.removeIf(o -> o.tradeDate().isBefore(historyFrom));
                history.addAll(todays);
                Map<String, Residual> residualsBefore = lastResidual.entrySet().stream()
                        .filter(e -> !e.getValue().tradeDate().isBefore(day.minusDays(markParams.carryWindowDays())))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                Optional<CreditCurveModel> before = CreditCurveModel.fit(day, day.minusDays(1), history, curveParams);
                if (before.isPresent()) {
                    for (Prediction p : MarkingEngine.backtest(day, curve, before.get(),
                            todays.stream().filter(Observation::institutional).toList(), views, residualsBefore,
                            markParams)) {
                        backtest.row(p.tradeDate(), p.isin(), p.method(), p.ratingBucket(), p.sectorGroup(),
                                p.predictedYieldPct(), p.observedYieldPct(), p.errorBps(), p.lastYieldErrorBps(),
                                p.carriedSpreadErrorBps(), p.confidence());
                    }
                }

                todays.forEach(o -> lastCleanTrade.put(o.isin(), day));
                Optional<CreditCurveModel> model = CreditCurveModel.fit(day, day, history, curveParams);
                Map<String, Double> modelSpreadToday = new HashMap<>();
                if (model.isPresent()) {
                    CreditCurveModel m = model.get();
                    List<SecurityView> universe = lastCleanTrade.entrySet().stream()
                            .filter(e -> !e.getValue().isBefore(day.minusDays(UNIVERSE_DAYS)))
                            .map(e -> views.get(e.getKey()))
                            .filter(v -> v != null)
                            .toList();
                    Map<String, List<Observation>> byIsin =
                            todays.stream().collect(Collectors.groupingBy(Observation::isin));
                    List<Observation> issuerEvidence = history.stream()
                            .filter(o -> o.issuerKey() != null && !o.tradeDate().isBefore(day.minusDays(60)))
                            .toList();
                    boolean withEvidence = !day.isBefore(days.last().minusDays(EVIDENCE_DAYS + 2L));
                    for (Mark mk : MarkingEngine.mark(day, curve, m, universe, byIsin, residualsBefore, issuerEvidence,
                            markParams)) {
                        marks.row(mk.valuationDate(), mk.isin(), mk.method(), mk.cleanPrice(), mk.dirtyPrice(),
                                mk.accrued(), mk.yieldPct(), mk.zSpreadBps(), mk.gSpreadBps(), mk.modifiedDuration(),
                                mk.tenorYears(), mk.ratingBucket(), mk.sectorGroup(), mk.modelSpreadBps(),
                                mk.confidence(), withEvidence ? mapper.writeValueAsString(mk.evidence()) : null);
                    }
                    m.curves().values().forEach(c -> fits.row(day, c.bucket(), c.level(), c.slope(), c.scaleBps(),
                            c.effectiveN(), c.source()));
                    m.sectorOffsets().forEach((g, o) -> sectors.row(day, g, o.bps(), o.weight(), o.observations()));
                    for (Observation o : todays) {
                        double ms = m.modelSpread(o.bucket(), o.sectorGroup(), o.issuerKey(), o.tenorYears());
                        modelSpreadToday.put(o.isin(), ms);
                        if (o.institutional()) {
                            lastResidual.put(o.isin(), new Residual(day, o.zSpreadBps(), ms, o.yieldPct(),
                                    o.faceValueLakh(), o.riskFreeYieldPct()));
                        }
                    }
                }
                Map<String, Observation> spreadByIsin = new HashMap<>();
                todays.forEach(o -> spreadByIsin.putIfAbsent(o.isin(), o));
                for (TradeObservation o : todaysRaw.values()) {
                    Observation s = spreadByIsin.get(o.isin());
                    observations.row(o.isin(), o.tradeDate(), o.cleanPrice(), o.yieldPct(), o.faceValueLakh(),
                            o.sizeClass(), String.join(";", o.flags()), o.recomputedYieldPct(),
                            o.settledMandatoryShare(), s == null ? null : s.zSpreadBps(),
                            s == null ? null : s.tenorYears(), s == null ? null : s.bucket(),
                            s == null ? null : s.sectorGroup(), modelSpreadToday.get(o.isin()));
                }
                log("%s: %d reported, %d spread observations", day, reported.size(), todays.size());
            }
        }

        try (Csv lc = new Csv(out.resolve("lifecycle.csv"), "isin", "trade_date", "instrument_class", "state",
                        "reported_face_lakh", "settled_face_lakh", "settled_mandatory_face_lakh", "failed_face_lakh");
                Csv recon = new Csv(out.resolve("recon_outcomes.csv"), "recon_type", "isin", "business_date",
                        "result", "break_type", "severity", "amount_lakh", "details")) {
            for (Map.Entry<Key, LifecycleState> e : lifecycle.entrySet()) {
                Agg a = activity.get(e.getKey());
                lc.row(e.getKey().isin(), e.getKey().tradeDate(), a.instrumentClass, e.getValue(), a.reportedFace,
                        a.settled, a.settledMandatory, a.failed);
            }
            List<Outcome> all = new ArrayList<>(settlementOutcomes.values());
            all.addAll(reconOutcomes);
            for (Outcome o : all) {
                recon.row(o.reconType(), o.isin(), o.businessDate(), o.result(), o.breakType(), o.severity(),
                        o.amountLakh(), mapper.writeValueAsString(o.details()));
            }
        }
        log("done in %d s", (System.currentTimeMillis() - started) / 1000);
    }

    private static void log(String format, Object... args) {
        System.out.printf(format + "%n", args);
    }

    private static final class Csv implements AutoCloseable {
        private final BufferedWriter writer;

        Csv(Path path, String... header) throws IOException {
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            row((Object[]) header);
        }

        void row(Object... values) {
            try {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < values.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    Object v = values[i];
                    if (v == null) {
                        continue;
                    }
                    String s = v.toString();
                    if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
                        sb.append('"').append(s.replace("\"", "\"\"")).append('"');
                    } else {
                        sb.append(s);
                    }
                }
                writer.write(sb.toString());
                writer.newLine();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
